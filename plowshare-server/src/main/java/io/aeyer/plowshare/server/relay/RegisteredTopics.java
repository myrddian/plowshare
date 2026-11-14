package io.aeyer.plowshare.server.relay;

/**
 * Process-local memo of topics already registered with durable storage.
 *
 * <p>Registration is idempotent, so the memo is purely an optimisation: a false negative costs one
 * redundant register, and a false positive (topic deleted elsewhere) is repaired by the publisher
 * on {@link RelayTopicMissingException}. Implementations are thread-safe and bounded.
 */
public interface RegisteredTopics {
  /** Whether this process has already registered the topic and not since forgotten it. */
  boolean known(Relay.TopicKey key);

  /** Records a topic after a successful registration; never call it after a failure. */
  void remember(Relay.TopicKey key);

  /** Drops a topic after an observed or in-process deletion. */
  void forget(Relay.TopicKey key);
}
