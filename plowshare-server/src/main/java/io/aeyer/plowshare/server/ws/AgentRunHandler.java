package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.api.RunAgentRequest;
import io.aeyer.plowshare.server.api.StartedJob;
import java.util.Map;
import java.util.Objects;

/**
 * {@code agent.run} — start one declared agent on one task, and answer with the id at once. The
 * frame equivalent of {@code POST /v1/agents/&#123;name&#125;/runs}.
 *
 * <h2>{@link Code#ACCEPTED} and never {@link Code#OK}</h2>
 *
 * <p>A run is a loop of model calls and is minutes, so the endpoint answers 202 with a handle to
 * poll at {@code job.status}. An {@code OK} here would tell a client its work had finished while it
 * holds a handle to a run that has not started.
 *
 * <h2>Every decision is {@link Runs#start}'s, including the order</h2>
 *
 * <p>The caller and the definition are resolved before any other field is read, which is what
 * decides that a body wrong in two ways is told about the agent; a turn's caller comes from the
 * conversation's own home and never from {@code project}, which the same method refuses outright
 * beside a conversation; and an utterance carrying images is refused before a turn is spoken.
 * <b>None of that is restated here</b>, and restating any of it is how the two surfaces would come
 * to refuse the same body in different words.
 *
 * <h2>The project comes from the payload, and it has to</h2>
 *
 * <p>This handler is handed an {@link Asking} — a session, and nothing else — so the {@code
 * project} the caller is resolved from is the payload's own field, exactly as the endpoint's is its
 * body's. {@code CallerParityTest} measures what the other reading would have answered: the same
 * status, in the right shape, enumerating the boot set instead of the project's own agents.
 */
public final class AgentRunHandler implements FrameHandler {

  private final Runs runs;
  private final Pictures pictures;

  /**
   * @param runs the same service the controller is injected with, which decides between a
   *     submission and an utterance
   * @param pictures how the ids a run names become the bytes it is shown
   */
  public AgentRunHandler(Runs runs, Pictures pictures) {
    this.runs = Objects.requireNonNull(runs, "runs");
    this.pictures = Objects.requireNonNull(pictures, "pictures");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String agent =
        Payloads.required(
            payload,
            "agent",
            FrameTypes.AGENT_RUN,
            "the name the agent is registered under, which agent.list answers with."
                + " Nothing was started.");
    RunAgentRequest asked = Payloads.as(payload, RunAgentRequest.class, FrameTypes.AGENT_RUN);
    // The socket's own account is who speaks: a turn spoken over this frame is the
    // signed-in person's words, and the log records them as theirs by handle.
    Runs.Started started =
        runs.start(
            new Runs.Ask(
                agent,
                asked.task(),
                asked.project(),
                asked.session(),
                asked.conversation(),
                asked.maxTurns(),
                asked.noTurnCap(),
                asked.images(),
                Speaker.person(asking.handle()),
                asking.handle(),
                asked.newConversation()),
            pictures);
    return new Outcome(
        Code.ACCEPTED,
        null,
        new StartedJob(started.id(), started.agent(), null, started.conversation()));
  }
}
