package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.util.Set;

/** Bridge-owned strict TEXT codec; no JSON values enter forum business logic. */
public final class BoardBridgeCodec {
  public static final String REQUEST_VERSION = "plowshare-board-request/1";
  public static final String REPLY_VERSION = "plowshare-board-reply/1";
  // Worst-case JSON escaping of the bounded body plus schema and a 1024-character topic.
  private static final int MAX_REQUEST_BYTES = 6 * 64000 + 8192;
  private static final ObjectMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(3)
                          .maxStringLength(RelayPort.MAX_TEXT_BYTES)
                          .build())
                  .build())
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private BoardBridgeCodec() {}

  /** Missing reply_to means no replies; explicit null is accepted as the same absence. */
  public static BoardBridge.Payload request(String source) {
    try {
      JsonNode value = JSON.readTree(RelayPort.text(source, MAX_REQUEST_BYTES));
      if (value == null || !value.isObject() || !value.has("schema") || !value.has("body"))
        throw invalid();
      var names = value.fieldNames();
      while (names.hasNext())
        if (!Set.of("schema", "body", "reply_to").contains(names.next())) throw invalid();
      if (!REQUEST_VERSION.equals(text(value, "schema"))) throw invalid();
      return new BoardBridge.Payload(
          text(value, "body"),
          !value.has("reply_to") || value.get("reply_to").isNull()
              ? null
              : text(value, "reply_to"));
    } catch (IOException | IllegalArgumentException failure) {
      throw invalid();
    }
  }

  /**
   * Full post text and attribution; only thread answers and terminal resolutions reach this codec.
   */
  public static String reply(BoardBridgeRepository.Pending pending) {
    var value = JSON.createObjectNode();
    value.put("schema", REPLY_VERSION);
    value.put("kind", pending.terminal() ? "terminal" : "post");
    value.put("topic", pending.topic());
    value.put("message", pending.message());
    value.put("reply_to", pending.replyTo());
    value.put("body", pending.body());
    value.put("authorKind", pending.authorKind());
    value.put("author", pending.author());
    try {
      return RelayPort.text(JSON.writeValueAsString(value));
    } catch (IOException failure) {
      throw new IllegalStateException("Board reply could not be encoded");
    }
  }

  private static String text(JsonNode value, String name) {
    JsonNode field = value.get(name);
    if (field == null || !field.isTextual()) throw invalid();
    return field.textValue();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid board bridge request");
  }
}
