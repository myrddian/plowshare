package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegisteredTopicsTest {
  private static Relay.TopicKey key(String name) {
    return new Relay.TopicKey(1L, name);
  }

  @Test
  void remembersAndForgets() {
    var topics = new LruRegisteredTopics();
    assertFalse(topics.known(key("a")));
    topics.remember(key("a"));
    assertTrue(topics.known(key("a")));
    topics.forget(key("a"));
    assertFalse(topics.known(key("a")));
  }

  @Test
  void evictsLeastRecentlyUsedBeyondCapacity() {
    var topics = new LruRegisteredTopics(2);
    topics.remember(key("a"));
    topics.remember(key("b"));
    assertTrue(topics.known(key("a")));
    topics.remember(key("c"));
    assertFalse(topics.known(key("b")));
    assertTrue(topics.known(key("a")));
    assertTrue(topics.known(key("c")));
  }
}
