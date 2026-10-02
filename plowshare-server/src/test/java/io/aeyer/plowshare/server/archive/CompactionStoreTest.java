package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Where a conversation's history was folded up, and what the seam has to say.
 *
 * <h2>The two questions, and why they are two methods</h2>
 *
 * <p>{@link CompactionStore#latest} answers what the <em>model</em> is shown:
 * only the newest fold still stands, because each one summarises the previous
 * one along with everything said since. {@link CompactionStore#forConversation}
 * answers what <em>happened</em>: every fold, in the order they fell. A store
 * offering only the first would keep the rows and hide all but one of them,
 * which is the same information loss with a longer audit trail — so both are
 * driven, and each has a fixture holding a row it must not return.
 *
 * <h2>The schema's three rules are asserted by name</h2>
 *
 * <p>{@code TurnStoreTest} makes the argument for V7's seven and it holds here:
 * naming a constraint is what makes Postgres say which rule a bad row broke, and
 * a test asserting only that "some constraint fired" would pass with all three
 * collapsed into one predicate.
 *
 * <p><b>{@code compactions_reach_a_turn_that_was_spoken} is the interesting
 * one.</b> It is a composite key into {@code turns}, so it carries two claims in
 * one: the conversation exists, and the turn this summary says it stands for was
 * really said. Both halves are driven, because a key that only checked the
 * conversation would let a compaction claim to have summarised turns 1 to 40 of
 * a conversation with three.
 */
@Testcontainers
class CompactionStoreTest {

    /** The pgvector image, not stock postgres:16: V1's first line is CREATE
     *  EXTENSION vector, and this class runs the whole migration chain. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Home PAYMENTS = Home.of("payments");

    private static JdbcTemplate jdbc;

    private ConversationStore conversations;
    private TurnStore turns;
    private CompactionStore compactions;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource()).load().migrate();
        jdbc = new JdbcTemplate(dataSource());
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeEach
    void freshCompactions() {
        // All three, because of the keys: `turns` references `conversations`,
        // and V8's `compactions` references `turns`. Postgres refuses to
        // truncate a referenced table unless its referrer is named with it,
        // whether or not either holds a row. Named rather than CASCADE, so a
        // table joining this graph later cannot be emptied by a fixture that
        // does not know about it. `board_topics`, `board_messages` and
        // `board_seats` are here for the same reason: V74 gave each a foreign
        // key into `conversations`. `firings` is a further hop out, through its
        // V74 foreign key into `board_topics`, and `user_inbox` a hop past
        // that, through its pre-existing V40 foreign key into `firings`.
        jdbc.execute("TRUNCATE TABLE orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
        conversations = new ConversationStore(jdbc);
        turns = new TurnStore(jdbc);
        compactions = new CompactionStore(jdbc);
    }

    // --- what a compaction is ---------------------------------------------------

    /**
     * A fold is written down and survives a restart.
     *
     * <p>Read back through a second store over a second connection, so the
     * answer came from Postgres and not from anything the first store kept —
     * which is the whole point of the table, since a conversation whose budget
     * survives a restart with no account of what its turns were folded into is
     * the receipt-with-no-purchase this schema exists against.
     */
    @Test
    void a_compaction_records_how_far_back_it_reaches_and_survives_a_restart() {
        String conversation = twoTurns();

        CompactionRecord written = compactions.record(conversation, 2, "they agreed to roll back");

        assertEquals(new CompactionRecord(conversation, 2, "they agreed to roll back"), written);
        assertEquals(Optional.of(written),
                new CompactionStore(new JdbcTemplate(dataSource())).latest(conversation));
    }

    /**
     * The newest fold is the one that stands, and the older one is still there.
     *
     * <p>Both halves matter and they are opposite mistakes. A {@code latest}
     * that returned the first row inserted, or that ordered by nothing, answers
     * the earlier fold here; a store that <em>replaced</em> the earlier row
     * rather than adding to it passes {@code latest} and fails the listing,
     * which is the loss the table was shaped to prevent.
     */
    @Test
    void the_newest_fold_stands_and_every_earlier_one_is_still_readable() {
        String conversation = threeTurns();
        compactions.record(conversation, 1, "the first summary");
        compactions.record(conversation, 2, "the second summary");

        assertEquals(2, compactions.latest(conversation).orElseThrow().throughOrdinal());
        assertEquals(List.of(1, 2),
                compactions.forConversation(conversation).stream()
                        .map(CompactionRecord::throughOrdinal).toList(),
                "an earlier seam disappeared, so a person can no longer see where it fell");
    }

    /**
     * Both reads filter, so both fixtures hold a fold in a second conversation.
     *
     * <p>The plan's first standing check: a query that filters needs something
     * to filter out. Without the second conversation, a {@code WHERE} dropped
     * from either statement leaves every assertion here green.
     */
    @Test
    void a_fold_belongs_to_exactly_one_conversation() {
        String mine = twoTurns();
        String theirs = twoTurns();
        compactions.record(mine, 1, "mine");
        compactions.record(theirs, 2, "theirs");

        assertEquals(List.of(new CompactionRecord(mine, 1, "mine")),
                compactions.forConversation(mine));
        assertEquals(Optional.of(new CompactionRecord(mine, 1, "mine")),
                compactions.latest(mine),
                "the other conversation's later fold was answered for this one");
    }

    /**
     * A conversation nothing has folded answers empty rather than raising.
     *
     * <p>Every conversation is in that state for most of its life, so refusing
     * would make "nothing has been compacted" indistinguishable from "no such
     * conversation" — which is exactly why the id itself is still refused when
     * it cannot be named, below.
     */
    @Test
    void a_conversation_nothing_has_folded_answers_empty() {
        String conversation = twoTurns();

        assertEquals(Optional.empty(), compactions.latest(conversation));
        assertEquals(List.of(), compactions.forConversation(conversation));
    }

    /** An id nothing can name is refused rather than answered, because it would
     *  otherwise come back as that same empty answer and a dropped identifier
     *  would read as a conversation nobody has compacted. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void an_id_that_cannot_be_named_is_refused_rather_than_answered(String unusable) {
        assertThrows(ValidationException.class, () -> compactions.latest(unusable));
        assertThrows(ValidationException.class, () -> compactions.forConversation(unusable));
        assertThrows(ValidationException.class, () -> compactions.record(unusable, 1, "a summary"));
    }

    // --- what the schema refuses -------------------------------------------------

    /**
     * A fold cannot reach a turn nobody spoke.
     *
     * <p>The first half of the composite key's claim. A compaction saying it
     * stands for turns 1 to 40 of a conversation with two would put a sentence
     * in front of the model naming turns a person could then not find in the
     * transcript.
     */
    @Test
    void a_fold_cannot_reach_past_the_turns_that_were_spoken() {
        String conversation = twoTurns();

        DataIntegrityViolationException refused = assertThrows(
                DataIntegrityViolationException.class,
                () -> compactions.record(conversation, 40, "a summary of turns nobody took"));
        assertTrue(refused.getMessage().contains("compactions_reach_a_turn_that_was_spoken"),
                refused.getMessage());

        // The accepted side, written independently of the refused one: turn 1 is
        // the first thing anybody says, and a fold reaching it is the smallest
        // real fold there is.
        assertEquals(1, compactions.record(conversation, 1, "a summary").throughOrdinal());
    }

    /**
     * And it cannot reach a turn spoken in a different conversation.
     *
     * <p>The second half, and the one a plain foreign key on {@code
     * conversation_id} would miss: the other conversation really does have a
     * turn 2, so a key checking only that some turn with that ordinal exists
     * accepts this row.
     */
    @Test
    void a_fold_cannot_reach_a_turn_from_somebody_elses_conversation() {
        String mine = conversations.open(PAYMENTS, Budget.of(20)).id();
        turns.record(mine, "the only thing I said", "an answer", Ending.ANSWERED, 100, null, null);
        twoTurns();

        DataIntegrityViolationException refused = assertThrows(
                DataIntegrityViolationException.class,
                () -> compactions.record(mine, 2, "a summary of somebody else's turn"));
        assertTrue(refused.getMessage().contains("compactions_reach_a_turn_that_was_spoken"),
                refused.getMessage());
    }

    /** One fold per reach: the same turns summarised twice is a caller that has
     *  lost track of what it already did, and the second row would make a
     *  transcript show two seams in one place. */
    @Test
    void the_same_reach_cannot_be_folded_twice() {
        String conversation = twoTurns();
        compactions.record(conversation, 1, "the summary");

        DataIntegrityViolationException refused = assertThrows(
                DataIntegrityViolationException.class,
                () -> compactions.record(conversation, 1, "the summary again"));
        assertTrue(refused.getMessage().contains("compactions_one_per_reach_in_a_conversation"),
                refused.getMessage());

        // The accepted side: a further reach is a further fold, not a duplicate.
        assertEquals(2, compactions.record(conversation, 2, "a later summary").throughOrdinal());
    }

    /**
     * A summary that says nothing is refused.
     *
     * <p>Not a short history: a blank one is a fold that lost everything and
     * said nothing about it, while the seam sentence in front of the model goes
     * on claiming those turns were summarised. That is the confident-empty-answer
     * shape with a receipt attached.
     */
    @Test
    void a_summary_that_says_nothing_is_refused() {
        String conversation = twoTurns();

        DataIntegrityViolationException refused = assertThrows(
                DataIntegrityViolationException.class,
                () -> compactions.record(conversation, 1, ""));
        assertTrue(refused.getMessage().contains("compactions_summary_says_something"),
                refused.getMessage());
    }

    // --- scaffolding ---------------------------------------------------------------

    private String twoTurns() {
        String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
        turns.record(conversation, "first thing", "the first answer", Ending.ANSWERED,
                120, null, null);
        turns.record(conversation, "second thing", "the second answer", Ending.ANSWERED,
                240, null, null);
        return conversation;
    }

    private String threeTurns() {
        String conversation = twoTurns();
        turns.record(conversation, "third thing", "the third answer", Ending.ANSWERED,
                360, null, null);
        return conversation;
    }
}
