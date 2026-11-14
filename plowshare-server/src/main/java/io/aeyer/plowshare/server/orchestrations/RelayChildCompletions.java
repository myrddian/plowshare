package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One leased group per broker scope. Notices announce availability; terminal owning rows retain
 * pending reports across busy parents, outages, expired publications and stale terminal attempts.
 */
public final class RelayChildCompletions implements ChildCompletionConsumer, AutoCloseable {
  private static final Duration LEASE = Duration.ofMinutes(2);
  private final Relay relay;
  private final RelayConsumerRepository consumers;
  private final ChildCompletions repository;
  private final ChildCompletionDelivery delivery;
  private final UnitOfWork transactions;
  private final ScheduledExecutorService renewals =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "child-completion-lease");
            thread.setDaemon(true);
            return thread;
          });

  public RelayChildCompletions(
      Relay relay,
      RelayConsumerRepository consumers,
      ChildCompletions repository,
      ChildCompletionDelivery delivery,
      UnitOfWork transactions) {
    this.relay = Objects.requireNonNull(relay);
    this.consumers = Objects.requireNonNull(consumers);
    this.repository = Objects.requireNonNull(repository);
    this.delivery = Objects.requireNonNull(delivery);
    this.transactions = Objects.requireNonNull(transactions);
  }

  /** One bounded pass. A competing live consumer does no reads or delivery. */
  public void consume(long project, int limit) {
    if (project < 0 || limit < 1 || limit > 256)
      throw new IllegalArgumentException("Invalid completion pass");
    var topic =
        new Relay.TopicKey(
            project == 0 ? Relay.SystemScope.SERVER : new Relay.ProjectScope(project),
            ChildCompletions.TOPIC);
    relay.registerTopic(topic, RelayPayload.Kind.LIFECYCLE, Relay.Policy.systemDefault());
    var subscription = new Relay.SubscriptionKey(topic, ChildCompletions.GROUP);
    relay.subscribe(subscription, Relay.Start.OLDEST_RETAINED);
    var acquired =
        consumers.acquire(
            subscription, "child-completion:" + UUID.randomUUID(), ChildCompletions.GROUP, LEASE);
    if (acquired.isEmpty()) return;
    var ownership = new Ownership(acquired.get());
    ScheduledFuture<?> renewal = null;
    try {
      renewal = renewals.scheduleWithFixedDelay(ownership::renew, 30, 30, TimeUnit.SECONDS);
      ownership.check();
      var read = relay.read(subscription, limit);
      var attempted = new HashSet<ChildCompletions.Completion>();
      boolean recovered = read.gap().isPresent();
      if (recovered) {
        recover(ownership, limit, attempted);
        ownership.gap(read.gap().get().throughInclusive());
        read = relay.read(subscription, limit);
      }
      var notices = new ArrayList<ChildCompletions.Completion>();
      for (var publication : read.publications()) {
        var event = publication.event();
        if (event.publisher().equals("system.source")
            && event.payload() instanceof RelayPayload.Lifecycle notice
            && notice.source().equals(ChildCompletions.TOPIC)
            && notice.related() != null
            && Set.of("finished", "failed", "capped", "cancelled").contains(notice.state())) {
          notices.add(
              new ChildCompletions.Completion(
                  project, notice.subject(), notice.related(), event.occurredAt()));
        }
      }
      for (var completion : ownership.current(notices))
        if (attempted.add(completion)) delivery.childCompleted(completion, ownership::check);
      for (var publication : read.publications()) ownership.advance(publication.position());
      // Availability offsets never settle a report. Recovery excludes still-unpublished outbox
      // entries and revisits only durable pending attempts, including busy or asking parents.
      if (!recovered) recover(ownership, limit, attempted);
    } finally {
      if (renewal != null) renewal.cancel(false);
      ownership.release();
    }
  }

  private void recover(Ownership ownership, int limit, Set<ChildCompletions.Completion> attempted) {
    for (var completion : ownership.pending(limit)) {
      ownership.check();
      if (attempted.add(completion)) delivery.childCompleted(completion, ownership::check);
    }
  }

  /**
   * Renewal and epoch-sensitive checks serialize locally; no caller effect runs under this lock.
   */
  private final class Ownership {
    private RelayConsumerRepository.Lease lease;
    private final AtomicBoolean failed = new AtomicBoolean();

    Ownership(RelayConsumerRepository.Lease lease) {
      this.lease = lease;
    }

    synchronized void renew() {
      if (failed.get()) return;
      try {
        var renewed =
            consumers.acquire(lease.subscription(), lease.worker(), lease.account(), LEASE);
        if (renewed.isEmpty()) failed.set(true);
        else lease = renewed.get();
      } catch (RuntimeException lost) {
        failed.set(true);
      }
    }

    synchronized void check() {
      if (failed.get() || Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Child completion consumer ownership lost or interrupted");
      transactions.inTransaction(
          () -> {
            consumers.requireOwned(lease);
            return true;
          });
    }

    synchronized List<ChildCompletions.Completion> pending(int limit) {
      check();
      return repository.pending(lease, limit);
    }

    synchronized List<ChildCompletions.Completion> current(
        List<ChildCompletions.Completion> notices) {
      check();
      return repository.current(lease, notices);
    }

    synchronized void advance(long position) {
      check();
      consumers.advanceSeen(lease, position);
    }

    synchronized void gap(long boundary) {
      check();
      consumers.acknowledgeGap(lease, boundary);
    }

    synchronized void release() {
      failed.set(true);
      consumers.release(lease);
    }
  }

  @Override
  public void close() {
    renewals.shutdownNow();
  }
}
