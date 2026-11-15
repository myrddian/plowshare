package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.approvals.BudgetExtension;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Intrinsic request capability for the current execution; it cannot grant its own allowance. */
final class RequestBudgetTool implements AgentTool {
  static final String NAME = "request_budget";
  private static final ObjectReader READER =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build()
          .readerFor(Ask.class);
  private static final ToolSchema SCHEMA =
      ToolSchema.from(
          NAME,
          "Ask the owning person for a one-time budget extension, then continue this same work after approval. "
              + "The harness pauses this execution without spending model calls while the decision is pending. "
              + "Request additional model calls, additional steps, or both, and explain the remaining work. "
              + "This never grants permission to itself. Denial must not be retried.",
          ToolArguments.object(
              Map.of(
                  "additional_calls",
                      Map.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE),
                  "additional_steps",
                      Map.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE),
                  "reason",
                      ToolArguments.string(
                          "What remains to finish and why the additional allowance is needed; at most 4000 characters.")),
              List.of("additional_calls", "additional_steps", "reason")));

  record Ask(
      @JsonProperty("additional_calls") int calls,
      @JsonProperty("additional_steps") int steps,
      String reason) {
    Ask {
      new BudgetExtension(calls, steps);
      if (reason == null || reason.isBlank() || reason.length() > 4000 || reason.indexOf('\0') >= 0)
        throw new IllegalArgumentException(
            "reason must be nonblank text of at most 4000 characters");
    }
  }

  private final Function<Ask, Optional<BudgetRequests.Decision>> request;
  private String approval;
  private boolean pending;

  RequestBudgetTool(Function<Ask, Optional<BudgetRequests.Decision>> request) {
    this.request = Objects.requireNonNull(request);
  }

  @Override
  public ToolSchema schema() {
    return SCHEMA;
  }

  @Override
  public String run(String argumentsJson, Home home) {
    approval = null;
    pending = false;
    Ask ask;
    try {
      ask = READER.readValue(argumentsJson);
      if (ask == null)
        return "request_budget needs an object containing the additional allowance and reason.";
    } catch (JsonProcessingException invalid) {
      return "request_budget needs nonnegative integer additional_calls/additional_steps (at least one positive), and a nonblank reason of at most 4000 characters. Unknown, duplicate and null fields are refused.";
    }
    Optional<BudgetRequests.Decision> decision;
    try {
      decision = request.apply(ask);
    } catch (ToolArguments.BadArguments invalid) {
      return invalid.getMessage();
    }
    approval =
        decision
            .filter(answer -> !answer.pending())
            .map(BudgetRequests.Decision::approval)
            .orElse(null);
    pending = decision.map(BudgetRequests.Decision::pending).orElse(false);
    return decision
        .map(BudgetRequests.Decision::text)
        .orElse("The budget request was cancelled; no continuation was started.");
  }

  String approval() {
    return approval;
  }

  static ToolSchema requestSchema() {
    return SCHEMA;
  }

  boolean pending() {
    return pending;
  }
}
