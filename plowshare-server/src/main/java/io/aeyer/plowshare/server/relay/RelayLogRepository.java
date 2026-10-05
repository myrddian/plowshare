package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.util.List;

/** Bounded broker log inspection, without creating subscribers or advancing offsets. */
public interface RelayLogRepository {
  List<Relay.Topic> topics(Relay.Scope scope, int limit);

  Snapshot read(Relay.TopicKey topic, long after, int limit, String account);

  record Branch(
      java.util.UUID id,
      long position,
      String subscriber,
      String name,
      String receiver,
      RelayDeliveries.State state,
      long fence,
      Instant updatedAt,
      String failure,
      RelayDeliveries.Receipt receipt,
      String conversation,
      String conversationProject,
      String routingHash,
      String handlerHash) {}

  record Recovery(String subscriber, long expiredThrough, long pendingInbox, Instant recoveredAt) {
    public Recovery {
      RelayValues.name(subscriber, "native subscriber");
      if (expiredThrough < 1 || pendingInbox < 0)
        throw new IllegalArgumentException("Invalid native recovery");
      recoveredAt = RelayValues.time(recoveredAt);
    }
  }

  record Snapshot(
      Relay.Topic topic,
      long after,
      List<Relay.Publication> events,
      List<Relay.Subscription> subscribers,
      List<Branch> branches,
      List<Recovery> recoveries) {
    public Snapshot(
        Relay.Topic topic,
        long after,
        List<Relay.Publication> events,
        List<Relay.Subscription> subscribers,
        List<Branch> branches) {
      this(topic, after, events, subscribers, branches, List.of());
    }

    public Snapshot {
      events = List.copyOf(events);
      subscribers = List.copyOf(subscribers);
      branches = List.copyOf(branches);
      recoveries = List.copyOf(recoveries);
    }
  }
}
