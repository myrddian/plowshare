package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.digests.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import java.util.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("full-db")
@Testcontainers
class DigestStoreTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static UnitOfWork transactions;
  DigestStore digests;
  Archive archive;
  EntryStore entries;
  ConversationStore conversations;
  EmbeddingClient embeddings;
  DigestModel model;
  DigestModel.Operation operation;
  Home home = Home.of("digest-project");

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
    jdbc.execute("TRUNCATE digests,memories,conversations CASCADE");
    digests = new DigestStore(jdbc, transactions);
    entries = new EntryStore(jdbc);
    conversations = new ConversationStore(jdbc);
    embeddings = mock(EmbeddingClient.class);
    when(embeddings.embed(anyString())).thenReturn(new float[768]);
    archive =
        new Archive(
            new MemoryStore(jdbc), new ReasonLog(jdbc), embeddings, transactions, 8000, 200, 30);
    model = mock(DigestModel.class);
    operation = mock(DigestModel.Operation.class);
    when(model.operation(anyString(), any(), any())).thenReturn(operation);
    when(operation.usage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    when(operation.call(anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              Budget budget = invocation.getArgument(4);
              budget.trySpend();
              return "Aggregate summary";
            });
  }

  String memory(String title) {
    return archive
        .applyVerdict(
            new MemoryProposal(title, "this project", "body of " + title, "test", "fixture"),
            new Verdict(VerdictKind.NEW, null, "new"),
            home)
        .memoryId();
  }

  Digester digester() {
    return new Digester(digests, archive, model, embeddings, new MemoryProperties());
  }

  Navigator navigator() {
    return new Navigator(digests, archive, entries, new ReasonLog(jdbc), model);
  }

  @Test
  void a_memory_is_a_durable_leaf_and_browsing_does_not_count_a_use() {
    String id = memory("A decision");
    var leaf = digests.roots(home).get(0);
    assertEquals(id, digests.memory(leaf.id()));
    assertEquals(0, archive.get(id).uses());
    assertTrue(digests.roots(Home.global()).isEmpty());
  }

  @Test
  void a_fold_is_copied_atomically_before_any_learning_pass() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    entries.append(conversation, 1, LoggedEntry.utterance("We chose A", Speaker.person(null)));
    EntryRecord seam = entries.append(conversation, 1, LoggedEntry.summary("A was chosen"));
    entries.supersede(conversation, 0, 1, seam.ordinal());
    var leaf = digests.roots(home).get(0);
    assertEquals("A was chosen", leaf.summary());
    assertTrue(digests.covered(conversation));
    assertEquals(new DigestStore.Span(conversation, 0, 1), digests.span(leaf.id()));
    assertEquals("We chose A", digests.source(digests.span(leaf.id())).get(0).content());
  }

  @Test
  void a_failed_fold_transaction_leaves_neither_summary_nor_digest() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  entries.append(conversation, 1, LoggedEntry.summary("must roll back"));
                  throw new IllegalStateException("fail");
                }));
    assertTrue(digests.roots(home).isEmpty());
    assertTrue(entries.forConversation(conversation).isEmpty());
  }

  @Test
  void new_spans_leave_previous_fold_leaves_unchanged() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    entries.append(conversation, 1, LoggedEntry.summary("First"));
    entries.append(conversation, 3, LoggedEntry.summary("Second"));
    var leaves = digests.roots(home);
    assertEquals(2, leaves.size());
    assertTrue(
        leaves.stream()
            .map(n -> digests.span(n.id()))
            .anyMatch(s -> s.since() == 1 && s.through() == 3));
  }

  @Test
  void a_single_leaf_is_carried_without_a_model_call() {
    memory("A");
    digester().pass(home, Budget.of(10), () -> false);
    verify(operation, never()).call(anyString(), anyString(), anyString(), any(), any());
    assertEquals(1, digests.roots(home).size());
  }

  @Test
  void groups_grow_to_one_root_and_retry_does_no_work() {
    for (int i = 0; i < 19; i++) memory("Decision " + i);
    digester().pass(home, Budget.of(100), () -> false);
    var root = digests.roots(home).get(0);
    assertEquals(2, root.depth());
    assertEquals(3, digests.children(home, root.id()).size());
    clearInvocations(model, operation);
    digester().pass(home, Budget.of(100), () -> false);
    verify(operation, never()).call(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void only_ancestors_of_a_changed_memory_go_stale_and_old_summaries_survive() {
    String a = memory("A"), b = memory("B"), c = memory("C"), d = memory("D");
    var left =
        digests.group(
            home,
            List.of(digests.get(home, "dig_m_" + a), digests.get(home, "dig_m_" + b)),
            "Left",
            null);
    var right =
        digests.group(
            home,
            List.of(digests.get(home, "dig_m_" + c), digests.get(home, "dig_m_" + d)),
            "Right",
            null);
    var root = digests.group(home, List.of(left, right), "Root", null);
    archive.invalidate(a, "no longer true", "test");
    assertNotNull(digests.get(home, left.id()).staleAt());
    assertNull(digests.get(home, right.id()).staleAt());
    assertNotNull(digests.get(home, root.id()).staleAt());
    digester().pass(home, Budget.of(50), () -> false);
    assertTrue(digests.stale(home).isEmpty());
    assertEquals(
        "Left",
        jdbc.queryForObject(
            "SELECT summary FROM digest_revisions WHERE digest_id=?", String.class, left.id()));
  }

  @Test
  void schema_refuses_cross_home_edges_and_cycles() {
    String a = memory("A"), b = memory("B");
    var root = digests.group(home, digests.roots(home), "Root", null);
    assertThrows(
        RuntimeException.class,
        () -> jdbc.update("INSERT INTO digest_children VALUES(?,?)", "dig_m_" + a, root.id()));
    jdbc.update("INSERT INTO digests(id,depth,summary) VALUES('foreign',5,'global')");
    assertThrows(
        RuntimeException.class,
        () -> jdbc.update("INSERT INTO digest_children VALUES('foreign',?)", root.id()));
    assertNotNull(b);
  }

  @Test
  void a_memory_and_its_provenance_commit_together() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    var p = new MemoryProposal("A", "scope", "body", "test", "");
    var result =
        archive.applyVerdict(
            p,
            new Verdict(VerdictKind.NEW, null, "new"),
            home,
            null,
            id -> digests.provenance(id, conversation, List.of(1)));
    assertEquals(1, digests.provenance(result.memoryId()).size());
    int before = new MemoryStore(jdbc).loadAll(home).size();
    assertThrows(
        RuntimeException.class,
        () ->
            archive.applyVerdict(
                p,
                new Verdict(VerdictKind.NEW, null, "new"),
                home,
                null,
                id -> digests.provenance(id, "missing-conversation", List.of(1))));
    assertEquals(before, new MemoryStore(jdbc).loadAll(home).size());
  }

  @Test
  void navigation_returns_a_lesson_and_counts_only_that_memory() {
    String a = memory("A"), b = memory("B");
    digester().pass(home, Budget.of(10), () -> false);
    var root = digests.roots(home).get(0);
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenReturn(root.id(), "dig_m_" + b);
    var answer = navigator().navigate(home, "B?", Budget.of(10), () -> false);
    assertEquals("lesson", answer.level());
    assertEquals(List.of(b), answer.ids());
    assertEquals(0, archive.get(a).uses());
    assertEquals(1, archive.get(b).uses());
  }

  @Test
  void invalid_navigation_returns_the_deepest_summary_and_never_counts_a_memory() {
    String a = memory("A");
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenReturn("invented");
    var answer = navigator().navigate(home, "A?", Budget.of(10), () -> false);
    assertEquals("digest", answer.level());
    assertFalse(answer.complete());
    assertEquals(0, archive.get(a).uses());
  }

  @Test
  void a_span_with_no_lesson_is_reachable_and_ejected_sources_are_named() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    EntryRecord result =
        entries.append(conversation, 1, LoggedEntry.toolResult("call", "old file bytes"));
    EntryRecord seam =
        entries.append(conversation, 1, LoggedEntry.summary("The file used the old format"));
    entries.supersede(conversation, 0, 1, seam.ordinal());
    String leaf = digests.roots(home).get(0).id();
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenReturn(leaf);
    var first = navigator().navigate(home, "old format", Budget.of(10), () -> false);
    assertEquals("log_entries", first.level());
    assertTrue(first.text().contains("old file bytes"));
    entries.ejectPayload(conversation, result.ordinal(), java.time.Instant.now(), "/export/test");
    var after = navigator().navigate(home, "old format", Budget.of(10), () -> false);
    assertEquals("fold_summary", after.level());
    assertTrue(after.text().contains("old format"));
    assertTrue(after.text().contains("ejected on"));
    assertTrue(after.text().contains("/export/test"));
  }

  @Test
  void short_unfolded_conversations_are_reachable_without_a_memory() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    entries.append(conversation, 1, LoggedEntry.utterance("Why A?", Speaker.person(null)));
    entries.append(conversation, 1, LoggedEntry.answer("Because it handles retries", List.of()));
    new TurnStore(jdbc)
        .record(
            conversation,
            "Why A?",
            "Because it handles retries",
            Outcome.Ending.ANSWERED,
            20,
            "test",
            null);
    digester().pass(home, Budget.of(10), () -> false);
    String leaf = digests.roots(home).get(0).id();
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenReturn(leaf);
    var result = navigator().navigate(home, "retries", Budget.of(10), () -> false);
    assertEquals("log_entries", result.level());
    assertTrue(result.text().contains("handles retries"));
  }

  @Test
  void a_retired_lesson_uses_its_fold_archive_when_its_source_is_ejected() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    EntryRecord source =
        entries.append(
            conversation, 1, LoggedEntry.toolResult("historical-call", "Historical source fact"));
    entries.append(conversation, 1, LoggedEntry.summary("Archived historical fact"));
    String lesson = memory("Outdated claim");
    digests.provenance(lesson, conversation, List.of(1));
    archive.invalidate(lesson, "corrected", "test");
    assertTrue(
        entries.ejectPayload(
            conversation, source.ordinal(), java.time.Instant.now(), "/export/history"));
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenReturn("dig_m_" + lesson);
    var result = navigator().navigate(home, "history", Budget.of(10), () -> false);
    assertEquals("fold_summary", result.level());
    assertTrue(result.text().contains("Archived historical fact"));
    assertTrue(result.text().contains("invalidated"));
    assertFalse(result.ids().contains("dig_m_" + lesson));
    assertEquals(0, archive.get(lesson).uses());
  }

  @Test
  void retention_captures_a_completed_short_turn_before_ejection() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    EntryRecord source =
        entries.append(
            conversation, 1, LoggedEntry.utterance("A short source", Speaker.person(null)));
    new TurnStore(jdbc)
        .record(
            conversation, "A short source", "answer", Outcome.Ending.ANSWERED, 20, "test", null);
    assertTrue(digests.roots(home).isEmpty());
    assertTrue(digests.covered(conversation));
    String leaf = digests.roots(home).get(0).id();
    // Current retention only ejects tool results; utterances remain readable.
    assertFalse(
        entries.ejectPayload(
            conversation, source.ordinal(), java.time.Instant.now(), "/export/short"));
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenReturn(leaf);
    var result = navigator().navigate(home, "short", Budget.of(10), () -> false);
    assertEquals("log_entries", result.level());
    assertTrue(result.text().contains("A short source"));
    assertFalse(result.text().contains("fold summary"));
  }

  @Test
  void a_budget_exhausted_mid_descent_returns_the_deepest_surviving_digest() {
    memory("A");
    memory("B");
    digester().pass(home, Budget.of(10), () -> false);
    String root = digests.roots(home).get(0).id();
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              Budget b = call.getArgument(4);
              assertTrue(b.trySpend());
              return root;
            });
    var answer = navigator().navigate(home, "A?", Budget.of(1), () -> false);
    assertEquals("digest", answer.level());
    assertFalse(answer.complete());
    assertEquals(List.of(root), answer.ids());
    assertEquals(1, answer.modelCalls());
  }

  @Test
  void cancellation_after_a_model_response_does_not_read_a_memory() {
    String memory = memory("A");
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              cancelled.set(true);
              return "dig_m_" + memory;
            });
    assertFalse(navigator().navigate(home, "A?", Budget.of(10), cancelled::get).complete());
    assertEquals(0, archive.get(memory).uses());
  }

  @Test
  void rejected_project_branch_can_fall_back_to_global_without_leaking_other_projects() {
    memory("Local");
    String global =
        archive
            .applyVerdict(
                new MemoryProposal("Global", "everywhere", "global body", "test", ""),
                new Verdict(VerdictKind.NEW, null, "new"),
                Home.global())
            .memoryId();
    when(operation.call(eq("memory_navigator"), anyString(), anyString(), any(), any()))
        .thenReturn("NONE", "dig_m_" + global);
    assertEquals(
        List.of(global), navigator().navigate(home, "global?", Budget.of(10), () -> false).ids());
  }

  @Test
  void a_concurrent_memory_change_cannot_clear_a_new_stale_mark() {
    String memory = memory("A");
    archive.invalidate(memory, "old", "test");
    var old = digests.get(home, "dig_m_" + memory);
    jdbc.update("UPDATE memories SET body='changed again' WHERE id=?", memory);
    assertFalse(digests.refresh(home, old, "outdated summary", null));
    assertNotNull(digests.get(home, old.id()).staleAt());
  }

  @Test
  void a_missing_fold_digest_is_reported_before_retention_can_eject_it() {
    String conversation = conversations.open(home, Budget.of(10)).id();
    entries.append(conversation, 1, LoggedEntry.toolResult("call", "keep these bytes"));
    entries.append(conversation, 1, LoggedEntry.summary("summary"));
    // Simulate an incomplete legacy archive, not a production deletion path.
    jdbc.update("DELETE FROM digest_spans WHERE conversation_id=?", conversation);
    conversations.moveTo(conversation, ConversationLifecycle.TO_BE_EJECTED);
    Retention retention =
        new Retention(
                conversations,
                entries,
                mock(JobLog.class),
                mock(PayloadExport.class),
                new RetentionPolicy(null, null, null),
                java.time.Instant::now)
            .protectingDigests(digests::covered);
    assertThrows(ArchiveRefusedException.class, retention::sweep);
    assertEquals("keep these bytes", entries.forConversation(conversation).get(0).content());
  }

  @Test
  void a_memory_operation_owns_a_budget_and_uses_the_curator_retention_age() {
    var conversation =
        conversations.log(Origin.MEMORY, home, "memory_navigator", null, Budget.of(17));
    assertEquals(17, conversation.budget().limit());
    assertEquals(
        java.time.Duration.ofDays(1),
        new RetentionPolicy(java.time.Duration.ofDays(1), java.time.Duration.ofDays(7), null)
            .ejectAfter(Origin.MEMORY));
  }

  @Test
  void a_cancelled_pass_does_not_spend_model_calls() {
    memory("A");
    memory("B");
    assertTrue(digester().pass(home, Budget.of(10), () -> true).startsWith("Cancelled"));
    verify(operation, never()).call(anyString(), anyString(), anyString(), any(), any());
  }
}
