package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Archive tool transport projection. Raw wire nodes never enter the messaging policy: only
 * validated conversation identities reach it. Other view fields stay at this output boundary.
 */
final class ConversationVisibilityTool implements AgentTool {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final AgentTool delegate;
  private final String current;
  private final BiPredicate<String, String> visibility;

  ConversationVisibilityTool(
      AgentTool delegate, String current, BiPredicate<String, String> visibility) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.current = current == null ? null : identity(current);
    this.visibility = Objects.requireNonNull(visibility, "visibility");
  }

  @Override
  public ToolSchema schema() {
    return delegate.schema();
  }

  @Override
  public void calledAs(String id) {
    delegate.calledAs(id);
  }

  @Override
  public String run(String arguments, Home home) {
    return run(arguments, home, UsageAttribution.LEGACY);
  }

  @Override
  public String run(String arguments, Home home, UsageAttribution owner) {
    try {
      JsonNode args = object(arguments);
      var selected = args.get("conversation");
      if (selected != null && !selected.isNull() && !visibility.test(identity(selected), current))
        return "That conversation is not visible to this participant.";
      String answer = delegate.run(arguments, home, owner);
      String name = schema().name();
      if (!name.equals("conversation_search") && !name.equals("conversation_list")) return answer;
      JsonNode result = tree(answer);
      JsonNode rows = name.equals("conversation_list") ? result : result.get("hits");
      if (rows == null || !rows.isArray())
        throw new IllegalArgumentException("invalid archive projection");
      record Row(String id, JsonNode value) {}
      var decoded = new java.util.ArrayList<Row>();
      for (var row : rows) {
        if (!row.isObject()) throw new IllegalArgumentException("invalid archive row");
        String id = identity(row.get(name.equals("conversation_list") ? "id" : "conversationId"));
        decoded.add(new Row(id, row));
      }
      var visible = JSON.createArrayNode();
      for (var row : decoded) if (visibility.test(row.id(), current)) visible.add(row.value());
      if (name.equals("conversation_list")) return JSON.writeValueAsString(visible);
      if (!result.isObject()) throw new IllegalArgumentException("invalid archive projection");
      if (visible.size() != rows.size()) {
        var output = (com.fasterxml.jackson.databind.node.ObjectNode) result;
        output.set("hits", visible);
        output.put("visibilityFiltered", true);
        if (result.path("retrieval").isObject())
          ((com.fasterxml.jackson.databind.node.ObjectNode) result.get("retrieval"))
              .put("complete", false);
      }
      return JSON.writeValueAsString(result);
    } catch (IOException | IllegalArgumentException malformed) {
      // An unreadable search/list must never expose an unfiltered private result or raw error.
      return "Conversation retrieval is unavailable: the visibility projection could not be read.";
    }
  }

  private static JsonNode object(String source) throws IOException {
    var value = tree(source);
    if (value == null || !value.isObject())
      throw new IllegalArgumentException("arguments require an object");
    return value;
  }

  private static JsonNode tree(String source) throws IOException {
    if (source == null || source.length() > 8 * 1048576)
      throw new IllegalArgumentException("archive projection exceeds its bound");
    return JSON.readTree(source);
  }

  private static String identity(JsonNode value) {
    if (value == null || !value.isTextual())
      throw new IllegalArgumentException("conversation requires text");
    return identity(value.textValue());
  }

  private static String identity(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException("invalid conversation identity");
    return value;
  }
}
