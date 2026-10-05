package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit project administration. Request IDs identify retained receipts, never automatic retries.
 */
public final class RelayControl {
  private RelayControl() {}

  public enum Action {
    ACKNOWLEDGE_GAP,
    RECONCILE,
    ABANDON,
    REMOVE_SUBSCRIPTION,
    REMOVE_TOPIC
  }

  /** Every mutation binds to the topic incarnation inspected by the operator. */
  public record Request(
      String requestId,
      String project,
      String topic,
      String topicGeneration,
      Action action,
      String subscriber,
      String subscriptionGeneration,
      String deliveryId,
      String fence,
      String expiredThrough,
      String expectedState,
      String reason) {
    public Request {
      uuid(requestId);
      identity(project);
      name(topic);
      uuid(topicGeneration);
      Objects.requireNonNull(action);
      identity(reason);
      if (action != Action.REMOVE_TOPIC) {
        name(subscriber);
        if (!subscriber.startsWith("relay."))
          throw new IllegalArgumentException("Built-in subscriptions are not operator-managed");
        uuid(subscriptionGeneration);
      } else if (subscriber != null || subscriptionGeneration != null) throw invalid();
      if (action == Action.RECONCILE || action == Action.ABANDON) {
        uuid(deliveryId);
        decimal(fence);
        if (expectedState == null) throw invalid();
        if (action == Action.ABANDON
            ? !Set.of("READY", "UNCERTAIN").contains(expectedState)
            : !Set.of("UNCERTAIN", "ABANDONED_UNCERTAIN").contains(expectedState)) throw invalid();
      } else if (deliveryId != null || fence != null || expectedState != null) throw invalid();
      if (action == Action.ACKNOWLEDGE_GAP) {
        decimal(expiredThrough);
        if (expiredThrough.equals("0")) throw invalid();
      } else if (expiredThrough != null) throw invalid();
    }
  }

  /** Outcome contains no private execution receipt or source. Unknown effects stay explicit. */
  public record Result(
      String requestId,
      String project,
      String topic,
      Action action,
      String subscriber,
      String deliveryId,
      String status,
      String seenThrough,
      Instant completedAt) {
    public Result {
      uuid(requestId);
      identity(project);
      name(topic);
      Objects.requireNonNull(action);
      if (subscriber != null) name(subscriber);
      if (deliveryId != null) uuid(deliveryId);
      Objects.requireNonNull(completedAt);
      if (status == null) throw invalid();
      boolean valid =
          switch (action) {
            case ACKNOWLEDGE_GAP -> "GAP_ACKNOWLEDGED".equals(status) && seenThrough != null;
            case RECONCILE ->
                Set.of("ACCEPTED", "FAILED", "UNCERTAIN", "ABANDONED_UNCERTAIN").contains(status)
                    && seenThrough == null;
            case ABANDON ->
                Set.of("ABANDONED", "ABANDONED_UNCERTAIN").contains(status) && seenThrough == null;
            case REMOVE_SUBSCRIPTION, REMOVE_TOPIC ->
                "REMOVED".equals(status) && seenThrough == null;
          };
      if (!valid
          || (action == Action.REMOVE_TOPIC) != (subscriber == null)
          || (action == Action.RECONCILE || action == Action.ABANDON) != (deliveryId != null))
        throw invalid();
      if (seenThrough != null) decimal(seenThrough);
    }
  }

  private static void uuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid();
  }

  private static void name(String value) {
    if (value == null
        || value.length() > 160
        || !value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) throw invalid();
  }

  private static void decimal(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
    Long.parseLong(value);
  }

  private static void identity(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid Relay control request or outcome");
  }
}
