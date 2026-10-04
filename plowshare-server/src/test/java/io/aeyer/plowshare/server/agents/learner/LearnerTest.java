package io.aeyer.plowshare.server.agents.learner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.BoundTools;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One learning pass: what it asks, what it files, and what it marks.
 *
 * <h2>What is real here and what is not</h2>
 *
 * <p>The log is real, on a real database, because every claim about marking is a claim about a
 * column and the queue is the whole state machine. The archive and the scribe are doubles, because
 * what is being asserted is <em>that the learner hands a proposal to the pipeline</em> and never
 * what the pipeline then decides — {@code ScribeTest} and {@code ArchiveTest} own that, and
 * re-asserting it here would be this file testing them.
 *
 * <p>The transport is scripted, on {@code ScribeTest}'s pattern, so a pass's answer is a fixture
 * rather than a model's mood.
 */
@Tag("full-db")
@Testcontainers
class LearnerTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home PAYMENTS = Home.of("payments");

  /** A fixed moment, so a stamp in an assertion is one somebody chose. */
  private static final Instant THEN = Instant.parse("2026-09-04T11:00:00Z");

  /**
   * What the shipped definitions are read from; see {@code CodeReviewerDefinitionTest}, which reads
   * by path for the same reason — both source sets publish an {@code agents} directory.
   */
  private static final Path SHIPPED = Path.of("src/main/resources/agents");

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private EntryStore entries;
  private String conversation;

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
    entries = new EntryStore(jdbc);
    conversation = conversations.open(PAYMENTS, Budget.of(20)).id();
  }

  /**
   * The ordinary pass: nothing worth keeping, and the span is marked anyway.
   *
   * <p>Marking on "no" is the whole point of the queue. A pass that only marked what produced a
   * memory would re-read the same span on every fold, for ever, paying a model call each time to
   * reach the same answer.
   */
  @Test
  void a_window_that_yields_nothing_is_marked_learned_and_nothing_is_filed() {
    foldedSpan();
    Archive archive = archive();
    Scribe scribe = scribe();

    learner(new Scripted().answering("{\"memories\": []}"), archive, scribe).thereIsMaterial();

    assertTrue(
        entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, 10).isEmpty());
    assertEquals(THEN, learnedAt(1));
    verify(archive, never()).applyVerdict(any(), any(), any(), any(), any());
  }

  /**
   * A learning pass writes its note outside any turn, so it tells the log's followers itself —
   * nothing at a turn's end would.
   */
  @Test
  void the_pass_s_note_tells_the_log_s_followers() {
    foldedSpan();
    List<String> told = new ArrayList<>();
    Learner learner = learner(new Scripted().answering("{\"memories\": []}"), archive(), scribe());
    learner.useGrowth(told::add);

    learner.thereIsMaterial();

    assertEquals(List.of(conversation), told);
  }

  /**
   * Telling the followers is the least of what a pass does, and a follower that cannot be told
   * costs the pass nothing: the span stays marked, and nothing reports the pass as one that could
   * not be taken at all.
   */
  @Test
  void a_follower_that_cannot_be_told_costs_the_pass_nothing() {
    foldedSpan();
    Learner learner = learner(new Scripted().answering("{\"memories\": []}"), archive(), scribe());
    learner.useGrowth(
        told -> {
          throw new IllegalStateException("no socket to tell it on");
        });
    Logger learnerLog = (Logger) LoggerFactory.getLogger(Learner.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    learnerLog.addAppender(captured);
    try {
      learner.thereIsMaterial();
    } finally {
      learnerLog.detachAppender(captured);
    }

    assertEquals(THEN, learnedAt(1));
    assertEquals(
        List.of(),
        captured.list.stream()
            .filter(said -> said.getLevel().isGreaterOrEqual(Level.WARN))
            .map(ILoggingEvent::getFormattedMessage)
            .toList());
  }

  /**
   * A memory it proposes goes through the scribe and then the archive — exactly the pair a person's
   * own write goes through.
   *
   * <p>It proposes; it does not write. There is no path from here to {@code memory_write}, and
   * there is no path to {@code ProposalStore.propose} either: that files a question about a memory
   * that already exists, and a learner has none to name.
   */
  @Test
  void a_memory_it_proposes_is_judged_by_the_scribe_and_filed_by_the_archive() {
    foldedSpan();
    Archive archive = archive();
    Scribe scribe = scribe();

    learner(
            new Scripted()
                .answering(
                    """
                {"memories": [{"summary": "Rollbacks go out before the incident is understood",
                               "scope": "when deciding how to respond to a red deploy",
                               "body": "They roll back first and diagnose afterwards."}]}"""),
            archive,
            scribe)
        .thereIsMaterial();

    ArgumentCaptor<MemoryProposal> judged = ArgumentCaptor.forClass(MemoryProposal.class);
    verify(scribe).judge(judged.capture(), any());
    assertEquals("Rollbacks go out before the incident is understood", judged.getValue().summary());
    assertEquals("when deciding how to respond to a red deploy", judged.getValue().scope());
    assertEquals("They roll back first and diagnose afterwards.", judged.getValue().body());
    assertEquals(Learner.BY, judged.getValue().formedBy());
    assertTrue(
        judged.getValue().formedWhere().contains(conversation), judged.getValue().formedWhere());

    ArgumentCaptor<Home> home = ArgumentCaptor.forClass(Home.class);
    verify(archive).applyVerdict(any(), any(), home.capture(), any(), any());
    assertEquals(PAYMENTS, home.getValue());
  }

  /**
   * A pass whose model cannot be reached marks nothing.
   *
   * <p>The self-healing half: the span is exactly where it was, so the next window covers it and
   * whatever has folded since. Nothing retries and nothing backs off — the widening <em>is</em> the
   * retry.
   */
  @Test
  void a_pass_that_could_not_reach_a_model_marks_nothing_and_says_so() {
    foldedSpan();

    learner(new Scripted().failing(), archive(), scribe()).thereIsMaterial();

    assertFalse(
        entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, 10).isEmpty());
    assertNull(learnedAt(1));
    assertTrue(lastDiagnostic().contains("could not be taken"), lastDiagnostic());
  }

  /** And so does one whose answer is not JSON at all. */
  @Test
  void a_pass_whose_answer_could_not_be_read_marks_nothing() {
    foldedSpan();

    learner(new Scripted().answering("I could not decide, sorry"), archive(), scribe())
        .thereIsMaterial();

    assertFalse(
        entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, 10).isEmpty());
    assertNull(learnedAt(1));
  }

  /**
   * The pass's record is a {@code diagnostic}, which carries no role and therefore cannot reach a
   * model.
   *
   * <p>A conversation is never told that a learner ran over it. That is not a convention here:
   * {@code entries_role_matches_kind} refuses a diagnostic a role, and a row with no role is one
   * {@code thatProjectFor} does not fetch.
   */
  @Test
  void the_pass_leaves_a_diagnostic_that_cannot_reach_a_model() {
    foldedSpan();

    learner(new Scripted().answering("{\"memories\": []}"), archive(), scribe()).thereIsMaterial();

    EntryRecord note = lastEntry();
    assertEquals(EntryKind.DIAGNOSTIC, note.kind());
    assertTrue(note.content().contains("A learning pass read"), note.content());
    assertNull(
        jdbc.queryForObject(
            "SELECT role FROM entries WHERE conversation_id = ? AND ordinal = ?",
            String.class,
            conversation,
            note.ordinal()));
    assertFalse(
        entries.thatProjectFor(conversation).stream()
            .anyMatch(entry -> entry.kind() == EntryKind.DIAGNOSTIC));
  }

  /**
   * The learner is offered no tools, and asks the model its definition names.
   *
   * <p>Structural rather than a setting, exactly as it is for the scribe: the request is built
   * through {@code ChatRequest.of}, whose tool list is empty, and {@code withTools} is never
   * called. An operator who adds a tool to {@code learner.md} changes nothing here.
   */
  @Test
  void the_learner_is_offered_no_tools_and_asks_the_model_its_definition_names() {
    foldedSpan();
    Scripted transport = new Scripted().answering("{\"memories\": []}");

    learner(transport, archive(), scribe()).thereIsMaterial();

    assertEquals(1, transport.calls().size());
    assertEquals(List.of(), transport.calls().get(0).tools());
    assertEquals("model-reasoning", transport.calls().get(0).wireModel());
  }

  /**
   * The window's own text is what the model is shown, and the system's sentence about it says how
   * much of the queue this is.
   */
  @Test
  void what_the_model_is_shown_is_the_window_and_the_two_numbers() {
    foldedSpan();
    Scripted transport = new Scripted().answering("{\"memories\": []}");

    learner(transport, archive(), scribe()).thereIsMaterial();

    List<ChatMessage> sent = transport.calls().get(0).messages();
    assertEquals(2, sent.size());
    assertTrue(sent.get(1).content().contains("we roll back on red"), sent.get(1).content());
    assertTrue(sent.get(1).content().contains("2 of the 2 entries waiting"), sent.get(1).content());
  }

  /**
   * More memories than one window may produce are bounded rather than filed whole.
   *
   * <p>The failure mode this guards is a small model answering the question "is anything here worth
   * keeping" with a list of everything that happened. Each of those would be a row every future
   * recall pays attention to.
   */
  @Test
  void more_memories_than_one_window_may_produce_are_bounded() {
    foldedSpan();
    Archive archive = archive();
    StringBuilder many = new StringBuilder("{\"memories\": [");
    for (int i = 0; i < Learner.MOST_PROPOSED + 4; i++) {
      many.append(i == 0 ? "" : ",")
          .append("{\"summary\": \"claim ")
          .append(i)
          .append("\", \"scope\": \"when\", \"body\": \"because\"}");
    }
    many.append("]}");

    learner(new Scripted().answering(many.toString()), archive, scribe()).thereIsMaterial();

    // The tier as well as the count. This was five `any()`s, which asserted
    // that MOST_PROPOSED verdicts were applied and nothing about where --
    // the shape TODO 18.2 records. The home is held by a captor in
    // `a_proposal_is_judged_and_applied_in_the_tier_it_came_from` too, so
    // this is a second reader of the same property rather than the only one.
    verify(archive, times(Learner.MOST_PROPOSED))
        .applyVerdict(any(), any(), eq(PAYMENTS), any(), any());
  }

  /**
   * A proposal the archive refuses does not hold the mark back.
   *
   * <p>The one asymmetry, and it is deliberate: the window's claim is that the learner was shown
   * these rows, and it was. Holding the span back against an archive that is refusing writes would
   * buy a model call per fold, for ever, against a failure nothing here can repair — and this
   * design retries nothing.
   */
  @Test
  void a_proposal_the_archive_refuses_does_not_hold_back_the_mark() {
    foldedSpan();
    Archive archive = archive();
    when(archive.applyVerdict(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("the archive is not taking writes"));

    learner(
            new Scripted()
                .answering(
                    """
                {"memories": [{"summary": "a claim", "scope": "when", "body": "because"}]}"""),
            archive,
            scribe())
        .thereIsMaterial();

    assertEquals(THEN, learnedAt(1));
    assertTrue(lastDiagnostic().contains("proposed 0 memories"), lastDiagnostic());
  }

  /** Nothing awaiting is no model call at all. */
  @Test
  void a_server_with_nothing_awaiting_asks_no_model_anything() {
    entries.append(conversation, 1, LoggedEntry.utterance("still live", Speaker.person(null)));
    Scripted transport = new Scripted().answering("{\"memories\": []}");

    learner(transport, archive(), scribe()).thereIsMaterial();

    assertTrue(transport.calls().isEmpty());
  }

  /** And a server whose registry has no learner in it mines nothing rather than failing. */
  @Test
  void a_server_with_no_learner_defined_mines_nothing() {
    foldedSpan();
    Scripted transport = new Scripted().answering("{\"memories\": []}");
    Learner learner =
        new Learner(
            dispatcherOver(transport),
            entries,
            conversations,
            archive(),
            scribe(),
            () -> new AgentRegistry(Map.of()),
            () -> THEN);

    learner.thereIsMaterial();

    assertTrue(transport.calls().isEmpty());
    assertFalse(
        entries.awaitingTheLearner(conversation, ConversationLifecycle.ACTIVE, 10).isEmpty());
  }

  // --- the shipped definition ---------------------------------------------------

  /**
   * The shipped learner runs on the {@code reasoning} class, and the decision is recorded rather
   * than implied.
   *
   * <p>Scribe chose {@code fast} because a person waits on it. Nothing waits on this one, and what
   * it is asked is the harder question — whether a span of unfiltered conversation holds anything
   * worth keeping at all. {@code archive.Validation} says there is "deliberately no semantic
   * quality gate" on a write because "judging whether a memory is worth keeping is precisely what a
   * small local model is worst at"; that makes this agent the gate.
   */
  @Test
  void the_shipped_learner_runs_on_the_reasoning_class() {
    assertEquals("reasoning", shipped().model());
  }

  /** And is offered nothing, which its own file says is structural. */
  @Test
  void the_shipped_learner_declares_no_tools_and_no_callees() {
    assertEquals(List.of(), shipped().tools());
    assertEquals(List.of(), shipped().calls());
    assertEquals(List.of(), shipped().scopes());
  }

  private static AgentDefinition shipped() {
    return AgentRegistry.of(SHIPPED, BoundTools.boundByThisServer()).get(Learner.AGENT);
  }

  // --- the fixture ---------------------------------------------------------------

  /** Two entries a fold has covered: the whole of what a window is made of. */
  private void foldedSpan() {
    entries.append(
        conversation, 1, LoggedEntry.utterance("we roll back on red", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("understood", List.of()));
    EntryRecord summary = entries.append(conversation, 1, LoggedEntry.summary("they talked"));
    entries.supersede(conversation, 0, 1, summary.ordinal());
  }

  private Learner learner(Scripted transport, Archive archive, Scribe scribe) {
    return new Learner(
        dispatcherOver(transport),
        entries,
        conversations,
        archive,
        scribe,
        () -> new AgentRegistry(Map.of(Learner.AGENT, definition())),
        () -> THEN);
  }

  /** The learner as it is shipped: no tools, and the {@code reasoning} class. */
  private static AgentDefinition definition() {
    return new AgentDefinition(
        Learner.AGENT,
        "says whether anything here is worth remembering",
        "reasoning",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You read a span and decide.");
  }

  /**
   * An archive that takes whatever it is given, so that what is asserted is what the learner handed
   * it.
   */
  private static Archive archive() {
    Archive archive = mock(Archive.class);
    when(archive.maxBodyChars()).thenReturn(10_000);
    when(archive.applyVerdict(any(), any(), any(), any(), any()))
        .thenReturn(new WriteResult(VerdictKind.NEW, "mem_000001", "new", null, List.of()));
    return archive;
  }

  /**
   * A scribe that files everything flat, because its judgement is {@code ScribeTest}'s subject and
   * not this file's.
   */
  private static Scribe scribe() {
    Scribe scribe = mock(Scribe.class);
    when(scribe.judge(any(), any()))
        .thenReturn(new Scribe.Judgement(new Verdict(VerdictKind.NEW, null, "filed flat"), null));
    return scribe;
  }

  private Instant learnedAt(int ordinal) {
    java.sql.Timestamp stamped =
        jdbc.queryForObject(
            "SELECT learned_at FROM entries WHERE conversation_id = ? AND ordinal = ?",
            java.sql.Timestamp.class,
            conversation,
            ordinal);
    return stamped == null ? null : stamped.toInstant();
  }

  private EntryRecord lastEntry() {
    List<EntryRecord> log = entries.forConversation(conversation);
    return log.get(log.size() - 1);
  }

  private String lastDiagnostic() {
    return entries.forConversation(conversation).stream()
        .filter(entry -> entry.kind() == EntryKind.DIAGNOSTIC)
        .reduce((first, second) -> second)
        .orElseThrow()
        .content();
  }

  private static LlmDispatcher dispatcherOver(LlmTransport transport) {
    return new LlmDispatcher(
        List.of(
            new LlmPool(
                "scripted",
                List.of("model-fast", "model-reasoning"),
                Map.of("fast", "model-fast", "reasoning", "model-reasoning"),
                4,
                1,
                Duration.ofSeconds(5),
                transport)),
        new NoOpTokenLedger());
  }

  /**
   * A transport that answers with what the test queued and records what it was asked. {@code
   * ScribeTest.Scripted}, minus what this file has no use for.
   */
  private static final class Scripted implements LlmTransport {

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile Supplier<Completion> answer = () -> content("{}");

    record Call(String wireModel, List<ChatMessage> messages, List<ToolSchema> tools) {}

    Scripted answering(String text) {
      this.answer = () -> content(text);
      return this;
    }

    /** An endpoint that is not there, which is the shape every {@code LlmException} arrives in. */
    Scripted failing() {
      this.answer =
          () -> {
            throw new LlmException("nothing is listening");
          };
      return this;
    }

    List<Call> calls() {
      synchronized (calls) {
        return List.copyOf(calls);
      }
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      calls.add(new Call(wireModel, messages, tools));
      return answer.get();
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      return complete(wireModel, messages, sampling, tools);
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException("the learner does not embed");
    }

    @Override
    public void close() {}
  }

  private static Completion content(String text) {
    return new Completion(text, "stop", TokenUsage.UNKNOWN, List.of());
  }
}
