package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.approvals.ActiveRunApprovals;
import io.aeyer.plowshare.server.approvals.BudgetExtension;
import io.aeyer.plowshare.server.approvals.BudgetRequestAccess;
import io.aeyer.plowshare.server.approvals.BudgetRequestContinuations;
import io.aeyer.plowshare.server.approvals.BudgetRequestRepository;
import io.aeyer.plowshare.server.approvals.RetainedBudgetRequests;
import io.aeyer.plowshare.server.approvals.TestBudgetRequests;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.ToolPost;
import io.aeyer.plowshare.server.hooks.ToolPre;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Real runtime, delegation and budget waits over retained in-memory logs; no model or database. */
class BudgetRequestFlowTest {
  private static final Home HOME = Home.of("project");

  private static final class Log implements Transcript {
    private final String id;
    private final Origin origin;
    private final List<LoggedEntry> entries = new ArrayList<>();
    private final List<Log> children = new ArrayList<>();
    private final boolean resumed;
    private int resumedChildren;
    private Log retainedChild;
    private boolean failResults;
    private DelegationRetry failureRetry;
    private int failureResumptions;

    Log(String id, Origin origin) {
      this(id, origin, false);
    }

    Log(String id, Origin origin, boolean resumed) {
      this.id = id;
      this.origin = origin;
      this.resumed = resumed;
    }

    @Override
    public List<ChatMessage> before() {
      List<EntryRecord> saved = new ArrayList<>();
      for (LoggedEntry entry : entries)
        saved.add(
            new EntryRecord(
                id,
                saved.size() + 1,
                entry.kind(),
                entry.content(),
                entry.toolCallId(),
                entry.toolCalls(),
                null,
                null,
                1,
                Instant.EPOCH,
                null,
                null,
                null));
      return Projection.of(saved, false);
    }

    @Override
    public void promptMeasured(int tokens) {}

    @Override
    public String conversationId() {
      return id;
    }

    @Override
    public Origin origin() {
      return origin;
    }

    @Override
    public Spoken spokenIn() {
      return new Spoken(id, 1);
    }

    @Override
    public Integer continuingTurn() {
      return resumed ? 1 : null;
    }

    @Override
    public void record(LoggedEntry entry) {
      entries.add(entry);
    }

    @Override
    public void recordDurably(LoggedEntry entry) {
      if (failResults && entry.kind() == EntryKind.TOOL_RESULT)
        throw new IllegalStateException("Cannot retain tool result");
      entries.add(entry);
    }

    @Override
    public Transcript delegate(AgentDefinition agent, Home home, String openedBy) {
      Log child = new Log("cnv_child", Origin.DELEGATION);
      children.add(child);
      return child;
    }

    @Override
    public Optional<DelegationRetry> pendingDelegationRetry() {
      return Optional.ofNullable(failureRetry);
    }

    @Override
    public Transcript resumeDelegation(DelegationRetry retry, AgentDefinition callee) {
      assertEquals(failureRetry, retry);
      assertEquals("helper", callee.name());
      failureResumptions++;
      if (retainedChild == null) throw new IllegalStateException("No saved failed child");
      return retainedChild;
    }

    @Override
    public Transcript resumeDelegate(AgentDefinition agent, String conversation, int turn) {
      resumedChildren++;
      assertEquals("cnv_child", conversation);
      assertEquals(1, turn);
      if (retainedChild != null) return retainedChild;
      return new Log(conversation, Origin.DELEGATION, true);
    }
  }

  private static final class Scripted implements LlmTransport {
    private final List<Completion> completions;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<List<ChatMessage>> prompts = new ArrayList<>();

    Scripted(Completion... completions) {
      this.completions = List.of(completions);
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      prompts.add(List.copyOf(messages));
      int next = calls.getAndIncrement();
      if (next >= completions.size()) throw new AssertionError("Unexpected model call " + next);
      return completions.get(next);
    }

    @Override
    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      return complete(model, messages, sampling, tools);
    }

    @Override
    public Embeddings embed(String model, List<String> input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }

  private static Completion answer(String text) {
    return new Completion(text, "stop", TokenUsage.UNKNOWN, List.of());
  }

  private static Completion asks(ToolCall... calls) {
    return new Completion("", "tool_calls", TokenUsage.UNKNOWN, List.of(calls));
  }

  private static ToolCall delegation() {
    return new ToolCall(
        "call_child", "agent_run", "{\"agent\":\"helper\",\"task\":\"Read the evidence\"}");
  }

  private static ToolCall probe() {
    return new ToolCall("call_probe", "probe_read", "{}");
  }

  private static AgentRegistry agents() throws Exception {
    return AgentRegistry.of(
        Path.of(BudgetRequestFlowTest.class.getResource("/agents").toURI()),
        Set.of("probe_read", "probe_write", "agent_run", MemoryTools.WRITE_NAME));
  }

  private static JobRuntime runtime(
      Scripted transport, AgentRegistry agents, AtomicInteger effects) {
    LlmDispatcher models =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "scripted",
                    List.of("model-fast"),
                    Map.of("fast", "model-fast"),
                    1,
                    1,
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    AgentTool probe =
        new AgentTool() {
          @Override
          public ToolSchema schema() {
            return ToolSchema.from(
                "probe_read", "Read evidence", Map.of("type", "object", "properties", Map.of()));
          }

          @Override
          public String run(String arguments, Home home) {
            effects.incrementAndGet();
            return "verified evidence";
          }
        };
    return new JobRuntime(models, List.of(probe), () -> agents);
  }

  private static RetainedBudgetRequests service(
      TestBudgetRequests repository, CountDownLatch ready) {
    BudgetRequestAccess access = mock(BudgetRequestAccess.class);
    when(access.require(any(), any())).thenReturn(1L);
    return new RetainedBudgetRequests(
        repository,
        access,
        new ActiveRunApprovals(),
        root -> ready.countDown(),
        mock(BudgetRequestContinuations.class));
  }

  private static Outcome run(
      JobRuntime runtime, AgentRegistry agents, String agent, Budget budget, Log log) {
    return runtime.run(
        agents.get(agent),
        "The original task",
        HOME,
        budget,
        () -> false,
        null,
        JobWatch.UNWATCHED,
        log,
        TurnCap.of(4),
        List.of(),
        "owner");
  }

  /** Returns a retained pending decision without holding a thread, so no live model is needed. */
  private static BudgetRequests pendingConsent(
      AtomicReference<BudgetCheckpoint> saved, AtomicInteger questions) {
    return new BudgetRequests() {
      @Override
      public boolean available(Transcript transcript, String executor) {
        return transcript.conversationId() != null && "owner".equals(executor);
      }

      @Override
      public Optional<Decision> await(
          BudgetCheckpoint checkpoint,
          Budget budget,
          TurnCap cap,
          BudgetExtension extension,
          String reason,
          BooleanSupplier cancelled) {
        questions.incrementAndGet();
        saved.set(checkpoint);
        return Optional.of(new Decision("apr_budget", false, "Budget consent is pending", true));
      }
    };
  }

  @Test
  void mandatory_reviewer_awaiting_budget_parks_parent_without_another_call_or_draft_publication()
      throws Exception {
    AgentRegistry agents = agents();
    Scripted transport = new Scripted(answer("unreviewed draft"));
    JobRuntime runtime = runtime(transport, agents, new AtomicInteger());
    AtomicReference<BudgetCheckpoint> saved = new AtomicReference<>();
    AtomicInteger questions = new AtomicInteger();
    runtime.useBudgetRequests(pendingConsent(saved, questions));
    AgentDefinition plain = agents.get("boss");
    AgentDefinition reviewed =
        new AgentDefinition(
            plain.name(),
            plain.description(),
            plain.model(),
            plain.intent(),
            plain.sampling(),
            plain.tools(),
            plain.calls(),
            plain.scopes(),
            8,
            8,
            plain.prompt(),
            plain.exported(),
            plain.delegable(),
            plain.vision(),
            plain.bot(),
            plain.announcesInbox(),
            plain.fallback(),
            plain.orchestrations(),
            "helper");
    Log root = new Log("cnv_root", Origin.TURN);
    Outcome result =
        runtime.run(
            reviewed,
            "The original task",
            HOME,
            Budget.of(1),
            () -> false,
            null,
            JobWatch.UNWATCHED,
            root,
            TurnCap.of(8),
            List.of(),
            "owner");

    assertEquals(Outcome.Ending.AWAITING, result.ending());
    assertEquals(1, questions.get(), "the parent must not ask a second question or spin");
    assertEquals(1, transport.calls.get(), "only the original draft was generated");
    assertTrue(root.entries.stream().noneMatch(entry -> entry.kind() == EntryKind.ANSWER));
    assertEquals(
        List.of("cnv_root", "cnv_child"),
        saved.get().frames().stream().map(BudgetCheckpoint.Frame::conversation).toList());
  }

  @Test
  void restored_child_without_a_parent_call_slot_retains_diagnostic_and_refuses_new_inference()
      throws Exception {
    AgentRegistry agents = agents();
    Scripted transport = new Scripted();
    AtomicInteger effects = new AtomicInteger();
    JobRuntime runtime = runtime(transport, agents, effects);
    var parent = new BudgetCheckpoint.Frame("cnv_root", "boss", 1, 1, 4, List.of());
    var reviewer = new BudgetCheckpoint.Frame("cnv_child", "helper", 1, 0, 4, List.of());
    runtime.useBudgetRequests(
        new BudgetRequests() {
          @Override
          public Optional<BudgetCheckpoint.Frame> restoring(String conversation) {
            return "cnv_root".equals(conversation) ? Optional.of(parent) : Optional.empty();
          }

          @Override
          public Optional<BudgetCheckpoint.Frame> restoringChild(String conversation) {
            return "cnv_root".equals(conversation) ? Optional.of(reviewer) : Optional.empty();
          }

          @Override
          public Optional<String> restorationDecision(String conversation) {
            return Optional.of("Budget extension approved");
          }
        });
    Log root = new Log("cnv_root", Origin.TURN, true);
    root.entries.add(LoggedEntry.utterance("The original task", null));
    root.retainedChild = new Log("cnv_child", Origin.DELEGATION, true);
    root.retainedChild.entries.add(LoggedEntry.utterance("Saved review work", null));

    Outcome refused = run(runtime, agents, "boss", Budget.of(4), root);

    assertEquals(Outcome.Ending.UNAVAILABLE, refused.ending());
    assertEquals(0, refused.modelCalls());
    assertTrue(refused.detail().contains("no parent tool-call slot"));
    assertEquals(0, transport.calls.get());
    assertEquals(0, effects.get());
    assertEquals(0, root.resumedChildren);
    assertTrue(root.children.isEmpty());
    assertEquals(1, root.retainedChild.entries.size());
    assertTrue(
        root.entries.stream()
            .anyMatch(
                entry ->
                    entry.kind() == EntryKind.DIAGNOSTIC
                        && entry.content().contains("checkpoint and work are retained")));
  }

  @Test
  void failed_child_retry_keeps_original_parent_call_in_new_budget_checkpoint_and_stays_awaiting()
      throws Exception {
    AgentRegistry agents = agents();
    Scripted transport = new Scripted();
    JobRuntime runtime = runtime(transport, agents, new AtomicInteger());
    AtomicReference<BudgetCheckpoint> saved = new AtomicReference<>();
    AtomicInteger questions = new AtomicInteger();
    runtime.useBudgetRequests(pendingConsent(saved, questions));
    Log root = new Log("cnv_root", Origin.TURN, true);
    root.entries.add(LoggedEntry.utterance("The original task", null));
    root.entries.add(LoggedEntry.answer("", List.of(delegation())));
    root.retainedChild = new Log("cnv_child", Origin.DELEGATION, true);
    root.retainedChild.entries.add(LoggedEntry.utterance("Read the evidence", null));
    root.retainedChild.entries.add(LoggedEntry.answer("", List.of(probe())));
    root.retainedChild.entries.add(LoggedEntry.toolResult(probe().id(), "saved evidence"));
    root.failureRetry =
        new Transcript.DelegationRetry(
            delegation(), "cnv_child", "helper", HOME, 1, "Read the evidence", Optional.empty());
    Budget budget = Budget.of(1);
    budget.tryClaim().orElseThrow(); // The original failed call already spent this allowance.

    Outcome result = run(runtime, agents, "boss", budget, root);

    assertEquals(Outcome.Ending.AWAITING, result.ending());
    assertEquals(1, questions.get());
    assertEquals(0, transport.calls.get());
    assertEquals(1, root.failureResumptions);
    assertTrue(root.children.isEmpty(), "retry must not create another child");
    assertEquals(
        List.of("cnv_root", "cnv_child"),
        saved.get().frames().stream().map(BudgetCheckpoint.Frame::conversation).toList());
    assertEquals(List.of(delegation()), saved.get().frames().getFirst().pending());
    assertTrue(
        root.entries.stream().noneMatch(entry -> entry.kind() == EntryKind.TOOL_RESULT),
        "a pending child must not be recorded as a completed delegation");
  }

  private static BudgetRequests restoringDelegation() {
    var parent = new BudgetCheckpoint.Frame("cnv_root", "boss", 1, 1, 4, List.of(delegation()));
    var child = new BudgetCheckpoint.Frame("cnv_child", "helper", 1, 1, 4, List.of());
    return new BudgetRequests() {
      @Override
      public Optional<BudgetCheckpoint.Frame> restoring(String conversation) {
        return "cnv_root".equals(conversation)
            ? Optional.of(parent)
            : "cnv_child".equals(conversation) ? Optional.of(child) : Optional.empty();
      }

      @Override
      public Optional<BudgetCheckpoint.Frame> restoringChild(String conversation) {
        return "cnv_root".equals(conversation) ? Optional.of(child) : Optional.empty();
      }

      @Override
      public Optional<String> restorationDecision(String conversation) {
        return Optional.of("Budget extension approved");
      }
    };
  }

  private static Log retainedDelegationLog() {
    Log root = new Log("cnv_root", Origin.TURN, true);
    root.entries.add(LoggedEntry.utterance("The original task", null));
    root.entries.add(LoggedEntry.answer("", List.of(delegation())));
    root.retainedChild = new Log("cnv_child", Origin.DELEGATION, true);
    root.retainedChild.entries.add(LoggedEntry.utterance("Read the evidence", null));
    root.retainedChild.entries.add(LoggedEntry.answer("", List.of(probe())));
    root.retainedChild.entries.add(LoggedEntry.toolResult(probe().id(), "saved evidence"));
    return root;
  }

  @Test
  void budget_restarted_delegation_rechecks_current_hooks_and_does_not_publish_withheld_output()
      throws Exception {
    AgentRegistry agents = agents();
    for (String gate : List.of("deny", "rewrite", "withhold")) {
      Scripted transport = new Scripted(answer("private resumed child answer"));
      AtomicInteger effects = new AtomicInteger();
      JobRuntime runtime = runtime(transport, agents, effects);
      runtime.useBudgetRequests(restoringDelegation());
      runtime.useHooks(
          new Hooks() {
            @Override
            public ToolPre toolPre(HookContext context, String tool, String arguments) {
              if (gate.equals("deny"))
                return new ToolPre(arguments, "current gate denied", List.of());
              return ToolPre.allowed(gate.equals("rewrite") ? "{}" : arguments);
            }

            @Override
            public ToolPost toolPost(
                HookContext context, String tool, String arguments, String result) {
              throw new IllegalStateException("current output hook unavailable");
            }
          });
      Log root = retainedDelegationLog();
      Budget budget = Budget.of(4);
      budget.tryClaim().orElseThrow();
      budget.tryClaim().orElseThrow(); // Original parent dispatch and completed child read.
      Outcome refused = run(runtime, agents, "boss", budget, root);
      assertEquals(Outcome.Ending.UNAVAILABLE, refused.ending(), gate);
      assertEquals(
          gate.equals("withhold") ? 1 : 0,
          refused.modelCalls(),
          "a disclosed-result refusal must not erase already spent child calls");
      assertEquals(gate.equals("withhold") ? 1 : 0, transport.calls.get(), gate);
      assertEquals(0, effects.get(), "saved child tool effects must not run again");
      assertTrue(
          root.entries.stream().noneMatch(entry -> entry.kind() == EntryKind.TOOL_RESULT),
          "the restored result cannot reach a parent prompt after " + gate);
      assertTrue(
          root.entries.stream()
              .noneMatch(
                  entry ->
                      entry.kind() == EntryKind.ANSWER
                          && entry.content().contains("private resumed child answer")));
      if (gate.equals("withhold"))
        assertTrue(root.entries.stream().anyMatch(entry -> entry.kind() == EntryKind.HOOK));
    }
  }

  @Test
  void budget_restoration_counts_child_calls_and_runs_parent_once_after_authorized_result()
      throws Exception {
    AgentRegistry agents = agents();
    Scripted transport = new Scripted(answer("Recovered child answer"), answer("Final answer"));
    AtomicInteger effects = new AtomicInteger();
    JobRuntime runtime = runtime(transport, agents, effects);
    runtime.useBudgetRequests(restoringDelegation());
    Log root = retainedDelegationLog();
    Budget budget = Budget.of(4);
    budget.tryClaim().orElseThrow();
    budget.tryClaim().orElseThrow(); // Original parent dispatch and completed child read.

    Outcome result = run(runtime, agents, "boss", budget, root);

    assertEquals(Outcome.Ending.ANSWERED, result.ending());
    assertEquals(
        2, result.modelCalls(), "resumed child calls remain charged in the parent outcome");
    assertEquals(4, budget.spent());
    assertEquals(0, effects.get());
    assertEquals(1, root.resumedChildren);
    assertTrue(
        transport.prompts.get(1).stream()
            .anyMatch(
                message ->
                    message.role() == ChatMessage.Role.TOOL
                        && message.content().contains("Recovered child answer")));
    assertTrue(root.children.isEmpty());
  }

  @Test
  void exhausted_delegate_keeps_one_child_and_one_effect_and_resumes_its_waiting_parent()
      throws Exception {
    AgentRegistry agents = agents();
    Scripted transport =
        new Scripted(
            asks(delegation()), asks(probe()), answer("Child evidence"), answer("Final answer"));
    AtomicInteger effects = new AtomicInteger();
    JobRuntime runtime = runtime(transport, agents, effects);
    Budget budget = Budget.of(2);
    var identity = budget.identity();
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    CountDownLatch ready = new CountDownLatch(1);
    var requests = service(repository, ready);
    runtime.useBudgetRequests(requests);
    Log root = new Log("cnv_root", Origin.TURN);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var originalJob = workers.submit(() -> run(runtime, agents, "boss", budget, root));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      assertFalse(originalJob.isDone());
      assertEquals(2, transport.calls.get());
      assertEquals(1, effects.get());
      assertEquals(
          List.of("cnv_root", "cnv_child"),
          repository.find(repository.approval().id()).orElseThrow().checkpoint().frames().stream()
              .map(BudgetCheckpoint.Frame::conversation)
              .toList());
      requests.answer(repository.approval(), true, "reviewer");
      assertEquals(Outcome.Ending.ANSWERED, originalJob.get(10, TimeUnit.SECONDS).ending());
    }
    assertEquals(identity, budget.identity());
    assertEquals(4, budget.spent());
    assertEquals(1, repository.questions());
    assertEquals(1, root.children.size());
    assertEquals(1, effects.get());
    assertTrue(
        transport.prompts.get(2).stream()
            .anyMatch(message -> message.content().contains("verified evidence")));
    assertTrue(
        transport.prompts.get(3).stream()
            .anyMatch(message -> message.content().contains("Child evidence")));
  }

  @Test
  void message_agent_can_request_before_exhaustion_and_continue_the_same_runtime()
      throws Exception {
    AgentRegistry agents = agents();
    var ask =
        new ToolCall(
            "call_budget",
            "request_budget",
            "{\"additional_calls\":2,\"additional_steps\":1,\"reason\":\"Finish the original message\"}");
    Scripted transport = new Scripted(asks(ask), answer("Message finished"));
    AtomicInteger effects = new AtomicInteger();
    JobRuntime runtime = runtime(transport, agents, effects);
    Budget budget = Budget.of(1);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    CountDownLatch ready = new CountDownLatch(1);
    var requests = service(repository, ready);
    runtime.useBudgetRequests(requests);
    Log message = new Log("cnv_message", Origin.MESSAGE);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var original = workers.submit(() -> run(runtime, agents, "helper", budget, message));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      assertFalse(original.isDone());
      requests.answer(repository.approval(), true, "reviewer");
      assertEquals("Message finished", original.get(10, TimeUnit.SECONDS).text());
    }
    assertEquals(2, budget.spent());
    assertEquals(3, budget.limit());
    assertEquals(1, repository.questions());
    assertEquals(
        1, message.entries.stream().filter(entry -> entry.kind() == EntryKind.UTTERANCE).count());
    assertTrue(
        transport.prompts.get(1).stream()
            .anyMatch(prompt -> prompt.content().contains("Budget extension approved")));
  }

  @Test
  void approval_after_interruption_restores_the_original_delegation_and_completed_evidence()
      throws Exception {
    AgentRegistry agents = agents();
    Scripted before = new Scripted(asks(delegation()), asks(probe()));
    AtomicInteger effects = new AtomicInteger();
    JobRuntime firstRuntime = runtime(before, agents, effects);
    Budget budget = Budget.of(2);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    CountDownLatch ready = new CountDownLatch(1);
    var firstRequests = service(repository, ready);
    firstRuntime.useBudgetRequests(firstRequests);
    Log firstRoot = new Log("cnv_root", Origin.TURN);
    Thread interrupted =
        Thread.ofVirtual().start(() -> run(firstRuntime, agents, "boss", budget, firstRoot));
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    interrupted.interrupt();
    interrupted.join(10000);
    assertFalse(interrupted.isAlive());
    assertTrue(repository.awaiting("cnv_root"));

    Scripted after =
        new Scripted(answer("Recovered child evidence"), answer("Recovered final answer"));
    JobRuntime recoveredRuntime = runtime(after, agents, effects);
    Log recoveredRoot = new Log("cnv_root", Origin.TURN, true);
    recoveredRoot.entries.addAll(firstRoot.entries);
    Log recoveredChild = new Log("cnv_child", Origin.DELEGATION, true);
    recoveredChild.entries.addAll(firstRoot.children.getFirst().entries);
    recoveredRoot.retainedChild = recoveredChild;
    AtomicReference<Outcome> outcome = new AtomicReference<>();
    BudgetRequestContinuations owner =
        new BudgetRequestContinuations() {
          @Override
          public boolean busy(String root) {
            return false;
          }

          @Override
          public boolean resume(BudgetRequestRepository.Request request) {
            assertEquals("cnv_root", request.root());
            budget.refreshFrom(request.allowance());
            outcome.set(run(recoveredRuntime, agents, "boss", budget, recoveredRoot));
            return true;
          }

          @Override
          public void denied(BudgetRequestRepository.Request request) {
            throw new AssertionError("Denied");
          }

          @Override
          public void interrupted(BudgetRequestRepository.Request request, String text) {
            throw new AssertionError(text);
          }

          @Override
          public void problem(BudgetRequestRepository.Request request, String text) {
            throw new AssertionError(text);
          }
        };
    var recoveredRequests =
        new RetainedBudgetRequests(
            repository,
            mock(BudgetRequestAccess.class),
            new ActiveRunApprovals(),
            root -> {},
            owner);
    recoveredRuntime.useBudgetRequests(recoveredRequests);
    recoveredRequests.answer(repository.approval(), true, "reviewer");
    recoveredRequests.recover();
    assertEquals(Outcome.Ending.ANSWERED, outcome.get().ending());
    assertEquals("Recovered final answer", outcome.get().text());
    assertEquals(1, recoveredRoot.resumedChildren);
    assertEquals(0, recoveredRoot.children.size());
    assertEquals(1, effects.get(), "The completed probe must never replay");
    assertEquals(4, budget.spent());
    assertEquals(1, repository.questions());
    assertTrue(
        after.prompts.getFirst().stream()
            .anyMatch(prompt -> prompt.content().contains("verified evidence")));
    assertEquals(
        1,
        after.prompts.get(1).stream()
            .filter(prompt -> prompt.content().equals("The original task"))
            .count());
  }

  @Test
  void step_exhaustion_can_extend_only_steps_without_resetting_or_adding_calls() throws Exception {
    AgentRegistry agents = agents();
    Scripted transport = new Scripted(asks(probe()), answer("Finished after the cap"));
    JobRuntime runtime = runtime(transport, agents, new AtomicInteger());
    Budget budget = Budget.of(3);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    CountDownLatch ready = new CountDownLatch(1);
    var requests = service(repository, ready);
    runtime.useBudgetRequests(requests);
    Log root = new Log("cnv_message", Origin.MESSAGE);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var original =
          workers.submit(
              () ->
                  runtime.run(
                      agents.get("helper"),
                      "The original task",
                      HOME,
                      budget,
                      () -> false,
                      null,
                      JobWatch.UNWATCHED,
                      root,
                      TurnCap.of(1),
                      List.of(),
                      "owner"));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      assertEquals(0, repository.approval().budget().calls());
      assertEquals(3, repository.approval().budget().steps());
      requests.answer(repository.approval(), true, "reviewer");
      assertEquals(Outcome.Ending.ANSWERED, original.get(10, TimeUnit.SECONDS).ending());
    }
    assertEquals(3, budget.limit());
    assertEquals(2, budget.spent());
  }

  @Test
  void denial_after_exhaustion_stops_without_a_model_call_or_a_second_question() throws Exception {
    AgentRegistry agents = agents();
    Scripted transport = new Scripted(asks(probe()));
    AtomicInteger effects = new AtomicInteger();
    JobRuntime runtime = runtime(transport, agents, effects);
    Budget budget = Budget.of(1);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    CountDownLatch ready = new CountDownLatch(1);
    var requests = service(repository, ready);
    runtime.useBudgetRequests(requests);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var original =
          workers.submit(
              () -> run(runtime, agents, "helper", budget, new Log("cnv_message", Origin.MESSAGE)));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      requests.answer(repository.approval(), false, "reviewer");
      assertEquals(Outcome.Ending.CALL_BUDGET, original.get(10, TimeUnit.SECONDS).ending());
    }
    assertEquals(1, repository.questions());
    assertEquals(1, transport.calls.get());
    assertEquals(1, effects.get());
  }

  @Test
  void a_missing_durable_result_refuses_continuation_instead_of_losing_completed_work()
      throws Exception {
    AgentRegistry agents = agents();
    Scripted transport = new Scripted(asks(probe()));
    AtomicInteger effects = new AtomicInteger();
    JobRuntime runtime = runtime(transport, agents, effects);
    Budget budget = Budget.of(1);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    runtime.useBudgetRequests(service(repository, new CountDownLatch(1)));
    Log root = new Log("cnv_message", Origin.MESSAGE);
    root.failResults = true;
    assertThrows(IllegalStateException.class, () -> run(runtime, agents, "helper", budget, root));
    assertEquals(0, repository.questions());
    assertEquals(1, effects.get());
    assertEquals(1, transport.calls.get());
  }
}
