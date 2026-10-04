package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.api.ResumeRunRequest;
import io.aeyer.plowshare.server.api.StartedJob;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.requests.RequestedAgent;
import io.aeyer.plowshare.server.requests.RequestedSession;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code conversation.resume} — continue a run that stopped for want of allowance, and answer with
 * the new job. The frame equivalent of {@code POST /v1/conversations/&#123;id&#125;/resume}, and
 * the one write in this area that starts a turn.
 *
 * <h2>{@link Code#ACCEPTED} and never {@link Code#OK}</h2>
 *
 * <p>The endpoint answers 202, and the reachable mistake here is the one that put {@code ACCEPTED}
 * into {@code Code} in the first place: what comes back is a handle to poll at {@code GET
 * /v1/jobs/&#123;id&#125;}, and an {@code OK} would tell a client its work had finished while the
 * run it names has not started. {@code Code} was derived from a table of failures and had no
 * constant for a success that is not 200 until that was noticed.
 *
 * <h2>The session comes from the payload and not from the socket</h2>
 *
 * <p><b>This is the trap {@link Asking} exists to make visible, met from the other side.</b> A
 * frame arrives on a socket opened under a session, so a handler could plausibly pass {@code
 * asking.sessionId()} to {@code Turn.resume} — and it must not: the endpoint takes {@code session}
 * from its body, through {@link RequestedSession}, and a run's session decides which listener its
 * events reach. A resumption started from one socket and watched on another is the ordinary case;
 * taking the socket's own session would silently redirect the event stream of every resumption a
 * frame started. The payload's field is the request's, exactly as on the HTTP side.
 *
 * <h2>Three refusals, none restated</h2>
 *
 * <p>Which agent may continue is {@code Conversations.whoToContinueAs}'s — a body naming an agent
 * the last turn did not is refused rather than silently corrected. Which endings a grant continues
 * is {@code Turn.resume}'s 409, because an ending is a property of the conversation's last turn. A
 * number the caller can correct — an allowance below what is already spent, a lifted budget with no
 * ceiling to raise — reaches both surfaces as a {@code CallerFault} from {@code Turn.grant}, and
 * {@code Faults} answers 400. Nothing is caught here, which is the arrangement the endpoint's own
 * javadoc describes as what makes a frame handler calling {@code Turn.resume} correct without the
 * controller method existing.
 */
public final class ConversationResumeHandler implements FrameHandler {

  private final CallerAccess access;
  private final Conversations rules;
  private final ObjectProvider<AgentRegistry> agents;
  private final Turn speaking;

  /**
   * @param rules the service that decides which agent continues
   * @param agents the registry {@link RequestedAgent} resolves a name through
   * @param speaking the one service both surfaces start a turn through
   */
  public ConversationResumeHandler(
      Conversations rules,
      ObjectProvider<AgentRegistry> agents,
      Turn speaking,
      CallerAccess access) {
    this.access = access;
    this.rules = Objects.requireNonNull(rules, "rules");
    this.agents = Objects.requireNonNull(agents, "agents");
    this.speaking = Objects.requireNonNull(speaking, "speaking");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String conversation =
        Payloads.required(
            payload,
            "conversation",
            FrameTypes.CONVERSATION_RESUME,
            "the id POST /v1/conversations answered with. Nothing was started.");
    ResumeRunRequest asked =
        Payloads.as(payload, ResumeRunRequest.class, FrameTypes.CONVERSATION_RESUME);
    AgentDefinition definition =
        RequestedAgent.toRun(agents, rules.whoToContinueAs(conversation, asked.agent()));
    TurnCap turnCap = RequestedTurnCap.in(asked.maxTurns(), asked.noTurnCap(), "this run");
    String session = RequestedSession.in(asked.session());
    access.requireSession(session, asking.handle());
    var home = speaking.homeOf(conversation);
    if (home.isGlobal())
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Global conversations are read-only; open Personal or a project");
    access.requireWork(home.project(), asking.handle());
    String job = speaking.resume(conversation, definition, session, turnCap, asked.maxModelCalls());
    return new Outcome(Code.ACCEPTED, null, new StartedJob(job, definition.name()));
  }
}
