package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/jobs/{id}/limits}: what to move, on a run that is
 * already going.
 *
 * <p><b>Every field is optional and a body naming none of them is refused.</b>
 * What is sent is what changes; what is left out is left alone, so raising a
 * budget does not quietly put a turn cap back. An empty body would answer 200
 * having done nothing, which reads to whoever sent it as a limit that moved.
 *
 * <p>Spelled the same way as {@code RunAgentRequest}'s fields, on purpose: one
 * word for one thing, so that a caller that knows how to <em>start</em> a run
 * under a ceiling does not have to learn a second vocabulary to move it. {@code
 * RequestedTurnCap} reads the same pair for all three bodies that carry it.
 *
 * @param maxTurns the ceiling this run is to have from its next turn boundary,
 *     or {@code null} to leave the turn cap alone. Lowering it below the turns
 *     the run has already taken is permitted and stops it there, at the same
 *     boundary cancellation is honoured at; nothing is unwound
 * @param noTurnCap {@code true} to take the ceiling off this run altogether.
 *     Refused beside {@code maxTurns}, which would be two answers to one
 *     question
 * @param maxModelCalls what this run and everything it delegates to may spend,
 *     or {@code null} to leave the budget alone. <b>One budget is shared by a
 *     whole delegation tree</b>, so this moves it for the parent and every
 *     child — which is the only reading that matches what a budget is for, and
 *     is deliberate rather than incidental. It may not be lowered below what has
 *     already been spent; {@code agents.Limits.move} says why, and names
 *     cancellation as the verb for stopping a run
 */
public record AdjustLimitsRequest(Integer maxTurns, Boolean noTurnCap, Integer maxModelCalls) {}
