package io.aeyer.plowshare.server.agents;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.api.AgentController;
import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryPage;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.images.ImageStore;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * An utterance is a job, and the budget it spends is the conversation's.
 *
 * <h2>What would pass if the budget were not shared, and does not</h2>
 *
 * <p>Every number here is chosen so that the wrong allowance produces a different one. A
 * conversation is opened with {@link #SPENDABLE} model calls and the agent that answers in it
 * declares {@link #DEFINITION_CALLS} of its own — distinct integers, neither a multiple of the
 * other — so a {@code JobStore.submit} that kept building {@code
 * Budget.of(definition.maxModelCalls())} reports {@link #DEFINITION_CALLS} where these assert
 * {@link #SPENDABLE}. {@link #CAP} is above both, so the ending under test is the budget's and not
 * the loop's.
 *
 * <p>The plan's second standing check, applied to the <em>accepted</em> side: the conversations
 * whose turns are meant to stop for some other reason are opened with {@link #ROOMY}, a number
 * written from "several turns of two calls each" rather than from any limit this code holds.
 *
 * <h2>Why a real Postgres and not a mocked store</h2>
 *
 * <p>The mechanism is a row read at the start of a turn and written at the end of it, and the write
 * is a conditional {@code UPDATE} whose condition is the rule. A mocked {@code ConversationStore}
 * would let a turn's spending be whatever this file said it was, which is the one thing not worth
 * asserting. {@code ConversationStoreTest} runs the same image for the same reason.
 *
 * <h2>The session is nullable here too, and that is not an omission</h2>
 *
 * <p>Slice 3d's rule is that a run submitted with no session has "a smaller set, not an empty
 * capability". A turn will normally carry one, and {@code
 * a_turn_with_no_session_is_an_ordinary_turn} is what stops the conversation path from quietly
 * requiring one.
 */
@Tag("full-db")
@Testcontainers
class TurnTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /**
   * What a conversation under test is opened with. Small, so that a run scripted never to answer
   * reaches it in a bounded time.
   */
  private static final int SPENDABLE = 3;

  /** What the agent declares for itself, and what a turn must NOT be given. */
  private static final int DEFINITION_CALLS = 8;

  /** Above both, so that a budget test ends at the budget. */
  private static final int CAP = 11;

  /**
   * For the conversations whose turns stop for some other reason: several turns of two calls each,
   * and no limit in {@code main} was consulted to arrive at it.
   */
  private static final int ROOMY = 20;

  private static final Home PAYMENTS = Home.of("payments");

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private TurnStore turnRows;
  private EntryStore entryRows;
  private CompactionStore compactionRows;

  /**
   * Every store this test opened, so that a test leaving a run in flight does not leave a thread
   * behind it.
   */
  private final List<JobStore> opened = new ArrayList<>();

  /**
   * Every fold this class dispatched, so that the teardown can join it.
   *
   * <p><b>This fixture leaked one per ending turn and got away with it by accident.</b> {@code
   * Compaction.close} is {@code shutdown()} and waits for nothing, so a fold dispatched here
   * outlived the test that started it and had nothing between its writes and the next test's {@code
   * TRUNCATE}. What saved it is not the wiring: it is that this file's transport reports {@code
   * TokenUsage.UNKNOWN}, so {@code JobRuntime} never calls {@code promptMeasured} and every fold
   * returns at {@code foldIfItWouldNotFit}'s first guard, before it reads or writes anything at
   * all.
   *
   * <p>That is one fixture detail away from being false, and it was measured rather than argued: a
   * transport reporting usage, over conversations of more than one turn, reproduced exactly the
   * deadlock {@link FoldsOnTheirOwnThreads} describes — on the {@code TRUNCATE} in {@link
   * #freshConversations()}, taking down {@code
   * a_ceiling_raised_while_a_turn_is_in_flight_is_picked_up_by_that_turn}, which has nothing to do
   * with compaction. The guard belongs to the wiring and not to the accident, so it is here.
   */
  private final FoldsOnTheirOwnThreads folds = new FoldsOnTheirOwnThreads();

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
    // Both tables, because V7 gave `turns` a foreign key to this one, and
    // Postgres refuses to truncate a table another one references unless
    // that one is truncated with it — whether or not it holds a row, and
    // this class writes none. This comment used to say `conversations` had
    // no foreign key in either direction, which V7 made false.
    //
    // Named rather than CASCADE, so that a table joining this graph later
    // cannot be emptied by a fixture that does not know about it. V8's
    // `compactions` is the table that joined it, with a composite key into
    // `turns`, and this line is where it had to be named. `board_topics`,
    // `board_messages` and `board_seats` joined the same way in V74, each
    // with its own foreign key into `conversations`; `firings` a hop
    // further out, through its V74 foreign key into `board_topics`; and
    // `user_inbox` a hop past that, through its pre-existing V40 foreign
    // key into `firings`.
    jdbc.execute(
        "TRUNCATE TABLE board_message_route_bindings, outgoing_work, message_external_tasks, message_external_contexts, board_message_route_bindings, board_message_routes, board_message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
    conversations = new ConversationStore(jdbc);
    turnRows = new TurnStore(jdbc);
    compactionRows = new CompactionStore(jdbc);
    entryRows = new EntryStore(jdbc);
  }

  @AfterEach
  void stopEveryRun() throws InterruptedException {
    // First, because a fold still writing is the one thing here that can
    // reach the next test. See FoldsOnTheirOwnThreads.
    folds.join();
    opened.forEach(JobStore::close);
    opened.clear();
  }

  @Test
  void project_auto_increase_allows_a_new_utterance_after_exhaustion_and_records_the_total(
      @TempDir Path dir) throws Exception {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers(), 2, 2);
    Path environment = dir.resolve("environment.yml");
    Files.writeString(environment, "caps:\n  steps: 2\n  budget: 2\n  auto-increase: true\n");
    var automatic =
        new AutomaticLimits(
            new Environments(project -> 7L, id -> environment, null), conversations);
    fixture.turn.useAutomaticLimits(automatic);
    fixture.runtime.useAutomaticLimits(automatic);
    var conversation = conversations.open(PAYMENTS, Budget.of(2));
    assertEquals(
        Outcome.Ending.ANSWERED, fixture.speakAndWait(conversation.id(), "first", null).ending());
    assertEquals(2, conversations.find(conversation.id()).orElseThrow().budget().spent());
    assertEquals(
        Outcome.Ending.ANSWERED, fixture.speakAndWait(conversation.id(), "next", null).ending());
    var saved = conversations.find(conversation.id()).orElseThrow().budget();
    assertEquals(4, saved.spent());
    assertEquals(4, saved.limit());
  }

  @Test
  void project_auto_increase_continues_in_the_same_turn_and_retains_spending(@TempDir Path dir)
      throws Exception {
    Script script =
        new Script(false, false) {
          int calls;

          @Override
          public Completion complete(
              String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
            if (++calls == 3) return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
            return super.complete(model, messages, sampling, tools);
          }
        };
    Fixture fixture = new Fixture(dir, script, 2, 2);
    Path manifest = dir.resolve("plowshare");
    Files.writeString(
        manifest,
        "{\"version\":1,\"name\":\"payments\",\"caps\":{\"steps\":2,\"budget\":2,\"autoIncrease\":true}}\n");
    var environments = new Environments(project -> 7L, id -> dir.resolve("environment.yml"), null);
    environments.useProjectConfiguration(
        project -> ProjectConfiguration.server(dir, List.of(), project));
    var automatic = new AutomaticLimits(environments, conversations);
    fixture.turn.useAutomaticLimits(automatic);
    fixture.runtime.useAutomaticLimits(automatic);
    var conversation = conversations.open(PAYMENTS, Budget.of(2));
    var outcome = fixture.speakAndWait(conversation.id(), "work", null);
    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals(3, outcome.modelCalls());
    var saved = conversations.find(conversation.id()).orElseThrow().budget();
    assertEquals(3, saved.spent());
    assertEquals(4, saved.limit());
  }

  // --- the budget a turn spends -------------------------------------------------

  /**
   * A turn stops at the conversation's allowance, not at the agent's.
   *
   * <p>The discriminating assertion is the count. A job built from {@code
   * definition.maxModelCalls()} would end {@code CALL_BUDGET} too, having made {@link
   * #DEFINITION_CALLS} calls instead of {@link #SPENDABLE}, so asserting the ending alone would
   * pass against the mistake this test is for.
   */
  @Test
  void a_turn_spends_the_conversations_budget_and_not_the_agents(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));

    Outcome outcome = fixture.speakAndWait(conversation.id(), "hello", "s-cli");

    assertEquals(Outcome.Ending.CALL_BUDGET, outcome.ending());
    assertEquals(
        SPENDABLE,
        outcome.modelCalls(),
        "the turn was given the agent's allowance rather than the conversation's");
  }

  /**
   * The job a turn starts names the conversation it speaks into, and the origin that conversation
   * was opened as.
   *
   * <p>The only production coverage of {@code JobStore.submit}'s threading — {@code
   * AgentControllerTest} mocks {@code JobStore} entirely, so it proves only that {@code JobView.of}
   * copies a field a {@code JobAccess} fixture put there by hand. This is the real {@code
   * Transcript.conversationId()} read off a real {@code Compaction.TurnTranscript}, through the
   * real {@code JobStore.start}.
   */
  @Test
  void a_turns_job_carries_the_conversation_it_speaks_into(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));

    Job job = fixture.jobFor(conversation.id(), "hello", "s-cli");

    assertEquals(
        conversation.id(),
        job.conversation(),
        "a jobs screen polling this job has nothing to send to POST"
            + " /v1/conversations/{id}/resume without this");
    assertEquals(
        Origin.TURN,
        job.conversationOrigin(),
        "a turn's conversation is always Origin.TURN, which is what makes it"
            + " resumable at all");
  }

  // --- an orchestration's own conversation ------------------------------------------

  /**
   * The harness speaks into an orchestration's own conversation through {@link
   * Turn#speakToConductor}, and the job it starts runs as that conversation's own origin — not a
   * hardcoded {@link Origin#TURN}.
   *
   * <p>The conversation is logged with the fixture's own {@link #AGENT} ("talker") as its
   * conductor, and spoken to with {@code fixture.definition}, which is that same agent — the
   * cheaper of the two options for making the logged agent and the speaking definition agree, since
   * the fixture already builds one definition and nothing here needs a second on-disk agent file.
   *
   * <p>{@link Script#blocks()} so that {@code isSpeaking} can be observed {@code true} while the
   * turn is genuinely in flight and {@code false} again once it has ended, on {@code
   * a_conversation_holds_one_turn_at_a_time}'s own pattern.
   */
  @Test
  void an_orchestration_s_conversation_is_spoken_into_and_its_turn_runs_as_that_origin(
      @TempDir Path dir) throws Exception {
    Script blocking = Script.blocks();
    Fixture fixture = new Fixture(dir, blocking);
    ConversationRecord conversation =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, AGENT, null, Budget.of(10));

    String job =
        fixture.turn.speakToConductor(
            conversation.id(), fixture.definition, "hello", outcome -> {});
    blocking.awaitEntry();
    try {
      assertTrue(
          fixture.turn.isSpeaking(conversation.id()),
          "the conversation should be speaking while its turn is in flight");
    } finally {
      blocking.release();
    }
    fixture.await(job);

    assertEquals(
        Origin.ORCHESTRATION,
        fixture.jobs.get(job).conversationOrigin(),
        "the job did not carry the conversation's own origin");
    assertFalse(
        fixture.turn.isSpeaking(conversation.id()),
        "the conversation should be free again once its turn ended");
  }

  @Test
  void an_approved_event_is_continued_as_the_agent_its_log_names(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation =
        conversations.log(Origin.EVENT, PAYMENTS, AGENT, null, Budget.of(ROOMY));

    String job =
        fixture.turn.speakToApprovedRun(
            conversation.id(), fixture.definition, "approved; run it again", outcome -> {});
    fixture.await(job);

    assertEquals(Origin.EVENT, fixture.jobs.get(job).conversationOrigin());
    assertFalse(fixture.turn.isSpeaking(conversation.id()));
  }

  /**
   * The door a person reaches — {@link Turn#speak} — still cannot post into an orchestration's own
   * conversation, even though that origin is speakable now through {@link Turn#speakToConductor}.
   * Nothing routes a caller-supplied conversation id there, and this is the refusal that stops one
   * reaching it by naming the id directly: the original "is the log of a ... run" text, unchanged.
   */
  @Test
  void a_person_s_door_cannot_speak_into_an_orchestration_s_conversation(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, AGENT, null, Budget.of(ROOMY));

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.speak(conversation.id(), fixture.definition, "hello", "s-cli"));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "conversation " + conversation.id() + " is the log of a orchestration" + " run"),
        refused.getMessage());
  }

  // --- incoming: only a person's own words reach TriggerNoticing ------------------

  /**
   * {@link Turn#speak} is a person's own words arriving fresh — spec §6 — so the run it starts
   * carries {@code incoming = true} down to {@link JobRuntime#run}, and {@link TriggerNoticing} is
   * asked about it, gated on the definition's own grant exactly as {@link JobRuntimeTest} proves in
   * isolation. Wiring it here, through the real {@code JobStore} and {@code Turn} this class
   * already exercises, is what proves the seam threads all the way from the door.
   */
  @Test
  void speak_reaches_the_runtime_as_an_incoming_utterance(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    List<String> asked = new ArrayList<>();
    fixture.runtime.useTriggers(
        (definition, utterance, home, session, conversation) -> {
          asked.add(utterance);
          return Optional.empty();
        });
    AgentDefinition granting = granting(fixture.definition, "code_implementation");
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    fixture.await(fixture.turn.speak(conversation.id(), granting, "hello", "s-cli"));

    assertEquals(List.of("hello"), asked);
  }

  /**
   * {@link Turn#deliver} is the harness delivering its own words, never a person's — spec §6 names
   * it beside a conductor turn, an approved-run continuation and a resume as a door that never
   * reaches {@link TriggerNoticing}. It also carries no session (a delivery reaches no client
   * machine) and no per-call cap override (there is no argument for one), so what runs is the
   * definition's own.
   */
  @Test
  void deliver_reaches_the_runtime_as_not_incoming_with_no_session_and_the_definitions_own_cap(
      @TempDir Path dir) {
    // A cap below Repeats.ENOUGH (8): neverAnswers() asks the identical tool call every
    // step, and a cap at or past that count would end STUCK before it ever reached its own
    // ceiling -- see a_turn_runs_under_the_conversations_cap_and_not_the_agents for the same
    // reasoning applied to a conversation's own cap.
    int belowStuck = 4;
    Fixture fixture = new Fixture(dir, Script.neverAnswers(), belowStuck, DEFINITION_CALLS);
    List<String> asked = new ArrayList<>();
    fixture.runtime.useTriggers(
        (definition, utterance, home, session, conversation) -> {
          asked.add(utterance);
          return Optional.empty();
        });
    AgentDefinition granting = granting(fixture.definition, "code_implementation");
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    Outcome outcome =
        fixture.await(fixture.turn.deliver(conversation.id(), granting, "a delivery", never -> {}));

    assertEquals(List.of(), asked, "a delivery is never an incoming utterance");
    assertEquals(Outcome.Ending.TURN_CAP, outcome.ending());
    assertEquals(belowStuck, outcome.steps(), "a delivery runs under the definition's own cap");
    // The seam is re-asked on every routing call -- ProviderRouter's own javadoc -- so
    // neverAnswers()'s repeated file_roots call records one session per step; what matters
    // is that every one of them is null, on SessionSubmissionTest's pattern for the identical
    // fact (List.of() refuses a null element, so this cannot be an equality against it).
    assertFalse(fixture.providers.sessions().isEmpty());
    fixture
        .providers
        .sessions()
        .forEach(
            session ->
                assertNull(
                    session,
                    "a delivery reaches no client machine, so its run carries no session: "
                        + fixture.providers.sessions()));
  }

  /**
   * {@link Turn#deliver} shares {@link Turn#requireSpeakable}'s admissibility with {@link
   * Turn#speak} — {@link Origin#TURN} only — and refuses an orchestration's own conversation with
   * the identical message {@link
   * #a_person_s_door_cannot_speak_into_an_orchestration_s_conversation} pins for {@code speak}.
   */
  @Test
  void deliver_refuses_a_non_turn_conversation_with_speaks_own_message(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, AGENT, null, Budget.of(ROOMY));

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.deliver(conversation.id(), fixture.definition, "hello", never -> {}));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "conversation " + conversation.id() + " is the log of a orchestration" + " run"),
        refused.getMessage());
  }

  /**
   * {@link Turn#speakToConductor} is the harness driving a conductor, never a person's own words —
   * spec §6 names it beside {@link Turn#deliver} as a door that never reaches {@link
   * TriggerNoticing}.
   */
  @Test
  void speak_to_conductor_reaches_the_runtime_as_not_incoming(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    List<String> asked = new ArrayList<>();
    fixture.runtime.useTriggers(
        (definition, utterance, home, session, conversation) -> {
          asked.add(utterance);
          return Optional.empty();
        });
    AgentDefinition granting = granting(fixture.definition, "code_implementation");
    ConversationRecord conversation =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, granting.name(), null, Budget.of(ROOMY));

    fixture.await(fixture.turn.speakToConductor(conversation.id(), granting, "hello", never -> {}));

    assertEquals(List.of(), asked, "the harness driving a conductor is never incoming");
  }

  /**
   * {@link Turn#speakToApprovedRun} is the harness continuing a run after its owning account
   * answered an approval, never a person's own words — spec §6 names it beside {@link Turn#deliver}
   * and {@link Turn#speakToConductor} as a door that never reaches {@link TriggerNoticing}.
   */
  @Test
  void speak_to_approved_run_reaches_the_runtime_as_not_incoming(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    List<String> asked = new ArrayList<>();
    fixture.runtime.useTriggers(
        (definition, utterance, home, session, conversation) -> {
          asked.add(utterance);
          return Optional.empty();
        });
    AgentDefinition granting = granting(fixture.definition, "code_implementation");
    ConversationRecord conversation =
        conversations.log(Origin.EVENT, PAYMENTS, AGENT, null, Budget.of(ROOMY));

    fixture.await(
        fixture.turn.speakToApprovedRun(
            conversation.id(), granting, "approved; run it again", outcome -> {}));

    assertEquals(List.of(), asked, "an approved-run continuation is never incoming");
  }

  /**
   * {@link Turn#speakToDelegate} resumes a conductor's sub-agent in the sub-agent's own
   * conversation — spec 2026-09-26 §4 — and, because a delegation owns neither an allowance nor a
   * lifecycle, it spends the conductor's and holds both conversations while it runs. When the turn
   * ends, both are free again.
   */
  @Test
  void a_delegate_is_resumed_in_its_own_conversation_on_its_conductor_s_budget(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conductor =
        conversations.log(
            Origin.ORCHESTRATION, PAYMENTS, "code_implementation", null, Budget.of(ROOMY));
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, PAYMENTS, AGENT, conductor.id(), null);

    String job =
        fixture.turn.speakToDelegate(
            child.id(),
            conductor.id(),
            fixture.definition,
            "[approval] allowed; run it again",
            "enzo",
            outcome -> {});
    fixture.await(job);

    assertEquals(Origin.DELEGATION, fixture.jobs.get(job).conversationOrigin());
    assertFalse(fixture.turn.isSpeaking(child.id()));
    assertFalse(fixture.turn.isSpeaking(conductor.id()));
  }

  /**
   * Ledger (final review): the allowance a resumed sub-agent spends is the conductor's, so its
   * model calls are written back to the CONDUCTOR's row — and the delegation's own row, which owns
   * no allowance, stays without one. Written back to the child, the conductor's budget would never
   * see what its sub-agent spent, and the cap resuming must not get past would be gone.
   */
  @Test
  void a_resumed_delegate_s_calls_are_spent_from_the_conductor_s_row_and_not_the_child_s(
      @TempDir Path dir) throws Exception {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conductor =
        conversations.log(
            Origin.ORCHESTRATION, PAYMENTS, "code_implementation", null, Budget.of(ROOMY));
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, PAYMENTS, AGENT, conductor.id(), null);
    java.util.concurrent.CompletableFuture<Outcome> ended =
        new java.util.concurrent.CompletableFuture<>();

    fixture.turn.speakToDelegate(
        child.id(),
        conductor.id(),
        fixture.definition,
        "[approval] allowed; run it again",
        "enzo",
        ended::complete);
    Outcome outcome = ended.get(10, TimeUnit.SECONDS);

    assertTrue(outcome.modelCalls() > 0, "the resumed turn made model calls");
    assertEquals(
        outcome.modelCalls(),
        jdbc.queryForObject(
            "SELECT budget_spent FROM conversations WHERE id = ?", Integer.class, conductor.id()));
    assertEquals(
        null,
        jdbc.queryForObject(
            "SELECT budget_spent FROM conversations WHERE id = ?", Integer.class, child.id()),
        "the delegation's row owns no allowance");
    assertEquals(
        null,
        jdbc.queryForObject(
            "SELECT budget_total FROM conversations WHERE id = ?", Integer.class, child.id()));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void a_seats_resumed_delegate_spends_an_external_lease_and_settles_before_its_callback(
      boolean nested, @TempDir Path dir) throws Exception {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord seat = conversations.log(Origin.BOARD, PAYMENTS, "opener", null, null);
    ConversationRecord parent =
        nested
            ? conversations.log(Origin.DELEGATION, PAYMENTS, "intermediate", seat.id(), null)
            : seat;
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, PAYMENTS, AGENT, parent.id(), null);
    var charges = new java.util.concurrent.atomic.AtomicInteger();
    Budget lease = Budget.of(ROOMY, charges::incrementAndGet);
    java.util.concurrent.atomic.AtomicInteger settlements =
        new java.util.concurrent.atomic.AtomicInteger();
    fixture.turn.useDelegatedAllowances(
        owner -> {
          assertEquals(parent.id(), owner.id());
          return new Turn.DelegatedAllowance(lease, settlements::incrementAndGet);
        });
    java.util.concurrent.CompletableFuture<Outcome> ended =
        new java.util.concurrent.CompletableFuture<>();
    fixture.turn.speakToDelegate(
        child.id(),
        parent.id(),
        fixture.definition,
        "continue",
        "enzo",
        outcome -> {
          assertEquals(1, settlements.get());
          ended.complete(outcome);
        });
    Outcome outcome = ended.get(10, TimeUnit.SECONDS);
    assertTrue(outcome.modelCalls() > 0);
    assertEquals(outcome.modelCalls(), lease.spent());
    assertEquals(
        outcome.modelCalls(),
        charges.get(),
        "direct and nested resumed delegates must charge every shared claim");
    assertNull(conversations.find(seat.id()).orElseThrow().budget());
    assertNull(conversations.find(child.id()).orElseThrow().budget());
    assertFalse(fixture.turn.isSpeaking(seat.id()));
  }

  @Test
  void a_seat_delegate_without_an_allowance_is_refused_cleanly_and_frees_both_logs(
      @TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord seat = conversations.log(Origin.BOARD, PAYMENTS, "opener", null, null);
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, PAYMENTS, AGENT, seat.id(), null);
    assertThrows(
        Turn.Refused.class,
        () ->
            fixture.turn.speakToDelegate(
                child.id(), seat.id(), fixture.definition, "continue", "enzo", outcome -> {}));
    assertFalse(fixture.turn.isSpeaking(seat.id()));
    assertFalse(fixture.turn.isSpeaking(child.id()));
  }

  /**
   * Only a delegation the named conductor made is resumed through that conductor: another
   * conductor's child, or the conductor's own conversation, is refused before anything is claimed
   * or spent.
   */
  @Test
  void only_a_conductor_s_own_delegate_is_resumed(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conductor =
        conversations.log(
            Origin.ORCHESTRATION, PAYMENTS, "code_implementation", null, Budget.of(ROOMY));
    ConversationRecord other =
        conversations.log(
            Origin.ORCHESTRATION, PAYMENTS, "code_implementation", null, Budget.of(ROOMY));
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, PAYMENTS, AGENT, other.id(), null);

    assertThrows(
        Turn.Refused.class,
        () ->
            fixture.turn.speakToDelegate(
                child.id(), conductor.id(), fixture.definition, "x", "enzo", outcome -> {}));
    assertThrows(
        Turn.Refused.class,
        () ->
            fixture.turn.speakToDelegate(
                conductor.id(), conductor.id(), fixture.definition, "x", "enzo", outcome -> {}));
  }

  /**
   * {@link Turn#resume} is the harness continuing a run that ran out of allowance, never a person's
   * own words — spec §6 names it beside {@link Turn#deliver}, {@link Turn#speakToConductor} and
   * {@link Turn#speakToApprovedRun} as a door that never reaches {@link TriggerNoticing}.
   */
  @Test
  void resume_reaches_the_runtime_as_not_incoming(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    List<String> asked = new ArrayList<>();
    fixture.runtime.useTriggers(
        (definition, utterance, home, session, conversation) -> {
          asked.add(utterance);
          return Optional.empty();
        });
    AgentDefinition granting = granting(fixture.definition, "code_implementation");
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.none(), TurnCap.of(2));
    assertEquals(
        Outcome.Ending.TURN_CAP,
        fixture.speakAndWait(conversation.id(), "hello", "s-cli").ending(),
        "this test says nothing unless the run really stopped somewhere continuable");

    fixture.await(fixture.turn.resume(conversation.id(), granting, "s-cli", null, null));

    assertEquals(List.of(), asked, "a resumed run is never an incoming utterance");
  }

  /**
   * The conductor door carries a session when the caller gives it one, through the 5-argument
   * overload — the harness asking on behalf of a laptop session that has a socket of its own.
   */
  @Test
  void the_conductor_door_carries_a_session_when_given_one(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, AGENT, null, Budget.of(ROOMY));

    fixture.await(
        fixture.turn.speakToConductor(
            conversation.id(), fixture.definition, "hello", "s-laptop", null, outcome -> {}));

    assertEquals(List.of("s-laptop"), fixture.providers.sessions());
  }

  /**
   * A turn nobody's client streamed — a harness delivery — is still told to the log's followers,
   * once every entry it wrote is committed and before its job reports it ended. That ordering is
   * what lets a client streaming its own turn learn the log's reach before it hears {@code ended}:
   * both go through the same per-socket queue.
   */
  @Test
  void a_turn_the_harness_started_tells_the_log_s_followers_before_it_reports_it_ended(
      @TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));
    List<Integer> reachWhenTold = new CopyOnWriteArrayList<>();
    fixture.compaction.useGrowth(told -> reachWhenTold.add(entryRows.through(told)));

    fixture.await(
        fixture.turn.deliver(
            conversation.id(),
            fixture.definition,
            "The run finished.",
            Speaker.orchestration("orc_1"),
            outcome -> {}));

    // No wait: await returned because the job is DONE, and the growth is told inside the
    // job's own ending, before that — which is also before its `ended` event is published.
    assertFalse(reachWhenTold.isEmpty(), "the followers were told before the turn ended");
    int lastOfTheTurn =
        entryRows.pageOfLog(conversation.id(), 0, 50).listed().stream()
            .filter(row -> row.turnOrdinal() == 1)
            .mapToInt(EntryPage.Row::ordinal)
            .max()
            .orElseThrow();
    assertEquals(
        lastOfTheTurn, reachWhenTold.get(0), "told once every entry of the turn was in the log");
  }

  /**
   * A conductor's turn that spends its last allowed call is already recorded as spent when its
   * ending is handed on.
   *
   * <p>The orchestration engine relies on this: {@code JobRuntime} ends a turn whose last call
   * answered as {@code ANSWERED}, not {@code CALL_BUDGET}, so the engine reads the conversation's
   * row from inside {@code alsoEnded} to see that nothing is left, and asks the caller to raise the
   * budget instead of nudging a conductor that could only be refused. A {@code turnEnded} written
   * after {@code alsoEnded} would show it the budget from before the turn.
   */
  @Test
  void a_conductor_s_last_call_turn_is_recorded_as_spent_before_its_ending_is_handed_on(
      @TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, AGENT, null, Budget.of(2));
    List<Budget> seen = Collections.synchronizedList(new ArrayList<>());
    List<Outcome> endings = Collections.synchronizedList(new ArrayList<>());

    fixture.await(
        fixture.turn.speakToConductor(
            conversation.id(),
            fixture.definition,
            "hello",
            null,
            null,
            outcome -> {
              endings.add(outcome);
              seen.add(conversations.find(conversation.id()).orElseThrow().budget());
            }));

    assertEquals(1, endings.size());
    assertEquals(
        Outcome.Ending.ANSWERED,
        endings.get(0).ending(),
        "the last call answered, so the turn ends ANSWERED and not CALL_BUDGET");
    assertEquals(2, seen.get(0).spent());
    assertTrue(
        seen.get(0).exhausted(), "alsoEnded read the budget from before the turn was written back");
  }

  /**
   * A grant through the conductor door that {@link Turn#grant} would refuse — a new total below
   * what the conversation has already spent — surfaces as {@link Turn.Refused}, carrying {@code
   * grant}'s own sentence, rather than the {@link CallerFault} {@link Turn#resume}'s door lets
   * through. The engine driving a conductor has one exception type to catch, not two.
   */
  @Test
  void the_conductor_door_converts_a_grant_failure_to_a_refusal(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, AGENT, null, Budget.of(SPENDABLE));
    Outcome first =
        fixture.await(
            fixture.turn.speakToConductor(
                conversation.id(), fixture.definition, "hello", outcome -> {}));
    assertEquals(Outcome.Ending.CALL_BUDGET, first.ending());

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.speakToConductor(
                    conversation.id(),
                    fixture.definition,
                    "again",
                    null,
                    SPENDABLE - 1,
                    outcome -> {}));

    assertTrue(refused.getMessage().contains("has already spent"), refused.getMessage());
    assertFalse(
        fixture.turn.isSpeaking(conversation.id()),
        "a refused speakToConductor must not leave the conversation claimed");
  }

  /**
   * The conductor door is not a general-purpose way into an orchestration's conversation either: it
   * refuses a definition that does not name the conductor the conversation was logged under, so
   * nothing can drive it as a different agent.
   */
  @Test
  void a_conductor_s_conversation_is_spoken_to_only_as_its_conductor(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, AGENT, null, Budget.of(ROOMY));
    AgentDefinition impostor =
        new AgentDefinition(
            "impostor",
            "a fixture",
            "fast",
            List.of(),
            List.of(),
            List.of(),
            2,
            4,
            "You do one thing.");

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.speakToConductor(conversation.id(), impostor, "hello", outcome -> {}));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "conversation "
                    + conversation.id()
                    + " belongs to the conductor '"
                    + AGENT
                    + "', not 'impostor'"),
        refused.getMessage());
    assertFalse(
        fixture.turn.isSpeaking(conversation.id()),
        "a refused speakToConductor must not leave the conversation claimed");
  }

  /**
   * And the conductor door refuses a person's own conversation, the identical situation in the
   * opposite direction.
   */
  @Test
  void the_conductor_door_refuses_a_person_s_conversation(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.speakToConductor(
                    conversation.id(), fixture.definition, "hello", outcome -> {}));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "conversation "
                    + conversation.id()
                    + " is not an orchestration's own conversation"),
        refused.getMessage());
    assertFalse(
        fixture.turn.isSpeaking(conversation.id()),
        "a refused speakToConductor must not leave the conversation claimed");
  }

  private String seat() {
    return conversations.log(Origin.BOARD, PAYMENTS, AGENT, null, null).id();
  }

  @Test
  void a_seat_turn_spends_the_lease_it_is_handed_and_leaves_the_row_without_numbers(
      @TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    String seat = seat();
    Budget lease = Budget.of(3);

    Outcome outcome =
        fixture.await(
            fixture.turn.speakToSeat(
                seat,
                fixture.definition,
                "Board · woken",
                lease,
                TurnCap.of(12),
                Speaker.board("bdt_1"),
                ended -> {}));

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals(2, lease.spent(), "the turn spent something other than its lease");
    ConversationRecord row = conversations.find(seat).orElseThrow();
    assertNull(row.budget(), "a seat's row gained budget numbers");
    assertEquals(
        ConversationLifecycle.ACTIVE,
        row.lifecycle(),
        "a seat's conversation closed with one wake");
  }

  @Test
  void only_a_seat_is_spoken_to_through_the_seat_door(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord person = conversations.open(PAYMENTS, Budget.of(5));
    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.speakToSeat(
                    person.id(),
                    fixture.definition,
                    "x",
                    Budget.of(1),
                    TurnCap.of(1),
                    Speaker.board("bdt_1"),
                    ended -> {}));
    assertTrue(refused.getMessage().contains("not a seat"), refused.getMessage());
  }

  @Test
  void a_seat_is_spoken_to_as_the_agent_it_belongs_to(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    String other = conversations.log(Origin.BOARD, PAYMENTS, "someone_else", null, null).id();
    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.speakToSeat(
                    other,
                    fixture.definition,
                    "x",
                    Budget.of(1),
                    TurnCap.of(1),
                    Speaker.board("bdt_1"),
                    ended -> {}));
    assertTrue(refused.getMessage().contains("someone_else"), refused.getMessage());
  }

  @Test
  void a_board_speaker_is_the_harness_naming_its_topic() {
    assertEquals(Speaker.Kind.HARNESS, Speaker.board("bdt_1").kind());
    assertEquals("board bdt_1", Speaker.board("bdt_1").name());
  }

  /**
   * A delegated child's log is still refused: it is the log of a run, not a conversation somebody
   * opened to speak into, and that stays true for an origin besides {@link Origin#TURN} being
   * speakable now.
   */
  @Test
  void a_delegated_child_s_log_is_still_refused(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord parent = conversations.open(PAYMENTS, Budget.of(ROOMY));
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, PAYMENTS, AGENT, parent.id(), null);

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.speak(child.id(), fixture.definition, "hello", "s-cli"));

    assertTrue(
        refused
            .getMessage()
            .startsWith("conversation " + child.id() + " is the log of a delegation run"),
        refused.getMessage());
  }

  /** An event run's log is still refused, on the identical grounds. */
  @Test
  void an_event_run_s_log_is_still_refused(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation =
        conversations.log(Origin.EVENT, PAYMENTS, AGENT, null, Budget.of(ROOMY));

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.speak(conversation.id(), fixture.definition, "hello", "s-cli"));

    assertTrue(
        refused
            .getMessage()
            .startsWith("conversation " + conversation.id() + " is the log of a event run"),
        refused.getMessage());
  }

  /** And what it spent is on the row afterwards, not only in the job handle. */
  @Test
  void what_a_turn_spent_is_written_back_onto_the_conversation(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));

    Outcome outcome = fixture.speakAndWait(conversation.id(), "hello", "s-cli");

    ConversationRecord after = conversations.find(conversation.id()).orElseThrow();
    assertEquals(outcome.modelCalls(), after.budget().spent());
    assertEquals(0, after.budget().remaining());
    assertNotNull(after.lastTurnAt(), "a turn that ran left no mark of having ended");
  }

  /**
   * The spending is recorded before the job says it is finished.
   *
   * <p><b>The whole of {@code Turn}'s correctness after the first turn rests on this, so it is
   * measured here rather than asserted in a javadoc.</b> A person's next utterance is submitted the
   * moment they see the turn end, and they can see that two ways: the ENDED event, or a poll of
   * {@code GET /v1/jobs/&#123;id&#125;}. If {@code JobStore} filed the outcome before telling its
   * caller, the poll would win the race and the next turn would read a row one turn out of date —
   * which is a budget quietly handed back.
   *
   * <p>It is asserted at the seam and not through {@code Turn}, because "before" is not something a
   * polling loop can observe from outside: the consumer records what the store said about its own
   * job at the instant it was called. And it is what lets every other test here wait on {@code
   * DONE} and then read the row.
   */
  @Test
  void what_a_turn_spent_is_recorded_before_the_job_reports_it_has_ended(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    List<Job.State> whenTold = Collections.synchronizedList(new ArrayList<>());

    // null and not Origin.TURN: this call goes straight to JobStore and
    // never through Turn, so there is no real conversation behind
    // Transcript.NONE for an origin to be the origin of.
    String job =
        fixture.jobs.submit(
            fixture.definition,
            "hello",
            PAYMENTS,
            "s-cli",
            Budget.of(ROOMY),
            Transcript.NONE,
            null,
            outcome -> whenTold.add(fixture.jobs.jobs().get(0).state()));
    fixture.await(job);

    assertEquals(
        List.of(Job.State.RUNNING),
        whenTold,
        "the job was already filed as finished when its spending was handed back");
  }

  /**
   * A second turn starts where the first one stopped.
   *
   * <p>The assertion is the sum rather than a literal, so it does not restate the script: what it
   * says is that the row holds everything both turns spent. A design handing each turn a fresh
   * {@code Budget} leaves the row at the second turn's count, or at zero if the fresh one is the
   * object the run actually spends, and both turns still answer.
   */
  @Test
  void a_second_turn_starts_from_what_the_first_one_left(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    Outcome first = fixture.speakAndWait(conversation.id(), "hello", "s-cli");
    Outcome second = fixture.speakAndWait(conversation.id(), "again", "s-cli");

    assertEquals(Outcome.Ending.ANSWERED, first.ending());
    assertEquals(Outcome.Ending.ANSWERED, second.ending());
    assertTrue(first.modelCalls() > 0, "the first turn made no model call to spend");
    assertEquals(
        first.modelCalls() + second.modelCalls(),
        conversations.find(conversation.id()).orElseThrow().budget().spent());
  }

  // --- an ending that is not ANSWERED ---------------------------------------------

  /**
   * A turn that hits its turn cap does not end the conversation.
   *
   * <p>{@code TURN_CAP} is a fact about one turn. What this asserts is that the spending was
   * recorded anyway and that the next utterance is accepted and runs — a {@code Turn} that only
   * wrote the row for an answer, or that closed the conversation on a non-answer, fails on one of
   * the two.
   */
  @Test
  void a_turn_that_did_not_answer_does_not_end_the_conversation(@TempDir Path dir) {
    int cap = 2;
    Fixture fixture = new Fixture(dir, Script.neverAnswers(), cap, DEFINITION_CALLS);
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    Outcome capped = fixture.speakAndWait(conversation.id(), "hello", "s-cli");
    assertEquals(Outcome.Ending.TURN_CAP, capped.ending());
    assertEquals(cap, conversations.find(conversation.id()).orElseThrow().budget().spent());

    Outcome next = fixture.speakAndWait(conversation.id(), "again", "s-cli");
    assertEquals(
        Outcome.Ending.TURN_CAP,
        next.ending(),
        "the utterance after a capped turn did not run its own turns");
    assertEquals(cap * 2, conversations.find(conversation.id()).orElseThrow().budget().spent());
  }

  // --- the turn cap a turn runs under ---------------------------------------------

  /**
   * A turn runs under the conversation's ceiling and not the agent's.
   *
   * <p>The discriminating assertion is the count, exactly as it is for the budget one section
   * above: a turn capped at the definition's number would end {@code TURN_CAP} too, having taken a
   * different number of turns, so asserting the ending alone would pass against the mistake this is
   * for.
   */
  @Test
  void a_turn_runs_under_the_conversations_cap_and_not_the_agents(@TempDir Path dir) {
    int conversationCap = 2;
    Fixture fixture = new Fixture(dir, Script.neverAnswers(), CAP + 5, DEFINITION_CALLS);
    ConversationRecord conversation =
        conversations.open(PAYMENTS, Budget.of(ROOMY), TurnCap.of(conversationCap));

    Outcome outcome = fixture.speakAndWait(conversation.id(), "hello", "s-cli");

    assertEquals(Outcome.Ending.TURN_CAP, outcome.ending());
    assertEquals(
        conversationCap,
        outcome.steps(),
        "the turn was capped at the agent's number rather than the conversation's");
  }

  /**
   * And the narrowest of the three wins: what this utterance was given beats what the conversation
   * decided, which beat what the agent's file says.
   */
  @Test
  void an_utterances_own_cap_beats_the_conversations(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers(), CAP + 5, DEFINITION_CALLS);
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY), TurnCap.of(4));

    Outcome outcome =
        fixture.await(
            fixture.turn.speak(
                conversation.id(),
                fixture.definition,
                "hello",
                "s-cli",
                TurnCap.of(1),
                Speaker.person(null),
                ended -> {}));

    assertEquals(Outcome.Ending.TURN_CAP, outcome.ending());
    assertEquals(1, outcome.steps());
  }

  /**
   * <b>Disabled is honoured, end to end.</b>
   *
   * <p>A conversation that lifted the cap runs a turn far past the number its answering agent's
   * file names, and what stops it is the budget — which is the whole point of separating the two.
   * The ending is the discriminating half: a turn that had quietly been given a number would stop
   * at one.
   */
  @Test
  void a_turn_of_an_uncapped_conversation_is_stopped_by_cost_and_not_by_count(@TempDir Path dir) {
    int spendable = 6;
    Fixture fixture = new Fixture(dir, Script.neverAnswers(), 2, DEFINITION_CALLS);
    ConversationRecord conversation =
        conversations.open(PAYMENTS, Budget.of(spendable), TurnCap.none());

    Outcome outcome = fixture.speakAndWait(conversation.id(), "hello", "s-cli");

    assertEquals(Outcome.Ending.CALL_BUDGET, outcome.ending());
    assertEquals(
        spendable,
        outcome.modelCalls(),
        "it ran until the money ran out, well past its agent's own ceiling");
  }

  /**
   * <b>A ceiling raised while the turn is in flight, picked up without the run being restarted.</b>
   *
   * <p>The transport holds the first model call open, so the raise genuinely happens mid-turn —
   * after the run has read the old ceiling once and while it is inside a call it cannot be
   * interrupted out of. It goes through the job handle, which is the same object {@code POST
   * /v1/jobs/&#123;id&#125;/limits} moves, so what this exercises is the operator's path and not a
   * back door.
   *
   * <p>{@code a_turn_whose_ceiling_nobody_raises_stops_at_it} is the counterfactual: the same
   * fixture without the raise ends {@code TURN_CAP}.
   */
  @Test
  void a_ceiling_raised_while_a_turn_is_in_flight_is_picked_up_by_that_turn(@TempDir Path dir)
      throws Exception {
    Script blocking = Script.blocks();
    Fixture fixture = new Fixture(dir, blocking, 1, DEFINITION_CALLS);
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    String job = fixture.turn.speak(conversation.id(), fixture.definition, "hello", "s-cli");
    blocking.awaitEntry();
    try {
      fixture.jobs.get(job).limits().orElseThrow().cap().changeTo(5);
    } finally {
      blocking.release();
    }

    Outcome outcome = fixture.await(job);
    assertEquals(
        Outcome.Ending.ANSWERED,
        outcome.ending(),
        "it would have stopped at one turn; it was given five while it ran");
    assertTrue(outcome.steps() > 1, "and it took more than the ceiling it started under");
  }

  /**
   * The same fixture with nobody watching it: the run stops at the ceiling it started under, which
   * is what makes the test above about the raise.
   */
  @Test
  void a_turn_whose_ceiling_nobody_raises_stops_at_it(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers(), 1, DEFINITION_CALLS);
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    Outcome outcome = fixture.speakAndWait(conversation.id(), "hello", "s-cli");

    assertEquals(Outcome.Ending.TURN_CAP, outcome.ending());
    assertEquals(1, outcome.steps());
  }

  /**
   * <b>A budget raised while a turn is in flight is written back onto the conversation</b>, so it
   * is a decision about the conversation and not a grant that evaporates when the turn ends.
   *
   * <p>Nothing a model does can move this number — no tool takes one and no schema mentions one.
   * What moved it is an operator, on the run, through the handle {@code POST
   * /v1/jobs/&#123;id&#125;/limits} moves.
   */
  @Test
  void a_budget_raised_on_a_running_turn_is_recorded_on_the_conversation(@TempDir Path dir)
      throws Exception {
    int opened = 2;
    Script blocking = Script.blocks();
    Fixture fixture = new Fixture(dir, blocking);
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(opened));

    String job = fixture.turn.speak(conversation.id(), fixture.definition, "hello", "s-cli");
    blocking.awaitEntry();
    try {
      fixture.jobs.get(job).limits().orElseThrow().budget().changeTo(opened + 8);
    } finally {
      blocking.release();
    }
    fixture.await(job);

    ConversationRecord after = conversations.find(conversation.id()).orElseThrow();
    assertEquals(
        opened + 8,
        after.budget().limit(),
        "the raise was granted for one turn and lost at the row");
    assertTrue(
        after.budget().remaining() > 0,
        "and the next utterance is not refused for a budget that was raised");
  }

  // --- the conversation with nothing left -----------------------------------------

  /**
   * The turn after the one that spent everything is refused, and the refusal names the budget.
   *
   * <p>The assertions are on the words a person reads. A generic "this could not be started" would
   * satisfy an assertion on the type alone and would send whoever met it looking for a broken
   * server; the number and the word {@code budget} are what say the conversation did exactly what
   * it was asked to and has no more room.
   */
  @Test
  void a_conversation_with_nothing_left_refuses_the_next_turn_and_names_its_budget(
      @TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));
    assertEquals(
        Outcome.Ending.CALL_BUDGET,
        fixture.speakAndWait(conversation.id(), "hello", "s-cli").ending());

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.speak(conversation.id(), fixture.definition, "again", "s-cli"));

    String said = refused.getMessage();
    assertTrue(said.contains(conversation.id()), said);
    assertTrue(said.contains("budget"), said);
    assertTrue(
        said.contains(String.valueOf(SPENDABLE)),
        "the refusal does not say how much the conversation was given: " + said);
  }

  /** And a refusal starts nothing: no job is minted for a turn that cannot run. */
  @Test
  void a_refused_utterance_starts_no_job(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));
    fixture.speakAndWait(conversation.id(), "hello", "s-cli");
    int jobsSoFar = fixture.jobs.jobs().size();

    assertThrows(
        Turn.Refused.class,
        () -> fixture.turn.speak(conversation.id(), fixture.definition, "again", null));

    assertEquals(jobsSoFar, fixture.jobs.jobs().size());
  }

  // --- a conversation whose allowance has no ceiling --------------------------------

  /**
   * A conversation opened with no ceiling can actually be spoken into.
   *
   * <p><b>The feature's own primary path, and it 500ed on the first utterance.</b> {@code POST
   * /v1/conversations {"noBudget":true}} answered 200, {@code requireSpeakable} passed the row
   * correctly — turn origin, active — and the very next line asked a budget with no ceiling how
   * much it had remaining, which is a subtraction with nothing to subtract from. Everything about
   * the feature worked except saying anything into it.
   *
   * <p>Two turns rather than one, because one turn is passed by a guard that happens to be checked
   * before any spending exists: what has to hold is that a spend already recorded on the row never
   * becomes a refusal, however far it goes.
   */
  @Test
  void an_utterance_into_a_conversation_with_no_ceiling_runs(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.none());

    Outcome first = fixture.speakAndWait(conversation.id(), "hello", "s-cli");
    Outcome second = fixture.speakAndWait(conversation.id(), "again", "s-cli");

    assertEquals(Outcome.Ending.ANSWERED, first.ending());
    assertEquals(Outcome.Ending.ANSWERED, second.ending());
    ConversationRecord after = conversations.find(conversation.id()).orElseThrow();
    assertFalse(after.budget().capped(), "the row came back with a ceiling nobody set");
    assertEquals(
        first.modelCalls() + second.modelCalls(),
        after.budget().spent(),
        "a lifted budget stopped counting, which is the half of it that must not go");
  }

  /**
   * And a run stopped inside one is continued with no grant at all.
   *
   * <p>{@code Turn.resume} carried the identical pair of reads and so failed identically, one door
   * over. There is nothing to grant here — the conversation cannot run out — so the body names no
   * allowance, and the turn cap is what actually stopped the run.
   */
  @Test
  void a_stopped_run_in_a_conversation_with_no_ceiling_is_continued(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    // A cap on the conversation, because a lifted budget stops nothing and a
    // run scripted never to answer has to stop somewhere continuable. It is
    // also the honest shape: turns are the only thing left bounding a turn
    // in a conversation like this.
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.none(), TurnCap.of(2));
    assertEquals(
        Outcome.Ending.TURN_CAP,
        fixture.speakAndWait(conversation.id(), "hello", "s-cli").ending(),
        "this test says nothing unless the run really stopped somewhere continuable");
    int spentByTheFirstRun = conversations.find(conversation.id()).orElseThrow().budget().spent();

    Outcome continued =
        fixture.await(
            fixture.turn.resume(conversation.id(), fixture.definition, "s-cli", null, null));

    assertEquals(Outcome.Ending.TURN_CAP, continued.ending());
    assertTrue(
        conversations.find(conversation.id()).orElseThrow().budget().spent() > spentByTheFirstRun,
        "the continuation spent nothing, so it never ran");
  }

  /**
   * A grant naming a number for a conversation with no ceiling is refused as an argument, and the
   * sentence is the one written for the operator.
   *
   * <p><b>A {@link io.aeyer.plowshare.server.faults.CallerFault} and not a {@code Refused}</b>,
   * which is what makes it a 400 at {@code ConversationController.resume} rather than a 409 or — as
   * it was — an uncaught 500. {@code Budget.changeTo} refuses with {@code IllegalStateException}
   * and {@code Turn.grant} converts it at the call, on the reasoning set out there: catching that
   * type around the whole of {@code resume} would answer 400 for every failure underneath it.
   * {@code AgentController.limits} answers the identical refusal at the other door, through {@code
   * Budget.changeToOrRefuse}, and it answers it in the same type — which is the point. {@code
   * Turn.grant} used to convert to {@code IllegalArgumentException}, a type {@code Faults} has no
   * row for, so the 400 existed only in {@code ConversationController.resume}'s own catch and a
   * frame handler reaching the same grant would have been told 500.
   */
  @Test
  void granting_model_calls_to_a_conversation_with_no_ceiling_is_refused(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.none(), TurnCap.of(2));
    assertEquals(
        Outcome.Ending.TURN_CAP,
        fixture.speakAndWait(conversation.id(), "hello", "s-cli").ending());

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> fixture.turn.resume(conversation.id(), fixture.definition, "s-cli", null, 60));

    assertTrue(refused.getMessage().contains("no ceiling"), refused.getMessage());
  }

  // --- what a grant of model calls has to be worth ----------------------------------

  /**
   * A grant of N model calls leaves the continued run N to spend.
   *
   * <p><b>{@code maxModelCalls} is a new total and not an increment, and the console shipped a
   * control that did not know it.</b> A turn cap bounds one run and the continued run starts again
   * at zero turns, so the number that stopped the last run is a full grant again; a budget is
   * conversation-cumulative and shared by reference down the delegation tree, so the same trick
   * applied to it hands back the number the conversation has already spent. Asserting the ending
   * alone would pass against that mistake — see the test below, which is what it actually does — so
   * the discriminator here is the count.
   */
  @Test
  void a_grant_of_model_calls_leaves_the_continued_run_that_many_to_spend(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));
    assertEquals(
        Outcome.Ending.CALL_BUDGET,
        fixture.speakAndWait(conversation.id(), "hello", "s-cli").ending());

    Outcome continued =
        fixture.await(
            fixture.turn.resume(
                conversation.id(), fixture.definition, "s-cli", null, SPENDABLE + SPENDABLE));

    assertEquals(Outcome.Ending.CALL_BUDGET, continued.ending());
    assertEquals(
        SPENDABLE,
        continued.modelCalls(),
        "a grant of " + SPENDABLE + " more did not buy " + SPENDABLE + " calls");
    assertEquals(
        SPENDABLE + SPENDABLE,
        conversations.find(conversation.id()).orElseThrow().budget().spent());
  }

  /**
   * And a grant of exactly what was already spent buys nothing, which is the refusal the console's
   * own button was walking into.
   *
   * <p>The button read "grant 20 more model calls" and sent the exhausted total back as the new
   * total. {@code changeTo} takes it — it is not below the spending — and leaves the budget with
   * nothing in it, so this is where the person's grant actually ended up. Named here so that the
   * arithmetic above has a failing case to be the answer to.
   */
  @Test
  void a_grant_of_exactly_what_was_already_spent_continues_nothing(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));
    assertEquals(
        Outcome.Ending.CALL_BUDGET,
        fixture.speakAndWait(conversation.id(), "hello", "s-cli").ending());

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.resume(
                    conversation.id(), fixture.definition, "s-cli", null, SPENDABLE));

    assertTrue(refused.getMessage().contains("spent all"), refused.getMessage());
  }

  // --- archiving has to bite -------------------------------------------------------

  /**
   * An archived conversation takes no new turn.
   *
   * <p><b>Without this the state is decorative</b>, which is the failure the retention design names
   * by name: a state added as a listing filter that quietly does nothing else. A listing filter is
   * not enough on its own — an id survives a listing, in a console tab left open or a script, so a
   * state enforced only by a query is one anything holding the id walks straight past.
   *
   * <p>The refusal names the way back, because {@code archived} is reversible and a person meeting
   * a refusal with no next step opens another conversation and loses the history.
   */
  @Test
  void an_archived_conversation_refuses_a_new_turn_and_says_how_to_come_back(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));
    conversations.moveTo(conversation.id(), ConversationLifecycle.ARCHIVED);

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.speak(conversation.id(), fixture.definition, "hello", "s-cli"));

    String said = refused.getMessage();
    assertTrue(said.contains(conversation.id()), said);
    assertTrue(said.contains("archived"), said);
    assertTrue(
        said.contains("lifecycle"),
        "a reversible state whose refusal does not say how to reverse it sends a person"
            + " to open a second conversation: "
            + said);
  }

  /**
   * And a stopped run inside one cannot be continued.
   *
   * <p><b>Both doors and not one.</b> {@code POST /v1/conversations/&#123;id&#125;/resume} starts a
   * turn just as an utterance does — it writes entries, spends the budget and ends in a {@code
   * turns} row — so a guard on {@link Turn#speak} alone would leave the whole of what archiving
   * stops reachable through the other verb.
   */
  @Test
  void an_archived_conversation_refuses_a_resumption_of_its_stopped_run(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.neverAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));
    assertEquals(
        Outcome.Ending.CALL_BUDGET,
        fixture.speakAndWait(conversation.id(), "hello", "s-cli").ending(),
        "this test says nothing unless the run really stopped somewhere continuable");
    conversations.moveTo(conversation.id(), ConversationLifecycle.ARCHIVED);

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.resume(
                    conversation.id(), fixture.definition, "s-cli", null, SPENDABLE * 4));

    assertTrue(refused.getMessage().contains("archived"), refused.getMessage());
  }

  /**
   * Unarchiving really gives the conversation back, which is what makes the refusal above a state
   * rather than an ending.
   */
  @Test
  void an_unarchived_conversation_takes_a_turn_again(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(SPENDABLE));
    conversations.moveTo(conversation.id(), ConversationLifecycle.ARCHIVED);
    conversations.moveTo(conversation.id(), ConversationLifecycle.ACTIVE);

    assertEquals(
        Outcome.Ending.ANSWERED,
        fixture.speakAndWait(conversation.id(), "hello", "s-cli").ending());
  }

  // --- the conversation that is not there, and the one that is busy ----------------

  /** An utterance on a conversation nothing opened is refused, naming the id. */
  @Test
  void an_utterance_on_a_conversation_nothing_opened_is_refused(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());

    ArchiveException refused =
        assertThrows(
            ArchiveException.class,
            () -> fixture.turn.speak("cnv_9999", fixture.definition, "hello", "s-cli"));
    assertTrue(refused.getMessage().contains("cnv_9999"), refused.getMessage());
  }

  /**
   * One turn at a time in a conversation, and the second is refused rather than quietly given the
   * whole remainder.
   *
   * <p>This is the question {@code ConversationRecord}'s javadoc leaves to "the task that builds
   * turns": two turns in flight each read the row and are each told the whole remainder is theirs,
   * so between them they can spend twice what the conversation holds. A person at a terminal speaks
   * once and waits, so refusing is the honest shape, and the second half of this test is what says
   * the refusal is not permanent.
   */
  @Test
  void a_conversation_holds_one_turn_at_a_time(@TempDir Path dir) throws Exception {
    Script blocking = Script.blocks();
    Fixture fixture = new Fixture(dir, blocking);
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    String first = fixture.turn.speak(conversation.id(), fixture.definition, "hello", "s-cli");
    blocking.awaitEntry();
    try {
      Turn.Refused refused =
          assertThrows(
              Turn.Refused.class,
              () -> fixture.turn.speak(conversation.id(), fixture.definition, "over you", "s-cli"));
      assertTrue(refused.getMessage().contains(conversation.id()), refused.getMessage());
    } finally {
      blocking.release();
    }
    assertEquals(Outcome.Ending.ANSWERED, fixture.await(first).ending());

    assertEquals(
        Outcome.Ending.ANSWERED,
        fixture.speakAndWait(conversation.id(), "again", "s-cli").ending());
  }

  // --- hearing a conversation come free ---------------------------------------------

  /**
   * {@code alsoEnded} runs after the conversation is free, not before — {@code Dispatcher}'s reason
   * for existing: a queued firing started from inside that callback must find {@link
   * Turn#isSpeaking} already false, or it would be refused the very turn it was woken to take.
   */
  @Test
  void a_turn_reports_its_ending_after_the_conversation_is_free_again(@TempDir Path dir)
      throws Exception {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));
    AtomicBoolean freeWhenEnded = new AtomicBoolean();
    CountDownLatch ended = new CountDownLatch(1);

    fixture.turn.speak(
        conversation.id(),
        fixture.definition,
        "hello",
        null,
        null,
        Speaker.person(null),
        outcome -> {
          freeWhenEnded.set(!fixture.turn.isSpeaking(conversation.id()));
          ended.countDown();
        });

    assertTrue(ended.await(30, TimeUnit.SECONDS));
    assertTrue(freeWhenEnded.get());
  }

  /**
   * Every {@link Turn#whenFree} listener hears a conversation become free, and one that throws does
   * not stop the others from being told.
   */
  @Test
  void every_listener_hears_a_conversation_become_free(@TempDir Path dir) throws Exception {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));
    List<String> heard = new CopyOnWriteArrayList<>();
    CountDownLatch both = new CountDownLatch(2);

    fixture.turn.whenFree(
        id -> {
          throw new IllegalStateException("a broken listener");
        });
    fixture.turn.whenFree(
        id -> {
          heard.add(id);
          both.countDown();
        });
    fixture.turn.whenFree(id -> both.countDown());

    fixture.turn.speak(conversation.id(), fixture.definition, "hello", null);

    assertTrue(both.await(30, TimeUnit.SECONDS));
    assertEquals(List.of(conversation.id()), heard);
  }

  // --- who spoke: 2026-09-28-the-log-is-the-source §2 ---------------------------------------

  @Test
  void a_person_s_utterance_is_recorded_as_theirs(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    fixture.await(
        fixture.turn.speak(
            conversation.id(),
            fixture.definition,
            "hello",
            "s-cli",
            null,
            Speaker.person("enzo"),
            outcome -> {}));

    EntryPage.Row said = entryRows.pageOfLog(conversation.id(), 0, 10).listed().get(0);
    assertEquals(EntryKind.UTTERANCE, said.kind());
    assertEquals(Speaker.person("enzo"), said.speaker());
  }

  @Test
  void a_delivered_utterance_is_recorded_as_the_harness_s_naming_its_run(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    fixture.await(
        fixture.turn.deliver(
            conversation.id(),
            fixture.definition,
            "The orchestration 'build' (id orc_1) finished.",
            Speaker.orchestration("orc_1"),
            outcome -> {}));

    EntryPage.Row said = entryRows.pageOfLog(conversation.id(), 0, 10).listed().get(0);
    assertEquals(Speaker.orchestration("orc_1"), said.speaker());
  }

  @Test
  void a_door_that_names_nobody_keeps_its_old_meaning(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord spoken = conversations.open(PAYMENTS, Budget.of(ROOMY));
    ConversationRecord delivered = conversations.open(PAYMENTS, Budget.of(ROOMY));

    fixture.speakAndWait(spoken.id(), "hello", "s-cli");
    fixture.await(
        fixture.turn.deliver(delivered.id(), fixture.definition, "a delivery", outcome -> {}));

    assertEquals(
        Speaker.person(null), entryRows.pageOfLog(spoken.id(), 0, 10).listed().get(0).speaker());
    assertEquals(
        Speaker.harness(), entryRows.pageOfLog(delivered.id(), 0, 10).listed().get(0).speaker());
  }

  // --- what a turn carries ---------------------------------------------------------

  /**
   * The run happens in the home the conversation was opened in.
   *
   * <p><b>This is the half of the pair below that discriminates, and it was measured rather than
   * assumed.</b> With {@code Turn} changed to submit {@code Home.global()} regardless of the row —
   * the shape of a conversation path that forgot to read the home — this test fails and {@code
   * a_turn_of_a_global_conversation_runs_in_global} still passes, because the mutant makes global
   * the answer to everything. The global one is here for the opposite mistake, a path that treated
   * a NULL project as a missing home and refused or substituted one, and neither covers the other.
   */
  @Test
  void a_turn_runs_in_the_home_the_conversation_was_opened_in(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    fixture.speakAndWait(conversation.id(), "hello", "s-cli");

    assertEquals(List.of(PAYMENTS), fixture.providers.homes());
  }

  /**
   * A conversation opened in global is an ordinary conversation, and its turns run in global —
   * which is the absence of a project and not a project called "global".
   */
  @Test
  void a_turn_of_a_global_conversation_runs_in_global(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(Home.global(), Budget.of(ROOMY));

    fixture.speakAndWait(conversation.id(), "hello", "s-cli");

    assertEquals(List.of(Home.global()), fixture.providers.homes());
  }

  /** The session the utterance was submitted under reaches the wiring. */
  @Test
  void a_turn_carries_the_session_it_was_submitted_under(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    fixture.speakAndWait(conversation.id(), "hello", "s-cli");

    assertEquals(List.of("s-cli"), fixture.providers.sessions());
  }

  /**
   * And a turn with no session is an ordinary turn.
   *
   * <p>The null is asserted rather than the run merely finishing: a conversation path that
   * substituted the conversation id, or {@code ""}, for a missing session would satisfy "it ran"
   * and would be a different value reaching the wiring that decides what the run can see.
   */
  @Test
  void a_turn_with_no_session_is_an_ordinary_turn(@TempDir Path dir) {
    Fixture fixture = new Fixture(dir, Script.looksThenAnswers());
    ConversationRecord conversation = conversations.open(PAYMENTS, Budget.of(ROOMY));

    assertEquals(
        Outcome.Ending.ANSWERED, fixture.speakAndWait(conversation.id(), "hello", null).ending());

    assertEquals(1, fixture.providers.sessions().size(), fixture.providers.sessions().toString());
    assertNull(fixture.providers.sessions().get(0), fixture.providers.sessions().toString());
  }

  // --- the door a person reaches ----------------------------------------------------

  /**
   * The conversation in the request body reaches {@code Turn}, with the session beside it, and the
   * plain submission door is not used.
   */
  @Test
  void the_conversation_in_the_request_body_reaches_the_turn() throws Exception {
    JobStore jobs = mock(JobStore.class);
    Turn turn = mock(Turn.class);
    when(turn.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenReturn("job_000009");

    mvc(jobs, turn)
        .perform(
            post("/v1/agents/echo/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "hello", "conversation": "cnv_0001",
                                 "session": "s-cli"}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000009"));

    verify(turn)
        .speak(
            eq("cnv_0001"),
            any(AgentDefinition.class),
            eq("hello"),
            eq("s-cli"),
            isNull(),
            eq(Speaker.person(null)),
            any());
    verifyNoInteractions(jobs);
  }

  /** A turn may be spoken with no session, through the same door. */
  @Test
  void a_turn_submitted_over_http_with_no_session_still_reaches_the_turn() throws Exception {
    JobStore jobs = mock(JobStore.class);
    Turn turn = mock(Turn.class);
    when(turn.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenReturn("job_000009");

    mvc(jobs, turn)
        .perform(
            post("/v1/agents/echo/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "hello", "conversation": "cnv_0001"}"""))
        .andExpect(status().isAccepted());

    verify(turn)
        .speak(
            eq("cnv_0001"),
            any(AgentDefinition.class),
            eq("hello"),
            isNull(),
            isNull(),
            eq(Speaker.person(null)),
            any());
  }

  /** A body naming no conversation is the plain run it has always been. */
  @Test
  void a_request_naming_no_conversation_is_still_a_plain_run() throws Exception {
    JobStore jobs = mock(JobStore.class);
    Turn turn = mock(Turn.class);
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    mvc(jobs, turn)
        .perform(
            post("/v1/agents/echo/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "hello", "project": "payments"}"""))
        .andExpect(status().isAccepted());

    verify(jobs)
        .submit(
            any(AgentDefinition.class),
            eq("hello"),
            eq(Home.of("payments")),
            isNull(),
            isNull(),
            eq(List.of()),
            eq(true),
            isNull());
    verify(turn, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
  }

  /**
   * A body naming both a conversation and a project is refused.
   *
   * <p>A conversation is opened in a home and its turns run there. A request carrying both has two
   * answers to one question, and taking the conversation's silently is how a person comes to
   * believe a run reached a project it never touched.
   */
  @Test
  void a_request_naming_both_a_conversation_and_a_project_is_refused() throws Exception {
    JobStore jobs = mock(JobStore.class);
    Turn turn = mock(Turn.class);

    mvc(jobs, turn)
        .perform(
            post("/v1/agents/echo/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "hello", "conversation": "cnv_0001",
                                 "project": "payments"}"""))
        .andExpect(status().isBadRequest());

    verify(turn, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
    verifyNoInteractions(jobs);
  }

  /**
   * A conversation with nothing left is a 409 over HTTP, and the sentence survives the mapping.
   *
   * <p>409 and not 500: the row is there and the server will not start a turn on it, which is what
   * {@code ArchiveRefusedException} means one package over. A 500 would tell a caller the server is
   * broken, and the repair for that is nothing the caller can do.
   */
  @Test
  void a_spent_conversation_is_a_conflict_over_http() throws Exception {
    JobStore jobs = mock(JobStore.class);
    Turn turn = mock(Turn.class);
    when(turn.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenThrow(new Turn.Refused("conversation cnv_0001 has spent its whole budget"));

    mvc(jobs, turn)
        .perform(
            post("/v1/agents/echo/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"task": "hello", "conversation": "cnv_0001"}"""))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("budget")));
  }

  // --- scaffolding -------------------------------------------------------------------

  private static final String AGENT = "talker";

  /**
   * Everything one conversation needs to be spoken into, wired the way production wires it apart
   * from the transport.
   */
  private final class Fixture {

    private final Recording providers = new Recording();
    private final JobStore jobs;
    private final Turn turn;
    private final AgentDefinition definition;
    private final JobRuntime runtime;
    private final Compaction compaction;

    Fixture(Path dir, Script script) {
      this(dir, script, CAP, DEFINITION_CALLS);
    }

    Fixture(Path dir, Script script, int maxTurns, int maxModelCalls) {
      LlmDispatcher models = dispatcherOver(script);
      JobRuntime runtime = new JobRuntime(models, List.of(), null, providers);
      this.runtime = runtime;
      write(dir, maxTurns, maxModelCalls);
      this.definition = AgentRegistry.of(dir, runtime.knownTools()).get(AGENT);
      this.jobs = new JobStore(runtime);
      // A real Compaction, and nothing here ever compacts. That used to
      // be because the pool's transport is a bare LlmTransport, so
      // LlmDispatcher.contextLength answered empty; it no longer does --
      // the dispatcher falls back to a configured default now -- so the
      // reason is written out rather than inherited.
      //
      // TWO independent reasons, and the first is the one that fires.
      // This fixture's transport reports TokenUsage.UNKNOWN, so
      // JobRuntime never calls promptMeasured, so a fold returns at
      // `sent == 0` before it asks for a context length at all. The
      // second is the number below: a million folds at 333 333, which no
      // fixture here comes near.
      //
      // That is the point: these tests are about the budget and the
      // transcript, and a compaction firing in the middle of one would
      // spend a call none of their arithmetic accounts for. CompactionTest
      // is where a context length exists.
      this.compaction =
          new Compaction(
              models, TurnTest::folder, turnRows, compactionRows, entryRows, 1_000_000, folds);
      this.turn = new Turn(jobs, conversations, turnRows, compaction);
      opened.add(jobs);
    }

    Outcome speakAndWait(String conversation, String utterance, String sessionId) {
      return await(turn.speak(conversation, definition, utterance, sessionId));
    }

    /**
     * The job handle itself, once it is done — for a test that reads {@link Job#conversation()} or
     * {@link Job#conversationOrigin()} rather than only the outcome the run came to.
     */
    Job jobFor(String conversation, String utterance, String sessionId) {
      String job = turn.speak(conversation, definition, utterance, sessionId);
      await(job);
      return jobs.get(job);
    }

    /**
     * Wait for the job to be {@code DONE}.
     *
     * <p>Enough on its own, and that is a property of {@code JobStore} rather than of this loop:
     * the conversation's row is written before the outcome is filed, so a job this method returns
     * for has already had its spending recorded. {@code
     * what_a_turn_spent_is_recorded_before_the_job_reports_it_has_ended} is what holds that, and a
     * sleep here would be papering over an ordering that does not need one.
     */
    Outcome await(String job) {
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
  }

  /**
   * A transport answering from a rule rather than from a queue.
   *
   * <p>{@code SessionSubmissionTest.Scripted} answers a fixed list and then falls back to "done",
   * which cannot express "never answers" — the shape every budget test here needs. The rule reads
   * the run's own history rather than a counter, so it resets between turns without this class
   * knowing that turns exist: a {@code tool} message in the conversation is a tool result this run
   * has already had back.
   *
   * <p><b>It used to read "anything longer than the two-message opening", and the compaction task
   * made that false.</b> A run in a conversation opens with everything already said, so a second
   * turn's very first call now arrives with four messages and a rule counting them would have
   * answered without ever asking for a tool — quietly turning every two-call turn here into a
   * one-call turn. A {@code tool} message is the thing that was actually meant, and a history never
   * carries one: {@code Compaction} emits what was said and what came back, and no working in
   * between.
   */
  private static class Script implements LlmTransport {

    private final boolean everAnswers;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch held;

    private Script(boolean everAnswers, boolean blocks) {
      this.everAnswers = everAnswers;
      this.held = new CountDownLatch(blocks ? 1 : 0);
    }

    /** Asks for a tool for ever, so a run ends at whichever limit bites first. */
    static Script neverAnswers() {
      return new Script(false, false);
    }

    /**
     * Asks for a file tool once — so the wiring is asked for providers — and answers on the turn
     * after it. Two model calls per turn.
     */
    static Script looksThenAnswers() {
      return new Script(true, false);
    }

    /**
     * Holds every call open until released, so a second utterance arrives while a turn is genuinely
     * in flight.
     */
    static Script blocks() {
      return new Script(true, true);
    }

    void awaitEntry() throws InterruptedException {
      assertTrue(entered.await(10, TimeUnit.SECONDS), "no turn ever reached the model");
    }

    void release() {
      held.countDown();
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      entered.countDown();
      try {
        assertTrue(held.await(10, TimeUnit.SECONDS), "a held call was never released");
      } catch (InterruptedException stop) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted in the transport", stop);
      }
      boolean toolAnswered =
          messages.stream().anyMatch(message -> message.role() == ChatMessage.Role.TOOL);
      return toolAnswered && everAnswers
          ? new Completion("done", "stop", TokenUsage.UNKNOWN, List.of())
          : new Completion(
              "",
              "tool_calls",
              TokenUsage.UNKNOWN,
              List.of(new ToolCall("1", FileTools.ROOTS_NAME, "{}")));
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      // The job runtime streams now. This double answers the same
      // thing either way, on purpose: reconciling two wire formats is the
      // transport's problem and OpenAiTransportTest is where it is
      // proved, so a fake that answered differently down this path would
      // only be testing itself. Delegating to complete(...) keeps every
      // assertion in this class — what a turn was offered, what it sent,
      // what came back — meaning exactly what it meant.
      Completion streamed = complete(wireModel, messages, sampling, tools);
      // Asked after the call, which is where a fake can honestly ask it:
      // the real transport asks once per chunk, and this one has exactly
      // one chunk. See LlmTransport.stream and CallerAbandonedException.
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
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException("a turn does not embed");
    }

    @Override
    public void close() {}
  }

  /**
   * The wiring seam, recording the home and the session each run asked it for. A list that may hold
   * nulls, deliberately: "no session" is a value this seam is asked for and not a case to filter
   * away before the assertion sees it.
   */
  private static final class Recording implements RunProviders {

    private final List<Home> homes = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sessions = Collections.synchronizedList(new ArrayList<>());

    @Override
    public List<FileProvider> forRun(
        Home home, List<Grant> grants, String sessionId, String owner) {
      homes.add(home);
      sessions.add(sessionId);
      return List.of();
    }

    List<Home> homes() {
      synchronized (homes) {
        return new ArrayList<>(homes);
      }
    }

    List<String> sessions() {
      synchronized (sessions) {
        return new ArrayList<>(sessions);
      }
    }
  }

  /**
   * The fixture agent, granted one orchestration — there is no frontmatter key on the fixture for
   * this, so it is built by hand off the loaded definition, on {@code JobRuntimeTest#granting}'s
   * pattern.
   */
  private static AgentDefinition granting(AgentDefinition plain, String orchestration) {
    return new AgentDefinition(
        plain.name(),
        plain.description(),
        plain.model(),
        plain.intent(),
        plain.sampling(),
        plain.tools(),
        plain.calls(),
        plain.scopes(),
        plain.maxTurns(),
        plain.maxModelCalls(),
        plain.prompt(),
        plain.exported(),
        plain.delegable(),
        plain.vision(),
        plain.bot(),
        plain.announcesInbox(),
        plain.fallback(),
        List.of(orchestration));
  }

  private static LlmDispatcher dispatcherOver(LlmTransport transport) {
    return new LlmDispatcher(
        List.of(
            new LlmPool(
                "scripted",
                List.of("model-fast"),
                Map.of("fast", "model-fast"),
                4,
                1,
                Duration.ofSeconds(5),
                transport)),
        new NoOpTokenLedger());
  }

  /** A definition written the way an operator writes one, loaded the way a boot loads it. */
  private static void write(Path dir, int maxTurns, int maxModelCalls) {
    try {
      Files.createDirectories(dir);
      Files.writeString(
          dir.resolve(AGENT + ".md"),
          "---\n"
              + "name: "
              + AGENT
              + "\n"
              + "description: a fixture agent\n"
              + "model: fast\n"
              + "tools: ["
              + FileTools.ROOTS_NAME
              + "]\n"
              + "scopes: [workspace:read]\n"
              + "max-turns: "
              + maxTurns
              + "\n"
              + "max-model-calls: "
              + maxModelCalls
              + "\n"
              + "---\n"
              + "You talk.\n");
    } catch (IOException e) {
      throw new UncheckedIOException("could not write the fixture " + AGENT, e);
    }
  }

  /**
   * The controller, standalone, over mocks. The same harness shape {@code AgentControllerTest}
   * builds, and the same Jackson settings, since a plain mapper differs from Spring Boot's
   * auto-configured one.
   */
  private static MockMvc mvc(JobStore jobs, Turn turn) {
    // AgentController.run reads Turn.homeOf before it reads anything a
    // null Home would NPE inside, for every request naming a conversation
    // -- which every test below this helper does. Global is the default
    // every one of them already ran against before that read existed, so
    // this is a stub and not a behaviour change.
    when(turn.homeOf(anyString())).thenReturn(PAYMENTS);
    ObjectMapper json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    AgentRegistry registry =
        new AgentRegistry(
            Map.of(
                "echo",
                new AgentDefinition(
                    "echo",
                    "a fixture",
                    "fast",
                    List.of(),
                    List.of(),
                    List.of(),
                    2,
                    4,
                    // Exported: a turn is started by naming the agent over HTTP,
                    // which is one of the four doors that key gates.
                    "You do one thing.",
                    true,
                    true)));
    // DataLayout.NONE and an always-false projectExists: no request built
    // in this file names a project a row exists for, so every Caller
    // resolves with a null project id and DefinitionResolver.forCaller
    // answers `registry` before either is ever consulted.
    DefinitionResolver resolver =
        new DefinitionResolver(
            registry,
            DataLayout.NONE,
            id -> false,
            Set.of(),
            Set.of(),
            mock(SessionChannel.class),
            session -> true,
            DefinitionChecks.NONE);
    // The services wired as the container wires them: one Callers, shared
    // by the two services that take one. The controller builds none of them.
    ProjectStore projects = mock(ProjectStore.class);
    Callers callers =
        new Callers(
            resolver,
            projects,
            turn,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
    return MockMvcBuilders.standaloneSetup(
            new AgentController(
                jobs,
                resolver,
                projects,
                callers,
                new Runs(callers, jobs, turn),
                new Pictures(ImageStore.NONE),
                new Passes(jobs, mock(Curator.class), new AgentsProperties()),
                new Limits(jobs),
                new Definitions(mock(DefinitionWriter.class), resolver, projects, callers)))
        .setControllerAdvice(new ApiExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
        .build();
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
