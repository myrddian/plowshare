package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Validated post-hook operation and optional, one-use clarification evidence. */
record WebToolRequest(Operation operation, String challenge, String justification) {
  /** Provider refusal text is never submitted to the security monitor as web evidence. */
  record Result(String text, boolean hasEvidence) {}

  sealed interface Operation permits Search, Fetch {
    Verb verb();

    String destination();
  }

  record Search(String query, int pageSize, int max, int page) implements Operation {
    @Override
    public Verb verb() {
      return Verb.SEARCH;
    }

    @Override
    public String destination() {
      return query;
    }
  }

  record Fetch(String url, int offset) implements Operation {
    @Override
    public Verb verb() {
      return Verb.FETCH;
    }

    @Override
    public String destination() {
      return url;
    }
  }

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> SEARCH = Set.of("query", "page_size", "max", "page");
  private static final Set<String> FETCH = Set.of("url", "offset");

  static WebToolRequest parse(String tool, String arguments) {
    if (arguments.length() > 24000) throw invalid();
    JsonNode fields;
    try {
      fields = JSON.readTree(arguments);
    } catch (JsonProcessingException malformed) {
      throw invalid();
    }
    if (fields == null || !fields.isObject()) throw invalid();
    boolean search = SearchTool.NAME.equals(tool);
    if (!search && !FetchTool.NAME.equals(tool)) throw invalid();
    Set<String> valid = search ? SEARCH : FETCH;
    for (var field : fields.properties()) {
      String name = field.getKey();
      if (!valid.contains(name) && !name.equals("challenge") && !name.equals("justification"))
        throw invalid();
    }
    String challenge = optionalText(fields, "challenge", 36);
    String justification = optionalText(fields, "justification", 2000);
    if (challenge == null ^ justification == null) throw invalid();
    return new WebToolRequest(search ? search(fields) : fetch(fields), challenge, justification);
  }

  private static Search search(JsonNode fields) {
    return new Search(
        requiredText(fields, "query", 8192),
        integer(fields, "page_size", SearchTool.DEFAULT_PAGE_SIZE, 1),
        integer(fields, "max", SearchTool.DEFAULT_MAX, 1),
        integer(fields, "page", SearchTool.DEFAULT_PAGE, 1));
  }

  private static Fetch fetch(JsonNode fields) {
    return validatedFetch(requiredText(fields, "url", 8192), integer(fields, "offset", 0, 0));
  }

  static Fetch validatedFetch(String url, int offset) {
    if (url.length() > 8192) throw invalid();
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null) throw invalid();
    return new Fetch(url, offset);
  }

  private static int integer(JsonNode fields, String name, int fallback, int minimum) {
    JsonNode value = fields.get(name);
    if (value == null) return fallback;
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum)
      throw invalid();
    return value.intValue();
  }

  private static String requiredText(JsonNode fields, String name, int max) {
    String value = optionalText(fields, name, max);
    if (value == null) throw invalid();
    return value;
  }

  private static String optionalText(JsonNode fields, String name, int max) {
    JsonNode value = fields.get(name);
    if (value == null) return null;
    if (!value.isTextual()) throw invalid();
    String text = value.textValue().strip();
    if (text.isEmpty() || text.length() > max || text.indexOf(0) >= 0) throw invalid();
    return text;
  }

  private static BadArguments invalid() {
    return new BadArguments("Invalid web request arguments. Check the tool schema and retry.");
  }

  static void clarificationFields(Map<String, Object> properties) {
    properties.put("challenge", ToolArguments.string("Challenge ID returned by SECURITY."));
    properties.put(
        "justification",
        ToolArguments.string(
            "Why the exact request serves the user's task. Supply only with a challenge ID."));
  }
}
