package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A run that stopped for want of allowance comes back holding what it learned.
 *
 * <h2>The one thing this file is about</h2>
 *
 * <p>{@code Compaction.whatWasSaidAndWhatCameBack} drops every tool result
 * before a turn's history is projected, and that is correct for a <em>later</em>
 * turn: a conversation whose context filled with the files it once read would
 * fold on every turn and recover nothing. A <b>resumed</b> run is not a later
 * turn. It is the same run continuing, and those tool results are precisely what
 * it learned — so a resumed run opened on the ordinary narrowing would come back
 * having forgotten every file it read and would spend the new grant reading them
 * again.
 *
 * <p>So there are two readings of one log, and {@code
 * Compaction.whatWasSaidAndWhatTheRunLearned} is the second. Everything before
 * the turn being continued is read exactly as any turn reads it; the turn being
 * continued is read whole. The tests here are what hold those two halves apart,
 * because an implementation that got either one wrong would still produce a
 * plausible history.
 *
 * <p><b>No database and no model.</b> The function is over a list, so it is
 * tested over a list; what {@code EntryStore} answers with is {@code
 * EntryStoreTest}'s subject, and what a resumed run does with the answer is
 * {@code ResumedRunTest}'s.
 */
class ResumptionTest {

    private static final String CONVERSATION = "cnv_0000000001";

    /**
     * What the agent this history is read for declares, which decides only the
     * <em>earlier</em> half.
     *
     * <p>The turn being continued is read whole either way — that is what
     * resumption is — so every test about that half says {@link
     * #DECLARES_RESULT_READ} and would say the same thing with the other. The
     * two tests about the earlier half are the ones where it matters, and
     * {@code an_earlier_turns_working_is_dropped_for_a_resumed_run_that_cannot_
     * redeem} is the second of them.
     */
    private static final boolean DECLARES_RESULT_READ = true;

    /** An agent that uses tools and does not declare {@code result_read}: it
     *  gets the old drop for everything older than the turn it is continuing,
     *  because a reference it cannot redeem is context spent for nothing. */
    private static final boolean DECLARES_NOTHING = false;

    /** The ordinal of the next entry to be appended, so a fixture does not have
     *  to count them by hand and no two entries in one log collide. */
    private int nextOrdinal = 1;

    // --- the turn being continued, read whole -------------------------------------------

    /**
     * A resumed run comes back with every tool result the stopped turn got.
     *
     * <p><b>The point of the whole design.</b> The stopped turn read two files;
     * the resumed run has to open with what they said, or the grant buys the
     * same two reads again.
     */
    @Test
    void a_resumed_run_comes_back_with_every_tool_result_the_stopped_turn_got() {
        List<EntryRecord> log = new ArrayList<>();
        log.add(utterance(1, "what is in the deploy script"));
        log.add(answer(1, "", List.of(new ToolCall("c1", "read", "{}"))));
        log.add(toolResult(1, "c1", "the deploy script says: set -e"));
        log.add(answer(1, "", List.of(new ToolCall("c2", "read", "{}"))));
        log.add(toolResult(1, "c2", "the rollback script says: kubectl rollout undo"));
        log.add(answer(1, "This run stopped at its turn cap of 2 turns.", List.of()));

        List<EntryRecord> history = Compaction.whatWasSaidAndWhatTheRunLearned(log, 1, DECLARES_RESULT_READ);

        assertEquals(
                List.of("the deploy script says: set -e",
                        "the rollback script says: kubectl rollout undo"),
                contentsOf(history, EntryKind.TOOL_RESULT),
                "a resumed run that cannot see what its tools returned will read the same"
                        + " files again on somebody else's grant");
    }

    /**
     * Every assistant turn the stopped run took is there, and not only the one
     * it came to.
     *
     * <p>The ordinary narrowing keeps a turn's <em>last</em> answer and drops
     * the ones on the way to it, because a later turn wants what a turn
     * concluded and not how it got there. A run continuing itself wants how it
     * got there: the calls it declared are what its results answer, and a
     * history holding results whose calls were dropped is one no endpoint
     * accepts.
     */
    @Test
    void every_assistant_turn_the_stopped_run_took_is_still_in_front_of_it() {
        List<EntryRecord> log = new ArrayList<>();
        log.add(utterance(1, "what is in the deploy script"));
        log.add(answer(1, "Let me look.", List.of(new ToolCall("c1", "read", "{}"))));
        log.add(toolResult(1, "c1", "set -e"));
        log.add(answer(1, "This run stopped at its turn cap of 1 turn.", List.of()));

        List<EntryRecord> history = Compaction.whatWasSaidAndWhatTheRunLearned(log, 1, DECLARES_RESULT_READ);

        assertEquals(
                List.of("Let me look.", "This run stopped at its turn cap of 1 turn."),
                contentsOf(history, EntryKind.ANSWER),
                "the working of the turn being continued is what the run learned, and dropping"
                        + " it leaves its tool results answering calls nothing made");
    }

    /**
     * The utterance of the turn being continued appears exactly once.
     *
     * <p>It is already in the log, at the turn that carried it. A resumed run
     * has no utterance of its own — this is the same question being answered,
     * not a new one — so the history it opens with holds one copy and the
     * resumed turn appends none.
     */
    @Test
    void the_utterance_of_the_turn_being_continued_appears_exactly_once() {
        List<EntryRecord> log = new ArrayList<>();
        log.add(utterance(1, "first thing"));
        log.add(answer(1, "the first answer", List.of()));
        log.add(utterance(2, "what is in the deploy script"));
        log.add(answer(2, "This run stopped at its turn cap of 1 turn.", List.of()));

        List<EntryRecord> history = Compaction.whatWasSaidAndWhatTheRunLearned(log, 2, DECLARES_RESULT_READ);

        assertEquals(List.of("first thing", "what is in the deploy script"),
                contentsOf(history, EntryKind.UTTERANCE));
    }

    // --- everything before it, read the way any turn reads it ---------------------------

    /**
     * An earlier turn's working is referenced, exactly as it is for any turn.
     *
     * <p><b>The half that would be easy to get wrong in the generous
     * direction.</b> A reading that carried every earlier tool result in full
     * would hand a resumed run the whole of a long conversation's working, which
     * is the growth {@code whatWasSaidAndWhatCameBack} exists to refuse and the
     * fold arithmetic is written against.
     *
     * <p><b>This asserted an empty list until references existed, and the
     * change is real rather than cosmetic.</b> An earlier turn's result is no
     * longer dropped; it is replaced by a line naming the tool, the size and a
     * handle — so a resumed run can still <em>reach</em> what an earlier turn
     * read, which is the point, while carrying a bounded line rather than the
     * result. What this now pins is that the content is not the result: the
     * result here is short enough to be cheaper as itself, so the fixture makes
     * it long enough to be worth referencing, which is the case the growth
     * argument is about.
     */
    @Test
    void an_earlier_turns_working_is_referenced_in_a_resumed_run_as_it_is_in_any_turn() {
        String read = "a whole file the first turn read. ".repeat(40);
        List<EntryRecord> log = new ArrayList<>();
        log.add(utterance(1, "first thing"));
        log.add(answer(1, "Let me look.", List.of(new ToolCall("c1", "read", "{}"))));
        log.add(toolResult(1, "c1", read));
        log.add(answer(1, "the first answer", List.of()));
        log.add(utterance(2, "second thing"));
        log.add(answer(2, "This run stopped at its turn cap of 1 turn.", List.of()));

        List<EntryRecord> history = Compaction.whatWasSaidAndWhatTheRunLearned(log, 2, DECLARES_RESULT_READ);

        List<String> results = contentsOf(history, EntryKind.TOOL_RESULT);
        assertEquals(1, results.size(),
                "the result the first turn got did not come back at all: " + results);
        assertFalse(results.get(0).contains(read),
                "an earlier turn's whole result was carried into the resumed run: "
                        + results.get(0));
        assertTrue(results.get(0).contains("read"), results.get(0));
        assertEquals(List.of("Let me look.", "the first answer",
                        "This run stopped at its turn cap of 1 turn."),
                contentsOf(history, EntryKind.ANSWER),
                "the answer that asked for the result must survive with it, or the reference is"
                        + " a tool message no endpoint would accept");
    }

    /**
     * An earlier turn's working is <b>dropped</b> for a resumed run whose agent
     * cannot redeem a reference.
     *
     * <p>The other side of the test above, and the same rule an ordinary turn
     * gets: a reference is worth its line only to an agent that declares {@code
     * result_read}, so an agent that does not gets the previous behaviour —
     * every earlier result and every earlier answer but the last, dropped
     * together, which is what keeps the sequence balanced.
     *
     * <p><b>The turn being continued is untouched.</b> That half is what
     * resumption <em>is</em> — the run's own working, carried whole, so the
     * grant does not buy the same reads twice — and it has nothing to do with
     * redemption. This asserts both halves at once, because an implementation
     * that applied the drop to the whole log would look right from the earlier
     * half alone and would have thrown away the point of the feature.
     */
    @Test
    void an_earlier_turns_working_is_dropped_for_a_resumed_run_that_cannot_redeem() {
        String read = "a whole file the first turn read. ".repeat(40);
        List<EntryRecord> log = new ArrayList<>();
        log.add(utterance(1, "first thing"));
        log.add(answer(1, "Let me look.", List.of(new ToolCall("c1", "read", "{}"))));
        log.add(toolResult(1, "c1", read));
        log.add(answer(1, "the first answer", List.of()));
        log.add(utterance(2, "second thing"));
        log.add(answer(2, "", List.of(new ToolCall("c2", "read", "{}"))));
        log.add(toolResult(2, "c2", "what this run itself read"));
        log.add(answer(2, "This run stopped at its turn cap of 1 turn.", List.of()));

        List<EntryRecord> history =
                Compaction.whatWasSaidAndWhatTheRunLearned(log, 2, DECLARES_NOTHING);

        assertEquals(List.of("what this run itself read"),
                contentsOf(history, EntryKind.TOOL_RESULT),
                "an agent with no way to redeem a handle was handed a reference for the earlier"
                        + " turn, or lost the working of the turn it is continuing: " + history);
        assertEquals(List.of("the first answer", "",
                        "This run stopped at its turn cap of 1 turn."),
                contentsOf(history, EntryKind.ANSWER),
                "the earlier turn's answer that asked for the dropped result was kept, so this"
                        + " run pays for it and for a call nothing answers: " + history);
        assertFalse(Projection.of(history, false).stream()
                        .anyMatch(said -> Projection.NEVER_COMPLETED.equals(said.content())),
                "the drop left a call dangling, so the backstop had to repair a sequence that"
                        + " should have been balanced by construction");
    }

    /**
     * A seam an earlier fold left is still the seam.
     *
     * <p>A summary carries the last turn it stands for as its own turn ordinal,
     * so it sits among the turns it covers and is read out of the earlier half
     * like anything else there. Resumption changes what a run is shown about the
     * turn it is continuing and changes nothing about what a fold did.
     */
    @Test
    void a_fold_an_earlier_turn_took_is_still_a_seam_for_a_resumed_run() {
        List<EntryRecord> log = new ArrayList<>();
        log.add(superseded(utterance(1, "first thing"), 3));
        log.add(superseded(answer(1, "the first answer", List.of()), 3));
        log.add(summary(1, "They discussed the deploy."));
        log.add(utterance(2, "second thing"));
        log.add(answer(2, "This run stopped at its turn cap of 1 turn.", List.of()));

        List<EntryRecord> history = Compaction.whatWasSaidAndWhatTheRunLearned(log, 2, DECLARES_RESULT_READ);

        assertEquals(List.of("They discussed the deploy."),
                contentsOf(history, EntryKind.SUMMARY));
        assertEquals(
                List.of(Compaction.SEAM.formatted(1, 1, "They discussed the deploy."),
                        "second thing", "This run stopped at its turn cap of 1 turn."),
                Projection.of(history, false).stream().map(ChatMessage::content).toList(),
                "a folded turn stays folded for a resumed run: the seam is rendered in its"
                        + " place and the turns behind it are not read");
    }

    // --- what must not happen ------------------------------------------------------------

    /**
     * A dangling tool call never reaches the model.
     *
     * <p>The endings resumption accepts all stop at the top of the loop, after
     * the previous iteration appended its results — so the history is whole and
     * there is nothing for {@link Projection#NEVER_COMPLETED} to stand in for.
     * <b>This asserts the scope rather than trusting it</b>: an ending that
     * stopped mid-turn would show up here as a synthetic result, which is
     * exactly the shape §2 of the design scopes those endings out for.
     */
    @Test
    void a_stopped_run_whose_results_all_arrived_carries_no_synthetic_result() {
        List<EntryRecord> log = new ArrayList<>();
        log.add(utterance(1, "what is in the deploy script"));
        log.add(answer(1, "", List.of(new ToolCall("c1", "read", "{}"))));
        log.add(toolResult(1, "c1", "set -e"));
        log.add(answer(1, "This run stopped at its turn cap of 1 turn.", List.of()));

        List<ChatMessage> messages =
                Projection.of(Compaction.whatWasSaidAndWhatTheRunLearned(
                        log, 1, DECLARES_RESULT_READ), false);

        assertFalse(
                messages.stream().anyMatch(m -> Projection.NEVER_COMPLETED.equals(m.content())),
                "a run that stopped at a turn boundary left no call unanswered, so nothing"
                        + " should be standing in for one: " + messages);
        assertEquals(1, messages.stream()
                        .filter(m -> m.role() == ChatMessage.Role.TOOL).count(),
                "one call was made and one result answers it");
    }

    /**
     * A tool call the stopped run made is not asked for again.
     *
     * <p>The resumed run opens with the call <em>and</em> its answer, in that
     * order, which is what makes re-running it unnecessary rather than
     * forbidden: a model reading a call it already made and the result it
     * already got has no reason to make it twice.
     */
    @Test
    void a_call_the_stopped_run_made_comes_back_paired_with_the_answer_it_got() {
        List<EntryRecord> log = new ArrayList<>();
        log.add(utterance(1, "what is in the deploy script"));
        log.add(answer(1, "", List.of(new ToolCall("c1", "read", "{}"))));
        log.add(toolResult(1, "c1", "set -e"));
        log.add(answer(1, "This run stopped at its turn cap of 1 turn.", List.of()));

        List<ChatMessage> messages =
                Projection.of(Compaction.whatWasSaidAndWhatTheRunLearned(
                        log, 1, DECLARES_RESULT_READ), false);

        int declared = indexOf(messages, ChatMessage.Role.ASSISTANT, "");
        int answered = indexOf(messages, ChatMessage.Role.TOOL, "set -e");
        assertTrue(declared >= 0 && answered == declared + 1,
                "the result belongs immediately after the call it answers: " + messages);
    }

    /**
     * Nothing filed against a later turn reaches a run continuing an earlier
     * one.
     *
     * <p>Not a shape resumption produces — a grant continues the conversation's
     * last turn, so there is nothing after it — and asserted anyway, because
     * what this function must never do is hand a run a history in which the
     * answer it is about to write has already been given.
     */
    @Test
    void nothing_said_after_the_turn_being_continued_reaches_the_run_continuing_it() {
        List<EntryRecord> log = new ArrayList<>();
        log.add(utterance(1, "first thing"));
        log.add(answer(1, "This run stopped at its turn cap of 1 turn.", List.of()));
        log.add(utterance(2, "second thing"));
        log.add(answer(2, "the second answer", List.of()));

        List<EntryRecord> history = Compaction.whatWasSaidAndWhatTheRunLearned(log, 1, DECLARES_RESULT_READ);

        assertEquals(List.of("first thing"), contentsOf(history, EntryKind.UTTERANCE));
        assertEquals(List.of("This run stopped at its turn cap of 1 turn."),
                contentsOf(history, EntryKind.ANSWER));
    }

    // --- fixtures -------------------------------------------------------------------------

    private EntryRecord utterance(int turnOrdinal, String content) {
        return entry(turnOrdinal, EntryKind.UTTERANCE, content, null, List.of());
    }

    private EntryRecord answer(int turnOrdinal, String content, List<ToolCall> calls) {
        return entry(turnOrdinal, EntryKind.ANSWER, content, null, calls);
    }

    private EntryRecord toolResult(int turnOrdinal, String callId, String content) {
        return entry(turnOrdinal, EntryKind.TOOL_RESULT, content, callId, List.of());
    }

    private EntryRecord summary(int turnOrdinal, String content) {
        return entry(turnOrdinal, EntryKind.SUMMARY, content, null, List.of());
    }

    private EntryRecord entry(
            int turnOrdinal, EntryKind kind, String content, String callId,
            List<ToolCall> calls) {
        // A handle for a result and none for anything else, which is what
        // EntryStore.append mints and what entries_only_a_tool_result_is_
        // addressable permits. A fixture that gave every entry one would be
        // building rows the table refuses.
        // No stamp and no duration: this fixture is about what a resumed run
        // reads, and V16's two columns are read by nothing on that path. An
        // entry written before V16 carries the same two nulls.
        return new EntryRecord(
                CONVERSATION, nextOrdinal++, kind, content, callId, calls, null,
                kind == EntryKind.TOOL_RESULT ? UUID.randomUUID() : null, turnOrdinal, null,
                null, null, null);
    }

    /** The same entry, as a fold left it. */
    private static EntryRecord superseded(EntryRecord entry, int by) {
        return new EntryRecord(entry.conversationId(), entry.ordinal(), entry.kind(),
                entry.content(), entry.toolCallId(), entry.toolCalls(), by, entry.handle(),
                entry.turnOrdinal(), entry.recordedAt(), entry.tookMillis(), entry.ejectedAt(),
                entry.export());
    }

    private static List<String> contentsOf(List<EntryRecord> history, EntryKind kind) {
        return history.stream()
                .filter(entry -> entry.kind() == kind)
                .map(EntryRecord::content)
                .toList();
    }

    private static int indexOf(List<ChatMessage> messages, ChatMessage.Role role, String content) {
        for (int at = 0; at < messages.size(); at++) {
            ChatMessage message = messages.get(at);
            if (message.role() == role && content.equals(message.content())) {
                return at;
            }
        }
        return -1;
    }
}
