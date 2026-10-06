package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal durable pub/sub capability. Callers must authorize scope access and publisher or
 * subscriber authority before invoking it. This contract grants no runtime execution capability.
 * Seen positions track availability, not successful handling; reading never advances a position.
 */
public interface Relay {
  /** Creates a topic or changes its policy; an existing payload family cannot be changed. */
  Topic configureTopic(TopicKey key, RelayPayload.Kind kind, Policy policy);

  /** Registers a default once; explicit server deployment policy may override system retention. */
  Topic registerTopic(TopicKey key, RelayPayload.Kind kind, Policy initialPolicy);

  /** Reads registered topic metadata without changing policy; an unavailable topic fails. */
  Topic topic(TopicKey key);

  /**
   * Appends atomically. A retained event ID with equal content returns the original publication;
   * conflicting reuse fails. An expired ID is no longer covered by broker deduplication.
   */
  Publication publish(TopicKey key, Draft draft);

  /**
   * Read-only receipt reconciliation by scoped event identity. Empty means not retained and cannot
   * prove an earlier publication never occurred. This method never creates or republishes work.
   */
  Optional<Publication> retained(TopicKey key, String eventId);

  /** Registers independently; repeating registration returns the existing position unchanged. */
  Subscription subscribe(SubscriptionKey key, Start start);

  /** Reads 1..1,000 retained publications after the seen position, reporting any expired gap. */
  Read read(SubscriptionKey key, int limit);

  /**
   * Advances monotonically to a retained publication position. An outstanding gap must first be
   * explicitly acknowledged. A repeated or older advance leaves the position and timestamp intact.
   */
  Subscription advanceSeen(SubscriptionKey key, long position);

  /**
   * Acknowledges an exact expired boundary. A changed boundary fails rather than silently
   * acknowledging additional loss. Repeating a previously applied acknowledgement is harmless.
   */
  Subscription acknowledgeGap(SubscriptionKey key, long expiredThrough);

  /** Counts retained unread publications, separately reporting any expired gap. */
  Unread unread(SubscriptionKey key);

  enum Start {
    OLDEST_RETAINED,
    LATEST
  }

  /** System publications have an explicit scope, never a fabricated project identity. */
  sealed interface Scope permits ProjectScope, SystemScope {}

  record ProjectScope(long projectId) implements Scope {
    public ProjectScope {
      if (projectId < 1) throw new IllegalArgumentException("project ID must be positive");
    }
  }

  /** Internal server-owned topics; project configuration cannot grant access to this scope. */
  enum SystemScope implements Scope {
    SERVER
  }

  record TopicKey(Scope scope, String name) {
    public TopicKey(long projectId, String name) {
      this(new ProjectScope(projectId), name);
    }

    public TopicKey {
      Objects.requireNonNull(scope, "scope");
      name = RelayValues.name(name, "topic");
    }

    /** For project-only adapters; system scope cannot be coerced to an ID. */
    public long projectId() {
      if (scope instanceof ProjectScope project) return project.projectId();
      throw new IllegalArgumentException("system Relay topics have no project identity");
    }
  }

  record SubscriptionKey(TopicKey topic, String subscriber) {
    public SubscriptionKey {
      Objects.requireNonNull(topic, "topic");
      subscriber = RelayValues.name(subscriber, "subscriber");
    }
  }

  /** Four days by default; whole seconds up to 3,650 days and an optional retained-record cap. */
  record Policy(Duration retention, Long maxRecords) {
    public Policy {
      Objects.requireNonNull(retention, "retention");
      if (retention.isZero()
          || retention.isNegative()
          || retention.getNano() != 0
          || retention.compareTo(Duration.ofDays(3650)) > 0)
        throw new IllegalArgumentException("retention must be 1 second through 3650 days");
      if (maxRecords != null && maxRecords < 1)
        throw new IllegalArgumentException("maxRecords must be positive");
    }

    public static Policy systemDefault() {
      return new Policy(Duration.ofDays(4), null);
    }
  }

  record Topic(
      TopicKey key,
      RelayPayload.Kind kind,
      Policy policy,
      long lastPosition,
      long expiredThrough,
      java.util.UUID generation) {
    public Topic(
        TopicKey key,
        RelayPayload.Kind kind,
        Policy policy,
        long lastPosition,
        long expiredThrough) {
      this(key, kind, policy, lastPosition, expiredThrough, null);
    }

    public Topic {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(policy, "policy");
      if (expiredThrough < 0 || lastPosition < expiredThrough)
        throw new IllegalArgumentException("invalid topic positions");
      boolean nativeWake =
          key.scope() == SystemScope.SERVER
              && java.util.Set.of("message.wake.requested", "board.wake.requested")
                  .contains(key.name());
      if (nativeWake != (kind == RelayPayload.Kind.WAKE_REQUESTED))
        throw new IllegalArgumentException(
            "Native wake families require their private system topics");
      if (key.name().equals("schedule.due") != (kind == RelayPayload.Kind.SCHEDULE_DUE))
        throw new IllegalArgumentException("schedule.due requires its registered payload family");
    }
  }

  /** Publisher identities are derived by trusted adapters. Times are normalized to microseconds. */
  record Draft(
      String eventId,
      String publisher,
      Instant occurredAt,
      String correlationId,
      String causationId,
      RelayPayload payload,
      RelayCausation causation) {
    public Draft(
        String eventId,
        String publisher,
        Instant occurredAt,
        String correlationId,
        String causationId,
        RelayPayload payload) {
      this(eventId, publisher, occurredAt, correlationId, causationId, payload, null);
    }

    public Draft {
      eventId = RelayValues.identity(eventId, "event ID");
      publisher = RelayValues.identity(publisher, "publisher");
      occurredAt = RelayValues.time(occurredAt);
      if (correlationId != null)
        correlationId = RelayValues.identity(correlationId, "correlation ID");
      if (causationId != null) causationId = RelayValues.identity(causationId, "causation ID");
      Objects.requireNonNull(payload, "payload");
    }
  }

  /** Schema version one is owned by the explicit payload codec, not supplied by publishers. */
  record Publication(TopicKey topic, long position, Instant publishedAt, Draft event) {
    public Publication {
      Objects.requireNonNull(topic, "topic");
      if (position < 1) throw new IllegalArgumentException("publication position must be positive");
      publishedAt = RelayValues.time(publishedAt);
      Objects.requireNonNull(event, "event");
    }

    public int schemaVersion() {
      return 1;
    }
  }

  record Subscription(
      SubscriptionKey key, long seenThrough, Instant seenAt, java.util.UUID generation) {
    public Subscription(SubscriptionKey key, long seenThrough, Instant seenAt) {
      this(key, seenThrough, seenAt, null);
    }

    public Subscription {
      Objects.requireNonNull(key, "key");
      if (seenThrough < 0) throw new IllegalArgumentException("seen position must not be negative");
      seenAt = RelayValues.time(seenAt);
    }
  }

  /**
   * Positions in (afterExclusive, throughInclusive] have expired before this subscriber saw them.
   */
  record Gap(long afterExclusive, long throughInclusive) {
    public Gap {
      if (afterExclusive < 0 || throughInclusive <= afterExclusive)
        throw new IllegalArgumentException("invalid expired gap");
    }
  }

  record Read(Subscription subscription, Optional<Gap> gap, List<Publication> publications) {
    public Read {
      Objects.requireNonNull(subscription, "subscription");
      Objects.requireNonNull(gap, "gap");
      publications = List.copyOf(publications);
    }
  }

  record Unread(Subscription subscription, long count, Optional<Gap> gap) {
    public Unread {
      Objects.requireNonNull(subscription, "subscription");
      if (count < 0) throw new IllegalArgumentException("unread count must not be negative");
      Objects.requireNonNull(gap, "gap");
    }
  }
}
