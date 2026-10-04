package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The two fields a client says a turn cap with, read as the one thing they mean.
 *
 * <h2>Two fields for one knob, and why that is the shape</h2>
 *
 * <p>A caller has three things to say and JSON gives one of them away for free: an absent field is
 * "I am not deciding this". The other two are a number and <em>no cap at all</em>, and they cannot
 * share a field — {@code null} is already the absence, and any number standing in for infinity is
 * the thing {@link TurnCap} exists to refuse. So {@code maxTurns} carries a number and {@code
 * noTurnCap} carries the decision, and <b>a body that sends both is refused rather than
 * resolved</b>: two answers to one question, where taking either silently is how somebody comes to
 * believe a run ran under a ceiling it never had. {@code agents.Runs.start} refuses a conversation
 * beside a project for the same reason and says so in the same words.
 *
 * <h2>One copy, where a home's own resolution no longer keeps one</h2>
 *
 * <p>{@code AgentController} used to spell {@code resolveHome} out itself, and that decision was
 * argued there: three lines, and lifting them would have put a helper between a request field and
 * {@link io.aeyer.plowshare.protocol.Home}'s own message. That copy went with the rest of {@code
 * run}'s decision — {@code agents.Runs.start} resolves through {@link RequestedHome}, as {@code
 * ConversationController} already did, so no copy of it remains anywhere. <b>This is not three
 * lines and it is not one message.</b> It is a refusal with two sentences and a three-state result,
 * wanted identically by three request bodies — opening a conversation, submitting a run, and moving
 * a running one's ceiling — and three copies of it would be three places for the wording of one
 * rule to drift.
 */
public final class RequestedTurnCap {

  private RequestedTurnCap() {}

  /**
   * What this body says about the turn cap.
   *
   * @param maxTurns the number of turns, or null
   * @param noTurnCap whether there is to be no cap, or null. {@code false} is read as saying
   *     nothing rather than as "put a cap back": a client serialising its whole form with the box
   *     unticked means it is not deciding, and the field that means "put the definition's cap back"
   *     is the absence of both
   * @param subject what the sentence is about — "this conversation", "this run" — so a refusal
   *     names the thing the caller was doing rather than a field in the abstract
   * @return the cap, or {@code null} for a body that decides nothing
   * @throws CallerFault if both are given, or if the number is not a cap. {@link TurnCap#of}'s own
   *     refusal is passed through for the second, because it already names what it got and why
   */
  public static TurnCap in(Integer maxTurns, Boolean noTurnCap, String subject) {
    boolean lifted = Boolean.TRUE.equals(noTurnCap);
    if (maxTurns != null && lifted) {
      throw new CallerFault(
          "this body says both 'maxTurns' and 'noTurnCap', and only one of them can"
              + " decide how many turns "
              + subject
              + " may take. Send a number to"
              + " cap it, send 'noTurnCap' to let it run, and leave both out to"
              + " take the cap from the level above. Nothing was changed.");
    }
    if (lifted) {
      return TurnCap.none();
    }
    if (maxTurns == null) {
      return null;
    }
    try {
      return TurnCap.of(maxTurns);
    } catch (IllegalArgumentException notACap) {
      throw new CallerFault(notACap.getMessage(), notACap);
    }
  }
}
