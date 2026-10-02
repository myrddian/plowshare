package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A conversation's log, as the messages a request carries.
 *
 * <h2>A request is a projection and not a construction</h2>
 *
 * <p>Nothing here decides what a model should see. Each entry is asked what kind
 * it is; a kind that projects becomes exactly one message and a kind that does
 * not becomes nothing, and {@link EntryKind} owns that answer. So there is no
 * site in this codebase where a particular entry can be included anyway, and a
 * kind added without a decision about it is invisible rather than accidentally
 * visible — the failure mode is a missing message, which is loud, rather than a
 * leaked one, which is not.
 *
 * <p><b>The agent's system prompt is not projected because it is not logged.</b>
 * It is assembled at request time from the definition — {@link
 * JobRuntime#oneSystemMessageFirst}, which is also what folds a seam into it —
 * so what this method returns is the conversation and never the agent.
 *
 * <h2>What reads this</h2>
 *
 * <p><b>{@code Compaction} does, and it is what every turn after the first opens
 * with.</b> It was written before anything read it, alongside the path that was
 * already running, and the switch was taken only once {@code ProjectionTest}
 * asserted that this class answers with what {@code turns} and {@code
 * compactions} built between them. That equivalence is what made the switch a
 * refactor rather than a rewrite, and {@code
 * a_projected_request_is_the_request_the_turns_table_builds_across_a_fold} still
 * holds it against a copy of the construction that was replaced.
 *
 * <p><b>{@code Compaction} decides what it hands in and does not widen what
 * comes out.</b> Before projecting, it either replaces an earlier turn's tool
 * results with references or drops them along with the answers that asked for
 * them — see {@code Compaction.whatWasSaidAndWhatCameBack}, which picks on the
 * agent's declaration — because how much of its own history a consumer reads is
 * a different question from whether an entry may be shown at all. Neither
 * reading is anything this class knows about. Nothing anywhere can make a kind
 * with no role reach a model, which is the guarantee this class exists to hold,
 * and a caller that substitutes or omits content cannot break a rule about which
 * <em>kinds</em> project.
 *
 * <h2>The dangling call, and the decision this class had to take</h2>
 *
 * <p>A turn that dies inside a tool call leaves an {@link EntryKind#ANSWER}
 * declaring calls and no {@link EntryKind#TOOL_RESULT} answering them. {@code
 * JobRuntime} returns before appending results for {@code SUB_AGENT_FAILED}, for
 * {@code SESSION_GONE}, for a dependency failure and for {@code STUCK} — its own
 * comment says "this turn's tool results were never appended, so the turn did
 * not complete" — and a run killed outright appends nothing either. Today that
 * is harmless because the transient history is discarded at the turn boundary.
 * Once the history is a log, it is a shape a later request would carry.
 *
 * <p><b>The two available answers are to drop the calls or to pair each one with
 * a synthetic result, and this class pairs.</b> Three things decided it, and the
 * second is the one that is not a matter of taste:
 *
 * <ul>
 *   <li><b>Dropping erases the fact that the call was made.</b> The first
 *       problem this log exists for is that a run which stops leaves nothing to
 *       resume from; a resumed run that cannot see it asked an agent to do
 *       something, and that the request went nowhere, is being handed a history
 *       in which that never happened. {@code SUB_AGENT_FAILED} and {@code
 *       SESSION_GONE} are facts a resumed run should know.
 *   <li><b>"Drop the calls" is not actually available on the shape it matters
 *       for.</b> An assistant turn that is entirely tool calls carries empty
 *       content, and {@code ChatMessage.assistant} refuses a message with
 *       neither content nor calls — "an assistant message with neither content
 *       nor tool calls says nothing; it is a turn that did not happen".
 *       Measured, not assumed: {@code
 *       dropping_a_dying_turns_calls_would_have_to_drop_the_message_too} builds
 *       exactly that message and pins the refusal. So dropping the calls means
 *       dropping the whole message, and with it whatever the model said
 *       alongside them.
 *   <li><b>{@code ChatRequest} will not catch it.</b> The design assumed the
 *       constructor refuses a dangling call. It does not, and says so:
 *       "<em>The converse is deliberately not enforced:</em> an assistant turn
 *       may declare a call that no {@code tool} message answers ... It is left
 *       representable knowingly rather than by omission." So a projection that
 *       produced one would pass every check on this side of the wire and be
 *       refused by the endpoint, on a later request, for a reason nothing in the
 *       message explains — which is the failure mode
 *       {@code requireEveryToolResultAnswersACall} was itself written for.
 * </ul>
 *
 * <p><b>Pairing means this class owns model-visible text</b>, which dropping
 * would not have. {@link #NEVER_COMPLETED} is that text and it is the only
 * sentence in this file a model reads. It is repaired here, at projection time,
 * and never written into the log: the log records what happened, which is a call
 * with no result, and a run that was killed outright could not have written a
 * repair anyway. One mechanism covers every way a turn can die, including the
 * ones no code path survives.
 *
 * <p><b>How often it fires depends on which reading {@code Compaction} took, and
 * it must hold under both.</b> Where results are dropped, a dying turn's
 * surviving answer leaves <em>every</em> call it declared unanswered — including
 * the ones that really were answered — so this stands in for results that exist;
 * that is the reading an agent without {@code result_read} still gets, and it is
 * why this is a live backstop rather than dead code. Where they are referenced,
 * the answered ones come back with their references and only a call nothing ever
 * answered reaches here, which is the situation the sentence below is written
 * about.
 */
public final class Projection {

    /**
     * What a model is told in place of a result that never arrived.
     *
     * <h2>This is model-visible content and its wording is a decision</h2>
     *
     * <p>It goes in the {@code tool} slot, against the id of the call it stands
     * for, which is where a model looks for that call's answer. Four things it
     * has to do, and each clause is one of them:
     *
     * <ul>
     *   <li><b>say what happened</b> — the call did not complete — rather than
     *       reporting a result. A sentence that read like an answer would be the
     *       confident-empty-answer shape this project keeps finding, arriving
     *       through the harness instead of through a tool;
     *   <li><b>not blame the tool.</b> The tool may never have been dispatched
     *       at all; on the {@code STUCK} path the call is refused before
     *       dispatch, and on the others it may have been part way through.
     *       {@code JobRuntime}'s own "the tool 'X' failed" sentence is a
     *       different claim and belongs to a tool that really failed;
     *   <li><b>say nothing can be concluded from it.</b> A model that reads "no
     *       result" and infers "the answer was nothing" has been misled exactly
     *       as {@code JobRuntime.usable} says a blank tool result misleads it;
     *   <li><b>say who is speaking.</b> Everything else in the tool slot is a
     *       tool's own output, so a harness sentence sitting there
     *       unattributed reads as one. {@code Compaction.ASK_FOR_A_SUMMARY}
     *       opens with the same move for the same reason.
     * </ul>
     *
     * <p><b>It does not name the ending</b>, and that is deliberate rather than
     * an omission. The projection cannot know it: a conversation whose server was
     * killed mid-call has an ending nothing recorded, and a sentence that named
     * one on the paths that have one and not on the paths that do not would be
     * two different messages for one situation. The ending <em>is</em> recorded,
     * as an {@link EntryKind#ATTEMPT_FAILED} entry, for the operator who wants
     * to know which of them it was — a different sentence, in a different place,
     * for a different reader.
     *
     * <p><b>It does not tell the model what to do next.</b> Whether a resumed run
     * should retry the call, work around it, or stop is a decision about
     * resumption, and resumption declined to take it: {@code Turn.resume}
     * continues only the endings that stop at a turn boundary, where every call
     * has its result, <em>because</em> the dangling one has no answer yet. So
     * this sentence still reaches no resumed run, and it is still not the place
     * to invent a policy for one.
     */
    public static final String NEVER_COMPLETED =
            "This call did not complete. The run that made it stopped before this tool returned"
                    + " anything, so there is no result here: nothing was learned from this call,"
                    + " and nothing about what it would have returned can be assumed. This"
                    + " sentence is from the harness that owns this conversation and is not the"
                    + " tool's answer.";

    private Projection() {
    }

    /**
     * Whose decision it is that a superseded row is not shown.
     *
     * <h2>The column means "as of now", and one of the two reads is not asking
     * about now</h2>
     *
     * <p><b>{@code entries.superseded_by} records that a fold has covered a row,
     * and it carries no date.</b> A row covered by a fold taken at turn 40 looks
     * exactly the same as one covered by a fold taken at turn 2, so reading the
     * column answers one question only: <em>is this row folded away today</em>.
     * That is the right question for every caller building the prompt a turn is
     * about to send, and it is the wrong one for the caller asking what an older
     * turn was shown — at turn 12, the fold at turn 40 had not happened and its
     * rows were plainly visible.
     *
     * <p><b>So the decision has exactly one owner and this names which.</b>
     * {@code EntryStore.thatProjectFor} filters on the column in SQL and hands
     * over rows that are visible now; {@code EntryStore.thatProjectedAt}
     * deliberately <em>returns</em> superseded rows, because returning the ones a
     * later fold covered is the entire purpose of an as-of-turn read, and it has
     * already applied the comparison the column cannot make on its own. A
     * renderer that re-derived visibility from the column would silently drop
     * every one of those rows and answer with a turn that saw no history — which
     * is what it did, and what this parameter exists to stop coming back.
     *
     * <p><b>Named rather than a boolean.</b> A call site reading {@code of(rows,
     * canList, false)} cannot say what the {@code false} governs, and this is a
     * decision a reader has to be able to check at the call site — the split
     * {@code RequestedAgent.toRun} and {@code toRead} made for the same reason.
     */
    public enum Superseded {

        /**
         * The column decides, and so this reading is as of now.
         *
         * <p>What {@link #of(List, boolean)} does, which is every caller
         * assembling a prompt and every caller handing over a raw log —
         * {@code EntryStore.forConversation} answers with covered rows and a
         * model must not be shown them.
         */
        HIDES_A_ROW,

        /**
         * The read that chose these rows already decided, so the column is not
         * consulted.
         *
         * <p>{@code EntryStore.thatProjectedAt}'s contract: what it hands over
         * is what that turn could see, superseded rows included, and every row
         * whose kind projects is rendered.
         */
        WAS_WEIGHED_BY_THE_READ
    }

    /**
     * The messages a conversation's log projects to, in order.
     *
     * <p><b>Superseded entries are skipped and the summary appears in their
     * place</b>, which is the whole of what a fold does to a request. The
     * entries themselves are untouched and still read back through {@code
     * EntryStore.forConversation}; a fold changes what a model is shown and
     * changes nothing a person can read, which is the promise {@code
     * compactions} already makes about {@code turns}.
     *
     * <p><b>The skip is kept even though {@code EntryStore.thatProjectFor} no
     * longer fetches those rows.</b> It costs nothing on rows that do not
     * arrive, and this is a function of a list: it has to be right about
     * whatever list it is handed, including the whole log, which is what makes
     * "the same messages, fewer rows" an equivalence anything can check. The
     * store's javadoc carries the proof that its SQL predicate and {@link
     * EntryKind#projects()} select the same entries.
     *
     * <p><b>It is a skip about now, which is why the other reading exists.</b>
     * {@code superseded_by} says a fold has covered a row and says nothing about
     * when, so this overload answers "what can a model be shown today" and
     * cannot answer "what could turn 12 see". {@link #of(List, boolean,
     * Superseded)} is that reading and {@link Superseded} carries the whole
     * argument; a caller holding rows from {@code EntryStore.thatProjectedAt}
     * wants that one, because this one drops precisely the rows that read went
     * to the trouble of returning.
     *
     * <p><b>A seam names the span it stands for, and the span starts where the
     * seam before it stopped.</b> Folds are incremental — {@code
     * Compaction.foldTheLog} — so a conversation can carry two standing
     * summaries, and each covers only the turns since the one above it. This
     * loop therefore keeps the reach of the last seam it rendered and hands it
     * to the next, which is the lower bound {@link Compaction#SEAM} names. The
     * alternative, asking {@code EntryStore.foldedThrough}, would answer about
     * the log rather than about this projection and would be a different number
     * for any caller rendering less than the whole of one.
     *
     * <p><b>Every declared call comes back answered.</b> A call that no result
     * answers is paired with {@link #NEVER_COMPLETED} in the position the result
     * would have taken — immediately after the assistant turn that asked for it
     * and before anything else. See this class's javadoc for why pairing and not
     * dropping.
     *
     * <p><b>Which of the two seams is written is the agent's question, so it is
     * asked as a parameter.</b> A seam can tell a model that the stored results
     * behind it are still readable and name the tool that lists them — {@link
     * Compaction#SEAM_OVER_STORED_RESULTS} — and that sentence is worth nothing
     * to an agent without {@code result_list} and worse than nothing to the turn
     * it sends looking for a tool that is not there. It is passed rather than
     * re-derived here, for the reason {@code
     * Compaction.whatWasSaidAndWhatCameBack} takes {@code canRedeem} that way:
     * this class holds no definition and has no other use for one.
     *
     * @param log one conversation's entries, in conversation order. Superseded
     *     entries and kinds that do not project may be present and are skipped;
     *     {@code EntryStore.thatProjectFor} leaves them in the database and
     *     {@code EntryStore.forConversation} answers with them. Never null
     * @param canList whether the agent this history is being read for declares
     *     {@code result_list}, which is {@link AgentDefinition#canList()} and
     *     decides the seam's wording and nothing else
     * @return a mutable list, for a caller that has something to append —
     *     {@code JobRuntime.oneSystemMessageFirst} is the one that has
     */
    public static List<ChatMessage> of(List<EntryRecord> log, boolean canList) {
        return of(log, canList, Superseded.HIDES_A_ROW);
    }

    /**
     * The same reading, over rows whose visibility somebody else has already
     * decided.
     *
     * <p><b>One owner for that decision, and {@code superseded} says which.</b>
     * {@link Superseded} carries the argument in full: the column answers "is
     * this folded away today", the as-of-turn read has to answer "was it folded
     * away yet", and only the read that knows the turn can tell those apart. So
     * this method takes the answer rather than re-deriving one, which is what
     * {@code Compaction.projected} passes down from whichever question it was
     * asked.
     *
     * <p>Everything after that is identical, and deliberately: the seams, the
     * pairing of unanswered calls and the substitution for an answer that said
     * nothing are properties of a message list and not of which rows reached it,
     * so a second copy of this loop would be a second place for a model's view
     * of its own past to be assembled differently.
     *
     * @param log the rows to render, in conversation order. Never null
     * @param canList {@link AgentDefinition#canList()}, as above
     * @param superseded who decides whether a covered row is drawn — {@link
     *     Superseded#HIDES_A_ROW} for the reading as of now, {@link
     *     Superseded#WAS_WEIGHED_BY_THE_READ} for rows a store already chose
     * @return a mutable list, as above
     */
    public static List<ChatMessage> of(
            List<EntryRecord> log, boolean canList, Superseded superseded) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(superseded, "superseded");
        List<ChatMessage> messages = new ArrayList<>();
        // Insertion-ordered, so the synthetic results come out in the order the
        // model asked for them rather than in a hash's order. A model reading
        // its own batch back in a different order is being shown a turn it did
        // not take.
        Set<String> unanswered = new LinkedHashSet<>();
        // How far the seams so far reach, which is the lower bound of the next
        // one. Carried down the log rather than asked of the store, because it
        // is a property of what is being rendered: a summary this loop skipped
        // is one no model will read, so it cannot be the seam a later sentence
        // counts from. It starts at zero and a first seam therefore reads from
        // turn one; see Compaction.SEAM.
        int foldedThrough = 0;
        List<EntryRecord> shown = new ArrayList<>(log.size());
        for (EntryRecord entry : log) {
            // The kind is unconditional -- a roleless entry is not a message on
            // any reading -- and the fold is not: see Superseded for why the
            // column can only be believed by the caller asking about today.
            boolean covered =
                    superseded == Superseded.HIDES_A_ROW && entry.supersededBy() != null;
            if (!covered && entry.kind().projects()) {
                shown.add(entry);
            }
        }
        for (EntryRecord entry : inTurnSummariesInPlace(shown)) {
            // Before the message and not after it: a result belongs immediately
            // after the assistant turn that asked for it, so anything that is
            // not itself a result closes the batch. The only thing that ever
            // reopens one is another answer, below.
            if (entry.kind() != EntryKind.TOOL_RESULT) {
                pairTheUnanswered(messages, unanswered);
            }
            messages.add(messageFor(entry, unanswered, foldedThrough, canList));
            if (entry.kind() == EntryKind.SUMMARY) {
                // A summary carries the last turn it stands for as its own turn
                // ordinal, and the log is read in conversation order, so this is
                // in step with the seams as the model meets them.
                foldedThrough = entry.turnOrdinal();
            } else if (entry.kind() == EntryKind.TURN_SUMMARY) {
                // An in-turn summary stands for every turn before its own; see
                // EntryStore.foldedThrough, which reads it the same way.
                foldedThrough = Math.max(foldedThrough, entry.turnOrdinal() - 1);
            }
        }
        // A log that ends inside a batch, which is exactly the run that died in
        // one.
        pairTheUnanswered(messages, unanswered);
        return messages;
    }

    private static ChatMessage messageFor(
            EntryRecord entry, Set<String> unanswered, int foldedThrough, boolean canList) {
        return switch (entry.kind()) {
            case UTTERANCE -> ChatMessage.user(entry.content());
            case ANSWER -> {
                for (ToolCall asked : entry.toolCalls()) {
                    unanswered.add(asked.id());
                }
                yield ChatMessage.assistant(answerOf(entry), entry.toolCalls());
            }
            case TOOL_RESULT -> {
                unanswered.remove(entry.toolCallId());
                yield ChatMessage.tool(entry.toolCallId(), entry.content());
            }
            case SUMMARY -> ChatMessage.system(Compaction.seam(canList).formatted(
                    foldedThrough + 1, entry.turnOrdinal(), entry.content()));
            // USER, not SYSTEM: see EntryKind.NOTICE for the measurement. A
            // notice is the harness speaking, but it is rendered the way
            // Reminding's own message is -- appended, never merged into the
            // one system message a run already carries -- so a live turn's
            // prompt and a replayed one agree, and neither ever rewrites a
            // byte that came before it.
            case NOTICE -> ChatMessage.user(entry.content());
            // USER, for NOTICE's reason and one more: it is read in place, inside
            // its turn, which is where inTurnSummariesInPlace put it. It names the
            // turns it took along with the turn's older steps -- from the turn
            // after the seams so far reach to the turn before its own, which is
            // no turn at all when nothing earlier was left to take.
            case TURN_SUMMARY -> ChatMessage.user(Compaction.turnSeam(canList,
                    foldedThrough + 1, entry.turnOrdinal() - 1, entry.content()));
            // Unreachable while the six above are the kinds that project, and
            // kept for the reason ChatMessage keeps its own default arm: a seventh
            // projecting kind added later would otherwise fall through this
            // switch and produce nothing, which is a message silently missing
            // from a request rather than a build that stops.
            default -> throw new IllegalStateException(
                    "entry kind " + entry.kind() + " projects but nothing renders it");
        };
    }

    /**
     * The rows to render, with every in-turn summary moved to where the steps it stands for
     * were: immediately before the first step of its turn still standing (spec
     * 2026-09-30-fold-at-60-and-80 §1 — "the summary is placed where the removed steps were").
     *
     * <h2>Why it has to be moved at all</h2>
     *
     * <p>The log is append-only, so an in-turn summary is written after the steps it keeps and
     * sorts after them. A fold inside a turn always takes the <em>oldest</em> steps still
     * standing and keeps the most recent, so what it stands for is a prefix of the turn's
     * standing steps, and its place is in front of the first of them — after the turn's opening
     * request and whatever the turn opened with, which come before any step. Two summaries of
     * one turn both belong there, in the order they were written, which is the order they
     * appear here.
     *
     * <p><b>A step is an answer</b>, so the first standing answer of the turn is the place:
     * the answer is what opens a step, and the results after it belong to it. A turn with no
     * standing answer left — nothing a fold inside it keeps can produce one, since it keeps at
     * least three steps, but this is a function of whatever it is handed — has its summaries
     * rendered where the first of them sits, which is the end of what the turn shows.
     *
     * <p><b>Nothing else moves</b>, so the order of every other message, and in particular of
     * the {@code USER}-role ones {@code Compaction} counts turns by, is the order the rows came
     * in: a summary only ever moves back past the answers and results of its own turn.
     */
    private static List<EntryRecord> inTurnSummariesInPlace(List<EntryRecord> shown) {
        Map<Integer, List<EntryRecord>> pending = new HashMap<>();
        for (EntryRecord entry : shown) {
            if (entry.kind() == EntryKind.TURN_SUMMARY) {
                pending.computeIfAbsent(entry.turnOrdinal(), turn -> new ArrayList<>())
                        .add(entry);
            }
        }
        if (pending.isEmpty()) {
            return shown;
        }
        List<EntryRecord> ordered = new ArrayList<>(shown.size());
        for (EntryRecord entry : shown) {
            if (entry.kind() == EntryKind.TURN_SUMMARY) {
                // Reached before any answer of its turn: rendered here, with any later summary
                // of the same turn, and skipped when an answer already placed them.
                List<EntryRecord> here = pending.remove(entry.turnOrdinal());
                if (here != null) {
                    ordered.addAll(here);
                }
                continue;
            }
            if (entry.kind() == EntryKind.ANSWER) {
                List<EntryRecord> before = pending.remove(entry.turnOrdinal());
                if (before != null) {
                    ordered.addAll(before);
                }
            }
            ordered.add(entry);
        }
        return ordered;
    }

    /**
     * What an answer that said nothing is rendered as.
     *
     * <p>{@code Outcome} permits an answered turn to carry the empty string — "a
     * model that stops on its first token has answered with nothing, which is a
     * decision and not a failure" — and {@code ChatMessage} refuses an assistant
     * message with neither content nor tool calls. So the absence is rendered
     * rather than dropped, with the same substitution {@code Compaction} already
     * makes for the same row: dropping it would leave an utterance with no reply
     * after it, and a model reading that has been shown a conversation that never
     * took place.
     *
     * <p>An answer that carries calls keeps its empty content, which is not an
     * absence at all — it is a turn that is entirely tool calls, and is one of
     * the two ways {@code Completion} documents arriving with no content.
     */
    private static String answerOf(EntryRecord entry) {
        return entry.content().isBlank() && entry.toolCalls().isEmpty()
                ? Compaction.SAID_NOTHING
                : entry.content();
    }

    private static void pairTheUnanswered(List<ChatMessage> messages, Set<String> unanswered) {
        for (String call : unanswered) {
            messages.add(ChatMessage.tool(call, NEVER_COMPLETED));
        }
        unanswered.clear();
    }
}
