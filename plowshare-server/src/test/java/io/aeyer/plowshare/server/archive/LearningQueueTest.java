package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Speaker;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The work queue {@code V20__entry_learning.sql} adds: what is folded and not
 * yet learned, and what marking it does.
 *
 * <h2>Why this is its own file</h2>
 *
 * <p>{@code EntryStoreTest} asks what the table accepts. This asks what one
 * query selects, and the answer is a policy rather than a constraint — which
 * kinds are worth a model call, and what "waiting" means for a row that a fold
 * has covered. V20 deliberately puts none of that in a CHECK, so a test is the
 * only place it is held down.
 *
 * <p><b>The decoupling has a test of its own here.</b> Learning and ejection are
 * separate workflows and the owner was explicit about it; the way that stops
 * being true is a constraint or a query somebody adds later that makes one wait
 * for the other. {@link
 * #a_payload_may_be_ejected_having_never_been_learned()} is what fails on the
 * build that couples them.
 */
@Testcontainers
class LearningQueueTest {

    /** The pgvector image, not stock postgres:16: V1's first line is CREATE
     *  EXTENSION vector, and this class runs the whole migration chain. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Home PAYMENTS = Home.of("payments");

    /** A fixed moment, so a stamp in an assertion is one somebody chose. */
    private static final Instant THEN = Instant.parse("2026-09-04T11:00:00Z");

    /** How many the queue is asked for when the number is not what is being
     *  asserted — comfortably past every fixture in this file. */
    private static final int PLENTY = 50;

    private static JdbcTemplate jdbc;

    private ConversationStore conversations;
    private EntryStore entries;
    private String conversation;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void freshConversations() {
        // board_topics, board_messages and board_seats: V74 gave each a foreign
        // key into conversations. firings: a further hop out, through its V74
        // foreign key into board_topics. user_inbox: a hop past that, through
        // its pre-existing V40 foreign key into firings. Postgres refuses to
        // truncate conversations unless every one of these goes with it.
        jdbc.execute("TRUNCATE TABLE orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
        conversations = new ConversationStore(jdbc);
        entries = new EntryStore(jdbc);
        conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
    }

    /**
     * A folded entry is what the learner is waiting on.
     *
     * <p>The whole of the queue's positive case: a fold is the moment a span
     * stops being visible to a model, so it is the moment its text is either
     * extracted or lost to the summary that stands in its place.
     */
    @Test
    void a_folded_entry_is_waiting_for_the_learner() {
        int said = entries.append(conversation, 1, LoggedEntry.utterance("we roll back on red",
                Speaker.person(null)))
                .ordinal();
        fold(1, 1);

        List<EntryRecord> waiting = entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, PLENTY);

        assertEquals(List.of(said), waiting.stream().map(EntryRecord::ordinal).toList());
    }

    /**
     * A live entry is not.
     *
     * <p>"Only folded" is principled rather than convenient: a live row is still
     * in the model's own view of the conversation, so nothing is lost by not
     * mining it yet.
     */
    @Test
    void a_live_entry_is_not_waiting_because_it_is_still_in_the_conversation() {
        entries.append(conversation, 1, LoggedEntry.utterance("we roll back on red",
                Speaker.person(null)));

        assertTrue(entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, PLENTY).isEmpty());
    }

    /**
     * A live entry <em>is</em> waiting once nobody can speak into the
     * conversation again.
     *
     * <p><b>The premise of "only folded", withdrawn where it stops holding.</b>
     * The test above is principled because a live row is still in the model's
     * own view, so there is a later moment to mine it in. {@code Turn.speak}
     * refuses a turn into anything that is not {@code ACTIVE}, so for an
     * archived conversation there is no later moment: its rows are live only in
     * the sense that no fold happened to cover them.
     *
     * <p>What this reaches is the shape the design keeps naming and never
     * reached — a run that never folds. Tool results do not push a fold, so "ask
     * a question, read one 100 000-character file, answer, done" supersedes
     * nothing at all and had no row in this queue on any terms.
     */
    @Test
    void a_live_entry_is_waiting_once_nobody_can_speak_into_the_conversation() {
        int said = entries.append(conversation, 1, LoggedEntry.utterance("we roll back on red",
                Speaker.person(null)))
                .ordinal();
        conversations.moveTo(conversation, ConversationLifecycle.ARCHIVED);

        List<EntryRecord> waiting =
                entries.awaitingTheLearner(conversation, ConversationLifecycle.ARCHIVED, PLENTY);

        assertEquals(List.of(said), waiting.stream().map(EntryRecord::ordinal).toList());
    }

    /**
     * And in every state that is not {@code ACTIVE}, which is one question and
     * not a list of three.
     *
     * <p>{@code ConversationLifecycle.acceptsWork} is the condition, and it is
     * the same method {@code Turn} asks before refusing a turn. V20 names {@code
     * archived} and {@code to_be_ejected} because those are the two somebody
     * <em>moves</em> a conversation to; {@code ejected} is the same argument
     * carried one state further, and excluding it would have been an omission
     * rather than a decision. A fifth state added to the enum arrives here
     * answered.
     */
    @Test
    void every_state_that_takes_no_further_turn_offers_its_live_rows() {
        entries.append(conversation, 1, LoggedEntry.utterance("we roll back on red",
                Speaker.person(null)));

        for (ConversationLifecycle standing : ConversationLifecycle.values()) {
            assertEquals(standing.acceptsWork(),
                    entries.awaitingTheLearner(conversation, standing, PLENTY).isEmpty(),
                    "a live row is offered exactly when the tree takes no further turn, and "
                            + standing + " disagreed");
        }
    }

    /**
     * The bytes decision is not widened with the tree.
     *
     * <p>{@code kind <> 'tool_result'} is the clause that survives every change
     * to what "waiting" means, and the sharp reason is retention: results are
     * exactly what a sweep ejects, so a window built on them would be a window
     * whose value depended on racing a sweep this workflow is deliberately not
     * coupled to. A tree at {@code to_be_ejected} is where that race is closest
     * to being lost.
     */
    @Test
    void a_live_tool_result_is_not_in_the_widened_window_either() {
        entries.append(conversation, 1, LoggedEntry.answer("reading it",
                List.of(new ToolCall("call_1", "file_read", "{\"path\":\"a.txt\"}"))));
        entries.append(conversation, 1, LoggedEntry.toolResult("call_1", "the whole file"));
        conversations.moveTo(conversation, ConversationLifecycle.ARCHIVED);
        conversations.moveTo(conversation, ConversationLifecycle.TO_BE_EJECTED);

        List<EntryKind> kinds = entries.awaitingTheLearner(
                        conversation, ConversationLifecycle.TO_BE_EJECTED, PLENTY)
                .stream().map(EntryRecord::kind).toList();

        assertEquals(List.of(EntryKind.ANSWER), kinds);
    }

    /**
     * A live row handed over once is never handed over again, and folding it
     * afterwards does not put it back.
     *
     * <p><b>What stops the learner re-reading an archived tree every pass.</b>
     * {@code learned_at IS NULL} is not part of the half that widened — it is
     * asked of every row whatever the tree's state — and {@code markLearned}
     * never asks whether a row was folded, so a live row leaves this queue on
     * exactly the terms a folded one does. The second half of this test is the
     * composition the widening could have broken: {@code SUPERSEDE} does not
     * clear the stamp, so a row learned while live and folded afterwards stays
     * out rather than arriving back as new material.
     */
    @Test
    void a_live_row_already_handed_over_is_not_offered_again() {
        int said = entries.append(conversation, 1, LoggedEntry.utterance("we roll back on red",
                Speaker.person(null)))
                .ordinal();
        conversations.moveTo(conversation, ConversationLifecycle.ARCHIVED);

        assertEquals(1, entries.markLearned(conversation, List.of(said), THEN));

        assertTrue(entries.awaitingTheLearner(conversation, ConversationLifecycle.ARCHIVED, PLENTY)
                .isEmpty());

        fold(1, 1);

        assertTrue(entries.awaitingTheLearner(conversation, ConversationLifecycle.ARCHIVED, PLENTY)
                .stream().noneMatch(row -> row.ordinal() == said));
        assertEquals(THEN, learnedAt(said));
    }

    /**
     * A folded tool result is not in the window although it is folded.
     *
     * <p>The bytes decision, held down where it is made. Results are where the
     * facts are and they are the bulk of the log — 34 MB of it against 1.9 MB of
     * projected history, measured in {@code
     * implementation rationale} — and they are also
     * exactly what retention ejects. A window built on them would be a window
     * built on the material most likely to be gone by the time it is read.
     */
    @Test
    void a_folded_tool_result_is_not_in_the_window() {
        entries.append(conversation, 1, LoggedEntry.answer("reading it",
                List.of(new ToolCall("call_1", "file_read", "{\"path\":\"a.txt\"}"))));
        entries.append(conversation, 1, LoggedEntry.toolResult("call_1", "the whole file"));
        fold(1, 1);

        List<EntryKind> kinds =
                entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, PLENTY).stream()
                        .map(EntryRecord::kind).toList();

        assertEquals(List.of(EntryKind.ANSWER), kinds);
    }

    /**
     * Neither is anything the harness said to itself.
     *
     * <p>A {@code diagnostic} carries no role and cannot reach a model by
     * construction; a fold covers one anyway, because {@code supersede} is over
     * a span and not over a list of kinds. Selecting one here would spend a
     * model call asking whether this server's own note about its arithmetic is
     * worth remembering.
     */
    @Test
    void a_folded_diagnostic_is_not_waiting_because_it_is_the_harness_talking_to_itself() {
        entries.append(conversation, 1, LoggedEntry.diagnostic("a compaction was triggered"));
        fold(1, 1);

        assertTrue(entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, PLENTY).isEmpty());
    }

    /** In the order the conversation held them, and no more than were asked for. */
    @Test
    void the_queue_is_in_conversation_order_and_is_bounded_by_what_was_asked_for() {
        entries.append(conversation, 1, LoggedEntry.utterance("first", Speaker.person(null)));
        entries.append(conversation, 1, LoggedEntry.answer("second", List.of()));
        entries.append(conversation, 2, LoggedEntry.utterance("third", Speaker.person(null)));
        fold(2, 2);

        assertEquals(List.of("first", "second", "third"),
                entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, PLENTY).stream()
                        .map(EntryRecord::content).toList());
        assertEquals(List.of("first", "second"),
                entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, 2).stream()
                        .map(EntryRecord::content).toList());
    }

    /** Marking what was handed over takes it out of the queue and leaves the rest. */
    @Test
    void marking_what_was_handed_over_takes_it_out_of_the_queue() {
        int first = entries.append(conversation, 1, LoggedEntry.utterance("first",
                Speaker.person(null))).ordinal();
        entries.append(conversation, 1, LoggedEntry.answer("second", List.of()));
        fold(1, 1);

        assertEquals(1, entries.markLearned(conversation, List.of(first), THEN));

        assertEquals(List.of("second"),
                entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, PLENTY).stream()
                        .map(EntryRecord::content).toList());
        assertEquals(THEN, learnedAt(first));
    }

    /**
     * And marking is write-once.
     *
     * <p>{@code ejectPayload}'s rule one column over, for its reason: when the
     * learner was given a row is a fact about when it happened, and a second
     * pass that restamped it would be a pass claiming work it did not do.
     */
    @Test
    void marking_is_write_once() {
        int said = entries.append(conversation, 1, LoggedEntry.utterance("first",
                Speaker.person(null))).ordinal();
        fold(1, 1);
        entries.markLearned(conversation, List.of(said), THEN);

        assertEquals(0, entries.markLearned(conversation, List.of(said), THEN.plusSeconds(600)));
        assertEquals(THEN, learnedAt(said));
    }

    /** Marking nothing asks the database nothing and says so. */
    @Test
    void marking_an_empty_window_is_not_a_statement() {
        assertEquals(0, entries.markLearned(conversation, List.of(), THEN));
    }

    /**
     * A payload may be ejected having never been learned.
     *
     * <p><b>The decoupling, as a row.</b> Ejection is its own workflow and
     * learning does not gate it — the owner corrected an earlier draft that
     * gated one on the other, and the case that settles it is that the largest
     * payloads in the system belong to runs that never fold at all. This is the
     * shape a coupling would refuse: content gone, {@code ejected_at} stamped,
     * {@code learned_at} still NULL.
     */
    @Test
    void a_payload_may_be_ejected_having_never_been_learned() {
        entries.append(conversation, 1, LoggedEntry.answer("reading it",
                List.of(new ToolCall("call_1", "file_read", "{\"path\":\"a.txt\"}"))));
        int result = entries.append(conversation, 1,
                LoggedEntry.toolResult("call_1", "the whole file")).ordinal();

        assertTrue(entries.ejectPayload(conversation, result, THEN, "/exports/a"));

        assertNull(learnedAt(result));
        assertTrue(entries.payloadsHeldBy(conversation).isEmpty());
    }

    /** The conversations holding folded material are the ones a pass can act on. */
    @Test
    void the_conversations_awaiting_the_learner_are_the_ones_holding_folded_material() {
        String quiet = conversations.open(PAYMENTS, Budget.of(20)).id();
        entries.append(quiet, 1, LoggedEntry.utterance("still live", Speaker.person(null)));
        entries.append(conversation, 1, LoggedEntry.utterance("folded away", Speaker.person(null)));
        fold(1, 1);

        assertEquals(List.of(conversation), entries.conversationsAwaitingTheLearner(PLENTY));

        entries.markLearned(conversation, List.of(1), THEN);
        assertTrue(entries.conversationsAwaitingTheLearner(PLENTY).isEmpty());
    }

    /**
     * A conversation that never folded reaches the shortlist the moment it is
     * archived, and not before.
     *
     * <p><b>This is the whole gap.</b> Ranking is ordering <em>within</em> a
     * shortlist, so {@code archived} and {@code to_be_ejected} could only ever
     * reorder conversations that had already folded something. A one-turn run
     * has nothing superseded, so it was never a candidate at all and the two
     * states were decorative for exactly the shape they were introduced for.
     */
    @Test
    void a_conversation_that_never_folded_reaches_the_shortlist_once_it_is_archived() {
        String quiet = conversations.open(PAYMENTS, Budget.of(20)).id();
        entries.append(quiet, 1, LoggedEntry.utterance("one question, one file, one answer",
                Speaker.person(null)));
        entries.append(conversation, 1, LoggedEntry.utterance("still being spoken into",
                Speaker.person(null)));

        assertTrue(entries.conversationsAwaitingTheLearner(PLENTY).isEmpty());

        conversations.moveTo(quiet, ConversationLifecycle.ARCHIVED);

        assertEquals(List.of(quiet), entries.conversationsAwaitingTheLearner(PLENTY));
    }

    /**
     * A branch of a finished tree is finished, although the branch carries no
     * state of its own.
     *
     * <p>The lifecycle lives on the root and a child's column is NULL, so the
     * shortlist walks <em>down</em> from the roots that take no further turn
     * rather than reading a column off the child. A {@code code_reviewer}
     * delegated a repository is the run that stores the most and folds least,
     * so a shortlist that only saw roots would miss the larger half of what this
     * widening is for.
     */
    @Test
    void a_branch_of_a_finished_tree_reaches_the_shortlist_too() {
        String root = conversations.open(PAYMENTS, Budget.of(20)).id();
        String child = conversations.log(
                Origin.DELEGATION, PAYMENTS, "code_reviewer", root, null).id();
        entries.append(child, 1, LoggedEntry.utterance("read this repository",
                Speaker.person(null)));

        assertTrue(entries.conversationsAwaitingTheLearner(PLENTY).isEmpty());

        conversations.moveTo(root, ConversationLifecycle.ARCHIVED);

        assertEquals(List.of(child), entries.conversationsAwaitingTheLearner(PLENTY));
    }

    /**
     * The partial index V20 wrote is still the index that serves the folded half
     * of the queue.
     *
     * <p><b>Asked of the store's own SQL and not a copy of it</b>, for {@code
     * DocumentStoreTest.explainSearch}'s reason: a test that explained a query
     * it wrote itself would go on passing after the store's stopped being
     * index-servable, which is the whole failure it is here to catch. That
     * failure is a live one here — {@code entries_awaiting_the_learner} is
     * written against {@code superseded_by IS NOT NULL AND learned_at IS NULL},
     * and a widening that folded both halves of the queue into one {@code OR}
     * would have left it covering neither. The two halves are a {@code UNION
     * ALL} so that this one keeps its index and the other is driven from
     * {@code conversations_under_retention} instead.
     */
    @Test
    void the_folded_half_of_the_queue_is_still_served_by_its_partial_index() {
        String plan = explain(EntryStore.CONVERSATIONS_AWAITING_THE_LEARNER);

        assertTrue(plan.contains("entries_awaiting_the_learner"),
                "V20's partial index no longer serves the queue it was written for:\n" + plan);
    }

    /**
     * Postgres's plan for one query, with {@code enable_seqscan} off.
     *
     * <p>Off, because on a fixture of a few rows the planner will scan whatever
     * the SQL says and the answer would be about the fixture rather than about
     * the query. What is asked is the narrower and far more durable question:
     * <em>can</em> this be served by the index at all.
     */
    private String explain(String sql) {
        return String.join("\n", jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<List<String>>) connection -> {
                    try (var off = connection.createStatement()) {
                        off.execute("SET enable_seqscan = off");
                    }
                    try (var explained = connection.prepareStatement("EXPLAIN " + sql)) {
                        explained.setInt(1, PLENTY);
                        List<String> lines = new java.util.ArrayList<>();
                        try (var rows = explained.executeQuery()) {
                            while (rows.next()) {
                                lines.add(rows.getString(1));
                            }
                        }
                        return lines;
                    }
                }));
    }

    /**
     * A conversation id nobody can name is refused rather than answered empty.
     *
     * <p>{@code EntryStore}'s standing rule: an empty list is the true answer
     * for a conversation holding nothing, so a dropped identifier must not
     * arrive as one.
     */
    @Test
    void an_unusable_conversation_id_is_refused() {
        assertThrows(ValidationException.class, () -> entries.awaitingTheLearner("  ", ConversationLifecycle.ACTIVE, PLENTY));
        assertThrows(ValidationException.class,
                () -> entries.markLearned("  ", List.of(1), THEN));
    }

    /**
     * Folds the log through {@code through}, exactly as {@code Compaction} does:
     * a summary entry carrying the reach as its own turn ordinal, and every
     * uncovered row up to it pointed at it.
     */
    private void fold(int turnOrdinal, int through) {
        EntryRecord summary =
                entries.append(conversation, turnOrdinal, LoggedEntry.summary("they talked"));
        entries.supersede(conversation, 0, through, summary.ordinal());
    }

    /** The column itself, which no record carries and no reader reports. */
    private Instant learnedAt(int ordinal) {
        Timestamp stamped = jdbc.queryForObject(
                "SELECT learned_at FROM entries WHERE conversation_id = ? AND ordinal = ?",
                Timestamp.class, conversation, ordinal);
        return stamped == null ? null : stamped.toInstant();
    }
}
