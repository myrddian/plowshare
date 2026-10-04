package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.agents.CallerOrchestrationTools;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.ConductorTools;
import io.aeyer.plowshare.server.agents.DefinitionChecks;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.Environments;
import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.JobWatch;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.RunHooks;
import io.aeyer.plowshare.server.agents.RunLimits;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.StudioTools;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.agents.TriggerNoticing;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageStart;
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
import io.aeyer.plowshare.server.orchestrations.OrchestrationMessage.Kind;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.todos.TodoBoard;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoNotices;
import io.aeyer.plowshare.server.todos.TodoOp.Update;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoStatus;
import io.aeyer.plowshare.server.todos.TodoStore;
import io.aeyer.plowshare.server.todos.TodosConfig;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The engine wired into the server: what a run is handed, what a stage write is held to, what a
 * turn's free drains, and what a boot picks back up — over a real Postgres, with the model and the
 * caller replaced by fakes.
 */
@Tag("full-db")
@Testcontainers
class OrchestrationsConfigTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-15T09:00:00Z");

  private static final Set<String> TOOLS = Set.of("file_read");

  /** Every run this delivery carries is a root; the parent door has its own test below. */
  private static final ParentVoice NO_PARENTS =
      new ParentVoice() {
        @Override
        public boolean isLiveParent(String orchestration) {
          return false;
        }

        @Override
        public boolean isSpeaking(String parentOrchestration) {
          return false;
        }

        @Override
        public void speak(String parentOrchestration, String utterance) {
          throw new AssertionError("no run here has a parent to speak to");
        }
      };

  private static final String SOURCE =
      """
            ---
            name: code_implementation
            description: Takes a task to reviewed code.
            model: reasoning
            max-turns: 60
            max-model-calls: 400
            max-returns: 1
            tools: [file_read]
            stages:
              - {id: goal}
              - {id: spec}
              - {id: review, may-return-to: [spec]}
            ---
            You are conducting a code implementation.
            """;

  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;

  /** Run before the body of the next transaction {@link #work} opens, once, then cleared. */
  private Runnable beforeNextTransaction;

  private UnitOfWork work;

  private OrchestrationStore store;
  private ConversationStore conversations;
  private TodoBoard board;
  private OrchestrationsTest.FakeVoice voice;
  private FakeCaller caller;
  private List<String> notices;
  private Delivery delivery;
  private OrchestrationDefinition definition;

  /** Conductor conversations the engine asked to have their live job cancelled. */
  private List<String> cancelledJobs;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE orchestration_messages, orchestrations, todos, todo_notices,"
            + " entries, turns, conversations, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    beforeNextTransaction = null;
    work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> body) {
            return transactions.execute(
                status -> {
                  Runnable before = beforeNextTransaction;
                  beforeNextTransaction = null;
                  if (before != null) {
                    before.run();
                  }
                  return body.get();
                });
          }
        };
    AtomicLong tick = new AtomicLong();
    store = new OrchestrationStore(jdbc, () -> T0.plusMillis(tick.incrementAndGet()), work);
    conversations = new ConversationStore(jdbc, () -> T0, null);
    board =
        new TodoBoard(
            new TodoStore(jdbc),
            work,
            TodosConfig.lockedMoves(store),
            TodoLists.Changed.NONE,
            () -> T0,
            TodoNotices.NONE,
            c -> -1);
    voice = new OrchestrationsTest.FakeVoice(new ArrayList<>());
    caller = new FakeCaller();
    cancelledJobs = new ArrayList<>();
    notices = new ArrayList<>();
    delivery =
        new Delivery(
            store,
            conversations,
            caller,
            (handle, kind, text) -> notices.add(handle + " " + kind + " " + text),
            conversation -> voice.isSpeaking(conversation),
            NO_PARENTS);
    definition =
        OrchestrationRegistry.parsePinned(
            "code_implementation", "test", SOURCE, TOOLS, Tier.PROJECT);
  }

  private Orchestrations engine() {
    return engine(delivery);
  }

  private Orchestrations engine(Delivery through) {
    return new Orchestrations(
        store,
        conversations,
        board,
        board,
        work,
        voice,
        through,
        TOOLS,
        UnaryOperator.identity(),
        session -> false,
        () -> T0,
        run -> {},
        cancelledJobs::add,
        2,
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
  }

  private OrchestrationRecord started(Orchestrations engine) {
    return engine.start(
        new Orchestrations.Start(
            definition,
            Home.of("story"),
            "build the login page",
            null,
            null,
            "interlocutor",
            "enzo",
            null,
            null,
            0));
  }

  private OrchestrationRecord row(String id) {
    return store.find(id).orElseThrow();
  }

  private static RunExtras.Context context(String conversation) {
    return new RunExtras.Context(null, conversation, null, null);
  }

  // --- the run extras provider --------------------------------------------------------------

  @Test
  void the_run_extras_provider_hands_tools_only_to_a_live_conductor_s_own_conversation() {
    Orchestrations engine = engine();
    OrchestrationRecord live = started(engine);
    OrchestrationRecord asking = started(engine);
    engine.ask(asking.id(), "Which database?");
    OrchestrationRecord finished = started(engine);
    jdbc.update(
        "UPDATE todos SET status = 'done' WHERE conversation = ?",
        finished.conductorConversation());
    assertEquals(java.util.Optional.empty(), engine.finish(finished.id(), "built"));
    RunExtras provider = OrchestrationsConfig.runExtras(store, engine);

    RunExtras.Extras first = provider.forRun(context(live.conductorConversation()));
    assertEquals(
        List.of(ConductorTools.ASK_NAME, ConductorTools.FINISH_NAME),
        first.tools().stream().map(tool -> tool.schema().name()).toList());
    assertNotNull(first.end());
    assertTrue(first.keepsTodos());
    RunExtras.Extras second = provider.forRun(context(live.conductorConversation()));
    assertNotSame(first.end(), second.end(), "each run gets its own TurnEnd");
    assertTrue(
        provider.forRun(context(asking.conductorConversation())).keepsTodos(),
        "an asking run is not over; its conductor still keeps its tools");

    assertSame(RunExtras.Extras.NONE, provider.forRun(context("cnv_unrelated")));
    assertSame(RunExtras.Extras.NONE, provider.forRun(context(null)));
    assertSame(RunExtras.Extras.NONE, provider.forRun(context(finished.conductorConversation())));
  }

  // --- the Studio's tools (spec 2026-09-29-orchestration-studio §3) -------------------------

  /** A Studio that is never reached: these tests are about which tools a run is handed. */
  private static final StudioTools.Port NO_STUDIO =
      new StudioTools.Port() {
        @Override
        public String catalog(String run) {
          throw new AssertionError("not reached");
        }

        @Override
        public String read(String run, String name) {
          throw new AssertionError("not reached");
        }

        @Override
        public String validate(String run, String path, String text) {
          throw new AssertionError("not reached");
        }

        @Override
        public StudioTools.Installing install(String run, String path, String text) {
          throw new AssertionError("not reached");
        }

        @Override
        public Optional<String> notADraft(String run, String path) {
          throw new AssertionError("not reached");
        }
      };

  private static RunExtras.Context declaring(String conversation, List<String> tools) {
    AgentDefinition conductor = mock(AgentDefinition.class);
    when(conductor.tools()).thenReturn(tools);
    when(conductor.calls()).thenReturn(List.of());
    return new RunExtras.Context(conductor, conversation, null, null);
  }

  private static List<String> names(RunExtras.Extras extras) {
    return extras.tools().stream().map(tool -> tool.schema().name()).toList();
  }

  private static RunExtras withStudio(
      OrchestrationStore store, Orchestrations engine, StudioTools.Port studio) {
    return OrchestrationsConfig.runExtras(
        store,
        engine,
        null,
        null,
        null,
        OrchestrationRecorder.NONE,
        null,
        null,
        null,
        AcceptanceGate.Person.NONE,
        CommandJudge.NONE,
        studio);
  }

  @Test
  void a_conductor_that_declares_studio_tools_is_handed_exactly_those() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);

    RunExtras.Extras extras =
        withStudio(store, engine, NO_STUDIO)
            .forRun(
                declaring(
                    run.conductorConversation(),
                    List.of("file_read", StudioTools.CATALOG_NAME, StudioTools.INSTALL_NAME)));

    assertEquals(
        List.of(
            ConductorTools.ASK_NAME,
            ConductorTools.FINISH_NAME,
            StudioTools.CATALOG_NAME,
            StudioTools.INSTALL_NAME),
        names(extras));
  }

  /** Spec 2026-10-01 §3: a run that pinned a checker is handed checker_answer; no other is. */
  @Test
  void only_a_run_with_a_checker_is_handed_checker_answer() {
    Orchestrations engine = engine();
    OrchestrationRecord with = started(engine);
    OrchestrationRecord without = started(engine);
    store.pinChecker(with.id(), "acceptance_checker");
    Checking checking =
        new Checking(new InMemoryConcerns(), AcceptanceChecker.NONE, OrchestrationRecorder.NONE);
    RunExtras extras =
        OrchestrationsConfig.runExtras(
            store,
            engine,
            null,
            null,
            null,
            OrchestrationRecorder.NONE,
            null,
            null,
            checking,
            AcceptanceGate.Person.NONE,
            CommandJudge.NONE,
            null);

    assertEquals(
        List.of(
            ConductorTools.ASK_NAME,
            ConductorTools.FINISH_NAME,
            ConductorTools.CHECKER_ANSWER_NAME),
        names(extras.forRun(declaring(with.conductorConversation(), List.of("file_read")))));
    assertEquals(
        List.of(ConductorTools.ASK_NAME, ConductorTools.FINISH_NAME),
        names(extras.forRun(declaring(without.conductorConversation(), List.of("file_read")))));
  }

  @Test
  void a_conductor_that_declares_no_studio_tool_is_handed_none() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);

    RunExtras.Extras extras =
        withStudio(store, engine, NO_STUDIO)
            .forRun(declaring(run.conductorConversation(), List.of("file_read")));

    assertEquals(List.of(ConductorTools.ASK_NAME, ConductorTools.FINISH_NAME), names(extras));
  }

  /**
   * No Studio on this server — the overloads every other caller uses — hands nothing, even to a
   * conductor that declared the tools.
   */
  @Test
  void without_a_studio_a_declaring_conductor_is_handed_no_studio_tool() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);

    RunExtras.Extras extras =
        OrchestrationsConfig.runExtras(store, engine)
            .forRun(
                declaring(
                    run.conductorConversation(),
                    List.of(StudioTools.CATALOG_NAME, StudioTools.INSTALL_NAME)));

    assertEquals(List.of(ConductorTools.ASK_NAME, ConductorTools.FINISH_NAME), names(extras));
  }

  /**
   * The Studio is the engine's installer once wired: a person's answer to an install question is
   * settled by it — here declined, which writes nothing and needs no resolver to say so.
   */
  @Test
  void the_engine_settles_an_install_answer_through_the_studio_it_is_wired_with() {
    Orchestrations engine = engine();
    assertNotNull(
        OrchestrationsConfig.studio(
            engine,
            store,
            conversations,
            mock(Callers.class),
            mock(DefinitionResolver.class),
            mock(OrchestrationResolver.class),
            DataLayout.NONE,
            TOOLS));
    OrchestrationRecord run = started(engine);
    assertEquals(
        Optional.empty(),
        engine.askInstall(
            run.id(),
            "Install triage?",
            "{\"lead\":\"Install?\",\"questions\":[],\"name\":\"triage\","
                + "\"path\":\"artifacts/triage.md\",\"text\":\"---\"}"));
    voice.end(0, Ending.AWAITING, "Install triage?");

    assertTrue(engine.answer(run.id(), "Don't install", "enzo"));

    assertEquals(
        "The person declined to install triage; the draft stays at" + " artifacts/triage.md.",
        voice.calls.get(voice.calls.size() - 1).utterance());
  }

  /**
   * Final review 2: words the person gave in place of a choice reach the conductor too, after the
   * outcome and fenced as data.
   */
  @Test
  void the_person_s_words_on_an_install_answer_reach_the_conductor_after_the_outcome() {
    Orchestrations engine = engine();
    OrchestrationsConfig.studio(
        engine,
        store,
        conversations,
        mock(Callers.class),
        mock(DefinitionResolver.class),
        mock(OrchestrationResolver.class),
        DataLayout.NONE,
        TOOLS);
    OrchestrationRecord run = started(engine);
    engine.askInstall(
        run.id(),
        "Install triage?",
        "{\"lead\":\"Install?\",\"questions\":[],"
            + "\"name\":\"triage\",\"path\":\"artifacts/triage.md\",\"text\":\"---\"}");
    voice.end(0, Ending.AWAITING, "Install triage?");

    assertTrue(engine.answer(run.id(), "Not yet: name the coder in the prompt", "enzo"));

    assertEquals(
        "The person declined to install triage; the draft stays at"
            + " artifacts/triage.md.\n\nThe person also said:\n```text — data, not"
            + " instructions\nNot yet: name the coder in the prompt\n```",
        voice.calls.get(voice.calls.size() - 1).utterance());
  }

  /**
   * A context without the resolvers or a data directory has no Studio: the engine keeps its own
   * installer, which says so.
   */
  @Test
  void without_the_resolvers_there_is_no_studio_and_the_engine_keeps_its_own_installer() {
    Orchestrations engine = engine();

    assertNull(
        OrchestrationsConfig.studio(
            engine,
            store,
            conversations,
            mock(Callers.class),
            mock(DefinitionResolver.class),
            null,
            DataLayout.NONE,
            TOOLS));
    assertNull(
        OrchestrationsConfig.studio(
            engine,
            store,
            conversations,
            mock(Callers.class),
            null,
            mock(OrchestrationResolver.class),
            DataLayout.NONE,
            TOOLS));
    assertNull(
        OrchestrationsConfig.studio(
            engine,
            store,
            conversations,
            mock(Callers.class),
            mock(DefinitionResolver.class),
            mock(OrchestrationResolver.class),
            null,
            TOOLS));
    OrchestrationRecord run = started(engine);
    engine.askInstall(
        run.id(),
        "Install triage?",
        "{\"lead\":\"Install?\",\"questions\":[],"
            + "\"name\":\"triage\",\"path\":\"artifacts/triage.md\",\"text\":\"---\"}");
    voice.end(0, Ending.AWAITING, "Install triage?");

    assertTrue(engine.answer(run.id(), "Install", "enzo"));

    assertTrue(
        voice
            .calls
            .get(voice.calls.size() - 1)
            .utterance()
            .startsWith("Nothing was installed: this server has no Studio"));
  }

  // --- final review F6: the checked todo_write, through the runtime --------------------------

  private static final String CHECKED_SOURCE =
      """
            ---
            name: code_implementation
            description: Takes a task to reviewed code.
            model: reasoning
            max-turns: 60
            max-model-calls: 400
            tools: [file_read]
            stages:
              - {id: test_design}
              - {id: code, check: required}
            ---
            You are conducting a code implementation.
            """;

  /** A model that marks the checked stage done once, then answers. */
  private static final class MarksCodeDone implements LlmTransport {
    private final String item;
    final List<List<ChatMessage>> sent = new ArrayList<>();

    MarksCodeDone(String item) {
      this.item = item;
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      sent.add(List.copyOf(messages));
      if (sent.size() == 1) {
        return new Completion(
            "",
            "tool_calls",
            TokenUsage.UNKNOWN,
            List.of(
                new ToolCall(
                    "c1",
                    "todo_write",
                    "{\"ops\":[{\"op\":\"update\",\"id\":\""
                        + item
                        + "\",\"status\":\"done\",\"summary\":\"built it\"}]}")));
      }
      return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        java.util.function.BooleanSupplier abandoned) {
      return complete(model, messages, sampling, tools);
    }

    @Override
    public Embeddings embed(String model, List<String> input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }

  /**
   * What a checked conductor run's own {@code todo_write} answered when the model marked its
   * checked stage done, with the real runtime between the provider and the tool — so what is shown
   * is the write the runtime was offered, not one a test built by hand.
   *
   * @param files the runtime's providers, or null for a runtime that reaches no filesystem
   */
  private String checkedDoneMoveThroughTheRuntime(
      io.aeyer.plowshare.server.files.RunProviders files) {
    Orchestrations engine = engine();
    OrchestrationDefinition checked =
        OrchestrationRegistry.parsePinned(
            "code_implementation", "test", CHECKED_SOURCE, TOOLS, Tier.PROJECT);
    OrchestrationRecord run =
        engine.start(
            new Orchestrations.Start(
                checked,
                Home.of("story"),
                "build it",
                null,
                null,
                "interlocutor",
                "enzo",
                null,
                null,
                0));
    jdbc.update(
        "UPDATE todos SET status = 'in_progress', summary = 'built it' WHERE"
            + " conversation = ? AND stage_id = 'code'",
        run.conductorConversation());
    String code =
        board.list(run.conductorConversation()).stream()
            .filter(item -> "code".equals(item.stageId()))
            .findFirst()
            .orElseThrow()
            .id();
    MarksCodeDone model = new MarksCodeDone(code);
    JobRuntime runtime =
        new JobRuntime(
            new LlmDispatcher(
                List.of(
                    new LlmPool(
                        "scripted",
                        List.of("model-reasoning"),
                        Map.of("reasoning", "model-reasoning"),
                        4,
                        1,
                        java.time.Duration.ofSeconds(5),
                        model)),
                new NoOpTokenLedger()),
            List.of(),
            null,
            files);
    runtime.useTodos(board);
    runtime.useRunExtras(
        OrchestrationsConfig.runExtras(
            store, engine, board, new OrchestrationChecks(jdbc), mock(RunApprovalStore.class)));
    List<LoggedEntry> logged = new ArrayList<>();
    Transcript transcript =
        new Transcript() {
          @Override
          public List<ChatMessage> before() {
            return List.of();
          }

          @Override
          public String conversationId() {
            return run.conductorConversation();
          }

          @Override
          public void promptMeasured(int promptTokens) {}

          @Override
          public void record(LoggedEntry entry) {
            logged.add(entry);
          }
        };

    runtime.run(
        engine.conductorOf(run).orElseThrow(),
        "go on",
        Home.of("story"),
        Budget.of(20),
        () -> false,
        null,
        JobWatch.UNWATCHED,
        transcript);

    assertEquals(
        TodoStatus.IN_PROGRESS,
        board.list(run.conductorConversation()).stream()
            .filter(item -> "code".equals(item.stageId()))
            .findFirst()
            .orElseThrow()
            .status(),
        "the checked stage is not done");
    return logged.stream()
        .filter(entry -> entry.kind() == EntryKind.TOOL_RESULT)
        .map(LoggedEntry::content)
        .findFirst()
        .orElseThrow();
  }

  /** F6: the runtime offers a checked run the checked todo_write, which runs StageChecks. */
  @Test
  void a_checked_conductor_run_is_offered_the_checked_todo_write_through_the_runtime() {
    FileProvider machine = mock(FileProvider.class);
    when(machine.roots()).thenReturn(List.of(java.nio.file.Path.of("/repo")));

    String shown =
        checkedDoneMoveThroughTheRuntime((home, grants, session, owner) -> List.of(machine));

    assertTrue(shown.contains("stage 'code' is checked, and this run has no check yet"), shown);
  }

  /**
   * F6: a checked run with no port to run its check — a runtime that reaches no filesystem — is
   * still offered a checked todo_write, and it refuses the done move rather than letting a plain
   * write mark the stage done unchecked.
   */
  @Test
  void a_checked_run_that_cannot_run_its_check_is_refused_its_done_move_not_let_through() {
    String shown = checkedDoneMoveThroughTheRuntime(null);

    assertTrue(
        shown.contains(
            "the check cannot run on this server, so a checked stage cannot"
                + " be marked done here"),
        shown);
  }

  /**
   * The constraint arrives on the turns that follow a prose ending and on no others.
   *
   * <p>Measured 2026-09-25: two of the three runs in the first live orchestration tree ended turn
   * after turn in prose and were failed {@code stuck} with their work complete; the third called
   * {@code orchestration_finish} properly once nudged. A run that gets it right first time sends
   * the request it would have sent before any of this existed, which is why the first turn is left
   * alone.
   */
  @Test
  void only_a_run_that_has_already_ended_a_turn_in_prose_must_call_a_tool() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    RunExtras provider = OrchestrationsConfig.runExtras(store, engine);

    assertFalse(
        provider.forRun(context(run.conductorConversation())).requiresAToolCall(),
        "an ordinary first turn is unchanged");

    // What nudgeOrRestart counts, and the only thing that increments this column.
    store.nudged(run.id());

    assertTrue(provider.forRun(context(run.conductorConversation())).requiresAToolCall());
    // Restarts are a separate column: a server restart is not a conductor misbehaving.
    assertTrue(OrchestrationsConfig.hasEndedATurnInProse(row(run.id())));
  }

  /**
   * Progress forgives the {@code stuck} count (spec 2026-09-28) but not the prose ending: a
   * conductor that has narrated once is still made to call a tool after it moves a stage, a
   * delegation returns or the person says go on. {@code nudges} used to carry both meanings, and
   * resetting it would have lifted the forced call.
   */
  @Test
  void progress_resets_the_count_but_a_conductor_that_ended_in_prose_must_still_call_a_tool() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    RunExtras provider = OrchestrationsConfig.runExtras(store, engine);
    store.nudged(run.id());

    store.progressedIn(run.conductorConversation());

    assertEquals(0, row(run.id()).nudges(), "the stuck count starts again");
    assertTrue(row(run.id()).endedInProse());
    assertTrue(
        provider.forRun(context(run.conductorConversation())).requiresAToolCall(),
        "the next request is still tool_choice: required");
  }

  @Test
  void a_run_restarted_under_a_new_server_is_not_made_to_call_a_tool() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    RunExtras provider = OrchestrationsConfig.runExtras(store, engine);

    store.restarted(run.id());

    assertFalse(
        provider.forRun(context(run.conductorConversation())).requiresAToolCall(),
        "a restart says nothing about how the conductor was ending its turns");
  }

  /** A caller side offering only a status tool that says what it was asked about. */
  private static RunExtras statusOnly() {
    AgentTool status =
        new AgentTool() {
          @Override
          public ToolSchema schema() {
            return new ToolSchema(CallerOrchestrationTools.STATUS_NAME, "status", Map.of());
          }

          @Override
          public String run(String argumentsJson, Home home) {
            return "status of " + argumentsJson;
          }
        };
    return context -> new RunExtras.Extras(List.of(status), null, false);
  }

  /**
   * 65f2b26c's constraint was built on the conductor's side and lost here, by a three-argument
   * constructor, for every conductor that also holds an orchestrations grant.
   */
  @Test
  void combining_keeps_a_conductor_s_duty_to_call_a_tool() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    store.nudged(run.id());

    RunExtras.Extras extras =
        OrchestrationsConfig.combinedRunExtras(
                OrchestrationsConfig.runExtras(store, engine), statusOnly(), store)
            .forRun(context(run.conductorConversation()));

    assertTrue(extras.requiresAToolCall());
  }

  // --- rule 4: the conductor's fence -------------------------------------------------------

  private static final String PRODUCT_EDIT =
      "{\"path\":\"/Users/e/rpg/rpg/main.py\",\"content\":\"print()\"}";

  /**
   * {@link #SOURCE} under {@code name}, with a directory to be fenced to, as the shipped
   * definitions have.
   */
  private void conducting(String name) {
    definition =
        OrchestrationRegistry.parsePinned(
            name,
            "test",
            SOURCE
                .replace("code_implementation", name)
                .replace(
                    "tools: [file_read]\n",
                    "tools: [file_read]\nartifacts: docs/orchestrations/{date}-{name}-{id}/\n"),
            TOOLS,
            Tier.PROJECT);
  }

  /**
   * Rule 4 (spec 2026-09-29 §3): the fence rides in the conductor's extras, built from the
   * directory V60 stored at start, and its refusal names code_implementation's coder.
   */
  @Test
  void a_code_implementation_conductor_s_extras_fence_its_edits_to_its_own_directory() {
    conducting("code_implementation");
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    String own = store.artifactsDir(run.id()).orElseThrow();

    RunExtras.Fence fence =
        OrchestrationsConfig.runExtras(store, engine, board, null, null)
            .forRun(
                new RunExtras.Context(
                    null,
                    run.conductorConversation(),
                    null,
                    io.aeyer.plowshare.server.agents.Transcript.NONE))
            .fence();

    assertTrue(fence.refusal("file_edit", PRODUCT_EDIT).endsWith(ArtifactsFence.REFUSAL));
    assertNull(
        fence.refusal(
            "file_edit", "{\"path\":\"/Users/e/rpg/" + own + "spec.md\",\"content\":\"x\"}"));
    assertTrue(
        OrchestrationsConfig.runExtras(store, engine)
            .forRun(context(run.conductorConversation()))
            .fence()
            .refusal("file_edit", PRODUCT_EDIT)
            .endsWith(ArtifactsFence.REFUSAL),
        "the board-less overload fences too");
  }

  /** implement_specification has no coder: its conductor is sent to a phase run instead. */
  @Test
  void an_implement_specification_conductor_is_refused_with_the_phase_s_way() {
    conducting("implement_specification");
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);

    RunExtras.Fence fence =
        OrchestrationsConfig.runExtras(store, engine)
            .forRun(context(run.conductorConversation()))
            .fence();

    assertTrue(fence.refusal("file_edit", PRODUCT_EDIT).endsWith(ArtifactsFence.PHASE_REFUSAL));
  }

  /** Only a conductor is fenced; the coder it delegates to writes code. */
  @Test
  void a_run_that_conducts_nothing_is_not_fenced() {
    RunExtras provider = OrchestrationsConfig.runExtras(store, engine(), board, null, null);

    assertSame(RunExtras.Fence.NONE, provider.forRun(context("cnv_coder")).fence());
  }

  /** The merge carries the conductor's fence, as it carries its duty to call a tool. */
  @Test
  void combining_keeps_a_conductor_s_fence() {
    conducting("code_implementation");
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);

    RunExtras.Extras extras =
        OrchestrationsConfig.combinedRunExtras(
                OrchestrationsConfig.runExtras(store, engine), statusOnly(), store)
            .forRun(context(run.conductorConversation()));

    assertTrue(extras.fence().refusal("file_edit", PRODUCT_EDIT).endsWith(ArtifactsFence.REFUSAL));
  }

  // --- stage moves --------------------------------------------------------------------------

  @Test
  void stage_moves_refuse_a_return_past_the_limit_with_a_todo_refusal() {
    OrchestrationRecord run = started(engine());
    String conversation = run.conductorConversation();
    List<TodoItem> stages = board.list(conversation);
    String goal = stages.get(0).id();
    String spec = stages.get(1).id();
    String review = stages.get(2).id();
    board.apply(conversation, List.of(new Update(goal, TodoStatus.IN_PROGRESS, null, null)), "s");
    board.apply(conversation, List.of(new Update(goal, TodoStatus.DONE, null, "restated")), "s");
    board.apply(conversation, List.of(new Update(spec, TodoStatus.IN_PROGRESS, null, null)), "s");
    board.apply(conversation, List.of(new Update(spec, TodoStatus.DONE, null, "written")), "s");
    board.apply(conversation, List.of(new Update(review, TodoStatus.IN_PROGRESS, null, null)), "s");
    List<TodoItem> before = board.list(conversation);

    // The rules read one return left; the only one is spent before this batch's count runs
    // (written inside its transaction, so it rolls back with the refusal), so the count is
    // what refuses it.
    beforeNextTransaction =
        () ->
            jdbc.update(
                "UPDATE orchestrations SET returns_used = max_returns WHERE id = ?", run.id());
    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () ->
                board.apply(
                    conversation,
                    List.of(new Update(spec, TodoStatus.IN_PROGRESS, null, null)),
                    "s"));

    assertEquals(
        "todo_write refused: this orchestration has used all 1 of its returns, or is"
            + " no longer running. Nothing was changed.",
        refused.getMessage());
    assertEquals(before, board.list(conversation), "nothing changed");
    assertEquals(0, row(run.id()).returnsUsed(), "the refused batch counted nothing");

    // And with no return left to read, the rules refuse it before anything is counted.
    jdbc.update("UPDATE orchestrations SET returns_used = max_returns WHERE id = ?", run.id());
    TodoRefused again =
        assertThrows(
            TodoRefused.class,
            () ->
                board.apply(
                    conversation,
                    List.of(new Update(spec, TodoStatus.IN_PROGRESS, null, null)),
                    "s"));
    assertTrue(again.getMessage().contains("has used all 1 of its returns"), again.getMessage());
    assertEquals(before, board.list(conversation));
  }

  @Test
  void stage_moves_refuse_every_locked_move_once_the_run_is_not_running() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    engine.ask(run.id(), "Which database?");
    String goal = board.list(run.conductorConversation()).get(0).id();

    TodoRefused refused =
        assertThrows(
            TodoRefused.class,
            () ->
                board.apply(
                    run.conductorConversation(),
                    List.of(new Update(goal, TodoStatus.IN_PROGRESS, null, null)),
                    "s"));

    assertTrue(refused.getMessage().contains("locked"), refused.getMessage());
  }

  @Test
  void a_context_with_no_orchestration_store_refuses_every_locked_move() {
    assertSame(
        io.aeyer.plowshare.server.todos.LockedMoves.REFUSE_ALL, TodosConfig.lockedMoves(null));
  }

  /**
   * A transcript naming {@code conversation}, so {@code TodoTools.Write} (which reads its
   * conversation off the transcript, not off the context) finds this run's own list.
   */
  private static Transcript transcriptFor(String conversation) {
    return new Transcript() {
      @Override
      public List<ChatMessage> before() {
        return List.of();
      }

      @Override
      public String conversationId() {
        return conversation;
      }

      @Override
      public void promptMeasured(int promptTokens) {}
    };
  }

  private static RunExtras.Context contextWithTranscript(String conversation) {
    return new RunExtras.Context(null, conversation, null, transcriptFor(conversation));
  }

  private static AgentTool todoWrite(RunExtras.Extras extras) {
    return extras.tools().stream()
        .filter(tool -> "todo_write".equals(tool.schema().name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no todo_write in " + extras));
  }

  /** Rule 1's second half: a phase child is not done while the run started under it lives. */
  @Test
  void a_phase_child_cannot_be_marked_done_while_its_run_is_still_live() {
    Orchestrations engine = engine();
    OrchestrationRecord parent = started(engine);
    String spec =
        board.list(parent.conductorConversation()).stream()
            .filter(item -> "spec".equals(item.stageId()))
            .findFirst()
            .orElseThrow()
            .id();
    AgentTool write =
        todoWrite(
            OrchestrationsConfig.runExtras(
                    store,
                    engine,
                    board,
                    new OrchestrationChecks(jdbc),
                    mock(RunApprovalStore.class))
                .forRun(contextWithTranscript(parent.conductorConversation())));
    write.run(
        "{\"ops\":[{\"op\":\"add\",\"text\":\"character\",\"parent\":\"" + spec + "\"}]}",
        Home.of("story"));
    String character =
        board.list(parent.conductorConversation()).stream()
            .filter(item -> "character".equals(item.text()))
            .findFirst()
            .orElseThrow()
            .id();
    OrchestrationRecord kid =
        engine.startNested(parent.id(), definition, "phase 3", null, false).run();
    store.placed(kid.id(), "docs/x/phases/03-character/k/", character);

    String refused =
        write.run(
            "{\"ops\":[{\"op\":\"update\",\"id\":\"" + character + "\",\"status\":\"done\"}]}",
            Home.of("story"));

    assertTrue(
        refused.contains(
            "`character` cannot be done while its run "
                + kid.id()
                + " is still running: its report comes to you when it ends"),
        refused);
    store.stop(kid.id(), OrchestrationState.FAILED, "gave up");
    String done =
        write.run(
            "{\"ops\":[{\"op\":\"update\",\"id\":\"" + character + "\",\"status\":\"done\"}]}",
            Home.of("story"));
    assertTrue(done.startsWith("Done."), done);
  }

  // --- acceptance (spec 2026-09-29 §1b) -------------------------------------------------------

  private static final String ACCEPTING_SOURCE =
      """
            ---
            name: implement_specification
            description: Takes a specification to a product that runs.
            model: reasoning
            max-turns: 60
            max-model-calls: 400
            tools: [file_read]
            stages:
              - {id: spec, acceptance: written}
              - {id: phases}
              - {id: acceptance, acceptance: required, may-return-to: [phases]}
            artifacts: docs/orchestrations/{date}-{name}-{id}/
            ---
            You are conducting an implementation.
            """;

  /** A root run with an acceptance stage, its spec stage in progress with a summary. */
  private OrchestrationRecord accepting(Orchestrations engine) {
    OrchestrationDefinition accepting =
        OrchestrationRegistry.parsePinned(
            "implement_specification", "test", ACCEPTING_SOURCE, TOOLS, Tier.PROJECT);
    OrchestrationRecord run =
        engine.start(
            new Orchestrations.Start(
                accepting,
                Home.of("rpg"),
                "build the rpg",
                null,
                null,
                "interlocutor",
                "enzo",
                null,
                null,
                0));
    store.placed(run.id(), "docs/o/", null);
    jdbc.update(
        "UPDATE todos SET status = 'in_progress', summary = 'spec.md written' WHERE"
            + " conversation = ? AND stage_id = 'spec'",
        run.conductorConversation());
    return run;
  }

  private String specItem(OrchestrationRecord run) {
    return board.list(run.conductorConversation()).stream()
        .filter(item -> "spec".equals(item.stageId()))
        .findFirst()
        .orElseThrow()
        .id();
  }

  /** Fail closed, as the check does: no port, and the spec stage cannot be marked done. */
  @Test
  void a_run_with_acceptance_that_cannot_run_it_is_refused_its_spec_done_move() {
    Orchestrations engine = engine();
    OrchestrationRecord run = accepting(engine);
    AgentTool write =
        todoWrite(
            OrchestrationsConfig.runExtras(
                    store,
                    engine,
                    board,
                    new OrchestrationChecks(jdbc),
                    mock(RunApprovalStore.class))
                .forRun(contextWithTranscript(run.conductorConversation())));

    String refused =
        write.run(
            "{\"ops\":[{\"op\":\"update\",\"id\":\"" + specItem(run) + "\",\"status\":\"done\"}]}",
            Home.of("rpg"));

    assertTrue(refused.contains("acceptance cannot run on this server"), refused);
  }

  /** Through the bindings: the gate is in the chain, reads spec.md and registers its set. */
  @Test
  void a_run_with_acceptance_registers_its_commands_when_spec_is_marked_done() {
    jdbc.update("DELETE FROM orchestration_acceptance");
    jdbc.update("DELETE FROM orchestration_acceptance_requirements");
    Orchestrations engine = engine();
    OrchestrationRecord run = accepting(engine);
    OrchestrationAcceptance acceptance = new OrchestrationAcceptance(jdbc, work);
    io.aeyer.plowshare.server.agents.Commands.Port port =
        new io.aeyer.plowshare.server.agents.Commands.Port() {
          @Override
          public io.aeyer.plowshare.server.agents.Commands.Placed place(
              Home home, java.nio.file.Path cwd, List<String> argv) {
            io.aeyer.plowshare.protocol.EnvironmentFile.Side d =
                io.aeyer.plowshare.protocol.EnvironmentFile.Side.DEFAULT;
            return new io.aeyer.plowshare.server.agents.Commands.Placed(
                argv,
                java.nio.file.Path.of("/repo"),
                null,
                "local",
                new io.aeyer.plowshare.protocol.EnvironmentFile.Side(
                    "open",
                    false,
                    d.inherit(),
                    d.env(),
                    d.timeout(),
                    d.outputBytes(),
                    d.isolation()),
                null);
          }

          @Override
          public io.aeyer.plowshare.server.agents.Commands.Verdict judge(
              io.aeyer.plowshare.server.agents.Commands.Placed placed) {
            return io.aeyer.plowshare.server.agents.Commands.Verdict.ALLOWED;
          }

          @Override
          public io.aeyer.plowshare.protocol.CommandRunner.Outcome run(
              io.aeyer.plowshare.server.agents.Commands.Placed placed) {
            throw new AssertionError("nothing runs at the spec stage");
          }

          @Override
          public String read(Home home, String relative) {
            assertEquals("docs/o/spec.md", relative);
            return AcceptanceTest.SPEC;
          }
        };
    AgentTool write =
        todoWrite(
            OrchestrationsConfig.runExtras(
                    store,
                    engine,
                    board,
                    new OrchestrationChecks(jdbc),
                    mock(RunApprovalStore.class),
                    OrchestrationRecorder.NONE,
                    acceptance,
                    (r, argv, side, cwd) -> Optional.empty(),
                    null,
                    AcceptanceGate.Person.NONE)
                .forRun(
                    new RunExtras.Context(
                        null,
                        run.conductorConversation(),
                        null,
                        transcriptFor(run.conductorConversation()),
                        Home.of("rpg"),
                        "enzo",
                        port)));

    String done =
        write.run(
            "{\"ops\":[{\"op\":\"update\",\"id\":\"" + specItem(run) + "\",\"status\":\"done\"}]}",
            Home.of("rpg"));

    assertTrue(done.startsWith("Done."), done);
    assertEquals(3, acceptance.find(run.id()).size());
    assertEquals(OrchestrationChecks.OPEN, acceptance.find(run.id()).get(0).consent());
    // The requirements the set is held to, stored beside it (final review): the section
    // taken out, the rest of spec.md kept.
    assertEquals(
        Optional.of(Acceptance.requirements(AcceptanceTest.SPEC)),
        acceptance.requirements(run.id()));
    assertTrue(acceptance.requirements(run.id()).orElseThrow().contains("Out of scope"));
    assertTrue(!acceptance.requirements(run.id()).orElseThrow().contains("rpg.main | stdin"));
    assertTrue(
        board.list(run.conductorConversation()).stream()
            .filter(item -> "spec".equals(item.stageId()))
            .findFirst()
            .orElseThrow()
            .summary()
            .startsWith("spec.md written — acceptance: 3 acceptance commands"));
  }

  // --- drains -------------------------------------------------------------------------------

  @Test
  void a_whenFree_drain_that_throws_does_not_break_the_turn_s_free() {
    List<String> reached = new ArrayList<>();
    Consumer<String> drains =
        OrchestrationsConfig.drains(
            conversation -> {
              throw new IllegalStateException("the caller's store is down");
            },
            conversation -> reached.add("answers " + conversation));

    drains.accept("cnv_1");

    assertEquals(
        List.of("answers cnv_1"),
        reached,
        "a failed delivery drain still lets a pending answer be spoken");

    Consumer<String> answersThrow =
        OrchestrationsConfig.drains(
            conversation -> reached.add("delivered " + conversation),
            conversation -> {
              throw new IllegalStateException("the conductor's store is down");
            });
    answersThrow.accept("cnv_2");
    assertEquals(List.of("answers cnv_1", "delivered cnv_2"), reached);
  }

  /** Final review F5: a free also speaks a sub-agent's result left for it, after any answer. */
  @Test
  void a_free_speaks_a_waiting_sub_agent_result_after_answers_even_when_they_throw() {
    List<String> reached = new ArrayList<>();
    Consumer<String> drains =
        OrchestrationsConfig.drains(
            conversation -> reached.add("delivered " + conversation),
            conversation -> {
              throw new IllegalStateException("the conductor's store is down");
            },
            conversation -> reached.add("result " + conversation));

    drains.accept("cnv_1");

    assertEquals(List.of("delivered cnv_1", "result cnv_1"), reached);
  }

  // --- recovery at boot ---------------------------------------------------------------------

  @Test
  void recovery_at_boot_restarts_running_runs_and_drains_undelivered_messages() {
    Orchestrations engine = engine();
    OrchestrationRecord running = started(engine);
    OrchestrationRecord asking = started(engine);
    engine.ask(asking.id(), "Which database?");
    OrchestrationRecord answered = started(engine);
    engine.ask(answered.id(), "Which cache?");
    // Answered while the server was going down: recorded, never spoken.
    assertTrue(store.answer(answered.id(), "redis", "enzo"));
    OrchestrationRecord ended = started(engine);
    assertTrue(store.stop(ended.id(), OrchestrationState.FAILED, "the pool went away"));
    voice.calls.clear();

    OrchestrationsConfig.recover(store, engine, delivery);

    List<String> spokenInto = voice.calls.stream().map(call -> call.conversation()).toList();
    assertEquals(2, voice.calls.size(), spokenInto.toString());
    OrchestrationsTest.FakeVoice.Call restart =
        voice.calls.stream()
            .filter(call -> call.conversation().equals(running.conductorConversation()))
            .findFirst()
            .orElseThrow();
    assertEquals(Utterances.restart(), restart.utterance());
    assertEquals(1, row(running.id()).restarts());

    OrchestrationsTest.FakeVoice.Call answer =
        voice.calls.stream()
            .filter(call -> call.conversation().equals(answered.conductorConversation()))
            .findFirst()
            .orElseThrow();
    assertTrue(answer.utterance().contains("redis"), answer.utterance());
    assertEquals(0, row(answered.id()).restarts(), "a pending answer is spoken, not restarted");
    assertTrue(
        store.messages(answered.id()).stream()
            .filter(m -> m.kind() == Kind.ANSWER)
            .allMatch(m -> m.deliveredAt() != null));

    OrchestrationRecord stillAsking = row(asking.id());
    assertEquals(OrchestrationState.ASKING, stillAsking.state());
    assertEquals(0, stillAsking.restarts());
    assertEquals(0, stillAsking.nudges());

    assertNotNull(row(ended.id()).resultDeliveredAt(), "the undelivered ending was delivered");
    assertTrue(store.undeliveredMessages().isEmpty(), "the asking run's question was delivered");
    assertEquals(2, notices.size(), "only the asking run's question and the ending: " + notices);
    assertTrue(notices.stream().anyMatch(n -> n.contains("Which database?")), notices.toString());
    assertTrue(
        notices.stream().noneMatch(n -> n.contains("Which cache?")),
        "the answered question is not handed back to the caller: " + notices);
  }

  @Test
  void recovery_fails_an_answered_run_whose_answer_an_idle_conductor_refuses() {
    Orchestrations engine = engine();
    OrchestrationRecord answered = started(engine);
    engine.ask(answered.id(), "Which cache?");
    assertTrue(store.answer(answered.id(), "redis", "enzo"));
    voice.calls.clear();
    voice.refusal = "the conductor could not take it";
    voice.refusals = 1;

    OrchestrationsConfig.recover(store, engine, delivery);

    // Nothing is speaking at boot, so the refusal is not "in flight": no drain would get past
    // it, and a restart would only be spoken into the same refusal.
    assertTrue(voice.calls.isEmpty(), "neither the answer nor a restart was spoken");
    OrchestrationRecord found = row(answered.id());
    assertEquals(OrchestrationState.FAILED, found.state());
    assertEquals("the conductor could not take it", found.failure());
    assertEquals(0, found.restarts());
  }

  @Test
  void recovery_is_off_in_tests() throws Exception {
    Properties test = new Properties();
    try (InputStream in = getClass().getResourceAsStream("/config/application.properties")) {
      test.load(in);
    }
    assertEquals("false", test.getProperty("plowshare.orchestrations.recover-at-boot"));
    assertTrue(new OrchestrationsProperties().isRecoverAtBoot(), "on by default everywhere else");
  }

  /**
   * {@code TickerStaysOffInTestsTest}'s own reason: a sweep started from a bean would judge runs
   * stalled, and notice accounts, against the live test database on a clock no test controls.
   */
  @Test
  void the_stall_sweep_stays_off_in_tests() throws Exception {
    Properties test = new Properties();
    try (InputStream in = getClass().getResourceAsStream("/config/application.properties")) {
      test.load(in);
    }
    assertEquals("false", test.getProperty("plowshare.orchestrations.stall-sweep-enabled"));
    assertTrue(
        new OrchestrationsProperties().isStallSweepEnabled(), "on by default everywhere else");
  }

  @Test
  void a_max_depth_of_zero_turns_nesting_off_and_a_negative_one_is_refused() {
    OrchestrationsProperties properties = new OrchestrationsProperties();
    assertEquals(2, properties.getMaxDepth(), "a root, a child and a grandchild by default");

    properties.setMaxDepth(0);
    assertEquals(0, properties.getMaxDepth(), "a root is already as deep as nesting goes");

    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxDepth(-1));
    assertEquals(
        "plowshare.orchestrations.max-depth is -1, and it is 0 (no nesting at all) or" + " more",
        refused.getMessage());
    assertEquals(0, properties.getMaxDepth(), "the refused value was not kept");
  }

  // --- nesting end to end -------------------------------------------------------------------

  /**
   * The engine and a delivery whose third route is the real parent door, wired to each other as
   * {@code OrchestrationsConfig}'s beans are: the provider is read when a report climbs, not when
   * the delivery is built.
   */
  private Orchestrations nestedEngine() {
    AtomicReference<Orchestrations> engine = new AtomicReference<>();
    @SuppressWarnings("unchecked")
    ObjectProvider<Orchestrations> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenAnswer(invocation -> engine.get());
    delivery =
        new Delivery(
            store,
            conversations,
            caller,
            (handle, kind, text) -> notices.add(handle + " " + kind + " " + text),
            conversation -> voice.isSpeaking(conversation),
            OrchestrationsConfig.parentVoice(store, voice::isSpeaking, provider));
    engine.set(engine(delivery));
    return engine.get();
  }

  private List<String> spokenInto() {
    return voice.calls.stream().map(OrchestrationsTest.FakeVoice.Call::conversation).toList();
  }

  @Test
  void at_boot_a_child_that_ended_during_the_outage_is_delivered_to_its_waiting_parent() {
    Orchestrations engine = nestedEngine();
    OrchestrationRecord parent = started(engine);
    Orchestrations.Started child =
        engine.startNested(parent.id(), definition, "build the form", null, true);
    assertNull(child.refusal(), child.refusal());
    assertTrue(child.waiting());
    // It finished as the server went down: the row is terminal and its result undelivered, and
    // the parent is left waiting on a child that will never report again by itself.
    assertTrue(store.finish(child.run().id(), "the form is built"));
    voice.calls.clear();

    OrchestrationsConfig.recover(store, engine, delivery);

    OrchestrationRecord woken = row(parent.id());
    assertEquals(OrchestrationState.RUNNING, woken.state(), "the boot drain woke it");
    assertNull(woken.waitingFor());
    assertEquals(0, woken.restarts(), "a waiting row is not restarted, it is reported to");
    assertEquals(List.of(parent.conductorConversation()), spokenInto());
    assertTrue(
        voice.calls.get(0).utterance().contains("the form is built"),
        voice.calls.get(0).utterance());
    assertNotNull(row(child.run().id()).resultDeliveredAt());
    assertTrue(notices.isEmpty(), "nothing fell through to the account's inbox: " + notices);
  }

  @Test
  void a_childs_question_climbs_to_the_person_and_the_answer_comes_back_down() {
    Orchestrations engine = nestedEngine();
    OrchestrationRecord parent = started(engine);
    Orchestrations.Started started =
        engine.startNested(parent.id(), definition, "build the form", null, true);
    assertNull(started.refusal(), started.refusal());
    String kid = started.run().id();
    // 0 is the parent's first turn, 1 the child's own.
    assertEquals(
        List.of(parent.conductorConversation(), row(kid).conductorConversation()), spokenInto());

    // The child asks, and its turn ends: the question climbs to the parent, not to the person.
    assertEquals(Optional.empty(), engine.ask(kid, "Which database?"));
    voice.end(1, Ending.AWAITING, "I asked my caller");

    assertEquals(OrchestrationState.RUNNING, row(parent.id()).state(), "woken by the question");
    assertNull(row(parent.id()).waitingFor());
    assertEquals(3, voice.calls.size(), spokenInto().toString());
    assertEquals(parent.conductorConversation(), voice.calls.get(2).conversation());
    assertTrue(
        voice.calls.get(2).utterance().contains("Which database?"), voice.calls.get(2).utterance());
    assertTrue(
        voice.calls.get(2).utterance().contains("orchestration_answer, passing the id " + kid),
        voice.calls.get(2).utterance());
    assertTrue(notices.isEmpty(), "the person was not asked over the parent's head: " + notices);

    // The parent cannot answer it, so it passes the question up with orchestration_ask. Its own
    // caller is an account and no conversation, so this one does reach the person's inbox.
    assertEquals(
        Optional.empty(), engine.ask(parent.id(), "My child asks which database to use. Which?"));
    voice.end(2, Ending.AWAITING, "I asked my own caller");

    assertEquals(OrchestrationState.ASKING, row(parent.id()).state());
    assertEquals(1, notices.size(), notices.toString());
    assertTrue(notices.get(0).startsWith("enzo orchestration "), notices.get(0));
    assertTrue(notices.get(0).contains("My child asks which database"), notices.get(0));

    // The person answers the parent, and the parent answers the child — which is what its own
    // orchestration_answer calls (CallerOrchestrationsTest covers the tool over this).
    assertTrue(engine.answer(parent.id(), "Postgres", "enzo"));
    assertEquals(OrchestrationState.RUNNING, row(parent.id()).state());
    assertEquals(4, voice.calls.size(), spokenInto().toString());
    assertTrue(voice.calls.get(3).utterance().contains("Postgres"), voice.calls.get(3).utterance());

    assertTrue(engine.answer(kid, "Postgres", "code_implementation"));
    assertEquals(OrchestrationState.RUNNING, row(kid).state());
    assertEquals(5, voice.calls.size(), spokenInto().toString());
    assertEquals(row(kid).conductorConversation(), voice.calls.get(4).conversation());
    assertTrue(voice.calls.get(4).utterance().contains("Postgres"), voice.calls.get(4).utterance());
    assertTrue(
        store.messages(kid).stream()
            .filter(m -> m.kind() == Kind.ANSWER)
            .allMatch(m -> m.deliveredAt() != null));
    assertEquals(1, notices.size(), "only the climbed question ever left the tree: " + notices);
  }

  // --- rule 1 on a running conductor (final review, 2026-09-27 in-flight work) -------------

  /**
   * A conductor whose stages are all done, speaking, with one go-on child still running: it has
   * just called {@code orchestration_status} on that child, and rule 1 has ended its turn with the
   * harness's sentence. The child then finishes before that ending is routed, so its report is held
   * while the parent speaks. Returned here with the tree in that state.
   */
  private String ruleOneRaceWithTheChildOver(Orchestrations engine, OrchestrationRecord parent) {
    jdbc.update(
        "UPDATE todos SET status = 'done' WHERE conversation = ?", parent.conductorConversation());
    Orchestrations.Started started =
        engine.startNested(parent.id(), definition, "build the form", null, false);
    assertNull(started.refusal(), started.refusal());
    String kid = started.run().id();
    voice.speaking = true;
    jdbc.update(
        "UPDATE todos SET status = 'done' WHERE conversation = ?",
        row(kid).conductorConversation());
    assertEquals(Optional.empty(), engine.finish(kid, "the form is built"));
    voice.end(1, Ending.ANSWERED, "the form is built");
    assertNull(row(kid).resultDeliveredAt(), "held while its parent's turn is going");
    voice.speaking = false;
    // The report's speak is a turn in the parent's conversation, which is speaking from then.
    voice.duringSpeak = () -> voice.speaking = true;
    return kid;
  }

  private static String ruleOneSentence(String kid) {
    return "`"
        + kid
        + "` (`code_implementation`) is working; its result will be delivered to"
        + " you when it finishes.";
  }

  private void assertTheReportWokeTheParentAndNothingFinishedIt(
      OrchestrationRecord parent, String kid) {
    OrchestrationRecord after = row(parent.id());
    assertEquals(
        OrchestrationState.RUNNING, after.state(), "never finished on the harness's own sentence");
    assertNull(after.result());
    assertEquals(0, after.nudges(), "the report is what drives it next, not a nudge");
    OrchestrationsTest.FakeVoice.Call last = voice.calls.get(voice.calls.size() - 1);
    assertEquals(parent.conductorConversation(), last.conversation());
    assertTrue(last.utterance().contains("the form is built"), last.utterance());
    assertNotNull(row(kid).resultDeliveredAt());
    assertTrue(notices.isEmpty(), "nothing fell through to the inbox: " + notices);
  }

  /**
   * Turn's own order: the conversation is freed first — its drain speaks the held report into a new
   * turn — and the ending is routed after. Before the fix the route read the rule-1 sentence as the
   * conductor's prose, found every stage done and no child live, and finished the tree with that
   * sentence as its result, delivered to the person; the real report landed on a finished run.
   */
  @Test
  void a_rule_one_ending_never_finishes_a_conductor_whose_last_child_ended_meanwhile() {
    Orchestrations engine = nestedEngine();
    OrchestrationRecord parent = started(engine);
    String kid = ruleOneRaceWithTheChildOver(engine, parent);

    delivery.drainCaller(parent.conductorConversation());
    voice.endRequested(0, Ending.ANSWERED, ruleOneSentence(kid));

    assertTheReportWokeTheParentAndNothingFinishedIt(parent, kid);
  }

  /**
   * The same race with the report still held when the ending is routed: the route itself delivers
   * it, rather than leaving a running conductor with nothing to wake it.
   */
  @Test
  void a_rule_one_ending_delivers_a_report_still_held_when_it_is_routed() {
    Orchestrations engine = nestedEngine();
    OrchestrationRecord parent = started(engine);
    String kid = ruleOneRaceWithTheChildOver(engine, parent);

    voice.endRequested(0, Ending.ANSWERED, ruleOneSentence(kid));

    assertTheReportWokeTheParentAndNothingFinishedIt(parent, kid);
  }

  /** A child still running when the ending is routed: nothing happens until it reports. */
  @Test
  void a_rule_one_ending_with_its_child_still_running_leaves_the_conductor_alone() {
    Orchestrations engine = nestedEngine();
    OrchestrationRecord parent = started(engine);
    jdbc.update(
        "UPDATE todos SET status = 'done' WHERE conversation = ?", parent.conductorConversation());
    String kid =
        engine.startNested(parent.id(), definition, "build the form", null, false).run().id();

    voice.endRequested(0, Ending.ANSWERED, ruleOneSentence(kid));

    assertEquals(OrchestrationState.RUNNING, row(parent.id()).state());
    assertEquals(0, row(parent.id()).nudges());
    assertEquals(2, voice.calls.size(), "nothing spoken: the child's report drives it next");
  }

  // --- pushChanges ----------------------------------------------------------------------------
  // // --- pushChanges ----------------------------------------------------------------------------
  //    // --- pushChanges
  // ----------------------------------------------------------------------------

  @Test
  void a_change_is_pushed_to_the_runs_account() {
    AccountPushes accountPushes = mock(AccountPushes.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<AccountPushes> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(accountPushes);
    OrchestrationRecord run = changeRecord("orc_1", "enzo", OrchestrationState.ASKING);

    OrchestrationsConfig.pushChanges(provider).accept(run);

    verify(accountPushes)
        .push(
            "enzo",
            Map.of(
                "kind", OrchestrationsConfig.CHANGED, "orchestration", "orc_1", "state", "asking"));
  }

  @Test
  void a_run_with_no_account_pushes_nothing() {
    AccountPushes accountPushes = mock(AccountPushes.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<AccountPushes> provider = mock(ObjectProvider.class);
    OrchestrationRecord run = changeRecord("orc_1", null, OrchestrationState.ASKING);

    OrchestrationsConfig.pushChanges(provider).accept(run);

    verifyNoInteractions(accountPushes, provider);
  }

  private static OrchestrationRecord changeRecord(
      String id, String callerHandle, OrchestrationState state) {
    return new OrchestrationRecord(
        id,
        "code_implementation",
        Tier.PROJECT,
        "sha256:x",
        "src",
        "test",
        List.of(),
        3,
        0,
        "story",
        "conv_c",
        "conv_caller",
        "interlocutor",
        callerHandle,
        null,
        null,
        0,
        null,
        state,
        null,
        null,
        null,
        0,
        0,
        false,
        null,
        Instant.EPOCH,
        null);
  }

  // --- the smaller bindings -----------------------------------------------------------------

  @Test
  void the_conductor_checks_return_the_checked_definition_or_refuse_with_the_reason() {
    AgentDefinition conductor = definition.conductor();
    AgentDefinition checkedOne = mock(AgentDefinition.class);
    DefinitionChecks serves =
        (loaded, source) -> {
          assertEquals("a pinned orchestration", source);
          return new AgentRegistry.Loaded(Map.of(conductor.name(), checkedOne), Map.of(), Map.of());
        };
    assertSame(checkedOne, OrchestrationsConfig.conductorChecks(serves).apply(conductor));

    DefinitionChecks refuses =
        (loaded, source) ->
            new AgentRegistry.Loaded(
                Map.of(),
                Map.of(conductor.name(), "the model 'reasoning' is served by no pool"),
                Map.of());
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> OrchestrationsConfig.conductorChecks(refuses).apply(conductor));
    assertEquals("the model 'reasoning' is served by no pool", refused.getMessage());
  }

  @Test
  void
      the_caller_voice_speaks_as_the_caller_s_agent_and_refuses_an_agent_that_no_longer_resolves() {
    Turn turn = mock(Turn.class);
    Callers callers = mock(Callers.class);
    DefinitionResolver.Caller resolved = new DefinitionResolver.Caller(null, null);
    AgentDefinition interlocutor = mock(AgentDefinition.class);
    when(callers.callerForConversation("cnv_1", null)).thenReturn(resolved);
    when(callers.requireAgent("interlocutor", resolved)).thenReturn(interlocutor);
    when(turn.isSpeaking("cnv_1")).thenReturn(true);
    CallerVoice voice = OrchestrationsConfig.callerVoice(turn, callers);

    assertTrue(voice.isSpeaking("cnv_1"));
    voice.speak("cnv_1", "interlocutor", "the result", Speaker.orchestration("orc_1"));
    // deliver(), not speak(): the harness relaying a conductor's own result into a caller's
    // conversation is never a person's own words arriving fresh -- spec §6.
    verify(turn)
        .deliver(
            eq("cnv_1"),
            eq(interlocutor),
            eq("the result"),
            eq(Speaker.orchestration("orc_1")),
            any());

    when(callers.requireAgent("gone", resolved))
        .thenThrow(new IllegalArgumentException("no agent is named 'gone'"));
    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> voice.speak("cnv_1", "gone", "the result", Speaker.orchestration("orc_1")));
    assertTrue(refused.getMessage().contains("no agent is named 'gone'"), refused.getMessage());
  }

  @Test
  void a_parent_voice_wakes_a_waiting_parent_before_it_speaks() {
    Orchestrations engine = engine();
    OrchestrationRecord parent = started(engine);
    Orchestrations.Started child =
        engine.startNested(parent.id(), definition, "build the form", null, true);
    assertNull(child.refusal(), child.refusal());
    assertTrue(child.waiting());
    assertEquals(OrchestrationState.WAITING, row(parent.id()).state());
    @SuppressWarnings("unchecked")
    ObjectProvider<Orchestrations> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(engine);
    ParentVoice parents = OrchestrationsConfig.parentVoice(store, voice::isSpeaking, provider);
    List<OrchestrationState> whenSpokenTo = new ArrayList<>();
    voice.duringSpeak = () -> whenSpokenTo.add(row(parent.id()).state());
    voice.calls.clear();

    assertTrue(parents.isLiveParent(parent.id()));
    assertFalse(parents.isSpeaking(parent.id()));
    parents.speak(parent.id(), "orchestration " + child.run().id() + " finished");

    assertEquals(
        List.of(OrchestrationState.RUNNING),
        whenSpokenTo,
        "the row was already running by the time the turn started");
    assertEquals(
        List.of(parent.conductorConversation()),
        voice.calls.stream().map(OrchestrationsTest.FakeVoice.Call::conversation).toList());
    assertEquals("orchestration " + child.run().id() + " finished", voice.calls.get(0).utterance());
    assertNull(row(parent.id()).waitingFor());
  }

  @Test
  void the_parent_door_reads_the_parents_own_conductor_conversation_and_its_row() {
    Orchestrations engine = engine();
    OrchestrationRecord parent = started(engine);
    @SuppressWarnings("unchecked")
    ObjectProvider<Orchestrations> provider = mock(ObjectProvider.class);
    // Only the parent's own conductor conversation is speaking, so nothing but a door that
    // reads THAT conversation — not the run id, not the conversation that started it — answers
    // true.
    ParentVoice parents =
        OrchestrationsConfig.parentVoice(store, parent.conductorConversation()::equals, provider);

    assertTrue(parents.isSpeaking(parent.id()));
    assertFalse(parents.isSpeaking("orc_gone"), "a row that is gone is not speaking");
    assertTrue(parents.isLiveParent(parent.id()));
    assertFalse(parents.isLiveParent("orc_gone"), "a row that is gone is no parent at all");
    assertTrue(store.stop(parent.id(), OrchestrationState.CANCELLED, "enough"));
    assertFalse(parents.isLiveParent(parent.id()), "a run that has ended is no live parent");
    verifyNoInteractions(provider);
  }

  @Test
  void cancelling_a_run_cancels_its_conductor_s_running_job_and_nothing_else() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    OrchestrationRecord other = started(engine);
    JobStore jobs = mock(JobStore.class);
    Job conductorJob = job("job_1", run.conductorConversation(), Job.State.RUNNING);
    Job finishedJob = job("job_2", run.conductorConversation(), Job.State.DONE);
    Job otherJob = job("job_3", other.conductorConversation(), Job.State.RUNNING);
    when(jobs.jobs()).thenReturn(List.of(conductorJob, finishedJob, otherJob));
    OrchestrationCancel cancel = new OrchestrationCancel(engine, store, jobs, conversations);

    assertTrue(cancel.cancel(run.id(), "enzo"));

    assertEquals(OrchestrationState.CANCELLED, row(run.id()).state());
    verify(jobs).cancel("job_1");
    verify(jobs, never()).cancel("job_2");
    verify(jobs, never()).cancel("job_3");

    JobStore untouched = mock(JobStore.class);
    assertEquals(
        false,
        new OrchestrationCancel(engine, store, untouched, conversations).cancel(run.id(), "enzo"),
        "a second cancel is not the one that cancelled it");
    verify(untouched, never()).cancel(anyString());
  }

  /**
   * A sub-agent the engine resumed after an approval runs as a job in its own delegation
   * conversation, not the conductor's, on the conductor's budget — so cancelling the run has to
   * reach it too, or it goes on spending for a run nobody is waiting on. A delegation some other
   * conductor made is not this run's.
   */
  @Test
  void cancelling_a_run_cancels_its_conductor_s_resumed_delegate_s_job_too() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    OrchestrationRecord other = started(engine);
    ConversationRecord coder =
        conversations.log(
            Origin.DELEGATION, Home.of("story"), "coder", run.conductorConversation(), null);
    ConversationRecord othersCoder =
        conversations.log(
            Origin.DELEGATION, Home.of("story"), "coder", other.conductorConversation(), null);
    JobStore jobs = mock(JobStore.class);
    Job resumed = job("job_1", coder.id(), Job.State.RUNNING);
    when(resumed.conversationOrigin()).thenReturn(Origin.DELEGATION);
    Job othersResumed = job("job_2", othersCoder.id(), Job.State.RUNNING);
    when(othersResumed.conversationOrigin()).thenReturn(Origin.DELEGATION);
    when(jobs.jobs()).thenReturn(List.of(resumed, othersResumed));

    assertTrue(
        new OrchestrationCancel(engine, store, jobs, conversations).cancel(run.id(), "enzo"));

    verify(jobs).cancel("job_1");
    verify(jobs, never()).cancel("job_2");
  }

  private static Job job(String id, String conversation, Job.State state) {
    Job job = mock(Job.class);
    when(job.id()).thenReturn(id);
    when(job.conversation()).thenReturn(conversation);
    when(job.state()).thenReturn(state);
    return job;
  }

  /**
   * A cap applied now reaches the turn running on the conductor's conversation, and no other: a
   * finished job's limits are nobody's any more — spec 2026-09-29 §2.
   */
  @Test
  void a_conversation_s_live_limits_are_its_running_job_s() {
    RunLimits live = new RunLimits(Budget.of(400), TurnCap.of(60));
    Job running = job("job_1", "cnv_a", Job.State.RUNNING);
    when(running.limits()).thenReturn(Optional.of(live));
    Job done = job("job_2", "cnv_b", Job.State.DONE);
    when(done.limits()).thenReturn(Optional.of(new RunLimits(Budget.of(1), TurnCap.of(1))));
    JobStore jobs = mock(JobStore.class);
    when(jobs.jobs()).thenReturn(List.of(done, running));

    assertSame(live, OrchestrationsConfig.liveLimits(jobs).apply("cnv_a").orElseThrow());
    assertTrue(OrchestrationsConfig.liveLimits(jobs).apply("cnv_b").isEmpty());
    assertTrue(OrchestrationsConfig.liveLimits(jobs).apply("cnv_c").isEmpty());
  }

  // --- the beans ----------------------------------------------------------------------------

  @Test
  void the_beans_hand_the_runtime_its_extras_and_the_turn_its_drain_and_recover_only_when_asked() {
    JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
    JobRuntime runtime = mock(JobRuntime.class);
    when(runtime.knownTools()).thenReturn(TOOLS);
    Turn turn = mock(Turn.class);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(OrchestrationsConfig.class, CallerOrchestrationsConfig.class)
        .withBean(CallerAccess.class, () -> mock(CallerAccess.class))
        .withBean(JdbcTemplate.class, () -> mockJdbc)
        .withBean(UnitOfWork.class, () -> work)
        .withBean(ConversationStore.class, () -> conversations)
        .withBean(TodoBoard.class, () -> board)
        .withBean(Turn.class, () -> turn)
        .withBean(Callers.class, () -> mock(Callers.class))
        .withBean(OrchestrationResolver.class, () -> mock(OrchestrationResolver.class))
        .withBean(Inbox.class, () -> mock(Inbox.class))
        .withBean(JobRuntime.class, () -> runtime)
        .withBean(JobStore.class, () -> mock(JobStore.class))
        .withBean(DefinitionChecks.class, () -> DefinitionChecks.NONE)
        .withBean(SessionRegistry.class, () -> mock(SessionRegistry.class))
        .withBean(RunApprovalStore.class, () -> mock(RunApprovalStore.class))
        .withBean(Environments.class, () -> Environments.NONE)
        .withBean(PresenceRegistry.class, PresenceRegistry::new)
        .withBean(AgentRegistry.class, () -> new AgentRegistry(Map.of()))
        .withPropertyValues("plowshare.orchestrations.recover-at-boot=false")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertNotNull(context.getBean(Orchestrations.class));
              assertNotNull(context.getBean(OrchestrationCancel.class));
              assertNotNull(context.getBean(CallerOrchestrations.class));
              assertNotNull(context.getBean(Delivery.class));
              verify(runtime).useRunExtras(any());
              verify(runtime).useTriggers(any());
              verify(turn).whenFree(any());
              @SuppressWarnings("unchecked")
              ApplicationListener<ApplicationReadyEvent> atBoot =
                  context.getBean("orchestrationsAtBoot", ApplicationListener.class);
              clearInvocations(mockJdbc);
              atBoot.onApplicationEvent(mock(ApplicationReadyEvent.class));
              verifyNoInteractions(mockJdbc);
              // A returned agent_run resets its conductor's nudges (spec 2026-09-28).
              @SuppressWarnings("unchecked")
              ArgumentCaptor<Consumer<String>> returned = ArgumentCaptor.forClass(Consumer.class);
              verify(runtime).useDelegationReturned(returned.capture());
              returned.getValue().accept("cnv_conductor");
              verify(mockJdbc)
                  .update(
                      org.mockito.ArgumentMatchers.contains(
                          "SET nudges = 0 WHERE conductor_conversation = ?"),
                      eq("cnv_conductor"));
            });
  }

  @Test
  void the_trigger_noticing_answers_empty_when_no_caller_orchestrations_bean_exists() {
    JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
    JobRuntime runtime = mock(JobRuntime.class);
    when(runtime.knownTools()).thenReturn(TOOLS);
    Turn turn = mock(Turn.class);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(OrchestrationsConfig.class)
        .withBean(CallerAccess.class, () -> mock(CallerAccess.class))
        .withBean(JdbcTemplate.class, () -> mockJdbc)
        .withBean(UnitOfWork.class, () -> work)
        .withBean(ConversationStore.class, () -> conversations)
        .withBean(TodoBoard.class, () -> board)
        .withBean(Turn.class, () -> turn)
        .withBean(Callers.class, () -> mock(Callers.class))
        // This narrow engine context omits CallerOrchestrationsConfig, so the provider
        // the trigger noticing reads finds no caller-facing services.
        .withBean(Inbox.class, () -> mock(Inbox.class))
        .withBean(JobRuntime.class, () -> runtime)
        .withBean(JobStore.class, () -> mock(JobStore.class))
        .withBean(DefinitionChecks.class, () -> DefinitionChecks.NONE)
        .withBean(SessionRegistry.class, () -> mock(SessionRegistry.class))
        .withBean(RunApprovalStore.class, () -> mock(RunApprovalStore.class))
        .withBean(Environments.class, () -> Environments.NONE)
        .withBean(PresenceRegistry.class, PresenceRegistry::new)
        .withBean(AgentRegistry.class, () -> new AgentRegistry(Map.of()))
        .withPropertyValues("plowshare.orchestrations.recover-at-boot=false")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(0, context.getBeanNamesForType(CallerOrchestrations.class).length);
              ArgumentCaptor<TriggerNoticing> trap = ArgumentCaptor.forClass(TriggerNoticing.class);
              verify(runtime).useTriggers(trap.capture());
              assertEquals(
                  Optional.empty(),
                  trap.getValue()
                      .noticeFor(
                          mock(AgentDefinition.class),
                          "implement it",
                          Home.of("story"),
                          "ses_1",
                          "cnv_1"));
            });
  }

  // --- fakes --------------------------------------------------------------------------------

  static final class FakeCaller implements CallerVoice {

    final List<String> spoken = new ArrayList<>();

    @Override
    public boolean isSpeaking(String conversation) {
      return false;
    }

    @Override
    public void speak(String conversation, String callerAgent, String utterance, Speaker speaker) {
      spoken.add(conversation);
    }
  }

  @Test
  void the_recorder_is_installed_at_every_source_outside_the_engine() {
    OrchestrationRecorder recorder = new OrchestrationRecorder() {};
    TodoBoard board = mock(TodoBoard.class);
    JobRuntime runtime = mock(JobRuntime.class);
    RunApprovalStore approvals = mock(RunApprovalStore.class);

    OrchestrationsConfig.install(recorder, board, runtime, approvals);

    verify(board).whenMoved(recorder);
    verify(runtime).useActivity(recorder);
    verify(approvals).useEvents(recorder);
  }

  /**
   * V68: delivery's inbox binds every door — {@code inbox::notify} alone would bind the
   * three-argument notice, and a question's notice would never name what it asks, nor leave.
   */
  @Test
  void delivery_s_inbox_writes_what_a_question_asks_and_settles_it() {
    Inbox inbox = mock(Inbox.class);
    InboxPort port = OrchestrationsConfig.inboxPort(inbox);

    port.notify("enzo", "orchestration", "it finished");
    port.notify("enzo", "orchestration", "Which database?", "question:msg_1");
    port.settle("question:msg_1");

    verify(inbox).notify("enzo", "orchestration", "it finished");
    verify(inbox).notify("enzo", "orchestration", "Which database?", "question:msg_1");
    verify(inbox).settle("question:msg_1");
  }

  // --- stage.pre / stage.post (spec 2026-09-28-hooks-reach-the-log, slice 2) ----------------

  /** A run's in-turn gates: refuses one stage's start when told to, notes every done move. */
  private static final class Gatekeeper implements RunHooks {
    final List<String> asked = new ArrayList<>();
    final List<HookRecord> parked = new ArrayList<>();
    String refuseStartOf;

    @Override
    public Gate stagePre(HookContext.Orchestration orchestration, StageStart start) {
      asked.add("pre " + orchestration.id() + " " + start.stage().id());
      return start.stage().id().equals(refuseStartOf)
          ? new Gate(
              "'freeze': not today",
              List.of(),
              List.of(
                  new HookRecord(
                      "freeze",
                      "f.js",
                      io.aeyer.plowshare.server.hooks.Tier.PROJECT,
                      io.aeyer.plowshare.server.hooks.Stage.STAGE_PRE,
                      null,
                      HookRecord.DENY,
                      "not today",
                      null,
                      null,
                      0)))
          : Gate.NOTHING;
    }

    @Override
    public Gate stagePost(HookContext.Orchestration orchestration, StageDone done) {
      asked.add("post " + done.stage().id() + " " + done.summary());
      return new Gate(null, List.of("reviewed " + done.stage().id()), List.of());
    }

    @Override
    public void record(List<HookRecord> records) {
      parked.addAll(records);
    }
  }

  /** The conductor's own todo_write, as the provider hands it to a run with these gates. */
  private static AgentTool todoWrite(RunExtras provider, String conversation, RunHooks hooks) {
    Transcript in =
        new Transcript() {
          @Override
          public List<ChatMessage> before() {
            return List.of();
          }

          @Override
          public String conversationId() {
            return conversation;
          }

          @Override
          public void promptMeasured(int promptTokens) {}
        };
    return provider
        .forRun(
            new RunExtras.Context(
                null, conversation, null, in, Home.of("story"), null, null, null, hooks))
        .tools()
        .stream()
        .filter(tool -> TodoTools.WRITE_NAME.equals(tool.schema().name()))
        .findFirst()
        .orElseThrow();
  }

  private static String update(String id, String status, String summary) {
    return "{\"ops\":[{\"op\":\"update\",\"id\":\""
        + id
        + "\",\"status\":\""
        + status
        + "\""
        + (summary == null ? "" : ",\"summary\":\"" + summary + "\"")
        + "}]}";
  }

  @Test
  void a_project_s_stage_hooks_are_asked_about_each_move_and_their_note_reaches_the_conductor() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    String conversation = run.conductorConversation();
    String goal = board.list(conversation).get(0).id();
    Gatekeeper hooks = new Gatekeeper();
    AgentTool write =
        todoWrite(
            OrchestrationsConfig.runExtras(store, engine, board, null, null), conversation, hooks);

    String started = write.run(update(goal, "in_progress", null), Home.of("story"));
    String done = write.run(update(goal, "done", "restated"), Home.of("story"));

    assertTrue(started.startsWith("Done. The list is now:"), started);
    assertTrue(done.endsWith("\n\nreviewed goal"), done);
    assertEquals(List.of("pre " + run.id() + " goal", "post goal restated"), hooks.asked);
    assertEquals(TodoStatus.DONE, board.list(conversation).get(0).status());
  }

  @Test
  void a_stage_hook_s_denial_refuses_the_write_and_nothing_moves() {
    Orchestrations engine = engine();
    OrchestrationRecord run = started(engine);
    String conversation = run.conductorConversation();
    String goal = board.list(conversation).get(0).id();
    Gatekeeper hooks = new Gatekeeper();
    hooks.refuseStartOf = "goal";

    String refused =
        todoWrite(
                OrchestrationsConfig.runExtras(store, engine, board, null, null),
                conversation,
                hooks)
            .run(update(goal, "in_progress", null), Home.of("story"));

    assertEquals(
        "todo_write refused operation 1: stage 'goal' was refused by a hook on"
            + " stage.pre: 'freeze': not today. Nothing was changed.",
        refused);
    assertEquals(TodoStatus.PENDING, board.list(conversation).get(0).status());
    assertEquals(1, hooks.parked.size(), "the denial is recorded");
  }

  /** Spec §7: a system StageChecks failure stops the move before any user hook runs. */
  @Test
  void a_system_gate_s_refusal_stops_the_move_before_any_user_hook_is_asked() {
    Orchestrations engine = engine();
    OrchestrationDefinition checked =
        OrchestrationRegistry.parsePinned(
            "code_implementation", "test", CHECKED_SOURCE, TOOLS, Tier.PROJECT);
    OrchestrationRecord run =
        engine.start(
            new Orchestrations.Start(
                checked,
                Home.of("story"),
                "build it",
                null,
                null,
                "interlocutor",
                "enzo",
                null,
                null,
                0));
    jdbc.update(
        "UPDATE todos SET status = 'done', summary = 'designed' WHERE"
            + " conversation = ? AND stage_id = 'test_design'",
        run.conductorConversation());
    jdbc.update(
        "UPDATE todos SET status = 'in_progress', summary = 'built it' WHERE"
            + " conversation = ? AND stage_id = 'code'",
        run.conductorConversation());
    String code =
        board.list(run.conductorConversation()).stream()
            .filter(item -> "code".equals(item.stageId()))
            .findFirst()
            .orElseThrow()
            .id();
    Gatekeeper hooks = new Gatekeeper();

    String refused =
        todoWrite(
                OrchestrationsConfig.runExtras(store, engine, board, null, null),
                run.conductorConversation(),
                hooks)
            .run(update(code, "done", null), Home.of("story"));

    assertTrue(refused.contains("the check cannot run on this server"), refused);
    assertEquals(List.of(), hooks.asked);
  }
}
