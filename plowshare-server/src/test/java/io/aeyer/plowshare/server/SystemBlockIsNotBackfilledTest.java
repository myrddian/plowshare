package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What {@code V32__turn_system_prompt.sql} does to turns that were already
 * there — which is, deliberately and permanently, nothing.
 *
 * <h2>Why this needs its own class</h2>
 *
 * <p>Every other schema test in this suite runs the whole migration chain into
 * an empty database and then writes, which can only ever exercise a migration
 * against a table with no rows. This one stops at V31, writes the turns a
 * running server would be holding, and then lets V32 land on them — {@code
 * ProjectIdBackfillTest}'s shape, for the opposite question. That one asks
 * whether a backfill kept every row's meaning; this one asks whether a backfill
 * <b>stayed absent</b>.
 *
 * <h2>The failure it exists to catch</h2>
 *
 * <p><b>A backfill here would be silent, plausible and unrecoverable.</b> {@code
 * turns.system_block} is the record of the system block a turn was actually
 * sent, and the projection route reports a turn that has one as having been sent
 * it. Filling the column for historical turns from the agent files as they stand
 * on the day of the deploy would therefore make the server assert, about every
 * turn ever taken, that it went out with today's prompt — which is precisely the
 * claim the column was added to stop it making. Nothing downstream could tell
 * such a row from a real recording, and no later migration could undo it,
 * because the true values are nowhere.
 *
 * <p>So the assertion is the plain one: after V32, every pre-existing turn reads
 * back as not recorded, and {@code system_blocks} is empty. A future editor who
 * adds an {@code UPDATE turns SET system_block = ...} to a later migration fails
 * here rather than in an audit six months on.
 */
@Testcontainers
class SystemBlockIsNotBackfilledTest {

    /** The pgvector image, not stock postgres:16: V1's first line is CREATE
     *  EXTENSION vector, and this class runs the migration chain from V1. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Instant WHEN = Instant.parse("2026-09-05T10:00:00Z");

    /** A person's conversation, which is what `origin = 'turn'` means and is the
     *  only origin that owns its own allowance without lifting it. */
    private static final String CONVERSATION = "cnv_before";

    /** The prompt an agent's file holds on the day of the deploy. Nothing may
     *  put this text anywhere near a row written before it. */
    private static final String TODAYS_FILE =
            "You are the interlocutor, as the file reads on the morning of the deploy.";

    private JdbcTemplate jdbc;

    /**
     * A schema from nothing, the chain up to V31 alone, then the rows a running
     * server would hold.
     *
     * <p>{@code DROP SCHEMA} rather than Flyway's {@code clean}, which is
     * disabled by default and would make this fixture depend on a Flyway setting
     * rather than on SQL — {@code ProjectIdBackfillTest}'s reasoning exactly.
     */
    @BeforeEach
    void schemaAtV31() {
        jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        migrateTo("31");
        seed();
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void migrateTo(String version) {
        Flyway.configure().dataSource(dataSource()).target(version).load().migrate();
    }

    /**
     * Three turns of one conversation, written the way V31 could write them:
     * one that named the agent that answered it and two that did not, because
     * {@code turns.agent} is itself nullable and the older rows in a real
     * archive have nothing there either.
     */
    private void seed() {
        jdbc.update("INSERT INTO conversations"
                        + " (id, created_at, origin, budget_total, budget_spent)"
                        + " VALUES (?, ?, 'turn', 20, 3)",
                CONVERSATION, OffsetDateTime.ofInstant(WHEN, ZoneOffset.UTC));

        turn(1, "what broke the deploy?", "the migration ran twice", 1_200, "talker");
        turn(2, "and before that?", "a retry loop", 1_450, null);
        turn(3, "is it holding now?", "it is", 1_610, null);
    }

    private void turn(int ordinal, String utterance, String answer, int cost, String agent) {
        jdbc.update("INSERT INTO turns"
                        + " (conversation_id, ordinal, utterance, answer, ending, prompt_tokens,"
                        + " agent) VALUES (?, ?, ?, ?, 'ANSWERED', ?, ?)",
                CONVERSATION, ordinal, utterance, answer, cost, agent);
    }

    /**
     * V32 lands on a populated table and every turn already there reads back as
     * not recorded.
     *
     * <p>The whole point of the column, asserted against rows that existed before
     * it did. NULL and not the empty string: a blank would say the turn was sent
     * an empty system message, which is a thing that can be sent and is not what
     * happened here.
     */
    @Test
    void every_turn_that_was_already_there_reads_back_as_not_recorded() {
        migrateTo("32");

        List<String> blocks = jdbc.queryForList(
                "SELECT system_block FROM turns ORDER BY ordinal", String.class);

        assertEquals(3, blocks.size(),
                "the migration must not lose a turn on its way past one");
        for (String block : blocks) {
            assertNull(block,
                    "a turn written before this column existed did not record a block, and"
                            + " anything but NULL here is this server asserting what a past turn"
                            + " was sent");
        }
    }

    /**
     * Nothing was invented to point those turns at, either.
     *
     * <p>The column being NULL is half of the claim; the other half is that the
     * migration did not read the agent directory at all. An empty {@code
     * system_blocks} is what says so: a backfill would have had to write the
     * blocks it was filling the column with, so a row here would be the evidence
     * of one even if the {@code UPDATE} had been reverted and the {@code INSERT}
     * left behind.
     */
    @Test
    void no_block_was_invented_to_fill_them_with() {
        migrateTo("32");

        assertEquals(0, jdbc.queryForObject(
                        "SELECT count(*) FROM system_blocks", Integer.class),
                "V32 creates the table and stores nothing in it: the blocks past turns went out"
                        + " with are not recoverable, and today's files are a fact about today");
    }

    /**
     * The rest of each turn is exactly as it was, which is what makes the NULLs
     * above a deliberate absence rather than a migration that damaged the table.
     */
    @Test
    void the_turns_themselves_are_untouched() {
        migrateTo("32");

        assertEquals(List.of("what broke the deploy?", "and before that?", "is it holding now?"),
                jdbc.queryForList("SELECT utterance FROM turns ORDER BY ordinal", String.class));
        assertEquals(List.of(1_200, 1_450, 1_610),
                jdbc.queryForList("SELECT prompt_tokens FROM turns ORDER BY ordinal",
                        Integer.class));
        assertEquals("talker", jdbc.queryForObject(
                "SELECT agent FROM turns WHERE ordinal = 1", String.class));
    }

    /**
     * A turn written after V32 can only name a block the table holds.
     *
     * <p>{@code turns_a_recorded_block_is_one_this_table_holds}, driven on a
     * database that was populated before the constraint existed — which is where
     * a foreign key added to a live table can fail for a reason an empty-database
     * test never sees. The pre-existing rows are all NULL, so the key is
     * satisfiable, and this is what says the migration would have refused to
     * apply otherwise.
     */
    @Test
    void a_turn_written_after_the_migration_cannot_name_a_block_nothing_stored() {
        migrateTo("32");

        DataIntegrityViolationException refused = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO turns"
                                + " (conversation_id, ordinal, utterance, answer, ending,"
                                + " prompt_tokens, agent, system_block)"
                                + " VALUES (?, 4, 'said', 'answered', 'ANSWERED', 90, 'talker',"
                                + " ?)",
                        CONVERSATION,
                        "0000000000000000000000000000000000000000000000000000000000000000"));

        assertTrue(refused.getMessage()
                        .contains("turns_a_recorded_block_is_one_this_table_holds"),
                "the rule that broke should be the rule by name: " + refused.getMessage());
    }

    /**
     * And a block written after V32 sits beside the old turns without changing
     * them.
     *
     * <p>The state a deployment is actually in the day after this ships: three
     * turns that recorded nothing, one that did, and no way to confuse them.
     * This is what the projection route reads to decide whether it may say "as
     * sent", so the two shapes have to coexist in one table rather than one
     * replacing the other.
     */
    @Test
    void a_turn_written_after_the_migration_records_its_block_beside_the_ones_that_did_not() {
        migrateTo("32");
        String hash = "1f".repeat(32);

        jdbc.update("INSERT INTO system_blocks (hash, body) VALUES (?, ?)", hash, TODAYS_FILE);
        jdbc.update("INSERT INTO turns"
                        + " (conversation_id, ordinal, utterance, answer, ending, prompt_tokens,"
                        + " agent, system_block)"
                        + " VALUES (?, 4, 'and today?', 'today it holds', 'ANSWERED', 1700,"
                        + " 'talker', ?)",
                CONVERSATION, hash);

        assertEquals(List.of(TODAYS_FILE), jdbc.queryForList(
                        "SELECT b.body FROM turns t JOIN system_blocks b"
                                + " ON b.hash = t.system_block WHERE t.ordinal = 4",
                        String.class),
                "the turn that recorded a block answers with it");
        assertEquals(0, jdbc.queryForObject(
                        "SELECT count(*) FROM turns"
                                + " WHERE ordinal < 4 AND system_block IS NOT NULL",
                        Integer.class),
                "and storing today's block for today's turn does not retroactively attach it to"
                        + " the turns that came before, which is the whole distinction");
    }
}
