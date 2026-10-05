package io.aeyer.plowshare.server.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.orchestrations.CommandJudge;
import java.util.Set;

/** Model boundary for a command decision: no string coercion or unvalidated property bags. */
final class CommandVerdictCodec {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private CommandVerdictCodec() {}

  static CommandJudge.Verdict read(String content) {
    String body = content == null ? "" : content.strip();
    if (body.length() > 524288)
      throw new IllegalStateException("the judge's answer exceeds its size limit");
    int open = body.indexOf('{'), close = body.lastIndexOf('}');
    if (open < 0 || close < open)
      throw new IllegalStateException("the judge answered no JSON object");
    JsonNode node;
    try {
      node = JSON.readTree(body.substring(open, close + 1));
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("the judge's answer is not JSON", invalid);
    }
    if (!node.isObject()) throw new IllegalStateException("the judge's answer must be an object");
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!Set.of("clear", "why").contains(field))
                throw new IllegalStateException("unsupported judge field: " + field);
            });
    if (!node.path("clear").isBoolean())
      throw new IllegalStateException("the judge's answer has no 'clear' true or false");
    JsonNode why = node.get("why");
    if (why != null && !why.isNull() && !why.isTextual())
      throw new IllegalStateException("the judge's why must be text");
    String reason = why == null || why.isNull() ? null : why.textValue();
    if (reason != null && (reason.length() > 32768 || reason.indexOf('\0') >= 0))
      throw new IllegalStateException("the judge's why exceeds its text bounds");
    return new CommandJudge.Verdict(node.get("clear").booleanValue(), firstLine(reason));
  }

  /**
   * Presentation contract: a bounded first line, rather than a changed decision or clipped input.
   */
  private static String firstLine(String text) {
    if (text == null) return null;
    String first =
        text.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse(null);
    if (first == null) return null;
    return first.length() <= ModelCommandJudge.WHY_KEPT
        ? first
        : first.substring(0, ModelCommandJudge.WHY_KEPT - 1) + "…";
  }
}
