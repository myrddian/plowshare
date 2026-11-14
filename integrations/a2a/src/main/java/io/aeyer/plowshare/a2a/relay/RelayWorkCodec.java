package io.aeyer.plowshare.a2a.relay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aeyer.plowshare.a2a.model.ExternalPayloadCodec;
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
  static final int MAX_COMPACT_BYTES = 64 * 1024;
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
    byte[] bytes = JSON.writeValueAsBytes(value);
    if (bytes.length > MAX_JOURNAL_BYTES) throw new JournalCapacityExceeded();
    return bytes;
  }

  static final class JournalCapacityExceeded extends IOException {
    JournalCapacityExceeded() {
      super("A2A journal snapshot exceeds its byte bound");
    }
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
      boolean legacy = !root.has("identity");
      if (legacy) fields(root, "schema", "owner", "generation", "entries");
      else
        fields(
            root,
            "schema",
            "owner",
            "identity",
            "previousOwner",
            "migratedAt",
            "generation",
            "entries");
      var rows = root.get("entries");
      if (!rows.isObject() || rows.size() > 1024) throw new IllegalArgumentException();
      var entries = new LinkedHashMap<String, RelayWork.Entry>();
      var iterator = rows.fields();
      while (iterator.hasNext()) {
        var row = iterator.next();
        entries.put(row.getKey(), entry(row.getValue()));
      }
      return new RelayWork.Snapshot(
          text(root, "schema"),
          text(root, "owner"),
          legacy ? text(root, "owner") : text(root, "identity"),
          legacy ? null : optional(root, "previousOwner"),
          legacy || root.get("migratedAt").isNull()
              ? null
              : Instant.parse(text(root, "migratedAt")),
          optional(root, "generation"),
          entries);
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid retained A2A journal");
    }
  }

  private static RelayWork.Entry entry(JsonNode row) throws IOException {
    boolean legacy = !row.has("replyAttempts");
    if (legacy)
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
    else
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
          "occurredAt",
          "replyAttempts",
          "resolution");
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
        Instant.parse(text(row, "occurredAt")),
        legacy ? 0 : integer(row, "replyAttempts"),
        legacy ? null : optional(row, "resolution"));
  }

  static byte[] archived(RelayWork.Archived value) throws IOException {
    return write(value, MAX_COMPACT_BYTES).getBytes(StandardCharsets.UTF_8);
  }

  static RelayWork.Archived archived(byte[] source) throws IOException {
    try {
      var root = parse(strictText(source), MAX_COMPACT_BYTES);
      fields(
          root,
          "requestId",
          "hash",
          "sourceEvent",
          "producer",
          "peer",
          "phase",
          "remoteTask",
          "remoteContext",
          "resolution");
      return new RelayWork.Archived(
          uuid(text(root, "requestId")),
          text(root, "hash"),
          text(root, "sourceEvent"),
          text(root, "producer"),
          text(root, "peer"),
          RelayWork.Phase.valueOf(text(root, "phase")),
          optional(root, "remoteTask"),
          optional(root, "remoteContext"),
          optional(root, "resolution"));
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid compact A2A receipt");
    }
  }

  static byte[] ownership(RelayWork.RemoteOwnership value) throws IOException {
    return write(value, MAX_COMPACT_BYTES).getBytes(StandardCharsets.UTF_8);
  }

  static RelayWork.RemoteOwnership ownership(byte[] source) throws IOException {
    try {
      var root = parse(strictText(source), MAX_COMPACT_BYTES);
      fields(root, "producer", "peer", "task", "context");
      return new RelayWork.RemoteOwnership(
          text(root, "producer"),
          text(root, "peer"),
          optional(root, "task"),
          text(root, "context"));
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("Invalid A2A ownership proof");
    }
  }

  private static String strictText(byte[] value) throws IOException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString();
  }

  private static int integer(JsonNode row, String field) {
    var value = row.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException();
    return value.intValue();
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
