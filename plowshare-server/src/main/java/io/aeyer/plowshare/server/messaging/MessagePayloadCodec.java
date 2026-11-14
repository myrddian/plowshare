package io.aeyer.plowshare.server.messaging;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/**
 * Strict versioned messaging JSON boundary inside the existing Relay TEXT carrier. All fields,
 * including nullable ones, must appear exactly once. Unsupported fields, coercion, trailing input,
 * excessive nesting and malformed text are refused before a typed payload is returned.
 *
 * <p>Parser diagnostics can quote message bodies, so failures deliberately contain no parser cause
 * or input excerpt. The mapper is private to this codec and cannot widen with application defaults.
 */
public final class MessagePayloadCodec {
  public static final String VERSION = "plowshare-message/1";

  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "message",
          "sender",
          "recipient",
          "body",
          "reply_to",
          "conversation",
          "reply_expected",
          "final");
  private static final Set<String> INLINE = Set.of("kind", "text");
  private static final Set<String> RETAINED = Set.of("kind", "message");
  private final ObjectMapper json;
  private final int bodyBytes;
  private final int wireBytes;

  /**
   * @param bodyBytes explicit deployment allowance for inline UTF-8 bodies, normally supplied from
   *     the existing messaging settings; must fit the portable Relay TEXT ceiling
   */
  public MessagePayloadCodec(int bodyBytes) {
    if (bodyBytes < 1 || bodyBytes > RelayPort.MAX_TEXT_BYTES)
      throw new IllegalArgumentException("Messaging body allowance must fit Relay TEXT");
    this.bodyBytes = bodyBytes;
    // JSON can escape each body byte to six bytes. The fixed overhead covers the five bounded
    // alphanumeric identifiers and field names. The encoded JSON is itself Relay TEXT, so it must
    // also fit that carrier's raw UTF-8 ceiling, independently of the body allowance.
    wireBytes = (int) Math.min(RelayPort.MAX_TEXT_BYTES, 6L * bodyBytes + 4096);
    json =
        JsonMapper.builder(
                JsonFactory.builder()
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(4)
                            .maxStringLength(Math.max(bodyBytes, 256))
                            .maxNumberLength(16)
                            .build())
                    .build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
  }

  /** Returns canonical field order and escaping, preserving the validated message content. */
  public String write(MessagePayload payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload.body() instanceof MessagePayload.Inline inline)
      RelayPort.text(inline.text(), bodyBytes);
    ObjectNode value = json.createObjectNode();
    value.put("schema", VERSION);
    value.put("message", payload.message());
    value.put("sender", payload.sender());
    value.put("recipient", payload.recipient());
    ObjectNode body = value.putObject("body");
    switch (payload.body()) {
      case MessagePayload.Inline inline -> {
        body.put("kind", "inline");
        body.put("text", inline.text());
      }
      case MessagePayload.Retained retained -> {
        body.put("kind", "retained");
        body.put("message", retained.message());
      }
    }
    value.put("reply_to", payload.replyTo());
    value.put("conversation", payload.conversation());
    value.put("reply_expected", payload.replyExpected());
    value.put("final", payload.finalReply());
    try {
      return RelayPort.text(json.writeValueAsString(value), wireBytes);
    } catch (IOException invalid) {
      throw new IllegalStateException("Messaging payload could not be encoded");
    }
  }

  /**
   * Decodes and validates the complete wire contract without defaults or scalar coercion.
   *
   * @throws IllegalArgumentException if any field, version, shape or bound is invalid; the failure
   *     contains no message content
   */
  public MessagePayload read(String source) {
    try {
      JsonNode value = json.readTree(RelayPort.text(source, wireBytes));
      fields(value, FIELDS);
      if (!VERSION.equals(text(value, "schema"))) throw invalid();
      JsonNode content = value.get("body");
      if (content == null || !content.isObject()) throw invalid();
      MessagePayload.Body body =
          switch (text(content, "kind")) {
            case "inline" -> {
              fields(content, INLINE);
              yield new MessagePayload.Inline(RelayPort.text(text(content, "text"), bodyBytes));
            }
            case "retained" -> {
              fields(content, RETAINED);
              yield new MessagePayload.Retained(text(content, "message"));
            }
            default -> throw invalid();
          };
      return new MessagePayload(
          text(value, "message"),
          text(value, "sender"),
          text(value, "recipient"),
          body,
          value.get("reply_to").isNull() ? null : text(value, "reply_to"),
          text(value, "conversation"),
          flag(value, "reply_expected"),
          flag(value, "final"));
    } catch (IOException | IllegalArgumentException invalid) {
      throw invalid();
    }
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject() || value.size() != expected.size()) throw invalid();
    var names = value.fieldNames();
    while (names.hasNext()) if (!expected.contains(names.next())) throw invalid();
  }

  private static String text(JsonNode value, String name) {
    JsonNode field = value.get(name);
    if (field == null || !field.isTextual()) throw invalid();
    return field.textValue();
  }

  private static boolean flag(JsonNode value, String name) {
    JsonNode field = value.get(name);
    if (field == null || !field.isBoolean()) throw invalid();
    return field.booleanValue();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid messaging payload");
  }
}
