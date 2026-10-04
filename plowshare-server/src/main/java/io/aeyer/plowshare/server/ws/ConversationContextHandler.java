package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.api.ContextView;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.context} — what a conversation's prompt costs, and every part of it that
 * cannot honestly be priced. The frame equivalent of {@code GET
 * /v1/conversations/&#123;id&#125;/context}.
 *
 * <h2>An omitted agent is answered and not refused, which is where this parts company with {@code
 * conversation.projection}</h2>
 *
 * <p>The {@code agent} field is an override: named, it prices that agent's block; omitted, {@code
 * Conversations.whoAnswered} reads whichever agent actually answered, and a conversation that has
 * had no turn and names none answers <b>without a prefix</b> rather than refusing. {@link
 * ContextView} can hold that absence and a projection cannot, which is why {@code
 * Conversations.whoToProjectAs} exists for the other reading and is not used here.
 *
 * <h2>The price is {@code JobRuntime}'s assembly, not a second reading of a {@code tools:} line
 * </h2>
 *
 * <p>{@code JobRuntime.schemasOfferedTo} is what answers, because two of the tools built per run
 * have a schema that depends on the definition — {@code agent_run}'s description names what it may
 * delegate to — so a list assembled here would describe different tools from the ones the model is
 * shown. This is the controller's own private {@code priced} helper, calling the same three
 * collaborators the controller is injected with.
 */
public final class ConversationContextHandler implements FrameHandler {

  /**
   * {@code GET /v1/conversations/&#123;id&#125;/context}'s one query parameter, as a payload. The
   * conversation is read separately, through {@link Payloads#required}, because it came from the
   * path.
   *
   * @param agent whose block to price, or null to price whichever agent actually answered
   */
  record Asked(String agent) {}

  private final Conversations rules;
  private final TurnStore turns;
  private final JobRuntime runtime;
  private final Callers callers;
  private final Tokenizer tokenizer;
  private final Compaction compaction;

  /**
   * @param rules the service the controller asks both of its questions of
   * @param turns the one store both surfaces read a history from
   * @param runtime what assembles the schemas a definition is really offered
   * @param callers what resolves an agent for the conversation and the asking session
   * @param tokenizer how this deployment counts tokens
   * @param compaction what sizes an agent's model, as it sizes a fold's ceiling
   */
  public ConversationContextHandler(
      Conversations rules,
      TurnStore turns,
      JobRuntime runtime,
      Callers callers,
      Tokenizer tokenizer,
      Compaction compaction) {
    this.rules = Objects.requireNonNull(rules, "rules");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    this.compaction = Objects.requireNonNull(compaction, "compaction");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String conversation =
        Payloads.required(
            payload,
            "conversation",
            FrameTypes.CONVERSATION_CONTEXT,
            "the id POST /v1/conversations answered with. Nothing was read.");
    Asked asked = Payloads.as(payload, Asked.class, FrameTypes.CONVERSATION_CONTEXT);
    rules.requireExists(conversation, "context");
    List<TurnRecord> spoken = turns.forConversation(conversation);
    String priced = asked.agent() != null ? asked.agent() : rules.whoAnswered(conversation, spoken);
    return Outcome.ok(
        ContextView.of(
            spoken, priced == null ? null : priced(conversation, priced, asking.sessionId())));
  }

  /**
   * The block {@code agent} would send, resolved as a run in this conversation resolves it: the
   * conversation's own project, and the asking session's machine. A bot a laptop defines answers
   * the laptop's turns, and a price that looked for it anywhere else would refuse the one agent the
   * conversation is actually having.
   */
  private ContextView.Prefix priced(String conversation, String agent, String session) {
    AgentDefinition definition =
        callers.readAgent(agent, callers.callerForConversation(conversation, session));
    definition =
        runtime.withAgentRules(
            definition, callers.homeOfConversation(conversation), session, conversation);
    return ContextView.Prefix.of(
        definition,
        runtime.schemasOfferedTo(definition),
        tokenizer,
        compaction.contextLengthOf(definition));
  }
}
