package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.agents.digests.*;
import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("full-db")
@Testcontainers
class ConversationRetrievalTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static UnitOfWork transactions;
  EntryStore entries;
  ConversationStore conversations;
  PassageIndex index;
  ConversationSearch search;
  EmbeddingClient embeddings;
  Home project = Home.of("retrieval-project");

  @BeforeAll
  static void database() {
    var ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
    var template = new TransactionTemplate(new DataSourceTransactionManager(ds));
    transactions =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> work) {
            return template.execute(s -> work.get());
          }
        };
  }

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE memories,conversations,digests CASCADE");
    entries = new EntryStore(jdbc, transactions);
    conversations = new ConversationStore(jdbc);
    embeddings = mock(EmbeddingClient.class);
    when(embeddings.embed(anyString())).thenAnswer(i -> vector((String) i.getArgument(0)));
    when(embeddings.embedAll(anyList()))
        .thenAnswer(
            i -> {
              List<String> texts = i.getArgument(0);
              return texts.stream().map(this::vector).toList();
            });
    index =
        new PassageIndex(
            jdbc,
            transactions,
            embeddings,
            new Chunking(RatioTokenizer.anchoredOn(400, 100), 80, 100),
            "fixture:1",
            768);
    search = new ConversationSearch(entries, index);
    entries.retrieving(() -> search);
  }

  float[] vector(String text) {
    float[] v = new float[768];
    v[text.contains("orchard") || text.contains("fruit cultivation") ? 0 : 1] = 1;
    return v;
  }

  String add(Home home, String text) {
    String id = conversations.open(home, Budget.of(10)).id();
    entries.append(id, 1, LoggedEntry.utterance(text, Speaker.person(null)));
    return id;
  }

  void fill() {
    for (int i = 0; i < 80; i++) {
      if (jdbc.queryForObject(
              "SELECT count(*) FROM retrieval_sources WHERE status<>'ready'", Integer.class)
          == 0) return;
      index.repair();
    }
    fail("Repair did not finish");
  }

  @Test
  void lexical_is_backward_compatible_and_supersession_does_not_eject_history() {
    var id = add(project, "orchard identifier E_BAD_TREE");
    var seam = entries.append(id, 1, LoggedEntry.summary("fold"));
    entries.supersede(id, 0, 1, seam.ordinal());
    var old = search.search(project, "orchard", 0, 10, null, null);
    assertNull(old.retrieval());
    assertEquals(1, old.total());
    assertEquals(seam.ordinal(), old.hits().get(0).supersededBy());
    verifyNoInteractions(embeddings);
  }

  @Test
  void semantic_excerpt_scope_and_hybrid_exact_identifier() {
    String target = add(project, "We planted an orchard with pear trees.");
    add(Home.global(), "orchard outside this tier");
    add(Home.of("other"), "orchard elsewhere");
    String exact = add(project, "E_BAD_TREE retry failed");
    fill();
    var found = search.search(project, "fruit cultivation", 0, 10, "semantic", null);
    assertEquals(1, found.total());
    assertEquals(target, found.hits().get(0).conversationId());
    assertTrue(found.hits().get(0).snippet().contains("pear trees"));
    assertTrue(found.retrieval().complete());
    var hybrid = search.search(project, "E_BAD_TREE", 0, 10, "hybrid", null);
    assertTrue(hybrid.hits().stream().anyMatch(h -> h.conversationId().equals(exact)));
    assertThrows(
        ValidationException.class,
        () ->
            search.search(
                Home.global(),
                "fruit cultivation",
                0,
                10,
                "semantic",
                found.retrieval().snapshot()));
  }

  @Test
  void long_tool_results_are_passages_and_resume_across_worker_instances() {
    String id = add(project, "opening");
    var source =
        entries.append(
            id,
            1,
            LoggedEntry.toolResult(
                "call", "noise. ".repeat(1400) + " We planted an orchard with pear trees."));
    index.repair();
    assertTrue(index.coverage(project, "entry").pending() > 0);
    var resumed =
        new PassageIndex(
            jdbc,
            transactions,
            embeddings,
            new Chunking(RatioTokenizer.anchoredOn(400, 100), 80, 100),
            "fixture:1",
            768);
    for (int i = 0; i < 30 && resumed.coverage(project, "entry").pending() > 0; i++)
      resumed.repair();
    var found = search.search(project, "fruit cultivation", 0, 10, "semantic", null);
    assertEquals(1, found.total());
    assertEquals(source.ordinal(), found.hits().get(0).ordinal());
    assertNotNull(found.hits().get(0).handle());
    assertTrue(found.hits().get(0).snippet().contains("orchard"));
    verify(embeddings, atLeastOnce())
        .embedAll(
            argThat(
                texts -> texts.size() <= 16 && texts.stream().allMatch(t -> t.length() <= 400)));
  }

  @Test
  void snapshots_stay_stable_and_removed_payloads_are_not_returned() {
    var id = add(project, "header");
    var tool = entries.append(id, 1, LoggedEntry.toolResult("call", "orchard evidence"));
    add(project, "orchard second conversation");
    fill();
    var first = search.search(project, "fruit cultivation", 0, 1, "semantic", null);
    var token = first.retrieval().snapshot();
    add(project, "orchard newly arrived");
    fill();
    var next = search.search(project, "fruit cultivation", 1, 10, "semantic", token);
    assertEquals(2, next.total());
    assertEquals(1, next.hits().size());
    entries.ejectPayload(id, tool.ordinal(), Instant.now(), null);
    var same = search.search(project, "fruit cultivation", 0, 10, "semantic", token);
    assertTrue(
        same.hits().stream()
            .noneMatch(h -> h.ordinal() == tool.ordinal() && h.conversationId().equals(id)));
    assertFalse(same.retrieval().complete());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM retrieval_passages WHERE source_id=?",
            Integer.class,
            id + ":" + tool.ordinal()));
  }

  @Test
  void delayed_embedding_cannot_republish_ejected_or_changed_source() {
    String id = add(project, "header");
    var tool = entries.append(id, 1, LoggedEntry.toolResult("call", "orchard evidence"));
    String key = id + ":" + tool.ordinal();
    String hash =
        jdbc.queryForObject(
            "SELECT source_hash FROM retrieval_sources WHERE source_id=?", String.class, key);
    var delayed = new PassageIndex.Source("entry", key, hash, "orchard evidence", 0);
    entries.ejectPayload(id, tool.ordinal(), Instant.now(), null);
    assertFalse(index.publish(delayed, List.of("orchard evidence"), List.of(vector("orchard"))));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM retrieval_passages WHERE source_id=?", Integer.class, key));
  }

  @Test
  void failed_embeddings_preserve_lexical_search_and_report_incomplete_hybrid() {
    add(project, "orchard");
    when(embeddings.embedAll(anyList())).thenThrow(new EmbeddingException("offline"));
    index.repair();
    assertEquals(1, index.coverage(project, "entry").failed());
    when(embeddings.embed(anyString())).thenThrow(new EmbeddingException("offline"));
    var hybrid = search.search(project, "orchard", 0, 10, "hybrid", null);
    assertEquals(1, hybrid.total());
    assertEquals("lexical", hybrid.retrieval().effectiveMode());
    assertFalse(hybrid.retrieval().complete());
    assertThrows(
        EmbeddingException.class, () -> search.search(project, "orchard", 0, 10, "semantic", null));
    assertEquals(1, search.search(project, "orchard", 0, 10, null, null).total());
  }

  @Test
  void embedding_generation_changes_require_rebuild_and_do_not_mix_spaces() {
    add(project, "orchard");
    fill();
    var next =
        new PassageIndex(
            jdbc,
            transactions,
            embeddings,
            new Chunking(RatioTokenizer.anchoredOn(400, 100), 80, 100),
            "fixture:2",
            768);
    assertEquals(0, next.coverage(project, "entry").indexed());
    assertTrue(next.rank(project, "entry", vector("orchard"), 10).isEmpty());
    next.repair();
    assertEquals(1, next.coverage(project, "entry").indexed());
  }

  @Test
  void duplicate_passages_do_not_crowd_out_other_entries_before_the_candidate_bound() {
    String first = add(project, "orchard first");
    String second = add(project, "orchard second");
    String hash =
        jdbc.queryForObject(
            "SELECT source_hash FROM retrieval_sources WHERE source_id=?",
            String.class,
            first + ":1");
    index.publish(
        new PassageIndex.Source("entry", first + ":1", hash, "orchard first", 0),
        Collections.nCopies(220, "orchard passage"),
        Collections.nCopies(220, vector("orchard")));
    index.repair();
    var matches = index.rank(project, "entry", vector("orchard"), 2);
    assertEquals(2, matches.size());
    assertTrue(matches.stream().anyMatch(m -> m.id().equals(second + ":1")));
  }

  @Test
  void exact_scoped_ranking_measures_filtered_recall_at_ten_thousand_passages() {
    String own = add(project, "orchard fixture"), outside = add(Home.global(), "orchard fixture");
    for (String conversation : List.of(own, outside))
      jdbc.update(
          "INSERT INTO entries(conversation_id,ordinal,turn_ordinal,kind,role,content) "
              + "SELECT ?,g,1,'utterance','user','orchard fixture ' || g FROM generate_series(2,1000) g",
          conversation);
    jdbc.update(
        "UPDATE retrieval_sources SET status='ready',generation=? WHERE source_type='entry'",
        index.generation());
    jdbc.update(
        "INSERT INTO retrieval_passages(source_type,source_id,position,passage,embedding) "
            + "SELECT s.source_type,s.source_id,g,'orchard fixture passage',CAST(? AS vector) "
            + "FROM retrieval_sources s CROSS JOIN generate_series(0,4) g WHERE s.source_type='entry'",
        Arrays.toString(vector("orchard")));
    assertEquals(
        10000, jdbc.queryForObject("SELECT count(*) FROM retrieval_passages", Integer.class));
    long began = System.nanoTime();
    var ranked = index.rank(project, "entry", vector("orchard"), 201);
    long millis = java.time.Duration.ofNanos(System.nanoTime() - began).toMillis();
    assertEquals(201, ranked.size());
    assertTrue(ranked.stream().allMatch(m -> m.id().startsWith(own + ":")));
    assertEquals(201, ranked.stream().map(PassageIndex.Match::id).distinct().count());
    var bounded = search.search(project, "fruit cultivation", 0, 10, "semantic", null);
    assertEquals(200, bounded.total());
    assertTrue(bounded.retrieval().truncated());
    assertFalse(bounded.retrieval().complete());
    System.out.println(
        "Scoped exact ranking: 10000 passages, 2000 entries, 5000 in scope; qualified top-201 recall 201/201; elapsed_ms="
            + millis);
  }

  @Test
  void
      committed_writes_are_indexed_asynchronously_and_failed_network_calls_hold_no_write_transaction()
          throws Exception {
    var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("plowshare.retrieval.worker-enabled", "true");
    var worker = new RetrievalIndexConfig().passageRepairWorker(index, env);
    when(embeddings.embedAll(anyList()))
        .thenAnswer(
            i -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              List<String> texts = i.getArgument(0);
              return texts.stream().map(this::vector).toList();
            });
    try {
      worker.start();
      transactions.inTransaction(
          () -> {
            add(project, "orchard committed");
            return null;
          });
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
      while (index.coverage(project, "entry").indexed() == 0 && System.nanoTime() < deadline)
        Thread.sleep(50);
      assertEquals(1, index.coverage(project, "entry").indexed());
    } finally {
      worker.stop();
    }
  }

  @Test
  void late_summaries_do_not_change_the_exact_evidence_address() {
    String id = add(project, "orchard first turn");
    var next = entries.append(id, 2, LoggedEntry.utterance("second turn", Speaker.person(null)));
    entries.append(id, 1, LoggedEntry.summary("late fold"));
    var tool = new ConversationTrajectoryTool(conversations, entries);
    String result =
        tool.run("{\"conversation\":\"" + id + "\",\"ordinal\":" + next.ordinal() + "}", project);
    assertTrue(result.contains("second turn"));
    assertFalse(result.contains("late fold"));
  }

  @Test
  void deletion_and_home_moves_are_rechecked_by_snapshot_and_digest_source_reads() {
    String id = add(project, "orchard source");
    fill();
    var first = search.search(project, "fruit cultivation", 0, 10, "semantic", null);
    jdbc.update(
        "UPDATE conversations SET project_id=? WHERE id=?",
        ProjectIds.toWrite(jdbc, Home.of("elsewhere")),
        id);
    var page =
        search.search(
            project, "fruit cultivation", 0, 10, "semantic", first.retrieval().snapshot());
    assertTrue(page.hits().isEmpty());
    assertFalse(page.retrieval().complete());
    var store = new DigestStore(jdbc, transactions);
    assertTrue(store.source(project, new DigestStore.Span(id, 0, 1)).isEmpty());
    jdbc.update("DELETE FROM entries WHERE conversation_id=?", id);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM retrieval_sources WHERE source_type='entry'", Integer.class));
  }

  @Test
  void invalid_window_and_mode_are_refused() {
    assertThrows(
        ValidationException.class,
        () -> search.search(project, "orchard", 1, 10, "semantic", null));
    assertThrows(
        ValidationException.class, () -> search.search(project, "orchard", 0, 10, "unknown", null));
    assertThrows(
        ValidationException.class,
        () -> search.search(project, "orchard", 0, 10, "hybrid", "missing"));
  }

  @Test
  void semantic_seeds_below_roots_and_tree_fallback_reach_durable_evidence() {
    var store = new DigestStore(jdbc, transactions);
    String id = "dig_fixture";
    jdbc.update(
        "INSERT INTO digests(id,project_id,depth,summary) VALUES(?,?,0,?)",
        id,
        ProjectIds.toWrite(jdbc, project),
        "orchard durable summary");
    var second =
        jdbc.update(
            "INSERT INTO digests(id,project_id,depth,summary) VALUES(?,?,0,?)",
            "dig_other",
            ProjectIds.toRead(jdbc, project),
            "unrelated subject");
    assertEquals(1, second);
    var parent = store.group(project, store.roots(project), "unrelated parent", null);
    fill();
    var model = mock(DigestModel.class);
    var operation = mock(DigestModel.Operation.class);
    when(model.operation(anyString(), any(), any())).thenReturn(operation);
    when(model.operation(anyString(), any(), any(), any())).thenReturn(operation);
    when(operation.usage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    when(operation.call(anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            i -> {
              ((Budget) i.getArgument(4)).trySpend();
              return id;
            });
    var navigator = new Navigator(store, null, entries, null, model).seeded(index);
    var found = navigator.navigate(project, "fruit cultivation", Budget.of(4), () -> false);
    assertEquals(List.of(id), found.ids());
    assertEquals("semantic", found.retrieval().seedMode());
    assertEquals(1, found.modelCalls());
    // Without vectors the parent and its descendants remain navigable.
    jdbc.update("DELETE FROM retrieval_passages WHERE source_type='digest'");
    jdbc.update("UPDATE retrieval_sources SET status='pending' WHERE source_type='digest'");
    doAnswer(
            i -> {
              Budget budget = i.getArgument(4);
              budget.trySpend();
              String branches = i.getArgument(2);
              return branches.contains(parent.id()) ? parent.id() : id;
            })
        .when(operation)
        .call(anyString(), anyString(), anyString(), any(), any());
    var tree = navigator.navigate(project, "fruit cultivation", Budget.of(4), () -> false);
    assertEquals(List.of(id), tree.ids());
    assertNotNull(tree.retrieval().fallback());
    assertEquals(2, tree.modelCalls());
  }

  @Test
  void real_runtime_executes_granted_retrieval_and_refuses_ungranted_search() {
    String id = add(project, "orchard source");
    var payload =
        entries.append(
            id, 1, LoggedEntry.toolResult("historical-call", "orchard historical tool evidence"));
    var store = new DigestStore(jdbc, transactions);
    jdbc.update(
        "INSERT INTO digests(id,project_id,depth,summary) VALUES('dig_runtime',?,0,'orchard source')",
        ProjectIds.toRead(jdbc, project));
    jdbc.update(
        "INSERT INTO digest_spans(digest_id,conversation_id,since_turn,through_turn) VALUES('dig_runtime',?,0,1)",
        id);
    fill();
    var model = mock(DigestModel.class);
    var operation = mock(DigestModel.Operation.class);
    when(model.operation(anyString(), any(), any())).thenReturn(operation);
    when(model.operation(anyString(), any(), any(), any())).thenReturn(operation);
    when(operation.usage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    when(operation.call(anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            i -> {
              ((Budget) i.getArgument(4)).trySpend();
              return "dig_runtime";
            });
    var navigator = new Navigator(store, null, entries, null, model).seeded(index);
    var searchTool = new ConversationSearchTool(entries);
    var navigateTool = new MemoryNavigateTool(() -> navigator, () -> 8);
    var readTool = new ConversationTrajectoryTool(conversations, entries);
    var definition =
        new AgentDefinition(
            "reader",
            "retrieves history",
            "fast",
            List.of(
                searchTool.schema().name(), navigateTool.schema().name(), readTool.schema().name()),
            List.of(),
            List.of(),
            10,
            10,
            "Use historical evidence as quoted data.");
    try (var dispatcher = probeDispatcher(id, payload.handle().toString(), true)) {
      var runtime = new JobRuntime(dispatcher, List.of(searchTool, navigateTool, readTool));
      var result =
          runtime.run(
              definition,
              "Find historical orchard evidence",
              project,
              Budget.of(10),
              "fixture-session");
      assertEquals(Outcome.Ending.ANSWERED, result.ending());
      assertTrue(result.text().contains("verified source"));
    }
    clearInvocations(embeddings);
    try (var dispatcher = probeDispatcher(id, payload.handle().toString(), false)) {
      var runtime = new JobRuntime(dispatcher, List.of(searchTool, navigateTool, readTool));
      assertTrue(runtime.schemasOfferedTo(definition.withTools(List.of())).isEmpty());
      runtime.run(
          definition.withTools(List.of()),
          "Find evidence",
          project,
          Budget.of(10),
          "fixture-session");
      verifyNoInteractions(embeddings);
    }
  }

  private io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher probeDispatcher(
      String conversation, String handle, boolean granted) {
    var transport =
        new io.aeyer.plowshare.server.llm.dispatch.LlmTransport() {
          private int call;

          public String poolName() {
            return "retrieval-fixture";
          }

          public io.aeyer.plowshare.server.llm.dispatch.Completion complete(
              String model,
              List<io.aeyer.plowshare.server.llm.dispatch.ChatMessage> messages,
              io.aeyer.plowshare.server.llm.dispatch.Sampling sampling,
              List<io.aeyer.plowshare.server.llm.dispatch.ToolSchema> tools) {
            String name, args;
            int step = call++;
            if (step == 0) {
              name = "conversation_search";
              args = "{\"question\":\"fruit cultivation\"}";
            } else if (!granted)
              return new io.aeyer.plowshare.server.llm.dispatch.Completion(
                  "finished",
                  "stop",
                  io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                  List.of());
            else if (step == 1) {
              assertTrue(messages.stream().anyMatch(m -> m.content().contains(conversation)));
              name = "conversation_trajectory";
              args = "{\"conversation\":\"" + conversation + "\",\"handle\":\"" + handle + "\"}";
            } else if (step == 2) {
              assertTrue(
                  messages.stream()
                      .anyMatch(m -> m.content().contains("orchard historical tool evidence")));
              name = "memory_navigate";
              args = "{\"question\":\"fruit cultivation\"}";
            } else {
              assertTrue(
                  messages.stream().anyMatch(m -> m.content().contains("Reached log_entries")));
              return new io.aeyer.plowshare.server.llm.dispatch.Completion(
                  "verified source " + conversation,
                  "stop",
                  io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                  List.of());
            }
            if (granted) assertTrue(tools.stream().anyMatch(t -> t.name().equals(name)));
            return new io.aeyer.plowshare.server.llm.dispatch.Completion(
                "",
                "tool_calls",
                io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                List.of(new io.aeyer.plowshare.protocol.ToolCall("call-" + step, name, args)));
          }

          public io.aeyer.plowshare.server.llm.dispatch.Completion stream(
              String model,
              List<io.aeyer.plowshare.server.llm.dispatch.ChatMessage> messages,
              io.aeyer.plowshare.server.llm.dispatch.Sampling sampling,
              List<io.aeyer.plowshare.server.llm.dispatch.ToolSchema> tools,
              io.aeyer.plowshare.server.llm.dispatch.Deltas deltas,
              java.util.function.BooleanSupplier cancelled) {
            return complete(model, messages, sampling, tools);
          }

          public io.aeyer.plowshare.server.llm.dispatch.Embeddings embed(
              String model, List<String> input) {
            throw new UnsupportedOperationException();
          }

          public void close() {}
        };
    return new io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher(
        List.of(
            new io.aeyer.plowshare.server.llm.dispatch.LlmPool(
                "retrieval-fixture",
                List.of("fixture-model"),
                Map.of("fast", "fixture-model"),
                1,
                1,
                java.time.Duration.ofSeconds(5),
                transport)),
        new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger());
  }

  @Test
  void internal_search_cannot_override_home_and_returns_historical_evidence_route() {
    add(project, "orchard");
    fill();
    var tool = new ConversationSearchTool(entries);
    assertThrows(
        IllegalArgumentException.class,
        () -> tool.run("{\"question\":\"orchard\",\"project\":\"other\"}", project));
    assertTrue(
        tool.run("{\"question\":\"fruit cultivation\"}", project).contains("conversationId"));
    assertTrue(tool.schema().description().contains("conversation_trajectory"));
  }
}
