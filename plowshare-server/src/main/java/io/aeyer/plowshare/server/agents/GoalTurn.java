package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Per-activation binding. This object belongs to the root transcript and runtime only. */
public final class GoalTurn {
  public enum DecisionType {
    COMPLETE,
    BLOCK
  }

  public record Decision(DecisionType state, String explanation, List<String> evidence) {
    public Decision {
      Objects.requireNonNull(state);
      if (!validText(explanation, 4000)
          || evidence == null
          || evidence.size() > 12
          || evidence.stream().anyMatch(reference -> !validText(reference, 500))
          || state == DecisionType.COMPLETE && evidence.isEmpty()) {
        throw new IllegalArgumentException("Supply a decision, explanation and bounded evidence");
      }
      evidence = List.copyOf(evidence);
    }

    private static boolean validText(String value, int limit) {
      return value != null && !value.isBlank() && value.length() <= limit && value.indexOf(0) < 0;
    }
  }

  @FunctionalInterface
  public interface Settlement {
    /** Commits the reserved turn ending and goal transition together. */
    void commit(
        String job,
        int ordinal,
        Outcome outcome,
        Decision decision,
        boolean journalFailed,
        Runnable writeTurn);
  }

  private static final Set<String> SUSPENDING_TOOLS =
      Set.of("run", "file_edit", "file_delete", "file_move", SearchTool.NAME, FetchTool.NAME);
  static final String UPDATE = "goal_update";

  private static final ObjectReader READER = decisionReader();

  private static ObjectReader decisionReader() {
    var mapper =
        JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    return mapper.readerFor(Decision.class);
  }

  private static final ToolSchema SCHEMA =
      ToolSchema.from(
          UPDATE,
          "Signal COMPLETE only after verifying the entire goal, with specific evidence references."
              + " Signal BLOCK for a concrete obstacle that needs input or changed access. Use this"
              + " tool alone and include a concise owner-facing explanation. The harness settles"
              + " the decision after the turn commits.",
          ToolArguments.object(
              Map.of(
                  "state", Map.of("type", "string", "enum", List.of("COMPLETE", "BLOCK")),
                  "explanation",
                      ToolArguments.string("What was verified or what unblocks the goal"),
                  "evidence",
                      Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 12)),
              List.of("state", "explanation", "evidence")));

  private final UUID id;
  private final String conversation;
  private final OperationAuthority.Admission authority;
  private final Settlement settlement;
  private final AtomicReference<Decision> decision = new AtomicReference<>();
  private final AtomicBoolean journalFailed = new AtomicBoolean();

  public GoalTurn(
      UUID id, String conversation, OperationAuthority.Admission authority, Settlement settlement) {
    this.id = Objects.requireNonNull(id);
    this.conversation = Objects.requireNonNull(conversation);
    this.authority = Objects.requireNonNull(authority);
    this.settlement = Objects.requireNonNull(settlement);
  }

  public static void requireAgent(AgentDefinition agent) {
    RetainedAgent.require(agent);
    if (agent.tools().stream().anyMatch(SUSPENDING_TOOLS::contains))
      throw new CallerFault("Choose an agent whose tools support unattended goal turns");
  }

  public OperationAuthority.Admission authority() {
    return authority;
  }

  String conversation() {
    return conversation;
  }

  Speaker speaker() {
    return new Speaker(Speaker.Kind.HARNESS, "goal " + id);
  }

  void journalFailed() {
    journalFailed.set(true);
  }

  void commit(String job, int ordinal, Outcome outcome, Runnable writeTurn) {
    settlement.commit(job, ordinal, outcome, decision.get(), journalFailed.get(), writeTurn);
  }

  AgentTool tool(TurnEnd end) {
    return new AgentTool() {
      @Override
      public ToolSchema schema() {
        return SCHEMA;
      }

      @Override
      public String run(String arguments, Home home) {
        if (arguments.length() > 65536) return "Goal decision arguments exceed the input limit.";
        Decision proposal;
        try {
          proposal = READER.readValue(arguments);
        } catch (JsonProcessingException invalid) {
          return "Supply a valid goal decision, explanation and evidence references.";
        }
        if (proposal == null) return "Supply a goal decision object.";
        if (!end.request(Outcome.Ending.ANSWERED, proposal.explanation()))
          return "This turn already has a pending ending.";
        decision.set(proposal);
        return "Goal decision recorded for settlement.";
      }
    };
  }
}
