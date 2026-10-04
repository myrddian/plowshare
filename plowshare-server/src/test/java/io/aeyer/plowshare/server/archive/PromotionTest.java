package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Promotion: a project memory becoming a global one.
 *
 * <p><b>Not a port.</b> Excalibur has one pool of memories, so it has no tiers and nothing to
 * promote between; every rule below is this design's, stated as a test.
 *
 * <h2>What this class is really guarding</h2>
 *
 * <p>Promotion looks like a supersession from the outside — a new record, the old one retired with
 * a forward link — and that resemblance is a trap the spec records having already fallen into once.
 * {@code Archive.requireTarget} refuses a cross-tier target outright, "a memory in another tier is
 * shadowed, never superseded", and that refusal is deliberate: one project retiring a global memory
 * would take it away from every other project. An earlier draft of the design proposed
 * promotion-as-supersession and was wrong.
 *
 * <p>So {@link Archive#promote} sits <em>beside</em> that rule rather than through it. {@code
 * requireTarget}'s rule is about a verdict a caller supplies, which the archive cannot check;
 * promotion is an operation the archive performs knowing what it means. {@link
 * #a_caller_still_cannot_supersede_across_tiers_with_a_verdict} is here — and deliberately overlaps
 * {@code ArchiveTest} — so that anyone who relaxes {@code requireTarget} to make promotion easier
 * fails a test in the file the change was made for.
 *
 * <p>The embedding client is stubbed and must stay stubbed, for the reason {@link
 * StubEmbeddingClient} gives at length: a live model would make every assertion here a measurement
 * of that model on that day.
 */
@Tag("full-db")
@Testcontainers
class PromotionTest {

  /**
   * The pgvector image, not stock postgres:16: the migration's first line is CREATE EXTENSION
   * vector, and stock Postgres has no vector.so to load.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  /** The fixture instant the rest of the archive suite uses, kept so the files read alike. */
  private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

  private static final Home PAYMENTS = Home.of("payments");

  private static final int MAX_BODY_CHARS = 1000;
  private static final double HALF_LIFE_DAYS = 30.0;

  /**
   * High enough that no test here trips demotion by accident. The one test that is about demotion
   * builds its own archive with a threshold of 1.
   */
  private static final int INDEX_THRESHOLD = 50;

  private static final String CURATOR = "curator";

  private Instant clock;
  private int minted;
  private MemoryStore store;
  private StubEmbeddingClient embeddings;
  private Archive archive;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void freshArchive() {
    // CASCADE because V2's `proposals` references this table: a plain
    // TRUNCATE is refused outright, and this class holds no proposals of
    // its own to lose.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, memories CASCADE");
    clock = NOW;
    minted = 0;
    store = new MemoryStore(jdbc);
    embeddings = new StubEmbeddingClient();
    archive = archiveWithThreshold(INDEX_THRESHOLD);
  }

  /**
   * Sequential zero-padded ids, matching the rest of the suite: the store sorts by id, and an
   * unpadded counter would sort {@code mem_10} before {@code mem_2} and quietly make every ordering
   * assertion test the wrong thing.
   */
  private Archive archiveWithThreshold(int threshold) {
    return new Archive(
        store,
        new ReasonLog(jdbc),
        embeddings,
        MAX_BODY_CHARS,
        threshold,
        HALF_LIFE_DAYS,
        () -> clock,
        () -> String.format("mem_%06d", ++minted));
  }

  // --- what promotion does --------------------------------------------------

  /**
   * The shape of the operation: a new global record, and the project record retired with a forward
   * link to it.
   *
   * <p>A <em>new</em> record and not a moved one. Rewriting the project row's {@code project}
   * column to NULL would leave every id anyone already holds pointing at a memory that has silently
   * changed tiers, and nothing anywhere saying it happened.
   */
  @Test
  void promotion_writes_a_global_record_and_retires_the_project_one() {
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");

    Memory promoted = archive.promote(local.memoryId(), "it holds everywhere", CURATOR).promoted();

    assertNotEquals(local.memoryId(), promoted.id(), "promotion mints a new record");
    assertEquals(Home.global(), promoted.home());
    assertEquals(MemoryState.ACTIVE, promoted.state());
    // The claim itself travels unchanged: promotion is about which tier
    // answers with it, never about what it says.
    assertEquals("Payments uses mTLS", promoted.summary());
    assertEquals("payments auth work", promoted.scope());
    assertEquals("Inter-service calls present certs.", promoted.body());
    assertEquals(local.memoryId(), promoted.supersedes());

    Memory origin = archive.get(local.memoryId());
    assertEquals(MemoryState.SUPERSEDED, origin.state());
    assertEquals(promoted.id(), origin.supersededBy());
    assertEquals(PAYMENTS, origin.home(), "the retired record stays where it was written");
    // The body is kept, like every other tombstone in this archive: it is
    // the evidence of what the project once held on its own.
    assertEquals("Inter-service calls present certs.", origin.body());
  }

  /**
   * A promoted memory carries the account of how it got there.
   *
   * <p>A global memory is read by every project, so "why is this global?" is a question somebody
   * will ask about it years later. The origin id and the reason are the whole of the answer, and
   * there is nowhere else they are recorded: {@link Memory} has no field for a promotion, so the
   * provenance prose carries it and {@code supersedes} carries the machine-readable link.
   */
  @Test
  void the_new_record_says_where_it_came_from() {
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");
    // Moved on before the promotion, so the two instants differ and the
    // assertion on formed().at() below is a real counterfactual: with the
    // clock still at NOW, a record stamped with the origin's formation time
    // would satisfy it by accident.
    clock = NOW.plus(Duration.ofDays(40));

    Memory promoted =
        archive
            .promote(local.memoryId(), "three other projects hit the same wall", CURATOR)
            .promoted();

    String where = promoted.formed().where();
    assertTrue(where.contains(local.memoryId()), where);
    assertTrue(where.contains("payments"), where);
    assertTrue(where.contains("three other projects hit the same wall"), where);
    // The promoter, not the agent that formed the original claim. Who
    // decided this belongs everywhere is a different fact from who noticed
    // it, and the origin record still holds the second one.
    assertEquals(CURATOR, promoted.formed().by());
    assertEquals("claude-code", archive.get(local.memoryId()).formed().by());
    // Stamped at the promotion, not at the original formation: Scoring
    // decays from lastUsed-or-formed, so a record that arrived in the global
    // index carrying a months-old formation instant would be the first one
    // demoted out of it on the day it got there.
    assertEquals(clock, promoted.formed().at());
  }

  /**
   * A promoted record starts its life in the global index with a clean slate.
   *
   * <p>Carrying the origin's {@code lastUsed} forward is the tempting version and is the harmful
   * one: {@link Scoring} decays from that instant, so a memory promoted for having proved itself
   * over months would arrive already decayed and be the first record demoted out of the tier it was
   * just promoted into.
   *
   * <p>The origin is seeded by hand, {@code pinned} and used, because all three assertions have to
   * be counterfactuals: no caller can set {@code pinned} — {@code
   * ArchiveTest.demotion_honours_pinned_though_no_caller_can_set_it} seeds it the same way and says
   * why — so an origin written through the archive would be unpinned already and the pinning
   * assertion would hold whether or not the flag travels.
   */
  @Test
  void a_promoted_record_arrives_with_a_fresh_use_history() {
    Instant longAgo = NOW.minus(Duration.ofDays(200));
    store.save(
        new Memory(
            "mem_veteran",
            "Payments uses mTLS",
            "payments auth work",
            new Provenance(longAgo, "claude-code", "proj/payments"),
            MemoryState.ACTIVE,
            true,
            17,
            longAgo,
            "Inter-service calls present certs.",
            null,
            null,
            null,
            PAYMENTS));

    Memory promoted = archive.promote("mem_veteran", "it holds everywhere", CURATOR).promoted();

    assertEquals(0, promoted.uses());
    assertNull(promoted.lastUsed());
    assertFalse(promoted.pinned(), "pinning is a per-tier decision, not a travelling one");
    // And the origin keeps its own history: promotion writes a new record,
    // it does not empty the old one.
    Memory origin = archive.get("mem_veteran");
    assertEquals(17, origin.uses());
    assertTrue(origin.pinned());
  }

  /**
   * A promotion with nothing to say for itself still says where it came from.
   *
   * <p>{@code Validation.check} deliberately does not require {@code formedWhere} — an omitted
   * "where" is a proposal with less context, not a malformed one — so a blank reason is refused
   * nowhere and must render sensibly. What must not happen is a stored provenance ending in a
   * dangling {@code ": "}, which reads as a reason that was lost rather than one that was never
   * given.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void a_promotion_with_no_reason_still_names_its_origin(String reason) {
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");

    Memory promoted = archive.promote(local.memoryId(), reason, CURATOR).promoted();

    String where = promoted.formed().where();
    assertTrue(where.contains(local.memoryId()), where);
    assertFalse(where.stripTrailing().endsWith(":"), where);
  }

  // --- what promotion refuses -----------------------------------------------

  /**
   * The rule promotion must not break.
   *
   * <p>{@code Archive.requireTarget} refuses a cross-tier verdict deliberately — "a memory in
   * another tier is shadowed, never superseded" — and promotion sits beside that rule rather than
   * through it. This overlaps {@code ArchiveTest.a_verdict_may_not_name_a_target_in_another_tier}
   * on purpose: the cheapest way to make promotion "work" is to relax {@code requireTarget}, and
   * the person doing that should fail a test in this file.
   */
  @Test
  void a_caller_still_cannot_supersede_across_tiers_with_a_verdict() {
    WriteResult global =
        archive.applyVerdict(
            proposal("The retry budget is 4", "Four everywhere."), isNew(), Home.global());

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () ->
                archive.applyVerdict(
                    proposal("The retry budget is 3", "Three here."),
                    new Verdict(VerdictKind.SUPERSEDES, global.memoryId(), "contradicts"),
                    PAYMENTS));
    assertTrue(refused.getMessage().contains("shadowed, never superseded"), refused.getMessage());

    Memory untouched = archive.get(global.memoryId());
    assertEquals(MemoryState.ACTIVE, untouched.state());
    assertNull(untouched.supersededBy());
  }

  /**
   * A memory already in the global tier cannot be promoted into it again.
   *
   * <p>Left alone this would file a second global record for one claim and retire the first with a
   * forward link — two identical memories where the live one is the copy, which is precisely the
   * "two records for one claim with nothing to choose between them" state the verdicts exist to
   * prevent.
   */
  @Test
  void promoting_a_global_memory_is_refused() {
    WriteResult global =
        archive.applyVerdict(
            proposal("The retry budget is 4", "Four everywhere."), isNew(), Home.global());

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> archive.promote(global.memoryId(), "it holds everywhere", CURATOR));
    assertTrue(refused.getMessage().contains("already in the global tier"), refused.getMessage());
    assertEquals(1, rowCount(), "nothing was written");
  }

  /**
   * A tombstone cannot be promoted, and the refusal names which kind it is.
   *
   * <p>The curator proposes a promotion and a human approves it later, so the memory can be retired
   * in between — this is a race, not a caller bug, and the message has to be enough to tell the two
   * apart without a database session. Promoting a tombstone would put a claim the archive has
   * already stopped standing behind into the tier every project reads.
   *
   * <p>The origin is a <em>project</em> memory, so only this guard can be the one that fires: a
   * global tombstone would satisfy two refusals at once and the assertion could not say which it
   * caught.
   */
  @ParameterizedTest
  @EnumSource(
      value = MemoryState.class,
      names = {"SUPERSEDED", "INVALIDATED"})
  void promoting_a_retired_memory_is_refused_naming_its_state(MemoryState retired) {
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");
    store.save(archive.get(local.memoryId()).withState(retired));

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> archive.promote(local.memoryId(), "it holds everywhere", CURATOR));
    assertTrue(refused.getMessage().contains(retired.wireName()), refused.getMessage());
    assertTrue(refused.getMessage().contains("cannot be promoted"), refused.getMessage());
    assertEquals(1, rowCount(), "nothing was written");
  }

  /**
   * A retired global memory is refused for being retired, not for being global.
   *
   * <p>The only case where the two refusals both apply, and so the only test that can see which
   * order they are checked in. {@code Archive.promote} states the order in a comment; a claim in a
   * comment with nothing behind it is what this branch keeps finding, so this is the thing behind
   * it. The order is the useful one: a caller told "already in the global tier" about a tombstone
   * would go looking for a duplicate that is not there, when what actually happened is that the
   * memory stopped being true.
   */
  @Test
  void a_retired_global_memory_is_refused_for_being_retired_not_for_being_global() {
    WriteResult global =
        archive.applyVerdict(
            proposal("The retry budget is 4", "Four everywhere."), isNew(), Home.global());
    archive.invalidate(global.memoryId(), "the timeout changed", "claude-code");

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> archive.promote(global.memoryId(), "it holds everywhere", CURATOR));
    assertTrue(refused.getMessage().contains("invalidated"), refused.getMessage());
    assertFalse(refused.getMessage().contains("already in the global tier"), refused.getMessage());
  }

  /**
   * A cold memory can be promoted, because cold is not retired.
   *
   * <p>{@code COLD} means unused, not untrue: the record has only fallen out of its own project's
   * index. Refusing it here would make demotion into a kind of deletion after all — a memory that
   * proved rare-but-critical is exactly the one a curator is most likely to be looking at, and it
   * will often have gone cold in the project it was written in.
   */
  @Test
  void promoting_a_cold_memory_is_allowed_because_unused_is_not_untrue() {
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");
    store.save(Lifecycle.demote(archive.get(local.memoryId())));
    assertEquals(MemoryState.COLD, archive.get(local.memoryId()).state(), "fixture");

    Memory promoted = archive.promote(local.memoryId(), "rare but critical", CURATOR).promoted();

    assertEquals(Home.global(), promoted.home());
    assertEquals(MemoryState.ACTIVE, promoted.state());
    assertEquals(MemoryState.SUPERSEDED, archive.get(local.memoryId()).state());
  }

  /**
   * An id that was never written is refused, and nothing is written for it.
   *
   * <p>{@link Archive#get} is what refuses, but promotion is a new entry point into it and the
   * alternative — minting a global record from an id nobody recognises — is the worst outcome this
   * class has: a global memory with no origin, readable by every project, traceable to nothing.
   */
  @Test
  void promoting_an_id_that_was_never_written_is_refused() {
    ArchiveException absent =
        assertThrows(
            ArchiveException.class,
            () -> archive.promote("mem_nope", "it holds everywhere", CURATOR));
    assertEquals(0, rowCount());
    // The exact class, for the reason ArchiveTest's unknown-target test
    // gives: ArchiveRefusedException extends this, so assertThrows alone
    // cannot tell promote's absence from its two refusals — and this file
    // holds all three. The pair with those is what says the split is a
    // classification rather than a rename.
    assertEquals(
        ArchiveException.class,
        absent.getClass(),
        "an id nothing was written under is absent, never refused");
  }

  /**
   * A promotion with no named promoter is refused.
   *
   * <p>Every other record in the archive reaches the store through {@code Validation.check}, which
   * requires {@code formedBy}; promotion mints a record without a proposal and so bypasses it.
   * Without this, promotion is the one way to get a memory into the archive with nobody's name on
   * it — and for a {@code null} it is worse than that, because {@code Provenance} checks nothing
   * and {@code formed_by} is {@code NOT NULL}, so the failure arrives as a Postgres constraint
   * violation many layers from the caller that dropped the field.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void promoting_without_a_named_promoter_is_refused(String by) {
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");

    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> archive.promote(local.memoryId(), "it holds everywhere", by));
    assertTrue(refused.getMessage().contains("'by'"), refused.getMessage());
    assertEquals(1, rowCount(), "nothing was written");
    assertEquals(
        MemoryState.ACTIVE,
        archive.get(local.memoryId()).state(),
        "and the origin was not retired on the way to being refused");
  }

  // --- the promoted record is searchable ------------------------------------

  /**
   * A promoted memory that recall cannot find is the failure this whole project is built around.
   *
   * <p>"Stores everything, embeds nothing" is the shape: the row is on disk, the index shows it,
   * and {@link MemoryStore#searchByVector} filters {@code embedding IS NOT NULL}, so it is
   * unreachable by meaning however the question is phrased — and the archive answers "nothing is
   * close to that" rather than "some of this could not be searched". Promotion writes a new record,
   * so it owes a vector exactly as any other write does.
   *
   * <p>Both halves are asserted, because either alone is weak. The column check alone would pass on
   * a vector nothing can rank with; the recall alone would pass on a stub where every distance is
   * equal and the row simply came back in whatever order Postgres chose. The decoy is assigned a
   * different axis so the ranking has to be real.
   */
  @Test
  void the_promoted_record_is_embedded_like_any_other_write() {
    embeddings.assign("mTLS", 1);
    embeddings.assign("retry budget", 2);
    archive.applyVerdict(
        proposal("The retry budget is 4", "Four everywhere."), isNew(), Home.global());
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");

    Memory promoted = archive.promote(local.memoryId(), "it holds everywhere", CURATOR).promoted();

    assertNotNull(embeddingOf(promoted.id()), "the promoted record was stored without a vector");

    Archive.Recall found = archive.recall("what does mTLS apply to", Home.global(), 2);
    assertEquals(0, found.unsearchable(), "no live global record is missing a vector");
    assertEquals(
        promoted.id(),
        found.memories().get(0).id(),
        "the promoted record must be the nearest answer to its own subject");
  }

  /**
   * The embedding happens after the commit, and not inside it.
   *
   * <p>This boundary cost a lost write once. The model call used to sit inside the write
   * transaction — as a {@code @Transactional} on the controller — so an unexpected failure out of
   * the embedding client rolled a committed memory back, and a stalled model pinned a pooled
   * connection for as long as it stalled. {@code Archive.applyVerdict} closes its {@link
   * UnitOfWork} and only then calls {@code embed}; promotion writes a new record and has the same
   * hazard, so it must have the same shape.
   *
   * <p><b>What this instrument can and cannot see.</b> {@link TrackingUnitOfWork} reports whether a
   * unit of work is open on this thread at the moment the embedding client is called, which is
   * exactly the question — and the three sanity assertions below are what stop it passing
   * vacuously. Without them a promotion that opened no transaction at all, or embedded nothing at
   * all, would satisfy the one assertion that matters. Measured rather than assumed: moving {@code
   * embed} inside {@code inTransaction} fails this test and no other in the suite.
   *
   * <p>It does <b>not</b> see whether the connection was returned to the pool, which is a property
   * of the real {@code TransactionTemplate} and is asserted where that lives, in {@code
   * TransactionBoundaryTest.an_embedding_that_stalls_holds_no_database_connection}.
   */
  @Test
  void the_embedding_happens_after_the_commit_and_not_inside_it() {
    TrackingUnitOfWork tracking = new TrackingUnitOfWork();
    WatchingEmbeddings watching = new WatchingEmbeddings(tracking);
    Archive watched = watchedArchive(tracking, watching);

    WriteResult local =
        watched.applyVerdict(
            proposal("Payments uses mTLS", "Inter-service calls present certs."),
            isNew(),
            PAYMENTS);
    tracking.reset();
    watching.reset();

    Memory promoted = watched.promote(local.memoryId(), "it holds everywhere", CURATOR).promoted();

    assertEquals(
        1,
        tracking.opened(),
        "sanity: the promotion must run its saves inside a unit of work at all");
    assertEquals(1, watching.calls(), "sanity: the promotion must embed exactly once");
    assertNotNull(embeddingOf(promoted.id()), "sanity: the embedding must have been stored");
    assertFalse(
        watching.sawOpenUnitOfWork(),
        "the embedding was called with the write transaction still open: a failure in the"
            + " model client can now roll a committed memory back");
  }

  /**
   * An argument the archive can refuse without reading anything is refused without opening a unit
   * of work.
   *
   * <p>{@code Archive.promote} checks {@code by} before {@code inTransaction}, matching what {@code
   * applyVerdict} does with {@code Validation.check}, so a malformed call does not take a pooled
   * connection on its way to being rejected. Under {@link UnitOfWork#NONE} — what the rest of this
   * class is wired with — that placement is invisible, and the comment in {@code promote} used to
   * claim it was untestable for exactly that reason. It is not: {@link TrackingUnitOfWork} counts
   * the units of work an operation opens, and zero against one is the whole of the difference.
   * Measured, not assumed — moving the guard inside {@code inTransaction} passes every other case
   * in this file and fails this one.
   *
   * <p>Worth being blunt about why this test exists: the claim it replaces was "no instrument can
   * see this" while the instrument sat twenty lines below, written in the same commit. This branch
   * has spent most of its effort on the inverse error — a test offered as evidence for something it
   * cannot observe — and an untestability claim is the same mistake pointed the other way.
   *
   * <p>What this still cannot see is what the placement <em>costs</em>: a Hikari connection held
   * while an argument is refused. {@link TrackingUnitOfWork} runs the work itself and owns no pool,
   * so that half lives with the real transaction manager, in {@code TransactionBoundaryTest}.
   *
   * <p>The origin really exists, so the refusal cannot be coming from the id.
   */
  @Test
  void a_refused_argument_takes_no_unit_of_work_at_all() {
    TrackingUnitOfWork tracking = new TrackingUnitOfWork();
    WatchingEmbeddings watching = new WatchingEmbeddings(tracking);
    Archive watched = watchedArchive(tracking, watching);

    WriteResult local =
        watched.applyVerdict(
            proposal("Payments uses mTLS", "Inter-service calls present certs."),
            isNew(),
            PAYMENTS);
    tracking.reset();
    watching.reset();

    assertThrows(
        ValidationException.class,
        () -> watched.promote(local.memoryId(), "it holds everywhere", null));

    assertEquals(
        0,
        tracking.opened(),
        "a unit of work was opened to refuse an argument the archive could reject"
            + " without reading anything");
    assertEquals(0, watching.calls(), "and nothing was embedded for a write that never was");
  }

  // --- the index this promotion added to ------------------------------------

  /**
   * Promotion demotes in the tier it added a record to, and leaves the tier it took one out of
   * alone.
   *
   * <p>Every other path that adds an {@code active} record to a tier ends with a demotion pass over
   * that tier, and {@code Archive.invalidate} says why it does not: "this removes a record from the
   * index rather than adding one, so the tier can only have got emptier". Promotion does both at
   * once — global gains a record, the project loses one — so it demotes in global and nowhere else.
   * Without the pass, promotion would be the one write that can push the global index over its
   * threshold and leave it there; with the pass on the wrong tier, a curator's promotion would
   * quietly shrink the index of the project it was reading.
   *
   * <p>The fixture is built so both halves are real counterfactuals, and the threshold is dropped
   * to 1 only <em>after</em> the four records are written. Writing them under a threshold of 1
   * would have each write demote the one before it, leaving the project tier with a single active
   * record and nothing for a stray pass to take — a fixture that passes this test whichever tier
   * the pass runs over.
   *
   * <p>The clock is advanced before the promotion so the older global record scores strictly lower
   * than the one arriving, and the demotion is decided by decay rather than by the id tie-break.
   */
  /**
   * The record a promotion just wrote is never the one its own demotion pass takes out.
   *
   * <p><b>This is the bug the sibling test's message claimed was impossible.</b> {@code
   * promotion_demotes_in_the_tier_it_added_to_and_not_the_one_it_left} asserts "the record that was
   * just promoted is not the one to demote", and it passed against a build with no such rule: its
   * fixture advances the clock ninety days, which decays the resident below the newcomer, so the
   * arithmetic happened to come out right. The sentence was true of that fixture and not of the
   * code.
   *
   * <p>The arithmetic that exposes it is ordinary. A promoted record scores exactly {@code 1.0} —
   * {@link Scoring} floors uses at one and, with {@code lastUsed} null, decays from {@code
   * formed.at}, which is this instant — so <em>any</em> global record read twice and read recently
   * outscores it. The demotion pass then picks the newcomer as the lowest-scoring candidate and
   * files it {@code cold} in the same transaction that promoted it. Global is the tier every
   * project reads, so "the other records here have been read twice" is its ordinary condition: the
   * more useful the global tier is, the more reliably a promotion into it would undo itself.
   *
   * <p>The returned record is asserted against the stored row, not instead of it. That is the
   * second half of the same defect: {@code promote} returns the object it built, so under the bug
   * it hands back a record stamped {@code ACTIVE} while the row on disk reads {@code COLD}, and its
   * javadoc's "as it was committed" is false.
   *
   * <p>The resident must still fall out, or this test would pass on a build whose demotion pass
   * does not run at all — which is a different bug and one {@code
   * promotion_demotes_in_the_tier_it_added_to_and_not_the_one_it_left} is what holds down.
   */
  @Test
  void the_record_just_promoted_is_never_the_one_demoted_by_its_own_pass() {
    WriteResult resident =
        archive.applyVerdict(
            proposal("The retry budget is 4", "Four everywhere."), isNew(), Home.global());
    // Read twice, so the resident scores 2.0 against the newcomer's 1.0.
    // Two reads of a global memory is not a contrived history; it is what
    // the tier is for.
    archive.read(List.of(resident.memoryId()));
    archive.read(List.of(resident.memoryId()));
    WriteResult candidate = write("Payments uses mTLS", "Inter-service calls present certs.");
    archive = archiveWithThreshold(1);

    Memory promoted =
        archive.promote(candidate.memoryId(), "it holds everywhere", CURATOR).promoted();

    Memory stored = archive.get(promoted.id());
    assertEquals(
        MemoryState.ACTIVE,
        stored.state(),
        "the promotion demoted the record it had just written: it was undone by its own"
            + " index pass, in the transaction that performed it");
    assertEquals(
        stored.state(),
        promoted.state(),
        "promote returns the record 'as it was committed', so the returned state and the"
            + " stored state must agree");
    assertEquals(
        MemoryState.COLD,
        archive.get(resident.memoryId()).state(),
        "sanity: global was over threshold, so the pass must have demoted something");
  }

  @Test
  void promotion_demotes_in_the_tier_it_added_to_and_not_the_one_it_left() {
    WriteResult resident =
        archive.applyVerdict(
            proposal("The retry budget is 4", "Four everywhere."), isNew(), Home.global());
    WriteResult candidate = write("Payments uses mTLS", "Inter-service calls present certs.");
    WriteResult bystander = write("Payments logs in JSON", "One object per line.");
    WriteResult onlooker = write("Payments retries twice", "Two attempts, then give up.");
    clock = NOW.plus(Duration.ofDays(90));
    archive = archiveWithThreshold(1);

    Memory promoted =
        archive.promote(candidate.memoryId(), "it holds everywhere", CURATOR).promoted();

    // This fixture cannot establish the exemption rule and does not claim
    // to any more: the ninety-day advance decays the resident below the
    // newcomer, so the newcomer survives on arithmetic whether or not the
    // rule exists. It said "the record that was just promoted is not the one
    // to demote" and passed against a build where it was — see
    // the_record_just_promoted_is_never_the_one_demoted_by_its_own_pass,
    // which is what holds that rule down. Kept here only to pin that the
    // pass chose the resident and not the arrival.
    assertEquals(
        MemoryState.ACTIVE,
        archive.get(promoted.id()).state(),
        "the pass took the resident, not the record that had just arrived");
    assertEquals(
        MemoryState.COLD,
        archive.get(resident.memoryId()).state(),
        "global was over its threshold and the oldest record should have fallen out");
    // The project tier is still over its threshold with two active records,
    // so a demotion pass run there would have demoted one of these.
    assertEquals(MemoryState.ACTIVE, archive.get(bystander.memoryId()).state());
    assertEquals(MemoryState.ACTIVE, archive.get(onlooker.memoryId()).state());
  }

  /**
   * A promotion says what its own index pass took out of the global tier.
   *
   * <p>The gap {@code promote}'s javadoc recorded and could not close: {@code WriteResult.demoted}
   * carries those ids "surfaced rather than silent" and the client's {@code memory_write} renders
   * them, while this method returned a bare {@link Memory} and had nowhere to say it. Global is the
   * tier every project reads, so a promotion into a full index quietly demotes a memory every one
   * of them was relying on, and the only trace was the row.
   *
   * <p>The resident here has been read twice, so it is <em>not</em> the obvious candidate on age —
   * it outscores the arrival. It is demoted only because the arrival is exempt from its own pass,
   * which is what makes this a test of the channel rather than of the arithmetic.
   */
  @Test
  void a_promotion_reports_what_its_own_index_pass_demoted() {
    WriteResult resident =
        archive.applyVerdict(
            proposal("The retry budget is 4", "Four everywhere."), isNew(), Home.global());
    archive.read(List.of(resident.memoryId()));
    archive.read(List.of(resident.memoryId()));
    WriteResult candidate = write("Payments uses mTLS", "Inter-service calls present certs.");
    archive = archiveWithThreshold(1);

    Archive.Promotion promotion =
        archive.promote(candidate.memoryId(), "it holds everywhere", CURATOR);

    assertEquals(
        List.of(resident.memoryId()),
        promotion.demoted(),
        "the ids this promotion pushed out of the global index were not reported, so a"
            + " caller cannot tell a promotion from a promotion that cost a memory");
    assertEquals(
        MemoryState.COLD,
        archive.get(resident.memoryId()).state(),
        "sanity: the id reported as demoted really is cold");
    assertEquals(promotion.promoted().id(), archive.get(promotion.promoted().id()).id());
  }

  /**
   * A promotion that displaced nothing says so with an empty list, never a null: a caller rendering
   * "and this demoted:" has one shape to handle, which is the rule {@code WriteResult.demoted}
   * already keeps.
   */
  @Test
  void a_promotion_that_displaced_nothing_reports_an_empty_list() {
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");

    Archive.Promotion promotion = archive.promote(local.memoryId(), "it holds everywhere", CURATOR);

    assertEquals(List.of(), promotion.demoted());
  }

  /**
   * What a promotion hands back cannot be edited by whoever receives it.
   *
   * <p>Not defensiveness against a live view — nothing mutates the list after {@code
   * demoteOverThreshold} returns it. It is that the two paths through that method return different
   * <em>kinds</em> of list, a mutable {@code ArrayList} when something was demoted and an immutable
   * {@code List.of()} when nothing was, and a caller would discover which one it had by throwing.
   * The copy makes both the same.
   */
  @Test
  void a_promotion_hands_back_a_list_a_caller_cannot_edit() {
    // This promotion has to actually demote something. With nothing demoted
    // the list is demoteOverThreshold's own List.of(), which is already
    // unmodifiable, and the assertion below holds whether or not anything
    // copies it — the first version of this test was exactly that, and the
    // mutant that drops the copy survived it.
    archive.applyVerdict(
        proposal("The retry budget is 4", "Four everywhere."), isNew(), Home.global());
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");
    archive = archiveWithThreshold(1);

    Archive.Promotion promotion = archive.promote(local.memoryId(), "it holds everywhere", CURATOR);

    assertFalse(
        promotion.demoted().isEmpty(), "sanity: with nothing demoted this asserts nothing at all");
    assertThrows(UnsupportedOperationException.class, () -> promotion.demoted().add("mem_x"));
  }

  /**
   * The origin is not a component of {@link Archive.Promotion}, and this is what that decision
   * rests on: {@code supersedes} on the promoted record already is the origin id, written by the
   * same constructor call. A second spelling could disagree with the link a chain walker follows,
   * and there would be no way to tell which was right.
   */
  @Test
  void the_promotion_names_its_origin_through_the_record_it_wrote() {
    WriteResult local = write("Payments uses mTLS", "Inter-service calls present certs.");

    Archive.Promotion promotion = archive.promote(local.memoryId(), "it holds everywhere", CURATOR);

    assertEquals(local.memoryId(), promotion.promoted().supersedes());
    assertEquals(
        promotion.promoted().id(),
        archive.get(local.memoryId()).supersededBy(),
        "and the link points both ways");
  }

  // --- fixtures -------------------------------------------------------------

  private WriteResult write(String summary, String body) {
    return archive.applyVerdict(proposal(summary, body), isNew(), PAYMENTS);
  }

  private static MemoryProposal proposal(String summary, String body) {
    return new MemoryProposal(summary, "payments auth work", body, "claude-code", "proj/payments");
  }

  private static Verdict isNew() {
    return new Verdict(VerdictKind.NEW, null, "novel");
  }

  /**
   * The archive under the two instruments: a unit of work that counts, and an embedding client that
   * says what it saw. Shared by the two tests about where the transaction boundary falls.
   */
  private Archive watchedArchive(TrackingUnitOfWork tracking, WatchingEmbeddings watching) {
    return new Archive(
        store,
        new ReasonLog(jdbc),
        watching,
        tracking,
        MAX_BODY_CHARS,
        INDEX_THRESHOLD,
        HALF_LIFE_DAYS,
        () -> clock,
        () -> String.format("mem_%06d", ++minted));
  }

  private int rowCount() {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM memories", Integer.class);
    return rows == null ? 0 : rows;
  }

  /**
   * The stored vector as pgvector's text form, or {@code null} for a record written while the
   * endpoint was not working.
   */
  private String embeddingOf(String id) {
    return jdbc.queryForObject("SELECT embedding FROM memories WHERE id = ?", String.class, id);
  }

  /**
   * A unit of work that says whether it is currently open.
   *
   * <p>{@link UnitOfWork#NONE} cannot answer that question — it is what the rest of this class is
   * wired with, and it is indistinguishable from having no boundary at all, which is exactly the
   * thing under test in {@link #the_embedding_happens_after_the_commit_and_not_inside_it}. This one
   * runs the work unchanged and only counts.
   *
   * <p>Not thread-safe and not meant to be: one test, one thread, and a volatile field here would
   * suggest this measures something about concurrency, which it does not.
   */
  private static final class TrackingUnitOfWork implements UnitOfWork {

    private int open;
    private int opened;

    @Override
    public <T> T inTransaction(Supplier<T> work) {
      open++;
      opened++;
      try {
        return work.get();
      } finally {
        open--;
      }
    }

    boolean isOpen() {
      return open > 0;
    }

    int opened() {
      return opened;
    }

    void reset() {
      opened = 0;
    }
  }

  /**
   * An embedding client that records whether a unit of work was open when it was called.
   *
   * <p>{@link StubEmbeddingClient} is final and answers a different question, so this is its own
   * class rather than a subclass. The vector it returns only has to be the width the schema
   * declares; nothing in the test that uses it asserts on what a vector means.
   */
  private static final class WatchingEmbeddings implements EmbeddingClient {

    private final TrackingUnitOfWork unitOfWork;
    private int calls;
    private boolean sawOpenUnitOfWork;

    WatchingEmbeddings(TrackingUnitOfWork unitOfWork) {
      this.unitOfWork = unitOfWork;
    }

    @Override
    public float[] embed(String text) {
      calls++;
      if (unitOfWork.isOpen()) {
        sawOpenUnitOfWork = true;
      }
      float[] vector = new float[StubEmbeddingClient.DIM];
      vector[0] = 1.0f;
      return vector;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      List<float[]> vectors = new ArrayList<>(texts.size());
      for (String text : texts) {
        vectors.add(embed(text));
      }
      return List.copyOf(vectors);
    }

    int calls() {
      return calls;
    }

    boolean sawOpenUnitOfWork() {
      return sawOpenUnitOfWork;
    }

    void reset() {
      calls = 0;
      sawOpenUnitOfWork = false;
    }
  }
}
