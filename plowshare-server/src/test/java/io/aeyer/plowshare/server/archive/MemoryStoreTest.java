package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Invalidation;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The store, against a real Postgres.
 *
 * <p>Testcontainers and not H2: the schema uses {@code vector}, {@code IS NOT DISTINCT FROM} and
 * {@code ON CONFLICT}, none of which an in-memory stand-in has, so a test that passed on one would
 * only prove the stand-in agreed with itself.
 *
 * <p>Most of these are ports of {@code tests/archive/test_store.py}. Excalibur asserts on which of
 * two directories a file landed in; the property that mattered — a retired record leaves the search
 * path entirely — is asserted here on {@link MemoryStore#loadAll} and {@link MemoryStore#index},
 * because the state column is now the only thing that can guarantee it.
 */
@Tag("full-db")
@Testcontainers
class MemoryStoreTest {

  /**
   * The pgvector image, not stock postgres:16: the migration creates the extension, and stock
   * Postgres has no vector.so to create it from.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private MemoryStore store;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * One container, migrated once, emptied between tests. Excalibur gets a fresh archive per test
   * from `tmp_path`; a container per test would cost a Postgres start-up each time for the same
   * isolation.
   */
  @BeforeEach
  void freshArchive() {
    // `projects` and not `memories`, since V14: a memory names a project by
    // reference now, and a save registers the project it names — so emptying
    // only the memories would leave the projects those tests created behind,
    // and "this project has no row" would stop being a thing a test could
    // set up.
    //
    // CASCADE for the reason it was already needed one table down: V2's
    // `proposals` references `memories`, so a plain TRUNCATE is refused
    // outright. It reaches `memories`, `conversations` and `proposals`, none
    // of which this class holds anything of its own in.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, projects CASCADE");
    store = new MemoryStore(jdbc);
  }

  private static final Provenance FORMED = new Provenance(Instant.EPOCH, "probe", "test");

  private static Memory memory(String id, Home home) {
    return Memory.formed(id, "s", "sc", "b", FORMED, home);
  }

  private static Memory memory(String id) {
    return memory(id, Home.of("payments"));
  }

  private static List<String> ids(List<Memory> memories) {
    return memories.stream().map(Memory::id).toList();
  }

  // --- round trips ---------------------------------------------------------

  @Test
  void a_saved_memory_comes_back_identical() {
    Memory m =
        Memory.formed(
            "mem_01",
            "The retry budget is 4",
            "Calling the payments API",
            "Four attempts since the timeout change.",
            new Provenance(Instant.parse("2026-08-26T10:00:00Z"), "probe", "test"),
            Home.of("excalibur"));
    store.save(m);
    assertEquals(m, store.load("mem_01").orElseThrow());
  }

  /**
   * The same round trip with nothing left at its default.
   *
   * <p>{@code Memory.formed} leaves seven of thirteen components null, zero or false, so the test
   * above would still pass with half the row mapper wired to the wrong column. This one is the port
   * of Excalibur's {@code test_save_then_load_round_trips} that actually exercises the mapping.
   */
  @Test
  void every_component_of_a_memory_survives_the_round_trip() {
    Memory m =
        new Memory(
            "mem_full",
            "The retry budget is 4",
            "Calling the payments API",
            new Provenance(Instant.parse("2026-08-26T10:00:00Z"), "probe", "the mTLS migration"),
            MemoryState.SUPERSEDED,
            true,
            7,
            Instant.parse("2026-08-26T12:30:00Z"),
            "Four attempts since the timeout change.",
            "mem_older",
            "mem_newer",
            new Invalidation(
                Instant.parse("2026-08-26T13:00:00Z"), "enzo", "the endpoint went away"),
            Home.of("payments"));

    store.save(m);

    assertEquals(m, store.load("mem_full").orElseThrow());
  }

  /**
   * Global is a NULL project, not the string "global". A store that round-trips it as a string
   * would let a project literally named "global" collide with the tier.
   */
  @Test
  void global_round_trips_as_the_absence_of_a_project() {
    store.save(memory("mem_02", Home.global()));
    assertTrue(store.load("mem_02").orElseThrow().home().isGlobal());
  }

  /**
   * The other half of that rule, and the one a round trip alone cannot show: a project actually
   * called "global" is an ordinary project, and its rows are not the global tier's rows.
   *
   * <p>If the tier were stored as the literal string, these two homes would be the same row set,
   * and a single project could write into what every agent everywhere reads.
   */
  @Test
  void a_project_named_global_is_not_the_global_tier() {
    store.save(memory("mem_in_tier", Home.global()));
    store.save(memory("mem_in_project", Home.of("global")));

    assertEquals(List.of("mem_in_tier"), ids(store.loadAll(Home.global())));
    assertEquals(List.of("mem_in_project"), ids(store.loadAll(Home.of("global"))));
  }

  /**
   * Excalibur's test_store.py makes this point repeatedly: a tombstone that loses its reason is a
   * tombstone that teaches nothing.
   */
  @Test
  void an_invalidation_round_trips_with_its_reason() {
    Memory m = memory("mem_03", Home.global());
    // Both the state and the tombstone, because the store records what it
    // is handed and infers nothing: a store that read INVALIDATED off the
    // presence of an invalidation would be rewriting the state column on
    // the way in, and the round-trip test above would be a lie.
    Memory dead =
        m.withState(MemoryState.INVALIDATED)
            .withInvalidation(
                new Invalidation(
                    Instant.parse("2026-08-26T11:00:00Z"),
                    "enzo",
                    "the cache was removed in July"));
    store.save(dead);

    Memory back = store.load("mem_03").orElseThrow();
    assertEquals(MemoryState.INVALIDATED, back.state());
    assertEquals("the cache was removed in July", back.invalidation().reason());
  }

  @Test
  void an_unknown_id_is_empty_rather_than_an_exception() {
    assertTrue(store.load("mem_nope").isEmpty());
  }

  /**
   * A memory is readable by id in every one of the four states.
   *
   * <p>Excalibur's {@code test_load_and_exists_resolve_from_either_directory}: a tombstone is still
   * readable by id — that is what stops an agent relearning the mistake. Only <em>searching</em> is
   * narrowed.
   */
  @ParameterizedTest
  @EnumSource(MemoryState.class)
  void load_resolves_a_memory_in_any_state(MemoryState state) {
    Memory m = memory("mem_any").withState(state);
    store.save(m);
    assertEquals(m, store.load("mem_any").orElseThrow());
  }

  // --- the search path (Excalibur's memories/ versus retired/) --------------

  /**
   * Both live states stay where the search looks. {@code cold} belongs with {@code active} here and
   * not with the tombstones: it is unused, not untrue, and filing it with them would make true
   * knowledge permanently unreachable.
   */
  @ParameterizedTest
  @EnumSource(
      value = MemoryState.class,
      names = {"ACTIVE", "COLD"})
  void a_live_memory_is_on_the_search_path(MemoryState state) {
    store.save(memory("mem_live").withState(state));

    assertEquals(List.of("mem_live"), ids(store.loadAll(Home.of("payments"))));
    assertTrue(store.loadRetired(Home.of("payments")).isEmpty());
  }

  /**
   * The whole point, and the failure it prevents was measured: on 2026-08-18 a question whose only
   * match was an invalidated memory was answered with that known-false memory 5 runs out of 5. In
   * Excalibur the fix was a directory the search does not look in; here it is a filter on state,
   * and the row is still there to be read by id.
   */
  @ParameterizedTest
  @EnumSource(
      value = MemoryState.class,
      names = {"SUPERSEDED", "INVALIDATED"})
  void a_retired_memory_is_off_the_search_path_but_not_gone(MemoryState state) {
    store.save(memory("mem_dead").withState(state));

    assertTrue(store.loadAll(Home.of("payments")).isEmpty());
    assertTrue(store.index(Home.of("payments")).isEmpty());
    assertEquals(List.of("mem_dead"), ids(store.loadRetired(Home.of("payments"))));
    assertTrue(store.load("mem_dead").isPresent());
  }

  /**
   * The line between the search path and the index, which is the entire meaning of {@code cold}.
   *
   * <p>Excalibur draws it in two files: {@code store.py} keeps cold in {@code memories/} so grep
   * can still reach it, and {@code toc.py} builds entries for {@code ACTIVE} alone so it does not
   * spend the librarian's attention budget. An index that included cold would make demotion — the
   * one lever the archive has for keeping the index small — do nothing.
   */
  @Test
  void a_cold_memory_stays_on_the_search_path_and_leaves_the_index() {
    store.save(memory("mem_cold").withState(MemoryState.COLD));

    assertEquals(List.of("mem_cold"), ids(store.loadAll(Home.of("payments"))));
    assertTrue(store.index(Home.of("payments")).isEmpty());
  }

  @Test
  void load_all_returns_memories_sorted_by_id() {
    store.save(memory("mem_0000000002BBBBBB"));
    store.save(memory("mem_0000000001AAAAAA"));

    assertEquals(
        List.of("mem_0000000001AAAAAA", "mem_0000000002BBBBBB"),
        ids(store.loadAll(Home.of("payments"))));
  }

  @Test
  void load_all_does_not_see_retired_records() {
    store.save(memory("mem_0000000001AAAAAA"));
    store.save(memory("mem_0000000002BBBBBB").withState(MemoryState.SUPERSEDED));

    assertEquals(List.of("mem_0000000001AAAAAA"), ids(store.loadAll(Home.of("payments"))));
  }

  /**
   * Retiring a record takes it off the search path, keeps its body, and leaves exactly one row.
   *
   * <p>Excalibur's {@code test_retiring_a_memory_moves_its_file_and_leaves_no_copy} spends its
   * assertions on the file move because a move can leave two copies. Here the id is the primary
   * key, so the row count is the thing worth pinning: the body is the evidence of what was once
   * believed, and a second row under the same id would be two versions of a memory with nothing to
   * choose between them.
   */
  @Test
  void retiring_a_memory_keeps_its_body_and_leaves_one_row() {
    Memory m =
        Memory.formed("mem_retire", "s", "sc", "the original body", FORMED, Home.of("payments"));
    store.save(m);

    store.save(m.withState(MemoryState.SUPERSEDED).withSupersededBy("mem_x"));

    assertTrue(store.loadAll(Home.of("payments")).isEmpty());
    assertEquals("the original body", store.load("mem_retire").orElseThrow().body());
    assertEquals("mem_x", store.load("mem_retire").orElseThrow().supersededBy());
    assertEquals(1, rowCount("mem_retire"));
  }

  @Test
  void demotion_keeps_a_memory_where_the_search_looks() {
    Memory m = memory("mem_demote");
    store.save(m);

    store.save(m.withState(MemoryState.COLD));

    assertEquals(List.of("mem_demote"), ids(store.loadAll(Home.of("payments"))));
    assertEquals(1, rowCount("mem_demote"));
  }

  /**
   * {@code Archive.read} counts a use and saves the record straight back. A tombstone read by id
   * must not reappear on the search path because of it.
   */
  @Test
  void saving_a_retired_memory_again_does_not_resurrect_it() {
    Memory m = memory("mem_tomb").withState(MemoryState.INVALIDATED);
    store.save(m);

    store.save(m.withUses(1));

    assertTrue(store.loadAll(Home.of("payments")).isEmpty());
    assertEquals(1, store.load("mem_tomb").orElseThrow().uses());
  }

  @Test
  void a_revived_memory_returns_to_the_search_path() {
    Memory m = memory("mem_risen").withState(MemoryState.SUPERSEDED).withSupersededBy("mem_x");
    store.save(m);

    store.save(m.withState(MemoryState.ACTIVE).withSupersededBy(null));

    assertEquals(List.of("mem_risen"), ids(store.loadAll(Home.of("payments"))));
    assertTrue(store.loadRetired(Home.of("payments")).isEmpty());
  }

  /**
   * The id is the primary key, so a record saved twice is one row that changed. Excalibur needs an
   * exclusive lock file to get this — one server per archive, because two processes writing one
   * directory interleave — and a whole migration path for the two-files-under-one-id state that its
   * reconciler refuses to resolve. Neither is representable here.
   */
  @Test
  void saving_the_same_id_twice_leaves_one_row() {
    store.save(memory("mem_once"));
    store.save(memory("mem_once").withBody("rewritten"));

    assertEquals(1, rowCount("mem_once"));
    assertEquals("rewritten", store.load("mem_once").orElseThrow().body());
  }

  // --- the retired set -----------------------------------------------------

  /**
   * Nothing searches this. A caller asks for it on purpose, which is the only way a tombstone
   * should ever be found.
   */
  @Test
  void load_retired_returns_only_what_the_archive_no_longer_stands_behind() {
    store.save(memory("mem_0000000001AAAAAA"));
    store.save(memory("mem_0000000002BBBBBB").withState(MemoryState.COLD));
    store.save(memory("mem_0000000004DDDDDD").withState(MemoryState.INVALIDATED));
    store.save(memory("mem_0000000003CCCCCC").withState(MemoryState.SUPERSEDED));

    assertEquals(
        List.of("mem_0000000003CCCCCC", "mem_0000000004DDDDDD"),
        ids(store.loadRetired(Home.of("payments"))));
  }

  @Test
  void a_fresh_archive_has_nothing_live_and_nothing_retired() {
    assertTrue(store.loadAll(Home.of("payments")).isEmpty());
    assertTrue(store.loadRetired(Home.of("payments")).isEmpty());
    assertTrue(store.index(Home.of("payments")).isEmpty());
  }

  // --- the two tiers -------------------------------------------------------

  /**
   * The index is summary and scope only. It must never carry bodies: it is read whole on every
   * recall, and a body in it is a body in every prompt.
   */
  @Test
  void the_index_carries_summary_and_scope_and_no_body() {
    store.save(Memory.formed("mem_04", "a summary", "a scope", "a body", FORMED, Home.of("p")));
    var entries = store.index(Home.of("p"));
    assertEquals(1, entries.size());
    assertEquals("a summary", entries.get(0).summary());
    assertEquals("a scope", entries.get(0).scope());
  }

  /**
   * A tier reads only its own rows. Shadowing happens above this layer; the store must not quietly
   * merge tiers or the rule cannot be tested.
   */
  @Test
  void loading_a_project_does_not_return_global_rows() {
    store.save(memory("mem_05", Home.global()));
    store.save(memory("mem_06", Home.of("p")));
    assertEquals(List.of("mem_06"), ids(store.loadAll(Home.of("p"))));
  }

  /**
   * And the other direction, which is where the NULL bites: {@code project = ?} with a null
   * argument is NULL rather than true, so a store written the obvious way returns an empty global
   * tier forever and every global memory silently stops existing.
   */
  @Test
  void loading_global_does_not_return_project_rows() {
    store.save(memory("mem_05", Home.global()));
    store.save(memory("mem_06", Home.of("p")));

    assertEquals(List.of("mem_05"), ids(store.loadAll(Home.global())));
    assertEquals(List.of("mem_05"), store.index(Home.global()).stream().map(TocEntry::id).toList());
  }

  // --- what the database itself refuses ------------------------------------

  /**
   * Excalibur can be handed a memory file with a nonsense {@code state:} key, so its store skips
   * unreadable records with a warning and its migration leaves them where they are. A row is not
   * hand-editable the same way, but a psql session or a stray migration can still write one — and a
   * fifth state is worse than an unreadable file: it is in neither the search path nor the retired
   * set, so it is unreachable from both while looking perfectly intact. The CHECK refuses it at the
   * write.
   */
  @Test
  void a_state_outside_the_four_is_refused_by_the_database() {
    assertThrows(DataIntegrityViolationException.class, () -> insertRaw("mem_bad", "retired"));
  }

  /**
   * Blank is not global and not a project. {@code Home.of} already refuses it; the constraint stops
   * anything that bypasses the record from writing a row into a tier no read can name.
   */
  @Test
  void a_blank_project_is_refused_by_the_database() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("INSERT INTO projects (name) VALUES ('')"));
  }

  /**
   * <b>The constraint this column could not carry until V14.</b> V1 created {@code memories} before
   * V3 created {@code projects}, so {@code memories.project} had no table to reference and a memory
   * naming a project that did not exist was writable from the first migration onwards, with nothing
   * to notice it. The surrogate reference is the first time the database can refuse one.
   *
   * <p>Zero is the id chosen on purpose: {@code projects_id_is_positive} makes it an id no row can
   * ever hold, which is also what {@code ProjectIds.NONE} relies on.
   */
  @Test
  void a_memory_cannot_be_filed_in_a_project_that_does_not_exist() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO memories (id, project_id, summary, scope, body, state,"
                        + " formed_at, formed_by, formed_where)"
                        + " VALUES (?, 0, 's', 'sc', 'b', 'active', NOW(), 'probe',"
                        + " 'test')",
                    "mem_dangling"));
    assertTrue(
        refused.getMessage().contains("memories_project_exists"),
        "the missing project is what the row was refused for");
  }

  /**
   * The other half of that constraint: it must not make remembering something wait on an operator.
   *
   * <p>V3 and V6 both refuse to tie writes to a workspace — "tying them would make defining a
   * workspace depend on having already written something, which is backwards" — and a foreign key
   * would do exactly that if the only way to get a project row were {@code ProjectStore.define}. So
   * a save registers the project it names, and the row it writes has no workspace, which is the
   * access the project had before: none.
   */
  @Test
  void saving_a_memory_into_a_project_nobody_defined_registers_it_without_a_workspace() {
    store.save(memory("mem_new", Home.of("ledger")));

    assertEquals(Home.of("ledger"), store.load("mem_new").orElseThrow().home());
    assertNull(
        jdbc.queryForObject("SELECT workspace FROM projects WHERE name = 'ledger'", String.class));
  }

  /**
   * <b>A tier nobody has written to is empty, and it is emphatically not the global tier.</b>
   *
   * <p>This is the failure the surrogate key makes possible and {@code ProjectIds} exists to
   * prevent. A project name resolves to an id; an unknown name resolves to no id; and "no id"
   * spelled as NULL is the global tier, so the wrong resolution hands every global memory back as
   * that project's. The read is ordinary — an agent recalls before it ever writes — so the wrong
   * answer would be the first answer.
   */
  @Test
  void a_project_nothing_has_been_written_to_is_empty_and_is_not_the_global_tier() {
    store.save(memory("mem_global", Home.global()));

    assertEquals(List.of(), ids(store.loadAll(Home.of("never-written-to"))));
    assertEquals(List.of(), ids(store.loadRetired(Home.of("never-written-to"))));
    assertEquals(List.of(), store.index(Home.of("never-written-to")));
    assertEquals(0, store.countUnsearchable(Home.of("never-written-to")));
    assertEquals(List.of(), ids(store.unsearchable(Home.of("never-written-to"))));
    assertEquals(List.of("mem_global"), ids(store.loadAll(Home.global())));
  }

  /**
   * Reading is not writing: asking about a project must not create it, or the projects listing an
   * operator reads becomes a log of everything anybody mistyped.
   */
  @Test
  void reading_a_project_that_does_not_exist_does_not_register_it() {
    store.loadAll(Home.of("never-written-to"));

    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM projects WHERE name = 'never-written-to'", Integer.class));
  }

  /**
   * The text of a memory is written by a model, and it lands in a database rather than in a file.
   * Excalibur's equivalent — {@code test_grep_treats_shell_metacharacters_as_a_regex} — pins that a
   * model-supplied pattern never reaches a shell; the same argument here is that model-supplied
   * prose never reaches the SQL parser. Bound as a parameter, it is a string; concatenated, it is a
   * dropped table.
   */
  @Test
  void text_that_looks_like_sql_is_stored_as_text() {
    String hostile = "'; DROP TABLE memories; --";
    store.save(Memory.formed("mem_sql", hostile, hostile, hostile, FORMED, Home.of("payments")));

    assertEquals(hostile, store.load("mem_sql").orElseThrow().body());
    assertEquals(List.of("mem_sql"), ids(store.loadAll(Home.of("payments"))));
  }

  /**
   * Saving a memory must not throw away its embedding.
   *
   * <p>{@code embedding} is not a component of {@link Memory}, so an upsert that listed the column
   * would write NULL over it every time — and {@code Archive.read} counts a use and saves the
   * record straight back, so the archive would lose the vector of every memory anyone actually
   * recalled and get less searchable the more it was used. Task 8 stands on this. The raw UPDATE
   * below is what Task 8's writer will do.
   */
  @Test
  void saving_a_memory_again_does_not_erase_its_embedding() {
    Memory m = memory("mem_vec");
    store.save(m);
    jdbc.update(
        "UPDATE memories SET embedding = ?::vector WHERE id = ?", vectorLiteral(768), "mem_vec");

    store.save(m.withUses(1));

    assertNotNull(
        jdbc.queryForObject(
            "SELECT embedding FROM memories WHERE id = ?", String.class, "mem_vec"));
  }

  /**
   * A memory is written before it is embedded: the write keeps the memory and loses only the vector
   * — and the store can say which memories that happened to.
   *
   * <p><b>This test could not fail before.</b> Its whole body was {@code save} followed by {@code
   * SELECT … WHERE embedding IS NULL}, and {@link Memory} has no embedding component while {@code
   * save}'s column lists omit the column by design, so no implementation of {@code save} could have
   * made it red. It restated the schema back to itself.
   *
   * <p>What its javadoc actually claimed is the four-part fact below, and each part has an
   * implementation that can break it. The last is the one that did not exist at all until finding
   * A: the archive could hold exactly one memory, unembedded, and answer "nothing is close to that
   * question" with the only trace a {@code log.warn}. Dropping {@code embedding IS NULL} from
   * either {@link MemoryStore#unsearchable} or {@link MemoryStore#countUnsearchable} turns this
   * red.
   */
  @Test
  void a_memory_is_written_before_it_is_embedded_and_the_store_says_which() {
    Home payments = Home.of("payments");
    store.save(memory("mem_unembedded"));
    store.save(memory("mem_embedded"));
    jdbc.update(
        "UPDATE memories SET embedding = ?::vector WHERE id = ?",
        vectorLiteral(768),
        "mem_embedded");

    // 1. The write succeeded with no vector — the column is nullable for
    //    exactly this, and a NOT NULL there would lose the memory.
    assertFalse(
        jdbc.queryForList(
                "SELECT id FROM memories WHERE id = ? AND embedding IS NULL", "mem_unembedded")
            .isEmpty());
    // 2. It is on the search path and in the index like any other memory.
    assertEquals(List.of("mem_embedded", "mem_unembedded"), ids(store.loadAll(payments)));
    assertTrue(
        store.index(payments).stream().map(TocEntry::id).toList().contains("mem_unembedded"));
    // 3. …and is skipped by vector search alone, having no vector to be
    //    compared with. This is the right behaviour and the invisible one.
    assertEquals(List.of("mem_embedded"), ids(store.searchByVector(query(768), payments, 10)));
    // 4. …so the store has to be able to name it. Otherwise nothing above
    //    this layer can tell "nothing matched" from "the answer was never
    //    searched", and an agent told the first tries another question,
    //    forever.
    assertEquals(1, store.countUnsearchable(payments));
    assertEquals(List.of("mem_unembedded"), ids(store.unsearchable(payments)));
    assertTrue(entry(store.index(payments), "mem_unembedded").unsearchable());
    assertFalse(entry(store.index(payments), "mem_embedded").unsearchable());
  }

  /**
   * A tombstone with no embedding is not reported as unsearchable.
   *
   * <p>It is excluded from the search path on purpose rather than missing from it — the live-state
   * filter is the whole {@code memories/} versus {@code retired/} split restated — so counting it
   * would report a problem that is not one. A count that cries wolf is a count nobody reads, which
   * would put the archive straight back where finding A found it.
   */
  @Test
  void a_retired_memory_with_no_embedding_is_not_reported_as_unsearchable() {
    Home payments = Home.of("payments");
    store.save(memory("mem_live"));
    store.save(memory("mem_dead").withState(MemoryState.INVALIDATED));

    assertEquals(1, store.countUnsearchable(payments));
    assertEquals(List.of("mem_live"), ids(store.unsearchable(payments)));
  }

  /**
   * The unsearchable reads are per tier, like every other read here: a project's broken rows are
   * not the global tier's problem, and a store-wide count would tell every caller about every other
   * caller's outage.
   */
  @Test
  void the_unsearchable_count_is_per_tier() {
    store.save(memory("mem_project", Home.of("payments")));
    store.save(memory("mem_global", Home.global()));

    assertEquals(1, store.countUnsearchable(Home.of("payments")));
    assertEquals(1, store.countUnsearchable(Home.global()));
    assertEquals(List.of("mem_global"), ids(store.unsearchable(Home.global())));
  }

  // --- helpers -------------------------------------------------------------

  private static int rowCount(String id) {
    Integer count =
        jdbc.queryForObject("SELECT count(*) FROM memories WHERE id = ?", Integer.class, id);
    return count == null ? 0 : count;
  }

  private static void insertRaw(String id, String state) {
    jdbc.update(
        "INSERT INTO memories (id, summary, scope, body, state, formed_at, formed_by,"
            + " formed_where) VALUES (?, 's', 'sc', 'b', ?, NOW(), 'probe', 'test')",
        id,
        state);
  }

  /**
   * The float[] form of {@link #vectorLiteral}, so a query vector and the stored one are the same
   * numbers and cosine distance between them is zero — ordering then cannot rescue a filter that
   * failed.
   */
  private static float[] query(int dimensions) {
    float[] vector = new float[dimensions];
    java.util.Arrays.fill(vector, 0.1f);
    return vector;
  }

  private static TocEntry entry(List<TocEntry> index, String id) {
    return index.stream()
        .filter(e -> e.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError(id + " is not in the index: " + index));
  }

  private static String vectorLiteral(int dimensions) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < dimensions; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("0.1");
    }
    return sb.append(']').toString();
  }
}
