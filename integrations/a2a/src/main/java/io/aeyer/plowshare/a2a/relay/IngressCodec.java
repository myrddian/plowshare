package io.aeyer.plowshare.a2a.relay;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Strict JSON conversion at SDK/configuration/persistence boundaries only. */
final class IngressCodec {
  static final int MAX_BYTES = 64 * 1024 * 1024;
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();
  private static final ObjectMapper WIRE =
      JSON.copy().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);

  private IngressCodec() {}

  static String write(Object value) throws IOException {
    String text = JSON.writeValueAsString(value);
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IOException("Ingress value exceeds its byte bound");
    return text;
  }

  static <T> T retained(byte[] bytes, Class<T> type) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Ingress journal value exceeds bound");
    try {
      return JSON.readValue(bytes, type);
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid retained ingress evidence");
    }
  }

  /** All metadata fields are required, including nullable outcome evidence. */
  static <T> T replyHeader(byte[] bytes, Class<T> type) throws IOException {
    if (bytes.length > 65536) throw new IOException("Retained reply metadata exceeds bound");
    try {
      return WIRE.readValue(bytes, type);
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid retained reply metadata");
    }
  }

  static IngressWire.Snapshot snapshot(String text) throws IOException {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
      throw new IOException("Invalid ingress snapshot bound");
    try {
      return WIRE.readValue(text, IngressWire.Snapshot.class);
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid authoritative messaging snapshot");
    }
  }

  static LegacyProvenanceBundle exported(byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES)
      throw new IOException("Historical provenance bundle exceeds bound");
    try {
      var root = JSON.readTree(bytes);
      fields(root, Set.of("schema", "origin", "project", "principal", "tasks"));
      if (!root.get("tasks").isArray())
        throw new IllegalArgumentException("Historical task list required");
      for (var row : root.get("tasks")) {
        fields(
            row,
            Set.of(
                "client",
                "id",
                "context",
                "agent",
                "message",
                "createdAt",
                "source",
                "originalContextKnown"));
        if (!row.get("id").isTextual() || !row.get("context").isTextual())
          throw new IllegalArgumentException("Historical UUID identity required");
        IngressWire.uuid(row.get("id").textValue());
        IngressWire.uuid(row.get("context").textValue());
      }
      return JSON.treeToValue(root, LegacyProvenanceBundle.class);
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid historical provenance export");
    }
  }

  private static void fields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject())
      throw new IllegalArgumentException("Historical object required");
    var names = new HashSet<String>();
    node.fieldNames().forEachRemaining(names::add);
    if (!names.equals(expected))
      throw new IllegalArgumentException("Historical fields differ from export contract");
  }
}
