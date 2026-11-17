package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * External conversation facades on one existing authenticated connection. Every call submits one
 * application operation, without a client-side mapping or resolve RPC. Current server authority and
 * ordinary conversation/job lifecycle remain authoritative. Cancellation is not remote abort.
 */
public final class ExternalConversationClient {
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private final Plowshare connection;

  public ExternalConversationClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  public ConversationView open(ConversationOpenRequestEx request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> openAsync(request, options));
  }

  public CompletionStage<ConversationView> openAsync(ConversationOpenRequestEx request) {
    return openAsync(request, null);
  }

  /**
   * Repeating an uncertain open with identical selectors resolves the same mapping only while they
   * denote the same authenticated account and stable project namespace. A project name can be
   * renamed or reused; the same name alone does not establish that namespace. Recovery does not
   * change existing limits, and the SDK never performs it automatically.
   */
  public CompletionStage<ConversationView> openAsync(
      ConversationOpenRequestEx request, RequestOptions options) {
    Objects.requireNonNull(request);
    return connection.exchangeAsync(
        "conversation.open.ex", request, ConversationView.class, "OK", options);
  }

  public StartedJob run(AgentRunRequestEx request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> runAsync(request, options));
  }

  public CompletionStage<StartedJob> runAsync(AgentRunRequestEx request) {
    return runAsync(request, null);
  }

  /** Async admission returns a handle, not model completion; uncertain delivery is not replayed. */
  public CompletionStage<StartedJob> runAsync(AgentRunRequestEx request, RequestOptions options) {
    Objects.requireNonNull(request);
    return connection.exchangeAsync("agent.run.ex", request, StartedJob.class, "ACCEPTED", options);
  }

  /**
   * Canonical Ex admission receipt. Agent metadata may be absent; undeclared future fields such as
   * revision are deliberately not exposed. The conversation is the resolved internal identity.
   */
  public record StartedJob(
      @JsonProperty(required = true) String id,
      String agent,
      @JsonProperty(required = true) String conversation) {
    public StartedJob {
      text(id, "id", true);
      if (agent != null) text(agent, "agent", false);
      text(conversation, "conversation", true);
      if (!conversation.startsWith("cnv_"))
        throw new IllegalArgumentException("Expected an internal conversation identity");
    }

    /** Private SDK codec preserves optional-but-non-null agent metadata before projection. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    private static StartedJob decode(JsonNode value) {
      if (value == null
          || !value.isObject()
          || !value.path("id").isTextual()
          || !value.path("conversation").isTextual()
          || value.has("agent") && !value.get("agent").isTextual())
        throw new IllegalArgumentException("Invalid external conversation admission receipt");
      return new StartedJob(
          value.get("id").textValue(),
          value.has("agent") ? value.get("agent").textValue() : null,
          value.get("conversation").textValue());
    }
  }

  /**
   * Complete canonical Ex view. Nullable fields must still be present on the wire; text is not
   * normalized and counts use the canonical nonnegative safe-integer range. The older ordinary Java
   * conversation DTO retains its separate compatibility contract.
   */
  public record ConversationView(
      @JsonProperty(required = true) String id,
      @JsonProperty(required = true) String project,
      @JsonProperty(required = true) Long maxModelCalls,
      @JsonProperty(required = true) Long modelCallsSpent,
      @JsonProperty(required = true) Long maxTurns,
      @JsonProperty(required = true) boolean noTurnCap,
      @JsonProperty(required = true) boolean noBudget,
      @JsonProperty(required = true) String title) {
    public ConversationView {
      text(id, "id", true);
      if (project != null) text(project, "project", true);
      if (title != null) text(title, "title", false);
      count(maxModelCalls, "maxModelCalls");
      count(modelCallsSpent, "modelCallsSpent");
      count(maxTurns, "maxTurns");
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    private static ConversationView decode(JsonNode value) {
      if (value == null
          || !value.isObject()
          || List.of(
                  "id",
                  "project",
                  "maxModelCalls",
                  "modelCallsSpent",
                  "maxTurns",
                  "noTurnCap",
                  "noBudget",
                  "title")
              .stream()
              .anyMatch(field -> !value.has(field))
          || !value.path("noTurnCap").isBoolean()
          || !value.path("noBudget").isBoolean())
        throw new IllegalArgumentException("Invalid external conversation view");
      return new ConversationView(
          wireText(value.get("id"), false),
          wireText(value.get("project"), true),
          wireCount(value.get("maxModelCalls")),
          wireCount(value.get("modelCallsSpent")),
          wireCount(value.get("maxTurns")),
          value.get("noTurnCap").booleanValue(),
          value.get("noBudget").booleanValue(),
          wireText(value.get("title"), true));
    }
  }

  private static void text(String value, String field, boolean nonblank) {
    if (value == null
        || value.length() > 50 * 1024 * 1024
        || nonblank && value.codePoints().allMatch(ConversationOpenRequestEx::whitespace))
      throw new IllegalArgumentException("Invalid external conversation " + field);
  }

  private static void count(Long value, String field) {
    if (value != null && (value < 0 || value > MAX_SAFE_INTEGER))
      throw new IllegalArgumentException("Invalid external conversation " + field);
  }

  private static String wireText(JsonNode value, boolean nullable) {
    if (nullable && value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException("Expected conversation text");
    return value.textValue();
  }

  /** JSON integral values such as 1.0/1e3 have the same meaning as canonical JS numbers. */
  private static Long wireCount(JsonNode value) {
    if (value.isNull()) return null;
    if (!value.isNumber()) throw new IllegalArgumentException("Expected conversation count");
    BigDecimal number = value.decimalValue();
    if (number.signum() < 0
        || number.compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) > 0
        || number.stripTrailingZeros().scale() > 0)
      throw new IllegalArgumentException("Expected nonnegative safe-integer conversation count");
    return number.longValueExact();
  }
}
