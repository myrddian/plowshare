package io.aeyer.plowshare.server.events;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.server.board.MessageWake;
import io.aeyer.plowshare.server.board.SeatWake;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owned wire/persistence codec. Unsupported historical shapes block cutover, never become text. */
public final class EventPayloadCodec {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private EventPayloadCodec() {}

  /** Only manual DTOs are accepted from event.fire; callers cannot manufacture a system wake. */
  public static EventPayload manual(Object source) {
    try {
      return manualValue(source == null ? JSON.createObjectNode() : JSON.valueToTree(source));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("event data must be empty or the explicit {text: string} DTO");
    }
  }

  private static EventPayload manualValue(JsonNode value) {
    fields(value, Set.of("text"));
    if (value.isEmpty()) return new EventPayload.Empty();
    return new EventPayload.Text(text(value, "text"));
  }

  /** Rows are decoded before they can be claimed, recovered or returned to application code. */
  public static EventPayload stored(
      String event, String topic, String schedule, Instant fireAt, String source) {
    if (source == null || source.length() > 2 * 1048576)
      throw new IllegalArgumentException("invalid event payload size");
    try {
      JsonNode value = JSON.readTree(source);
      if (FiringStore.WAKE_EVENT.equals(event) && topic != null) {
        if (schedule != null || fireAt != null)
          throw new IllegalArgumentException("a wake cannot be scheduled");
        if (value != null && value.has("direct_message")) {
          fields(
              value,
              Set.of(
                  "direct_message",
                  "message_continuation",
                  "message_approval",
                  "utterance",
                  "delegate"));
          return new EventPayload.Message(JSON.treeToValue(value, MessageWake.class));
        }
        fields(value, Set.of("reason", "message", "by", "maxTurns"));
        return new EventPayload.Seat(JSON.treeToValue(value, SeatWake.class));
      }
      if (topic != null) throw new IllegalArgumentException("topic belongs to a board wake");
      if (schedule != null) {
        // Some historical callers recorded empty scheduled content; keep that explicit family.
        if (value != null && value.isObject() && value.isEmpty()) return new EventPayload.Empty();
        fields(value, Set.of("schedule", "fire_at"));
        var decoded =
            new EventPayload.Scheduled(
                text(value, "schedule"), Instant.parse(text(value, "fire_at")));
        if (!schedule.equals(decoded.schedule()) || !Objects.equals(fireAt, decoded.fireAt()))
          throw new IllegalArgumentException("scheduled event differs from its firing identity");
        return decoded;
      }
      if (fireAt != null) throw new IllegalArgumentException("fire time requires a schedule");
      return manualValue(value);
    } catch (IOException | IllegalArgumentException | java.time.DateTimeException invalid) {
      // Parser causes can contain payload excerpts; persistence diagnostics use fixed codes only.
      throw new IllegalArgumentException("unsupported or invalid persisted event DTO");
    }
  }

  private static void fields(JsonNode value, Set<String> allowed) {
    if (value == null || !value.isObject())
      throw new IllegalArgumentException("event data must be an object");
    value
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field))
                throw new IllegalArgumentException("unsupported event field");
            });
  }

  private static String text(JsonNode value, String name) {
    var field = value.get(name);
    if (field == null || !field.isTextual())
      throw new IllegalArgumentException("event field requires text");
    return field.textValue();
  }

  public static String write(EventPayload payload) {
    Objects.requireNonNull(payload, "payload");
    try {
      return switch (payload) {
        case EventPayload.Empty empty -> "{}";
        case EventPayload.Text text -> JSON.writeValueAsString(text);
        case EventPayload.Scheduled tick ->
            JSON.writeValueAsString(
                Map.of("schedule", tick.schedule(), "fire_at", tick.fireAt().toString()));
        case EventPayload.Seat seat -> JSON.writeValueAsString(seat.wake());
        case EventPayload.Message message -> JSON.writeValueAsString(message.wake());
      };
    } catch (IOException impossible) {
      throw new IllegalStateException("cannot encode event DTO", impossible);
    }
  }

  /** Defend repository writes from alternate callers before INSERT or a guarded transition. */
  static void require(
      String event, String topic, String schedule, Instant fireAt, EventPayload payload) {
    EventPayload.identity(event, "event");
    if (topic != null) EventPayload.identity(topic, "topic");
    if (schedule != null) EventPayload.identity(schedule, "schedule");
    if ((schedule == null) != (fireAt == null))
      throw new IllegalArgumentException("schedule and fire time must be present together");
    var decoded = stored(event, topic, schedule, fireAt, write(payload));
    if (!decoded.equals(payload))
      throw new IllegalArgumentException("event DTO does not match its firing family");
  }
}
