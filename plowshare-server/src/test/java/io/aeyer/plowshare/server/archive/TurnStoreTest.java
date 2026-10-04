package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What was said in a conversation, kept where a restart cannot take it.
 *
 * <h2>The three things worth failing on</h2>
 *
 * <ul>
 *   <li><b>a turn is numbered within its own conversation.</b> The ordinal is assigned by the store
 *       from what the conversation already holds, so the statement that computes it filters — and
 *       {@code every_conversation_is_numbered_from_its_own_first_turn} is the fixture that fails
 *       when that {@code WHERE} is lost, because a second conversation's first turn would then be
 *       numbered after the first conversation's last;
 *   <li><b>the listing filters</b>, so every fixture for {@link TurnStore#forConversation} holds
 *       turns in a second conversation as well as the one being asked for;
 *   <li><b>an unmeasured cost is not a cost of zero.</b> {@code prompt_tokens} is what task 4
 *       decides compaction from, and a NULL read as a zero would say the history is empty and defer
 *       compaction for ever. The column refuses zero outright and {@code
 *       a_cost_nobody_measured_is_absent_and_is_not_a_cost_of_zero} is what fails on a store that
 *       coalesced one into the other;
 *   <li><b>a turn that recorded no system block does not borrow today's.</b> {@code
 *       turns.system_block} — V32 — is the one thing in a projection that cannot be recomputed from
 *       the log, and every row written before that migration has none. {@code
 *       a_turn_written_before_this_column_existed_reports_no_block_rather_than_ todays} is what
 *       fails on a store that filled the gap from anywhere, and {@code
 *       two_turns_sending_the_same_block_store_it_once} is what fails on one that stopped
 *       content-addressing them.
 * </ul>
 *
 * <h2>The schema's rules are driven through SQL this store cannot emit</h2>
 *
 * <p>Every named constraint on {@code turns} is asserted <em>by name</em>, for the reason {@code
 * ConversationStoreTest} gives about V6's six: naming them is what makes Postgres say which rule a
 * bad row broke, and a test that only asserted "some constraint fired" would pass with all of them
 * collapsed into one predicate. The count is deliberately not written down here — it was "seven"
 * and V17 and V32 each added one without this sentence noticing.
 *
 * <p><b>{@code every_ending_this_server_can_reach_is_a_turn_this_table_holds} is the instrument for
 * the one that grows.</b> {@code Outcome.Ending}'s javadoc lists the places a new constant has to
 * be added, none of which is a compile error; V7's {@code turns_ending_is_known} is now one of
 * them, and an eighth constant added without a migration fails there rather than at the first
 * conversation that reaches it.
 */
@Tag("full-db")
@Testcontainers
class TurnStoreTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private static final Home PAYMENTS = Home.of("payments");
  private static final Instant OPENED_AT = Instant.parse("2026-08-31T09:00:00Z");

  private final AtomicInteger minted = new AtomicInteger();

  private ConversationStore conversations;
  private TurnStore turns;

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
  void freshTurns() {
    // All three tables in one statement, because of the keys between them:
    // Postgres refuses to truncate a table another one references unless
    // that one is truncated with it, and it refuses whether or not the
    // referencing table holds a row. V7 put `turns` behind `conversations`;
    // V8 put `compactions` behind `turns`, with a composite key into it, and
    // that is why a third name appears here in a file that writes no
    // compaction. `ConversationStoreTest` and `TurnTest` name all three for
    // the same reason, and the sentence this comment used to carry — that
    // `conversations` has no foreign key in either direction — is what V7
    // made false. `board_topics`, `board_messages` and `board_seats` joined
    // the same way in V74, each with its own foreign key into
    // `conversations`; `firings` a hop further out, through its V74 foreign
    // key into `board_topics`; and `user_inbox` a hop past that, through its
    // pre-existing V40 foreign key into `firings`.
    jdbc.execute(
        "TRUNCATE TABLE board_message_route_bindings, outgoing_work, message_external_tasks, message_external_contexts, board_message_routes, board_message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, system_blocks, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
    minted.set(0);
    conversations =
        new ConversationStore(
            jdbc, () -> OPENED_AT, () -> String.format("cnv_%04d", minted.incrementAndGet()));
    turns = new TurnStore(jdbc);
  }

  // --- what a turn is -------------------------------------------------------

  /**
   * Read back through a second store over a second connection, so the answer came from Postgres and
   * not from anything the first store kept.
   *
   * <p>This is the whole point of the table. A conversation's spending survives a restart already;
   * a turn that did not would leave a row that had spent its allowance and could not say on what.
   */
  @Test
  void a_turn_records_what_was_said_and_what_came_back_and_survives_a_restart() {
    String conversation = open();

    TurnRecord written =
        turns.record(
            conversation,
            "what broke the deploy?",
            "the migration ran twice",
            Ending.ANSWERED,
            1_432,
            null,
            null);

    TurnStore afterRestart = new TurnStore(new JdbcTemplate(dataSource()));
    List<TurnRecord> read = afterRestart.forConversation(conversation);
    assertEquals(
        List.of(written),
        read,
        "a turn is the record of what was said, and a restart is not a reason to lose" + " it");
    TurnRecord only = read.get(0);
    assertEquals("what broke the deploy?", only.utterance());
    assertEquals("the migration ran twice", only.answer());
    assertEquals(Ending.ANSWERED, only.ending());
    assertEquals(1_432, only.promptTokens());
    assertEquals(1, only.ordinal());
  }

  @Test
  void a_conversation_nobody_has_spoken_into_has_no_turns_rather_than_a_refusal() {
    String conversation = open();

    assertEquals(
        List.of(),
        turns.forConversation(conversation),
        "a conversation opened and not spoken into has an empty history, which is an"
            + " answer and not a mistake");
  }

  // --- the ending, and the set of them that grows ---------------------------

  /**
   * Every ending this server can reach is one this table holds.
   *
   * <p>The instrument for {@code turns_ending_is_known}, and the reason it is an {@code EnumSource}
   * rather than seven literals: {@code Outcome.Ending} grows, none of the places a constant has to
   * be added is a compile error, and a list written out here would go stale in exactly the same
   * silence.
   *
   * <p>The answer is non-blank for every case because {@code turns_a_turn_that_stopped_says_how}
   * requires it of a stopping ending — which is {@code Outcome}'s own guarantee, not this fixture's
   * convenience.
   */
  @ParameterizedTest
  @EnumSource(Ending.class)
  void every_ending_this_server_can_reach_is_a_turn_this_table_holds(Ending ending) {
    String conversation = open();

    TurnRecord written =
        turns.record(
            conversation, "go on", "This run stopped. " + ending, ending, null, null, null);

    assertEquals(ending, turns.forConversation(conversation).get(0).ending());
    assertEquals(ending, written.ending());
  }

  /**
   * A turn that stopped is still a turn, and the conversation goes on.
   *
   * <p>The spec's rule — "a turn that ends other than ANSWERED does not end the conversation" —
   * read back off the table rather than asserted about Java.
   */
  @Test
  void a_turn_that_stopped_is_recorded_beside_the_one_that_answered() {
    String conversation = open();

    turns.record(
        conversation,
        "read the log",
        "This run stopped at its turn cap of 8 turns" + " without reaching an answer.",
        Ending.TURN_CAP,
        900,
        null,
        null);
    turns.record(
        conversation,
        "try again",
        "the log rotated at midnight",
        Ending.ANSWERED,
        1_100,
        null,
        null);

    assertEquals(
        List.of(Ending.TURN_CAP, Ending.ANSWERED),
        turns.forConversation(conversation).stream().map(TurnRecord::ending).toList(),
        "the transcript records which ending each turn reached, and a capped turn is"
            + " part of the history rather than a gap in it");
  }

  // --- the numbering, which filters on the way in ---------------------------

  @Test
  void each_turn_is_numbered_after_the_one_before_it() {
    String conversation = open();

    turns.record(conversation, "one", "first", Ending.ANSWERED, null, null, null);
    turns.record(conversation, "two", "second", Ending.ANSWERED, null, null, null);
    turns.record(conversation, "three", "third", Ending.ANSWERED, null, null, null);

    assertEquals(
        List.of(1, 2, 3),
        turns.forConversation(conversation).stream().map(TurnRecord::ordinal).toList());
  }

  /**
   * Standing check 1, applied to the <em>write</em> rather than to a read.
   *
   * <p>The ordinal is {@code MAX(ordinal) + 1} over the turns of one conversation, so that
   * statement filters — and a fixture with a single conversation in the table could not tell a
   * filtered maximum from an unfiltered one. Here the busy conversation is written first, so a
   * store that lost its {@code WHERE} numbers the quiet conversation's first turn 3.
   */
  @Test
  void every_conversation_is_numbered_from_its_own_first_turn() {
    String busy = open();
    turns.record(busy, "one", "first", Ending.ANSWERED, null, null, null);
    turns.record(busy, "two", "second", Ending.ANSWERED, null, null, null);

    String quiet = open();
    TurnRecord opening =
        turns.record(quiet, "hello", "hello yourself", Ending.ANSWERED, null, null, null);

    assertEquals(
        1,
        opening.ordinal(),
        "the first thing said in a conversation is its first turn, whatever anybody has"
            + " said in another one");
  }

  /**
   * Oldest first, over a fixture whose insertion order disagrees.
   *
   * <p>The rows are written through SQL in the order 3, 1, 2, because the store assigns ordinals in
   * sequence and could not produce a disagreement on its own.
   *
   * <p><b>The {@code ORDER BY} is load-bearing, and that is measured rather than assumed.</b> It
   * looked as though it could not be — the primary key is {@code (conversation_id, ordinal)}, so a
   * planner reading through that index would answer in ordinal order either way — and this comment
   * said so until the mutant was run. Deleting the clause fails this test: on Postgres 16 against a
   * three-row table the planner sequentially scans and answers in heap order, which is the order
   * the rows were inserted in. What the measurement does <em>not</em> establish is that it would
   * still bite on a table large enough for the index to win, which is the honest limit of a fixture
   * this size.
   */
  @Test
  void a_conversations_turns_come_back_in_the_order_they_were_spoken() {
    String conversation = open();

    insert(conversation, 3, "third", "c", Ending.ANSWERED.name(), null);
    insert(conversation, 1, "first", "a", Ending.ANSWERED.name(), null);
    insert(conversation, 2, "second", "b", Ending.ANSWERED.name(), null);

    assertEquals(
        List.of("first", "second", "third"),
        turns.forConversation(conversation).stream().map(TurnRecord::utterance).toList());
  }

  /**
   * Standing check 1: the fixture holds another conversation's turns, so a query that lost its
   * {@code WHERE} fails here rather than passing over a table it happens to be the only occupant
   * of.
   */
  @Test
  void the_turns_of_one_conversation_are_not_anothers() {
    String mine = open();
    String theirs = open();
    turns.record(mine, "mine", "an answer of mine", Ending.ANSWERED, null, null, null);
    turns.record(theirs, "theirs", "an answer of theirs", Ending.ANSWERED, null, null, null);
    turns.record(theirs, "theirs again", "and another", Ending.ANSWERED, null, null, null);

    assertEquals(
        List.of("mine"),
        turns.forConversation(mine).stream().map(TurnRecord::utterance).toList(),
        "one conversation's history is not another's");
  }

  // --- the measurement task 4 reads -----------------------------------------

  /**
   * The distinction the whole column turns on.
   *
   * <p>Absent means nobody measured this turn's prompt — the endpoint omitted {@code usage}, or
   * nothing plumbed it — and it is not the number zero. Task 4 compacts when the last measurement
   * plus headroom would exceed the model's loaded context length; a NULL folded into a 0 would say
   * the history costs nothing and would defer compaction for ever, which is the confidently-wrong
   * answer this project keeps deleting rather than the loud one.
   */
  @Test
  void a_cost_nobody_measured_is_absent_and_is_not_a_cost_of_zero() {
    String conversation = open();

    turns.record(
        conversation, "unmeasured", "no usage came back", Ending.ANSWERED, null, null, null);
    turns.record(conversation, "measured", "usage came back", Ending.ANSWERED, 2_048, null, null);

    List<TurnRecord> history = turns.forConversation(conversation);
    assertNull(
        history.get(0).promptTokens(),
        "an endpoint that said nothing about cost leaves this absent; zero would be a"
            + " claim that the prompt had no tokens in it");
    assertEquals(2_048, history.get(1).promptTokens());
  }

  @Test
  void the_table_refuses_a_measurement_of_no_tokens_at_all() {
    String conversation = open();

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert(conversation, 1, "said", "answered", Ending.ANSWERED.name(), 0));
    assertTrue(
        refused.getMessage().contains("turns_prompt_tokens_are_a_measurement"),
        refused.getMessage());

    // The accepted side, written independently of the constraint: one token
    // is the smallest prompt a call can be charged for, and it is a row.
    assertEquals(
        1,
        turns
            .record(conversation, "said", "answered", Ending.ANSWERED, 1, null, null)
            .promptTokens());
  }

  // --- what the schema refuses that this store cannot emit -------------------

  /**
   * A turn in a conversation nobody opened is not a record, it is a bug.
   *
   * <p>Driven through the store's own call rather than through raw SQL, because this is the one of
   * the seven a caller can actually reach: nothing deletes a conversation, so the only way to break
   * this foreign key is to name an id that was never opened.
   */
  @Test
  void the_table_refuses_a_turn_in_a_conversation_nothing_opened() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                turns.record(
                    "cnv_9999", "into the void", "nothing", Ending.ANSWERED, 10, null, null));

    assertTrue(
        refused.getMessage().contains("turns_belong_to_a_conversation"), refused.getMessage());
  }

  @Test
  void the_table_refuses_two_turns_in_one_place_in_a_conversation() {
    String conversation = open();
    turns.record(conversation, "one", "first", Ending.ANSWERED, null, null, null);

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert(conversation, 1, "also one", "also first", Ending.ANSWERED.name(), null));
    assertTrue(
        refused.getMessage().contains("turns_one_per_place_in_a_conversation"),
        refused.getMessage());
  }

  @Test
  void the_table_refuses_a_turn_numbered_before_the_first() {
    String conversation = open();

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert(conversation, 0, "said", "answered", Ending.ANSWERED.name(), null));
    assertTrue(refused.getMessage().contains("turns_are_numbered_from_one"), refused.getMessage());
  }

  @Test
  void the_table_refuses_an_utterance_nobody_said() {
    String conversation = open();

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert(conversation, 1, "", "answered", Ending.ANSWERED.name(), null));
    assertTrue(refused.getMessage().contains("turns_utterance_is_not_blank"), refused.getMessage());
  }

  /**
   * An ending written successfully and unreadable for ever is the failure V5 refuses for {@code
   * memory_reasons.kind}, one table over: {@code Ending.valueOf} would raise on the way back out,
   * so the row could be stored and never read.
   */
  @Test
  void the_table_refuses_an_ending_this_server_cannot_read_back() {
    String conversation = open();

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert(conversation, 1, "said", "answered", "FINISHED", null));
    assertTrue(refused.getMessage().contains("turns_ending_is_known"), refused.getMessage());
  }

  /**
   * A turn that stopped carries the account of how far it got, and a turn that answered may have
   * answered with nothing.
   *
   * <p>Both halves are {@code Outcome}'s own rule rather than this table's invention: for every
   * ending but {@code ANSWERED} the text "is a sentence this runtime wrote, naming the ending and
   * listing the tools the run called in order", and for {@code ANSWERED} an empty string is "a
   * model that stops on its first token", which is a decision and not a failure.
   *
   * <p>Standing check 2: the accepted side is written through the store, which knows nothing of the
   * constraint, rather than as a restatement of the constraint's own predicate.
   */
  @Test
  void a_turn_that_stopped_says_how_far_it_got_and_one_that_answered_may_say_nothing() {
    String conversation = open();

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insert(conversation, 1, "go on", "", Ending.TURN_CAP.name(), null));
    assertTrue(
        refused.getMessage().contains("turns_a_turn_that_stopped_says_how"), refused.getMessage());

    TurnRecord silent = turns.record(conversation, "go on", "", Ending.ANSWERED, null, null, null);
    assertEquals(
        "",
        silent.answer(),
        "a model that answered with nothing has answered, and the row says so");
    assertEquals("", turns.forConversation(conversation).get(0).answer());
  }

  // --- an id that cannot be named -------------------------------------------

  /**
   * Both ways in, and the read matters more than the write.
   *
   * <p>A dropped id reaching {@link TurnStore#forConversation} would otherwise come back as an
   * empty history — a conversation that has said nothing, which is a real state and would be
   * indistinguishable from this bug. The write is refused by the foreign key in any case; the read
   * has nothing else to catch it.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t"})
  void an_id_that_cannot_be_named_is_refused_on_every_way_in(String unusable) {
    assertThrows(ValidationException.class, () -> turns.forConversation(unusable));
    assertThrows(
        ValidationException.class,
        () -> turns.record(unusable, "said", "answered", Ending.ANSWERED, null, null, null));
  }

  // --- which agent answered ---------------------------------------------------

  /**
   * The column {@code POST /v1/conversations/&#123;id&#125;/resume} reads instead of taking an
   * agent from its caller.
   *
   * <p>A resumption opens with the stopped run's whole history — every tool result it collected, in
   * the shape that agent's own {@code tools:} line produced — so continuing it as a different agent
   * puts one agent's working in front of another. Nothing refused that while the name came from the
   * request, because the request named an agent, the agent existed, and the run started.
   */
  @Test
  void a_turn_records_which_agent_answered_it() {
    String conversation = open();

    turns.record(
        conversation,
        "why did it stall?",
        "it waited on a lock",
        Ending.ANSWERED,
        900,
        "interlocutor",
        null);

    assertEquals("interlocutor", turns.forConversation(conversation).get(0).agent());
  }

  /**
   * NULL is "written before {@code V17__conversation_origin.sql}" and is not an unknown to be
   * filled in.
   *
   * <p>Nothing anywhere records which agent answered those turns — {@code entries} does not carry
   * it either, and {@code JobStore} mints job ids from a per-process counter that restarts on every
   * boot — so there was no honest backfill and a store that refused null here could not write the
   * shape its own table already holds.
   */
  @Test
  void a_turn_that_does_not_know_which_agent_answered_says_nothing_rather_than_guessing() {
    String conversation = open();

    turns.record(
        conversation, "and before that?", "the log rotated", Ending.ANSWERED, 900, null, null);

    assertNull(turns.forConversation(conversation).get(0).agent());
  }

  /**
   * Blank is not an agent and it is not "no agent" either — the empty string is what an omitted
   * field arrives as, and a row holding one names an agent nothing can look up.
   */
  @Test
  void the_table_refuses_a_turn_whose_agent_cannot_be_named() {
    String conversation = open();

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> turns.record(conversation, "said", "answered", Ending.ANSWERED, null, "", null));

    assertTrue(refused.getMessage().contains("turns_agent_is_named"), refused.getMessage());
  }

  // --- the name the first turn gives the conversation ------------------------

  /**
   * The first turn names the conversation, and the second does not rename it.
   *
   * <p><b>This is the point of the whole statement.</b> {@code ConversationStore.nameFromFirstTurn}
   * is {@code UPDATE ... WHERE id = ? AND title IS NULL}, so "the first turn names it" is true
   * without this store ever knowing which turn it is writing: a second turn's update matches no
   * row. A read-then-write would have to know the ordinal, would have a window between the two
   * halves, and would rename a conversation the moment two turns landed together.
   *
   * <p>Driven through {@link TurnStore#record} rather than through the write directly, because that
   * is what makes the naming a property of the archive and not of one caller of it: every path that
   * records a turn goes through here, and a conversation whose first turn was written by some other
   * caller would otherwise stay nameless for ever.
   */
  @Test
  void the_first_turn_names_the_conversation_and_the_second_does_not_rename_it() {
    String conversation = open();

    turns.record(
        conversation,
        "# why did the deploy roll back?\r\n\r\nhere is the log",
        "the migration ran twice",
        Ending.ANSWERED,
        900,
        "talker",
        null);

    assertEquals(
        "why did the deploy roll back?",
        titleOf(conversation),
        "the first turn is what names the conversation");

    turns.record(
        conversation,
        "and what fixed it?",
        "a second migration",
        Ending.ANSWERED,
        900,
        "talker",
        null);

    assertEquals(
        "why did the deploy roll back?",
        titleOf(conversation),
        "a title is what the conversation opened with; a second turn renames nothing");
  }

  /**
   * A turn that said nothing but whitespace leaves the conversation unnamed.
   *
   * <p><b>And the turn is still written</b>, which is the half worth asserting separately: a title
   * that could not be derived is not a reason to lose the record of what was said. {@code
   * turns_utterance_is_not_blank} refuses the empty string and nothing else, so this row is one the
   * table holds.
   */
  @Test
  void a_turn_that_said_nothing_but_whitespace_leaves_the_conversation_unnamed() {
    String conversation = open();

    TurnRecord written =
        turns.record(
            conversation,
            "  \r\n   \n  ",
            "nothing to answer",
            Ending.ANSWERED,
            900,
            "talker",
            null);

    assertEquals(1, written.ordinal(), "the turn is the work and it is written regardless");
    assertNull(
        titleOf(conversation),
        "no name is a fact a client can render; a blank name is a row nobody can read");
  }

  /**
   * A conversation nobody has spoken into has no name yet, and that is an ordinary state rather
   * than a gap -- the same one {@code last_turn_at} carries one column over.
   */
  @Test
  void a_conversation_nobody_has_spoken_into_has_no_name_yet() {
    assertNull(titleOf(open()));
  }

  // --- fixtures -------------------------------------------------------------

  private String open() {
    return conversations.open(PAYMENTS, Budget.of(20)).id();
  }

  /**
   * Read straight out of the column, so the assertion is about what Postgres holds rather than
   * about what any store chose to hand back.
   */
  private static String titleOf(String conversation) {
    return jdbc.queryForObject(
        "SELECT title FROM conversations WHERE id = ?", String.class, conversation);
  }

  /**
   * A row written the way this store cannot write one — an ordinal it did not choose, an ending it
   * cannot name — so each CHECK is driven by the situation it exists for.
   */
  private static void insert(
      String conversation,
      int ordinal,
      String utterance,
      String answer,
      String ending,
      Integer promptTokens) {
    jdbc.update(
        "INSERT INTO turns"
            + " (conversation_id, ordinal, utterance, answer, ending, prompt_tokens)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        conversation,
        ordinal,
        utterance,
        answer,
        ending,
        promptTokens);
  }

  // --- the block a turn went out with ---------------------------------------

  /**
   * Two turns that sent the same block store one row between them.
   *
   * <p><b>The measurement V32 was written for, driven rather than asserted in prose.</b> A shipped
   * prompt is ~5 kB and an operator edits it rarely, so the alternative — the text on every {@code
   * turns} row — puts about a megabyte of byte-identical bytes into a 200-turn conversation. This
   * is what fails if {@link TurnStore#remember} ever becomes an unconditional insert or grows a
   * per-turn salt: the count goes from one to two and the whole argument for the table's shape is
   * gone.
   *
   * <p><b>Both turns still read back the same block</b>, which is the half a count alone would not
   * catch: sharing a row must not cost either turn its own answer.
   */
  @Test
  void two_turns_sending_the_same_block_store_it_once() {
    String conversation = open();
    String block = "You are the interlocutor. You do one thing.";

    String first = turns.remember(block);
    turns.record(
        conversation, "what broke it?", "a retry loop", Ending.ANSWERED, 900, "talker", first);
    String second = turns.remember(block);
    turns.record(conversation, "and now?", "it holds", Ending.ANSWERED, 1_100, "talker", second);

    assertEquals(
        first,
        second,
        "a block is named by its own bytes, so the same text sent twice is the same"
            + " name and not a second one");
    assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM system_blocks", Integer.class),
        "a block is written once however many turns send it -- writing it per turn is"
            + " the megabyte of identical text V32 exists to refuse");
    assertEquals(Optional.of(block), turns.blockSentAt(conversation, 1));
    assertEquals(
        Optional.of(block),
        turns.blockSentAt(conversation, 2),
        "sharing one row must not cost the second turn its own answer");
  }

  /**
   * The case the whole task exists for: the agent's prompt is edited between two turns, and each
   * turn still reports what it was actually sent.
   *
   * <p>Before V32 both turns were answered with whatever the file said at the moment somebody read
   * them, so the first turn's projection changed every time an operator touched the agent —
   * silently, on the screen whose entire purpose is auditing what happened.
   */
  @Test
  void a_turn_that_went_out_with_a_different_block_keeps_its_own() {
    String conversation = open();
    String before = "You are the interlocutor. You do one thing.";
    String after = "You are the interlocutor. You do one thing, and you cite sources.";

    turns.record(
        conversation,
        "what broke it?",
        "a retry loop",
        Ending.ANSWERED,
        900,
        "talker",
        turns.remember(before));
    turns.record(
        conversation,
        "and now?",
        "it holds, with a citation",
        Ending.ANSWERED,
        1_100,
        "talker",
        turns.remember(after));

    assertEquals(
        Optional.of(before),
        turns.blockSentAt(conversation, 1),
        "the first turn was sent the file as it stood then, and editing the file"
            + " afterwards is not a thing that happened to that turn");
    assertEquals(Optional.of(after), turns.blockSentAt(conversation, 2));
    List<TurnRecord> read = turns.forConversation(conversation);
    assertNotEquals(
        read.get(0).systemBlock(),
        read.get(1).systemBlock(),
        "two different blocks are two different names, which is what makes the two"
            + " turns distinguishable at all");
    assertEquals(
        2,
        jdbc.queryForObject("SELECT count(*) FROM system_blocks", Integer.class),
        "an edited file is a second block and not an overwrite of the first: the row a"
            + " turn already points at is the record of what that turn was shown");
  }

  /**
   * A turn with no recorded block reports that it has none, and never today's text.
   *
   * <p><b>The honest answer for every row already in the archive.</b> Every turn written before
   * {@code V32__turn_system_prompt.sql} has a NULL here and always will: nothing anywhere holds the
   * prompt text those turns carried, and the agent's file today is a file today. The row written
   * here with a null block is exactly the shape those rows have — the migration test {@code
   * SystemBlockIsNotBackfilledTest} is what proves V32 leaves a real populated table in it — and
   * what this pins is that the store answers "not recorded" for it rather than filling the gap from
   * anywhere.
   *
   * <p>{@code Optional.empty()} and not the current definition, which the store could not reach
   * even if it wanted to: {@link TurnStore} holds no registry and takes no {@code AgentDefinition},
   * which is what makes the wrong answer unavailable rather than merely not chosen.
   */
  @Test
  void a_turn_written_before_this_column_existed_reports_no_block_rather_than_todays() {
    String conversation = open();
    String todays = "You are the interlocutor, as the file reads this morning.";
    turns.remember(todays);

    turns.record(
        conversation, "what broke it?", "a retry loop", Ending.ANSWERED, 900, "talker", null);

    assertEquals(
        Optional.empty(),
        turns.blockSentAt(conversation, 1),
        "a turn that recorded no block did not go out with today's file, and saying it"
            + " did is the one assertion this column exists to stop");
    assertNull(
        turns.forConversation(conversation).get(0).systemBlock(),
        "not recorded is a null and not a blank: a blank would read as a turn sent an"
            + " empty system message, which is a different thing that was sent");
  }

  /**
   * A block nothing wrote is not a block a turn can point at.
   *
   * <p>{@code turns_a_recorded_block_is_one_this_table_holds}, driven by name. The failure it stops
   * is a caller that hashed the text and skipped {@link TurnStore#remember}: the row would carry a
   * name for a block nobody stored, and the projection would read back as "not recorded" for ever
   * with the column looking full.
   */
  @Test
  void a_turn_cannot_name_a_block_nothing_stored() {
    String conversation = open();
    String hashedAndNeverStored =
        "0000000000000000000000000000000000000000000000000000000000000000";

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                turns.record(
                    conversation,
                    "said",
                    "answered",
                    Ending.ANSWERED,
                    900,
                    "talker",
                    hashedAndNeverStored));

    assertTrue(
        refused.getMessage().contains("turns_a_recorded_block_is_one_this_table_holds"),
        "the rule that broke should be the rule by name: " + refused.getMessage());
  }

  /**
   * A turn that sent no system message has no block to remember, and the store refuses to write a
   * row saying it sent an empty one.
   *
   * <p>{@code JobRuntime.oneSystemMessageFirst} sends no system message at all when there is no
   * system text — "a blank system turn is not the same input as no system turn, and a small model
   * notices" — so a {@code system_blocks} row holding the empty string would be the record of a
   * message that was never sent. The absence is {@code turns.system_block IS NULL}, one column
   * over.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void a_turn_that_sent_no_system_message_has_no_block_to_remember(String nothing) {
    assertThrows(ValidationException.class, () -> turns.remember(nothing));

    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM system_blocks", Integer.class));
  }
}
