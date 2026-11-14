package io.aeyer.plowshare.server.messaging;

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
import java.util.UUID;

/** Strict external TEXT boundary; the server never interprets adapter-specific observation JSON. */
public final class MessageReturnCodec {
  public static final String SCHEMA = "plowshare-message-return/1";
  private static final ObjectMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(2)
                          .maxStringLength(RelayPort.MAX_TEXT_BYTES)
                          .build())
                  .build())
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private MessageReturnCodec() {}

  public static MessageExports.Return read(String text) {
    try {
      JsonNode value = JSON.readTree(RelayPort.text(text));
      if (value == null || !value.isObject() || value.size() != 5) throw invalid();
      var names = value.fieldNames();
      while (names.hasNext())
        if (!Set.of("schema", "requestId", "sourceEvent", "body", "finalReply")
            .contains(names.next())) throw invalid();
      if (!SCHEMA.equals(text(value, "schema")) || !value.path("finalReply").isBoolean())
        throw invalid();
      return new MessageExports.Return(
          uuid(text(value, "requestId")),
          uuid(text(value, "sourceEvent")),
          text(value, "body"),
          value.get("finalReply").booleanValue());
    } catch (IOException | IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static UUID uuid(String value) {
    var uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value)) throw invalid();
    return uuid;
  }

  private static String text(JsonNode value, String key) {
    var field = value.get(key);
    if (field == null || !field.isTextual()) throw invalid();
    return field.textValue();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid native message return");
  }
}
