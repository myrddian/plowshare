package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.llm.LlmJson;

/**
 * Object-only facade for model answers. Syntax recovery is shared with Graal workflows through
 * {@link LlmJson}; callers decide what an unreadable answer means for their work. Service requests
 * and tool arguments must use their strict readers instead.
 */
public final class ModelJson {
  private ModelJson() {}

  /** Failure detail, framed by the caller in its own sentence. */
  public static final class Unreadable extends RuntimeException {
    Unreadable(String detail) {
      super(detail);
    }
  }

  /**
   * Read the object spanning the first opening brace to the last closing brace. Fences and
   * surrounding prose are allowed, nested objects stay whole, and multiple roots are refused.
   * Formatting recovery preserves valid escapes and lone LaTeX backslashes; it does not validate
   * the caller's fields, judgments or references.
   *
   * @throws Unreadable if there is no object or bounded syntax recovery fails
   */
  public static JsonNode object(String content) {
    String text = content == null ? "" : content.strip();
    int open = text.indexOf('{'), close = text.lastIndexOf('}');
    if (open < 0 || close < open)
      throw new Unreadable(text.isEmpty() ? "it said nothing" : "there is no JSON object in it");
    var recovered = LlmJson.parse(text.substring(open, close + 1));
    if (!recovered.recovered() || !recovered.value().isObject())
      throw new Unreadable("it is not valid JSON");
    return recovered.value();
  }
}
