package io.aeyer.plowshare.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The map against a real Postgres, and above all <b>the guarantee that is in
 * the schema rather than in the caller</b>.
 *
 * <p>Every other class in this slice is a caller of this one: the seed writes
 * through it at boot, the controller writes through it on a {@code PUT}, and a
 * {@code @Live} accessor reads through it. So the assertion that matters most
 * here is {@link #a_write_with_no_author_is_refused_by_the_schema}, which goes
 * around this class's own API entirely — it hands {@link RuntimeConfig#put} an
 * empty author and expects <em>Postgres</em> to refuse it. A Java guard would
 * make that test pass while leaving the promise — that a value which surprises
 * somebody has a name against it — resting on every future writer remembering
 * to call this method rather than writing the row itself.
 *
 * <p>Testcontainers and not H2, following {@code DocumentStoreTest}. The two
 * behaviours under test are an {@code ON CONFLICT} upsert and a {@code CHECK}
 * constraint, and a stand-in that implemented either differently would be
 * testing itself.
 *
 * <p>No Spring context anywhere in this class. The store is a {@link
 * JdbcTemplate} and nothing else, so a test of it never boots an application —
 * which is what keeps the boot-precedence rules of {@code RuntimeConfigSeed}
 * out of the way of the plain question of whether a row round-trips.
 */
@Testcontainers
class RuntimeConfigTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private RuntimeConfig config;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void emptyMap() {
        jdbc.execute("TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, runtime_config");
        config = new RuntimeConfig(jdbc);
    }

    @Test
    void a_value_written_is_the_value_read_back() {
        config.put("plowshare.documents.ingest-budget", "1200", "enzo");

        assertEquals(Optional.of("1200"), config.get("plowshare.documents.ingest-budget"));
    }

    /**
     * Absent and blank are different answers, and the difference is the whole
     * of the boot rule. A key with no row is a key no operator has set, which
     * is what lets a packaged default seed it; an empty string would be a
     * value somebody chose.
     */
    @Test
    void a_key_never_written_is_absent_rather_than_blank() {
        assertEquals(Optional.empty(), config.get("plowshare.documents.ingest-budget"));
    }

    /**
     * One row per key, not an append-only log. The map answers "what is it
     * now", and the author it carries is the author of the value it is holding
     * — an upsert that kept the first writer's name would make {@code
     * updated_by} answer a question nobody asked.
     *
     * <p><b>And the timestamp moves with them, which is the assertion that pins
     * the upsert's most argued line.</b> {@code updated_at} is {@code NOT NULL
     * DEFAULT now()}, so the DEFAULT covers the INSERT and covers nothing after
     * it: delete {@code updated_at = now()} from the conflict branch and every
     * key's timestamp freezes at the moment it was first written, however many
     * times it has since changed. Asserting the column is merely present cannot
     * fail — {@code NOT NULL} already guarantees that — so strictly later is the
     * only form of this assertion that can tell the two schemas apart.
     */
    @Test
    void a_second_write_replaces_the_first_and_records_the_new_author() {
        config.put("k", "1", "enzo");
        Instant firstWrite = config.entry("k").orElseThrow().updatedAt();

        config.put("k", "2", "someone-else");

        assertEquals(Optional.of("2"), config.get("k"));
        assertEquals("someone-else", config.authorOf("k").orElseThrow());
        assertTrue(config.entry("k").orElseThrow().updatedAt().isAfter(firstWrite),
                "the key `k` was written twice, so its updated_at must have moved past "
                        + firstWrite + "; a timestamp still sitting at the first write is what"
                        + " the upsert's conflict branch omitting `updated_at = now()` looks"
                        + " like");
    }

    /**
     * The column is NOT NULL and CHECKed non-empty, so this is refused by the
     * database and not only by whatever calls it.
     *
     * <p><b>Named, following {@code MemoryStoreTest}.</b> A bare {@code
     * DataAccessException} is also what a mistyped column arrives as, so an
     * assertion that broad would pass on a statement that never reached a
     * constraint at all. And the name is the half that carries the meaning
     * here: an empty string satisfies {@code NOT NULL}, so {@code
     * runtime_config_has_an_author} is the only thing in the schema that
     * refuses this write, and it is the constraint this test is about.
     */
    @Test
    void a_write_with_no_author_is_refused_by_the_schema() {
        DataIntegrityViolationException refused = assertThrows(
                DataIntegrityViolationException.class, () -> config.put("k", "1", ""));

        assertTrue(refused.getMessage().contains("runtime_config_has_an_author"),
                "the nameless write is what the row was refused for");
    }

    /**
     * And the refusal leaves nothing behind. Worth its own assertion because
     * the upsert is one statement: a constraint that fired after a partial
     * write would be a key holding a value with no author against it, which is
     * exactly the state the constraint exists to make unreachable.
     */
    @Test
    void a_refused_write_leaves_no_row() {
        assertThrows(DataIntegrityViolationException.class, () -> config.put("k", "1", ""));

        assertEquals(Optional.empty(), config.get("k"));
    }

    /**
     * What the listing is for: {@code GET /v1/config} has to say what the map
     * holds, and it needs the author of each value beside it rather than a
     * second query per key.
     */
    @Test
    void the_listing_carries_every_key_with_its_value_and_its_author() {
        config.put("b", "2", "enzo");
        config.put("a", "1", "boot");

        List<RuntimeConfig.Entry> all = config.all();

        assertEquals(List.of("a", "b"), all.stream().map(RuntimeConfig.Entry::key).toList());
        assertEquals(List.of("1", "2"), all.stream().map(RuntimeConfig.Entry::value).toList());
        assertEquals(List.of("boot", "enzo"),
                all.stream().map(RuntimeConfig.Entry::updatedBy).toList());
    }

    /** An empty map lists nothing rather than failing. The first boot of a new
     *  deployment reads it before anything has ever written to it. */
    @Test
    void an_empty_map_lists_nothing() {
        assertEquals(List.of(), config.all());
    }

    /** {@link RuntimeConfig#authorOf} answers about the row, so a key with no
     *  row has no author — not {@code null} inside a present Optional. */
    @Test
    void a_key_never_written_has_no_author() {
        assertEquals(Optional.empty(), config.authorOf("k"));
    }

    /**
     * A value and its author out of <em>one</em> statement.
     *
     * <p>{@link RuntimeConfig#get} and {@link RuntimeConfig#authorOf} are two
     * reads, and a caller wanting both can have a write land between them and
     * see one writer's value beside another writer's name — a row state that
     * never existed, and the negation of what the upsert above promises. {@link
     * RuntimeConfig#entry} is the read that cannot tear.
     */
    @Test
    void one_read_answers_both_what_a_key_holds_and_who_set_it() {
        config.put("k", "1", "enzo");

        RuntimeConfig.Entry entry = config.entry("k").orElseThrow();

        assertEquals("k", entry.key());
        assertEquals("1", entry.value());
        assertEquals("enzo", entry.updatedBy());
    }

    /** And absent stays absent for the whole row too, on {@link
     *  RuntimeConfig#get}'s rule: a key nobody has set is empty rather than a
     *  record full of blanks. */
    @Test
    void a_key_never_written_has_no_entry() {
        assertEquals(Optional.empty(), config.entry("k"));
    }
}
