package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
    var draft =
        new Relay.Draft(
            identity(payload), selected.definedBy(), payload.fireAt(), null, null, payload);
    var accepted =
        transactions.inTransaction(
            () -> {
              var scope = schedules.claimPublication(selected, following);
              if (scope.isEmpty())
                return java.util.Optional.<List<ScheduledArrival.Arrival>>empty();
              var topic = new Relay.TopicKey(scope.get(), "schedule.due");
              relay.registerTopic(
                  topic, RelayPayload.Kind.SCHEDULE_DUE, Relay.Policy.systemDefault());
              var subscription = new Relay.SubscriptionKey(topic, "builtin.scheduling");
              // First registration starts at the cutover baseline without replaying legacy work.
              // Subsequent registration preserves the exact durable position.
              var cursor = relay.subscribe(subscription, Relay.Start.LATEST);
              var publication = relay.publish(topic, draft);
              if (publication.position() != Math.addExact(cursor.seenThrough(), 1))
                throw new IllegalStateException(
                    "scheduled Relay subscriber has an unadmitted publication");
              var copied =
                  arrivals.record(payload.emits(), payload.schedule(), payload.fireAt(), now);
              relay.advanceSeen(subscription, publication.position());
              return java.util.Optional.of(copied);
            });
    if (accepted.isEmpty()) return false;
    boundary.requireOutsideTransaction();
    arrivals.dispatch(accepted.get());
    return true;
  }

  private static String identity(RelayPayload.ScheduleDue payload) {
    try {
      // Schedule identities cannot contain NUL, so this encoding separates the two fields
      // unambiguously. Broker publication time and the next fire time do not change identity.
      byte[] content =
          (payload.schedule() + "\0" + payload.fireAt()).getBytes(StandardCharsets.UTF_8);
      return "schedule:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
    }
  }
}
