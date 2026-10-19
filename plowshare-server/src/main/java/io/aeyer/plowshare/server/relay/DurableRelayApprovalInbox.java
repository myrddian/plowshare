package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.approvals.ApprovalNotifications;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * approval.requested is an availability notice, not permission to execute. The owning approval
 * remains the recovery source until answered, and Inbox admission is idempotent. Consequently a
 * lost push, expired Relay publication or failure after Inbox commit cannot swallow a decision or
 * replay a command. Volatile scan cursors rotate failed recipients; no pending work lives in them.
 */
public final class DurableRelayApprovalInbox implements RelayApprovalInbox {
  private static final Logger LOG = LoggerFactory.getLogger(DurableRelayApprovalInbox.class);
  private final Relay relay;
  private final RelayConsumerRepository consumers;
  private final RelayApprovalInboxRepository repository;
  private final ApprovalNotifications notifications;
  private final ConcurrentHashMap<Relay.TopicKey, String> cursors = new ConcurrentHashMap<>();

  public DurableRelayApprovalInbox(
      Relay relay,
      RelayConsumerRepository consumers,
      RelayApprovalInboxRepository repository,
      ApprovalNotifications notifications) {
    this.relay = Objects.requireNonNull(relay);
    this.consumers = Objects.requireNonNull(consumers);
    this.repository = Objects.requireNonNull(repository);
    this.notifications = Objects.requireNonNull(notifications);
  }

  @Override
  public List<Relay.TopicKey> ready(String after, int limit) {
    return repository.ready(after, limit);
  }

  @Override
  public void consume(Relay.TopicKey topic, int limit) {
    if (!(topic.scope() instanceof Relay.ProjectScope scope)
        || !List.of("approval.requested", "approval.resolved").contains(topic.name()))
      throw new IllegalArgumentException("Approval notifications require their project topic");
    RelayValues.limit(limit, 1000);
    var subscription = new Relay.SubscriptionKey(topic, RelayApprovalInboxRepository.SUBSCRIBER);
    relay.subscribe(subscription, Relay.Start.OLDEST_RETAINED);
    // This internal lease label coordinates notification workers; it is not an authenticated
    // user, a grant or an accounting principal. The owning approval resolves every recipient.
    var acquired =
        consumers.acquire(
            subscription,
            "approval-inbox:" + UUID.randomUUID(),
            "builtin.approval-inbox",
            Duration.ofMinutes(2));
    if (acquired.isEmpty()) return;
    var lease = acquired.get();
    try {
      // Reconcile retained decisions even after a missed notification, a restart, a legacy
      // delivered_at marker without an Inbox item, or Relay retention. One bad recipient cannot
      // prevent another pending decision from reaching its own account.
      boolean requested = topic.name().equals("approval.requested");
      var missing =
          requested
              ? repository.missing(scope.projectId(), cursors.get(topic), limit)
              : repository.unsettledNotices(scope.projectId(), cursors.get(topic), limit);
      for (String id : missing) deliver(id, scope.projectId(), requested);
      if (missing.size() < limit) cursors.remove(topic);
      else cursors.put(topic, missing.getLast());
      var read = relay.read(subscription, limit);
      if (read.gap().isPresent()) {
        repository.recoverGap(lease, read.gap().get().throughInclusive());
        read = relay.read(subscription, limit);
      }
      for (var publication : read.publications()) {
        if (!(publication.event().payload() instanceof RelayPayload.Lifecycle event)
            || !event.source().equals(topic.name()))
          throw new IllegalStateException("Approval topic contains a different lifecycle payload");
        deliver(event.subject(), scope.projectId(), requested);
        // This acknowledges availability only. Failed Inbox delivery remains discoverable by
        // the owning pending row, independently of this offset and publication retention.
        repository.advance(lease, publication.position());
      }
    } finally {
      consumers.release(lease);
    }
  }

  private void deliver(String approval, long projectId, boolean requested) {
    try {
      if (requested) notifications.requested(approval, projectId);
      else notifications.resolved(approval, projectId);
    } catch (RuntimeException failed) {
      // Do not expose the command, paths, credentials or arbitrary exception text. Independent
      // pending-source discovery will retry notification, never an execution or approval answer.
      LOG.warn("Approval {} Inbox notification failed; retained for retry", approval);
    }
  }
}
