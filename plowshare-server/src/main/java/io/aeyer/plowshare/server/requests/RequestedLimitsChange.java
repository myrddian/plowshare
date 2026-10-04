package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * What a body naming {@code maxTurns}, {@code noTurnCap} and {@code maxModelCalls} together is
 * asking for on {@code POST /v1/jobs/{id}/limits} — refused here when it names none of the three,
 * before {@code agents.Limits.move} ever asks {@code Job} whether it even has limits to move.
 *
 * <p><b>{@link RequestedTurnCap#in} runs first, inside {@link #wanted}, and that order is
 * load-bearing.</b> {@code AgentController.limits} computed the turn cap before checking whether
 * the body named anything at all, so a body naming both {@code maxTurns} and {@code noTurnCap} —
 * and no {@code maxModelCalls} — must see {@link RequestedTurnCap}'s own conflict refusal, not this
 * class's "names no limit to move." Wrapping {@link RequestedTurnCap#in} here, rather than calling
 * it separately at the call site, is what keeps that order from being something the next reader has
 * to remember to preserve.
 */
public final class RequestedLimitsChange {

  private RequestedLimitsChange() {}

  /**
   * The turn cap a body asked for, or {@code null} for a body that leaves the cap alone — refusing
   * first if the body named no change at all.
   *
   * @param maxTurns the body's {@code maxTurns}
   * @param noTurnCap the body's {@code noTurnCap}
   * @param maxModelCalls the body's {@code maxModelCalls}
   * @param subject what {@link RequestedTurnCap#in}'s own conflict refusal should call the thing
   *     being changed
   * @throws CallerFault if {@code maxTurns} and {@code noTurnCap} conflict — {@link
   *     RequestedTurnCap#in}'s own refusal, unchanged — or if none of the three fields names a
   *     change at all
   */
  public static TurnCap wanted(
      Integer maxTurns, Boolean noTurnCap, Integer maxModelCalls, String subject) {
    TurnCap wanted = RequestedTurnCap.in(maxTurns, noTurnCap, subject);
    if (wanted == null && maxModelCalls == null) {
      throw new CallerFault(
          "this body names no limit to move. Send 'maxTurns' or 'noTurnCap' to change"
              + " how many turns this run may take, or 'maxModelCalls' to change"
              + " what it and everything it delegates to may spend. Nothing was"
              + " changed.");
    }
    return wanted;
  }
}
