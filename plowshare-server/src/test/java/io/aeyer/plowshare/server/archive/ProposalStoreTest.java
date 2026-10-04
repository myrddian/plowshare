package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The durable queue: what a curator proposes, what a human settles, and what the archive remembers
 * having been asked already.
 *
 * <p><b>Not a port.</b> Excalibur has one pool of memories and nothing to promote between, so it
 * has no queue; every rule below is this design's.
 *
 * <h2>Two of these tests are about two transactions, and say so</h2>
 *
 * <p>The queue's two hard guarantees — one pending proposal per memory and action, and one
 * settlement per proposal — are guarantees <em>across</em> transactions, because a nightly curator
 * pass and a human at a console are two connections that cannot see each other's uncommitted rows.
 * A check written in Java passes in both of them. So {@link
 * #two_concurrent_proposals_for_one_memory_leave_one_row} and {@link
 * #two_concurrent_resolutions_settle_a_proposal_once} drive two open transactions on two
 * connections and interleave them by hand, and each was run against a build that lacked the
 * property it names: the first against a ProposalStore whose duplicate check was a SELECT before
 * the INSERT with the partial index dropped (both transactions inserted; two rows), the second
 * against a load-then-update resolve (both settled; the later one won). Those are the mutants these
 * two tests exist to kill, and neither is killed by any single-connection test in this file.
 *
 * <p>These two also found the bug the single-connection tests could not see. {@code
 * ProposalStore.propose} was written as a plain INSERT catching Spring's {@code
 * DuplicateKeyException} and reading the blocking row to name it — which every other test in this
 * file passes against, and which is broken the moment a caller has a transaction open: the failed
 * statement aborts it, so the read inside the catch comes back {@code SQLState 25P02, "current
 * transaction is aborted"} and the caller is told the database is broken about a duplicate the
 * queue handled correctly. Measured here on 2026-08-29, and the reason the insert is now {@code ON
 * CONFLICT ... DO NOTHING}.
 */
@Testcontainers
class ProposalStoreTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /** The fixture instant the rest of the archive suite uses, kept so the files read alike. */
  private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

  private static final Home PAYMENTS = Home.of("payments");
  private static final Home LEDGER = Home.of("ledger");

  private static final String HUMAN = "enzo";

  /**
   * Who filed the fixture's proposals, and deliberately not {@link #HUMAN}.
   *
   * <p>{@code proposed_by} and {@code resolved_by} are adjacent nullable TEXT columns read by one
   * row mapper, so a mapper that read the wrong one would pass every assertion if the two fixtures
   * shared a name.
   */
  private static final String ASKED_BY = "curator";

  private static JdbcTemplate jdbc;

  private Instant clock;
  private int minted;
  private MemoryStore memories;
  private ProposalStore proposals;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  /**
   * A fresh {@link DriverManagerDataSource} every time it is asked for: the durability test needs
   * one that shares nothing with the store under test, and a shared field would quietly give it the
   * same object.
   */
  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void freshQueue() {
    // proposals first: its rows reference memories, so the other order is
    // refused by the foreign key rather than cascading silently.
    //
    // memory_reasons is named although this class writes none: Postgres
    // refuses a TRUNCATE of a table something references unless the
    // referring table is in the list or CASCADE is given, and it refuses on
    // the constraint rather than on the row count. Named rather than
    // CASCADE'd, matching the sentence above: this file says which tables it
    // empties.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, proposals, memory_reasons, memories");
    clock = NOW;
    minted = 0;
    memories = new MemoryStore(jdbc);
    proposals = storeOver(jdbc);
  }

  /**
   * Sequential zero-padded ids, matching the rest of the suite: {@code pending} sorts by id, and an
   * unpadded counter would sort {@code prp_10} before {@code prp_2} and make every ordering
   * assertion test the wrong thing.
   */
  private ProposalStore storeOver(JdbcTemplate over) {
    return new ProposalStore(over, () -> clock, () -> String.format("prp_%06d", ++minted));
  }

  // --- what a proposal is ---------------------------------------------------

  @Test
  void a_proposal_records_the_memory_the_action_and_the_reason() {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    assertEquals(memory, filed.memoryId());
    assertEquals(ProposalStore.PROMOTE, filed.action());
    assertEquals("it holds everywhere", filed.reason());
    assertEquals(ProposalState.PENDING, filed.state());
    assertEquals(NOW, filed.createdAt());
    assertNull(filed.resolvedAt());
    assertNull(filed.resolvedBy());
    assertNull(filed.resolution());
    // The tier is the memory's own, read back through the row rather than
    // supplied by the caller: see the store's javadoc on why there is no
    // project column to get wrong.
    assertEquals(PAYMENTS, filed.home());
  }

  /**
   * The tier travels for a global memory too, and {@code null} is the global tier rather than a
   * missing project.
   *
   * <p>Nothing promotes a global memory — {@code Archive.promote} refuses it — but {@code action}
   * is an open string and the queue is the general shape, so a proposal about a global memory has
   * to come back describing the right tier rather than an unspellable one. {@link Home} is what
   * makes that distinction unloseable; a bare {@code String project} could not tell a global memory
   * from a proposal whose project was dropped.
   */
  @Test
  void a_proposal_about_a_global_memory_comes_back_global() {
    String memory = memory("The retry budget is 4", Home.global());

    Proposal filed = proposals.propose(memory, "invalidate", "superseded upstream", ASKED_BY);

    assertEquals(Home.global(), filed.home());
    assertEquals(List.of(filed), proposals.pending(Home.global()));
    assertTrue(proposals.pending(PAYMENTS).isEmpty(), "a global proposal is not a project's work");
  }

  /**
   * A proposal is a row, read back through a store that shares nothing with the one that wrote it.
   *
   * <p><b>What this can and cannot see, stated rather than implied.</b> The second store is built
   * over a second {@link DriverManagerDataSource}, so a proposal held in a field of the writing
   * store — or in any per-instance cache — is not there to be found. What it cannot see is a real
   * process restart, and so it could not catch a {@code static} cache; the raw count below is the
   * second half, and it asks Postgres directly whether the row exists at all.
   */
  @Test
  void a_proposal_survives_a_restart() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    ProposalStore afterRestart = storeOver(new JdbcTemplate(dataSource()));

    assertEquals(
        filed,
        afterRestart.get(filed.id()),
        "the proposal did not come back through a store that shares nothing with the one"
            + " that wrote it");
    assertEquals(List.of(filed), afterRestart.pending(PAYMENTS));
    assertEquals(1, rowsFor(memory), "and the row really is in Postgres");
  }

  @Test
  void a_proposal_about_a_memory_that_was_never_written_is_refused() {
    ArchiveException refused =
        assertThrows(
            ArchiveException.class,
            () -> proposals.propose("mem_invented", ProposalStore.PROMOTE, "it holds", ASKED_BY));

    assertTrue(refused.getMessage().contains("mem_invented"), refused.getMessage());
    assertEquals(0, rowsFor("mem_invented"));
    // NOT an unavailability, and this is the assertion the whole
    // ArchiveUnavailableException split turns on. `translating` sits inside
    // this method's try, so a rule widened to DataIntegrityViolationException
    // would fire before the catch that produces the sentence above, and a
    // caller's typo in an id would reach an agent as "something this tool
    // needs could not be reached" — ending the run instead of letting the
    // model correct itself on its next turn.
    assertFalse(
        ArchiveUnavailableException.class.isInstance(refused),
        "a foreign-key violation is the database answering, not failing");
  }

  /**
   * A duplicate raises nothing at all, and saying so is the point.
   *
   * <p>{@code ArchiveUnavailableException}'s javadoc, {@code JobRuntime.dependencyFailure}'s, and
   * their commit message all named <em>this</em> case as the refusal a widened translation would
   * swallow. They were wrong: {@code propose} inserts with {@code ON CONFLICT (memory_id, action)
   * WHERE state = 'pending' DO NOTHING}, so a second pending row is a returned count of zero and
   * never an exception. The rule those javadocs argue for is right and its example was not — which
   * is the harder kind of wrong claim to catch, because it is nearly true.
   *
   * <p>Pinned here, against a real Postgres, so the corrected version has an instrument behind it
   * rather than a second confident paragraph.
   */
  @Test
  void a_duplicate_pending_proposal_raises_no_database_exception_at_all() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> proposals.propose(memory, ProposalStore.PROMOTE, "still holds", ASKED_BY));

    assertFalse(ArchiveUnavailableException.class.isInstance(refused), refused.getMessage());
    // The count is what says no exception was raised: the row that produced
    // this refusal was never inserted, and DO NOTHING is why. An
    // implementation that let the index throw and caught it would leave the
    // same message behind, so the message alone cannot tell them apart.
    assertEquals(1, rowsFor(memory));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void a_proposal_with_no_reason_is_refused(String reason) {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> proposals.propose(memory, ProposalStore.PROMOTE, reason, ASKED_BY));

    assertTrue(refused.getMessage().contains("reason"), refused.getMessage());
    assertEquals(0, rowsFor(memory), "nothing was filed for a proposal that was refused");
  }

  /**
   * Who asked is on the row, and it is not who answered.
   *
   * <p>{@code proposed_by} and {@code resolved_by} are adjacent nullable TEXT columns read by one
   * {@code RowMapper}, so this settles the proposal under a different name and asserts both: a
   * mapper that read {@code resolved_by} into {@code proposedBy} would answer {@code "enzo"} here,
   * and one that read them in the other order would answer {@code "curator"} for the resolver. A
   * fixture whose two names were the same could not see either.
   */
  @Test
  void a_proposal_records_who_asked_and_it_is_not_who_answered() {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    assertEquals(ASKED_BY, filed.proposedBy());
    assertNull(filed.resolvedBy(), "nobody has answered it yet");

    Proposal settled = proposals.resolve(filed.id(), false, "too niche", HUMAN);

    assertEquals(ASKED_BY, settled.proposedBy(), "settling does not change who asked");
    assertEquals(HUMAN, settled.resolvedBy());
  }

  /**
   * A question with nobody's name on it is refused, even though the column takes NULL.
   *
   * <p>The nullability is for rows that predate {@code V4__proposals_proposed_by.sql} and nothing
   * else; writing a new anonymous row would re-open the gap the column was added to close, and it
   * would be indistinguishable afterwards from one of those older rows.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void a_proposal_with_nobody_asking_is_refused(String by) {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", by));

    assertTrue(refused.getMessage().contains("by"), refused.getMessage());
    assertEquals(0, rowsFor(memory), "nothing was filed for a proposal that was refused");
  }

  /**
   * A row filed before the column existed comes back saying nobody was recorded, rather than
   * failing to come back at all.
   *
   * <p>Written through raw SQL because {@link ProposalStore#propose} cannot produce one any more —
   * which is the point: the only rows that can carry a NULL here are the ones this migration
   * inherited, and they still have to be readable. Nothing is backfilled, so this state is
   * permanent for them; see {@code V4__proposals_proposed_by.sql} for why an unverifiable name is
   * worse than an honest absence.
   */
  @Test
  void a_proposal_filed_before_the_column_existed_names_nobody() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    jdbc.update(
        "INSERT INTO proposals (id, memory_id, action, reason, state, created_at)"
            + " VALUES (?, ?, ?, ?, 'pending', now())",
        "prp_inherited",
        memory,
        ProposalStore.PROMOTE,
        "filed before V4");

    assertNull(proposals.get("prp_inherited").proposedBy());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void a_proposal_with_no_action_is_refused(String action) {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () -> proposals.propose(memory, action, "it holds everywhere", ASKED_BY));

    assertTrue(refused.getMessage().contains("action"), refused.getMessage());
    assertEquals(0, rowsFor(memory));
  }

  /**
   * A missing memory id is a malformed call, not a memory that does not exist.
   *
   * <p>It used to reach the foreign key and come back "no memory with id null", which sends the
   * reader looking for a row when the field was simply absent. A null arrives as a NOT NULL
   * violation and a blank as a foreign-key violation, both on the same Spring type as a real
   * unknown id, so the catch clause could not tell them apart either.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void a_proposal_naming_no_memory_at_all_is_refused(String memoryId) {
    ValidationException refused =
        assertThrows(
            ValidationException.class,
            () ->
                proposals.propose(
                    memoryId, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY));

    assertTrue(refused.getMessage().contains("memoryId"), refused.getMessage());
  }

  // --- one pending proposal per memory and action ----------------------------

  @Test
  void a_pending_proposal_blocks_a_duplicate() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal first =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () ->
                proposals.propose(
                    memory, ProposalStore.PROMOTE, "still holds everywhere", ASKED_BY));

    // The existing proposal's id is in the message, so a nightly pass that
    // wants to look at what is already queued does not need a second query
    // to find out which row stopped it.
    assertTrue(refused.getMessage().contains(first.id()), refused.getMessage());
    assertEquals(1, rowsFor(memory));
  }

  /**
   * The index is on the pair, not on the memory: a second kind of question about the same memory is
   * a different question.
   */
  @Test
  void a_pending_proposal_does_not_block_a_different_action() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    Proposal other = proposals.propose(memory, "invalidate", "the wall moved", ASKED_BY);

    assertEquals(ProposalState.PENDING, other.state());
    assertEquals(2, rowsFor(memory));
  }

  /**
   * The index is on <em>pending</em> rows alone, so a settled proposal can be asked again — and
   * that is deliberate rather than a leak.
   *
   * <p>{@link #ruledOn} is what stops a curator re-asking, and it is advisory: it is a filter the
   * triage applies, not a bar the database enforces. A human who rejected a promotion in March and
   * has since seen three other projects hit the same wall must be able to propose it again in June.
   * A unique index over every state would make a rejection permanent in the one sense the design
   * never claimed — the spec's rule is that a rejection is <em>remembered</em>, not that it is
   * irreversible.
   */
  @Test
  void a_settled_proposal_does_not_block_a_new_one() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal first =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);
    proposals.resolve(first.id(), false, "too niche", HUMAN);

    Proposal again =
        proposals.propose(memory, ProposalStore.PROMOTE, "three projects since", ASKED_BY);

    assertEquals(ProposalState.PENDING, again.state());
    assertEquals(2, rowsFor(memory));
    assertEquals(
        List.of(again), proposals.pending(PAYMENTS), "only the live one is waiting for anybody");
  }

  /**
   * Two curator passes are two transactions, and only one row survives them.
   *
   * <p><b>This is the test the plan's sixth self-review note is about, and it is the only one in
   * this file that can see the difference.</b> Both transactions are open at once and neither can
   * see the other's uncommitted row, so a {@code SELECT ... WHERE state = 'pending'} before the
   * INSERT passes in both of them. Run against exactly that build on 2026-08-29 — the Java
   * pre-check, with {@code proposals_one_pending} dropped — this fails on the first assertion
   * below, because the second call sailed past a check nothing was holding and finished before the
   * first transaction committed. {@link #a_pending_proposal_blocks_a_duplicate} passes against that
   * build unchanged, and so does every other test in this file.
   *
   * <p>The interleaving is not incidental. The second INSERT is issued while the first transaction
   * is still open, which is what makes the index the only thing that can be holding the rule; it
   * blocks there until the first commits, and only then is refused.
   */
  @Test
  void two_concurrent_proposals_for_one_memory_leave_one_row() throws Exception {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    Interleaved outcome =
        interleaved(
            first ->
                storeOver(first).propose(memory, ProposalStore.PROMOTE, "the curator", ASKED_BY),
            second ->
                storeOver(second).propose(memory, ProposalStore.PROMOTE, "the other", ASKED_BY));

    assertTrue(
        outcome.secondBlockedUntilFirstCommitted(),
        "the second proposal finished before the first transaction committed, so nothing"
            + " the database was holding can have decided its outcome");
    assertNull(outcome.firstFailure(), "the first proposal should have been filed");
    assertNotNull(
        outcome.secondFailure(),
        "both transactions filed a pending proposal for one memory: a check that lives in"
            + " Java passes in two transactions that cannot see each other");
    assertEquals(
        ArchiveRefusedException.class,
        outcome.secondFailure().getClass(),
        "the refusal reached the caller as a raw Spring data-access exception rather than"
            + " as the archive's own refusal: "
            + outcome.secondFailure());
    assertEquals(1, rowsFor(memory));
  }

  // --- settling one --------------------------------------------------------

  @Test
  void a_rejection_records_who_settled_it_and_when_and_why() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);
    clock = NOW.plus(Duration.ofDays(3));

    // Padded on purpose: the account is stripped on the way in, like every
    // other prose the archive stores, so that a ragged copy-paste does not
    // become a ragged queue nobody can see the cause of.
    Proposal settled = proposals.resolve(filed.id(), false, "  too niche for global\n", HUMAN);

    assertEquals(ProposalState.REJECTED, settled.state());
    assertEquals(clock, settled.resolvedAt());
    assertEquals(HUMAN, settled.resolvedBy());
    assertEquals("too niche for global", settled.resolution());
    // The curator's reason is not overwritten by the human's: the two are
    // different accounts of the same row, and a queue that kept only the
    // second could not say what was ever proposed.
    assertEquals("it holds everywhere", settled.reason());
    assertEquals(settled, proposals.get(filed.id()), "and it was written, not just returned");
  }

  /**
   * The rule that matters, and the archive's own principle one layer up: a tombstone carrying the
   * reason a fact stopped being true is what stops a future agent rediscovering it. A human
   * deciding a memory is too niche for global is exactly that, and a queue that forgets asks again
   * next week.
   */
  @Test
  void a_rejection_is_remembered_so_the_curator_stops_asking() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    proposals.resolve(filed.id(), false, "too niche for global", HUMAN);

    assertEquals(Set.of(memory), proposals.ruledOn(PAYMENTS));
    assertTrue(proposals.pending(PAYMENTS).isEmpty(), "and it is no longer waiting");
  }

  /**
   * An accepted proposal is ruled on too, and for a reason the rejection case does not have: the
   * memory it named has since been superseded by the global record it became, so re-proposing it
   * would propose a tombstone.
   */
  @Test
  void an_accepted_proposal_is_also_ruled_on() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    proposals.resolve(filed.id(), true, "promoted as mem_000002", HUMAN);

    assertEquals(Set.of(memory), proposals.ruledOn(PAYMENTS));
  }

  /**
   * Pending is not ruled on, or the word would mean nothing: the curator's triage would skip the
   * very memories still waiting for an answer, and the queue would empty itself of anything anybody
   * looked at.
   */
  @Test
  void a_pending_proposal_is_not_yet_ruled_on() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    assertTrue(proposals.ruledOn(PAYMENTS).isEmpty());
  }

  @Test
  void resolving_a_proposal_twice_is_refused() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);
    proposals.resolve(filed.id(), false, "too niche", HUMAN);

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> proposals.resolve(filed.id(), true, "on reflection", HUMAN));

    assertTrue(refused.getMessage().contains(filed.id()), refused.getMessage());
    // The state it is already in, so a caller can tell "somebody accepted
    // this" from "somebody rejected this" without a second query. The first
    // settlement stands: a second call must not re-open it.
    assertTrue(refused.getMessage().contains("rejected"), refused.getMessage());
    Proposal stored = proposals.get(filed.id());
    assertEquals(ProposalState.REJECTED, stored.state());
    assertEquals("too niche", stored.resolution());
  }

  /**
   * Two people at the same console are two transactions, and the proposal is settled once.
   *
   * <p>The load-then-update version of {@code resolve} passes every other test in this file: both
   * callers read a {@code pending} row, both write, and the later write wins silently. Run against
   * that build on 2026-08-29 this fails on {@code secondFailure}, which is null because the second
   * settlement was reported as successful. What makes the real one hold is that the state test and
   * the write are <em>one</em> statement — {@code UPDATE ... WHERE id = ? AND state = 'pending'} —
   * so the second caller waits on the row lock and then matches nothing. Measured on this
   * container: the second UPDATE reports zero rows affected.
   *
   * <p>Accept against reject on purpose, so the losing call is the one that would have done
   * something irreversible. A promotion applied to a proposal somebody had already rejected is the
   * failure this guards.
   */
  @Test
  void two_concurrent_resolutions_settle_a_proposal_once() throws Exception {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    Interleaved outcome =
        interleaved(
            first -> storeOver(first).resolve(filed.id(), false, "too niche", HUMAN),
            second -> storeOver(second).resolve(filed.id(), true, "promote it", "someone"));

    assertTrue(
        outcome.secondBlockedUntilFirstCommitted(),
        "the second UPDATE did not wait on the row the first had locked, so nothing in the"
            + " database was holding the rule");
    assertNull(outcome.firstFailure());
    assertNotNull(
        outcome.secondFailure(),
        "both transactions settled one proposal: a resolve that loads the row and then"
            + " writes it passes in two transactions that cannot see each other");
    Proposal stored = proposals.get(filed.id());
    assertEquals(
        ProposalState.REJECTED,
        stored.state(),
        "the settlement that won was overwritten by the one that should have been refused");
    assertEquals(HUMAN, stored.resolvedBy());
  }

  @Test
  void resolving_an_unknown_proposal_names_the_id() {
    ArchiveException refused =
        assertThrows(
            ArchiveException.class, () -> proposals.resolve("prp_invented", true, "sure", HUMAN));

    String said = refused.getMessage();
    assertTrue(said.contains("prp_invented"), said);
    // The distinguishing sentence, not the id every refusal in this class
    // carries. Its three siblings were strengthened for exactly this and
    // this one was missed: with `contains(id)` alone it passes against the
    // already-settled message, which describes the wrong mistake.
    assertTrue(said.contains("no proposal with id"), said);
    assertFalse(
        said.contains("already"), "an id that was never filed is absent, not settled: " + said);
  }

  @Test
  void reading_an_unknown_proposal_names_the_id() {
    ArchiveException refused =
        assertThrows(ArchiveException.class, () -> proposals.get("prp_invented"));

    assertTrue(refused.getMessage().contains("prp_invented"), refused.getMessage());
    // The exact class. ArchiveRefusedException extends this one, so
    // assertThrows alone cannot tell "no such proposal" from the eight
    // refusals this file also drives -- and it is the pair with those that
    // says the split classified the sites rather than renaming them.
    assertEquals(
        ArchiveException.class,
        refused.getClass(),
        "a proposal id nobody filed is absent, never refused");
  }

  /**
   * A settlement with nobody's name on it is refused, exactly as {@code Archive.promote} refuses
   * one: this is the row that says a human agreed, and a queue that cannot say which human is a
   * queue that records that somebody, once, thought it was fine.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void resolving_without_a_named_resolver_is_refused(String by) {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    ValidationException refused =
        assertThrows(
            ValidationException.class, () -> proposals.resolve(filed.id(), false, "too niche", by));

    assertTrue(refused.getMessage().contains("by"), refused.getMessage());
    assertEquals(
        ProposalState.PENDING,
        proposals.get(filed.id()).state(),
        "and the proposal is untouched, not half-settled");
  }

  /**
   * A settlement with no account of itself is allowed, matching {@code Archive.promote}'s reason
   * and {@code Validation}'s deliberate silence about {@code formedWhere}: less context, not a
   * malformed call.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void a_settlement_with_no_account_of_itself_is_still_recorded(String resolution) {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    Proposal settled = proposals.resolve(filed.id(), false, resolution, HUMAN);

    assertEquals(ProposalState.REJECTED, settled.state());
    assertNull(settled.resolution(), "blank is stored as absent, not as an empty account");
    assertEquals(HUMAN, settled.resolvedBy());
  }

  // --- one project at a time -------------------------------------------------

  /**
   * The queue is read per tier, like everything else in this archive: a human settling one
   * project's proposals must not be shown another project's, and a curator's triage must not be
   * filtered by what some other project was asked about.
   */
  @Test
  void pending_and_ruled_on_are_both_per_project() {
    String mine = memory("Payments uses mTLS", PAYMENTS);
    String theirs = memory("Ledger posts in batches", LEDGER);
    Proposal ours = proposals.propose(mine, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);
    Proposal other = proposals.propose(theirs, ProposalStore.PROMOTE, "so does this", ASKED_BY);
    proposals.resolve(other.id(), false, "too niche", HUMAN);

    assertEquals(List.of(ours), proposals.pending(PAYMENTS));
    assertTrue(proposals.pending(LEDGER).isEmpty(), "the ledger's proposal was settled");
    assertEquals(Set.of(theirs), proposals.ruledOn(LEDGER));
    assertTrue(
        proposals.ruledOn(PAYMENTS).isEmpty(),
        "one project's rejection must not silence another project's triage");
  }

  /**
   * Oldest first, and by id because ids sort by minting time: a human working a queue top-down
   * should meet the longest-waiting proposal first, and an unordered list would shuffle under them
   * between reads.
   *
   * <p><b>The rows are written in the reverse of their id order on purpose.</b> Filed in id order,
   * this test passes against a query with no {@code ORDER BY} at all — a sequential scan hands back
   * what was inserted, in the order it was inserted, and the fixture would be supplying the very
   * property under test. Minting the ids backwards is what makes physical order and id order
   * disagree, so only the sort can produce the expected list.
   */
  @Test
  void pending_comes_back_oldest_first() {
    String one = memory("Payments uses mTLS", PAYMENTS);
    String two = memory("Payments logs in JSON", PAYMENTS);
    String three = memory("Payments retries twice", PAYMENTS);
    ProposalStore backwards =
        new ProposalStore(
            jdbc, () -> clock, new ArrayDeque<>(List.of("prp_3", "prp_2", "prp_1"))::poll);
    backwards.propose(one, ProposalStore.PROMOTE, "written first, sorts last", ASKED_BY);
    backwards.propose(two, ProposalStore.PROMOTE, "b", ASKED_BY);
    backwards.propose(three, ProposalStore.PROMOTE, "written last, sorts first", ASKED_BY);

    assertEquals(
        List.of("prp_1", "prp_2", "prp_3"),
        backwards.pending(PAYMENTS).stream().map(Proposal::id).toList());
  }

  /**
   * A memory ruled on more than once is in the set once, which is what the {@code DISTINCT} is for.
   *
   * <p>Reachable because a rejection is remembered but not binding: the same memory can be proposed
   * again and settled again, so its id really does appear on several rows. A curator's triage
   * subtracts this set from its candidates, so a duplicate would not change the filtering — but it
   * would make the count of "already ruled on" wrong for anything that reports it, and it is one
   * word of SQL to be right.
   *
   * <p>This replaces a test that asserted an iteration order. The {@code LinkedHashSet} it was
   * written for promised one over a bare {@code DISTINCT}; adding {@code ORDER BY} did not make the
   * promise testable either, because this planner returns the rows sorted anyway and the mutant
   * that removed the sort survived. The promise is gone instead.
   *
   * <p>Note what this does <em>not</em> pin, because the method's comment originally claimed it
   * did: the {@code DISTINCT} in the SQL. {@code Set.copyOf} collapses duplicates by itself, so
   * removing {@code DISTINCT} leaves this green — measured. The uniqueness asserted here is the
   * collection's; the SQL keyword only keeps rows off the wire.
   */
  @Test
  void a_memory_ruled_on_more_than_once_is_in_the_set_once() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal first = proposals.propose(memory, ProposalStore.PROMOTE, "it holds", ASKED_BY);
    proposals.resolve(first.id(), false, "too niche", HUMAN);
    Proposal again =
        proposals.propose(memory, ProposalStore.PROMOTE, "three projects since", ASKED_BY);
    proposals.resolve(again.id(), false, "still too niche", HUMAN);

    assertEquals(2, rowsFor(memory), "sanity: the memory really is on two settled rows");
    Set<String> ruled = proposals.ruledOn(PAYMENTS);
    assertEquals(Set.of(memory), ruled);
    // And a caller cannot edit what it was handed, matching what promote
    // does with its demoted list. Nothing mutates this set after it is
    // returned, so the value is that it is the same kind of thing every
    // time rather than a live view somebody could corrupt.
    assertThrows(UnsupportedOperationException.class, () -> ruled.add("mem_invented"));
  }

  // --- the two halves of a claim ---------------------------------------------

  /*
   * `note` and `release` are package-private and exist for PromotionQueue
   * alone, which is why they are exercised directly here: their guards are
   * unreachable through anything else. PromotionQueueTest covers what they are
   * for; these two cover what they refuse.
   */

  /**
   * An account cannot be written onto a proposal nobody has answered.
   *
   * <p>{@code resolution} on a waiting row is an answer to an open question, and a human reading
   * the queue would find one row already carrying a verdict nobody gave. {@code
   * proposals_settlement_matches_state} would refuse it too, but as a constraint naming a column
   * rather than the mistake — and the row it names would be the wrong place to start looking.
   */
  @Test
  void an_account_cannot_be_written_onto_a_proposal_still_waiting() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> proposals.note(filed.id(), "promoted as mem_000009"));

    // The distinguishing sentence, not a substring every refusal in this
    // class shares. Asserting only `contains(id)` is what let this guard
    // answer a waiting proposal with "was already pending by null; a settled
    // proposal is not re-opened by settling it again" — a settlement that
    // never happened, and a literal null where a name would go.
    String said = refused.getMessage();
    assertTrue(said.contains(filed.id()), said);
    assertTrue(said.contains("still waiting"), said);
    assertTrue(said.contains("no settlement on it to account for"), said);
    assertFalse(
        said.contains("already"),
        "the refusal describes a settlement that did not happen: " + said);
    assertFalse(
        said.contains("null"), "the refusal has a resolver's name missing from it: " + said);

    assertNull(proposals.get(filed.id()).resolution());
    assertEquals(ProposalState.PENDING, proposals.get(filed.id()).state());
  }

  /**
   * The other half of a zero row count, and the reason the read comes first: an unknown id is a
   * different mistake from a waiting proposal, and {@link ProposalStore#get} already establishes
   * how this class says it.
   */
  @Test
  void writing_an_account_against_an_unknown_proposal_names_the_id() {
    ArchiveException refused =
        assertThrows(
            ArchiveException.class, () -> proposals.note("prp_invented", "promoted as mem_000009"));

    assertTrue(refused.getMessage().contains("prp_invented"), refused.getMessage());
    assertTrue(refused.getMessage().contains("no proposal with id"), refused.getMessage());
  }

  /**
   * There is no claim on a waiting proposal, so there is none to give back.
   *
   * <p>{@code release} is the one operation that undoes a settlement, and the design's account of
   * the queue rests on settlements being final to everybody else. Firing on a pending row would
   * make it a way to clear a settlement nobody took — which, called with the wrong id, is a silent
   * un-answering of somebody's decision.
   */
  @Test
  void a_proposal_nobody_has_claimed_cannot_be_released() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);

    ArchiveRefusedException refused =
        assertThrows(ArchiveRefusedException.class, () -> proposals.release(filed.id()));

    String said = refused.getMessage();
    assertTrue(said.contains(filed.id()), said);
    assertTrue(said.contains("still waiting"), said);
    assertTrue(said.contains("no claim to give back"), said);
    assertFalse(
        said.contains("is not settled"),
        "the refusal describes the row's storage state rather than the caller's"
            + " mistake: "
            + said);
    assertEquals(ProposalState.PENDING, proposals.get(filed.id()).state());
  }

  /**
   * An unknown id is not "unsettled", it is absent — the convention {@link ProposalStore#get} sets
   * for this whole class.
   */
  @Test
  void releasing_an_unknown_proposal_names_the_id() {
    ArchiveException refused =
        assertThrows(ArchiveException.class, () -> proposals.release("prp_invented"));

    assertTrue(refused.getMessage().contains("prp_invented"), refused.getMessage());
    assertTrue(refused.getMessage().contains("no proposal with id"), refused.getMessage());
  }

  /**
   * A claim cannot always be given back, and this needs no concurrency to see.
   *
   * <p>Claiming a proposal is what frees its {@code (memory_id, action)} waiting place; releasing
   * it asks for that place back. If anything has filed a proposal in the meantime, {@code
   * proposals_one_pending} refuses the release, and the row stays settled for work that did not
   * happen.
   *
   * <p>This is the failure {@code PromotionQueue.approve}'s compensating release can hit, and it is
   * why that class's account of its window names more than a process dying. Nothing is read after
   * the failed statement — the memory and the action in the message come from the read taken before
   * it, because a failed statement aborts the transaction.
   */
  @Test
  void releasing_a_claim_whose_waiting_place_was_taken_is_refused() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal claimed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);
    proposals.resolve(claimed.id(), true, null, HUMAN);
    Proposal occupant = proposals.propose(memory, ProposalStore.PROMOTE, "and again", ASKED_BY);

    ArchiveRefusedException refused =
        assertThrows(ArchiveRefusedException.class, () -> proposals.release(claimed.id()));

    String said = refused.getMessage();
    assertTrue(said.contains(claimed.id()), said);
    assertTrue(said.contains(memory), said);
    assertTrue(said.contains("waiting place"), said);
    assertEquals(
        ProposalState.ACCEPTED,
        proposals.get(claimed.id()).state(),
        "the refusal has to leave the row where it found it, and say so");
    assertEquals(ProposalState.PENDING, proposals.get(occupant.id()).state());
  }

  /**
   * And a released claim is genuinely back in the queue, carrying none of the settlement it briefly
   * had.
   */
  @Test
  void a_released_claim_is_waiting_again_and_carries_no_answer() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);
    proposals.resolve(filed.id(), true, "yes", HUMAN);

    Proposal released = proposals.release(filed.id());

    assertEquals(ProposalState.PENDING, released.state());
    assertNull(released.resolvedAt());
    assertNull(released.resolvedBy());
    assertNull(released.resolution());
    assertEquals(List.of(released), proposals.pending(PAYMENTS));
    assertTrue(
        proposals.ruledOn(PAYMENTS).isEmpty(),
        "a released claim is not a ruling, so the curator may ask again");
    // And who ASKED is not part of the settlement being given back. The
    // UPDATE nulls three columns beside this one, so a fourth added to that
    // list would leave the question waiting with nobody's name on it and
    // every other assertion here still green.
    assertEquals(
        ASKED_BY,
        released.proposedBy(),
        "giving back a claim undoes the answer, never the question");
  }

  // --- which rulings a settler answered by itself ----------------------------

  /**
   * The rows one settler answered by itself, and nobody else's.
   *
   * <p>The query half of the escape hatch 3a left possible; putting the rows back is {@code
   * PromotionQueue.reconsider}'s, which is where the argument about who may ask lives. This class
   * takes the predicate as data and never learns what a curator is.
   *
   * <p><b>The fixture holds three rows the query must not return</b> — a person's rejection, the
   * same settler's rejection under a different account, and that settler's acceptance — because a
   * query with any one of its three predicates dropped still answers correctly over a table holding
   * only the row it is looking for.
   */
  @Test
  void the_rulings_a_settler_answered_by_itself_are_the_ones_it_signed_that_way() {
    Proposal mine = rejectedBy(memory("Payments uses mTLS", PAYMENTS), "curator", "kept");
    rejectedBy(memory("The retry budget is 4", PAYMENTS), HUMAN, "kept");
    rejectedBy(memory("Certs rotate weekly", PAYMENTS), "curator", "too niche");
    Proposal accepted =
        proposals.propose(
            memory("Timeouts are 3s", PAYMENTS), ProposalStore.PROMOTE, "it holds", ASKED_BY);
    proposals.resolve(accepted.id(), true, "kept", "curator");

    assertEquals(List.of(mine.id()), proposals.rulingsSettledUnder(PAYMENTS, "curator", "kept"));
  }

  /**
   * One tier at a time, like every other read here: an operator working one project's queue must
   * not be handed another project's rulings.
   */
  @Test
  void the_rulings_of_one_tier_are_not_anothers() {
    Proposal ours = rejectedBy(memory("Payments uses mTLS", PAYMENTS), "curator", "kept");
    rejectedBy(memory("Ledger closes monthly", LEDGER), "curator", "kept");

    assertEquals(List.of(ours.id()), proposals.rulingsSettledUnder(PAYMENTS, "curator", "kept"));
  }

  /**
   * A ruling its settler gave no account for is matched by nothing, and this is what says so.
   *
   * <p>Written because a javadoc cited the opposite: it said a blank {@code resolution} "would
   * match every settled row whose settler gave no account". <b>It matches none.</b> {@link
   * #resolve} stores a blank account through {@code blankToNull}, so "gave no account" is NULL on
   * the row, and {@code p.resolution = ?} matches no NULL whatever is bound to it. The fixture
   * asserts the row really is NULL first, so this is measuring the query rather than a row that
   * happened not to exist.
   *
   * <p>The blank guard stays, and its reason is now the honest one: a blank predicate is a caller's
   * mistake, and answering "0 re-opened" for it would read to an operator as "there were none".
   */
  @Test
  void a_ruling_settled_under_no_account_at_all_is_matched_by_nothing() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal unsigned = rejectedBy(memory, "curator", "   ");
    assertNull(
        unsigned.resolution(),
        "a blank account is stored absent, not as an empty string: blankToNull is what"
            + " makes 'gave no account' a NULL rather than a matchable value");

    // Asked of the database directly, because the blank guard on
    // rulingsSettledUnder makes this unreachable through the method -- which
    // is the point. The retracted javadoc claimed a blank predicate "would
    // match every settled row whose settler gave no account"; the row is
    // here, and the predicate does not see it.
    assertEquals(
        0,
        rowsMatching("resolution = ?", ""),
        "an equality predicate matches no NULL, whatever is bound to it");
    assertEquals(
        1,
        rowsMatching("resolution IS NULL", null),
        "and the row it was supposed to match really is there, so the zero above is the"
            + " predicate missing it rather than an empty table");
  }

  /**
   * Rows whose {@code resolution} satisfies a predicate this class writes by hand, so a claim about
   * SQL semantics is measured rather than reasoned.
   */
  private int rowsMatching(String predicate, String bound) {
    Integer rows =
        bound == null
            ? jdbc.queryForObject(
                "SELECT count(*) FROM proposals WHERE " + predicate, Integer.class)
            : jdbc.queryForObject(
                "SELECT count(*) FROM proposals WHERE " + predicate, Integer.class, bound);
    return rows == null ? 0 : rows;
  }

  /**
   * A blank predicate is refused by name rather than answered with nothing.
   *
   * <p>Both halves matter and neither is "it would match everything": a blank {@code resolution}
   * matches no row at all (above), and a blank {@code settledBy} with a real account is an operator
   * asking a question this operation does not answer. Refused at the front, before a connection is
   * taken, so the answer is a refusal rather than an empty result an operator would read as "there
   * were none".
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void asking_for_rulings_under_a_blank_predicate_is_refused(String blank) {
    Proposal kept = rejectedBy(memory("Payments uses mTLS", PAYMENTS), "curator", "kept");

    assertThrows(
        ValidationException.class, () -> proposals.rulingsSettledUnder(PAYMENTS, blank, "kept"));
    assertThrows(
        ValidationException.class, () -> proposals.rulingsSettledUnder(PAYMENTS, "curator", blank));

    assertEquals(
        ProposalState.REJECTED,
        proposals.get(kept.id()).state(),
        "and nothing moved on the way to being refused");
  }

  /**
   * The state test on {@code note}'s write, and the only thing that can reach it: a release landing
   * between the read and the write.
   *
   * <p>{@code note} reads the proposal first so its refusal can name the mistake, and that read
   * makes the {@code AND state <> 'pending'} on the UPDATE look redundant — a mutant that drops it
   * survives every other test in this class, which is measured, not assumed. It is not redundant:
   * the two statements are not atomic, and {@link PromotionQueue} is a caller that can release a
   * claim between them. Without the test on the write, an account lands on a waiting proposal — an
   * answer to a question nobody has answered.
   *
   * <p>The store below releases on its way out of the read, once, which puts a release exactly in
   * that gap with no concurrency and no timing at all.
   */
  @Test
  void an_account_written_while_the_claim_is_given_back_records_nothing() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);
    proposals.resolve(filed.id(), true, null, HUMAN);
    ProposalStore releasingMidWrite = releasingOnItsWayOutOfTheRead();

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> releasingMidWrite.note(filed.id(), "promoted as mem_000009"));

    assertTrue(refused.getMessage().contains("went back to waiting"), refused.getMessage());
    Proposal after = proposals.get(filed.id());
    assertEquals(ProposalState.PENDING, after.state());
    assertNull(
        after.resolution(), "an account was written onto a proposal that had gone back to waiting");
  }

  /**
   * The same gap on the other method: two releases, and the second is told somebody got there first
   * rather than silently succeeding.
   */
  @Test
  void a_claim_given_back_twice_says_somebody_else_got_there_first() {
    String memory = memory("Payments uses mTLS", PAYMENTS);
    Proposal filed =
        proposals.propose(memory, ProposalStore.PROMOTE, "it holds everywhere", ASKED_BY);
    proposals.resolve(filed.id(), true, null, HUMAN);
    ProposalStore releasingMidWrite = releasingOnItsWayOutOfTheRead();

    ArchiveRefusedException refused =
        assertThrows(ArchiveRefusedException.class, () -> releasingMidWrite.release(filed.id()));

    assertTrue(refused.getMessage().contains("released by somebody else"), refused.getMessage());
    assertEquals(ProposalState.PENDING, proposals.get(filed.id()).state());
  }

  // --- what the database itself refuses --------------------------------------

  /**
   * A fourth state is worse than a malformed row: it is in neither {@link ProposalStore#pending}
   * nor {@link ProposalStore#ruledOn}, so the question is invisible to the human who has to answer
   * it <em>and</em> to the curator that would otherwise ask again. The CHECK refuses it at the
   * write, exactly as V1 does for memories.
   */
  @Test
  void a_state_outside_the_three_is_refused_by_the_database() {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    // Settled-looking on purpose, and the row names its settler: a row that
    // was merely *also* malformed would satisfy this assertion by tripping
    // proposals_settlement_matches_state instead, and the test would pass
    // against a schema with no state check at all. Postgres reports one
    // constraint, so the row is built to violate exactly one.
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertRaw("prp_bad", memory, "abandoned", NOW, HUMAN));

    assertTrue(refused.getMessage().contains("proposals_state_known"), refused.getMessage());
  }

  /**
   * A settled proposal with nobody's name on it records that somebody, once, thought it was fine.
   * {@code resolve} refuses that already; the constraint is what stops a stray migration or a psql
   * session writing one — and a half-applied settlement, the state moved and the columns not, is
   * exactly the shape a bug in this class would leave behind.
   */
  @Test
  void a_settlement_with_no_name_on_it_is_refused_by_the_database() {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    // A known state and a settlement instant, so proposals_state_known
    // cannot be what refuses this: the only thing wrong with the row is the
    // missing name.
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertRaw("prp_bad", memory, "rejected", NOW, null));

    assertTrue(
        refused.getMessage().contains("proposals_settlement_matches_state"), refused.getMessage());
  }

  /**
   * A proposer whose name is the empty string is refused by the database.
   *
   * <p>{@code propose} refuses it first; this is what stops a migration or a psql session writing
   * one. Absent and empty are different facts here — NULL means the row predates the column, {@code
   * ''} would mean somebody asked and their name is nothing — and a reader has no way to tell the
   * second from a bug. The same shape as {@code proposals_resolver_named} one constraint up.
   */
  @Test
  void a_proposer_named_by_the_empty_string_is_refused_by_the_database() {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    // Pending and unsettled, so proposals_settlement_matches_state and
    // proposals_state_known cannot be what refuses it: the only thing wrong
    // with this row is the blank proposer.
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO proposals"
                        + " (id, memory_id, action, reason, state, created_at,"
                        + " proposed_by)"
                        + " VALUES (?, ?, 'promote', 'why', 'pending', ?, '')",
                    "prp_bad",
                    memory,
                    utc(NOW)));

    assertTrue(refused.getMessage().contains("proposals_proposer_named"), refused.getMessage());
  }

  /**
   * And the other direction: a row still waiting cannot carry an answer, or {@code pending} would
   * hand a human a question somebody has answered.
   */
  @Test
  void a_pending_row_carrying_a_settlement_is_refused_by_the_database() {
    String memory = memory("Payments uses mTLS", PAYMENTS);

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertRaw("prp_bad", memory, "pending", NOW, HUMAN));

    assertTrue(
        refused.getMessage().contains("proposals_settlement_matches_state"), refused.getMessage());
  }

  // --- the state's own spelling ----------------------------------------------

  /**
   * The three wire names are a contract with {@code proposals_state_known} and with every row
   * already written, which is why they are spelled out rather than derived from {@link
   * Enum#name()}. This is what fails if somebody derives them: {@code name().toLowerCase()} would
   * keep this test green today and orphan the rows the day a constant is renamed, so the assertion
   * is against the SQL literals and not against the enum.
   */
  @Test
  void the_three_states_spell_themselves_the_way_the_schema_does() {
    assertEquals(
        List.of("pending", "accepted", "rejected"),
        List.of(
            ProposalState.PENDING.wireName(),
            ProposalState.ACCEPTED.wireName(),
            ProposalState.REJECTED.wireName()));
    for (ProposalState state : ProposalState.values()) {
      assertEquals(state, ProposalState.fromWireName(state.wireName()));
    }
  }

  /**
   * A row carrying a spelling nothing knows is refused rather than mapped to a default: a proposal
   * quietly read back as {@code PENDING} would be put in front of a human who has already answered
   * it.
   */
  @Test
  void a_state_spelling_nothing_knows_is_refused() {
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> ProposalState.fromWireName("abandoned"));

    assertTrue(refused.getMessage().contains("abandoned"), refused.getMessage());
  }

  /**
   * The nulls a proposal has no meaning without, refused where the caller is still standing — not
   * as a NOT NULL violation out of a statement many layers away. The three settlement fields are
   * legitimately absent and are not in this list; {@link
   * #a_proposal_records_the_memory_the_action_and_the_reason} is what pins that they may be null.
   */
  @Test
  void a_proposal_missing_something_it_cannot_mean_anything_without_is_refused() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Proposal(
                "prp_1",
                "mem_1",
                PAYMENTS,
                ProposalStore.PROMOTE,
                "why",
                null,
                NOW,
                ASKED_BY,
                null,
                null,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new Proposal(
                "prp_1",
                "mem_1",
                null,
                ProposalStore.PROMOTE,
                "why",
                ProposalState.PENDING,
                NOW,
                ASKED_BY,
                null,
                null,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new Proposal(
                "prp_1",
                null,
                PAYMENTS,
                ProposalStore.PROMOTE,
                "why",
                ProposalState.PENDING,
                NOW,
                ASKED_BY,
                null,
                null,
                null));
  }

  // --- fixtures --------------------------------------------------------------

  /**
   * A memory to propose about, written straight through the store: this class is about the queue,
   * and going through {@code Archive} would drag an embedding client into every fixture for
   * nothing.
   */
  private String memory(String summary, Home home) {
    String id = String.format("mem_%06d", ++minted);
    memories.save(
        new Memory(
            id,
            summary,
            "payments auth work",
            new Provenance(clock, "claude-code", "proj/payments"),
            MemoryState.ACTIVE,
            false,
            0,
            null,
            "The body.",
            null,
            null,
            null,
            home));
    return id;
  }

  /**
   * A store that gives a claim back on its way out of its own read, once.
   *
   * <p>The instrument for the gap between {@code note}'s and {@code release}'s read and their write
   * — the only thing the state test on those writes guards, and unreachable any other way: it needs
   * a release to land inside one method call, which no amount of thread juggling here can place.
   * One shot, so the release that the interleaving is about does not repeat on the reads that
   * follow it.
   */
  private ProposalStore releasingOnItsWayOutOfTheRead() {
    return new ProposalStore(jdbc, () -> clock, () -> String.format("prp_%06d", ++minted)) {
      private boolean armed = true;

      @Override
      public Proposal get(String id) {
        Proposal read = super.get(id);
        if (armed && read.state() != ProposalState.PENDING) {
          armed = false;
          super.release(id);
        }
        return read;
      }
    };
  }

  /**
   * A row written past every Java guard in this class, so that what the database itself refuses can
   * be asked directly.
   */
  /**
   * A proposal filed and then settled as a rejection by a named settler with a named account — the
   * shape {@code reconsider}'s predicate is about.
   */
  private Proposal rejectedBy(String memoryId, String settledBy, String resolution) {
    Proposal filed = proposals.propose(memoryId, ProposalStore.PROMOTE, "it holds", ASKED_BY);
    proposals.resolve(filed.id(), false, resolution, settledBy);
    return proposals.get(filed.id());
  }

  private void insertRaw(
      String id, String memoryId, String state, Instant resolvedAt, String resolvedBy) {
    jdbc.update(
        "INSERT INTO proposals"
            + " (id, memory_id, action, reason, state, created_at, resolved_at,"
            + " resolved_by)"
            + " VALUES (?, ?, 'promote', 'why', ?, ?, ?, ?)",
        id,
        memoryId,
        state,
        utc(NOW),
        utc(resolvedAt),
        resolvedBy);
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private int rowsFor(String memoryId) {
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM proposals WHERE memory_id = ?", Integer.class, memoryId);
    return rows == null ? 0 : rows;
  }

  /** What two interleaved transactions did. */
  private record Interleaved(
      RuntimeException firstFailure,
      RuntimeException secondFailure,
      boolean secondBlockedUntilFirstCommitted) {}

  /**
   * Run two pieces of store work in two transactions that overlap, and report what each did.
   *
   * <p>The interleaving is the whole instrument, so it is worth stating exactly: the second
   * transaction issues its statement <b>while the first is still open</b>, and the first commits
   * only after the second has been given time to block. If the second returns before that commit,
   * nothing in the database was holding the rule and {@code secondBlockedUntilFirstCommitted} is
   * false — which is the reading that distinguishes "the database refused it" from "a Java check
   * happened to run second".
   *
   * <p>{@link SingleConnectionDataSource} with {@code suppressClose}, so the {@link JdbcTemplate}
   * each store is built over cannot end the transaction by returning its connection between
   * statements.
   */
  private Interleaved interleaved(ThrowingWork firstWork, ThrowingWork secondWork)
      throws Exception {
    AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
    AtomicReference<RuntimeException> secondFailure = new AtomicReference<>();
    CountDownLatch firstDidItsWork = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch secondFinished = new CountDownLatch(1);

    Thread second =
        new Thread(
            () -> {
              try (Connection connection = dataSource().getConnection()) {
                connection.setAutoCommit(false);
                firstDidItsWork.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                secondStarted.countDown();
                try {
                  secondWork.run(new JdbcTemplate(suppressingClose(connection)));
                  connection.commit();
                } catch (RuntimeException failed) {
                  secondFailure.set(failed);
                  connection.rollback();
                }
              } catch (SQLException | InterruptedException broken) {
                secondFailure.set(new IllegalStateException(broken));
              } finally {
                secondFinished.countDown();
              }
            },
            "second-transaction");
    second.start();

    boolean stillBlocked;
    try (Connection connection = dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        firstWork.run(new JdbcTemplate(suppressingClose(connection)));
      } catch (RuntimeException failed) {
        firstFailure.set(failed);
      }
      firstDidItsWork.countDown();
      secondStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      // Long enough for the second statement to reach the database and
      // block on it. It is a wait for an absence, so there is nothing to
      // poll for; a second transaction that was going to finish on its own
      // has finished several times over by now.
      Thread.sleep(1_500);
      stillBlocked = secondFinished.getCount() == 1;
      connection.commit();
    }
    assertTrue(
        secondFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "the second transaction never finished");
    second.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    assertFalse(second.isAlive(), "the second transaction's thread outlived its work");
    return new Interleaved(firstFailure.get(), secondFailure.get(), stillBlocked);
  }

  private static final long TIMEOUT_SECONDS = 30;

  private static SingleConnectionDataSource suppressingClose(Connection connection) {
    return new SingleConnectionDataSource(connection, true);
  }

  @FunctionalInterface
  private interface ThrowingWork {
    void run(JdbcTemplate jdbc);
  }
}
