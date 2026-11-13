package io.aeyer.plowshare.a2a.relay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aeyer.plowshare.sdk.ExternalPayloadCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** Owns untrusted request and persisted JSON; no DOM crosses into worker or journal contracts. */
public final class RelayWorkCodec {
  public static final int MAX_REQUEST_BYTES = 256 * 1024;
  public static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
  static final int MAX_JOURNAL_BYTES = 64 * 1024 * 1024;
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private RelayWorkCodec() {}

  public static RelayWork.Request request(String source) throws IOException {
    try {
      var root = parse(source, MAX_REQUEST_BYTES);
      fields(root, "schema", "requestId", "peer", "action", "message", "task", "context");
      return new RelayWork.Request(
          text(root, "schema"),
          uuid(text(root, "requestId")),
          text(root, "peer"),
          RelayWork.Action.valueOf(text(root, "action")),
          root.get("message").isNull()
              ? null
              : ExternalPayloadCodec.message(root.get("message").toString()),
          optional(root, "task"),
          optional(root, "context"));
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid A2A Relay request");
    }
  }

  public static String writeRequest(RelayWork.Request request) throws IOException {
    String wire = write(request, MAX_REQUEST_BYTES);
    request(wire);
    return wire;
  }

  public static String writeReport(RelayWork.Report report) throws IOException {
    return write(report, MAX_ENTRY_BYTES);
  }

  static byte[] snapshot(RelayWork.Snapshot value) throws IOException {
    return write(value, MAX_JOURNAL_BYTES).getBytes(StandardCharsets.UTF_8);
  }

  static RelayWork.Snapshot snapshot(byte[] source) throws IOException {
    try {
      var root =
          parse(
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(source))
                  .toString(),
              MAX_JOURNAL_BYTES);
      fields(root, "schema", "owner", "generation", "entries");
      var rows = root.get("entries");
      if (!rows.isObject() || rows.size() > 1024) throw new IllegalArgumentException();
      var entries = new LinkedHashMap<String, RelayWork.Entry>();
      var iterator = rows.fields();
      while (iterator.hasNext()) {
        var row = iterator.next();
        entries.put(row.getKey(), entry(row.getValue()));
      }
      return new RelayWork.Snapshot(
          text(root, "schema"), text(root, "owner"), optional(root, "generation"), entries);
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid retained A2A journal");
    }
  }

  private static RelayWork.Entry entry(JsonNode row) throws IOException {
    fields(
        row,
        "requestId",
        "hash",
        "sourceEvent",
        "producer",
        "peer",
        "action",
        "task",
        "context",
        "phase",
        "observation",
        "occurredAt");
    if (JSON.writeValueAsBytes(row).length > MAX_ENTRY_BYTES) throw new IllegalArgumentException();
    RemotePeer.Observation observation = null;
    JsonNode result = row.get("observation");
    if (!result.isNull()) {
      fields(result, "state", "task", "context", "result", "error");
      observation =
          new RemotePeer.Observation(
              text(result, "state"),
              optional(result, "task"),
              optional(result, "context"),
              result.get("result").isNull()
                  ? null
                  : ExternalPayloadCodec.result(result.get("result").toString()),
              optional(result, "error"));
    }
    return new RelayWork.Entry(
        uuid(text(row, "requestId")),
        text(row, "hash"),
        text(row, "sourceEvent"),
        text(row, "producer"),
        text(row, "peer"),
        RelayWork.Action.valueOf(text(row, "action")),
        optional(row, "task"),
        optional(row, "context"),
        RelayWork.Phase.valueOf(text(row, "phase")),
        observation,
        Instant.parse(text(row, "occurredAt")));
  }

  private static String write(Object value, int max) throws IOException {
    String result = JSON.writeValueAsString(value);
    if (result.getBytes(StandardCharsets.UTF_8).length > max)
      throw new IOException("A2A contract exceeds its byte bound");
    return result;
  }

  private static JsonNode parse(String source, int max) throws IOException {
    if (source == null || source.getBytes(StandardCharsets.UTF_8).length > max)
      throw new IllegalArgumentException();
    return JSON.readTree(source);
  }

  private static void fields(JsonNode value, String... names) {
    if (value == null || !value.isObject() || value.size() != names.length)
      throw new IllegalArgumentException();
    var found = new HashSet<String>();
    value.fieldNames().forEachRemaining(found::add);
    if (!found.equals(Set.of(names))) throw new IllegalArgumentException();
  }

  private static String text(JsonNode value, String key) {
    JsonNode field = value.get(key);
    if (field == null || !field.isTextual()) throw new IllegalArgumentException();
    return field.textValue();
  }

  private static String optional(JsonNode value, String key) {
    return value.get(key).isNull() ? null : text(value, key);
  }

  private static UUID uuid(String value) {
    UUID id = UUID.fromString(value);
    if (!id.toString().equals(value)) throw new IllegalArgumentException();
    return id;
  }
}
