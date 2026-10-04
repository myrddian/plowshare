package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real run/delegation, immutable ownership, durable capture and PostgreSQL, with a fixture model.
 */
@Tag("full-db")
@Testcontainers
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunAttributionTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource database;
  private JdbcTemplate jdbc;
  private ConversationStore conversations;
  private UsageExecutionStore owners;
  @TempDir Path directory;
  private static final Home PAYMENTS = Home.of("payments");

  @BeforeAll
  static void migrate() {
    database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(database).load().migrate();
  }

  @BeforeEach
  void fresh() {
    jdbc = new JdbcTemplate(database);
    jdbc.execute(
        "TRUNCATE conversations, projects, admins, inference_run_ownership, inference_accounting_events, inference_attempts, inference_calls, inference_pricing_versions, inference_accounting_instances, inference_accounting_journals CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle, password_hash) VALUES ('alice', 'fixture-only'), ('bob', 'fixture-only')");
    conversations = new ConversationStore(jdbc);
    owners = source();
  }

  private UsageExecutionStore source() {
    return new UsageExecutionStore(
        jdbc, new DataSourceTransactionManager(database), AccountingFixtures.MAPPER);
  }

  @Test
  void actual_three_level_delegation_captures_every_run_under_the_original_project_and_owner()
      throws Exception {
    var registry = registry();
    var responses = List.of(asking("middle"), asking("leaf"), answer(), answer(), answer());
    var index = new AtomicInteger();
    try (var harness = harness(() -> responses.get(index.getAndIncrement()))) {
      var runtime = new JobRuntime(harness.dispatcher, List.of(), () -> registry);
      runtime.useRunUsage(owners);
      runtime.useLogOwners(conversations::ownerOf);
      var compaction = logs(harness.dispatcher, registry.get("leaf"));
      try {
        var root =
            compaction.logFor(
                Origin.SUBMISSION,
                PAYMENTS,
                registry.get("root"),
                null,
                Budget.of(20),
                Speaker.harness(),
                "alice");
        var result =
            runtime.run(
                registry.get("root"),
                "work",
                PAYMENTS,
                Budget.of(20),
                () -> false,
                null,
                JobWatch.UNWATCHED,
                root,
                TurnCap.none(),
                List.of(),
                "alice");
        assertEquals(Outcome.Ending.ANSWERED, result.ending());
        project(harness);
        assertEquals(5, count("inference_calls"));
        assertEquals(3, count("inference_run_ownership"));
        assertEquals(
            5,
            jdbc.queryForObject(
                "SELECT count(*) FROM inference_calls WHERE account_handle = 'alice' AND attribution_status = 'ATTRIBUTED' AND operation = 'AGENT_CHAT' AND turn_ordinal = 1 AND step_ordinal >= 1",
                Integer.class));
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(DISTINCT project_id) FROM inference_calls", Integer.class));
        assertEquals(
            root.conversationId(),
            jdbc.queryForObject(
                "SELECT root_conversation_id FROM inference_calls WHERE agent_name = 'leaf'",
                String.class));
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT cardinality(ancestor_run_ids) FROM inference_calls WHERE agent_name = 'leaf'",
                Integer.class));
        assertEquals(
            List.of(1L, 2L),
            jdbc.queryForList(
                "SELECT step_ordinal FROM inference_calls WHERE agent_name = 'root' ORDER BY step_ordinal",
                Long.class));
        assertEquals(
            50L,
            jdbc.queryForObject("SELECT sum(input_tokens) FROM inference_attempts", Long.class));
        var leafId =
            jdbc.queryForObject(
                "SELECT conversation_id FROM inference_calls WHERE agent_name = 'leaf'",
                String.class);
        var previous = owners.start(PAYMENTS, transcript(leafId, 2, 1), "leaf", "alice").usage();
        jdbc.execute("TRUNCATE inference_run_ownership");
        var recovered = source().start(PAYMENTS, transcript(leafId, 2, 1), "leaf", "alice").usage();
        assertEquals(previous.runs(), recovered.runs());
        assertEquals(previous.conversations(), recovered.conversations());
      } finally {
        compaction.close();
      }
    }
  }

  @Test
  void a_real_resumed_transcript_keeps_the_run_while_recording_its_new_turn() throws Exception {
    var registry = registry();
    var conversation = conversations.open(PAYMENTS, Budget.of(10), TurnCap.none(), "alice");
    try (var harness = harness(RunAttributionTest::answer)) {
      var compaction = logs(harness.dispatcher, registry.get("leaf"));
      try {
        var original =
            owners.start(
                PAYMENTS,
                compaction.transcriptFor(
                    conversation.id(), registry.get("leaf"), Speaker.harness()),
                "leaf",
                "alice");
        new TurnStore(jdbc)
            .record(
                conversation.id(),
                "old task",
                "stopped",
                Outcome.Ending.CALL_BUDGET,
                null,
                "leaf",
                null);
        var resumed = compaction.resumedTranscriptFor(conversation.id(), registry.get("leaf"), 1);
        var runtime = new JobRuntime(harness.dispatcher, List.of());
        runtime.useRunUsage(owners);
        var result =
            runtime.run(
                registry.get("leaf"),
                "continue",
                PAYMENTS,
                Budget.of(10),
                () -> false,
                null,
                JobWatch.UNWATCHED,
                resumed,
                TurnCap.none(),
                List.of(),
                "alice");
        assertEquals(Outcome.Ending.ANSWERED, result.ending());
        project(harness);
        assertEquals(
            original.usage().runs().id(),
            jdbc.queryForObject("SELECT run_id FROM inference_calls", String.class));
        assertEquals(
            2L, jdbc.queryForObject("SELECT turn_ordinal FROM inference_calls", Long.class));
      } finally {
        compaction.close();
      }
    }
  }

  @Test
  void pinned_runtime_calls_and_tools_share_one_frozen_account_snapshot() throws Exception {
    var registry = registry();
    var conversation = conversations.open(PAYMENTS, Budget.of(10), TurnCap.none(), "alice");
    var lookups = new AtomicInteger();
    try (var harness = harness(RunAttributionTest::answer)) {
      var runtime = new JobRuntime(harness.dispatcher, List.of());
      runtime.useRunUsage(owners);
      runtime.useLogOwners(
          id -> java.util.Optional.of(lookups.getAndIncrement() == 0 ? "alice" : "bob"));
      runtime.useScheduling(
          context -> {
            assertEquals("alice", context.callerHandle());
            assertEquals("alice", context.transcript().usage().accountHandle());
            return (specifier, cancelled) ->
                new Scheduling.Slot() {
                  @Override
                  public String pool() {
                    return "pool";
                  }

                  @Override
                  public void release() {}
                };
          });
      var result =
          runtime.run(
              registry.get("leaf"),
              "work",
              PAYMENTS,
              Budget.of(10),
              () -> false,
              null,
              JobWatch.UNWATCHED,
              transcript(conversation.id(), 1, null),
              TurnCap.none(),
              List.of());
      assertEquals(Outcome.Ending.ANSWERED, result.ending());
      project(harness);
      assertEquals(1, lookups.get());
      assertEquals(
          "alice",
          jdbc.queryForObject(
              "SELECT account_handle FROM inference_calls WHERE pool = 'pool'", String.class));
      assertEquals(
          conversation.id(),
          jdbc.queryForObject("SELECT conversation_id FROM inference_calls", String.class));
    }
  }

  @Test
  void nested_orchestration_membership_is_captured_and_inherited_by_delegation() {
    var root =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, "root", null, Budget.of(10), "alice");
    var nested =
        conversations.log(Origin.ORCHESTRATION, PAYMENTS, "middle", null, Budget.of(10), "alice");
    for (var pair : List.of(List.of("orch-root", root.id()), List.of("orch-nested", nested.id()))) {
      boolean child = pair.getFirst().equals("orch-nested");
      jdbc.update(
          """
                    INSERT INTO orchestrations(id, definition_name, tier, definition_hash, stages, max_returns,
                        project, conductor_conversation, caller_agent, caller_handle, state, created_at, parent, depth,
                        definition_source, definition_origin)
                    VALUES (?, 'fixture', 'PROJECT', 'fixture', '[]'::jsonb, 0, 'payments', ?, 'root', 'alice',
                        'running', now(), ?, ?, 'fixture', 'fixture')
                    """,
          pair.getFirst(),
          pair.get(1),
          child ? "orch-root" : null,
          child ? 1 : 0);
    }
    var conductor = owners.start(PAYMENTS, transcript(nested.id(), 1, null), "middle", "alice");
    assertEquals(
        new UsageLineage("orch-nested", List.of("orch-root")), conductor.usage().orchestrations());
    var leaf = conversations.log(Origin.DELEGATION, PAYMENTS, "leaf", nested.id(), null, null);
    var delegated =
        owners.start(
            PAYMENTS, parent(transcript(leaf.id(), 1, null), conductor.usage()), "leaf", "alice");
    assertEquals(conductor.usage().orchestrations(), delegated.usage().orchestrations());
  }

  @Test
  void a_parent_without_inference_is_persisted_and_retained_in_its_childs_full_ancestry()
      throws Exception {
    var root = conversations.log(Origin.SUBMISSION, PAYMENTS, "root", null, Budget.of(10), "alice");
    var middle = conversations.log(Origin.DELEGATION, PAYMENTS, "middle", root.id(), null, null);
    var leaf = conversations.log(Origin.DELEGATION, PAYMENTS, "leaf", middle.id(), null, null);
    var rootRun = owners.start(PAYMENTS, transcript(root.id(), 1, null), "root", "alice");
    var middleRun =
        owners.start(
            PAYMENTS, parent(transcript(middle.id(), 1, null), rootRun.usage()), "middle", "alice");
    var leafRun =
        owners.start(
            PAYMENTS, parent(transcript(leaf.id(), 1, null), middleRun.usage()), "leaf", "alice");
    try (var harness = harness(RunAttributionTest::answer)) {
      harness.dispatcher.complete(
          ChatRequest.of("fast", null, "private-content").withAttribution(leafRun.usage()));
      project(harness);
      assertEquals(1, count("inference_calls"));
      assertEquals(3, count("inference_run_ownership"));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT cardinality(ancestor_run_ids) FROM inference_calls", Integer.class));
      assertEquals(
          List.of(middleRun.usage().runs().id(), rootRun.usage().runs().id()),
          leafRun.usage().runs().ancestors());
      assertFalse(
          jdbc.queryForObject("SELECT attribution::text FROM inference_calls", String.class)
              .contains("private-content"));
    }
  }

  @Test
  void restart_and_repeated_resume_keep_the_execution_and_original_parent_ids() {
    var root = conversations.log(Origin.SUBMISSION, PAYMENTS, "root", null, Budget.of(10), "alice");
    var child = conversations.log(Origin.DELEGATION, PAYMENTS, "leaf", root.id(), null, null);
    var first = owners.start(PAYMENTS, transcript(root.id(), 1, null), "root", "alice");
    var leaf =
        owners.start(
            PAYMENTS, parent(transcript(child.id(), 1, null), first.usage()), "leaf", "alice");
    var resumed = source().start(PAYMENTS, transcript(child.id(), 2, 1), "leaf", "alice");
    var again = source().start(PAYMENTS, transcript(child.id(), 3, 2), "leaf", "alice");
    assertEquals(leaf.usage().runs(), resumed.usage().runs());
    assertEquals(leaf.usage().runs(), again.usage().runs());
    assertEquals(leaf.usage().conversations(), again.usage().conversations());
    assertEquals(3L, again.usage().turnOrdinal());
    assertEquals(4, count("inference_run_ownership"));
  }

  @Test
  void the_first_tracked_resume_of_an_older_root_does_not_invent_a_new_execution() {
    var root = conversations.log(Origin.SUBMISSION, PAYMENTS, "root", null, Budget.of(10), "alice");
    var resumed = owners.start(PAYMENTS, transcript(root.id(), 2, 1), "root", "alice");
    var again = source().start(PAYMENTS, transcript(root.id(), 3, 2), "root", "alice");
    assertEquals("run:" + root.id() + ":1", resumed.usage().runs().id());
    assertEquals(resumed.usage().runs(), again.usage().runs());
    assertEquals(3, count("inference_run_ownership"));
  }

  @Test
  void ownership_rejects_project_account_and_parent_changes_before_any_model_call() {
    var root = conversations.log(Origin.SUBMISSION, PAYMENTS, "root", null, Budget.of(10), "alice");
    var other =
        conversations.log(Origin.SUBMISSION, Home.of("other"), "root", null, Budget.of(10), "bob");
    var child = conversations.log(Origin.DELEGATION, PAYMENTS, "leaf", root.id(), null, null);
    var owned = owners.start(PAYMENTS, transcript(root.id(), 1, null), "root", "alice");
    var wrong = owners.start(Home.of("other"), transcript(other.id(), 1, null), "root", "bob");
    assertThrows(
        LlmException.class,
        () -> owners.start(Home.of("other"), transcript(root.id(), 1, null), "root", "alice"));
    assertThrows(
        LlmException.class,
        () -> owners.start(PAYMENTS, transcript(root.id(), 1, null), "root", "bob"));
    assertThrows(
        LlmException.class,
        () ->
            owners.start(
                PAYMENTS, parent(transcript(child.id(), 1, null), wrong.usage()), "leaf", "alice"));
    assertEquals(
        owned.usage(),
        source().start(PAYMENTS, transcript(root.id(), 1, null), "root", "alice").usage());
    assertEquals(2, count("inference_run_ownership"));
  }

  @Test
  void one_pool_serving_parallel_projects_never_shares_a_current_owner() throws Exception {
    var first = conversations.open(PAYMENTS, Budget.of(10), TurnCap.none(), "alice");
    var second = conversations.open(Home.of("other"), Budget.of(10), TurnCap.none(), "bob");
    var registry = registry();
    var entered = new CountDownLatch(2);
    try (var harness =
            harness(
                () -> {
                  entered.countDown();
                  try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                  } catch (InterruptedException failure) {
                    throw new IllegalStateException(failure);
                  }
                  return answer();
                });
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var runtime = new JobRuntime(harness.dispatcher, List.of());
      runtime.useRunUsage(owners);
      var one =
          workers.submit(
              () ->
                  runtime.run(
                      registry.get("leaf"),
                      "one",
                      PAYMENTS,
                      Budget.of(10),
                      () -> false,
                      null,
                      JobWatch.UNWATCHED,
                      transcript(first.id(), 1, null),
                      TurnCap.none(),
                      List.of(),
                      "alice"));
      var two =
          workers.submit(
              () ->
                  runtime.run(
                      registry.get("leaf"),
                      "two",
                      Home.of("other"),
                      Budget.of(10),
                      () -> false,
                      null,
                      JobWatch.UNWATCHED,
                      transcript(second.id(), 1, null),
                      TurnCap.none(),
                      List.of(),
                      "bob"));
      assertEquals(Outcome.Ending.ANSWERED, one.get(5, TimeUnit.SECONDS).ending());
      assertEquals(Outcome.Ending.ANSWERED, two.get(5, TimeUnit.SECONDS).ending());
      project(harness);
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(DISTINCT project_id) FROM inference_calls WHERE pool = 'pool'",
              Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE account_handle = 'alice' AND conversation_id = ?",
              Integer.class,
              first.id()));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE account_handle = 'bob' AND conversation_id = ?",
              Integer.class,
              second.id()));
    }
  }

  @Test
  void enabled_spring_wiring_automatically_attaches_run_ownership_to_the_runtime()
      throws Exception {
    var registry = registry();
    var conversation = conversations.open(PAYMENTS, Budget.of(10), TurnCap.none(), "alice");
    try (var harness = harness(RunAttributionTest::answer)) {
      var runtime = new JobRuntime(harness.dispatcher, List.of());
      var properties = new LlmProperties();
      var pool = new PoolProperties();
      pool.setName("pool");
      pool.setModels(List.of("model-fast"));
      properties.setPools(List.of(pool));
      new org.springframework.boot.test.context.runner.ApplicationContextRunner()
          .withUserConfiguration(AccountingConfig.class)
          .withBean(LlmProperties.class, () -> properties)
          .withBean(InferencePrices.class, () -> PricingCatalog.from(properties))
          .withBean(
              com.fasterxml.jackson.databind.ObjectMapper.class, () -> AccountingFixtures.MAPPER)
          .withBean(
              io.aeyer.plowshare.server.data.DataLayout.class,
              () ->
                  new io.aeyer.plowshare.server.data.DataLayout(directory.resolve("server-data"))
                      .initialise())
          .withBean(JdbcTemplate.class, () -> jdbc)
          .withBean(
              org.springframework.transaction.PlatformTransactionManager.class,
              () -> new DataSourceTransactionManager(database))
          .withBean(JobRuntime.class, () -> runtime)
          .withPropertyValues("plowshare.llm.accounting.enabled=true")
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                var result =
                    runtime.run(
                        registry.get("leaf"),
                        "work",
                        PAYMENTS,
                        Budget.of(10),
                        () -> false,
                        null,
                        JobWatch.UNWATCHED,
                        transcript(conversation.id(), 1, null),
                        TurnCap.none(),
                        List.of(),
                        "alice");
                assertEquals(Outcome.Ending.ANSWERED, result.ending());
                project(harness);
                assertEquals(
                    "ATTRIBUTED",
                    jdbc.queryForObject(
                        "SELECT attribution_status FROM inference_calls", String.class));
                assertEquals(1, count("inference_run_ownership"));
              });
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void runtime_and_standalone_transcripts_keep_ownership_for_an_async_fold(boolean standalone)
      throws Exception {
    var registry = registry();
    var conversation = conversations.open(PAYMENTS, Budget.of(20), TurnCap.none(), "alice");
    var entries = new EntryStore(jdbc);
    var turns = new TurnStore(jdbc);
    entries.append(
        conversation.id(), 1, LoggedEntry.utterance("earlier question", Speaker.harness()));
    entries.append(conversation.id(), 1, LoggedEntry.answer("earlier answer", List.of()));
    turns.record(
        conversation.id(),
        "earlier question",
        "earlier answer",
        Outcome.Ending.ANSWERED,
        10,
        "leaf",
        null);
    var calls = new AtomicInteger();
    try (var harness =
        harness(
            () ->
                calls.getAndIncrement() == 0
                    ? new Completion("answer", "stop", TokenUsage.of(61_000, 2, 61_002), List.of())
                    : answer())) {
      var folded = new CountDownLatch(1);
      var folder =
          new AgentDefinition(
              "conversation_folder",
              "fixture folder",
              "fast",
              List.of(),
              List.of(),
              List.of(),
              1,
              1,
              "Summarise.");
      var compaction =
          new Compaction(
              harness.dispatcher,
              () -> folder,
              turns,
              new CompactionStore(jdbc),
              entries,
              100_000,
              conversations,
              folded::countDown);
      try {
        compaction.useRunUsage(owners);
        var original =
            compaction.transcriptFor(conversation.id(), registry.get("leaf"), Speaker.harness());
        Outcome result;
        if (standalone) {
          original.before();
          original.promptMeasured(61_000);
          original.record(LoggedEntry.utterance("work", Speaker.harness()));
          original.record(LoggedEntry.answer("answer", List.of()));
          result = new Outcome(Outcome.Ending.ANSWERED, "answer", 0, 0, "");
        } else {
          var runtime = new JobRuntime(harness.dispatcher, List.of());
          runtime.useRunUsage(owners);
          result =
              runtime.run(
                  registry.get("leaf"),
                  "work",
                  PAYMENTS,
                  Budget.of(20),
                  () -> false,
                  null,
                  JobWatch.UNWATCHED,
                  original,
                  TurnCap.none(),
                  List.of(),
                  "alice");
        }
        original.closed("work", result);
        assertTrue(folded.await(5, TimeUnit.SECONDS), "the async fold did not commit");
        var owner = original.usage();
        assertEquals("alice", owner.accountHandle());
        assertThrows(
            IllegalStateException.class,
            () ->
                original.accounted(
                    UsageAttribution.system(
                        owner.projectId(), UsageAttribution.Operation.AGENT_CHAT)));
        project(harness);
        assertEquals(standalone ? 1 : 2, count("inference_calls"));
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM inference_calls WHERE operation = 'FOLD' AND agent_name = 'conversation_folder' AND account_handle = 'alice' AND turn_ordinal = 2",
                Integer.class));
        assertEquals(
            List.of(owner.runs().id()),
            jdbc.queryForList("SELECT DISTINCT run_id FROM inference_calls", String.class));
        assertEquals(
            owner.projectId(),
            jdbc.queryForObject(
                "SELECT project_id FROM inference_calls WHERE operation = 'FOLD'", String.class));
      } finally {
        compaction.close();
      }
    }
  }

  @Test
  void asynchronous_validator_and_advisor_keep_the_originating_execution_and_name_their_own_actor()
      throws Exception {
    var registry = registry();
    var conversation = conversations.open(PAYMENTS, Budget.of(10), TurnCap.none(), "alice");
    var owner =
        owners.start(PAYMENTS, transcript(conversation.id(), 1, null), "leaf", "alice").usage();
    try (var harness = harness(RunAttributionTest::answer);
        var validator =
            new io.aeyer.plowshare.server.harness.ModelCallValidator(
                harness.dispatcher, () -> registry.get("leaf"));
        var advisor =
            new io.aeyer.plowshare.server.harness.StuckTrapFactory(
                harness.dispatcher, () -> registry.get("middle"))) {
      var question =
          new CallValidator.Question(
              "held", new ToolSchema("file_read", "read", Map.of("type", "object")), "task", owner);
      validator.validate(question, () -> false);
      var trap =
          advisor.create(
              io.aeyer.plowshare.server.harness.Parameters.read(
                  "harness:stuck",
                  io.aeyer.plowshare.server.harness.StuckTrapFactory.PARAMETERS,
                  Map.of(
                      "failure-streak",
                      "1",
                      "repeat-failures",
                      "0",
                      "repeat-calls",
                      "0",
                      "read-only-steps",
                      "0",
                      "advisor",
                      "fast")));
      var context =
          new io.aeyer.plowshare.server.hooks.HookContext(
                  "leaf",
                  false,
                  Set.of("file_read"),
                  PAYMENTS.project(),
                  conversation.id(),
                  "server")
              .withUsage(owner);
      trap.promptPre(context, "task");
      trap.stepPost(
          context,
          new io.aeyer.plowshare.server.hooks.Step(
              1,
              "model-fast",
              List.of(new ToolCall("read", "file_read", "{}")),
              List.of("there is no file at gone"),
              ""));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (harness.journal.readBatch(100).stream()
                  .filter(entry -> entry.event().payload() instanceof AccountingEvent.CallFinished)
                  .count()
              < 2
          && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      trap.finish();
      project(harness);
      assertEquals(2, count("inference_calls"));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE operation = 'REVIEW' AND agent_name = 'leaf'",
              Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE operation = 'HOOK_MODEL' AND agent_name = 'middle'",
              Integer.class));
      assertEquals(
          List.of(owner.runs().id()),
          jdbc.queryForList("SELECT DISTINCT run_id FROM inference_calls", String.class));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE account_handle = 'alice' AND project_id = ?",
              Integer.class,
              owner.projectId()));
    }
  }

  @Test
  void
      attributed_embedding_overloads_book_one_call_per_batch_with_the_supplied_owner_and_operation()
          throws Exception {
    var registry = registry();
    var conversation = conversations.open(PAYMENTS, Budget.of(10), TurnCap.none(), "alice");
    var owner =
        owners.start(PAYMENTS, transcript(conversation.id(), 1, null), "leaf", "alice").usage();
    try (var harness = harness(RunAttributionTest::answer)) {
      var properties = new LlmProperties();
      properties.setEmbeddingModel("model-fast");
      properties.setEmbeddingDim(2);
      properties.setEmbeddingMaxInputTokens(1536);
      io.aeyer.plowshare.server.llm.EmbeddingClient client =
          new io.aeyer.plowshare.server.llm.DispatchingEmbeddingClient(
              harness.dispatcher,
              properties,
              new io.aeyer.plowshare.server.llm.tokens.RatioTokenizer(4));
      assertEquals(
          2,
          client.embed("query", owner.withOperation(UsageAttribution.Operation.EMBEDDING_QUERY))
              .length);
      assertEquals(
          2,
          client
              .embedAll(
                  List.of("first", "second"),
                  owner.withOperation(UsageAttribution.Operation.EMBEDDING_WRITE))
              .size());
      assertEquals(
          List.of(),
          client.embedAll(
              List.of(), owner.withOperation(UsageAttribution.Operation.EMBEDDING_REPAIR)));
      assertThrows(NullPointerException.class, () -> client.embedAll(List.of("bad owner"), null));
      project(harness);
      assertEquals(2, count("inference_calls"));
      assertEquals(2, count("inference_attempts"));
      assertEquals(
          Set.of("EMBEDDING_QUERY", "EMBEDDING_WRITE"),
          Set.copyOf(jdbc.queryForList("SELECT operation FROM inference_calls", String.class)));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE lane = 'EMBEDDING' AND account_handle = 'alice' AND run_id = ?",
              Integer.class,
              owner.runs().id()));
      assertEquals(
          30L, jdbc.queryForObject("SELECT sum(input_tokens) FROM inference_attempts", Long.class));
    }
  }

  @Test
  void genuine_unowned_automation_is_system_usage_and_unknown_conversations_fail_closed() {
    var root =
        conversations.log(Origin.SUBMISSION, Home.global(), "leaf", null, Budget.of(10), null);
    var owned = owners.start(Home.global(), transcript(root.id(), 1, null), "leaf", null);
    assertEquals(UsageAttribution.Status.SYSTEM, owned.usage().status());
    assertEquals(UsageAttribution.Scope.GLOBAL, owned.usage().scope());
    assertNull(owned.usage().accountHandle());
    assertThrows(
        LlmException.class,
        () -> owners.start(Home.global(), transcript("missing", 1, null), "leaf", null));
    assertThrows(
        LlmException.class, () -> owners.start(Home.global(), Transcript.NONE, "leaf", null));
  }

  @Test
  void enabled_accounting_refuses_a_new_unattributed_request_before_any_inference()
      throws Exception {
    var invoked = new AtomicInteger();
    try (var harness =
        harness(
            () -> {
              invoked.incrementAndGet();
              return answer();
            })) {
      assertThrows(
          LlmException.class,
          () -> harness.dispatcher.complete(ChatRequest.of("fast", null, "work")));
      assertEquals(0, invoked.get());
      assertTrue(harness.journal.readBatch(100).isEmpty());
    }
  }

  @Test
  void service_root_is_owned_before_a_child_runs_even_when_the_parent_never_calls_a_model()
      throws Exception {
    var registry = registry();
    var summary =
        new AgentDefinition(
            "document_summariser",
            "summarises",
            "fast",
            List.of(),
            List.of(),
            List.of(),
            10,
            10,
            "Summarise.");
    try (var harness = harness(RunAttributionTest::answer)) {
      var logs = logs(harness.dispatcher, registry.get("leaf"));
      logs.useRunUsage(owners);
      try {
        var root =
            logs.logFor(
                Origin.SUBMISSION,
                PAYMENTS,
                summary,
                null,
                Budget.of(10),
                Speaker.harness(),
                "alice");
        var owned = logs.own(PAYMENTS, root, summary, "alice");
        var child = owned.delegate(registry.get("leaf"), PAYMENTS);
        var runtime = new JobRuntime(harness.dispatcher, List.of());
        runtime.useRunUsage(owners);
        var result =
            runtime.run(
                registry.get("leaf"),
                "summarise paragraph",
                PAYMENTS,
                Budget.of(10),
                () -> false,
                null,
                JobWatch.UNWATCHED,
                child);
        assertEquals(Outcome.Ending.ANSWERED, result.ending());
        project(harness);
        assertEquals(2, count("inference_run_ownership"));
        assertEquals(1, count("inference_calls"));
        assertEquals(
            "DOCUMENT_SUMMARY",
            jdbc.queryForObject("SELECT operation FROM inference_calls", String.class));
        assertEquals(
            owned.usage().runs().id(),
            jdbc.queryForObject("SELECT parent_run_id FROM inference_calls", String.class));
        assertEquals(
            "alice",
            jdbc.queryForObject("SELECT account_handle FROM inference_calls", String.class));
        assertThrows(
            LlmException.class, () -> owners.start(PAYMENTS, owned, summary.name(), "bob"));
      } finally {
        logs.close();
      }
    }
  }

  @Test
  void background_learning_uses_the_selected_material_owner_and_reuses_its_saved_run() {
    var a = conversations.open(PAYMENTS, Budget.of(10), TurnCap.none(), "alice");
    var b = conversations.open(Home.of("other"), Budget.of(10), TurnCap.none(), "bob");
    var owner = owners.start(PAYMENTS, transcript(a.id(), 1, null), "leaf", "alice").usage();
    owners.start(Home.of("other"), transcript(b.id(), 1, null), "leaf", "bob");
    var learning = owners.source(a.id(), 1, UsageAttribution.Operation.LEARNING);
    assertEquals(owner.withOperation(UsageAttribution.Operation.LEARNING), learning);
    assertEquals(
        owner.withOperation(UsageAttribution.Operation.REVIEW),
        owners.source(a.id(), 0, UsageAttribution.Operation.REVIEW));
    assertEquals("alice", learning.accountHandle());
    assertEquals(owner.projectId(), learning.projectId());
    assertEquals(2, count("inference_run_ownership"));
  }

  @Test
  void runtime_passes_the_immutable_step_owner_to_a_tool_independently_of_model_arguments()
      throws Exception {
    var registry = registry();
    var replies =
        List.of(
            new Completion(
                "call",
                "tool_calls",
                TokenUsage.of(10, 2, 12),
                List.of(new ToolCall("owned", "owned_tool", "{\"account\":\"bob\"}"))),
            answer());
    var calls = new AtomicInteger();
    var observed = new java.util.concurrent.atomic.AtomicReference<UsageAttribution>();
    AgentTool tool =
        new AgentTool() {
          public ToolSchema schema() {
            return new ToolSchema("owned_tool", "fixture", Map.of("type", "object"));
          }

          public String run(String json, Home home) {
            fail("enabled runtime must use owned tool capability");
            return "";
          }

          public String run(String json, Home home, UsageAttribution owner) {
            observed.set(owner);
            return "done";
          }
        };
    var definition =
        new AgentDefinition(
            "worker",
            "fixture",
            "fast",
            List.of("owned_tool"),
            List.of(),
            List.of(),
            10,
            10,
            "Work.");
    var conversation = conversations.open(PAYMENTS, Budget.of(10), TurnCap.none(), "alice");
    try (var harness = harness(() -> replies.get(calls.getAndIncrement()))) {
      var runtime = new JobRuntime(harness.dispatcher, List.of(tool));
      runtime.useRunUsage(owners);
      runtime.run(
          definition,
          "work",
          PAYMENTS,
          Budget.of(10),
          () -> false,
          null,
          JobWatch.UNWATCHED,
          transcript(conversation.id(), 1, null),
          TurnCap.none(),
          List.of(),
          "alice");
      assertEquals("alice", observed.get().accountHandle());
      assertEquals(1L, observed.get().stepOrdinal());
      assertEquals(conversation.id(), observed.get().conversations().id());
      project(harness);
      assertEquals(2, count("inference_calls"));
      assertEquals(
          List.of(observed.get().runs().id()),
          jdbc.queryForList("SELECT DISTINCT run_id FROM inference_calls", String.class));
    }
  }

  private AgentRegistry registry() throws Exception {
    Path agents = directory.resolve("agents");
    Files.createDirectories(agents);
    for (var name : List.of("root", "middle", "leaf")) {
      String calls = name.equals("root") ? "[middle]" : "[leaf]";
      Files.writeString(
          agents.resolve(name + ".md"),
          "---\nname: "
              + name
              + "\ndescription: fixture\nmodel: fast\n"
              + (name.equals("leaf") ? "tools: []\n" : "tools: [agent_run]\ncalls: " + calls + "\n")
              + "max-turns: 4\nmax-model-calls: 12\n---\nYou perform the requested work.");
    }
    return AgentRegistry.of(agents, Set.of(AgentRegistry.AGENT_RUN));
  }

  private Compaction logs(LlmDispatcher dispatcher, AgentDefinition definition) {
    return new Compaction(
        dispatcher,
        () -> definition,
        new TurnStore(jdbc),
        new CompactionStore(jdbc),
        new EntryStore(jdbc),
        1_000_000,
        conversations);
  }

  private static Transcript transcript(String id, int turn, Integer continuing) {
    return new Transcript() {
      @Override
      public List<ChatMessage> before() {
        return List.of();
      }

      @Override
      public void promptMeasured(int tokens) {}

      @Override
      public String conversationId() {
        return id;
      }

      @Override
      public Spoken spokenIn() {
        return new Spoken(id, turn);
      }

      @Override
      public Integer continuingTurn() {
        return continuing;
      }
    };
  }

  private static Transcript parent(Transcript transcript, UsageAttribution owner) {
    return new AttributedTranscript(transcript, UsageAttribution.LEGACY, owner);
  }

  private static Completion answer() {
    return new Completion("answer", "stop", TokenUsage.of(10, 2, 12), List.of());
  }

  private static Completion asking(String callee) {
    return new Completion(
        "delegate",
        "tool_calls",
        TokenUsage.of(10, 2, 12),
        List.of(
            new ToolCall(
                "call-" + callee,
                "agent_run",
                "{\"agent\":\"" + callee + "\",\"task\":\"work\"}")));
  }

  private Harness harness(Supplier<Completion> reply) {
    var journal =
        new AccountingJournal(
            directory.resolve("journal"),
            1024 * 1024,
            8192,
            Duration.ofSeconds(1),
            AccountingFixtures.MAPPER);
    var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
    var properties = new PoolProperties();
    properties.setName("pool");
    properties.setModels(List.of("model-fast"));
    var settings = new LlmProperties();
    settings.setPools(List.of(properties));
    LlmTransport transport =
        new LlmTransport() {
          @Override
          public String poolName() {
            return "pool";
          }

          @Override
          public void close() {}

          @Override
          public Completion complete(
              String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
            return reply.get();
          }

          @Override
          public Completion stream(
              String model,
              List<ChatMessage> messages,
              Sampling sampling,
              List<ToolSchema> tools,
              Deltas sink,
              BooleanSupplier abandoned) {
            return reply.get();
          }

          @Override
          public java.util.OptionalInt contextLength(String model) {
            return java.util.OptionalInt.of(100_000);
          }

          @Override
          public java.util.OptionalInt compactionThreshold(String model) {
            return java.util.OptionalInt.of(60_000);
          }

          @Override
          public java.util.OptionalInt compactionNowThreshold(String model) {
            return java.util.OptionalInt.of(80_000);
          }

          @Override
          public Embeddings embed(String model, List<String> input) {
            return new Embeddings(
                input.stream().map(text -> new float[] {1, 0}).toList(),
                TokenUsage.of(15, null, null));
          }
        };
    var pool =
        new LlmPool(
            "pool",
            List.of("model-fast"),
            Map.of("fast", "model-fast"),
            2,
            1,
            Duration.ofSeconds(2),
            transport);
    var dispatcher =
        new LlmDispatcher(
            List.of(pool),
            null,
            type -> "",
            new DurableInferenceAccounting(
                recorder, PricingCatalog.from(settings), AccountingFixtures.CLOCK));
    return new Harness(journal, dispatcher);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private void project(Harness harness) {
    new AccountingStore(
            jdbc,
            new DataSourceTransactionManager(database),
            AccountingFixtures.MAPPER,
            AccountingFixtures.CLOCK)
        .project(harness.journal.journalId(), harness.journal.readBatch(100));
  }

  private record Harness(AccountingJournal journal, LlmDispatcher dispatcher)
      implements AutoCloseable {
    @Override
    public void close() {
      dispatcher.close();
      journal.close();
    }
  }
}
