package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.requests.RequestedAgent;
import io.aeyer.plowshare.server.requests.RequestedSession;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;

/**
 * The body of {@code POST /v1/conversations/{id}/resume}: what to grant a run that stopped for want
 * of allowance.
 *
 * <h2>Why this is not {@link AdjustLimitsRequest}, which it looks like</h2>
 *
 * <p>Three of the four fields are spelled the same way and mean the same thing, on purpose — one
 * word for one thing, so a caller that knows how to start a run under a ceiling does not learn a
 * second vocabulary to continue one. What is different is the verb. {@code POST
 * /v1/jobs/&#123;id&#125;/limits} moves a ceiling on a run that is still going and refuses a
 * finished job deliberately: "raising the ceiling on one is asking for something that cannot
 * happen, and answering 200 would say it had". This starts a <b>new</b> run continuing a stopped
 * one, so the subject is the conversation and not the job — the job is gone — and the body has to
 * carry the one thing a conversation does not hold.
 *
 * @param agent which agent answers, or {@code null} to continue as the one that answered the
 *     stopped run. <b>Required until V17 and optional now, and the change is a removal rather than
 *     a convenience.</b> This said "a conversation names no agent ... and {@code turns} holds no
 *     column saying which one answered, so a grant cannot recover it and says instead"; {@code
 *     turns.agent} is that column. A resumption opens with the stopped run's whole history — every
 *     tool result it collected, in the shape that agent's own {@code tools:} line produced — so
 *     continuing it as a different agent puts one agent's working in front of another under a
 *     different system prompt, and nothing refused that while the name came from the caller.
 *     <p><b>Sending a name that disagrees with the record is a 400</b>, not a silent correction: a
 *     caller naming an agent is asserting something about the run it is continuing, and an
 *     assertion this server knows to be false is worth a message. Sending the same name is accepted
 *     and changes nothing.
 *     <p><b>It is still the field this body has that the others do not</b>, because a turn written
 *     before V17 records no agent and nothing anywhere can recover it — so a grant continuing one
 *     of those still has to be told. {@link RequestedAgent} resolves whichever name is settled on
 * @param session the session this run reaches, or {@code null} for a run that reaches no client
 *     machine. <b>Said again rather than inherited</b>: a session is a socket somebody is holding
 *     now, not a fact the stopped run left behind. {@link RequestedSession} says why an empty one
 *     is refused
 * @param maxTurns how many turns this run may take, or {@code null} to take the conversation's
 *     ceiling, or the agent's when the conversation decides none. <b>Optional, and a new run counts
 *     its turns from zero</b>, so the same number that stopped the last one is a full grant again
 *     rather than an empty one. Turns are what a person actually grants — "approve for another
 *     CAP_TURNS" — and this is the field that says it
 * @param noTurnCap {@code true} to let this run go until it answers. Refused beside {@code
 *     maxTurns}, which would be two answers to one question; {@link RequestedTurnCap} reads the
 *     pair for all four bodies that carry it
 * @param maxModelCalls what the whole conversation may spend, raised, or {@code null} to leave it
 *     alone. <b>The conversation's and not this run's</b>: a budget is shared by reference across
 *     every turn in a conversation and everything those turns delegate to, so there is one
 *     allowance and this moves it. It may not be lowered below what has already been spent, under
 *     the identical refusal {@code POST /v1/jobs/&#123;id&#125;/limits} applies and for the
 *     identical reason — {@code Turn.resume} says which constraint
 */
public record ResumeRunRequest(
    String agent, String session, Integer maxTurns, Boolean noTurnCap, Integer maxModelCalls) {}
