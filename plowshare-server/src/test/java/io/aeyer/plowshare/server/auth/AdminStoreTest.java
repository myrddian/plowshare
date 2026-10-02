package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * {@link AdminStore} against a real Postgres, migrated through the whole
 * chain — the {@code archive.ProjectStoreTest} pattern for a store test:
 * Testcontainers for the database, {@link Flyway} to build the schema, a
 * plain {@link JdbcTemplate} underneath the store under test.
 */
@Testcontainers
class AdminStoreTest {

    /** The pgvector image, not stock postgres:16: V1's first line is CREATE
     *  EXTENSION vector, and this class runs the whole migration chain. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private AdminStore store;

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
    void freshAdmins() {
        // CASCADE, since V40 gives `schedules` and `triggers` a
        // `defined_by TEXT REFERENCES admins (handle)`: a plain TRUNCATE is
        // refused once a referencing table exists, even an empty one.
        jdbc.execute("TRUNCATE TABLE admins CASCADE");
        store = new AdminStore(jdbc);
    }

    @Test
    void a_created_admin_is_found_by_handle() {
        store.create("root", "argon2-hash-goes-here");

        AdminRecord found = store.byHandle("root").orElseThrow();

        assertEquals("root", found.handle());
        assertEquals("argon2-hash-goes-here", found.passwordHash());
    }

    @Test
    void an_unknown_handle_is_an_ordinary_empty_answer() {
        assertTrue(store.byHandle("nobody").isEmpty(),
                "asking whether an admin exists is a question, not a mistake");
    }

    /**
     * {@code must_change_password} defaults true on the row {@link
     * AdminStore#create} writes, per {@code V37__admins.sql}, and it round
     * trips once a later write clears it — the shape a real password change
     * will take.
     */
    @Test
    void must_change_password_defaults_true_and_round_trips() {
        store.create("root", "argon2-hash-goes-here");

        assertTrue(store.byHandle("root").orElseThrow().mustChangePassword(),
                "a seeded account must carry the flag from its very first row");

        jdbc.update("UPDATE admins SET must_change_password = false WHERE handle = ?", "root");

        assertFalse(store.byHandle("root").orElseThrow().mustChangePassword(),
                "and the store reads back whatever the column holds, not a cached true");
    }

    /** Two rows, {@code ProjectStoreTest}'s reason for the same shape: one
     *  cannot tell a predicate from its absence, and a store that dropped the
     *  {@code WHERE} would still pass a fixture holding exactly one admin. */
    @Test
    void one_admins_row_is_not_another_admins() {
        store.create("root", "hash-one");
        store.create("ops", "hash-two");

        assertEquals("hash-one", store.byHandle("root").orElseThrow().passwordHash());
        assertEquals("hash-two", store.byHandle("ops").orElseThrow().passwordHash());
    }

    /** {@link AdminStore#handleOfAnyOtherAdmin(String)} on the ordinary boot:
     *  the only row in the table is the one the caller just excluded, so
     *  there is no "other" admin to name. */
    @Test
    void handle_of_any_other_admin_is_empty_when_only_the_named_admin_is_present() {
        store.create("root", "hash-one");

        assertTrue(store.handleOfAnyOtherAdmin("root").isEmpty(),
                "the only row in the table is the one just excluded, so there is no other"
                        + " admin to name");
    }

    /** {@link AdminSeed}'s refusal case: an operator changed {@code
     *  PLOWSHARE_ADMIN_HANDLE} against a database that already has an
     *  account under a different name. */
    @Test
    void handle_of_any_other_admin_names_a_different_admin_when_one_exists() {
        store.create("original-admin", "hash-one");

        assertEquals("original-admin", store.handleOfAnyOtherAdmin("root").orElseThrow());
    }

    /** Both present — {@code one_admins_row_is_not_another_admins}'s own
     *  reasoning, applied to this query instead of {@code byHandle}: the
     *  named admin must not come back as its own answer merely because it is
     *  a row in the same table this method reads. */
    @Test
    void handle_of_any_other_admin_still_answers_the_other_when_both_are_present() {
        store.create("root", "hash-one");
        store.create("ops", "hash-two");

        assertEquals("ops", store.handleOfAnyOtherAdmin("root").orElseThrow());
    }

    /**
     * {@link AdminSeed#run} always trims {@code PLOWSHARE_ADMIN_HANDLE}
     * before passing it here — see that class's own note on why — so this
     * store never receives a padded handle from its one caller in
     * production. This pins the consequence that reliance rests on: the
     * exclusion below is exact-string, with no trimming of its own, so an
     * untrimmed argument fails to match the trimmed value {@link
     * AdminStore#create} would have written and this method would wrongly
     * hand a caller's own row back as "another" admin. {@link AdminSeed}
     * trimming before it ever calls this method is what keeps that safe.
     */
    @Test
    void handle_of_any_other_admin_compares_exactly_so_admin_seed_must_trim_before_calling() {
        store.create("root", "hash-one");

        assertEquals("root", store.handleOfAnyOtherAdmin("  root  ").orElseThrow(),
                "an untrimmed handle failed to exclude the very row it names, which is"
                        + " exactly the bug AdminSeed's own trim before calling this method"
                        + " exists to prevent");
    }

    /**
     * {@link AdminStore#changePassword(String, String)} is the write task 1's
     * gap needed: without it, {@code must_change_password} could be set on a
     * row but never cleared. Both halves of the write are asserted from one
     * call, since a change that wrote the hash but left the flag — or the
     * reverse — would be exactly the bug worth catching here.
     */
    @Test
    void change_password_writes_the_new_hash_and_clears_must_change_password() {
        store.create("root", "old-hash");
        assertTrue(store.byHandle("root").orElseThrow().mustChangePassword());

        store.changePassword("root", "new-hash");

        AdminRecord after = store.byHandle("root").orElseThrow();
        assertEquals("new-hash", after.passwordHash());
        assertFalse(after.mustChangePassword(),
                "changePassword wrote a new hash without clearing must_change_password, so a"
                        + " changed password would still look unchanged to a caller reading the"
                        + " flag");
    }

    /** A handle nobody has is the ordinary "nothing to update" case, the same
     *  shape {@link AdminStore#create(String, String)}'s own javadoc argues
     *  for a race between two boots: zero rows affected, and no exception a
     *  caller has to catch. */
    @Test
    void change_password_against_an_unknown_handle_updates_nothing_and_throws_nothing() {
        store.changePassword("nobody", "irrelevant-hash");

        assertTrue(store.byHandle("nobody").isEmpty());
    }

    /** {@link AdminStore#changePassword(String, String)} does not touch a
     *  different admin's row — {@code one_admins_row_is_not_another_admins}'s
     *  own reasoning, applied to the write half instead of the read half. */
    @Test
    void change_password_does_not_touch_a_different_admin() {
        store.create("root", "root-hash");
        store.create("ops", "ops-hash");

        store.changePassword("root", "root-hash-changed");

        assertEquals("root-hash-changed", store.byHandle("root").orElseThrow().passwordHash());
        assertEquals("ops-hash", store.byHandle("ops").orElseThrow().passwordHash());
        assertTrue(store.byHandle("ops").orElseThrow().mustChangePassword(),
                "changing root's password cleared ops's must_change_password too");
    }
}
