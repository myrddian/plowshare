package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * The archive's semantics, against a real Postgres.
 *
 * <p>Most of these port {@code tests/archive/test_archive.py}. Excalibur asserts on which of two
 * directories a file landed in; that mechanism is gone, so the property it bought — <b>a retired
 * record leaves the search path entirely</b> — is asserted here on {@link MemoryStore#loadAll},
 * which is what recall will read in Task 8. Where a Python test asserts on {@code grep}, this
 * asserts on the search path for the same reason: the question is what a search can reach, and the
 * regex was only ever how Excalibur asked.
 *
 * <p>The tier tests at the end are <b>not</b> ports. Excalibur has one pool, so nothing upstream
 * covers them; they are the design spec's rules stated as tests.
 */
@Tag("full-db")
@Testcontainers
class ArchiveTest {

  /**
   * The pgvector image, not stock postgres:16: the migration creates the extension, and stock
   * Postgres has no vector.so to create it from.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  /** Excalibur's fixture instant, kept so the two suites read alike. */
  private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

  private static final Home PAYMENTS = Home.of("payments");

  private static final int MAX_BODY_CHARS = 1000;
  private static final double HALF_LIFE_DAYS = 30.0;

  /**
   * Excalibur's fixture threshold. High enough that most tests never trip demotion, low enough that
   * the demotion tests can set their own.
   */
  private static final int INDEX_THRESHOLD = 3;

  private Instant clock;
  private int minted;
  private MemoryStore store;
  private ReasonLog reasons;
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

  /**
   * One container, migrated once, emptied between tests — Excalibur gets a fresh archive per test
   * from {@code tmp_path}, and a container per test would cost a Postgres start-up for the same
   * isolation.
   */
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
    reasons = new JdbcReasonLog(jdbc);
    // Stubbed, never live. Nothing in this file asserts on what a vector
    // means; the writes here need an embedding client only because the write
    // path has one, and a real model would make every test in the class
    // depend on a service being up. RecallTest is where the vectors matter.
    embeddings = new StubEmbeddingClient();
    archive = archiveWithThreshold(INDEX_THRESHOLD);
  }

  /**
   * Sequential ids, matching Excalibur's {@code id_factory=lambda: f"mem_{next(counter):06d}"}.
   * Zero-padded, because the store sorts by id and the real format is padded for exactly that
   * reason — an unpadded counter would sort {@code mem_10} before {@code mem_2} and quietly make
   * every ordering assertion here test the wrong thing.
   */
  private Archive archiveWithThreshold(int threshold) {
    return new Archive(
        store,
        reasons,
        embeddings,
        MAX_BODY_CHARS,
        threshold,
        HALF_LIFE_DAYS,
        () -> clock,
        () -> String.format("mem_%06d", ++minted));
  }

  private static MemoryProposal proposal() {
    return proposal("Payments uses mTLS", "Inter-service calls present client certs.");
  }

  private static MemoryProposal proposal(String summary, String body) {
    return new MemoryProposal(summary, "payments auth work", body, "claude-code", "proj/payments");
  }

  private static Verdict isNew() {
    return new Verdict(VerdictKind.NEW, null, "novel");
  }

  private WriteResult write(String summary, String body, Home home) {
    return archive.applyVerdict(proposal(summary, body), isNew(), home);
  }

  private List<String> indexIds(Home home) {
    return archive.index(home).stream().map(TocEntry::id).toList();
  }

  /**
   * What a search can reach: {@code active} and {@code cold}, never a tombstone. Excalibur asks
   * this question with grep.
   */
  private List<String> searchable(Home home) {
    return store.loadAll(home).stream().map(Memory::id).toList();
  }

  // --- writing ---------------------------------------------------------------

  @Test
  void a_new_verdict_creates_a_record() {
    WriteResult result = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    assertEquals(VerdictKind.NEW, result.kind());
    Memory stored = archive.get(result.memoryId());
    assertEquals("Payments uses mTLS", stored.summary());
    assertEquals(MemoryState.ACTIVE, stored.state());
  }

  /**
   * Provenance is not decoration: a claim whose origin is unknown is a claim nobody can later
   * judge. The archive stamps it from its own clock rather than trusting a caller's.
   */
  @Test
  void a_new_record_carries_the_provenance_it_was_formed_with() {
    WriteResult result = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    Provenance formed = archive.get(result.memoryId()).formed();
    assertEquals(NOW, formed.at());
    assertEquals("claude-code", formed.by());
    assertEquals("proj/payments", formed.where());
  }

  @Test
  void a_new_record_appears_in_the_index() {
    archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    assertEquals(
        List.of("Payments uses mTLS"),
        archive.index(PAYMENTS).stream().map(TocEntry::summary).toList());
  }

  /**
   * Newest wins, and the chain is walkable from either end. The old record keeps its body and gains
   * a pointer to what replaced it; the archive judges shape, never truth.
   */
  @Test
  void superseding_marks_the_old_record_and_links_both_ways() {
    WriteResult first = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    WriteResult second =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "Actually mTLS was rolled back."),
            new Verdict(VerdictKind.SUPERSEDES, first.memoryId(), "contradicts"),
            PAYMENTS);

    Memory old = archive.get(first.memoryId());
    Memory now = archive.get(second.memoryId());
    assertEquals(MemoryState.SUPERSEDED, old.state());
    assertEquals(now.id(), old.supersededBy());
    assertEquals(old.id(), now.supersedes());
    assertEquals(MemoryState.ACTIVE, now.state());
  }

  @Test
  void a_superseded_record_leaves_the_index_but_stays_readable() {
    WriteResult first = archive.applyVerdict(proposal(), isNew(), PAYMENTS);
    archive.applyVerdict(
        proposal("Payments uses mTLS", "revised"),
        new Verdict(VerdictKind.SUPERSEDES, first.memoryId(), "contradicts"),
        PAYMENTS);

    assertFalse(indexIds(PAYMENTS).contains(first.memoryId()));
    assertNotNull(archive.get(first.memoryId()));
  }

  /**
   * A merge appends and creates nothing, so the result names the target.
   *
   * <p>The dated seam is load-bearing, not decoration: without it the merged body reads as one
   * voice and nobody can tell afterwards which half arrived when, or from whom — which is exactly
   * what a reader needs when only one half turns out to be wrong.
   */
  @Test
  void merging_appends_to_the_target_and_creates_no_new_record() {
    WriteResult first = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    WriteResult result =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "Tokens expire in 15m."),
            new Verdict(VerdictKind.MERGED_INTO, first.memoryId(), "refinement"),
            PAYMENTS);

    assertEquals(first.memoryId(), result.memoryId());
    Memory merged = archive.get(first.memoryId());
    assertTrue(merged.body().contains("Tokens expire in 15m."), merged.body());
    assertTrue(merged.body().contains("Inter-service calls present client certs."), merged.body());
    assertTrue(
        merged.body().contains("*Merged 2026-08-16T12:00:00Z by claude-code*"), merged.body());
    assertEquals(1, archive.index(PAYMENTS).size());
  }

  /**
   * A verdict naming a target that does not exist is refused rather than silently downgraded to
   * NEW: the caller believed something specific about the archive's contents and was wrong, and
   * quietly writing anyway leaves the record it meant to retire live, with nothing to show for it.
   */
  @ParameterizedTest
  @EnumSource(
      value = VerdictKind.class,
      names = {"MERGED_INTO", "SUPERSEDES"})
  void a_verdict_naming_an_unknown_target_is_refused(VerdictKind kind) {
    ArchiveException thrown =
        assertThrows(
            ArchiveException.class,
            () -> archive.applyVerdict(proposal(), new Verdict(kind, "mem_nope", "x"), PAYMENTS));

    // The id is in the message because the caller's next move is to work
    // out which of its ids went stale, and a message without it cannot be
    // acted on.
    assertTrue(thrown.getMessage().contains("mem_nope"), thrown.getMessage());
    assertTrue(archive.index(PAYMENTS).isEmpty());
    // The exact class, not assertThrows' assignability: a target that
    // resolves to nothing is an ABSENCE, and ArchiveRefusedException
    // extends this type, so `assertThrows(ArchiveException.class, ...)`
    // above is satisfied by either. This is the one assertion that says
    // which — and it is the pair of the two refusal tests below, each of
    // which fails in the other direction. A build that classified every
    // requireTarget throw the same way passes exactly one of the three.
    assertEquals(
        ArchiveException.class,
        thrown.getClass(),
        "an id that resolves to nothing is absent, never refused");
  }

  /**
   * A verdict naming a target in another tier is a refusal, and the type says so.
   *
   * <p>The shadowing rule, and not a missing row: the target resolved. This and the two above are
   * the trio {@code ApiExceptionHandler} recorded as a known imprecision for two slices, when one
   * type had to answer 404 for all three.
   */
  @Test
  void a_verdict_naming_a_target_in_another_tier_is_a_refusal_and_not_an_absence() {
    WriteResult global = archive.applyVerdict(proposal(), isNew(), Home.global());

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () ->
                archive.applyVerdict(
                    proposal(),
                    new Verdict(VerdictKind.SUPERSEDES, global.memoryId(), "x"),
                    PAYMENTS));

    assertTrue(refused.getMessage().contains("shadowed, never superseded"), refused.getMessage());
  }

  /**
   * A merge or supersession with nothing to merge into is a verdict that contradicts itself, not a
   * write with a missing field — and so a refusal rather than an absence, which is the half the
   * type now carries.
   */
  @ParameterizedTest
  @EnumSource(
      value = VerdictKind.class,
      names = {"MERGED_INTO", "SUPERSEDES"})
  void a_verdict_naming_no_target_at_all_is_refused(VerdictKind kind) {
    assertThrows(
        ArchiveRefusedException.class,
        () -> archive.applyVerdict(proposal(), new Verdict(kind, null, "x"), PAYMENTS));
  }

  @Test
  void an_invalid_proposal_is_rejected_before_any_write() {
    assertThrows(
        ValidationException.class,
        () -> archive.applyVerdict(proposal("  ", "body"), isNew(), PAYMENTS));

    assertTrue(archive.index(PAYMENTS).isEmpty());
  }

  /**
   * The other half of "before any write", and the half a fresh archive cannot show: validation runs
   * before the target is touched, so a malformed proposal cannot retire a live memory on its way to
   * being rejected. A validate-after-supersede would leave the archive with the old record retired
   * and nothing standing in its place.
   */
  @Test
  void a_rejected_proposal_does_not_retire_its_target() {
    WriteResult first = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    assertThrows(
        ValidationException.class,
        () ->
            archive.applyVerdict(
                proposal("Payments uses mTLS", "  "),
                new Verdict(VerdictKind.SUPERSEDES, first.memoryId(), "contradicts"),
                PAYMENTS));

    assertEquals(MemoryState.ACTIVE, archive.get(first.memoryId()).state());
    assertNull(archive.get(first.memoryId()).supersededBy());
  }

  // --- reading ---------------------------------------------------------------

  @Test
  void reading_returns_the_records_and_counts_the_use() {
    WriteResult created = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    List<Memory> read = archive.read(List.of(created.memoryId()));

    assertEquals(List.of(created.memoryId()), read.stream().map(Memory::id).toList());
    assertEquals(1, archive.get(created.memoryId()).uses());
    assertEquals(NOW, archive.get(created.memoryId()).lastUsed());
  }

  /**
   * And it increments rather than assigns. A read that overwrote the count with 1 would make a
   * memory fetched fifty times look exactly as cold as one fetched once, and decay would have no
   * history to rank on.
   */
  @Test
  void reading_the_same_memory_twice_counts_two_uses() {
    WriteResult created = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    archive.read(List.of(created.memoryId()));
    clock = NOW.plus(Duration.ofHours(1));
    archive.read(List.of(created.memoryId()));

    assertEquals(2, archive.get(created.memoryId()).uses());
    assertEquals(NOW.plus(Duration.ofHours(1)), archive.get(created.memoryId()).lastUsed());
  }

  /**
   * A survey returns no bodies and is therefore not a use. Counters move on the two paths that hand
   * back bodies — {@code read} and {@code recall} — and nowhere else, or every glance at the index
   * would look like the memory earning its place.
   */
  @Test
  void get_does_not_count_a_use() {
    WriteResult created = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    archive.get(created.memoryId());

    assertEquals(0, archive.get(created.memoryId()).uses());
  }

  @Test
  void reading_an_id_that_was_never_written_is_refused() {
    assertThrows(ArchiveException.class, () -> archive.read(List.of("mem_nope")));
    assertThrows(ArchiveException.class, () -> archive.get("mem_nope"));
  }

  /**
   * A batch naming one unknown id counts <em>no</em> uses — not the uses of the ids that happened
   * to sort before it.
   *
   * <p>{@code Archive.read} resolves every id before it saves anything, for exactly this reason.
   * The test above asserts only the throw, and the obvious wrong implementation — save each memory
   * as it resolves — throws too: rewriting the loop that way left all 176 tests green while the
   * first memory of every failed batch silently earned a use.
   *
   * <p>Why a use count is worth a test, being only a decay signal: it is what {@link Scoring} ranks
   * by, so a caller retrying a bad batch ten times promotes whichever memory sorts first ten places
   * up the index and pushes a real one out of it. And the archive here is wired with {@link
   * UnitOfWork#NONE}, which is the honest case — the property has to come from the order of the two
   * loops, not from a rollback the production wiring happens to add on top.
   */
  @Test
  void a_batch_naming_one_unknown_id_counts_no_uses_at_all() {
    WriteResult first = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    assertThrows(ArchiveException.class, () -> archive.read(List.of(first.memoryId(), "mem_nope")));

    assertEquals(
        0,
        archive.get(first.memoryId()).uses(),
        "the id before the unknown one must not have been counted");
    assertNull(archive.get(first.memoryId()).lastUsed());
  }

  // --- a vector somebody else already paid for -------------------------------

  /**
   * A vector offered with the memory's own text is stored, and costs no model call.
   *
   * <p>The saving item 1 of task 9 is about: {@code Scribe} embeds the same text this memory is
   * embedded for, so the write can take its numbers rather than asking the endpoint a second time.
   * The call count is the assertion — an equal vector could have been recomputed, and the whole
   * point is that it was not.
   */
  @Test
  void a_vector_offered_with_the_memorys_own_text_is_stored_and_costs_no_model_call() {
    MemoryProposal proposal = proposal();
    String owed = proposal.summary().strip() + "\n" + proposal.scope().strip();
    float[] vector = new float[StubEmbeddingClient.DIM];
    vector[7] = 1.0f;

    WriteResult filed =
        archive.applyVerdict(proposal, isNew(), PAYMENTS, new Archive.Precomputed(vector, owed));

    assertEquals(0, embeddings.calls(), "the endpoint was asked for a vector already in hand");
    assertEquals(
        0,
        archive.recall("anything", PAYMENTS, 10).unsearchable(),
        "and the memory really has one: nothing about it is unsearchable");
    assertEquals(filed.memoryId(), archive.get(filed.memoryId()).id(), "fixture");
  }

  /**
   * A vector computed from text that is not the memory's is refused, and nothing is written.
   *
   * <p><b>The reason the shortcut carries its own guard.</b> The identity it rests on — {@code
   * Scribe.question} and {@link Archive#embeddedText} — is incidental: two expressions in two
   * classes that happen to agree. While the two vectors were computed separately a drift only made
   * search worse, and each vector was still right for its own use. Reused, the same drift stores a
   * <em>wrong vector against a right memory</em>: the row is perfect, the search misses it, and
   * nothing anywhere goes red. A string compare buys the saved model call.
   *
   * <p>Refused rather than stored unembedded, and refused inside the unit of work rather than after
   * it: a caller offering a vector from other text is this server being wrong, and the failure has
   * to be one somebody fixes. <b>That the refusal takes the whole write back is asserted where the
   * real transaction manager is</b> — {@code
   * TransactionBoundaryTest.a_vector_computed_from_other_text_takes_the_whole_write_back} — for the
   * reason that file already gives about supersession: this archive is wired with {@link
   * UnitOfWork#NONE}, so a rollback assertion here would be measuring a rollback nothing performs.
   */
  @Test
  void a_precomputed_vector_is_refused_when_it_was_computed_from_other_text() {
    float[] vector = new float[StubEmbeddingClient.DIM];
    vector[7] = 1.0f;
    // A NEAR miss and not an unrelated string, because that is the drift the
    // guard exists for: a strip, a separator, a case fold. The two share a
    // 33-character prefix and differ in one letter after it.
    MemoryProposal proposal = proposal();
    String almost = proposal.summary() + "\n" + proposal.scope().toUpperCase(Locale.ROOT);

    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                archive.applyVerdict(
                    proposal, isNew(), PAYMENTS, new Archive.Precomputed(vector, almost)));

    assertTrue(
        refused.getMessage().contains("wrong vector against a right memory"), refused.getMessage());
    // The offset, because the lengths alone say nothing when the two texts
    // are the same length -- which they are here, and which is the commonest
    // shape of the drift. An operator with an offset finds the difference in
    // a second.
    assertTrue(
        refused
            .getMessage()
            // summary + the newline: the two texts share every
            // character up to where the scope begins.
            .contains("first differ at character " + (proposal.summary().length() + 1)),
        refused.getMessage());
    // The offered text is not quoted back: it is the caller's own prose and
    // this message reaches an HTTP response and a log.
    assertFalse(
        refused.getMessage().contains(proposal.scope().toUpperCase(Locale.ROOT)),
        refused.getMessage());
    assertEquals(
        0,
        embeddings.calls(),
        "and the endpoint was never asked: the refusal is about the vector offered, not"
            + " a fallback to computing one");
  }

  /**
   * A merge stores no vector, so a precomputed one is neither used nor checked.
   *
   * <p>{@code merge} appends to a body and leaves summary and scope exactly as they were, so the
   * stored vector is still the right one and there is nothing to store. The guard skips rather than
   * refusing: firing on a path where nothing can go wrong is a guard that costs a write and buys
   * nothing. The offered text here is deliberately wrong, which is what makes the skip visible.
   */
  @Test
  void a_merge_stores_no_vector_so_a_precomputed_one_is_neither_used_nor_checked() {
    WriteResult target = archive.applyVerdict(proposal(), isNew(), PAYMENTS);
    String before = embeddingOf(target.memoryId());
    float[] vector = new float[StubEmbeddingClient.DIM];
    vector[7] = 1.0f;

    WriteResult merged =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "and the certs rotate weekly"),
            new Verdict(VerdictKind.MERGED_INTO, target.memoryId(), "more detail"),
            PAYMENTS,
            new Archive.Precomputed(vector, "text that is not any memory's"));

    assertEquals(target.memoryId(), merged.memoryId());
    // "Not checked" is the assertion above -- no refusal, though the text is
    // deliberately wrong. "NOT USED" is this one, and it was proven by
    // nothing: the id comes back the same whether or not the offered vector
    // was written over the target's own, so a build that stored it passed.
    assertEquals(
        before, embeddingOf(target.memoryId()), "the target kept the vector its own write gave it");
  }

  // --- why a write was filed as it was ---------------------------------------

  /**
   * The reason survives the response that carried it.
   *
   * <p>The spec's sentence, which nothing delivered until {@code V5__memory_reasons.sql}: every
   * scribe fallback says which one it was "so a year later the archive can distinguish 'no scribe
   * judged this' from 'the scribe was busy'". {@code WriteResult} carried it to an HTTP response
   * and no further.
   */
  @Test
  void a_write_records_the_verdicts_reason_where_it_can_be_asked_for_again() {
    WriteResult filed =
        archive.applyVerdict(
            proposal(),
            new Verdict(VerdictKind.NEW, null, "filed flat: the scribe was busy"),
            PAYMENTS);

    List<ReasonLog.Entry> recorded = reasons.forMemory(filed.memoryId());

    assertEquals(1, recorded.size(), recorded.toString());
    ReasonLog.Entry entry = recorded.get(0);
    assertEquals(filed.memoryId(), entry.memoryId());
    assertEquals(VerdictKind.NEW, entry.kind());
    assertEquals("filed flat: the scribe was busy", entry.reason());
    assertNull(entry.targetId(), "NEW names nothing");
    assertEquals(NOW, entry.filedAt());
  }

  /**
   * <b>A merge attaches to the target, and this is why that is safe.</b>
   *
   * <p>The first of the three questions 3a left open, and the whole reason a column on {@code
   * memories} could not carry the reason at all: {@code merge} writes no new row, so the only thing
   * a column could hang on is the target — where it would <em>overwrite</em> what that memory's own
   * write recorded, months earlier. A table does not overwrite. The target ends with two entries,
   * its own first, and the clock moves between them so the order is a fact rather than an accident
   * of insertion.
   */
  @Test
  void a_merge_files_its_reason_against_the_target_and_leaves_the_targets_own_standing() {
    WriteResult target =
        archive.applyVerdict(
            proposal(),
            new Verdict(VerdictKind.NEW, null, "the first write's own account"),
            PAYMENTS);
    clock = NOW.plusSeconds(3600);

    WriteResult merged =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "and the certs rotate weekly"),
            new Verdict(VerdictKind.MERGED_INTO, target.memoryId(), "more detail on the same"),
            PAYMENTS);

    assertEquals(target.memoryId(), merged.memoryId(), "fixture: a merge mints no id");
    List<ReasonLog.Entry> recorded = reasons.forMemory(target.memoryId());

    assertEquals(2, recorded.size(), recorded.toString());
    assertEquals("the first write's own account", recorded.get(0).reason());
    assertEquals(NOW, recorded.get(0).filedAt());
    assertEquals("more detail on the same", recorded.get(1).reason());
    assertEquals(NOW.plusSeconds(3600), recorded.get(1).filedAt());
    assertEquals(VerdictKind.MERGED_INTO, recorded.get(1).kind());
    assertEquals(
        target.memoryId(),
        recorded.get(1).targetId(),
        "the target of a merge is the row it landed in, by construction");
  }

  /**
   * A supersession records which memory it retired, and the retired one keeps its own account.
   *
   * <p>The pair with the merge above: there the two entries land on one memory, here they land on
   * two, and {@code targetId} is what says which record this write replaced. A build that filed
   * every entry against the verdict's target passes the merge test and fails this one.
   */
  @Test
  void a_supersession_records_the_memory_it_retired_and_the_retired_one_keeps_its_own() {
    WriteResult old =
        archive.applyVerdict(
            proposal(), new Verdict(VerdictKind.NEW, null, "the first fact"), PAYMENTS);

    WriteResult replacement =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "the policy changed"),
            new Verdict(VerdictKind.SUPERSEDES, old.memoryId(), "the timeout changed"),
            PAYMENTS);

    assertEquals(
        List.of("the first fact"),
        reasons.forMemory(old.memoryId()).stream().map(ReasonLog.Entry::reason).toList(),
        "the retired record's own account is untouched by what replaced it");

    List<ReasonLog.Entry> recorded = reasons.forMemory(replacement.memoryId());
    assertEquals(1, recorded.size(), recorded.toString());
    assertEquals(VerdictKind.SUPERSEDES, recorded.get(0).kind());
    assertEquals(old.memoryId(), recorded.get(0).targetId());
  }

  /**
   * A {@code NEW} verdict that fills the target field in anyway records no target, and the write
   * still succeeds.
   *
   * <p>{@code create} ignores that field — {@code Verdict} says {@code NEW} "names nothing" — so
   * recording it verbatim would put a foreign key on a value this write never used and fail a write
   * the archive was happy with. The id here was never written, so with the branch removed the
   * insert dies on {@code memory_reasons_target_id_fkey} and takes the memory with it.
   */
  @Test
  void a_new_verdict_that_names_a_target_anyway_records_no_target() {
    WriteResult filed =
        archive.applyVerdict(
            proposal(), new Verdict(VerdictKind.NEW, "mem_never_written", "novel"), PAYMENTS);

    assertNull(reasons.forMemory(filed.memoryId()).get(0).targetId());
    assertEquals(1, archive.index(PAYMENTS).size(), "and the write itself stands");
  }

  /**
   * A memory nothing was recorded for answers with no entries rather than refusing.
   *
   * <p>Unlike {@link Archive#get}, and deliberately: "what is recorded about this memory" has an
   * honest empty answer, because a memory written before this table existed has no entries and is
   * not missing. Refusing would make the absence of history indistinguishable from the absence of
   * the memory, which is the collapse the table exists to undo one level down.
   *
   * <p>The archive is not empty when this asks, which is the point: a build whose {@code WHERE
   * memory_id = ?} was dropped would hand back the other memory's entry, and a fixture with nothing
   * in it could not see that.
   */
  @Test
  void a_memory_nothing_was_recorded_for_answers_with_no_entries_rather_than_refusing() {
    archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    assertEquals(List.of(), reasons.forMemory("mem_never_written"));
  }

  /**
   * The entries come back oldest first by the archive's own clock, and not by the order they were
   * inserted.
   *
   * <p>Written because the sweep found the {@code ORDER BY} surviving its removal: with the entries
   * inserted in time order this planner returns them in that order for its own reasons, which is
   * the same measurement {@code ProposalStore.ruledOn} records about a bare {@code DISTINCT}.
   * <b>That class dropped its ordering promise and this one keeps it</b>, and the difference is the
   * caller: nothing needed {@code ruledOn} ordered, while a history that does not read oldest-first
   * is not a history. So the fixture makes the two orders disagree — the merge is filed an hour
   * <em>before</em> the write it merges into — and the promise is held by something rather than by
   * the planner's habit.
   */
  @Test
  void entries_come_back_oldest_first_even_when_they_were_not_written_in_that_order() {
    // The labels say where each row falls in each of the two orders, because
    // the whole subject of this test is that they disagree. An earlier
    // version called both of them "written second", which is true of one.
    clock = NOW.plusSeconds(3600);
    WriteResult target =
        archive.applyVerdict(
            proposal(),
            new Verdict(VerdictKind.NEW, null, "inserted first, stamped later"),
            PAYMENTS);

    clock = NOW;
    archive.applyVerdict(
        proposal("Payments uses mTLS", "and the certs rotate weekly"),
        new Verdict(VerdictKind.MERGED_INTO, target.memoryId(), "inserted second, stamped earlier"),
        PAYMENTS);

    assertEquals(
        List.of("inserted second, stamped earlier", "inserted first, stamped later"),
        reasons.forMemory(target.memoryId()).stream().map(ReasonLog.Entry::reason).toList(),
        "the entries came back in insertion order, which is what this planner does when"
            + " the ORDER BY is dropped");
  }

  /**
   * A verdict kind outside the three is refused by the database.
   *
   * <p>The rule V1 states about memory states, one table over: a kind {@code
   * VerdictKind.fromWireName} cannot read back would be written successfully and then be unreadable
   * for ever, so the row would exist and {@link ReasonLog#forMemory} would throw for every memory
   * near it. Java refuses it first — {@code Entry} takes the enum — so this is what stops a
   * migration or a psql session writing one.
   */
  @Test
  void a_verdict_kind_outside_the_three_is_refused_by_the_database() {
    WriteResult filed = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO memory_reasons"
                        + " (memory_id, filed_at, kind, reason) VALUES (?, now(), ?, ?)",
                    filed.memoryId(),
                    "reconsidered",
                    "why"));

    assertTrue(refused.getMessage().contains("memory_reasons_kind_known"), refused.getMessage());
  }

  /**
   * The kind and the target have to agree, and the database is what says so.
   *
   * <p>The rule V2 declined to leave to Java, for the reason {@code
   * proposals_settlement_matches_state} gives: a half-written row reads to a human as something it
   * is not, and a {@code merged_into} entry with no target reads as a merge into nothing. Only
   * {@code Archive.applyVerdict}'s ternary kept these true, and a second writer would not go
   * through it.
   *
   * <p>Both rows are otherwise valid — a known kind, a memory that exists, and for the first a
   * target that exists — so {@code memory_reasons_target_matches_kind} is the only constraint
   * either can break. Postgres reports one, so a row that broke two would leave the assertion
   * unable to say which.
   */
  @Test
  void a_reason_whose_kind_and_target_disagree_is_refused_by_the_database() {
    WriteResult filed = archive.applyVerdict(proposal(), isNew(), PAYMENTS);

    DataIntegrityViolationException namesOne =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertReason(filed.memoryId(), VerdictKind.NEW, filed.memoryId()));
    assertTrue(
        namesOne.getMessage().contains("memory_reasons_target_matches_kind"),
        namesOne.getMessage());

    DataIntegrityViolationException namesNone =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertReason(filed.memoryId(), VerdictKind.MERGED_INTO, null));
    assertTrue(
        namesNone.getMessage().contains("memory_reasons_target_matches_kind"),
        namesNone.getMessage());
  }

  /**
   * A merge lands in the row it merged into, and that is a constraint rather than a comment.
   *
   * <p>{@code Archive.merge} writes no new row — it appends to the target and returns the target's
   * id — so a {@code merged_into} entry whose {@code memory_id} and {@code target_id} differ
   * describes a write that cannot have happened. {@code target_id} carried this as "by
   * construction", which is a claim about one Java method.
   *
   * <p>A second real memory, so the target foreign key resolves and {@code
   * memory_reasons_target_matches_kind} passes: the only thing wrong with this row is which memory
   * the merge says it landed in.
   */
  @Test
  void a_merge_recorded_against_a_memory_that_is_not_its_target_is_refused() {
    WriteResult landed = archive.applyVerdict(proposal(), isNew(), PAYMENTS);
    WriteResult elsewhere =
        archive.applyVerdict(
            proposal("The retry budget is 4", "Four attempts."), isNew(), PAYMENTS);

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertReason(landed.memoryId(), VerdictKind.MERGED_INTO, elsewhere.memoryId()));

    assertTrue(
        refused.getMessage().contains("memory_reasons_merge_lands_on_its_target"),
        refused.getMessage());
  }

  /**
   * And an account of a write to a memory that was never written is refused.
   *
   * <p>{@code V2__proposals.sql}'s argument for its own foreign key, unchanged: a record about a
   * memory nobody wrote is not history, it is a bug, and it is safe as a hard constraint only
   * because nothing in this schema deletes a memory in any state.
   */
  @Test
  void an_account_of_a_write_to_a_memory_that_was_never_written_is_refused() {
    // A known kind, so memory_reasons_kind_known cannot be what refuses it:
    // Postgres reports one constraint, so the row is built to violate one.
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO memory_reasons"
                        + " (memory_id, filed_at, kind, reason) VALUES (?, now(), ?, ?)",
                    "mem_never_written",
                    VerdictKind.NEW.wireName(),
                    "why"));

    assertTrue(
        refused.getMessage().contains("memory_reasons_memory_id_fkey"), refused.getMessage());
  }

  /**
   * The stored vector as the database renders it, so a test can say a write left it alone without
   * needing to know what the numbers mean.
   */
  private String embeddingOf(String memoryId) {
    return jdbc.queryForObject(
        "SELECT embedding::text FROM memories WHERE id = ?", String.class, memoryId);
  }

  /**
   * A {@code memory_reasons} row written straight past {@code ReasonLog}, so the schema's own
   * guards are what answers rather than Java's.
   */
  private void insertReason(String memoryId, VerdictKind kind, String targetId) {
    jdbc.update(
        "INSERT INTO memory_reasons (memory_id, filed_at, kind, target_id, reason)"
            + " VALUES (?, now(), ?, ?, 'why')",
        memoryId,
        kind.wireName(),
        targetId);
  }

  // --- invalidation ----------------------------------------------------------

  @Test
  void invalidating_leaves_a_tombstone_with_a_reason_and_never_deletes() {
    WriteResult created =
        archive.applyVerdict(
            proposal("The cache TTL is 60 seconds", "Set in the edge config."),
            isNew(),
            Home.global());

    archive.invalidate(created.memoryId(), "the cache was removed in July", "enzo");

    Memory dead = archive.get(created.memoryId());
    assertEquals(MemoryState.INVALIDATED, dead.state());
    assertEquals("the cache was removed in July", dead.invalidation().reason());
    assertEquals("enzo", dead.invalidation().by());
    assertEquals(NOW, dead.invalidation().at());
    // The body stays: it is what lets someone later judge whether the
    // invalidation itself was right.
    assertEquals("Set in the edge config.", dead.body());
    assertTrue(archive.index(Home.global()).isEmpty());
  }

  // --- what the archive stands behind, and what it files away ----------------

  /**
   * The tombstone is kept, readable, and linked — it is only unreachable by a <em>search</em>,
   * which is the one route a question travels. Excalibur proves this with two directories and a
   * grep; here the state column is the only thing that can guarantee it.
   */
  @Test
  void supersession_files_the_tombstone_out_of_the_search_path() {
    WriteResult first =
        archive.applyVerdict(
            proposal("Payments auth", "Payments authenticates with JWT."), isNew(), PAYMENTS);
    WriteResult second =
        archive.applyVerdict(
            proposal("Payments auth", "Payments authenticates with mTLS."),
            new Verdict(VerdictKind.SUPERSEDES, first.memoryId(), "rolled over"),
            PAYMENTS);

    assertEquals(List.of(second.memoryId()), searchable(PAYMENTS));
    assertEquals(second.memoryId(), archive.get(first.memoryId()).supersededBy());
    assertEquals("Payments authenticates with JWT.", archive.get(first.memoryId()).body());
  }

  /**
   * The failure this prevents was measured on 2026-08-18: asked a question whose only match was an
   * invalidated memory, the librarian returned that known-false memory 5 runs out of 5.
   */
  @Test
  void invalidation_files_the_memory_out_of_the_search_path() {
    WriteResult created =
        archive.applyVerdict(
            proposal("Cert rotation", "Cert rotation is automatic."), isNew(), PAYMENTS);

    archive.invalidate(created.memoryId(), "it never was", "claude-code");

    assertTrue(searchable(PAYMENTS).isEmpty());
    assertEquals("it never was", archive.get(created.memoryId()).invalidation().reason());
  }

  /**
   * Demotion writes {@code cold}, and {@code cold} is live. If this ever filed a demoted record
   * with the tombstones, disuse would silently become deletion: a cold memory is still true, and
   * search is the only way back to it once it has left the index.
   */
  @Test
  void demotion_leaves_a_cold_memory_where_a_search_can_reach_it() {
    archive = archiveWithThreshold(1);
    WriteResult stale = write("Cert rotation", "Rotate the certs by hand.", PAYMENTS);

    WriteResult newer = write("newer", "Something else.", PAYMENTS);

    assertEquals(MemoryState.COLD, archive.get(stale.memoryId()).state());
    assertEquals(List.of(stale.memoryId(), newer.memoryId()), searchable(PAYMENTS));
    assertEquals(List.of(newer.memoryId()), indexIds(PAYMENTS));
  }

  /**
   * {@code read} counts a use and writes the record back. Whoever opened a tombstone on purpose
   * must not thereby make it findable by everyone — which works only because {@link
   * Lifecycle#recordUse} touches the counter and never the state.
   */
  @Test
  void reading_a_tombstone_by_id_does_not_put_it_back_on_the_search_path() {
    WriteResult created =
        archive.applyVerdict(
            proposal("Cert rotation", "Cert rotation is automatic."), isNew(), PAYMENTS);
    archive.invalidate(created.memoryId(), "it never was", "claude-code");

    List<Memory> read = archive.read(List.of(created.memoryId()));

    assertEquals(1, read.get(0).uses());
    assertEquals(MemoryState.INVALIDATED, archive.get(created.memoryId()).state());
    assertTrue(searchable(PAYMENTS).isEmpty());
    assertTrue(archive.index(PAYMENTS).isEmpty());
  }

  /**
   * Supersession chains: the second change to a fact names the record the first one retired. A
   * store that resolved only live records would make that an unknown target and refuse the write.
   */
  @Test
  void a_verdict_may_still_name_a_retired_memory_as_its_target() {
    WriteResult first = archive.applyVerdict(proposal(), isNew(), PAYMENTS);
    WriteResult second =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "second"),
            new Verdict(VerdictKind.SUPERSEDES, first.memoryId(), "changed"),
            PAYMENTS);

    WriteResult third =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "third"),
            new Verdict(VerdictKind.SUPERSEDES, first.memoryId(), "changed again"),
            PAYMENTS);

    assertEquals(third.memoryId(), archive.get(first.memoryId()).supersededBy());
    assertEquals(List.of(second.memoryId(), third.memoryId()), indexIds(PAYMENTS));
  }

  // --- decay and demotion ----------------------------------------------------

  /**
   * The lowest-scoring unpinned record crosses first, and a pinned one never crosses at all. Ported
   * from Excalibur's demotion test, clock moves and all.
   */
  @Test
  void demotion_evicts_the_lowest_scoring_unpinned_records_over_the_threshold() {
    archive = archiveWithThreshold(2);
    // Pinned memories are seeded through the store because no proposal can
    // ask for one: Excalibur's MemoryProposal carries `pin`, and Task 3
    // deliberately dropped it — pinning is the archive's to grant, not a
    // caller's to request. The rule under test is the demotion pass's, and
    // it reads `pinned` off the row either way.
    Memory pinned =
        new Memory(
            "mem_pinned",
            "pinned invariant",
            "payments auth work",
            new Provenance(NOW, "claude-code", "proj/payments"),
            MemoryState.ACTIVE,
            true,
            0,
            null,
            "An invariant.",
            null,
            null,
            null,
            PAYMENTS);
    store.save(pinned);

    WriteResult stale = write("stale", "body", PAYMENTS);
    archive.read(List.of(stale.memoryId()));

    clock = NOW.plus(Duration.ofDays(365));
    // Three active against a threshold of two: exactly one must go, and it
    // must be the year-old record rather than the pinned invariant or the
    // one written a moment ago. Reported back, not silent — a write that
    // quietly pushed another memory out of the index would make the index
    // shrink for a reason no caller could see.
    WriteResult fresh = write("fresh", "body", PAYMENTS);
    assertEquals(List.of(stale.memoryId()), fresh.demoted());
    archive.read(List.of(fresh.memoryId()));

    clock = NOW.plus(Duration.ofDays(366));
    WriteResult newest = write("newest", "body", PAYMENTS);

    List<String> surviving = indexIds(PAYMENTS);
    assertTrue(surviving.contains(pinned.id()), "pinned records are never demoted");
    assertFalse(surviving.contains(stale.memoryId()), "lowest-scoring unpinned record demoted");
    assertTrue(surviving.contains(newest.memoryId()), "the newest record stays");
    assertEquals(MemoryState.COLD, archive.get(stale.memoryId()).state());
    // Demotion is not deletion: the count survives, so a record that falls
    // out of the index once still carries the history decay reconsiders it on.
    assertEquals(1, archive.get(stale.memoryId()).uses());
  }

  /**
   * <b>A gap, recorded rather than hidden.</b> {@code pinned} survives a round trip and demotion
   * honours it — both asserted below, both true today — but <b>no caller-facing path in Plowshare
   * can set it.</b> Excalibur's {@code MemoryProposal} carries a {@code pin} flag; Task 3 dropped
   * it, so {@link Memory#pinned} has no writer outside a raw store call like the one this test
   * makes.
   *
   * <p>What that costs: {@link Scoring}'s own note says raw decay is "brutal to the
   * rare-but-critical memory, which looks statistically identical to dead weight right up until the
   * night it matters", and that pinning — not the formula — is what protects those records. That
   * protection is currently unreachable.
   *
   * <p>Why slice 1 ships without it: demotion fires only once a tier exceeds its threshold, which
   * nothing here will do for a long time, and {@code cold} is not deletion — a demoted memory stays
   * readable by id and stays on the search path. The consequence is bounded and reversible, which
   * is what makes this a gap rather than a bug.
   *
   * <p>The two memories below are identical but for {@code pinned}, and both score lowest in the
   * tier, so the assertion is a real counterfactual: the unpinned twin is the one that crosses.
   * Whoever restores a caller-facing pin should find this test already describing what they are
   * turning on.
   */
  @Test
  void demotion_honours_pinned_though_no_caller_can_set_it() {
    archive = archiveWithThreshold(2);
    // A year old and never used, so both sit far below anything written
    // today: without the pin, both would be demoted before the new record.
    Provenance longAgo =
        new Provenance(NOW.minus(Duration.ofDays(365)), "claude-code", "proj/payments");
    Memory pinned =
        new Memory(
            "mem_pinned",
            "a pinned invariant",
            "payments auth work",
            longAgo,
            MemoryState.ACTIVE,
            true,
            0,
            null,
            "An invariant.",
            null,
            null,
            null,
            PAYMENTS);
    Memory plain =
        new Memory(
            "mem_plain",
            "an unpinned twin",
            "payments auth work",
            longAgo,
            MemoryState.ACTIVE,
            false,
            0,
            null,
            "An invariant.",
            null,
            null,
            null,
            PAYMENTS);
    store.save(pinned);
    store.save(plain);

    // The flag reaches the row and comes back, which is the half of this
    // that a future caller-facing pin would depend on.
    assertTrue(archive.get("mem_pinned").pinned());
    assertFalse(archive.get("mem_plain").pinned());

    WriteResult newest = write("written today", "body", PAYMENTS);

    assertEquals(List.of("mem_plain"), newest.demoted());
    assertEquals(MemoryState.ACTIVE, archive.get("mem_pinned").state());
    assertEquals(MemoryState.COLD, archive.get("mem_plain").state());
    // Index order is id order, and the seeded id sorts after the minted one.
    assertEquals(List.of(newest.memoryId(), "mem_pinned"), indexIds(PAYMENTS));
  }

  /**
   * Under the threshold nothing moves. A demotion pass that ran anyway would empty a small archive
   * one write at a time.
   */
  @Test
  void a_write_under_the_threshold_demotes_nothing() {
    WriteResult first = write("one", "body", PAYMENTS);
    WriteResult second = write("two", "body", PAYMENTS);

    assertTrue(second.demoted().isEmpty());
    assertEquals(List.of(first.memoryId(), second.memoryId()), indexIds(PAYMENTS));
  }

  // --- newest wins: there is no verdict that refuses -------------------------

  /**
   * The scribe judges shape, never truth. A fourth verdict meaning "no, this restates something we
   * retired" was tried and measured: on two word-for-word identical proposals — one a stale fact
   * rediscovered from old code, one a genuine revert — it scored 9/12 and 1/6. It did not learn to
   * discriminate, it moved which error it makes.
   */
  @Test
  void the_verdicts_are_exactly_three() {
    assertEquals(
        List.of("new", "merged_into", "supersedes"),
        Arrays.stream(VerdictKind.values()).map(VerdictKind::wireName).toList());
  }

  /**
   * Nothing is ever refused, so every write path names the record it became. A caller that records
   * the id never has to handle its absence.
   */
  @Test
  void every_verdict_produces_a_memory_id() {
    WriteResult created = archive.applyVerdict(proposal(), isNew(), PAYMENTS);
    WriteResult merged =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "more detail"),
            new Verdict(VerdictKind.MERGED_INTO, created.memoryId(), "adds detail"),
            PAYMENTS);
    WriteResult superseded =
        archive.applyVerdict(
            proposal("Payments uses mTLS", "the newer text"),
            new Verdict(VerdictKind.SUPERSEDES, created.memoryId(), "changed"),
            PAYMENTS);

    for (WriteResult result : List.of(created, merged, superseded)) {
      assertNotNull(result.memoryId());
      assertNotNull(archive.get(result.memoryId()));
    }
  }

  /**
   * {@code retiredReason} existed only to explain a refusal. With nothing refused there is nothing
   * to explain, and a key that is always null is a key callers write branches against for no
   * reason.
   *
   * <p>Python asserts this with {@code hasattr}; a Java field that does not exist would not
   * compile, so this reads the record's components instead — which is what stops the field being
   * added back on a later task without anyone noticing the argument against it.
   */
  @Test
  void a_write_result_carries_no_retired_reason() {
    assertTrue(
        Arrays.stream(WriteResult.class.getRecordComponents())
            .noneMatch(component -> component.getName().equals("retiredReason")));
  }

  /**
   * The comeback case, filed rather than refused. The archive changed its mind once; this changes
   * it back. Which of the two is <em>true</em> is not a question the archive can answer, so the
   * newer record wins and the older one leaves the search path.
   */
  @Test
  void superseding_a_retired_record_is_still_the_newest_that_wins() {
    WriteResult old =
        archive.applyVerdict(proposal("The retry budget is 3", "body"), isNew(), PAYMENTS);
    WriteResult live =
        archive.applyVerdict(
            proposal("The retry budget is 4", "body"),
            new Verdict(VerdictKind.SUPERSEDES, old.memoryId(), "changed"),
            PAYMENTS);

    WriteResult back =
        archive.applyVerdict(
            proposal("The retry budget is 3", "Reverted this week."),
            new Verdict(VerdictKind.SUPERSEDES, live.memoryId(), "reverted"),
            PAYMENTS);

    assertEquals(MemoryState.SUPERSEDED, archive.get(live.memoryId()).state());
    assertEquals(MemoryState.ACTIVE, archive.get(back.memoryId()).state());
    assertEquals(List.of(back.memoryId()), indexIds(PAYMENTS));
    assertEquals(List.of(back.memoryId()), searchable(PAYMENTS));
  }

  // --- the two tiers ---------------------------------------------------------
  //
  // New work. Excalibur has one pool and no equivalent, so nothing upstream
  // covers any of this; these are the design spec's rules stated as tests,
  // and they are the only thing standing between the spec and a plausible
  // misreading of it.

  /**
   * A memory has exactly one home. Never both, so there are no copies and no sync — and nothing
   * that could disagree with itself across tiers.
   */
  @Test
  void a_memory_written_to_a_project_is_not_in_global() {
    WriteResult result = write("project fact", "body", PAYMENTS);

    assertFalse(indexIds(Home.global()).contains(result.memoryId()));
    assertTrue(searchable(Home.global()).isEmpty());
    assertEquals(List.of(result.memoryId()), indexIds(PAYMENTS));
  }

  /**
   * And the other direction, which is where a NULL project bites: a global memory is not in any
   * project's tier either.
   */
  @Test
  void a_memory_written_to_global_is_not_in_any_project() {
    WriteResult result = write("global fact", "body", Home.global());

    assertFalse(indexIds(PAYMENTS).contains(result.memoryId()));
    assertEquals(List.of(result.memoryId()), indexIds(Home.global()));
  }

  /**
   * Project shadows global — and shadowing is not supersession. The global record stays active and
   * gets no tombstone, because it is not wrong; it is simply not the answer for this project, and
   * it remains correct for every other project that reads it.
   */
  @Test
  void a_project_memory_shadows_a_global_one_without_retiring_it() {
    WriteResult global = write("The retry budget is 4", "Four everywhere.", Home.global());

    WriteResult project = write("The retry budget is 3", "Three here.", PAYMENTS);

    Memory stillGlobal = archive.get(global.memoryId());
    assertEquals(MemoryState.ACTIVE, stillGlobal.state());
    assertNull(stillGlobal.supersededBy());
    assertNull(stillGlobal.invalidation());
    // Both are live, each in its own tier. Two records, no copies: the
    // shadowing rule is applied at recall, not by rewriting the archive.
    assertEquals(List.of(global.memoryId()), indexIds(Home.global()));
    assertEquals(List.of(project.memoryId()), indexIds(PAYMENTS));
  }

  /**
   * The rule that makes the one above enforceable rather than merely conventional: a verdict cannot
   * reach across tiers.
   *
   * <p>If a project write could supersede a global memory, one project's local situation would
   * retire a fact every other project depends on — the exact thing "shadowing is not supersession"
   * exists to prevent, arriving through a verdict instead of through a tombstone. A merge is
   * refused for the same reason: it would let one project rewrite global prose. A project that
   * genuinely contradicts a global fact writes NEW in its own tier, and promotion the other way is
   * the curator's, escalating to a human.
   */
  @ParameterizedTest
  @EnumSource(
      value = VerdictKind.class,
      names = {"MERGED_INTO", "SUPERSEDES"})
  void a_verdict_may_not_name_a_target_in_another_tier(VerdictKind kind) {
    WriteResult global = write("The retry budget is 4", "Four everywhere.", Home.global());

    assertThrows(
        ArchiveException.class,
        () ->
            archive.applyVerdict(
                proposal("The retry budget is 3", "Three here."),
                new Verdict(kind, global.memoryId(), "contradicts"),
                PAYMENTS));

    Memory untouched = archive.get(global.memoryId());
    assertEquals(MemoryState.ACTIVE, untouched.state());
    assertNull(untouched.supersededBy());
    assertEquals("Four everywhere.", untouched.body());
  }

  /**
   * The index threshold is per tier, because the index is per tier.
   *
   * <p>A store-wide demotion pass would let a project that writes heavily push global memories out
   * of an index it does not even read — one project quietly shrinking what every other project can
   * be answered with.
   */
  @Test
  void writing_over_the_threshold_in_a_project_does_not_demote_global_memories() {
    archive = archiveWithThreshold(1);
    WriteResult global = write("a global fact", "body", Home.global());

    write("first project fact", "body", PAYMENTS);
    WriteResult second = write("second project fact", "body", PAYMENTS);

    assertEquals(MemoryState.ACTIVE, archive.get(global.memoryId()).state());
    assertEquals(List.of(global.memoryId()), indexIds(Home.global()));
    assertEquals(List.of(second.memoryId()), indexIds(PAYMENTS));
  }

  /**
   * A memory is reachable by id from either tier: ids are unique across the archive, and a caller
   * holding one does not have to know which tier wrote it. The tiers partition what a
   * <em>search</em> sees, not what exists.
   */
  @Test
  void a_global_memory_is_readable_by_id_without_naming_its_tier() {
    WriteResult global = write("a global fact", "body", Home.global());

    assertEquals(1, archive.read(List.of(global.memoryId())).get(0).uses());
    assertTrue(archive.get(global.memoryId()).home().isGlobal());
  }
}
