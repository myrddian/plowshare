package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.api.EntryPageView;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.files.FileReadWindow;
import io.aeyer.plowshare.server.pool.ScopedSqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Questions, answers and matching, over V50. Spec 2026-09-15, asking a person, §3. */
@Tag("full-db")
@Testcontainers
class RunApprovalStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final List<String> TEST = List.of("./gradlew", "test", "--tests", "Foo");

  private static JdbcTemplate jdbc;
  private static JdbcTemplate receiptJdbc;
  private static ScopedSqlWorkBudget receiptBudget;
  private static final Duration RECEIPT_DEADLINE = Duration.ofSeconds(2);

  @TempDir Path tmp;

  private RunApprovalStore store;
  private long project;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    receiptBudget =
        new ScopedSqlWorkBudget(
            new SqlWorkBudgetProperties(RECEIPT_DEADLINE, Duration.ofMillis(500), 128, 8, 64, 4),
            null);
    receiptJdbc = new JdbcTemplate(receiptBudget.decorate(source));
  }

  @AfterAll
  static void closeReceiptBudget() {
    if (receiptBudget != null) receiptBudget.close();
  }

  private static SuspendedRunCalls retainedCalls() {
    return new JdbcSuspendedRunCalls(receiptJdbc, receiptBudget, RECEIPT_DEADLINE);
  }

  @Test
  void an_approved_empty_file_result_retains_its_window_across_recovery() {
    String conversation = new ConversationStore(jdbc).open(Home.of("payments"), Budget.of(20)).id();
    var entries = new EntryStore(jdbc);
    var tool = new ToolCall("read-file", "file_read", "{\"path\":\"/fixture/empty.txt\"}");
    int original = entries.append(conversation, 1, LoggedEntry.answer("", List.of(tool))).ordinal();
    var approval = asked(conversation, "local", TEST);
    String id = approval.id();
    var calls = retainedCalls();
    calls.retainAt(id, conversation, "session", "local", "owner", tool, original);
    assertTrue(store.allow(id, RunApproval.ONCE, null, "reviewer"));
    calls.release(id);
    assertTrue(calls.claim(id));
    var window = new FileReadWindow(0, 0, 0, 0, 300, 300, "end");
    calls.completeRead(id, "", window);

    var restarted = retainedCalls();
    var saved = restarted.find(id).orElseThrow();
    assertEquals("", saved.result());
    assertEquals(window, saved.fileWindow());
    assertTrue(
        new RunCallResumption(restarted)
            .resumeWithReceipt(
                saved,
                ignored -> {
                  throw new AssertionError("The retained read must not execute again");
                },
                result ->
                    entries.appendRetainedResult(
                        conversation,
                        2,
                        LoggedEntry.toolResult(tool.id(), result.text())
                            .windowed(result.fileWindow()),
                        null,
                        id)));
    var row = entries.pageOfLog(conversation, 0, 10).listed().getLast();
    assertEquals("", row.excerpt());
    assertEquals(window, row.fileWindow());
    assertEquals(window, entries.forConversation(conversation).getLast().fileWindow());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> entries.append(conversation, 2, LoggedEntry.toolResult("ordinary", "")));
  }

  @Test
  void a_file_effect_receipt_survives_restart_without_changing_the_tool_result() {
    String conversation = new ConversationStore(jdbc).open(Home.of("payments"), Budget.of(20)).id();
    var entries = new EntryStore(jdbc);
    var approval = asked(conversation, "local", TEST);
    String id = approval.id();
    var calls = new JdbcSuspendedRunCalls(receiptJdbc, receiptBudget, RECEIPT_DEADLINE);
    var tool = new ToolCall("call_file", "file_delete", "{\"path\":\"/fixture/demo.txt\"}");
    int earlier = entries.append(conversation, 1, LoggedEntry.answer("", List.of(tool))).ordinal();
    int original = entries.append(conversation, 2, LoggedEntry.answer("", List.of(tool))).ordinal();
    calls.retainAt(id, conversation, "session", "local", "owner", tool, original);
    assertTrue(store.allow(id, RunApproval.ONCE, null, "reviewer"));
    calls.release(id);
    assertTrue(calls.claim(id));

    var sent = new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_SENT, "request-original");
    calls.recordEffect(id, sent);
    assertEquals(sent, retainedCalls().find(id).orElseThrow().effectReceipt());
    var foreign =
        new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_EFFECT_CONFIRMED, "other-request");
    assertThrows(IllegalStateException.class, () -> calls.recordEffect(id, foreign));
    var timeout =
        new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_REPLY_TIMEOUT, sent.providerRequestId());
    calls.recordEffect(id, timeout);
    assertThrows(IllegalStateException.class, () -> calls.recordEffect(id, sent));
    String body = RunCallResumption.FILE_REPLY_TIMEOUT_RESULT;
    calls.complete(id, body);

    var recovered = retainedCalls();
    var call = recovered.find(id).orElseThrow();
    assertEquals(body, call.result());
    assertEquals(timeout, call.effectReceipt());
    assertFalse(recovered.claim(id));
    assertTrue(
        new RunCallResumption(recovered)
            .resumeWithReceipt(
                call,
                ignored -> {
                  throw new AssertionError("An unknown effect must never be replayed");
                },
                result -> {
                  assertEquals(body, result.text());
                  assertEquals(timeout, result.effectReceipt());
                  entries.appendRetainedResult(
                      conversation, 3, LoggedEntry.toolResult(tool.id(), body), null, id);
                }));
    assertTrue(recovered.pending(conversation).isEmpty());
    entries.appendRetainedResult(
        conversation, 4, LoggedEntry.toolResult(tool.id(), "replayed body"), null, id);
    var log = entries.forConversation(conversation);
    assertEquals(3, log.size());
    assertEquals(body, log.getLast().content());
    var receipts = calls.forPage(conversation, List.of(earlier, original), List.of());
    assertEquals(
        List.of(
            new TrajectoryToolReceipts.Recorded(
                id, original, tool.id(), log.getLast().ordinal(), timeout)),
        receipts);
    var page = EntryPageView.of(entries.pageOfLog(conversation, 0, 10), 0, 10, receipts);
    assertEquals(null, page.entries().getFirst().toolCalls().getFirst().effectReceipt());
    assertEquals(id, page.entries().get(1).toolCalls().getFirst().effectReceipt().invocationId());
    assertEquals(body, page.entries().getLast().excerpt());
    var incremental =
        EntryPageView.trajectory(
                entries.pageOfLogAfter(conversation, original, EntryStore.EVERY_KIND, false, 0, 10),
                0,
                10,
                conversation,
                calls)
            .entries()
            .getFirst();
    assertEquals(original, incremental.retainedToolResult().answerOrdinal());
    assertEquals(body, incremental.excerpt());
    var traces = new JdbcToolCallTraces(receiptJdbc, receiptBudget, RECEIPT_DEADLINE);
    var trace = traces.read(conversation, id).orElseThrow();
    assertEquals("request-original", trace.requestId());
    assertFalse(trace.replyTimedOutAt().isBefore(trace.sentAt()));
    assertEquals(null, trace.replyObservedAt());
    String other = new ConversationStore(jdbc).open(Home.of("payments"), Budget.of(20)).id();
    assertTrue(traces.read(other, id).isEmpty());
    assertTrue(traces.read(conversation, "missing").isEmpty());
  }

  @Test
  void a_direct_invocation_keeps_its_exact_result_receipt_and_trace_across_recovery() {
    String conversation = new ConversationStore(jdbc).open(Home.of("payments"), Budget.of(20)).id();
    var entries = new EntryStore(jdbc);
    var tool = new ToolCall("same-id", "file_edit", "{\"path\":\"/fixture/note.txt\"}");
    int original = entries.append(conversation, 1, LoggedEntry.answer("", List.of(tool))).ordinal();
    var store = new JdbcSuspendedRunCalls(receiptJdbc, receiptBudget, RECEIPT_DEADLINE);
    var invocation = store.claimDirect(conversation, original, tool, "owner");
    assertThrows(
        IllegalStateException.class,
        () -> store.claimDirect(conversation, original, tool, "owner"));
    assertTrue(store.pending(conversation).isEmpty());
    assertTrue(store.pendingDirect(conversation, 0).isEmpty());
    assertTrue(
        store.pendingDirect(conversation, 2).isEmpty(),
        "A different turn cannot settle an unclosed call");
    assertEquals(
        ToolEffectReceipt.Dispatch.MAY_HAVE_DISPATCHED,
        store.pendingDirect(conversation, 1).getFirst().receipt().dispatch());
    store.recordEffect(
        invocation, new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_SENT, "original"));
    var unknown = new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_REPLY_TIMEOUT, "original");
    store.recordEffect(invocation, unknown);
    assertThrows(
        IllegalStateException.class,
        () ->
            store.recordEffect(
                invocation,
                new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_EFFECT_VERIFIED, "original")));

    var restarted = new JdbcSuspendedRunCalls(receiptJdbc, receiptBudget, RECEIPT_DEADLINE);
    var pending = restarted.pendingDirect(conversation, 1).getFirst();
    assertEquals(tool, pending.tool());
    assertEquals(unknown, pending.receipt());
    RunCallResumption.reconcileDirect(
        restarted,
        pending,
        result ->
            entries.appendRetainedResult(
                conversation,
                2,
                LoggedEntry.toolResult(tool.id(), result.text()),
                null,
                invocation));
    assertTrue(restarted.pendingDirect(conversation, 1).isEmpty());
    assertThrows(
        IllegalStateException.class,
        () -> restarted.claimDirect(conversation, original, tool, "owner"));
    var page =
        EntryPageView.trajectory(
            entries.pageOfLog(conversation, 0, 10), 0, 10, conversation, restarted);
    assertEquals(
        invocation,
        page.entries().getFirst().toolCalls().getFirst().effectReceipt().invocationId());
    assertEquals(RunCallResumption.FILE_REPLY_TIMEOUT_RESULT, page.entries().getLast().excerpt());
    var trace =
        new JdbcToolCallTraces(receiptJdbc, receiptBudget, RECEIPT_DEADLINE)
            .read(conversation, invocation)
            .orElseThrow();
    assertEquals("original", trace.requestId());
    assertEquals(null, trace.replyObservedAt());
    assertFalse(trace.replyTimedOutAt().isBefore(trace.sentAt()));
  }

  @BeforeEach
  void fresh() throws IOException {
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE TABLE run_approvals, digests, digest_children, digest_memories, digest_spans,"
            + " digest_revisions, memory_provenance, projects CASCADE");
    Path real = tmp.toRealPath();
    Path repo = Files.createDirectory(real.resolve("repo"));
    Path server = Files.createDirectory(real.resolve("srv"));
    ProjectStore projects =
        new ProjectStore(
            jdbc,
            Files.writeString(server.resolve("plowshare.yml"), "x"),
            Files.createDirectory(server.resolve("profiles")),
            server.resolve("console-token"),
            server.resolve("exports"),
            server.resolve("data"));
    projects.define("payments", repo, List.of());
    project = projects.id("payments");
    store = new RunApprovalStore(jdbc, () -> Instant.parse("2026-09-15T09:00:00Z"));
  }

  private RunApproval asked(String conversation, String side, List<String> argv) {
    return store.ask(project, conversation, conversation, "coder", side, argv, "/repo", null);
  }

  @Test
  void web_consent_binds_exact_operation_once_and_is_not_command_permission() {
    String conversation = new ConversationStore(jdbc).open(Home.of("payments"), Budget.of(20)).id();
    var request =
        new WebApproval(Verb.SEARCH, "public legal sources", 1, 10, 30, null, "a".repeat(64));
    var different =
        new WebApproval(Verb.SEARCH, "different sources", 1, 10, 30, null, "b".repeat(64));
    RunApproval approved =
        store.askWeb(project, conversation, conversation, "alice", "coder", request);
    var calls = retainedCalls();
    calls.retain(
        approved.id(),
        conversation,
        "session",
        "server",
        "alice",
        new ToolCall("reviewed", "web_search", "retained arguments"));
    assertEquals(request, store.find(approved.id()).orElseThrow().web());
    assertEquals("web_search", calls.find(approved.id()).orElseThrow().tool().name());
    assertFalse(store.allow(approved.id(), RunApproval.CONVERSATION, null, "alice"));
    assertTrue(store.allow(approved.id(), RunApproval.ONCE, null, "alice"));
    assertTrue(store.consume(project, conversation, "server", List.of(), "").isEmpty());
    assertFalse(store.consumeWeb(approved.id(), different, Instant.EPOCH));
    assertFalse(store.consumeWeb(approved.id(), request, Instant.parse("2026-09-15T09:00:01Z")));
    assertTrue(store.consumeWeb(approved.id(), request, Instant.EPOCH));
    assertFalse(store.consumeWeb(approved.id(), request, Instant.EPOCH));
  }

  @Test
  void history_uses_only_the_owners_exact_human_answer_and_expires() {
    jdbc.update(
        "INSERT INTO admins(handle, password_hash, must_change_password) VALUES ('history-owner', 'fixture-hash', false) ON CONFLICT (handle) DO NOTHING");
    String conversation = new ConversationStore(jdbc).open(Home.of("payments"), Budget.of(20)).id();
    RunApproval asked = asked(conversation, "local", TEST);
    SuspendedRunCalls calls = retainedCalls();
    calls.retain(
        asked.id(),
        conversation,
        "session",
        "local",
        "history-owner",
        new ToolCall("history-call", "run", "retained arguments"));
    ApprovalIntent intent =
        new ApprovalIntent(
            "history-owner",
            conversation,
            "history-call",
            "payments",
            "remote:session",
            ApprovalPolicy.Kind.COMMAND,
            TEST,
            List.of(),
            "/repo",
            null,
            "Inspect state",
            null);
    Instant now = Instant.parse("2026-09-16T00:00:00Z");
    HumanApprovalHistory history =
        new JdbcHumanApprovalHistory(jdbc, () -> now, Duration.ofDays(30));
    assertFalse(history.eligible(intent));
    history.remember(intent, asked.id());
    assertFalse(history.eligible(intent));
    assertTrue(store.allow(asked.id(), RunApproval.ONCE, null, "history-owner"));
    history.remember(intent, asked.id());
    assertTrue(history.eligible(intent));
    ApprovalIntent anotherProvider =
        new ApprovalIntent(
            intent.owner(),
            intent.conversation(),
            intent.call(),
            intent.project(),
            "remote:another-session",
            intent.kind(),
            intent.argv(),
            intent.paths(),
            intent.cwd(),
            null,
            intent.userIntent(),
            null);
    assertFalse(history.eligible(anotherProvider));
    HumanApprovalHistory expired =
        new JdbcHumanApprovalHistory(
            jdbc, () -> now.plus(Duration.ofDays(90)), Duration.ofDays(30));
    assertFalse(expired.eligible(intent));
  }

  @Test
  void typed_file_consent_is_once_only_and_retains_the_original_tool() {
    String conversation = new ConversationStore(jdbc).open(Home.of("payments"), Budget.of(20)).id();
    FileApproval file =
        new FileApproval(
            ApprovalPolicy.Kind.FILE_DELETE,
            "remote:session",
            List.of("/repo/config"),
            "a".repeat(64),
            "delete config");
    RunApproval asked =
        store.askFile(
            project,
            conversation,
            conversation,
            null,
            "coder",
            "local",
            "/repo",
            "SECURITY asks about deletion",
            file);
    SuspendedRunCalls calls = retainedCalls();
    calls.retain(
        asked.id(),
        conversation,
        "session",
        "local",
        "alice",
        new ToolCall("file-call", "file_delete", "exact retained arguments"));
    assertEquals(file, store.find(asked.id()).orElseThrow().file());
    assertFalse(store.allow(asked.id(), RunApproval.CONVERSATION, null, "alice"));
    assertTrue(store.allow(asked.id(), RunApproval.ONCE, null, "alice"));
    assertEquals("file_delete", calls.find(asked.id()).orElseThrow().tool().name());
    assertTrue(store.consume(project, conversation, "local", List.of(), "/repo").isEmpty());
    calls.release(asked.id());
    assertTrue(calls.claim(asked.id()));
    assertFalse(calls.claim(asked.id()));
    assertTrue(store.consumeFile(asked.id(), file));
    assertFalse(store.consumeFile(asked.id(), file));
  }

  @Test
  void review_inspection_does_not_spend_once_consent_and_only_one_claim_wins() {
    RunApproval question = asked("review-once", "server", TEST);
    assertTrue(store.allow(question.id(), RunApproval.ONCE, null, "alice"));
    RunApproval inspected =
        store.inspect(project, "review-once", "server", TEST, "/repo", null).orElseThrow();
    assertEquals(RunApproval.ALLOWED, store.find(question.id()).orElseThrow().state());
    assertTrue(store.claim(inspected));
    assertFalse(store.claim(inspected));
    assertEquals(RunApproval.USED, store.find(question.id()).orElseThrow().state());
  }

  @Test
  void standing_consent_revoked_during_review_cannot_be_claimed() {
    RunApproval question = asked("review-project", "server", TEST);
    assertTrue(store.allow(question.id(), RunApproval.PROJECT, List.of("./gradlew"), "alice"));
    RunApproval inspected =
        store.inspect(project, "another-chat", "server", TEST, "/repo", null).orElseThrow();
    assertTrue(store.revoke(question.id()));
    assertFalse(store.claim(inspected));
  }

  @Test
  void suspended_calls_survive_recovery_and_only_one_dispatch_can_claim_consent() throws Exception {
    String conversation =
        new io.aeyer.plowshare.server.archive.ConversationStore(jdbc)
            .open(
                io.aeyer.plowshare.protocol.Home.of("payments"),
                io.aeyer.plowshare.server.agents.Budget.of(20))
            .id();
    RunApproval question = asked(conversation, "local", TEST);
    SuspendedRunCalls retained = retainedCalls();
    String arguments =
        "{\"command\":[\"program\",\"one argument\",\"\"],\"stdin\":\"line\\n\",\"timeout_seconds\":17}";
    retained.retain(
        question.id(),
        conversation,
        "original-session",
        "local",
        "original-owner",
        new io.aeyer.plowshare.protocol.ToolCall("call_original", "run", arguments));
    assertFalse(retained.claim(question.id()));
    assertEquals(arguments, retained.pending(conversation).getFirst().tool().arguments());
    assertTrue(store.allow(question.id(), RunApproval.ONCE, null, "reviewer"));
    assertEquals(
        List.of(question.id()),
        retained.recoverable().stream().map(SuspendedRunCalls.Call::approval).toList());
    assertFalse(retained.claim(question.id()), "approval.post must release the call first");
    retained.release(question.id());
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      List<java.util.concurrent.Future<Boolean>> claims = new ArrayList<>();
      for (int i = 0; i < 8; i++) claims.add(workers.submit(() -> retained.claim(question.id())));
      int won = 0;
      for (var claim : claims) if (claim.get()) won++;
      assertEquals(1, won);
    }
    SuspendedRunCalls recovered = retainedCalls();
    var pending = recovered.pending(conversation).getFirst();
    assertEquals("original-session", pending.session());
    assertEquals("original-owner", pending.owner());
    assertEquals("started", pending.state());
    new RunCallResumption(recovered)
        .resume(
            pending,
            call -> {
              throw new AssertionError("An uncertain command must never replay");
            },
            result -> assertTrue(result.contains("outcome is unknown")));
    assertTrue(recovered.pending(conversation).isEmpty());
    assertTrue(recovered.recoverable().isEmpty());
    assertTrue(recovered.find(question.id()).orElseThrow().result().contains("outcome is unknown"));

    RunApproval denied = asked(conversation, "local", TEST);
    recovered.retain(
        denied.id(),
        conversation,
        "original-session",
        "local",
        new io.aeyer.plowshare.protocol.ToolCall("call_denied", "run", arguments));
    assertTrue(store.deny(denied.id(), "reviewer", "Publishing is not needed"));
    recovered.release(denied.id());
    new RunCallResumption(recovered)
        .resume(
            recovered.find(denied.id()).orElseThrow(),
            call -> {
              throw new AssertionError("A denial must never execute");
            },
            result -> {
              assertTrue(result.contains("reviewer"));
              assertTrue(result.contains("Publishing is not needed"));
            });
    assertTrue(recovered.pending(conversation).isEmpty());
  }

  @Test
  void a_question_is_open_until_it_is_answered_and_is_answered_at_most_once() {
    RunApproval question = asked("cnv_1", "local", TEST);

    assertEquals(
        List.of(question.id()), store.open("cnv_1").stream().map(RunApproval::id).toList());
    assertTrue(store.deny(question.id(), "enzo"));
    assertFalse(
        store.allow(question.id(), RunApproval.ONCE, null, "enzo"),
        "an answered question takes no second answer");
    assertTrue(store.open("cnv_1").isEmpty());
    assertEquals(RunApproval.DENIED, store.find(question.id()).orElseThrow().state());
  }

  @Test
  void an_account_s_open_questions_are_listed_wherever_they_were_raised_and_no_one_else_s() {
    RunApproval underAConductor =
        store.ask(
            project,
            "cnv_conductor",
            "cnv_coder",
            "enzo",
            "code_implementation",
            "local",
            TEST,
            "/repo",
            null);
    RunApproval inAnEvent =
        store.ask(
            project, "cnv_event", "cnv_event", "enzo", "coder", "server", TEST, "/repo", null);
    store.ask(project, "cnv_hers", "cnv_hers", "mara", "coder", "local", TEST, "/repo", null);
    RunApproval answered =
        store.ask(project, "cnv_done", "cnv_done", "enzo", "coder", "local", TEST, "/repo", null);
    store.deny(answered.id(), "enzo");

    assertEquals(
        java.util.Set.of(underAConductor.id(), inAnEvent.id()),
        store.openFor("enzo").stream()
            .map(RunApproval::id)
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void an_unattended_question_pins_its_account_until_delivery() {
    RunApproval question =
        store.ask(
            project, "cnv_event", "cnv_child", "enzo", "coder", "server", TEST, "/repo", null);

    assertEquals("enzo", question.handle());
    assertEquals(
        List.of(question.id()),
        store.undelivered("cnv_event").stream().map(RunApproval::id).toList());
    store.delivered(question.id());
    assertTrue(store.undelivered("cnv_event").isEmpty());
    assertTrue(store.find(question.id()).orElseThrow().deliveredAt() != null);
  }

  @Test
  void account_discovery_recovers_owned_foreground_questions_without_exposing_other_owners() {
    for (String owner : List.of("enzo", "mara")) {
      jdbc.update(
          "INSERT INTO conversations (id, project_id, origin, lifecycle, created_at,"
              + " budget_total, budget_spent, owner_handle) VALUES (?, ?, 'turn', 'active', ?, 20, 0, ?)",
          "cnv_" + owner,
          project,
          java.sql.Timestamp.from(Instant.now()),
          owner);
    }
    RunApproval foreground = asked("cnv_enzo", "local", TEST);
    RunApproval foreign = asked("cnv_mara", "local", TEST);
    // A pinned recipient takes precedence over the conversation's owner.
    RunApproval pinned =
        store.ask(project, "cnv_enzo", "cnv_enzo", "mara", "coder", "local", TEST, "/repo", null);
    RunApproval ownerless = asked("cnv_no_owner", "local", TEST);

    assertEquals(
        List.of(foreground.id()), store.openFor("enzo").stream().map(RunApproval::id).toList());
    assertEquals(
        java.util.Set.of(foreign.id(), pinned.id()),
        store.openFor("mara").stream()
            .map(RunApproval::id)
            .collect(java.util.stream.Collectors.toSet()));
    store.delivered(foreground.id());
    assertEquals(
        foreground.id(),
        store.openFor("enzo").getFirst().id(),
        "notification delivery does not settle the decision");
    store.deny(foreground.id(), "enzo");
    assertTrue(store.openFor("enzo").isEmpty());
    assertTrue(store.find(ownerless.id()).isPresent());
  }

  /**
   * A command given input is approved with that input: the question shows it, and only a call given
   * the same input is let through — the input to a program that reads its code from stdin is the
   * code.
   */
  @Test
  void an_approval_asked_with_input_covers_only_that_input() {
    List<String> reads = List.of("./gradlew", "-");
    RunApproval once =
        store.ask(
            project,
            "cnv_1",
            "cnv_1",
            "coder",
            "local",
            reads,
            "/repo",
            RunApproval.withInput(null, "first\n"));
    store.allow(once.id(), RunApproval.ONCE, null, "enzo");
    RunApproval always =
        store.ask(
            project,
            "cnv_2",
            "cnv_2",
            "coder",
            "local",
            reads,
            "/repo",
            RunApproval.withInput("'allowlist': reads", "second"));
    store.allow(always.id(), RunApproval.CONVERSATION, null, "enzo");

    assertTrue(store.consume(project, "cnv_1", "local", reads, "/repo", "other\n").isEmpty());
    assertTrue(
        store.consume(project, "cnv_1", "local", reads, "/repo").isEmpty(),
        "no input is other input");
    assertTrue(store.consume(project, "cnv_1", "local", reads, "/repo", "first\n").isPresent());
    assertEquals(RunApproval.USED, store.find(once.id()).orElseThrow().state());

    assertTrue(store.consume(project, "cnv_2", "local", reads, "/repo", "second").isPresent());
    assertTrue(store.consume(project, "cnv_2", "local", reads, "/repo", "second").isPresent());
    assertTrue(store.consume(project, "cnv_2", "local", reads, "/repo", "second ").isEmpty());
  }

  @Test
  void an_approval_asked_with_no_input_covers_no_input_only() {
    store.allow(asked("cnv_1", "local", TEST).id(), RunApproval.CONVERSATION, null, "enzo");

    assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo", null).isPresent());
    assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo", "y\n").isEmpty());
  }

  @Test
  void a_project_approval_covers_its_prefix_whatever_the_input() {
    store.allow(asked("cnv_1", "local", TEST).id(), RunApproval.PROJECT, TEST, "enzo");

    assertTrue(store.consume(project, "cnv_9", "local", TEST, "/repo", "anything").isPresent());
  }

  @Test
  void the_input_shown_is_cut_and_carries_its_length_and_digest() {
    String shown = RunApproval.input("a\n".repeat(100));

    assertTrue(shown.startsWith("given input: `a\\na\\n"), shown);
    assertTrue(shown.contains("…` (200 bytes, sha256 "), shown);
    assertTrue(shown.length() < 140, shown);
    assertEquals(null, RunApproval.input(null));
    assertEquals("why — " + RunApproval.input("x"), RunApproval.withInput("why", "x"));
    assertEquals(RunApproval.input("x"), RunApproval.withInput(null, "x"));
    assertEquals("why", RunApproval.withInput("why", null));
  }

  @Test
  void a_once_approval_lets_the_call_run_one_time() {
    RunApproval question = asked("cnv_1", "local", TEST);
    store.allow(question.id(), RunApproval.ONCE, null, "enzo");

    assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo").isPresent());
    assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo").isEmpty());
    assertEquals(RunApproval.USED, store.find(question.id()).orElseThrow().state());
  }

  @Test
  void a_conversation_approval_covers_the_exact_call_in_that_conversation_only() {
    store.allow(asked("cnv_1", "local", TEST).id(), RunApproval.CONVERSATION, null, "enzo");

    assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo").isPresent());
    assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo").isPresent());
    assertTrue(store.consume(project, "cnv_2", "local", TEST, "/repo").isEmpty());
    assertTrue(store.consume(project, "cnv_1", "local", TEST, "/elsewhere").isEmpty());
    assertTrue(
        store
            .consume(project, "cnv_1", "local", List.of("./gradlew", "publish"), "/repo")
            .isEmpty());
  }

  @Test
  void a_project_approval_covers_its_prefix_anywhere_on_its_side_and_can_be_revoked() {
    RunApproval question = asked("cnv_1", "local", TEST);
    store.allow(question.id(), RunApproval.PROJECT, List.of("./gradlew", "test"), "enzo");

    assertTrue(
        store
            .consume(project, "cnv_9", "local", List.of("./gradlew", "test"), "/other")
            .isPresent());
    assertTrue(
        store.consume(project, "cnv_9", "server", List.of("./gradlew", "test"), "/repo").isEmpty(),
        "an approval for a laptop never covers the server");
    assertTrue(
        store
            .consume(project, "cnv_9", "local", List.of("./gradlew", "testFixturesJar"), "/repo")
            .isEmpty(),
        "whole arguments, never substrings");
    assertEquals(1, store.standing(project).size());

    assertTrue(store.revoke(question.id()));
    assertTrue(
        store.consume(project, "cnv_9", "local", List.of("./gradlew", "test"), "/repo").isEmpty());
    assertTrue(store.standing(project).isEmpty());
    assertFalse(store.revoke(question.id()));
  }

  @Test
  void covers_compares_whole_leading_arguments() {
    assertTrue(RunApprovalStore.covers(List.of("git"), List.of("git", "status")));
    assertTrue(RunApprovalStore.covers(List.of("git", "status"), List.of("git", "status")));
    assertFalse(RunApprovalStore.covers(List.of(), List.of("git")));
    assertFalse(RunApprovalStore.covers(null, List.of("git")));
    assertFalse(RunApprovalStore.covers(List.of("git", "status", "-s"), List.of("git", "status")));
    assertFalse(RunApprovalStore.covers(List.of("gi"), List.of("git")));
  }

  @Test
  void the_default_prefix_is_the_program_and_its_first_argument() {
    assertEquals(List.of("./gradlew", "test"), asked("cnv_1", "local", TEST).defaultPrefix());
    assertEquals(List.of("ls"), asked("cnv_1", "local", List.of("ls")).defaultPrefix());
  }

  @Test
  void a_question_and_the_answer_that_won_are_each_told_once() {
    List<String> told = new ArrayList<>();
    store.useEvents(
        new ApprovalEvents() {
          @Override
          public void asked(RunApproval approval) {
            told.add("asked " + approval.state());
          }

          @Override
          public void answered(RunApproval approval) {
            told.add("answered " + approval.state() + " by " + approval.answeredBy());
          }
        });

    RunApproval question = asked("cnv_1", "local", TEST);
    assertTrue(store.deny(question.id(), "enzo"));
    assertFalse(store.allow(question.id(), RunApproval.ONCE, null, "enzo"));

    assertEquals(List.of("asked asked", "answered denied by enzo"), told);
  }

  /**
   * V68: every answer that settles a question — each decision, a denial, a withdrawal — is told by
   * id, so the person's inbox notice about it can leave; an answer that lost, and the judge's
   * allowing that nobody was asked, are not.
   */
  @Test
  void every_answer_that_settles_a_question_is_told_so_its_notice_can_leave() {
    List<String> settled = new ArrayList<>();
    store.whenSettled(settled::add);
    RunApproval once = asked("cnv_1", "local", TEST);
    RunApproval conversation = asked("cnv_1", "local", TEST);
    RunApproval standing = asked("cnv_1", "local", TEST);
    RunApproval denied = asked("cnv_1", "local", TEST);
    RunApproval superseded = asked("cnv_1", "local", TEST);
    RunApproval ended = asked("cnv_1", "local", TEST);

    assertTrue(store.allow(once.id(), RunApproval.ONCE, null, "enzo"));
    assertTrue(store.allow(conversation.id(), RunApproval.CONVERSATION, null, "enzo"));
    assertTrue(store.allow(standing.id(), RunApproval.PROJECT, List.of("./gradlew"), "enzo"));
    assertTrue(store.deny(denied.id(), "enzo"));
    assertTrue(store.deny(superseded.id(), RunApproval.SUPERSEDED));
    assertTrue(store.deny(ended.id(), RunApproval.RUN_ENDED));
    assertFalse(store.deny(once.id(), "enzo"), "already answered");
    store.allowedBy(
        project,
        "cnv_1",
        "cnv_1",
        "enzo",
        "coder",
        "local",
        TEST,
        null,
        "/repo",
        null,
        RunApproval.JUDGE,
        "a test run");

    assertEquals(
        List.of(
            once.id(), conversation.id(), standing.id(), denied.id(), superseded.id(), ended.id()),
        settled);
  }

  @Test
  void a_settle_that_throws_costs_the_answer_nothing() {
    store.whenSettled(
        id -> {
          throw new IllegalStateException("the inbox is on fire");
        });
    RunApproval question = asked("cnv_1", "local", TEST);

    assertTrue(store.deny(question.id(), "enzo"));
    assertEquals(RunApproval.DENIED, store.find(question.id()).orElseThrow().state());
  }

  @Test
  void a_listener_that_throws_costs_the_question_nothing() {
    store.useEvents(
        new ApprovalEvents() {
          @Override
          public void asked(RunApproval approval) {
            throw new IllegalStateException("the record is on fire");
          }
        });

    RunApproval question = asked("cnv_1", "local", TEST);

    assertEquals(RunApproval.ASKED, store.find(question.id()).orElseThrow().state());
  }

  /**
   * V67: one approval for a whole acceptance set. Its commands are kept in order; its own argv is
   * empty, so no run tool call ever matches — and so never spends — it, whatever it is answered
   * with; and one answer answers the set.
   */
  @Test
  void a_set_is_one_question_its_commands_kept_and_no_run_tool_call_spends_it() {
    List<List<String>> set = List.of(TEST, List.of("./gradlew", "run"));
    List<RunApproval> told = new ArrayList<>();
    store.useEvents(
        new ApprovalEvents() {
          @Override
          public void asked(RunApproval approval) {
            told.add(approval);
          }
        });

    RunApproval question =
        store.askSet(
            project,
            "cnv_conductor",
            "cnv_conductor",
            "enzo",
            "implement_specification",
            "local",
            set,
            "/repo",
            "the set's reason",
            "unsure");

    assertTrue(question.isSet());
    assertEquals(set, question.commands());
    assertEquals(set, question.covered());
    assertEquals(List.of(), question.argv());
    assertEquals("unsure", question.judged());
    assertEquals(RunApproval.ASKED, question.state());
    assertEquals(List.of(question.id()), told.stream().map(RunApproval::id).toList());
    assertTrue(store.allow(question.id(), RunApproval.ONCE, null, "enzo"));
    for (List<String> each : set) {
      assertTrue(store.consume(project, "cnv_conductor", "local", each, "/repo").isEmpty());
    }
    assertEquals(
        RunApproval.ALLOWED,
        store.find(question.id()).orElseThrow().state(),
        "still allowed: the set's every command runs under it");
  }

  /** V67: what the command judge allowed is written allowed, told as an answer, not a question. */
  @Test
  void the_judge_s_allowing_is_an_answer_nobody_was_asked() {
    List<RunApproval> asked = new ArrayList<>();
    List<RunApproval> answered = new ArrayList<>();
    store.useEvents(
        new ApprovalEvents() {
          @Override
          public void asked(RunApproval approval) {
            asked.add(approval);
          }

          @Override
          public void answered(RunApproval approval) {
            answered.add(approval);
          }
        });

    RunApproval check =
        store.allowedBy(
            project,
            "cnv_conductor",
            "cnv_conductor",
            "enzo",
            "code_implementation",
            "local",
            TEST,
            null,
            "/repo",
            "the check's reason",
            RunApproval.JUDGE,
            "runs the project's tests");
    RunApproval set =
        store.allowedBy(
            project,
            "cnv_conductor",
            "cnv_conductor",
            "enzo",
            "implement_specification",
            "local",
            List.of(),
            List.of(TEST),
            "/repo",
            "reason",
            RunApproval.JUDGE,
            "tests");

    assertEquals(List.of(), asked, "nobody was asked");
    assertEquals(List.of(check.id(), set.id()), answered.stream().map(RunApproval::id).toList());
    assertEquals(RunApproval.ALLOWED, check.state());
    assertEquals(RunApproval.JUDGE, check.answeredBy());
    assertEquals("runs the project's tests", check.judged());
    assertTrue(check.answeredAt() != null);
    assertEquals(TEST, check.argv());
    assertFalse(check.isSet());
    assertTrue(set.isSet());
    assertTrue(store.open("cnv_conductor").isEmpty(), "nothing waits on the person");
  }

  @Test
  void a_set_with_no_commands_is_refused() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            store.askSet(
                project, "cnv_1", "cnv_1", null, "a", "local", List.of(), "/repo", "r", null));
  }
}
