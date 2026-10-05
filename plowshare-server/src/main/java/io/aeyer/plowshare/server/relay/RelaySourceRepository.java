package io.aeyer.plowshare.server.relay;

import java.util.List;

/** Durable owning-row outbox. SQL capture contains bounded references, never arbitrary JSON. */
public interface RelaySourceRepository {
  /** Keyset discovery manages publisher lifetimes; each topic is published independently. */
  List<Relay.TopicKey> pendingTopics(String after, int limit);

  /** Publishes/deletes the oldest notice atomically. Never runs handlers or receiver effects. */
  boolean publishNext(Relay.TopicKey topic);

  static String cursor(Relay.TopicKey topic) {
    return RelayScopeCodec.write(topic) + "/" + topic.name();
  }
}
