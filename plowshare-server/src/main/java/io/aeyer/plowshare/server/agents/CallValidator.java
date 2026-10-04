package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Asked, from a turn's second call failure in a row, whether the held reply was the call to a safe
 * tool that the model meant to make (spec 2026-09-28-call-failures §5).
 *
 * <p><b>A judge of what the reply meant, never of what the run should do.</b> Its {@code call} is
 * acted on only when the arguments fit the tool and the tool is on {@link CallFailures#SAFE}; the
 * runtime checks both, whatever the validator says. {@code harness.ModelCallValidator} is the
 * production one; {@link #NONE} is every runtime's until one is set.
 */
public interface CallValidator {

  /**
   * What the validator is shown: the held reply, the tool it wrote a call to, and the run's
   * request.
   */
  record Question(
      String reply,
      ToolSchema tool,
      String request,
      @com.fasterxml.jackson.annotation.JsonIgnore UsageAttribution usage) {
    public Question(String reply, ToolSchema tool, String request) {
      this(reply, tool, request, UsageAttribution.LEGACY);
    }

    public Question {
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(reply, "reply");
      Objects.requireNonNull(tool, "tool");
      request = request == null ? "" : request;
    }
  }

  /**
   * What it answered: {@code {"verdict": "call" | "not_a_call" | "unsure", "arguments": {...},
   * "reason": "..."}}.
   *
   * @param arguments the arguments as compact JSON object text, or {@code null} when it gave none
   *     or gave something that is not an object
   */
  record Verdict(String verdict, String arguments, String reason) {

    public static final String CALL = "call";
    public static final String NOT_A_CALL = "not_a_call";
    public static final String UNSURE = "unsure";

    private static final ObjectMapper JSON = new ObjectMapper();

    public Verdict {
      verdict = verdict == null ? "" : verdict;
      reason = reason == null ? "" : reason;
    }

    public boolean isCall() {
      return CALL.equals(verdict);
    }

    /** A model's answer read as a verdict; anything but a JSON object is {@link #UNSURE}. */
    public static Verdict parse(String content) {
      String body = WrittenCalls.unfenced(content);
      JsonNode parsed;
      try {
        parsed = JSON.readTree(body);
      } catch (Exception notJson) {
        parsed = null;
      }
      if (parsed == null || !parsed.isObject()) {
        return new Verdict(
            UNSURE,
            null,
            "the validator did not answer with a JSON object: "
                + (body.length() <= 200 ? body : body.substring(0, 200) + "…"));
      }
      JsonNode arguments = parsed.get("arguments");
      return new Verdict(
          parsed.path("verdict").asText(""),
          arguments != null && arguments.isObject() ? arguments.toString() : null,
          parsed.path("reason").asText(""));
    }
  }

  /**
   * @param cancelled true once nobody will read the verdict -- the run was cancelled -- so a
   *     validator still generating can stop
   * @throws RuntimeException for a validator that failed or timed out; the runtime warns as it
   *     would have and records the failure
   */
  Verdict validate(Question question, BooleanSupplier cancelled);

  /** No validator: never a call, so every call failure is warned about. */
  CallValidator NONE =
      (question, cancelled) -> new Verdict(Verdict.UNSURE, null, "no call validator is wired");
}
