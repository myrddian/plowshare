package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.api.ContextView;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Context accounting with the same pricing as WS, bound to the actual run session. */
public final class ConversationContextTool implements AgentTool {
  public static final String NAME = "conversation_context";

  @FunctionalInterface
  public interface Pricing {
    ContextView.Prefix price(String conversation, String agent, String session);
  }

  private final ConversationStore conversations;
  private final TurnStore turns;
  private final Supplier<Conversations> rules;
  private final Pricing pricing;
  private final String session;
  private static final ToolSchema SCHEMA =
      ToolSchema.from(
          NAME,
          "Read measured context and prefix costs for a conversation in this run's tier. Optional agent prices that agent's prefix; omitted agent uses whoever answered. Uses the run's session, never a model-supplied session or project.",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of(
                  "conversation",
                  ToolArguments.string("Conversation id"),
                  "agent",
                  ToolArguments.string("Optional agent to price")),
              "required",
              List.of("conversation")));

  public ConversationContextTool(
      ConversationStore conversations,
      TurnStore turns,
      Supplier<Conversations> rules,
      Pricing pricing) {
    this(conversations, turns, rules, pricing, null);
  }

  private ConversationContextTool(
      ConversationStore conversations,
      TurnStore turns,
      Supplier<Conversations> rules,
      Pricing pricing,
      String session) {
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.pricing = Objects.requireNonNull(pricing, "pricing");
    this.session = session;
  }

  public ConversationContextTool inSession(String session) {
    return new ConversationContextTool(conversations, turns, rules, pricing, session);
  }

  @Override
  public ToolSchema schema() {
    return SCHEMA;
  }

  @Override
  public String run(String argumentsJson, Home home) {
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    Objects.requireNonNull(home, "home");
    try {
      var args = ToolArguments.parse(argumentsJson, NAME, "{\"conversation\":\"cnv_id\"}");
      if (args.has("project") || args.has("session"))
        throw new ToolArguments.BadArguments(
            NAME + " uses this run's tier and session; omit project/session");
      String conversation =
          ToolArguments.requireText(args, "conversation", NAME, "the conversation id");
      ArchiveReadTools.requireHome(conversations, conversation, home, NAME);
      var spoken = turns.forConversation(conversation);
      String agent =
          ToolArguments.optionalText(
              args, "agent", sent -> new ToolArguments.BadArguments(NAME + " needs agent as text"));
      if (agent == null) agent = rules.get().whoAnswered(conversation, spoken);
      return RetrievalTools.render(
          ContextView.of(
              spoken, agent == null ? null : pricing.price(conversation, agent, session)));
    } catch (ToolArguments.BadArguments | CallerFault refused) {
      return refused.getMessage();
    }
  }
}
