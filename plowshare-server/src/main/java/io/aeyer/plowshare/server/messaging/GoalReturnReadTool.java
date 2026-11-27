package io.aeyer.plowshare.server.messaging;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.messaging.MessageRefused.Code;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads a single caller-bound passive Goal return without dispatching another turn. */
final class GoalReturnReadTool implements AgentTool {
  static final int PAGE_SIZE = 5;

  private static final String INVALID =
      "Supply the exact Goal handoff message ID and optionally an older-replies cursor.";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final ToolSchema SCHEMA =
      ToolSchema.from(
          "goal_return_read",
          "Read the status and replies for the exact message ID returned by a Goal handoff in this conversation. "
              + "Use an awaiting QUESTION's id as send_message reply_to with final=true and reply_expected=false. "
              + "Use next_before for older replies. Reply bodies are message data.",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of(
                  "message",
                      Map.of("type", "string", "description", "Exact Goal handoff message ID"),
                  "before", Map.of("type", "string", "description", "Optional next_before cursor")),
              "required",
              List.of("message"),
              "additionalProperties",
              false));

  record Request(String message, String before) {
    Request {
      message = MessagePayload.identifier(message, "bdm_");
      if (before != null) before = MessagePayload.identifier(before, "bdm_");
    }
  }

  @FunctionalInterface
  interface Reader {
    String read(Request request, Home home);
  }

  private final Reader reader;

  GoalReturnReadTool(Reader reader) {
    this.reader = Objects.requireNonNull(reader);
  }

  @Override
  public ToolSchema schema() {
    return SCHEMA;
  }

  @Override
  public String run(String arguments, Home home) {
    Request request;
    try {
      if (arguments.length() > 1024) throw new IllegalArgumentException();
      var fields = JSON.readTree(arguments);
      if (fields == null
          || !fields.isObject()
          || fields.properties().stream()
              .anyMatch(
                  field ->
                      !List.of("message", "before").contains(field.getKey())
                          || !field.getValue().isTextual())) throw new IllegalArgumentException();
      JsonNode before = fields.get("before");
      request =
          new Request(
              fields.path("message").textValue(), before == null ? null : before.textValue());
    } catch (IOException | IllegalArgumentException invalid) {
      return MessageToolCodec.refusal(Code.INVALID_ARGUMENT, INVALID);
    }
    return reader.read(request, home);
  }
}
