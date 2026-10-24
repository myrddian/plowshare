package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.time.Instant;

/**
 * Durable record of when a subscription's read authority was resolved, and whether that resolution
 * is still valid (decision 0038).
 *
 * <p>Authority is established once, when the subscription is established, and is never re-resolved
 * per publication. Validity therefore has two independent conditions and both must hold:
 *
 * <ul>
 *   <li>the topic's incarnation has not moved since establishment — explicit invalidation, which is
 *       the primary mechanism;
 *   <li>the record is no older than the supplied maximum age — the fallback, which exists only to
 *       bound the failure of invalidation and is deployment configuration rather than a constant.
 * </ul>
 *
 * <p>Records are keyed by account as well as by subscription: a project-declared subscriber is
 * shared, so each consuming account establishes its own authority. Establishing a record asserts
 * nothing about authority on its own — the caller must have checked topic read authority first, and
 * this contract records that check rather than performing it.
 *
 * <p>Revocation is not this contract's only entry point. Removing a subscription or its topic
 * removes the records against it, and a project membership change withdraws that account's records
 * in the same transaction as the change.
 */
public interface RelaySubscriptionAuthority {
  /**
   * Records that this account's read authority over the subscription's topic was resolved at {@code
   * now}, replacing any earlier record for the same account. The subscription and its topic must
   * already exist; the topic's current incarnation is captured as the record's generation.
   *
   * @throws IllegalStateException if the subscription or its topic does not exist
   */
  Established establish(Relay.SubscriptionKey subscription, String account, Instant now);

  /**
   * Validity of an established record. {@code maximumAge} must be positive. A record for an unknown
   * subscription, topic or account is {@link Validity#NOT_ESTABLISHED} rather than an error: a
   * caller that never established authority is indistinguishable from one whose record was
   * withdrawn, and both must be refused the same way.
   */
  Validity validate(
      Relay.SubscriptionKey subscription, String account, Duration maximumAge, Instant now);

  /**
   * The accounts whose authority over this topic is established and <b>still valid</b> — every one
   * of them, and nothing else.
   *
   * <p>This is the audience of a topic as Relay records it, and it exists because a wake hint is a
   * disclosure: telling an account that something changed names a revision and a position to it. An
   * account whose authority has lapsed by any of the three routes must stop learning that, so this
   * applies the same {@code relay_subscription_authority_validity} definition {@link #validate}
   * does, in one set-based read. It is deliberately not "every subscriber row on the topic": a
   * subscription row survives revocation, and only its authority record does not.
   *
   * <p>It resolves the audience once per call, never a permission predicate per candidate account —
   * that shape cost 3,996 ms for a single information event and is what decision 0037 removed.
   *
   * <p>{@code maximumAge} must be positive. The result is ordered by account and bounded only by
   * the topic's established subscriptions, which is a meaningful bound rather than an arbitrary
   * one: an account appears here because it subscribed and its authority still holds.
   */
  java.util.List<String> validReaders(Relay.TopicKey topic, Duration maximumAge, Instant now);

  /**
   * Invalidates every record against this topic by moving the topic's incarnation, so no subsequent
   * validation of an existing record can succeed. The topic must exist.
   */
  void invalidateTopic(Relay.TopicKey topic);

  /**
   * Withdraws one account's records throughout a scope, which is the shape a read-authority change
   * takes. Returns the number of records withdrawn.
   */
  int invalidateAccount(Relay.Scope scope, String account);

  /** An established record: the generation it was bound to and when it was established. */
  record Established(
      Relay.SubscriptionKey subscription,
      String account,
      java.util.UUID topicGeneration,
      Instant establishedAt) {
    public Established {
      java.util.Objects.requireNonNull(subscription, "subscription");
      account = RelayValues.identity(account, "subscription authority account");
      java.util.Objects.requireNonNull(topicGeneration, "topicGeneration");
      establishedAt = RelayValues.time(establishedAt);
    }
  }

  /** Why a subscription is or is not valid. Only {@link #VALID} permits consumption. */
  enum Validity {
    VALID,
    /** No record for this account, or it was withdrawn. */
    NOT_ESTABLISHED,
    /** The topic's incarnation moved: explicit invalidation. */
    GENERATION_CHANGED,
    /** The record is older than the configured maximum age. */
    EXPIRED;

    public boolean valid() {
      return this == VALID;
    }
  }
}
