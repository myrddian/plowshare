package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A conversation owns a budget, and every turn in it spends the same one.
 *
 * <h2>The two things worth failing on</h2>
 *
 * <ul>
 *   <li><b>the budget that comes back is the row's and not a fresh one.</b> A store that returned
 *       {@code Budget.of(total)} would look right in every assertion about totals and would
 *       silently hand a conversation its whole allowance back on every read. {@code
 *       what_comes_back_is_the_budget_the_row_records_and_not_a_fresh_allowance} is the one that
 *       fails on it, and it asserts on {@code remaining()} rather than on {@code spent()}, because
 *       {@code spent()} is the field the row obviously carries and {@code remaining()} is what a
 *       caller decides with;
 *   <li><b>the listing filters.</b> Every fixture for {@link ConversationStore#inHome} holds rows
 *       in the other tiers as well as the one being asked for, so a query that lost its {@code
 *       WHERE} fails instead of passing over a table with one row in it.
 * </ul>
 *
 * <h2>The clock and the ids are chosen, not observed</h2>
 *
 * <p>Both are injected, for the reasons {@code ProposalStoreTest} and {@code ArchiveTest} give: a
 * test that asserted on instants it did not choose could only assert that they exist, and an
 * ordering test over random hex is an ordering test over nothing. The production spellings are
 * still driven — {@code the_default_id_is_the_house_scheme_under_its_own_prefix} builds a store the
 * way production does.
 *
 * <h2>The schema's rules are driven through SQL this store cannot emit</h2>
 *
 * <p>Six named {@code CHECK}s, each asserted through a direct {@code INSERT} or {@code UPDATE}, and
 * each asserted <em>by name</em>: the point of naming them in {@code V6} is that Postgres says
 * which rule a bad row broke, and a test that only asserted "some constraint fired" would pass with
 * all six collapsed into one predicate. Java cannot produce any of these rows — which is exactly
 * V2's and V5's argument for the constraints existing, since a psql session, a later migration or a
 * second writer does not go through Java.
 */
@Testcontainers
class ConversationStoreTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private static final Home PAYMENTS = Home.of("payments");
  private static final Home LEDGER = Home.of("ledger");

  /**
   * Where the store's clock starts. A fixed instant so that every assertion about an instant is an
   * assertion about a value this file chose.
   */
  private static final Instant OPENED_AT = Instant.parse("2026-08-31T09:00:00Z");

  private final AtomicReference<Instant> clock = new AtomicReference<>(OPENED_AT);
  private final AtomicInteger minted = new AtomicInteger();

  private ConversationStore store;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  /**
   * A fresh {@link DriverManagerDataSource} every time it is asked for, as {@code ProjectStoreTest}
   * and {@code ProposalStoreTest} both do: the survives-a-restart test needs one that shares
   * nothing with the store under test, and a shared field would quietly give it the same object.
   */
  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void freshConversations() {
    // Both tables, because V7 gave `turns` a foreign key to this one.
    // Postgres refuses to truncate a table another one references unless
    // that one is truncated with it, whether or not the referencing table
    // holds a row — so naming `turns` here is not about clearing turns this
    // file writes (it writes none), it is what makes the statement legal at
    // all. `compactions` is here for the same reason one hop further out:
    // V8 gave it a composite key into `turns`. This comment used to say
    // `conversations` had no foreign key in either direction, which V7 made
    // false.
    //
    // Named rather than CASCADE: CASCADE would silently reach whatever
    // references this table next, and a fixture that empties a table nobody
    // in this file knows about is a fixture that hides its own blast radius.
    // `board_topics`, `board_messages` and `board_seats` are here for the
    // same reason as `turns` and `compactions`: V74 gave each of them a
    // foreign key to this one — `origin_conversation`, `conversation` and
    // `conversation` respectively. `firings` is here one hop further out
    // again, the same shape as `compactions` into `turns`: V74 gave it a
    // foreign key into `board_topics`. `user_inbox` is a further hop still,
    // through its pre-existing V40 foreign key into `firings`, which this
    // file had never had to name before nothing chained through it.
    jdbc.execute(
        "TRUNCATE TABLE board_message_route_bindings, outgoing_work, message_external_tasks, message_external_contexts, board_message_routes, board_message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
    clock.set(OPENED_AT);
    minted.set(0);
    store =
        new ConversationStore(
            jdbc, clock::get, () -> String.format("cnv_%04d", minted.incrementAndGet()));
  }

  // --- what a conversation is -----------------------------------------------

  /**
   * Read back through a second store over a second connection, so the answer came from Postgres and
   * not from anything the first store kept.
   */
  @Test
  void a_conversation_names_a_home_and_a_budget_and_survives_a_restart() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(12));

    ConversationStore afterRestart = new ConversationStore(new JdbcTemplate(dataSource()));
    ConversationRecord read = afterRestart.find(opened.id()).orElseThrow();

    assertEquals(opened.id(), read.id());
    assertEquals(PAYMENTS, read.home());
    assertEquals(OPENED_AT, read.createdAt());
    assertEquals(12, read.budget().limit());
  }

  @Test
  void a_conversation_nobody_has_opened_is_an_ordinary_empty_answer() {
    assertTrue(
        store.find("cnv_9999").isEmpty(),
        "asking whether this server holds a conversation is a question, not a mistake");
  }

  /**
   * Global is a tier and not a missing project.
   *
   * <p>The plan asked for {@code project NOT NULL} — "a conversation always has a project" — and V6
   * declines it. This is the measurement behind that: {@code requests.RequestedHome.in} returns
   * {@link Home#global()} for every submission that names no project, so a NOT NULL would make the
   * default way of starting a run the one way a conversation cannot be opened.
   */
  @Test
  void the_global_tier_is_a_conversation_and_not_a_missing_project() {
    ConversationRecord opened = store.open(Home.global(), Budget.of(3));

    ConversationRecord read = store.find(opened.id()).orElseThrow();
    assertEquals(Home.global(), read.home());
    assertTrue(
        read.home().isGlobal(),
        "and it comes back as the global tier rather than as a project named null");
    assertNull(
        jdbc.queryForObject(
            "SELECT project_id FROM conversations WHERE id = ?", Long.class, opened.id()),
        "which is a NULL project in the row, the way V1 spells the global tier");
  }

  @Test
  void a_conversation_that_has_had_no_turn_has_no_last_turn_instant() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(5));

    assertNull(
        opened.lastTurnAt(),
        "nobody has spoken into it, which is not the same fact as a turn at the instant"
            + " it was opened");
    assertNull(store.find(opened.id()).orElseThrow().lastTurnAt());
  }

  // --- the budget -----------------------------------------------------------

  /**
   * The trap this store is most likely to fall into, and the assertion is on {@code remaining()}.
   *
   * <p>A row mapper that said {@code Budget.of(budget_total)} passes every assertion about the
   * total and hands a conversation its whole allowance back on every read — a bug whose symptom is
   * a conversation that never runs out. {@code spent()} is the obvious thing to assert and it is
   * the field the row plainly carries; {@code remaining()} is what a turn actually decides on, so
   * it is the one asserted here.
   */
  @Test
  void what_comes_back_is_the_budget_the_row_records_and_not_a_fresh_allowance() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(10));
    Budget spending = opened.budget();
    for (int call = 0; call < 4; call++) {
      assertTrue(spending.trySpend(), "the budget had room for call " + call);
    }
    store.turnEnded(opened.id(), spending);

    Budget readBack = store.find(opened.id()).orElseThrow().budget();
    assertEquals(
        6,
        readBack.remaining(),
        "a conversation four calls into a ten-call budget has six left, and a store that"
            + " rebuilt the budget from its total alone would say ten");
    assertEquals(4, readBack.spent());
    assertEquals(10, readBack.limit());
  }

  @Test
  void a_turn_records_what_it_spent_and_when_it_ended() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(8));
    Budget spending = opened.budget();
    spending.trySpend();
    spending.trySpend();

    Instant ended = OPENED_AT.plus(Duration.ofMinutes(3));
    clock.set(ended);
    ConversationRecord after = store.turnEnded(opened.id(), spending);

    assertEquals(ended, after.lastTurnAt());
    assertEquals(2, after.budget().spent());
    assertEquals(
        OPENED_AT,
        after.createdAt(),
        "and the conversation is still the one that was opened, not restamped by its"
            + " own turn");
  }

  /** A turn that made no model calls still happened, and the row says when. */
  @Test
  void a_turn_that_spent_nothing_still_stamps_that_it_happened() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(8));

    Instant ended = OPENED_AT.plus(Duration.ofSeconds(30));
    clock.set(ended);
    ConversationRecord after = store.turnEnded(opened.id(), opened.budget());

    assertEquals(ended, after.lastTurnAt());
    assertEquals(0, after.budget().spent());
  }

  /**
   * The write that would launder spending back into a fresh allowance.
   *
   * <p>The stale record is a real one — read before the other turn ran — and not a hand-made {@link
   * Budget}, so this is the situation a caller actually gets into rather than an approximation of
   * it.
   */
  @Test
  void a_turn_reporting_less_than_the_row_holds_is_refused_and_names_the_spending() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(10));
    ConversationRecord stale = store.find(opened.id()).orElseThrow();

    Budget busier = opened.budget();
    busier.trySpend();
    busier.trySpend();
    busier.trySpend();
    store.turnEnded(opened.id(), busier);

    Budget behind = stale.budget();
    behind.trySpend();
    ArchiveException refused =
        assertThrows(ArchiveException.class, () -> store.turnEnded(opened.id(), behind));

    assertTrue(refused.getMessage().contains("already spent 3"), refused.getMessage());
    assertTrue(refused.getMessage().contains("reports 1"), refused.getMessage());
    assertEquals(
        3,
        store.find(opened.id()).orElseThrow().budget().spent(),
        "and the row still holds what was really spent");
  }

  /**
   * The other way {@code turnEnded} writes nothing, and it gets its own sentence: an id nothing
   * opened and an id that is behind are different mistakes, and a single message covering both
   * would send an operator looking for a conversation that is not the problem.
   */
  @Test
  void a_turn_on_a_conversation_nothing_opened_is_refused_and_names_the_id() {
    ArchiveException refused =
        assertThrows(ArchiveException.class, () -> store.turnEnded("cnv_9999", Budget.of(4)));

    assertTrue(refused.getMessage().contains("cnv_9999"), refused.getMessage());
    assertTrue(refused.getMessage().contains("no conversation"), refused.getMessage());
  }

  /**
   * Equal spending is not lower spending: a turn that ends where the row already is re-stamps when,
   * and is not mistaken for a stale write.
   */
  @Test
  void a_turn_that_spent_no_more_than_the_row_already_holds_is_still_a_turn() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(6));
    Budget spending = opened.budget();
    spending.trySpend();
    store.turnEnded(opened.id(), spending);

    Instant later = OPENED_AT.plus(Duration.ofMinutes(5));
    clock.set(later);
    ConversationRecord after = store.turnEnded(opened.id(), spending);

    assertEquals(later, after.lastTurnAt());
    assertEquals(1, after.budget().spent());
  }

  // --- an allowance a conversation owns and puts no ceiling on -----------------

  /**
   * The third state, and the one V17 had no room for.
   *
   * <p>A lifted conversation OWNS its allowance -- it is nobody else's -- and simply declines to
   * put a ceiling on it, so the row carries a count and no total. That is the shape {@code
   * conversations_an_allowance_is_both_halves_or_neither} refused outright until V31, and the
   * assertion on the column is the half that matters: no large number stands in for infinity here
   * either.
   */
  @Test
  void a_lifted_conversation_round_trips_with_its_count_and_no_total() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.none());

    ConversationStore afterRestart = new ConversationStore(new JdbcTemplate(dataSource()));
    ConversationRecord read = afterRestart.find(opened.id()).orElseThrow();

    assertNotNull(read.budget(), "it owns an allowance; what it has not got is a ceiling");
    assertFalse(read.budget().capped());
    assertEquals(0, read.budget().spent());
    assertNull(
        jdbc.queryForObject(
            "SELECT budget_total FROM conversations WHERE id = ?", Integer.class, opened.id()),
        "and the row says so by holding no total, not by holding a very large one");
  }

  /**
   * Spend is a measurement, so it outlives the ceiling being taken away.
   *
   * <p>This is the write {@link ConversationStore#turnEnded} could not make before: it wrote {@code
   * budget.limit()}, which a lifted budget refuses to answer. An operator watching a run with no
   * ceiling still sees what it has used, which is the only thing left to watch.
   */
  @Test
  void a_turn_on_a_lifted_conversation_records_what_it_spent_without_a_total() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.none());
    Budget spending = opened.budget();
    spending.trySpend();
    spending.trySpend();
    spending.trySpend();

    ConversationRecord after = store.turnEnded(opened.id(), spending);

    assertFalse(after.budget().capped(), "the turn did not quietly give it a ceiling");
    assertEquals(3, after.budget().spent());
    assertEquals(3, store.find(opened.id()).orElseThrow().budget().spent());
    assertTrue(
        jdbc.queryForObject(
            "SELECT budget_lifted FROM conversations WHERE id = ?", Boolean.class, opened.id()));
  }

  /**
   * The state V17 gave NULL, still distinguishable from the new one.
   *
   * <p>Both rows hold no total; a lifted row holds a count and the boolean and a child holds
   * neither. If this read came back as a {@link Budget} the store would be handing a delegated
   * child a second allowance of its parent's size, which is the double count V17 section 4 exists
   * to make structurally impossible.
   */
  @Test
  void a_delegated_child_still_reads_back_as_holding_no_budget_at_all() {
    ConversationRecord parent = store.open(PAYMENTS, Budget.none());
    ConversationRecord child = store.log(Origin.DELEGATION, PAYMENTS, "helper", parent.id(), null);

    assertNull(
        store.find(child.id()).orElseThrow().budget(),
        "a child spends its parent's by reference, and a copy on this row would be"
            + " counted a second time by the first thing to sum the column");
  }

  /**
   * The revised constraint, asserted at the database and not in Java.
   *
   * <p>A count with no total is spending nothing measures -- V17's sentence, and it is still true
   * of every row that has not said the ceiling was lifted. The boolean is what makes the difference
   * legible to Postgres, so the same row is refused without it and accepted with it.
   */
  @Test
  void a_count_without_a_total_is_refused_unless_the_row_is_lifted() {
    insert("cnv_ordinary", "payments", OPENED_AT, 4, 0);

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "UPDATE conversations SET budget_total = NULL,"
                        + " budget_spent = 3, budget_lifted = FALSE WHERE id = ?",
                    "cnv_ordinary"));
    assertTrue(
        refused.getMessage().contains("conversations_an_allowance_is_both_halves_or_neither"),
        refused.getMessage());

    jdbc.update(
        "UPDATE conversations SET budget_total = NULL, budget_spent = 3,"
            + " budget_lifted = TRUE WHERE id = ?",
        "cnv_ordinary");

    assertFalse(
        store.find("cnv_ordinary").orElseThrow().budget().capped(),
        "and the same three columns with the boolean set are a legal row");
  }

  /**
   * A number and "no ceiling" are alternatives, on {@code
   * conversations_turn_cap_is_a_number_or_no_cap}'s terms exactly: a row holding both has answered
   * one question twice.
   */
  @Test
  void the_table_refuses_a_row_that_names_a_total_and_lifts_it_at_once() {
    insert("cnv_bothways", "payments", OPENED_AT, 4, 0);

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "UPDATE conversations SET budget_lifted = TRUE WHERE id = ?", "cnv_bothways"));

    assertTrue(
        refused.getMessage().contains("conversations_budget_is_a_number_or_no_ceiling"),
        refused.getMessage());
  }

  /**
   * Lifting is something the owner does, and a conversation that owns nothing has nothing to lift.
   *
   * <p>The boolean joins {@code budget_total} on the owning side of {@code
   * conversations_an_allowance_is_owned_or_shared} rather than standing beside it -- so a delegated
   * child cannot say "no ceiling" about an allowance that is its parent's, which would be the same
   * double claim as copying the numbers down, spelled in one column instead of two.
   *
   * <p>The row carries a count as well as the boolean, and that is what makes this an assertion
   * about ownership: a lifted row with no count is refused by {@code
   * conversations_an_allowance_is_both_halves_or_neither} first, so the shorter version of this
   * fixture would pass while proving the other rule twice.
   */
  @Test
  void a_conversation_that_shares_an_allowance_cannot_lift_a_ceiling_it_does_not_own() {
    ConversationRecord parent = store.open(PAYMENTS, Budget.of(4));

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, lifecycle, parent_id, agent,"
                        + " created_at, budget_spent, budget_lifted)"
                        + " VALUES ('cnv_liftless', ?, 'delegation', NULL, ?, 'helper',"
                        + " ?, 0, TRUE)",
                    projectId("payments"),
                    parent.id(),
                    utc(OPENED_AT)));

    assertTrue(
        refused.getMessage().contains("conversations_an_allowance_is_owned_or_shared"),
        refused.getMessage());
  }

  /**
   * And the other direction of the same predicate: an owner has to say which of the two it is. A
   * turn's row with no total and no lifting is a conversation whose allowance nobody wrote down.
   */
  @Test
  void an_owner_that_names_neither_a_total_nor_a_lifting_is_refused() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, created_at)"
                        + " VALUES ('cnv_silent', ?, 'turn', ?)",
                    projectId("payments"),
                    utc(OPENED_AT)));

    assertTrue(
        refused.getMessage().contains("conversations_an_allowance_is_owned_or_shared"),
        refused.getMessage());
  }

  /**
   * The same predicate, named for {@code 'memory'} rather than for {@code 'turn'}.
   *
   * <p><b>Why this row and not the one above.</b> V29 widened {@code
   * conversations_an_allowance_is_owned_or_shared} to a third origin, and V31 restated the whole
   * predicate rather than patching it — its own comment warns that a dropped-and-recreated
   * constraint "inherits from whatever last defined it, not from whichever migration a reader
   * happens to have open", which is exactly the shape of mistake a rewrite of this constraint could
   * make silently for {@code 'memory'} specifically: the fixture above would keep passing, because
   * {@code 'turn'} was never the origin at risk. Before this test, {@code 'memory'}'s membership
   * was exercised only incidentally, by a {@code DigestStoreTest} fixture that opens a memory
   * conversation with a real allowance and would fail for an unrelated reason — a missing digest
   * row, not a missing budget — if this predicate ever stopped covering the origin. This test fails
   * for the one reason that matters, by name.
   */
  @Test
  void a_memory_conversation_that_names_neither_a_total_nor_a_lifting_is_refused() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, agent, created_at)"
                        + " VALUES ('cnv_memoryless', ?, 'memory', 'memory_navigator', ?)",
                    projectId("payments"),
                    utc(OPENED_AT)));

    assertTrue(
        refused.getMessage().contains("conversations_an_allowance_is_owned_or_shared"),
        refused.getMessage());
  }

  // --- the listing, which filters -------------------------------------------

  /**
   * Standing check 1: the fixture holds rows in the other two tiers, so a query that lost its
   * {@code WHERE} fails here rather than passing over a table it happens to be the only occupant
   * of.
   */
  @Test
  void the_conversations_in_one_home_leave_out_the_ones_in_another() {
    ConversationRecord mine = store.open(PAYMENTS, Budget.of(4));
    store.open(LEDGER, Budget.of(4));
    store.open(Home.global(), Budget.of(4));

    assertEquals(
        List.of(mine.id()),
        store.inHome(PAYMENTS).stream().map(ConversationRecord::id).toList(),
        "one project's conversations are not another's, and are not the global tier's");
  }

  /**
   * And the global tier is an answer this query gives rather than a hole in it. {@code project = ?}
   * with a null returns nothing at all — {@code ProposalStore.HOME_MATCHES} carries that
   * measurement — so a store that used equality would pass the test above and fail only here.
   */
  @Test
  void the_global_tier_is_read_by_the_listing_rather_than_left_out_of_it() {
    store.open(PAYMENTS, Budget.of(4));
    store.open(LEDGER, Budget.of(4));
    ConversationRecord everywhere = store.open(Home.global(), Budget.of(4));

    assertEquals(
        List.of(everywhere.id()),
        store.inHome(Home.global()).stream().map(ConversationRecord::id).toList());
  }

  @Test
  void a_home_nobody_has_talked_in_lists_nothing_rather_than_refusing() {
    store.open(PAYMENTS, Budget.of(4));

    assertEquals(
        List.of(),
        store.inHome(LEDGER),
        "a project with no conversations is a project nobody has opened one in");
  }

  /**
   * Oldest first, and the fixture is written out of order so that insertion order and clock order
   * disagree — an ORDER BY dropped altogether would otherwise be indistinguishable from the
   * planner's own answer.
   */
  @Test
  void a_homes_conversations_come_back_oldest_first() {
    clock.set(OPENED_AT.plus(Duration.ofHours(2)));
    ConversationRecord last = store.open(PAYMENTS, Budget.of(4));
    clock.set(OPENED_AT);
    ConversationRecord first = store.open(PAYMENTS, Budget.of(4));
    clock.set(OPENED_AT.plus(Duration.ofHours(1)));
    ConversationRecord middle = store.open(PAYMENTS, Budget.of(4));

    assertEquals(
        List.of(first.id(), middle.id(), last.id()),
        store.inHome(PAYMENTS).stream().map(ConversationRecord::id).toList());
  }

  // --- the conversation a bot continues -------------------------------------

  /**
   * One turn, written the way {@code TurnStore.record} writes one, so that this file can put an
   * agent on a conversation at all.
   *
   * <p><b>Direct SQL because the agent is on the turn and not on the conversation</b>, which is the
   * finding {@link ConversationStore#latestFor} is built around: {@code
   * conversations_a_person_s_conversation_names_no_agent} is a CHECK that {@code (origin = 'turn')
   * = (agent IS NULL)}, so the row a person speaks into cannot name one and the only record of who
   * answered is {@code turns.agent}. Written here rather than through {@code TurnStore} for the
   * reason the constraint cases below are: this file holds no second store, and the columns are
   * three.
   */
  private void turnBy(String conversation, int ordinal, String agent) {
    jdbc.update(
        "INSERT INTO turns"
            + " (conversation_id, ordinal, utterance, answer, ending, agent)"
            + " VALUES (?, ?, 'what did you make of it', 'I read it twice', 'ANSWERED', ?)",
        conversation,
        ordinal,
        agent);
  }

  /**
   * Standing check: the fixture holds an older conversation with the same agent in it, so a query
   * that dropped its {@code ORDER BY} or its {@code LIMIT} answers the wrong row rather than
   * passing over a table with one in it.
   */
  @Test
  void the_newest_conversation_this_agent_answered_in_is_the_one_to_continue() {
    clock.set(OPENED_AT);
    ConversationRecord older = store.open(PAYMENTS, Budget.of(4));
    turnBy(older.id(), 1, "aristoxenus");
    clock.set(OPENED_AT.plus(Duration.ofHours(2)));
    ConversationRecord newest = store.open(PAYMENTS, Budget.of(4));
    turnBy(newest.id(), 1, "aristoxenus");

    assertEquals(
        newest.id(),
        store.latestFor(PAYMENTS, "aristoxenus").orElseThrow().id(),
        "there is only ever one to continue, and it is the newest of them");
    assertNull(
        store.find(newest.id()).orElseThrow().agent(),
        "and the row itself names no agent: a person's conversation cannot, so the"
            + " derivation is over turns.agent and not over this column");
  }

  @Test
  void another_agents_conversation_is_not_the_one_this_bot_continues() {
    ConversationRecord theirs = store.open(PAYMENTS, Budget.of(4));
    turnBy(theirs.id(), 1, "close_reader");

    assertTrue(
        store.latestFor(PAYMENTS, "aristoxenus").isEmpty(),
        "a tier where somebody has talked to another agent is a tier where this bot"
            + " has no conversation yet");
  }

  @Test
  void a_bot_nobody_has_spoken_to_here_has_no_conversation_to_continue() {
    assertTrue(
        store.latestFor(PAYMENTS, "aristoxenus").isEmpty(),
        "none yet is an ordinary answer and not a refusal — the first message opens" + " one");
  }

  /**
   * The tier filter, which is {@link ConversationStore#inHome}'s and has to be this read's too: a
   * bot is one character and a tier is one archive.
   */
  @Test
  void a_conversation_in_another_tier_is_not_the_one_to_continue() {
    ConversationRecord elsewhere = store.open(LEDGER, Budget.of(4));
    turnBy(elsewhere.id(), 1, "aristoxenus");

    assertTrue(store.latestFor(PAYMENTS, "aristoxenus").isEmpty());
    assertEquals(elsewhere.id(), store.latestFor(LEDGER, "aristoxenus").orElseThrow().id());
  }

  /**
   * And the global tier is an answer this read gives rather than a hole in it, which is what {@code
   * IS NOT DISTINCT FROM} buys.
   */
  @Test
  void the_global_tier_is_a_conversation_to_continue_rather_than_left_out() {
    ConversationRecord everywhere = store.open(Home.global(), Budget.of(4));
    turnBy(everywhere.id(), 1, "aristoxenus");
    ConversationRecord filed = store.open(PAYMENTS, Budget.of(4));
    turnBy(filed.id(), 1, "aristoxenus");

    assertEquals(everywhere.id(), store.latestFor(Home.global(), "aristoxenus").orElseThrow().id());
  }

  /**
   * A conversation somebody put away is not silently reopened by talking.
   *
   * <p>{@code Turn.speak} refuses to speak into an archived conversation, so a read that offered
   * one would hand this client an id every utterance is refused against — and the person would be
   * told they were continuing something they cannot say anything into.
   */
  @Test
  void an_archived_conversation_is_not_the_one_to_continue() {
    ConversationRecord putAway = store.open(PAYMENTS, Budget.of(4));
    turnBy(putAway.id(), 1, "aristoxenus");
    store.moveTo(putAway.id(), ConversationLifecycle.ARCHIVED);

    assertTrue(
        store.latestFor(PAYMENTS, "aristoxenus").isEmpty(),
        "archiving has to bite here too, or a person is handed a conversation every"
            + " utterance is refused against");
  }

  /**
   * A row with no turns in it is nobody's conversation yet.
   *
   * <p>It is a reachable state and not a hypothetical: this client opens a conversation on the
   * first message and the run that follows can fail, so a row with nothing in it is what a refused
   * first turn leaves behind. Nothing records who it was opened for, which is exactly why it cannot
   * be claimed.
   */
  @Test
  void a_conversation_nobody_has_spoken_into_is_not_the_one_to_continue() {
    store.open(PAYMENTS, Budget.of(4));

    assertTrue(store.latestFor(PAYMENTS, "aristoxenus").isEmpty());
  }

  /**
   * A delegated child is a machine's log and not a conversation to walk back into, which is {@link
   * ConversationStore#inHome}'s {@code origin} filter and has to be this read's for the same
   * reason.
   *
   * <p><b>It is the one row that would pass a turns-only test.</b> A child carries the agent on its
   * own row <em>and</em> on its turn, so a read that matched on either without filtering the origin
   * would answer with the log of a run somebody's agent started inside another run.
   */
  @Test
  void a_delegated_child_answered_by_the_same_agent_is_not_the_one_to_continue() {
    ConversationRecord parent = store.open(PAYMENTS, Budget.of(4));
    ConversationRecord child =
        store.log(Origin.DELEGATION, PAYMENTS, "aristoxenus", parent.id(), null);
    turnBy(child.id(), 1, "aristoxenus");

    assertTrue(
        store.latestFor(PAYMENTS, "aristoxenus").isEmpty(),
        "a person's conversation is what is continued, and a delegated child is not" + " one");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t"})
  void an_agent_that_cannot_be_named_is_refused_rather_than_matching_nothing(String unusable) {
    assertThrows(ValidationException.class, () -> store.latestFor(PAYMENTS, unusable));
  }

  // --- ids ------------------------------------------------------------------

  /**
   * The production spelling, driven once: a store built the way the server builds one mints the
   * house scheme under this table's own prefix.
   */
  @Test
  void the_default_id_is_the_house_scheme_under_its_own_prefix() {
    ConversationStore production = new ConversationStore(jdbc);

    String id = production.open(PAYMENTS, Budget.of(4)).id();

    assertTrue(id.startsWith(ConversationStore.PREFIX), id);
    assertEquals(
        "cnv_",
        ConversationStore.PREFIX,
        "and the prefix is the one V6 and MemoryIds' javadoc both name");
    assertEquals(
        ConversationStore.PREFIX.length() + 16,
        id.length(),
        "ten hex digits of millis and six of randomness, as MemoryIds.mint mints them");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t"})
  void an_id_that_cannot_be_named_is_refused_on_every_way_in(String unusable) {
    assertThrows(ValidationException.class, () -> store.find(unusable));
    assertThrows(ValidationException.class, () -> store.turnEnded(unusable, Budget.of(4)));
  }

  // --- what the schema refuses that Java cannot produce ---------------------

  /**
   * The invariant the table exists for, and standing check 2 applies to the accepted side of it.
   *
   * <p>The accepted fixture is <b>not</b> written as "total, and total again": it is a real {@link
   * Budget} spent to exhaustion through {@code trySpend}, which is the mechanism the constraint is
   * meant to agree with rather than a restatement of the constraint's own predicate. The refused
   * row is one call past it, inserted through SQL, because that is the only way to make one.
   */
  @Test
  void the_table_refuses_a_row_that_has_spent_more_than_it_was_given() {
    Budget exhausted = Budget.of(4);
    while (exhausted.trySpend()) {
      // Spent to its limit by the method that owns the limit.
    }
    ConversationRecord full = store.open(PAYMENTS, exhausted);
    assertEquals(
        exhausted.limit(),
        store.find(full.id()).orElseThrow().budget().spent(),
        "a budget spent exactly to its limit is a row, not a fault");

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert("cnv_over", "payments", OPENED_AT, 4, 5));
    assertTrue(
        refused.getMessage().contains("conversations_spent_within_budget"),
        "overspending is what the row was refused for — " + refused.getMessage());
  }

  /**
   * Separate from the ceiling, and asserted by name, because V6 splits them deliberately: one
   * predicate covering both would name a rule without saying which end of the accounting broke.
   */
  @Test
  void the_table_refuses_negative_spending_separately_from_overspending() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert("cnv_negative", "payments", OPENED_AT, 4, -1));

    assertTrue(
        refused.getMessage().contains("conversations_spending_is_not_negative"),
        refused.getMessage());
    assertTrue(
        !refused.getMessage().contains("conversations_spent_within_budget"),
        "and not the other one, which -1 also satisfies — " + refused.getMessage());
  }

  @Test
  void the_table_refuses_a_budget_nothing_can_be_spent_from() {
    // Budget.of refuses this first, naming it; this is what stops anything
    // that bypasses Java.
    assertThrows(CallerFault.class, () -> Budget.of(0));

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert("cnv_broke", "payments", OPENED_AT, 0, 0));
    assertTrue(
        refused.getMessage().contains("conversations_budget_is_spendable"), refused.getMessage());
  }

  @Test
  void the_table_refuses_a_blank_id_or_a_blank_project() {
    DataIntegrityViolationException unnamed =
        assertThrows(
            DataIntegrityViolationException.class, () -> insert("", "payments", OPENED_AT, 4, 0));
    assertTrue(unnamed.getMessage().contains("conversations_id_named"), unnamed.getMessage());

    // V14 took `conversations_project_named` with the text column, and its
    // argument moved rather than went away: there is one place a project
    // name is stored now, so there is one CHECK refusing a blank one, and a
    // conversation can only reach a tier through a row that passed it.
    DataIntegrityViolationException homeless =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO projects (name) VALUES ('')"));
    assertTrue(
        homeless.getMessage().contains("projects_name_named"),
        "the blank project is what the row was refused for, and NULL is not blank — "
            + homeless.getMessage());
  }

  /**
   * The reference V6 could not make. It said so at the time — "NO FOREIGN KEY to {@code projects
   * (name)} ... tying them would make talking depend on somebody having already run {@code define}"
   * — and V14 keeps that promise by a different route: {@link ConversationStore#open} registers the
   * project it names, so the constraint costs nobody a {@code define} and a conversation still
   * cannot be filed in a project that is not there.
   */
  @Test
  void a_conversation_cannot_be_filed_in_a_project_that_does_not_exist() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, created_at, budget_total, budget_spent)"
                        + " VALUES ('cnv_dangling', 0, 'turn', ?, 4, 0)",
                    utc(OPENED_AT)));
    assertTrue(
        refused.getMessage().contains("conversations_project_exists"),
        "the missing project is what the row was refused for");
  }

  /**
   * Opening a conversation in a project nobody defined registers it, without granting it anything:
   * the row has no workspace, which is the file access the project had a moment earlier — none.
   */
  @Test
  void opening_a_conversation_in_an_undefined_project_registers_it_without_a_workspace() {
    ConversationRecord opened = store.open(Home.of("ledger"), Budget.of(3));

    assertEquals(Home.of("ledger"), store.find(opened.id()).orElseThrow().home());
    assertNull(
        jdbc.queryForObject("SELECT workspace FROM projects WHERE name = 'ledger'", String.class));
  }

  /**
   * A home nothing has been opened in is empty, and it is not the global tier. {@code ProjectIds}
   * carries the argument: an unknown name that resolved to NULL would make {@code GET
   * /v1/conversations?project=typo} answer with every global conversation on the server.
   */
  @Test
  void a_home_nothing_has_been_opened_in_is_empty_and_is_not_the_global_tier() {
    store.open(Home.global(), Budget.of(3));

    assertEquals(List.of(), store.inHome(Home.of("never-opened-in")));
    assertEquals(1, store.inHome(Home.global()).size());
  }

  @Test
  void the_table_refuses_a_turn_that_ended_before_the_conversation_opened() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(4));

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "UPDATE conversations SET last_turn_at = ? WHERE id = ?",
                    utc(OPENED_AT.minus(Duration.ofSeconds(1))),
                    opened.id()));
    assertTrue(
        refused.getMessage().contains("conversations_turn_is_not_before_the_conversation"),
        refused.getMessage());

    // The equal case is a fixture and not a fault: the clock is injected, so
    // opening a conversation and ending a turn without moving it is an
    // ordinary thing for a test to do.
    jdbc.update(
        "UPDATE conversations SET last_turn_at = ? WHERE id = ?", utc(OPENED_AT), opened.id());
    assertEquals(OPENED_AT, store.find(opened.id()).orElseThrow().lastTurnAt());
  }

  // --- Budget.resumed, which exists for this store and has no other caller --

  /**
   * Tested here rather than in a {@code BudgetTest} that does not exist, because this store is the
   * reason {@link Budget#resumed} was added: it is how a row becomes the object a turn's delegation
   * tree shares. Its refusals are what stop a hand-edited row being laundered into a fresh
   * allowance on the way back in — the same fault {@code conversations_spent_within_budget} refuses
   * on the way out, one layer up, because a row that got past the database by some other route
   * still reaches this method.
   */
  @Test
  void a_budget_read_back_over_its_limit_is_refused_rather_than_laundered() {
    assertThrows(IllegalArgumentException.class, () -> Budget.resumed(4, 5));
    assertThrows(IllegalArgumentException.class, () -> Budget.resumed(4, -1));
    // A non-positive total is refused by Budget.of before resumed's own
    // checks ever run, and Budget.of raises CallerFault, not the plain
    // IllegalArgumentException the two lines above pin.
    assertThrows(CallerFault.class, () -> Budget.resumed(0, 0));

    Budget atItsLimit = Budget.resumed(4, 4);
    assertEquals(0, atItsLimit.remaining());
    assertTrue(
        !atItsLimit.trySpend(), "and it is spent: resuming at the limit does not hand back a call");
  }

  // --- fixtures -------------------------------------------------------------

  // --- the turn cap a conversation may decide ----------------------------------

  /**
   * The ordinary shape: a conversation that says nothing about the cap, so the agent answering each
   * turn is bounded by its own file.
   */
  @Test
  void a_conversation_that_decides_no_turn_cap_reads_back_deciding_none() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(8));

    assertNull(opened.turnCap());
    assertNull(store.find(opened.id()).orElseThrow().turnCap());
  }

  @Test
  void a_conversation_that_names_a_turn_cap_survives_a_restart_with_it() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(8), TurnCap.of(30));

    TurnCap readBack = store.find(opened.id()).orElseThrow().turnCap();

    assertNotNull(readBack);
    assertTrue(readBack.capped());
    assertEquals(30, readBack.turns());
  }

  /**
   * The state a number cannot hold.
   *
   * <p>A conversation that decided its turns run uncapped reads back as exactly that, and not as a
   * conversation that decided nothing — which is what a single nullable integer column would have
   * made of it.
   */
  @Test
  void a_conversation_that_lifted_the_turn_cap_reads_back_uncapped_and_not_undecided() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(8), TurnCap.none());

    TurnCap readBack = store.find(opened.id()).orElseThrow().turnCap();

    assertNotNull(readBack, "it decided something, and the something was 'no cap'");
    assertFalse(readBack.capped());
  }

  /**
   * Each read is its own object, so an operator moving one turn's ceiling cannot silently re-open
   * the conversation's for every turn after it.
   */
  @Test
  void two_reads_of_one_conversation_do_not_share_a_ceiling() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(8), TurnCap.of(30));

    TurnCap first = store.find(opened.id()).orElseThrow().turnCap();
    first.changeTo(90);

    assertEquals(
        30,
        store.find(opened.id()).orElseThrow().turnCap().turns(),
        "the row is what the next turn starts from");
  }

  @Test
  void the_table_refuses_a_turn_cap_of_no_turns() {
    assertThrows(IllegalArgumentException.class, () -> TurnCap.of(0));

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class, () -> insertWithCap("cnv_capless", 0, false));
    assertTrue(
        refused.getMessage().contains("conversations_turn_cap_is_a_cap"), refused.getMessage());
  }

  /**
   * A number and "no cap" are alternatives. A row holding both would have answered one question
   * twice, and whichever a reader preferred would be the reader's decision rather than the
   * conversation's.
   */
  @Test
  void the_table_refuses_a_row_that_names_a_cap_and_lifts_it_at_once() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class, () -> insertWithCap("cnv_both", 5, true));

    assertTrue(
        refused.getMessage().contains("conversations_turn_cap_is_a_number_or_no_cap"),
        refused.getMessage());
  }

  /**
   * Every conversation opened before there was a level to decide this at is one that decides
   * nothing, which is what they are. The column's default is what makes that true of a row this
   * test writes without naming it.
   */
  @Test
  void a_row_written_without_either_column_is_a_conversation_that_decides_nothing() {
    insert("cnv_older", "payments", OPENED_AT, 4, 0);

    assertNull(store.find("cnv_older").orElseThrow().turnCap());
  }

  // --- origin, the parent and the agent -----------------------------------------

  /**
   * The seam that fails on the build which adds an {@link Origin} constant without a migration.
   *
   * <p>{@code conversations_origin_is_known} and this enum are held together by nothing at compile
   * time — the situation {@code EntryKind} and {@code Outcome.Ending} are already in — so an origin
   * added to Java alone would be a row Postgres refuses the first time a real run reaches it, in
   * production. This enumerates {@link Origin#values()} and writes one of each, so it fails on the
   * build instead.
   */
  @Test
  void every_origin_this_server_can_write_is_one_this_table_holds() {
    for (Origin origin : Origin.values()) {
      ConversationRecord written =
          origin == Origin.TURN
              ? store.open(PAYMENTS, Budget.of(4))
              : store.log(
                  origin,
                  PAYMENTS,
                  "helper",
                  origin == Origin.DELEGATION ? store.open(PAYMENTS, Budget.of(4)).id() : null,
                  origin.ownsItsAllowance() ? Budget.of(4) : null);

      assertEquals(
          origin,
          store.find(written.id()).orElseThrow().origin(),
          "an origin this server writes has to read back as itself");
    }
  }

  /**
   * An origin nobody has given behaviour to is a row that would be written successfully and be
   * unreadable for ever, which is exactly what the CHECK exists to prevent.
   */
  @Test
  void the_table_refuses_an_origin_nobody_has_given_behaviour_to() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            // Every OTHER rule satisfied -- an agent, no parent, no
            // allowance -- so the one that fires is the one under test. A
            // row that broke two would pass this assertion with the origin
            // constraint deleted.
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, agent, created_at)"
                        + " VALUES ('cnv_scheduled', ?, 'schedule', 'helper', ?)",
                    projectId("payments"),
                    utc(OPENED_AT)));

    assertTrue(
        refused.getMessage().contains("conversations_origin_is_known"), refused.getMessage());
  }

  /**
   * A delegated child names the conversation that delegated to it, and every other origin is a
   * root. Both directions, because the constraint is one predicate and a test for half of it would
   * pass with the other half relaxed.
   */
  @Test
  void a_parent_belongs_to_a_delegation_and_to_nothing_else() {
    ConversationRecord parent = store.open(PAYMENTS, Budget.of(4));

    DataIntegrityViolationException orphaned =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, agent, created_at)"
                        + " VALUES ('cnv_orphan', ?, 'delegation', 'helper', ?)",
                    projectId("payments"),
                    utc(OPENED_AT)));
    assertTrue(
        orphaned.getMessage().contains("conversations_a_delegation_is_what_has_a_parent"),
        orphaned.getMessage());

    DataIntegrityViolationException adopted =
        assertThrows(
            DataIntegrityViolationException.class,
            // `lifecycle` named as NULL rather than left to its default,
            // so that exactly ONE constraint is broken and the assertion
            // below is about the one under test: V19 puts the lifecycle on
            // the root of a tree, and a row with a parent AND a state would
            // break that rule as well as this one.
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, lifecycle, parent_id, agent, created_at)"
                        + " VALUES ('cnv_adopted', ?, 'curator', NULL, ?, 'promotion_judge', ?)",
                    projectId("payments"),
                    parent.id(),
                    utc(OPENED_AT)));
    assertTrue(
        adopted.getMessage().contains("conversations_a_delegation_is_what_has_a_parent"),
        adopted.getMessage());
  }

  /** A conversation that is its own whole ancestry is one every walk up the tree hangs on. */
  @Test
  void the_table_refuses_a_conversation_that_delegated_to_itself() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            // `lifecycle` NULL for the reason above: a child holds no state
            // of its own, so leaving it to default would break V19's rule
            // alongside the one this asserts on.
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, lifecycle, parent_id, agent, created_at)"
                        + " VALUES ('cnv_ouroboros', ?, 'delegation', NULL, 'cnv_ouroboros',"
                        + " 'helper', ?)",
                    projectId("payments"),
                    utc(OPENED_AT)));

    assertTrue(
        refused.getMessage().contains("conversations_nothing_delegated_to_itself"),
        refused.getMessage());
  }

  /**
   * A person's conversation names no agent and every other kind names one.
   *
   * <p>This is the constraint that keeps {@code ConversationController}'s long-standing argument
   * true at its own level: a person may put two agents' turns in one conversation, so the row
   * cannot pin one — while a delegated child, a curator's ruling and a submission are each one
   * agent's from end to end and a row that did not say which would be one nothing could resume or
   * price.
   */
  @Test
  void a_person_s_conversation_is_the_one_that_names_no_agent() {
    ConversationRecord mine = store.open(PAYMENTS, Budget.of(4));
    assertNull(mine.agent(), "a person's conversation names none");
    assertEquals(
        "echo", store.log(Origin.SUBMISSION, PAYMENTS, "echo", null, Budget.of(4)).agent());

    DataIntegrityViolationException pinned =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, agent, created_at, budget_total,"
                        + " budget_spent) VALUES ('cnv_pinned', ?, 'turn', 'echo', ?, 4, 0)",
                    projectId("payments"),
                    utc(OPENED_AT)));
    assertTrue(
        pinned.getMessage().contains("conversations_a_person_s_conversation_names_no_agent"),
        pinned.getMessage());

    DataIntegrityViolationException nameless =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, created_at, budget_total, budget_spent)"
                        + " VALUES ('cnv_nameless', ?, 'submission', ?, 4, 0)",
                    projectId("payments"),
                    utc(OPENED_AT)));
    assertTrue(
        nameless.getMessage().contains("conversations_a_person_s_conversation_names_no_agent"),
        nameless.getMessage());
  }

  /**
   * Half an allowance is worse than none: a count with no total is spending with no ceiling, and
   * {@code conversations_spent_within_budget} cannot catch it because that predicate is unknown
   * when the total is NULL.
   */
  @Test
  void the_table_refuses_half_an_allowance() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, created_at, budget_total, budget_spent)"
                        + " VALUES ('cnv_half', ?, 'turn', ?, NULL, 3)",
                    projectId("payments"),
                    utc(OPENED_AT)));

    assertTrue(
        refused.getMessage().contains("conversations_an_allowance_is_both_halves_or_neither"),
        refused.getMessage());
  }

  /**
   * The store refuses what the schema refuses, one layer earlier and in sentences.
   *
   * <p>Four combinations that cannot mean anything, and the point of catching them here is that a
   * caller gets told which rule it broke rather than reading a constraint name out of a Postgres
   * message.
   */
  @Test
  void the_log_door_refuses_the_combinations_that_cannot_mean_anything() {
    ConversationRecord parent = store.open(PAYMENTS, Budget.of(4));

    assertThrows(
        IllegalArgumentException.class,
        () -> store.log(Origin.TURN, PAYMENTS, "echo", null, Budget.of(4)),
        "a person's conversation is opened with open(), which names no agent");
    assertThrows(
        ValidationException.class,
        () -> store.log(Origin.SUBMISSION, PAYMENTS, null, null, Budget.of(4)),
        "one agent's run has to say which agent");
    assertThrows(
        IllegalArgumentException.class,
        () -> store.log(Origin.DELEGATION, PAYMENTS, "helper", null, null),
        "a delegation without a delegator is a severed branch");
    assertThrows(
        IllegalArgumentException.class,
        () -> store.log(Origin.DELEGATION, PAYMENTS, "helper", parent.id(), Budget.of(4)),
        "and a child writing its parent's allowance down is the double count");
  }

  // --- the lifecycle --------------------------------------------------------

  /**
   * A state added to the enum without a migration is a row Postgres refuses the first time a real
   * conversation reaches it, in production. This enumerates {@link ConversationLifecycle#values()}
   * and drives one of each onto a real row, so it fails on the build instead.
   *
   * <p>Driven through {@link ConversationStore#moveTo} and not through a raw UPDATE, so it also
   * asserts the transition table reaches every state: a constant nothing can be moved to is one no
   * conversation can ever be in, which is the same defect one layer up.
   */
  @Test
  void every_lifecycle_state_this_server_can_write_is_one_this_table_holds() {
    for (ConversationLifecycle state : ConversationLifecycle.values()) {
      ConversationRecord opened = store.open(PAYMENTS, Budget.of(4));
      for (ConversationLifecycle step : pathTo(state)) {
        store.moveTo(opened.id(), step);
      }
      assertEquals(
          state,
          store.find(opened.id()).orElseThrow().lifecycle(),
          "a state this server can move a conversation to has to read back as itself");
    }
  }

  /**
   * The shortest walk from {@code active} to {@code target}, which is how a test reaches a state
   * the transition table does not allow in one step.
   */
  private static List<ConversationLifecycle> pathTo(ConversationLifecycle target) {
    return switch (target) {
      case ACTIVE -> List.of();
      case ARCHIVED -> List.of(ConversationLifecycle.ARCHIVED);
      case TO_BE_EJECTED -> List.of(ConversationLifecycle.TO_BE_EJECTED);
      case EJECTED -> List.of(ConversationLifecycle.TO_BE_EJECTED, ConversationLifecycle.EJECTED);
    };
  }

  @Test
  void a_conversation_is_opened_active_and_a_delegated_child_has_no_state_of_its_own() {
    ConversationRecord root = store.open(PAYMENTS, Budget.of(4));
    ConversationRecord child = store.log(Origin.DELEGATION, PAYMENTS, "helper", root.id(), null);

    assertEquals(ConversationLifecycle.ACTIVE, store.find(root.id()).orElseThrow().lifecycle());
    assertNull(
        store.find(child.id()).orElseThrow().lifecycle(),
        "one state per tree: a child that carried its own could disagree with its root,"
            + " which is the archived-root-with-live-branches failure as a row");
  }

  /**
   * The property the whole shape of the column exists for. A child does not hold a state and does
   * not need to be updated when its root moves: the answer changes because the root's did.
   */
  @Test
  void a_child_resolves_to_its_root_however_deep_it_is() {
    ConversationRecord root = store.open(PAYMENTS, Budget.of(4));
    ConversationRecord child = store.log(Origin.DELEGATION, PAYMENTS, "helper", root.id(), null);
    ConversationRecord grandchild =
        store.log(Origin.DELEGATION, PAYMENTS, "deeper", child.id(), null);

    assertEquals(Optional.of(ConversationLifecycle.ACTIVE), store.lifecycleOf(grandchild.id()));

    store.moveTo(root.id(), ConversationLifecycle.ARCHIVED);

    assertEquals(
        Optional.of(ConversationLifecycle.ARCHIVED),
        store.lifecycleOf(grandchild.id()),
        "archiving the root archived the tree, and nothing wrote a row for the child");
    assertEquals(Optional.of(ConversationLifecycle.ARCHIVED), store.lifecycleOf(child.id()));
    assertNull(
        store.find(grandchild.id()).orElseThrow().lifecycle(),
        "and it is still the root's state rather than a copy on the child");
  }

  /**
   * The same walk, answering with the row rather than the column.
   *
   * <p>{@link ConversationStore#lifecycleOf} is enough for a sweep, which only needs the state. A
   * learning window needs to know <em>which tree</em> as well: it ranks a shortlist of
   * conversations by the state of the trees they belong to, and two conversations in one tree have
   * to come out as one tree.
   */
  @Test
  void the_root_of_a_tree_is_what_a_child_resolves_to_and_what_a_root_answers_with() {
    ConversationRecord root = store.open(PAYMENTS, Budget.of(4));
    ConversationRecord child = store.log(Origin.DELEGATION, PAYMENTS, "helper", root.id(), null);
    ConversationRecord grandchild =
        store.log(Origin.DELEGATION, PAYMENTS, "deeper", child.id(), null);
    store.moveTo(root.id(), ConversationLifecycle.TO_BE_EJECTED);

    assertEquals(root.id(), store.rootOf(grandchild.id()).orElseThrow().id());
    assertEquals(
        root.id(),
        store.rootOf(root.id()).orElseThrow().id(),
        "a root resolves to itself, in one lookup");
    assertEquals(
        ConversationLifecycle.TO_BE_EJECTED,
        store.rootOf(child.id()).orElseThrow().lifecycle(),
        "the row this answers with is the one carrying the state, which is the whole"
            + " reason a caller asks for it");
    assertEquals(PAYMENTS, store.rootOf(child.id()).orElseThrow().home());
  }

  /**
   * And an id nothing holds is empty rather than a refusal, {@code find}'s choice for {@code
   * find}'s reason.
   */
  @Test
  void the_root_of_a_conversation_nothing_holds_is_empty() {
    assertTrue(store.rootOf("cnv_nothing").isEmpty());
  }

  @Test
  void the_tree_under_a_root_is_the_root_and_everything_delegated_below_it() {
    ConversationRecord root = store.open(PAYMENTS, Budget.of(4));
    ConversationRecord child = store.log(Origin.DELEGATION, PAYMENTS, "helper", root.id(), null);
    ConversationRecord grandchild =
        store.log(Origin.DELEGATION, PAYMENTS, "deeper", child.id(), null);
    // A second tree in the same home, to fail a query that lost its anchor.
    ConversationRecord other = store.open(PAYMENTS, Budget.of(4));
    store.log(Origin.DELEGATION, PAYMENTS, "elsewhere", other.id(), null);

    assertEquals(List.of(root.id(), child.id(), grandchild.id()), store.treeOf(root.id()));
  }

  /**
   * {@code archived} is optional and not a gate, which is what makes a policy able to mark a
   * machine's log directly without anybody having archived it on its behalf.
   */
  @Test
  void a_conversation_can_be_marked_for_ejection_without_being_archived_first() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(4));

    assertEquals(
        ConversationLifecycle.TO_BE_EJECTED,
        store.moveTo(opened.id(), ConversationLifecycle.TO_BE_EJECTED).lifecycle());
  }

  /**
   * Both reversals, because they are the recoverability the staged path is for and each is a
   * separate edge in the table.
   */
  @Test
  void unarchiving_and_cancelling_a_mark_both_return_a_conversation_to_active() {
    ConversationRecord archived = store.open(PAYMENTS, Budget.of(4));
    store.moveTo(archived.id(), ConversationLifecycle.ARCHIVED);
    assertEquals(
        ConversationLifecycle.ACTIVE,
        store.moveTo(archived.id(), ConversationLifecycle.ACTIVE).lifecycle());

    ConversationRecord marked = store.open(PAYMENTS, Budget.of(4));
    store.moveTo(marked.id(), ConversationLifecycle.TO_BE_EJECTED);
    assertEquals(
        ConversationLifecycle.ACTIVE,
        store.moveTo(marked.id(), ConversationLifecycle.ACTIVE).lifecycle(),
        "cancelling before the export is the whole point of marking separately");
  }

  /** Terminal, and the refusal says so rather than saying nothing. */
  @Test
  void nothing_comes_back_from_ejected() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(4));
    store.moveTo(opened.id(), ConversationLifecycle.TO_BE_EJECTED);
    store.moveTo(opened.id(), ConversationLifecycle.EJECTED);

    for (ConversationLifecycle target : ConversationLifecycle.values()) {
      if (target == ConversationLifecycle.EJECTED) {
        continue;
      }
      ArchiveException refused =
          assertThrows(ArchiveException.class, () -> store.moveTo(opened.id(), target));
      assertTrue(
          refused.getMessage().contains("is ejected and cannot become"), refused.getMessage());
    }
  }

  @Test
  void the_only_way_to_ejected_is_through_the_mark() {
    ConversationRecord opened = store.open(PAYMENTS, Budget.of(4));

    ArchiveException refused =
        assertThrows(
            ArchiveException.class, () -> store.moveTo(opened.id(), ConversationLifecycle.EJECTED));
    assertTrue(refused.getMessage().contains("to_be_ejected"), refused.getMessage());
    assertEquals(
        ConversationLifecycle.ACTIVE,
        store.find(opened.id()).orElseThrow().lifecycle(),
        "and the refusal changed nothing");
  }

  /**
   * Moving a child is refused rather than redirected: a person who archives one conversation must
   * not find another one gone.
   */
  @Test
  void a_delegated_child_cannot_be_moved_and_the_refusal_names_its_parent() {
    ConversationRecord root = store.open(PAYMENTS, Budget.of(4));
    ConversationRecord child = store.log(Origin.DELEGATION, PAYMENTS, "helper", root.id(), null);

    ArchiveException refused =
        assertThrows(
            ArchiveException.class, () -> store.moveTo(child.id(), ConversationLifecycle.ARCHIVED));
    assertTrue(refused.getMessage().contains(root.id()), refused.getMessage());
    assertEquals(
        ConversationLifecycle.ACTIVE,
        store.find(root.id()).orElseThrow().lifecycle(),
        "and nothing was archived on the caller's behalf");
  }

  /**
   * A person can eject or archive nearly any tree, but not the one a conductor is still speaking in
   * — {@code moveTo}'s guard against pulling a live orchestration's own conversation out from under
   * it.
   */
  @Test
  void a_live_orchestration_s_own_conversation_cannot_be_moved() {
    ConversationRecord conductor =
        store.log(Origin.ORCHESTRATION, PAYMENTS, "code_implementation", null, Budget.of(40));
    jdbc.update(
        "INSERT INTO orchestrations (id, definition_name, tier, definition_hash,"
            + " definition_source, definition_origin, stages, max_returns, returns_used,"
            + " conductor_conversation, caller_agent, depth, state, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 0, ?, ?, 0, ?, ?)",
        "orc_0001",
        "code_implementation",
        "PROJECT",
        "sha256:9f86d081884c7d659a2feaa0c55ad015",
        "---\nname: code_implementation\n---\nbody",
        "test",
        "[]",
        2,
        conductor.id(),
        "code_implementation",
        "running",
        OffsetDateTime.now(ZoneOffset.UTC));

    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> store.moveTo(conductor.id(), ConversationLifecycle.ARCHIVED));
    assertTrue(refused.getMessage().contains("live orchestration"), refused.getMessage());
    assertEquals(
        ConversationLifecycle.ACTIVE,
        store.find(conductor.id()).orElseThrow().lifecycle(),
        "and nothing was archived while the run was still live");

    jdbc.update(
        "UPDATE orchestrations SET state = 'cancelled', failure = 'x',"
            + " ended_at = now() WHERE id = ?",
        "orc_0001");

    assertEquals(
        ConversationLifecycle.ARCHIVED,
        store.moveTo(conductor.id(), ConversationLifecycle.ARCHIVED).lifecycle(),
        "and archiving succeeds once the run has ended");
  }

  @Test
  void the_listing_shows_what_is_active_and_finds_what_was_archived_when_asked() {
    ConversationRecord talking = store.open(PAYMENTS, Budget.of(4));
    ConversationRecord putAway = store.open(PAYMENTS, Budget.of(4));
    store.moveTo(putAway.id(), ConversationLifecycle.ARCHIVED);

    assertEquals(
        List.of(talking.id()),
        store.inHome(PAYMENTS).stream().map(ConversationRecord::id).toList(),
        "archiving has to bite, and the listing is where a person sees that it did");
    assertEquals(
        List.of(putAway.id()),
        store.inHome(PAYMENTS, ConversationLifecycle.ARCHIVED).stream()
            .map(ConversationRecord::id)
            .toList(),
        "and a state that can be entered and not left is not reversible in practice");
  }

  /**
   * The sweep's selection, and the fixture holds one of everything it must NOT pick: another
   * origin, another state, and one that is not old enough.
   */
  @Test
  void a_sweep_selects_the_roots_of_one_origin_that_have_not_been_touched_since() {
    Instant longAgo = OPENED_AT.minus(Duration.ofDays(30));
    clock.set(longAgo);
    ConversationRecord old = store.log(Origin.CURATOR, PAYMENTS, "judge", null, null);
    ConversationRecord alsoOld = store.log(Origin.SUBMISSION, PAYMENTS, "echo", null, Budget.of(4));
    clock.set(OPENED_AT);
    ConversationRecord recent = store.log(Origin.CURATOR, PAYMENTS, "judge", null, null);

    assertEquals(
        List.of(old.id()),
        store.roots(
            Origin.CURATOR, ConversationLifecycle.ACTIVE, OPENED_AT.minus(Duration.ofDays(1))),
        "the recent one, the submission and every non-root are all left alone");
    assertEquals(
        List.of(alsoOld.id()),
        store.roots(
            Origin.SUBMISSION, ConversationLifecycle.ACTIVE, OPENED_AT.minus(Duration.ofDays(1))));
    assertEquals(
        List.of(old.id(), recent.id()),
        store.roots(Origin.CURATOR, ConversationLifecycle.ACTIVE, null),
        "and no age at all is every root in that state, which is what the eject stage"
            + " asks for");
  }

  /**
   * A turn stamps a last turn and a submission never does, so age has to read both columns; a rule
   * on either alone gets one of the two wrong.
   */
  @Test
  void age_is_counted_from_the_last_turn_when_there_has_been_one() {
    clock.set(OPENED_AT.minus(Duration.ofDays(30)));
    ConversationRecord spokenInto = store.open(PAYMENTS, Budget.of(4));
    clock.set(OPENED_AT);
    store.turnEnded(spokenInto.id(), Budget.resumed(4, 1));

    assertEquals(
        List.of(),
        store.roots(Origin.TURN, ConversationLifecycle.ACTIVE, OPENED_AT.minus(Duration.ofDays(1))),
        "opened a month ago and spoken into today is not an old conversation");
  }

  /**
   * The state belongs to the root, and a row that says otherwise is one no reader has a meaning
   * for.
   */
  @Test
  void the_table_refuses_a_state_on_a_child_and_a_root_without_one() {
    ConversationRecord parent = store.open(PAYMENTS, Budget.of(4));

    DataIntegrityViolationException onAChild =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, lifecycle, parent_id, agent, created_at)"
                        + " VALUES ('cnv_branch', ?, 'delegation', 'archived', ?, 'helper', ?)",
                    projectId("payments"),
                    parent.id(),
                    utc(OPENED_AT)));
    assertTrue(
        onAChild.getMessage().contains("conversations_a_root_is_where_the_lifecycle_lives"),
        onAChild.getMessage());

    DataIntegrityViolationException rootless =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, lifecycle, created_at, budget_total,"
                        + " budget_spent) VALUES ('cnv_stateless', ?, 'turn', NULL, ?, 4, 0)",
                    projectId("payments"),
                    utc(OPENED_AT)));
    assertTrue(
        rootless.getMessage().contains("conversations_a_root_is_where_the_lifecycle_lives"),
        rootless.getMessage());
  }

  @Test
  void the_table_refuses_a_lifecycle_state_nobody_has_given_behaviour_to() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, lifecycle, created_at, budget_total,"
                        + " budget_spent) VALUES ('cnv_cold', ?, 'turn', 'cold', ?, 4, 0)",
                    projectId("payments"),
                    utc(OPENED_AT)));

    assertTrue(
        refused.getMessage().contains("conversations_lifecycle_is_known"), refused.getMessage());
  }

  // --- the name a conversation is known by ----------------------------------

  /**
   * The name is the first thing somebody said, and not the whole of it.
   *
   * <p><b>The first non-blank line, because that is where a person puts the subject.</b> An
   * utterance is a message, not a sentence: it opens with the question and then carries the paste,
   * the stack trace and the four paragraphs of context underneath. A derivation that took the first
   * <em>sentence</em> would run through a newline into the paste; one that took the whole utterance
   * would put a stack trace in a listing.
   *
   * <p><b>{@code lines()} and not a split on {@code \n}</b>, so a CRLF utterance — every one that
   * arrives from a Windows terminal — is the same three lines as a LF one rather than one line
   * ending in a stray carriage return that a listing would render as a cursor jump.
   */
  @Test
  void the_name_is_the_first_thing_somebody_said_and_not_the_whole_of_it() {
    assertEquals(
        "why did the deploy roll back?",
        ConversationStore.titleFrom(
            "  \r\n\r\n   why did the deploy roll back?  \r\n\r\nhere is the log:\r\n"
                + "  at Migration.run(Migration.java:41)\r\n"),
        "the first non-blank line, stripped, is the name; the log under it is not");
  }

  /**
   * A heading is stripped of its marks, because a listing is already a list.
   *
   * <p>{@code # Ask about soil} is a person writing markdown into a chat box, and the {@code #} is
   * structure rather than a word: it says "this line is the heading", which is exactly what the
   * title column already says about every value in it. Kept, it would render as furniture in front
   * of every title a markdown-writing person opened.
   *
   * <p><b>Only the ATX heading run, and that is the whole of the stripping.</b> A leading {@code -}
   * or {@code *} is a bullet in markdown and a minus sign or a glob everywhere else, and telling
   * those apart is a parser — a derivation with a parser in it is one that can disagree with itself
   * about the same line on two days. A {@code #} at the start of a line followed by whitespace is
   * unambiguous, so that is where the stripping stops.
   */
  @Test
  void a_heading_is_stripped_of_its_marks_because_a_listing_is_already_a_list() {
    assertEquals("Ask about soil", ConversationStore.titleFrom("# Ask about soil"));
    assertEquals("Ask about soil", ConversationStore.titleFrom("###   Ask about soil\n\nbody"));
    assertEquals(
        "C# or F#?",
        ConversationStore.titleFrom("C# or F#?"),
        "a hash that is part of a word is a word, and only a leading run is a mark");
    assertEquals(
        "- a bullet stays a bullet", ConversationStore.titleFrom("- a bullet stays a bullet"));
  }

  /**
   * An utterance with nothing in it names nothing, rather than naming it blank.
   *
   * <p><b>Null and not the empty string</b>, and the difference is the whole of what a reader can
   * do about it: a null title is a conversation with no name, which a client renders as its id; an
   * empty one is a conversation whose name is nothing, which renders as a blank row nobody can
   * click with confidence. {@code conversations_a_title_is_named} refuses the second one layer
   * down, so this is the shape that keeps the write from ever meeting it.
   *
   * <p>Reachable, and not a defensive branch: {@code turns_utterance_is_not_blank} refuses the
   * empty string and nothing else, so an utterance of three spaces and a newline is a row {@code
   * turns} holds quite happily.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\n\n\n", "  \r\n \r\n  "})
  void an_utterance_with_nothing_in_it_names_nothing_rather_than_naming_it_blank(String nothing) {
    assertNull(
        ConversationStore.titleFrom(nothing),
        "no name is a fact a client can render; a blank name is a row nobody can read");
  }

  /**
   * One word longer than the width is cut, rather than allowed to overflow.
   *
   * <p>A pasted URL, a base64 blob and a stack frame are all one "word" by any definition this
   * derivation could hold, and each of them is the ordinary first line of a first utterance. The
   * word boundary is a preference and not a rule: when there is no boundary near the cut, the cut
   * wins, because a title that respected the boundary above all would be the whole blob.
   */
  @Test
  void one_word_longer_than_the_width_is_cut_rather_than_allowed_to_overflow() {
    String blob = "a".repeat(ConversationStore.TITLE_WIDTH + 40);

    String title = ConversationStore.titleFrom(blob);

    assertEquals(
        ConversationStore.TITLE_WIDTH,
        title.length(),
        "there is no boundary to land on, so the width is what decides");
    assertEquals("a".repeat(ConversationStore.TITLE_WIDTH), title);
  }

  /**
   * A cut lands on a word boundary when one is near it.
   *
   * <p>Near, and not anywhere: a boundary further back than {@link
   * ConversationStore#NEAREST_BOUNDARY} throws away more of the name than the ragged edge costs,
   * which is the case above.
   */
  @Test
  void a_cut_lands_on_a_word_boundary_when_one_is_near_it() {
    // Boundaries every ten characters, so the last one inside the width is
    // at 69 and the width is 72 -- three characters back, and the cut takes
    // it rather than splitting `abcdefghi` across it.
    String utterance = "abcdefghi ".repeat(8);

    String title = ConversationStore.titleFrom(utterance);

    assertEquals("abcdefghi ".repeat(7).strip(), title);
    assertTrue(
        title.length() < ConversationStore.TITLE_WIDTH,
        "a boundary cut is shorter than the width, which is what makes it a boundary");
  }

  /**
   * The first turn names the conversation, and every one after it changes nothing.
   *
   * <p><b>{@code AND title IS NULL} is the whole of that</b>, and it is why this is one statement
   * rather than a read and a write. The write does not have to know the turn's ordinal, does not
   * have to read the row first, and does not have a window between the read and the write for a
   * second turn to land in: two turns arriving together both try, and Postgres lets exactly one of
   * them through because the second one's {@code WHERE} no longer matches.
   */
  @Test
  void the_first_turn_names_the_conversation_and_a_second_write_changes_nothing() {
    String conversation = store.open(PAYMENTS, Budget.of(4)).id();

    ConversationStore.nameFromFirstTurn(jdbc, conversation, "why did the deploy roll back?");
    ConversationStore.nameFromFirstTurn(jdbc, conversation, "and what fixed it?");

    assertEquals(
        "why did the deploy roll back?",
        store.find(conversation).orElseThrow().title(),
        "a title is what the conversation opened with, not what it last said");
  }

  /**
   * A conversation carries its title on the record, and one nobody has spoken into has none.
   *
   * <p>The null is permanent for every row written before V38 and ordinary for a conversation
   * opened and never spoken into — the same state {@code last_turn_at} carries one column over, and
   * for the same reason.
   */
  @Test
  void a_conversation_carries_its_title_on_the_record_and_a_new_one_has_none() {
    String conversation = store.open(PAYMENTS, Budget.of(4)).id();

    assertNull(
        store.find(conversation).orElseThrow().title(),
        "opened is not spoken into, and there is nothing yet to name it with");

    ConversationStore.nameFromFirstTurn(jdbc, conversation, "# Ask about soil");

    assertEquals("Ask about soil", store.find(conversation).orElseThrow().title());
  }

  /**
   * A title nothing can read is not a name: the empty string is what an omitted field arrives as,
   * and the absence is NULL one value over.
   */
  @Test
  void the_table_refuses_a_title_that_is_not_a_name() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO conversations"
                        + " (id, project_id, origin, lifecycle, created_at,"
                        + " budget_total, budget_spent, title)"
                        + " VALUES ('cnv_blank', ?, 'turn', 'active', ?, 4, 0, '')",
                    projectId("payments"),
                    utc(OPENED_AT)));

    assertTrue(
        refused.getMessage().contains("conversations_a_title_is_named"), refused.getMessage());
  }

  /** Spec 2026-09-28-hooks-reach-the-log decision 8, as amended: the owner is set at open. */
  @Test
  void an_owner_is_written_at_open_and_a_delegated_child_inherits_its_parent_s() {
    String turn = store.open(PAYMENTS, Budget.of(4), null, "enzo").id();
    String child = store.log(Origin.DELEGATION, PAYMENTS, "helper", turn, null).id();
    String event = store.log(Origin.EVENT, PAYMENTS, "helper", null, Budget.of(4), "sam").id();
    String machine = store.log(Origin.SUBMISSION, PAYMENTS, "helper", null, Budget.of(4)).id();

    assertEquals(Optional.of("enzo"), store.ownerOf(turn));
    assertEquals(Optional.of("enzo"), store.ownerOf(child), "a child's owner is its parent's");
    assertEquals(Optional.of("sam"), store.ownerOf(event));
    assertEquals(
        Optional.empty(), store.ownerOf(machine), "a door that knows no account writes none");
  }

  /** Decision 9: stored once in system_blocks, never changed once written. */
  @Test
  void a_log_s_opening_is_fixed_once_and_read_back_whole() {
    TurnStore turns = new TurnStore(jdbc);
    String log = store.open(PAYMENTS, Budget.of(4)).id();

    assertEquals(Optional.empty(), store.opening(log), "a log with no hooks carries no block");
    assertTrue(store.fixOpening(log, turns.remember("house rules")));
    assertFalse(
        store.fixOpening(log, turns.remember("other rules")),
        "the opening never changes once written");
    assertEquals(Optional.of("house rules"), store.opening(log));
  }

  @Test
  void a_log_is_closed_once() {
    String log = store.open(PAYMENTS, Budget.of(4)).id();

    assertTrue(store.closeLog(log, OPENED_AT));
    assertFalse(store.closeLog(log, OPENED_AT));
  }

  @Test
  void a_log_s_latest_turn_is_its_highest_ordinal_and_nought_before_its_first() {
    TurnStore turns = new TurnStore(jdbc);
    String log = store.open(PAYMENTS, Budget.of(4)).id();

    assertEquals(0, turns.latestOrdinal(log));
    turns.record(log, "hi", "hello", Outcome.Ending.ANSWERED, null, "helper", null);
    turns.record(log, "again", "hello again", Outcome.Ending.ANSWERED, null, "helper", null);
    assertEquals(2, turns.latestOrdinal(log));
  }

  /**
   * A row written the way nothing in Java can write one, so the CHECKs are driven by the situation
   * they exist for.
   */
  private static void insertWithCap(String id, Integer turnCap, boolean lifted) {
    jdbc.update(
        "INSERT INTO conversations"
            + " (id, project_id, origin, created_at, budget_total, budget_spent,"
            + " turn_cap, turn_cap_lifted) VALUES (?, ?, 'turn', ?, ?, ?, ?, ?)",
        id,
        projectId("payments"),
        utc(OPENED_AT),
        4,
        0,
        turnCap,
        lifted);
  }

  /**
   * A row written the way nothing in Java can write one, so the CHECKs are driven by the situation
   * they exist for.
   */
  private static void insert(String id, String project, Instant createdAt, int total, int spent) {
    jdbc.update(
        "INSERT INTO conversations"
            + " (id, project_id, origin, created_at, budget_total, budget_spent)"
            + " VALUES (?, ?, 'turn', ?, ?, ?)",
        id,
        projectId(project),
        utc(createdAt),
        total,
        spent);
  }

  /**
   * The id a raw insert has to carry since V14, registering the project if it is not there.
   *
   * <p>These fixtures write rows "the way nothing in Java can write one", and that is still what
   * they do — the project row is the referent the CHECKs under test need to exist, not part of the
   * row being driven. NULL passes straight through, because NULL is the global tier and has no id
   * to look up.
   */
  private static Long projectId(String project) {
    return project == null
        ? null
        : jdbc.queryForObject(
            "INSERT INTO projects (name) VALUES (?)"
                + " ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING id",
            Long.class,
            project);
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
