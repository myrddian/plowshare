package io.aeyer.plowshare.server.agents;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One run's request to end its turn, which a harness tool may trip and {@link JobRuntime} reads
 * once a whole tool batch has been answered.
 *
 * <h2>Tripped, not thrown</h2>
 *
 * <p>A tool that ends a turn — {@code orchestration_ask} putting a question to a person is the
 * first — could have thrown, the way {@link AgentRunTool.SubAgentFailed} does. It does not,
 * because a throw ends the run in the middle of its batch: every call the model asked for after
 * the one that threw is left with no tool result, and the next turn's history then carries an
 * assistant message declaring calls nothing answers. A chat API refuses that shape, and it would
 * be refused on the <em>next</em> turn, far from its cause. {@code SubAgentFailed} can afford that
 * because the run it ends has failed; a turn that ends waiting on somebody has not, and the
 * conversation it leaves behind has to be one a later turn can be sent.
 *
 * <p>So the tool records the request here and returns an ordinary result, the rest of the batch
 * runs and is answered, and the runtime ends the turn at the batch's end. That the step's own
 * results are all recorded before the ending is taken is the whole of the rule.
 *
 * <h2>The first request wins</h2>
 *
 * <p>Two tools in one batch may both ask. The first is kept and the second is told so by {@link
 * #request} returning {@code false}: the model asked in an order, and the request it made first is
 * the one whose text a caller is shown. Overwriting would make the ending depend on which call
 * happened to run last, which is a fact about dispatch rather than about what was asked.
 *
 * <h2>Except a soft request, which yields</h2>
 *
 * <p>{@link #requestSoftly} is rule 1's (spec 2026-09-27 §2): {@code orchestration_status} on a
 * run the caller started and is still going ends the turn only because there is nothing better to
 * do until the run reports. Any other request in the same batch — an approval, a question, a
 * finish — is something a person or a caller has to see, and first-wins let rule 1 bury it: an
 * attended bot's command approval is shown to the person only as its turn's {@code AWAITING}
 * ending, so a batch of [status, a run needing approval] ended {@code ANSWERED} on "is working"
 * and the approval was never seen (re-review, 2026-09-27 in-flight work). So a later request of
 * any kind replaces a soft one, and a soft one never replaces anything. Between ordinary requests
 * the rule above is unchanged, which is what keeps {@code orchestration_finish}'s ending from ever
 * being replaced by an approval raised after it.
 *
 * <h2>Two endings are a tool's to ask for, and no others</h2>
 *
 * <p>{@link Outcome.Ending#AWAITING} is {@code orchestration_ask}'s: the turn ends on a question
 * a person has to answer, and the text is that question. {@link Outcome.Ending#ANSWERED} is {@code
 * orchestration_finish}'s: the turn ends done, and the text is the result, which {@link
 * JobRuntime} records as the turn's answer entry, where a model's last words would otherwise be.
 *
 * <p>Every other ending is refused. {@code CANCELLED}, {@code TURN_CAP} and {@code CALL_BUDGET}
 * are the runtime's own verdicts — somebody pressed stop, the run used every step or every call.
 * {@code UNAVAILABLE}, {@code SESSION_GONE}, {@code SUB_AGENT_FAILED} and {@code STUCK} each
 * name a failure the runtime observed, and their sentences and details are built from it. A tool
 * claiming any of them would make an outcome lie about why the run stopped, and the conversation
 * log's closing entries are shaped by the ending, so a claim it cannot back leaves a log shaped
 * for a failure that never happened. A blank text is refused too: a person reads it, and {@link
 * Outcome}'s javadoc says why an empty one reads as a bug.
 *
 * <p>Synchronised because a batch's tools are not promised to run on the loop's thread for ever,
 * and the object is tiny enough that the question is not worth asking twice.
 */
public final class TurnEnd {

    /** What a tool asked for: the ending, and the sentence that goes with it. */
    public record Requested(Outcome.Ending ending, String text) {
        public Requested {
            Objects.requireNonNull(ending, "ending");
            Objects.requireNonNull(text, "text");
        }
    }

    private static final Set<Outcome.Ending> A_TOOLS_TO_ASK_FOR =
            Set.of(Outcome.Ending.AWAITING, Outcome.Ending.ANSWERED);

    private Requested requested;
    /** Whether {@link #requested} came from {@link #requestSoftly}, and so yields to any other. */
    private boolean soft;

    /**
     * Asks for the turn to end after the batch this call is in.
     *
     * @return {@code true} if this is the request that stands — the first, or the first after a
     *     soft one it replaced; {@code false} if an ordinary one already did
     * @throws IllegalArgumentException for a blank text, or an ending other than AWAITING or ANSWERED
     */
    public synchronized boolean request(Outcome.Ending ending, String text) {
        checked(ending, text);
        if (requested != null && !soft) {
            return false;
        }
        requested = new Requested(ending, text);
        soft = false;
        return true;
    }

    /**
     * Asks for the turn to end unless something else in the batch asks too: taken only when no
     * request stands, and replaced by any later {@link #request}. See the class javadoc.
     *
     * @return {@code true} if this request stands for now; {@code false} if one already did
     * @throws IllegalArgumentException as {@link #request} does
     */
    public synchronized boolean requestSoftly(Outcome.Ending ending, String text) {
        checked(ending, text);
        if (requested != null) {
            return false;
        }
        requested = new Requested(ending, text);
        soft = true;
        return true;
    }

    private static void checked(Outcome.Ending ending, String text) {
        Objects.requireNonNull(ending, "ending");
        if (!A_TOOLS_TO_ASK_FOR.contains(ending)) {
            throw new IllegalArgumentException("a tool cannot end a turn as " + ending
                    + "; only AWAITING and ANSWERED are a tool's to ask for");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "a tool that ends a turn has to say something a person can read");
        }
    }

    /** The request that stands, or empty when no tool has asked. */
    public synchronized Optional<Requested> requested() {
        return Optional.ofNullable(requested);
    }
}
