package io.aeyer.plowshare.server.relay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Raw JSON is confined to this persistence boundary and never returned to broker consumers. */
final class RelayPayloadCodec {
  private static final int MAX_BYTES = 262144;
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RelayPayloadCodec() {}

  static String write(RelayPayload payload) {
    Objects.requireNonNull(payload, "payload");
    try {
      String encoded = JSON.writeValueAsString(payload);
      requireSize(encoded);
      return encoded;
    } catch (IOException invalid) {
      throw new IllegalStateException("cannot encode Relay payload");
    }
  }

  static RelayPayload read(RelayPayload.Kind kind, int version, String source) {
    Objects.requireNonNull(kind, "kind");
    if (version != 1) throw new IllegalArgumentException("unsupported Relay payload version");
    requireSize(source);
    try {
      JsonNode value = JSON.readTree(source);
      return switch (kind) {
        case EMPTY -> {
          fields(value, Set.of());
          yield new RelayPayload.Empty();
        }
        case TEXT -> {
          fields(value, Set.of("text"));
          yield new RelayPayload.Text(text(value, "text"));
        }
        case LIFECYCLE -> {
          fields(value, Set.of("source", "subject", "state", "context", "related"));
          yield new RelayPayload.Lifecycle(
              text(value, "source"),
              text(value, "subject"),
              text(value, "state"),
              optionalText(value, "context"),
              optionalText(value, "related"));
        }
        case WAKE_REQUESTED -> {
          fields(value, Set.of("firing", "target", "type"));
          yield new RelayPayload.WakeRequested(
              text(value, "firing"),
              text(value, "target"),
              RelayPayload.WakeKind.valueOf(text(value, "type")));
        }
        case SCHEDULE_DUE -> {
          fields(value, Set.of("schedule", "emits", "fireAt"));
          yield new RelayPayload.ScheduleDue(
              text(value, "schedule"), text(value, "emits"), Instant.parse(text(value, "fireAt")));
        }
      };
    } catch (IOException | IllegalArgumentException | java.time.DateTimeException invalid) {
      // Parser errors may quote payload excerpts. Return a fixed diagnostic without their causes.
      throw new IllegalArgumentException("invalid persisted Relay payload");
    }
  }

  private static void requireSize(String source) {
    if (source == null
        || source.length() > MAX_BYTES
        || source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("Relay payload exceeds its byte limit");
  }

  private static void fields(JsonNode value, Set<String> names) {
    if (value == null || !value.isObject() || value.size() != names.size())
      throw new IllegalArgumentException("invalid Relay payload fields");
    value
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!names.contains(name))
                throw new IllegalArgumentException("unsupported Relay payload field");
            });
  }

  private static String optionalText(JsonNode value, String name) {
    return value.get(name).isNull() ? null : text(value, name);
  }

  private static String text(JsonNode value, String name) {
    JsonNode field = value.get(name);
    if (field == null || !field.isTextual())
      throw new IllegalArgumentException("Relay payload field requires text");
    return field.textValue();
  }
}
