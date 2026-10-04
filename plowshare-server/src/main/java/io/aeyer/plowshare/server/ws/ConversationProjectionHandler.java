package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.api.ProjectionView;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.requests.RequestedAgent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code conversation.projection} — the exact message list a conversation's next prompt would
 * carry, or the one a turn it has already had was shown. The frame equivalent of {@code GET
 * /v1/conversations/&#123;id&#125;/projection}.
 *
 * <h2>It must never send, and that rule travels with the read rather than with the surface</h2>
 *
 * <p>The endpoint makes no request to a model on any path: sending the assembled prompt would evict
 * the prefix the next real turn reuses and buy a generation of unbounded length for what is a read.
 * Nothing here changes that, because nothing here assembles anything — {@code
 * Compaction.projectionFor} and {@code projectionAsOf} are the same two computations the endpoint
 * calls, and both are pure.
 *
 * <h2>Three refusals, none of them this handler's</h2>
 *
 * <p>A turn the conversation never reached is {@code Conversations.aTurnThatHappened}'s 400 naming
 * the turns there are; an agent nothing is called is {@link RequestedAgent}'s 400 naming the agents
 * there are; and <b>a conversation with no turn and no agent named is {@code
 * Conversations.whoToProjectAs}'s</b>, which is where that refusal went when this controller's last
 * inline throws came down. It had to: it argues from what the conversation records rather than from
 * a field, so a handler calling the service would otherwise have gone without it and handed an
 * agent-less projection to {@link RequestedAgent} as a null name.
 *
 * <p><b>The turn is checked before the agent is resolved</b>, exactly as in the endpoint, because
 * whether a turn happened is a fact about the conversation alone — a caller who asked for a turn
 * that does not exist should be told that, and not told first about an agent whose prompt would
 * have gone over a history there is none of.
 *
 * <h2>Two calls and two view factories</h2>
 *
 * <p>Rather than one of each with a nullable turn threaded through, for the reason the endpoint
 * gives: only a past turn can have been sent a block, so only {@link ProjectionView#asOf} has a
 * {@code systemBlockAsSent} that can be true, and collapsing them would put that decision behind a
 * ternary where a false would quietly become a default.
 */
public final class ConversationProjectionHandler implements FrameHandler {

  /**
   * {@code GET /v1/conversations/&#123;id&#125;/projection}'s two query parameters, as a payload.
   * The conversation is read separately, through {@link Payloads#required}, because it came from
   * the path.
   *
   * @param agent whose prompt the projection opens with, or null for whichever agent answered
   * @param turn which turn to answer as of, or null for the next prompt
   */
  record Asked(String agent, Integer turn) {}

  private final Conversations rules;
  private final TurnStore turns;
  private final ObjectProvider<AgentRegistry> agents;
  private final Compaction compaction;

  /**
   * @param rules the service the controller asks all three of its questions of
   * @param turns the one store both surfaces read a history from
   * @param agents the registry {@link RequestedAgent} resolves a name through
   * @param compaction what assembles a projection, and never sends one
   */
  public ConversationProjectionHandler(
      Conversations rules,
      TurnStore turns,
      ObjectProvider<AgentRegistry> agents,
      Compaction compaction) {
    this.rules = Objects.requireNonNull(rules, "rules");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.agents = Objects.requireNonNull(agents, "agents");
    this.compaction = Objects.requireNonNull(compaction, "compaction");
  }

  private io.aeyer.plowshare.server.agents.JobRuntime runtime;
  private io.aeyer.plowshare.server.agents.Callers callers;

  public ConversationProjectionHandler withRules(
      io.aeyer.plowshare.server.agents.JobRuntime runtime,
      io.aeyer.plowshare.server.agents.Callers callers) {
    this.runtime = runtime;
    this.callers = callers;
    return this;
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String conversation =
        Payloads.required(
            payload,
            "conversation",
            FrameTypes.CONVERSATION_PROJECTION,
            "the id POST /v1/conversations answered with. Nothing was read.");
    Asked asked = Payloads.as(payload, Asked.class, FrameTypes.CONVERSATION_PROJECTION);
    rules.requireExists(conversation, "projection");
    List<TurnRecord> spoken = turns.forConversation(conversation);
    if (asked.turn() != null) {
      rules.aTurnThatHappened(conversation, spoken, asked.turn());
    }
    String named = rules.whoToProjectAs(conversation, asked.agent(), spoken);
    AgentDefinition definition = RequestedAgent.toRead(agents, named);
    if (asked.turn() == null && runtime != null) {
      definition =
          runtime.withAgentRules(
              definition,
              callers.homeOfConversation(conversation),
              asking.sessionId(),
              conversation);
    }
    return Outcome.ok(
        asked.turn() == null
            ? ProjectionView.of(named, compaction.projectionFor(conversation, definition))
            : ProjectionView.asOf(
                named,
                asked.turn(),
                compaction.projectionAsOf(conversation, definition, asked.turn())));
  }
}
