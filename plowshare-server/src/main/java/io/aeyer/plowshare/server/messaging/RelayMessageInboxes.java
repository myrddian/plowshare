package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessageService.Route;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayRetirementRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Atomic record-to-TEXT publication and leased TEXT-to-native-wake admission. */
public final class RelayMessageInboxes implements MessageInboxes {
  private static final Logger LOG = LoggerFactory.getLogger(RelayMessageInboxes.class);
  private final MessagingRepository messages;
  private final MessageInboxRepository inboxes;
  private final MessagePublications publications;
  private final Relay relay;
  private final RelayConsumerRepository consumers;
  private final FiringStore firings;
  private final UnitOfWork work;
  private final Consumer<String> drain;
  private final Supplier<Instant> clock;
  private final String worker = "messaging:" + UUID.randomUUID();
  private MessageInboxRepository.Cursor after;
  private MessageInboxRepository.Cursor retirementAfter;
  private MessageInboxRepository.Cursor cleanupAfter;
  private final RelayRetirementRepository retirement;
  private final Consumer<MessageInboxRepository.Inbox> signals;
  private final AtomicBoolean polling = new AtomicBoolean();

  public RelayMessageInboxes(
      MessagingRepository messages,
      MessageInboxRepository inboxes,
      MessagePublications publications,
      Relay relay,
      RelayConsumerRepository consumers,
      FiringStore firings,
      UnitOfWork work,
      Consumer<String> drain,
      Supplier<Instant> clock,
      RelayRetirementRepository retirement) {
    this(
        messages,
        inboxes,
        publications,
        relay,
        consumers,
        firings,
        work,
        drain,
        clock,
        retirement,
        inbox -> {});
  }

  public RelayMessageInboxes(
      MessagingRepository messages,
      MessageInboxRepository inboxes,
      MessagePublications publications,
      Relay relay,
      RelayConsumerRepository consumers,
      FiringStore firings,
      UnitOfWork work,
      Consumer<String> drain,
      Supplier<Instant> clock,
      RelayRetirementRepository retirement,
      Consumer<MessageInboxRepository.Inbox> signals) {
    this.signals = Objects.requireNonNull(signals);
    this.messages = Objects.requireNonNull(messages);
    this.inboxes = Objects.requireNonNull(inboxes);
    this.publications = Objects.requireNonNull(publications);
    this.relay = Objects.requireNonNull(relay);
    this.consumers = Objects.requireNonNull(consumers);
    this.firings = Objects.requireNonNull(firings);
    this.work = Objects.requireNonNull(work);
    this.drain = Objects.requireNonNull(drain);
    this.clock = Objects.requireNonNull(clock);
    this.retirement = Objects.requireNonNull(retirement);
  }

  private MessageInboxRepository.Inbox ensure(Instance instance) {
    String name =
        "messaging.agent."
            + RelayPort.hash(instance.account())
            + "."
            + RelayPort.hash(instance.agent());
    var inbox = inboxes.retain(instance.account(), instance.project(), instance.agent(), name);
    relay.registerTopic(
        inbox.subscription().topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    relay.subscribe(inbox.subscription(), Relay.Start.OLDEST_RETAINED);
    return inbox;
  }

  @Override
  public MessageInboxRepository.Inbox publish(
      MessageRecord record,
      Route route,
      Instance sender,
      Instance recipient,
      MessagePublications.Reply reply,
      RelayCausation causation) {
    if (!record.id().equals(route.message())
        || !record.recipient().equals(recipient.id())
        || !route.sender().equals(sender.id())
        || !route.recipient().equals(recipient.id()))
      throw new IllegalArgumentException("Message differs from its authorized publication intent");
    MessageInboxRepository.Inbox source;
    MessageInboxRepository.Inbox destination;
    String sourceKey = sender.account() + "\u0000" + sender.project() + "\u0000" + sender.agent();
    String destinationKey =
        recipient.account() + "\u0000" + recipient.project() + "\u0000" + recipient.agent();
    // Opposite-direction sends lock shared agent bindings in the same order. Instance IDs never
    // participate in topic naming: task and persistent instances reuse the same agent mailbox.
    if (sourceKey.equals(destinationKey)) {
      source = ensure(sender);
      destination = source;
    } else if (sourceKey.compareTo(destinationKey) < 0) {
      source = ensure(sender);
      destination = ensure(recipient);
    } else {
      destination = ensure(recipient);
      source = ensure(sender);
    }
    var binding =
        new MessagePublications.Binding(
            destination.subscription().topic(),
            participant(sender, source),
            participant(recipient, destination),
            reply);
    MessagePayload.Body body =
        route.generated()
            ? new MessagePayload.Retained(record.id())
            : new MessagePayload.Inline(record.body());
    publications.publish(
        binding,
        new MessagePayload(
            record.id(),
            sender.id(),
            recipient.id(),
            body,
            route.replyTo(),
            recipient.conversation(),
            route.replyExpected(),
            route.finalReply()),
        record.postedAt(),
        causation);
    messages.published(record.id());
    return destination;
  }

  private static MessagePublications.Participant participant(
      Instance instance, MessageInboxRepository.Inbox inbox) {
    return new MessagePublications.Participant(
        instance.id(),
        instance.account(),
        inbox.subscription().topic().projectId(),
        instance.conversation());
  }

  @Override
  public void admitSoon(MessageInboxRepository.Inbox inbox) {
    signals.accept(Objects.requireNonNull(inbox));
  }

  @Override
  public int poll(MessageInboxRepository.Inbox inbox) {
    if (!polling.compareAndSet(false, true)) return 0;
    try {
      return admit(Objects.requireNonNull(inbox));
    } finally {
      polling.set(false);
    }
  }

  /** Discovery state is local and bounded; subscription leases and cursors are the authority. */
  @Override
  public int poll() {
    if (!polling.compareAndSet(false, true)) return 0;
    try {
      return pass();
    } finally {
      polling.set(false);
    }
  }

  @Override
  public int retireMissing(MessageDefinitions definitions) {
    if (!polling.compareAndSet(false, true)) return 0;
    try {
      var candidates = inboxes.drained(retirementAfter, 32);
      if (candidates.isEmpty()) {
        retirementAfter = null;
        return 0;
      }
      record Scope(String account, String project) {}
      var inventories = new HashMap<Scope, Set<String>>();
      int retired = 0;
      for (var inbox : candidates) {
        retirementAfter = MessageInboxRepository.Cursor.after(inbox);
        // Passive external identities are adapter records, not project definition files.
        if (inbox.agent().startsWith("external:")) continue;
        boolean complete =
            observed(
                inbox,
                "RETIREMENT_FAILED",
                () -> {
                  var names =
                      inventories.computeIfAbsent(
                          new Scope(inbox.account(), inbox.project()),
                          scope -> Set.copyOf(definitions.names(scope.account(), scope.project())));
                  return !names.contains(inbox.agent())
                      && work.inTransaction(() -> inboxes.retire(inbox));
                });
        if (complete) retired++;
      }
      return retired;
    } finally {
      polling.set(false);
    }
  }

  @Override
  public int cleanupRetired() {
    if (!polling.compareAndSet(false, true)) return 0;
    try {
      var candidates = inboxes.retired(cleanupAfter, 32);
      if (candidates.isEmpty()) {
        cleanupAfter = null;
        return 0;
      }
      int removed = 0;
      for (var inbox : candidates) {
        cleanupAfter = MessageInboxRepository.Cursor.after(inbox);
        boolean complete =
            observed(
                inbox,
                "CLEANUP_FAILED",
                () ->
                    work.inTransaction(
                        () -> {
                          // Binding before topic is also publication's lock order. Reactivation
                          // wins before
                          // this
                          // check or waits until cleanup commits, then creates a fresh broker
                          // incarnation.
                          if (!inboxes.lockRetired(inbox)
                              || !retirement.retireOwned(inbox.subscription())) return false;
                          inboxes.forgetRetired(inbox);
                          return true;
                        }));
        if (complete) removed++;
      }
      return removed;
    } finally {
      polling.set(false);
    }
  }

  private int pass() {
    var ready = inboxes.ready(after, 32);
    if (ready.isEmpty()) {
      after = null;
      return 0;
    }
    int admitted = 0;
    RuntimeException firstFailure = null;
    for (var inbox : ready) {
      after = MessageInboxRepository.Cursor.after(inbox);
      try {
        admitted += admit(inbox);
      } catch (RuntimeException failure) {
        // Each admission owns its transaction and retained receipt. Preserve the first failure
        // while
        // finishing this bounded discovery page; a poisoned inbox cannot starve its neighbours.
        if (firstFailure == null) firstFailure = failure;
      }
    }
    if (firstFailure != null) throw firstFailure;
    return admitted;
  }

  private int admit(MessageInboxRepository.Inbox inbox) {
    return observed(inbox, "ADMISSION_FAILED", () -> admitOwned(inbox));
  }

  private <T> T observed(
      MessageInboxRepository.Inbox inbox, String category, Supplier<T> operation) {
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      // Exception messages/causes can contain payload or SQL values. These validated coordinates
      // locate the retained position without disclosing the request body or credential material.
      // A release or notification can fail after commit, so failure alone is not rollback evidence.
      LOG.warn(
          "Messaging failure category={} project={} topic={} agent={}; inspect retained position and native receipt before recovery",
          category,
          inbox.project(),
          inbox.subscription().topic().name(),
          inbox.agent());
      throw failure;
    }
  }

  private int admitOwned(MessageInboxRepository.Inbox inbox) {
    var lease =
        consumers.acquire(inbox.subscription(), worker, inbox.account(), Duration.ofSeconds(30));
    if (lease.isEmpty()) return 0;
    try {
      return work.inTransaction(
          () -> {
            consumers.requireOwned(lease.get());
            var batch = relay.read(inbox.subscription(), 32);
            if (batch.gap().isPresent())
              throw new IllegalStateException(
                  "Messaging inbox retention gap requires explicit recovery");
            var decodedBatch =
                batch.publications().stream()
                    .map(
                        publication ->
                            new Decoded(
                                publication, publications.read(inbox.account(), publication)))
                    .toList();
            var snapshots =
                messages.admissions(
                    decodedBatch.stream()
                        .flatMap(value -> value.payload().stream())
                        .map(
                            payload ->
                                new MessagingRepository.AdmissionRequest(
                                    payload.message(),
                                    payload.body() instanceof MessagePayload.Inline))
                        .toList());
            int count = 0;
            for (var next : decodedBatch) {
              var publication = next.publication();
              var decoded = next.payload();
              if (decoded.isPresent()) {
                var payload = decoded.get();
                var snapshot =
                    Objects.requireNonNull(
                        snapshots.get(payload.message()), "Missing retained messaging admission");
                var recipient = snapshot.recipient();
                var route = snapshot.route();
                if (!inbox.account().equals(recipient.account())
                    || !inbox.project().equals(recipient.project())
                    || !inbox.agent().equals(recipient.agent())
                    || !payload.conversation().equals(recipient.conversation())
                    || !route.sender().equals(payload.sender())
                    || !route.recipient().equals(payload.recipient())
                    || !Objects.equals(route.causation(), publication.event().causation())
                    || !Objects.equals(route.replyTo(), payload.replyTo())
                    || route.replyExpected() != payload.replyExpected()
                    || route.finalReply() != payload.finalReply()
                    || payload.body() instanceof MessagePayload.Inline inline
                        && !snapshot.inlineBody().filter(inline.text()::equals).isPresent())
                  throw new IllegalStateException(
                      "Messaging publication conflicts with its retained route");
                if (messages.admit(payload.message())) {
                  String target = target(recipient);
                  firings
                      .oweMessage(
                          recipient.id(),
                          target,
                          new EventPayload.Message(new MessageWake(payload.message())),
                          clock.get(),
                          publication.position())
                      .orElseThrow();
                  work.afterCommit(() -> drain.accept(target));
                  count++;
                }
              }
              consumers.advanceSeen(lease.get(), publication.position());
            }
            return count;
          });
    } finally {
      consumers.release(lease.get());
    }
  }

  private record Decoded(Relay.Publication publication, Optional<MessagePayload> payload) {}

  public static String target(Instance instance) {
    return "conversation:" + instance.conversation() + ":instance:" + instance.id();
  }
}
