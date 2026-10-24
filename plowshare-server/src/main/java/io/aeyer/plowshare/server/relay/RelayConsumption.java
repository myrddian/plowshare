package io.aeyer.plowshare.server.relay;

import java.time.Duration;

/**
 * Read-tier subscription and consumption (decision 0038).
 *
 * <p>Subscribing and consuming require <b>read authority on the topic</b> — the same authority that
 * already governs log inspection — and never write authority on the project. Writing a consumer's
 * own lease or offset is authorized by subscriber identity plus that read authority: a lease row is
 * keyed {@code (scope, topic, subscriber)} and carries the holding account, and nothing in it
 * describes project content, so a caller may write only the row its own subscription holds.
 *
 * <p>What this contract does <b>not</b> do is as much of its definition as what it does. It cannot
 * create, register or configure a topic; it cannot publish; it cannot open a port or dispatch to a
 * receiver. Those are the work and manage tiers and keep their own checks, so admitting a read-tier
 * account here admits it to subscription and consumption and to nothing else.
 *
 * <p>Authority is resolved once, when the subscription is established, and is not re-resolved per
 * publication. Every operation after {@link #subscribe} requires the subscription to still be valid
 * — see {@link RelaySubscriptionAuthority} — so revocation takes effect through invalidation, with
 * a configured maximum age only bounding the failure of invalidation.
 */
public interface RelayConsumption {
  /**
   * Establishes a subscription owned by this account on an existing topic and records its read
   * authority over it. Repeating it re-establishes the authority record, which is how a lapsed
   * subscription is renewed; the stored cursor is untouched.
   *
   * <p>The subscriber name must be this account's own or unused. A work-tier, project-declared
   * subscriber cannot be adopted: its cursor is shared state whose owner would skip events if
   * someone else advanced it.
   *
   * @throws io.aeyer.plowshare.server.faults.CallerFault if the account may not read the topic's
   *     project, the topic does not exist — a read-tier caller may not bring a topic into being —
   *     or the subscriber name belongs to the work tier or another reader
   */
  Relay.Subscription subscribe(String account, Request request, Relay.Start start);

  /**
   * Reads at most {@code limit} (1..1,000) retained publications after the seen position, reporting
   * any expired gap. Reading never advances a position, so it writes no consumer state.
   *
   * @throws io.aeyer.plowshare.server.faults.CallerFault if the account may not read the topic, or
   *     the subscription is no longer valid
   */
  Relay.Read consume(String account, Request request, int limit);

  /**
   * Advances the subscription's own seen position under a lease this caller holds, monotonically
   * and only to a retained position. The lease is required, not a convenience: the position write
   * and the ownership check commit in one transaction, so an absent or lapsed lease is never an
   * opening to move a position — least of all another subscriber's.
   *
   * @throws io.aeyer.plowshare.server.faults.CallerFault if the account may not read the topic, the
   *     subscription is no longer valid, or the lease is not this caller's live lease on it
   */
  Relay.Subscription advance(
      String account, Request request, RelayConsumerRepository.Lease lease, long position);

  /**
   * Takes or extends the caller's own lease on the subscription for exclusive consumption. A lease
   * held live by another owner is refused rather than stolen; an expired one may be taken over,
   * which is how a crashed consumer's subscription recovers. {@code duration} is bounded by the
   * broker's lease limits and an out-of-range value is an {@link IllegalArgumentException}, not a
   * caller fault: it is a programming error in the consumer, not a denied request.
   *
   * @throws io.aeyer.plowshare.server.faults.CallerFault if the account may not read the topic, the
   *     subscription is no longer valid, or the lease is held live by another owner
   */
  RelayConsumerRepository.Lease lease(String account, Request request, Duration duration);

  /** Releases a lease this caller holds. A stale release cannot disturb its successor. */
  void release(String account, Request request, RelayConsumerRepository.Lease lease);

  /**
   * The addressed subscription. Project and topic names are validated, never trusted.
   *
   * <p>{@code subscriber} is the caller's <b>logical</b> consumer-group name, not a stored group
   * identity. The server prefixes it with the authenticated account, so the same logical name used
   * by two accounts is two independent groups with independent committed offsets, and no caller can
   * name the project's work-tier pipeline group or another reader's.
   */
  record Request(String project, String topic, String subscriber) {
    public Request {
      project = RelayValues.identity(project, "project");
      topic = RelayValues.topic(topic);
      subscriber = RelayValues.name(subscriber, "subscriber");
    }
  }
}
