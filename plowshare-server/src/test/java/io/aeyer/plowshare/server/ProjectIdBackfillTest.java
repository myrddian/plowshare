package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * What {@code V14__project_ids.sql} does to rows that were already there.
 *
 * <p>Every other schema test in this suite runs the whole migration chain at
 * once and then writes, which can only ever exercise a migration against an
 * empty table. This one stops at V13, writes the rows a running server would
 * have, and then lets V14 land on them — which is the only way to ask the
 * question the migration is actually for: <b>does a memory keep the tier it was
 * in?</b>
 *
 * <p>The failure this exists to catch is silent and unrecoverable. V14 drops
 * {@code memories.project} after filling {@code memories.project_id} from it; a
 * row whose name did not resolve would lose its project and arrive in the
 * global tier — the tier every agent everywhere reads — with nothing in the log
 * to say it moved. V1's whole argument for spelling global as NULL is that no
 * project can be named into it, and a backfill that leaves a NULL behind is that
 * rule broken by the schema rather than by a caller.
 */
@Testcontainers
class ProjectIdBackfillTest {

    /** The pgvector image, not stock postgres:16: V1's first line is CREATE
     *  EXTENSION vector, and this class runs the migration chain from V1. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Instant WHEN = Instant.parse("2026-09-03T10:00:00Z");

    /** A project an operator had defined: it has a workspace and a row. */
    private static final String DEFINED = "payments";

    /** A project that has memories and conversations and <em>no row</em>, which
     *  is the ordinary state of every project nobody ran {@code define} on.
     *  V3's own comment says so: "A project may hold memories with no
     *  workspace (every project did, before this slice)." */
    private static final String UNDEFINED = "ledger";

    private JdbcTemplate jdbc;

    /**
     * A schema from nothing each time, then the chain up to V13 alone.
     *
     * <p>{@code DROP SCHEMA} rather than Flyway's {@code clean}, which is
     * disabled by default and would make this fixture depend on a Flyway
     * setting rather than on SQL. The container is shared across the class, as
     * everywhere else here; what is not shared is the schema.
     */
    @BeforeEach
    void schemaAtV13() {
        jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        migrateTo("13");
        seed();
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void migrateTo(String version) {
        Flyway.configure().dataSource(dataSource()).target(version).load().migrate();
    }

    /** The rows a server running on V13 would be holding: one project with a
     *  workspace, one without, and the global tier in both tables. */
    private void seed() {
        jdbc.update("INSERT INTO projects (name, workspace) VALUES (?, ?)", DEFINED, "/tmp");

        memory("mem_global", null);
        memory("mem_defined", DEFINED);
        memory("mem_undefined", UNDEFINED);

        conversation("cnv_global", null);
        conversation("cnv_defined", DEFINED);
        conversation("cnv_undefined", UNDEFINED);
    }

    private void memory(String id, String project) {
        jdbc.update("INSERT INTO memories"
                        + " (id, project, summary, scope, body, state, formed_at, formed_by,"
                        + " formed_where) VALUES (?, ?, 's', 'sc', 'b', 'active', ?, 'probe',"
                        + " 'test')",
                id, project, OffsetDateTime.ofInstant(WHEN, ZoneOffset.UTC));
    }

    private void conversation(String id, String project) {
        jdbc.update("INSERT INTO conversations"
                        + " (id, project, created_at, budget_total, budget_spent)"
                        + " VALUES (?, ?, ?, 4, 0)",
                id, project, OffsetDateTime.ofInstant(WHEN, ZoneOffset.UTC));
    }

    private String projectOf(String table, String id) {
        return jdbc.queryForObject(
                "SELECT p.name FROM " + table + " t LEFT JOIN projects p ON p.id = t.project_id"
                        + " WHERE t.id = ?",
                String.class, id);
    }

    // --- the backfill ---------------------------------------------------------

    @Test
    void a_memory_keeps_the_project_it_named() {
        migrateTo("14");

        assertEquals(DEFINED, projectOf("memories", "mem_defined"));
    }

    @Test
    void a_conversation_keeps_the_project_it_named() {
        migrateTo("14");

        assertEquals(DEFINED, projectOf("conversations", "cnv_defined"));
    }

    /**
     * The tier that must not move, and the reason this whole class exists: a
     * backfill that failed to resolve a name would leave NULL here and be
     * indistinguishable from a memory that was global all along.
     */
    @Test
    void a_global_memory_is_still_global() {
        migrateTo("14");

        assertNull(jdbc.queryForObject(
                "SELECT project_id FROM memories WHERE id = 'mem_global'", Long.class));
        assertNull(jdbc.queryForObject(
                "SELECT project_id FROM conversations WHERE id = 'cnv_global'", Long.class));
    }

    /**
     * The orphan decision, asserted rather than described. A memory naming a
     * project with no row is not a broken memory — under V3 that is what every
     * project without a workspace looks like — so V14 registers the project it
     * names instead of dropping the row or promoting it to global.
     */
    @Test
    void a_project_named_only_by_a_memory_is_registered_without_a_workspace() {
        migrateTo("14");

        assertEquals(UNDEFINED, projectOf("memories", "mem_undefined"));
        assertEquals(UNDEFINED, projectOf("conversations", "cnv_undefined"));
        assertNull(jdbc.queryForObject(
                "SELECT workspace FROM projects WHERE name = ?", String.class, UNDEFINED));
    }

    /** One row, not two, for a name both tables named. */
    @Test
    void a_project_named_by_both_tables_is_registered_once() {
        migrateTo("14");

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM projects WHERE name = ?", Integer.class, UNDEFINED));
    }

    /** The project that already had a row keeps the workspace it was given. */
    @Test
    void a_defined_project_keeps_its_workspace() {
        migrateTo("14");

        assertEquals("/tmp", jdbc.queryForObject(
                "SELECT workspace FROM projects WHERE name = ?", String.class, DEFINED));
    }

    // --- the correctness gain -------------------------------------------------

    /**
     * <b>The constraint {@code memories.project} could never have carried.</b>
     * V1 created {@code memories} before V3 created {@code projects}, so there
     * was no table to reference; a memory naming a project that does not exist
     * has been writable since the first migration and nothing noticed. The
     * surrogate reference is the first time the database can refuse it.
     */
    @Test
    void a_memory_cannot_point_at_a_project_that_does_not_exist() {
        migrateTo("14");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO memories"
                        + " (id, project_id, summary, scope, body, state, formed_at, formed_by,"
                        + " formed_where) VALUES ('mem_dangling', 987654321, 's', 'sc', 'b',"
                        + " 'active', now(), 'probe', 'test')"));
    }

    @Test
    void a_conversation_cannot_point_at_a_project_that_does_not_exist() {
        migrateTo("14");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO conversations (id, project_id, created_at, budget_total)"
                        + " VALUES ('cnv_dangling', 987654321, now(), 4)"));
    }

    // --- the natural key ------------------------------------------------------

    /**
     * The name stops being the primary key and has to stay unique anyway: it is
     * what {@code ProjectStore}'s upsert conflicts on and what every caller
     * resolves by, and two rows sharing one would make "the project called
     * payments" a question with two answers.
     */
    @Test
    void a_project_name_is_still_unique() {
        migrateTo("14");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO projects (name, workspace) VALUES (?, '/tmp')", DEFINED));
    }

    /** Distinct rows get distinct ids, which is the whole of what a surrogate
     *  key has to do. */
    @Test
    void every_project_has_its_own_id() {
        migrateTo("14");

        assertEquals(jdbc.queryForObject("SELECT count(*) FROM projects", Integer.class),
                jdbc.queryForObject("SELECT count(DISTINCT id) FROM projects", Integer.class));
        assertNotNull(jdbc.queryForObject(
                "SELECT id FROM projects WHERE name = ?", Long.class, DEFINED));
    }

    /**
     * A project's identity survives a move of its canonical name, which is the
     * property §10 of the harness-presence design asks for by name: renaming is
     * one row and one column, and every memory and conversation that pointed at
     * the project still does.
     */
    @Test
    void renaming_a_project_leaves_every_reference_to_it_alone() {
        migrateTo("14");

        jdbc.update("UPDATE projects SET name = ? WHERE name = ?", "laptop/payments", DEFINED);

        assertEquals("laptop/payments", projectOf("memories", "mem_defined"));
        assertEquals("laptop/payments", projectOf("conversations", "cnv_defined"));
    }

    /** The old columns are gone rather than left as a second, drifting copy of
     *  the same fact. */
    @Test
    void the_text_columns_are_retired() {
        migrateTo("14");

        assertTrue(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name IN ('memories', 'conversations')"
                        + " AND column_name = 'project'",
                String.class).isEmpty());
    }
}
