package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayTopicOwnership;
import io.aeyer.plowshare.server.relay.RelayTopicOwnership.Owner;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Scheduler publisher and native compatibility subscriber. Existing firing records are the
 * subscriber's durable inbox, so copied input survives topic expiration without a second queue.
 * Claim, append, fan-out/queue policy and availability acknowledgement share one transaction.
 */
public final class RelayScheduledPublication implements ScheduledPublication {
  private final ScheduleStore schedules;
  private final Relay relay;
  private final ScheduledArrival arrivals;
  private final UnitOfWork transactions;
  private final Boundary boundary;

  public RelayScheduledPublication(
      ScheduleStore schedules,
      Relay relay,
      ScheduledArrival arrivals,
      UnitOfWork transactions,
      Boundary boundary) {
    this.schedules = Objects.requireNonNull(schedules);
    this.relay = Objects.requireNonNull(relay);
    this.arrivals = Objects.requireNonNull(arrivals);
    this.transactions = Objects.requireNonNull(transactions);
    this.boundary = Objects.requireNonNull(boundary);
  }

  @Override
  public void recover() {
    boundary.requireOutsideTransaction();
    arrivals.retryWaiting();
  }

  @Override
  public boolean publish(ScheduleRecord selected, Instant following, Instant now) {
    Objects.requireNonNull(selected, "selected schedule");
    Objects.requireNonNull(now, "publication pass time");
    if (following == null || !following.isAfter(selected.nextFireAt()))
      throw new IllegalArgumentException("the next schedule occurrence must move forward");
    boundary.requireOutsideTransaction();
    var payload =
        new RelayPayload.ScheduleDue(selected.name(), selected.emits(), selected.nextFireAt());
    String eventId = ScheduledRelayOccurrences.identity(payload);
    var draft =
        new Relay.Draft(
            eventId,
            selected.definedBy(),
            payload.fireAt(),
            null,
            null,
            payload,
            RelayCausation.root(eventId));
    var accepted =
        transactions.inTransaction(
            () -> {
              var claim = schedules.claimPublication(selected, following);
              if (claim.isEmpty())
                return java.util.Optional.<List<ScheduledArrival.Arrival>>empty();
              // Derived inside this package from the scope just claimed, never written down: a
              // topic name is private to the system that owns it (decision 0040). schedule.define
              // is this family's public contract; where its occurrences travel is not.
              var scope = claim.get().scope();
              var topic = new Relay.TopicKey(scope, EventTopics.scheduled(scope));
              relay.registerOwnedTopic(
                  topic,
                  RelayPayload.Kind.SCHEDULE_DUE,
                  Relay.Policy.systemDefault(),
                  RelayTopicOwnership.internal(Owner.EVENTS));
              var subscription = new Relay.SubscriptionKey(topic, "builtin.scheduling");
              // First registration starts at the cutover baseline without replaying legacy work.
              // Subsequent registration preserves the exact durable position.
              var cursor = relay.subscribe(subscription, Relay.Start.LATEST);
              var publication = relay.publish(topic, draft);
              if (publication.position() != Math.addExact(cursor.seenThrough(), 1))
                throw new IllegalStateException(
                    "scheduled Relay subscriber has an unadmitted publication");
              var copied =
                  arrivals.record(
                      payload.emits(),
                      payload.schedule(),
                      payload.fireAt(),
                      now,
                      claim.get().goalGeneration());
              relay.advanceSeen(subscription, publication.position());
              return java.util.Optional.of(copied);
            });
    if (accepted.isEmpty()) return false;
    boundary.requireOutsideTransaction();
    arrivals.dispatch(accepted.get());
    return true;
  }
}
