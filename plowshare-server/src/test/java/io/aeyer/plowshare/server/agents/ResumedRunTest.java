package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A run that stopped for want of allowance is given more and carries on.
 *
 * <h2>What is real here</h2>
 *
 * <p>A real Postgres, the real {@code entries}, {@code turns} and {@code conversations} tables, a
 * real {@link JobStore} on real virtual threads, a real {@link Turn} and a real {@link Compaction}.
 * The model is a script and the one tool is a counter, which is what makes "the tool did not run
 * twice" a thing this file can assert rather than describe.
 *
 * <h2>The five things that must not happen</h2>
 *
 * <p>Each has a test, because every one of them would leave a conversation that still looked right:
 *
 * <ul>
 *   <li><b>tool calls must not re-run.</b> The resumed run opens with the results in hand — {@link
 *       #a_resumed_run_does_not_call_again_what_the_stopped_run_already_called};
 *   <li><b>the utterance must not be appended twice.</b> It is already in the log and the resumed
 *       turn has none of its own — {@link
 *       #the_utterance_is_in_the_log_once_however_often_a_run_is_continued};
 *   <li><b>spending must not be double-counted.</b> The stopped run's calls are already on the row
 *       — {@link #a_resumed_run_adds_its_own_spending_and_does_not_replay_the_stopped_runs};
 *   <li><b>a resumed run must not resume itself.</b> Each grant is a person's decision — {@link
 *       #a_run_that_stops_again_stops_again_and_asks_again};
 *   <li><b>a dangling tool call must never reach the model.</b> Not reachable for the endings in
 *       scope, which is <em>why</em> they are the scope, and asserted rather than trusted — {@link
 *       #a_resumed_run_is_sent_no_call_that_nothing_answers}.
 * </ul>
 *
 * <h2>And the decision that was hardest</h2>
 *
 * <p><b>A resumed run is a new turn.</b> {@link
 * #a_resumed_run_is_a_new_turn_and_the_stopped_one_keeps_its_ending} is what fails if it ever
 * re-enters the stopped turn's ordinal: that turn's row already carries how it ended, {@code
 * turns_a_turn_that_stopped_says_how} exists to pin exactly that, and every per-turn measurement
 * keys off the ordinal.
 */
@Testcontainers
class ResumedRunTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home PAYMENTS = Home.of("payments");

  /**
   * Roomy enough that nothing in this file folds: resumption and compaction are different subjects
   * and a fold in the middle of one of these fixtures would make the history a second thing to
   * reason about.
   */
  private static final int ROOMY_CONTEXT = 1_000_000;

  private static final String AGENT_PROMPT = "You talk.";

  private static final String TOOL = "look";

  private static final String WHAT_THE_TOOL_SAID = "the deploy script says: set -e";

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private TurnStore turns;
  private CompactionStore compactions;
  private EntryStore entries;

  private final List<JobStore> opened = new ArrayList<>();
  private final List<Compaction> folding = new ArrayList<>();
  private final FoldsOnTheirOwnThreads onTheirOwnThreads = new FoldsOnTheirOwnThreads();

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void freshConversations() {
    // board_topics, board_messages and board_seats: V74 gave each a foreign
    // key into conversations. firings: a further hop out, through its V74
    // foreign key into board_topics. user_inbox: a hop past that, through
    // its pre-existing V40 foreign key into firings. Postgres refuses to
    // truncate conversations unless every one of these goes with it.
    jdbc.execute(
        "TRUNCATE TABLE board_message_route_bindings, outgoing_work, message_external_tasks, message_external_contexts, board_message_routes, board_message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
    conversations = new ConversationStore(jdbc);
    turns = new TurnStore(jdbc);
    compactions = new CompactionStore(jdbc);
    entries = new EntryStore(jdbc);
  }

  @AfterEach
  void stopEveryRun() throws InterruptedException {
    onTheirOwnThreads.join();
    opened.forEach(JobStore::close);
    opened.clear();
    folding.forEach(Compaction::close);
    folding.clear();
  }

  // --- what a resumed run opens with ---------------------------------------------------

  /**
   * The job a resumed run starts names the conversation it continues, and that conversation's
   * origin is a turn's — same as an ordinary one.
   *
   * <p>The only production coverage of this door's threading through {@code JobStore.submit}:
   * {@code AgentControllerTest} mocks {@code JobStore} entirely, so it proves only that {@code
   * JobView.of} copies a field a {@code JobAccess} fixture put there by hand. This is the real
   * {@code Transcript.conversationId()} read off a real {@code Compaction.ResumedTranscript},
   * delegating to the {@code TurnTranscript} it wraps.
   */
  @Test
  void a_resumed_runs_job_carries_the_conversation_it_speaks_into() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();

    fixture.answersNext();
    Job job = fixture.jobFor(conversation, TurnCap.of(4), null);

    assertEquals(
        conversation,
        job.conversation(),
        "a resumed run is a new turn in the SAME conversation, not a new one");
    assertEquals(Origin.TURN, job.conversationOrigin());
  }

  /**
   * A resumed run opens with what the stopped run learned.
   *
   * <p>The first run reads a file and stops at its cap. The second is handed the same conversation
   * and has to find that file's contents already in front of it — otherwise the grant buys the same
   * read again.
   */
  @Test
  void a_resumed_run_opens_with_what_the_stopped_run_learned() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();

    fixture.model.forget();
    fixture.answersNext();
    fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    assertTrue(
        fixture.model.firstPrompt().stream()
            .anyMatch(
                message ->
                    message.role() == ChatMessage.Role.TOOL
                        && WHAT_THE_TOOL_SAID.equals(message.content())),
        "a resumed run that cannot see what its tools returned will read the same"
            + " files again: "
            + fixture.model.firstPrompt());
  }

  /**
   * A resumed run does not call again what the stopped run already called.
   *
   * <p>The tool counts its own invocations, so this is a measurement rather than an inference from
   * the prompt. The script asks for the tool only when nothing has answered one yet, which is
   * exactly what a model with the result already in front of it does.
   */
  @Test
  void a_resumed_run_does_not_call_again_what_the_stopped_run_already_called() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();
    int calledByTheStoppedRun = fixture.calls.get();

    fixture.answersNext();
    fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    assertEquals(
        calledByTheStoppedRun,
        fixture.calls.get(),
        "the resumed run opened with the result in hand and asked for it again anyway");
  }

  /**
   * A resumed run is sent no call that nothing answers.
   *
   * <p>The endings resumption accepts stop at the top of {@code JobRuntime}'s loop, so the previous
   * iteration's results are already appended and there is nothing for {@code
   * Projection.NEVER_COMPLETED} to stand in for. <b>The scope is asserted rather than trusted</b>:
   * an ending that stopped mid-turn would show up here.
   */
  @Test
  void a_resumed_run_is_sent_no_call_that_nothing_answers() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();

    fixture.model.forget();
    fixture.answersNext();
    fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    assertFalse(
        fixture.model.firstPrompt().stream()
            .anyMatch(m -> Projection.NEVER_COMPLETED.equals(m.content())),
        "a run stopped at a turn boundary left no call unanswered: " + fixture.model.firstPrompt());
  }

  /**
   * The run is told when the original stopped, and that what it read dates from then.
   *
   * <p>A conversation resumed a week later has tool results describing a disk that may have moved.
   * The harness reports that rather than guessing on the model's behalf, which is the pattern this
   * project already follows.
   */
  @Test
  void a_resumed_run_is_told_when_the_run_it_continues_stopped() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();
    String stoppedAt = conversations.find(conversation).orElseThrow().lastTurnAt().toString();

    fixture.model.forget();
    fixture.answersNext();
    fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    List<ChatMessage> sent = fixture.model.firstPrompt();
    ChatMessage last = sent.get(sent.size() - 1);
    assertEquals(
        ChatMessage.Role.USER,
        last.role(),
        "the note the run opens with is delivered where the run reads: " + sent);
    assertTrue(
        last.content().contains(stoppedAt),
        "the note has to say when the run it continues stopped: " + last.content());
  }

  /**
   * The note is delivered in the run and never again.
   *
   * <p>It is recorded as a {@code runtime_note}, which is the kind {@code JobRuntime.Repeats}'
   * nudge already uses for the same reason: it was delivered in-run and the model read it, so
   * projecting it would put it in front of the model a second time, in a later turn, about a
   * resumption that happened turns ago.
   */
  @Test
  void the_note_a_resumed_run_opens_with_does_not_reach_a_later_turn() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();
    fixture.answersNext();
    fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    assertEquals(
        1,
        entriesOfKind(conversation, EntryKind.RUNTIME_NOTE).size(),
        "the message a resumed run opens with is the harness speaking, and it is"
            + " recorded as one: "
            + entries.forConversation(conversation));

    fixture.model.forget();
    fixture.answersNext();
    fixture.speakAndWait(conversation, "and what about the rollback");

    assertFalse(
        fixture.model.firstPrompt().stream()
            .anyMatch(message -> message.content().contains("is being continued")),
        "a later turn was told again about a resumption that is over: "
            + fixture.model.firstPrompt());
  }

  // --- what must not happen -------------------------------------------------------------

  /**
   * The utterance is in the log once, however often a run is continued.
   *
   * <p>It is already there, at the turn that carried it. A resumed run has no utterance of its own
   * — the same question is being answered, not a new one — so what it opens with is recorded as
   * what it is.
   */
  @Test
  void the_utterance_is_in_the_log_once_however_often_a_run_is_continued() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();

    fixture.resumeAndWait(conversation, TurnCap.of(1), null);
    fixture.answersNext();
    fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    assertEquals(
        List.of(Fixture.THE_QUESTION),
        entriesOfKind(conversation, EntryKind.UTTERANCE).stream()
            .map(EntryRecord::content)
            .toList(),
        "one question was asked and it is written down once");
  }

  /**
   * A resumed run adds its own spending and does not replay the stopped run's.
   *
   * <p>The stopped run's model calls are already in {@code conversations.budget_spent}. Resumption
   * adds to the allowance; it does not re-do the accounting.
   */
  @Test
  void a_resumed_run_adds_its_own_spending_and_does_not_replay_the_stopped_runs() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();
    int spentByTheStoppedRun = conversations.find(conversation).orElseThrow().budget().spent();

    fixture.answersNext();
    Outcome resumed = fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    assertEquals(
        spentByTheStoppedRun + resumed.modelCalls(),
        conversations.find(conversation).orElseThrow().budget().spent(),
        "the conversation's spending is what the stopped run spent plus what the"
            + " resumed one spent, and nothing is counted twice");
  }

  /**
   * A resumed run is a new turn, and the stopped one keeps its ending.
   *
   * <p><b>The decision this whole design turns on.</b> Re-entering the stopped turn's ordinal would
   * mean rewriting the one table that records how a turn ended, which an append-only log must not
   * do; and {@code sent}, {@code added} and a fold's reach all key off {@code turn_ordinal}, so a
   * turn measured twice breaks the compaction trigger and the fold reach together.
   */
  @Test
  void a_resumed_run_is_a_new_turn_and_the_stopped_one_keeps_its_ending() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();

    fixture.answersNext();
    fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    List<TurnRecord> spoken = turns.forConversation(conversation);
    assertEquals(2, spoken.size(), "a resumed run takes a new turn ordinal: " + spoken);
    assertEquals(
        Ending.TURN_CAP,
        spoken.get(0).ending(),
        "the stopped turn's row already said how it ended, and an append-only log does"
            + " not rewrite that");
    assertEquals(Ending.ANSWERED, spoken.get(1).ending());
    assertEquals(
        List.of("an answer"),
        entries.forConversation(conversation).stream()
            .filter(entry -> entry.turnOrdinal() == 2)
            .filter(entry -> entry.kind() == EntryKind.ANSWER)
            .map(EntryRecord::content)
            .toList(),
        "the resumed turn's own entries are filed against the resumed turn");
    assertEquals(
        List.of(),
        entries.forConversation(conversation).stream()
            .filter(entry -> entry.turnOrdinal() == 2)
            .filter(entry -> entry.kind() == EntryKind.UTTERANCE)
            .toList(),
        "the resumed turn has no utterance of its own: the question is on turn 1");
  }

  /**
   * A run that stops again stops again, and asks again.
   *
   * <p>Nothing resumes itself. A grant is a person's decision, so a resumed run that reaches its
   * new cap ends at it and waits for another — it does not start a third turn on its own.
   */
  @Test
  void a_run_that_stops_again_stops_again_and_asks_again() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();

    Outcome resumed = fixture.resumeAndWait(conversation, TurnCap.of(1), null);

    assertEquals(
        Ending.TURN_CAP,
        resumed.ending(),
        "the resumed run had one turn and used it without answering");
    assertEquals(
        2,
        turns.forConversation(conversation).size(),
        "nothing started a third turn: a grant is a person's decision");
  }

  // --- which endings may be continued ---------------------------------------------------

  /** A conversation whose last turn answered has nothing to continue. */
  @Test
  void a_conversation_that_reached_an_answer_is_not_resumed() {
    Fixture fixture = new Fixture();
    String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
    fixture.answersNext();
    fixture.speakAndWait(conversation, Fixture.THE_QUESTION);

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.resume(conversation, fixture.definition, null, TurnCap.of(4), null));

    assertTrue(refused.getMessage().contains("ANSWERED"), refused.getMessage());
  }

  /**
   * A run that was stopped for repeating itself is not continued.
   *
   * <p>{@code Repeats} ended it because it kept asking for the same call. Allowance was not the
   * constraint, so granting more would buy the same loop again — which is offering to waste the
   * grant.
   */
  @Test
  void a_run_that_was_stopped_for_repeating_itself_is_not_continued() {
    Fixture fixture = new Fixture();
    String conversation = aConversationWhoseLastTurnEnded(Ending.STUCK);

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.resume(conversation, fixture.definition, null, TurnCap.of(4), null));

    assertTrue(refused.getMessage().contains("STUCK"), refused.getMessage());
  }

  /**
   * A run stopped for writing its calls as text is not continued: allowance was not what it lacked
   * (spec 2026-09-28-call-failures §4), so a grant would buy the same failure again.
   */
  @Test
  void a_run_that_kept_writing_calls_as_text_is_not_continued() {
    Fixture fixture = new Fixture();
    String conversation = aConversationWhoseLastTurnEnded(Ending.CALL_FAILURES);

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.resume(conversation, fixture.definition, null, TurnCap.of(4), null));

    assertTrue(refused.getMessage().contains("CALL_FAILURES"), refused.getMessage());
    assertTrue(refused.getMessage().contains("as text"), refused.getMessage());
  }

  /**
   * A run that died mid-turn is not continued yet.
   *
   * <p>{@code UNAVAILABLE}, {@code SUB_AGENT_FAILED} and {@code SESSION_GONE} can stop inside a
   * tool call and leave a declared call with no result. That is a real shape and it needs its own
   * handling; it is scoped out deliberately rather than left to be discovered.
   */
  @Test
  void a_run_that_died_inside_a_turn_is_not_continued_yet() {
    Fixture fixture = new Fixture();
    for (Ending midTurn :
        List.of(Ending.UNAVAILABLE, Ending.SUB_AGENT_FAILED, Ending.SESSION_GONE)) {
      String conversation = aConversationWhoseLastTurnEnded(midTurn);

      Turn.Refused refused =
          assertThrows(
              Turn.Refused.class,
              () ->
                  fixture.turn.resume(conversation, fixture.definition, null, TurnCap.of(4), null),
              midTurn + " can leave a call with no result and must not be resumed yet");

      assertTrue(refused.getMessage().contains(midTurn.name()), refused.getMessage());
    }
  }

  /**
   * A cancelled run may be continued on request.
   *
   * <p>Mechanically it stops at the same loop boundary as the other two, so refusing it would be an
   * invented restriction. The console never offers it — the person asked it to stop — but the
   * endpoint does not pretend it cannot be done.
   */
  @Test
  void a_cancelled_run_may_be_continued_on_request() {
    Fixture fixture = new Fixture();
    String conversation = aConversationWhoseLastTurnEnded(Ending.CANCELLED);

    fixture.answersNext();
    Outcome resumed = fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    assertEquals(Ending.ANSWERED, resumed.ending());
  }

  /** A conversation nobody has spoken into has no run to continue. */
  @Test
  void a_conversation_nobody_has_spoken_into_has_no_run_to_continue() {
    Fixture fixture = new Fixture();
    String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();

    assertThrows(
        Turn.Refused.class,
        () -> fixture.turn.resume(conversation, fixture.definition, null, TurnCap.of(4), null));
  }

  // --- the grant ------------------------------------------------------------------------

  /**
   * A run that spent the conversation's whole budget is continued by granting more of it.
   *
   * <p>Turns are the primary grant and a resumed run gets a new cap of its own; model calls are the
   * conversation's, shared by reference, so raising them raises the row's total. Without the raise
   * there is nothing left for the resumed run to spend and it is refused, which is the same refusal
   * any utterance meets.
   */
  @Test
  void a_run_that_spent_the_whole_budget_is_continued_by_granting_more_of_it() {
    Fixture fixture = new Fixture();
    String conversation = conversations.open(PAYMENTS, Budget.of(2)).id();
    fixture.neverAnswers();
    // A cap well above the budget, so it is the budget that bites: the loop
    // checks the cap first, and this test is about the other limit.
    Outcome stopped = fixture.speakAndWait(conversation, Fixture.THE_QUESTION, TurnCap.of(10));
    assertEquals(
        Ending.CALL_BUDGET,
        stopped.ending(),
        "this fixture is meant to run out of model calls, not turns");

    assertThrows(
        Turn.Refused.class,
        () -> fixture.turn.resume(conversation, fixture.definition, null, TurnCap.of(4), null),
        "a conversation with nothing left to spend cannot be continued for free");

    fixture.answersNext();
    Outcome resumed = fixture.resumeAndWait(conversation, TurnCap.of(4), 8);

    assertEquals(Ending.ANSWERED, resumed.ending());
    assertEquals(
        8,
        conversations.find(conversation).orElseThrow().budget().limit(),
        "the grant raises the conversation's total, which is where a budget lives");
  }

  /**
   * A budget may not be granted below what has already been spent.
   *
   * <p>The identical refusal {@code POST /v1/jobs/&#123;id&#125;/limits} applies, for the identical
   * reason: {@code conversations_spent_within_budget} would refuse the write-back and the
   * conversation would lose the record of what it spent. One accounting story, reused.
   *
   * <p>A {@link CallerFault} and not a {@link Turn.Refused}, which is the same split {@code
   * Budget.of} and {@code TurnCap.of} already have: a number the caller can correct is a 400 at the
   * door, where a conversation that cannot take this turn is a 409 about the row.
   *
   * <p><b>And {@code CallerFault} rather than the {@code IllegalArgumentException} this used to
   * be.</b> {@code Faults} maps the first to 400 for both surfaces and has no row for the second at
   * all, so the old type was a 400 only while {@code ConversationController.resume} was there to
   * translate it — a frame handler calling {@code Turn.resume} would have been answered 500.
   */
  @Test
  void a_grant_may_not_put_a_budget_below_what_has_been_spent() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();
    int spent = conversations.find(conversation).orElseThrow().budget().spent();

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () ->
                fixture.turn.resume(
                    conversation, fixture.definition, null, TurnCap.of(4), spent - 1));

    assertTrue(refused.getMessage().contains(String.valueOf(spent)), refused.getMessage());
    assertEquals(
        spent,
        conversations.find(conversation).orElseThrow().budget().spent(),
        "a refused grant changes nothing");
  }

  // --- provenance -------------------------------------------------------------------------

  /**
   * A resumption says so in the conversation, at the place it happened.
   *
   * <p>A {@code diagnostic} entry: the kind carries a NULL role and never reaches a model, and it
   * is the only record that the machinery acted on this conversation at a particular moment. No
   * column and no migration — what happened is prose about the machinery, addressed to whoever
   * reads the conversation back.
   */
  @Test
  void a_resumption_is_written_into_the_conversation_it_resumed() {
    Fixture fixture = new Fixture();
    String conversation = fixture.aRunThatStoppedAtItsCap();

    fixture.answersNext();
    fixture.resumeAndWait(conversation, TurnCap.of(4), null);

    List<EntryRecord> noted = entriesOfKind(conversation, EntryKind.DIAGNOSTIC);
    assertEquals(1, noted.size(), "expected one resumption to have said so: " + noted);
    assertEquals(2, noted.get(0).turnOrdinal(), "it belongs to the turn that did the resuming");
    assertTrue(
        noted.get(0).content().contains("turn 1"),
        "it has to say which turn it continues: " + noted.get(0).content());
    assertFalse(
        noted.get(0).kind().projects(),
        "the harness talking about a conversation is not part of the conversation");
  }

  // --- fixtures ---------------------------------------------------------------------------

  private List<EntryRecord> entriesOfKind(String conversation, EntryKind kind) {
    return entries.forConversation(conversation).stream()
        .filter(entry -> entry.kind() == kind)
        .toList();
  }

  /**
   * A conversation whose one turn ended the way the test needs it to.
   *
   * <p>Written rather than run, because three of the endings above cannot be reached from a script
   * without building the failure that produces each one — and what is being tested is the decision
   * taken from the ending, not the route to it. The entries are the shape a real stopped turn
   * leaves: {@code JobRuntime} records the utterance, and {@code Turn.closeTheLog} records the
   * attempt and the sentence the runtime wrote.
   */
  private String aConversationWhoseLastTurnEnded(Ending ending) {
    String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
    String answer = "This run ended " + ending + " without reaching an answer.";
    entries.append(
        conversation, 1, LoggedEntry.utterance(Fixture.THE_QUESTION, Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.attemptFailed(ending, answer));
    entries.append(conversation, 1, LoggedEntry.answer(answer, List.of()));
    turns.record(conversation, Fixture.THE_QUESTION, answer, ending, 40, null, null);
    conversations.turnEnded(conversation, Budget.resumed(20, 1));
    return conversation;
  }

  /**
   * A conversation that can be spoken into and continued, wired the way production wires one apart
   * from the transport and the one tool.
   */
  private final class Fixture {

    static final String THE_QUESTION = "what is in the deploy script";

    private final JobStore jobs;
    private final Turn turn;
    private final Script model = new Script();
    private final AtomicInteger calls = new AtomicInteger();
    private final AgentDefinition definition =
        new AgentDefinition(
            "talker",
            "a fixture agent",
            "fast",
            List.of(TOOL),
            List.of(),
            List.of(),
            2,
            20,
            AGENT_PROMPT);

    Fixture() {
      LlmDispatcher models =
          new LlmDispatcher(
              List.of(
                  new LlmPool(
                      "scripted",
                      List.of("model-fast"),
                      Map.of("fast", "model-fast"),
                      4,
                      1,
                      Duration.ofSeconds(5),
                      model)),
              new NoOpTokenLedger());
      JobRuntime runtime =
          new JobRuntime(
              models,
              List.of(new Counted()),
              null,
              (home, grants, sessionId, owner) -> List.<FileProvider>of());
      this.jobs = new JobStore(runtime);
      Compaction compaction =
          new Compaction(
              models,
              ResumedRunTest::folder,
              turns,
              compactions,
              entries,
              ROOMY_CONTEXT,
              Runnable::run);
      this.turn = new Turn(jobs, conversations, turns, compaction);
      opened.add(jobs);
      folding.add(compaction);
    }

    /**
     * A conversation whose one run read a file and stopped at its cap, which is the shape
     * resumption is for.
     *
     * <p>The definition caps at two turns and the script never answers, so the run reads the file
     * on its first turn and stops at the top of the third pass — after the results of the second
     * are appended, which is what makes the history whole.
     */
    String aRunThatStoppedAtItsCap() {
      String conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
      neverAnswers();
      Outcome stopped = speakAndWait(conversation, THE_QUESTION);
      assertEquals(
          Ending.TURN_CAP,
          stopped.ending(),
          "this fixture is meant to run out of turns: " + stopped.text());
      return conversation;
    }

    Fixture answersNext() {
      model.answers = true;
      return this;
    }

    Fixture neverAnswers() {
      model.answers = false;
      return this;
    }

    Outcome speakAndWait(String conversation, String utterance) {
      return waitFor(turn.speak(conversation, definition, utterance, null));
    }

    Outcome speakAndWait(String conversation, String utterance, TurnCap cap) {
      return waitFor(
          turn.speak(
              conversation, definition, utterance, null, cap, Speaker.person(null), outcome -> {}));
    }

    Outcome resumeAndWait(String conversation, TurnCap cap, Integer maxModelCalls) {
      return waitFor(turn.resume(conversation, definition, null, cap, maxModelCalls));
    }

    /**
     * The job handle itself, once it is done — for a test that reads {@link Job#conversation()} or
     * {@link Job#conversationOrigin()} rather than only the outcome the resumed run came to.
     */
    Job jobFor(String conversation, TurnCap cap, Integer maxModelCalls) {
      String job = turn.resume(conversation, definition, null, cap, maxModelCalls);
      waitFor(job);
      return jobs.get(job);
    }

    private Outcome waitFor(String job) {
      for (int attempt = 0; attempt < 400; attempt++) {
        if (jobs.get(job).state() == Job.State.DONE) {
          Outcome outcome = jobs.get(job).outcome().orElse(null);
          assertNotNull(outcome, "a DONE job with no outcome");
          return outcome;
        }
        try {
          TimeUnit.MILLISECONDS.sleep(25);
        } catch (InterruptedException stop) {
          Thread.currentThread().interrupt();
          throw new AssertionError("interrupted waiting for " + job, stop);
        }
      }
      throw new AssertionError("job " + job + " never finished");
    }

    /**
     * The one tool, which counts how often it was actually run. That count is what makes "the
     * resumed run did not read the file again" a measurement.
     */
    private final class Counted implements AgentTool {

      @Override
      public ToolSchema schema() {
        return new ToolSchema(TOOL, "reads the deploy script", Map.of());
      }

      @Override
      public String run(String argumentsJson, Home home) {
        calls.incrementAndGet();
        return WHAT_THE_TOOL_SAID;
      }
    }
  }

  /**
   * A transport that asks for the tool until something has answered one, then either answers or
   * asks again for ever.
   *
   * <p><b>Asking only when nothing has answered is what a model with the result in front of it
   * does</b>, and it is what makes {@code
   * a_resumed_run_does_not_call_again_what_the_stopped_run_already_called} an assertion about the
   * history rather than about the script: a resumed run opened on a history with the tool result in
   * it does not ask, and one opened on a history without it does.
   */
  private static final class Script implements LlmTransport {

    private final List<List<ChatMessage>> seen = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean answers = true;

    @Override
    public OptionalInt contextLength(String wireModel) {
      return OptionalInt.of(ROOMY_CONTEXT);
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public void close() {
      // Nothing was opened: this transport answers from a rule.
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException("a turn does not embed");
    }

    /**
     * One chunk, which is what a scripted answer is. {@code JobRuntime} streams rather than
     * completes, so this is the method a run actually reaches.
     */
    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      Completion streamed = complete(wireModel, messages, sampling, tools);
      if (abandoned.getAsBoolean()) {
        throw new CallerAbandonedException(poolName());
      }
      String content = streamed.content();
      if (content != null && !content.isEmpty()) {
        sink.answered(content);
      }
      return streamed;
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      seen.add(List.copyOf(messages));
      TokenUsage usage = TokenUsage.of(40, 2, null);
      boolean toolAnswered =
          messages.stream().anyMatch(message -> message.role() == ChatMessage.Role.TOOL);
      if (!toolAnswered) {
        return new Completion("", "tool_calls", usage, List.of(new ToolCall("c1", TOOL, "{}")));
      }
      return answers
          ? new Completion("an answer", "stop", usage, List.of())
          : new Completion("", "tool_calls", usage, List.of(new ToolCall("c2", TOOL, "{}")));
    }

    /**
     * Drop what has been seen so far, so an assertion is about one run rather than about this
     * transport's whole history.
     */
    void forget() {
      seen.clear();
    }

    /** What the first call after the last {@link #forget()} carried. */
    List<ChatMessage> firstPrompt() {
      synchronized (seen) {
        assertFalse(seen.isEmpty(), "nothing was sent to the model");
        return seen.get(0);
      }
    }
  }

  /**
   * The agent a fold runs as, which nothing in this class asks for.
   *
   * <p>Named rather than left out all the same. A fold that could not say what it runs as would run
   * as the agent being folded, which is the defect {@code Compaction.FOLDER} exists to end --
   * {@code implementation rationale} §6.1 -- and a fixture that quietly had no folder would be the
   * one place that could not tell the two apart.
   */
  private static AgentDefinition folder() {
    return new AgentDefinition(
        Compaction.FOLDER,
        "a fixture folder",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You summarise a span of a recorded conversation.");
  }
}
