package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The budget a conversation is opened with: the caller's number, the caller's
 * decision that there is to be no ceiling, or the operator's number when the
 * body names neither.
 *
 * <p><b>The configured default arrives as a parameter, and that is the one
 * change from the method this was moved out of.</b> {@code
 * ConversationController.allowance} read {@code
 * properties.getDefaultBudget()} off an injected {@code ConversationsProperties}
 * field; a straight move would have dragged that Spring-bound type into a
 * value type that otherwise needs no collaborator at all. {@link #in} takes
 * the number instead, and {@code ConversationController.open} passes {@code
 * properties.getDefaultBudget()} at the call site — the same split {@code
 * archive.Conversations} draws between fetching a row and deciding what to do
 * with it, kept here between reading configuration and deciding a budget.
 *
 * <p><b>An omitted key used to be a 400 and now takes the configured
 * default</b>, and {@code ConversationsProperties} carries the whole of why —
 * the short version being that the argument against a default was an argument
 * against the <em>server</em> inventing one, and an operator setting a cost
 * policy for their own deployment is a different act from a person being asked
 * for a number before they may say anything.
 *
 * <p><b>A non-positive one is still refused</b>, and that refusal is
 * untouched: {@link Budget#of} already names what it got and why a budget
 * that can make no calls is not a budget. A caller that sends {@code 0} has
 * said something, and it is not the same as saying nothing — folding it into
 * the default would answer 200 to a request for a conversation whose every
 * turn would end at its budget before it spoke.
 *
 * <p><b>{@link #in} used to catch {@code IllegalArgumentException} out of
 * {@link Budget#of} here and rethrow it as this class's own {@link
 * CallerFault} — this was the template the survey behind {@code
 * AgentController.curate}'s identical translation pointed at.</b> {@link
 * Budget#of} raises {@link CallerFault} directly now, so that catch is gone:
 * the line below just lets it through, the same fault reaching the same
 * caller either way.
 *
 * <p><b>{@code noBudget} beside {@code maxModelCalls} is refused before
 * either is read as the answer</b>, {@code RequestedTurnCap.in}'s shape for
 * the same reason: a body that says both has not told this door which one
 * decides, and choosing one silently is how a person comes to believe a
 * conversation runs under a ceiling it never had — or under none, when they
 * meant to state one. Naming neither still reaches the operator's default
 * above; {@code noBudget} is a caller stating something extra; it is not read
 * as declining the operator's policy the way an omitted body is.
 *
 * <p><b>{@link CallerFault} and not {@code BadRequestException}</b>, for
 * {@link RequestedProjectId}'s own reason: a plain static value type with no
 * injected collaborator throws the fault a caller reused from below {@code
 * api} would need anyway, for consistency with {@link RequestedHome}, {@link
 * RequestedDocument}, {@link RequestedPaths} and {@link RequestedProjectId}.
 * Both map to 400 with an identical body through {@code ApiExceptionHandler},
 * so this is not a behaviour change.
 */
public final class RequestedBudget {

    private RequestedBudget() {
    }

    /**
     * The budget a body opens a conversation with.
     *
     * <p><b>The two fields arrive as parameters rather than the body they were
     * read off</b>, which is the second application of the paragraph above.
     * This took {@code OpenConversationRequest} while it lived in {@code api},
     * and that is a Jackson-bound body shape belonging to one endpoint on one
     * surface — so a parser that cannot be called without one is a parser only
     * that surface can call. {@link RequestedTurnCap#in} already had this shape
     * for the same pair of fields; this now matches it.
     *
     * @param maxModelCalls the body's {@code maxModelCalls}, {@code null} when
     *     it names none
     * @param noBudget the body's {@code noBudget}, {@code null} when it names
     *     none
     * @param configuredDefault what an omitted body takes, an operator's
     *     {@code plowshare.conversations.default-budget}
     * @throws CallerFault if the body names both {@code maxModelCalls} and
     *     {@code noBudget}, or if the number it names — or the configured
     *     default, when it names none — is one {@link Budget#of} refuses
     */
    public static Budget in(Integer maxModelCalls, Boolean noBudget, int configuredDefault) {
        boolean lifted = Boolean.TRUE.equals(noBudget);
        if (lifted && maxModelCalls != null) {
            throw new CallerFault(
                    "this body says both 'maxModelCalls' and 'noBudget', and only one of them"
                            + " can decide what this conversation may spend. Send a number to"
                            + " cap it, send 'noBudget' to let it run with no ceiling, and leave"
                            + " both out to take the operator's configured default. Nothing was"
                            + " opened.");
        }
        if (lifted) {
            return Budget.none();
        }
        int limit = maxModelCalls == null ? configuredDefault : maxModelCalls;
        return Budget.of(limit);
    }
}
