package io.aeyer.plowshare.server.relay;

/** Bounded least-recently-used {@link RegisteredTopics}; holds at most 4,096 topics by default. */
public final class LruRegisteredTopics implements RegisteredTopics {
  /** Bound shared by the topic memo and the publisher-account memo. */
  public static final int DEFAULT_CAPACITY = 4_096;

  private final BoundedRecents<Relay.TopicKey> topics;

  public LruRegisteredTopics() {
    this(DEFAULT_CAPACITY);
  }

  public LruRegisteredTopics(int capacity) {
    this.topics = new BoundedRecents<>(capacity);
  }

  @Override
  public boolean known(Relay.TopicKey key) {
    return topics.contains(key);
  }

  @Override
  public void remember(Relay.TopicKey key) {
    topics.add(key);
  }

  @Override
  public void forget(Relay.TopicKey key) {
    topics.remove(key);
  }
}
