package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Specialist persistence contract. Each operation is atomic, joins an owning domain transaction
 * when present, and scopes all state by explicit scope/topic. No dispatch or remote effect occurs
 * here.
 */
public interface RelayRepository {
  /** Persists the policy under the topic lock, preserving the family and all positions. */
  Relay.Topic configureTopic(Relay.TopicKey key, RelayPayload.Kind kind, Relay.Policy policy);

  /** Atomic default registration; existing policy remains unchanged under concurrent publishers. */
  Relay.Topic registerTopic(Relay.TopicKey key, RelayPayload.Kind kind, Relay.Policy initialPolicy);

  /** Coherent metadata for an existing topic, without registration or policy changes. */
  Relay.Topic topic(Relay.TopicKey key);

  /** Allocates and appends in one transaction; see {@link Relay#publish} for identity semantics. */
  Relay.Publication append(Relay.TopicKey key, Relay.Draft draft, Instant publishedAt);

  /** Coherent read of a retained scoped event; absence is inconclusive after log retention. */
  Optional<Relay.Publication> retained(Relay.TopicKey key, String eventId);

  /** Atomically registers an explicit baseline, without resetting an existing subscription. */
  Relay.Subscription subscribe(Relay.SubscriptionKey key, Relay.Start start, Instant now);

  /** Returns a coherent bounded log/cursor snapshot; no position or timestamp changes. */
  Relay.Read read(Relay.SubscriptionKey key, int limit);

  /** Monotonic availability acknowledgement; see {@link Relay#advanceSeen} for gap handling. */
  Relay.Subscription advanceSeen(Relay.SubscriptionKey key, long position, Instant now);

  /** Acknowledges only the current exact expired boundary; no retained event is marked seen. */
  Relay.Subscription acknowledgeGap(Relay.SubscriptionKey key, long expiredThrough, Instant now);

  /** Returns retained unread count and expired gap from the same coherent snapshot. */
  Relay.Unread unread(Relay.SubscriptionKey key);

  /** At most 100 topics with an expired oldest record or excess records, oldest first. */
  List<Relay.TopicKey> topicsToPrune(Instant now, int limit);

  /**
   * Removes at most 1,000 publications from a contiguous prefix, recording the boundary atomically.
   * Subscriber positions are left intact so loss remains visible; admitted inputs live elsewhere.
   */
  int prune(Relay.TopicKey key, Instant now, int limit);
}
