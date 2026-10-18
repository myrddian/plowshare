package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.approvals.ApprovalDelivery;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gate as the run loop applies it: a model asks for run, and whether the command starts is
 * decided by the environment and the hooks, with no profile and no project hook able to leave the
 * gate out.
 */
class RunGateWiringTest {

  @TempDir Path tmp;

  private Path repo;
  private Path environment;
  private final List<List<String>> ran = Collections.synchronizedList(new ArrayList<>());

  /** The cancel the last command was run under. */
  private volatile BooleanSupplier lastCancel;

  private String executedInput;
  private Duration executedTimeout;
  private Path executedCwd;

  private Function<String, Optional<ConversationRecord>> approvalRoots =
      ignored -> Optional.empty();

  @BeforeEach
  void fresh() throws IOException {
    approvalRoots = ignored -> Optional.empty();
    repo = Files.createDirectory(tmp.toRealPath().resolve("repo"));
    environment = tmp.toRealPath().resolve("environment.yml");
  }

  private final class Machine implements FileProvider {

    @Override
    public String name() {
      return "local";
    }

    @Override
    public List<Path> roots() {
      return List.of(repo);
    }

    @Override
    public CommandRunner.Outcome run(
        Path cwd,
        List<String> argv,
        EnvironmentFile.Side side,
        Duration timeout,
        BooleanSupplier cancelled) {
      executedTimeout = timeout;
      executedCwd = cwd;
      ran.add(argv);
      lastCancel = cancelled;
      return new CommandRunner.Outcome(0, false, false, "built", 0, "", 0, 5);
    }

    @Override
    public CommandRunner.Outcome run(
        Path cwd,
        List<String> argv,
        EnvironmentFile.Side side,
        Duration timeout,
        String stdin,
        BooleanSupplier cancelled) {
      executedInput = stdin;
      return run(cwd, argv, side, timeout, cancelled);
    }

    @Override
    public Span read(Path path, Window window) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Span stat(Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Path> glob(String pattern) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Found grep(Needle needle, Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed write(Path path, String content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed create(Path path, String content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed edit(Path path, String old, String replacement) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed delete(Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.aeyer.plowshare.server.files.Changed move(Path from, Path to) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class Scripted implements LlmTransport {
    final List<List<ChatMessage>> sent = Collections.synchronizedList(new ArrayList<>());
    private final String call;

    Scripted(String call) {
      this.call = call;
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
            "", "tool_calls", TokenUsage.UNKNOWN, List.of(new ToolCall("c1", RunTool.NAME, call)));
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

  /** A conversation, for a run a person is in. */
  private static final class InConversation implements Transcript {
    final List<LoggedEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final boolean failResult;

    InConversation() {
      this(false);
    }

    InConversation(boolean failResult) {
      this.failResult = failResult;
    }

    @Override
    public void record(LoggedEntry entry) {
      entries.add(entry);
    }

    @Override
    public void recordDurably(LoggedEntry entry) {
      if (failResult && entry.kind() == EntryKind.TOOL_RESULT)
        throw new IllegalStateException("Simulated result persistence failure");
      entries.add(entry);
    }

    @Override
    public List<ChatMessage> before() {
      return List.of();
    }

    @Override
    public String conversationId() {
      return "cnv_asked";
    }

    @Override
    public void promptMeasured(int promptTokens) {}
  }

  private RunApprovalStore approvals = mock(RunApprovalStore.class);
  private ApprovalDelivery delivery = mock(ApprovalDelivery.class);
  private Outcome last;
  private io.aeyer.plowshare.server.approvals.SuspendedRunCalls suspendedCalls;
  private List<List<ChatMessage>> lastSent;
  private List<LoggedEntry> lastEntries;
  private volatile Scripted activeModel;
  private boolean failRetainedResult;

  /** The run call the model makes. */
  private String runCall = "{\"command\": [\"./gradlew\", \"test\"]}";

  /** What the model was shown for its run call. */
  private String runAs(Hooks hooks) throws Exception {
    return runAs(hooks, null);
  }

  /**
   * What the model was shown for its run call, in a conversation a session started when one is
   * named.
   */
  private String runAs(Hooks hooks, String session) throws Exception {
    return runAs(hooks, session, null);
  }

  private String runAs(Hooks hooks, String session, String callerHandle) throws Exception {
    Scripted model = new Scripted(runCall);
    activeModel = model;
    JobRuntime runtime =
        new JobRuntime(
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
                new NoOpTokenLedger()),
            List.of(),
            null,
            (home, grants, sessionId, owner) -> List.of(new Machine()));
    runtime.useEnvironments(new Environments(name -> 7L, id -> environment, null));
    runtime.useApprovals(approvals);
    if (suspendedCalls != null) runtime.useSuspendedRunCalls(suspendedCalls);
    runtime.useApprovalRoots(approvalRoots);
    runtime.useApprovalDelivery(delivery);
    runtime.useHooks(hooks);
    Path agents = Files.createDirectories(tmp.resolve("agents-" + System.nanoTime()));
    Files.writeString(
        agents.resolve("coder.md"),
        """
                ---
                name: coder
                description: runs the build
                model: fast
                tools: [run]
                scopes: [workspace:write]
                max-turns: 4
                max-model-calls: 8
                ---
                You run the build.
                """);
    AgentDefinition coder = AgentRegistry.of(agents, runtime.knownTools()).get("coder");
    InConversation conversation = new InConversation(failRetainedResult);
    last =
        session == null && callerHandle == null
            ? runtime.run(
                coder, "run the tests", Home.of("payments"), Budget.of(20), () -> false, null)
            : runtime.run(
                coder,
                "run the tests",
                Home.of("payments"),
                Budget.of(20),
                () -> false,
                session,
                JobWatch.UNWATCHED,
                conversation,
                TurnCap.from(coder),
                List.of(),
                callerHandle);
    lastSent = List.copyOf(model.sent);
    lastEntries = List.copyOf(conversation.entries);
    if (model.sent.size() < 2) {
      // The turn ended after the batch, so the model never saw its result; the log did.
      return conversation.entries.stream()
          .filter(e -> e.kind() == EntryKind.TOOL_RESULT)
          .map(LoggedEntry::content)
          .reduce((a, b) -> b)
          .orElse("");
    }
    List<ChatMessage> second = model.sent.get(1);
    return second.get(second.size() - 1).content();
  }

  private static RunApproval question(String id) {
    return new RunApproval(
        id,
        7L,
        "cnv_asked",
        "cnv_asked",
        "coder",
        "server",
        List.of("./gradlew", "test"),
        "/repo",
        null,
        RunApproval.ASKED,
        null,
        null,
        null,
        null,
        Instant.now());
  }

  @Test
  void in_ask_mode_a_person_is_asked_and_the_turn_ends_waiting_for_them() throws Exception {
    Files.writeString(environment, "server:\n  mode: ask\n");
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(approvals.ask(
            eq(7L),
            eq("cnv_asked"),
            eq("cnv_asked"),
            eq("coder"),
            eq("server"),
            eq(List.of("./gradlew", "test")),
            any(),
            isNull()))
        .thenReturn(question("apr_1"));

    String shown = runAs(Hooks.NONE, "tab-1");

    assertTrue(ran.isEmpty(), "nothing runs while a person has not answered");
    assertTrue(shown.startsWith("Waiting for a person to approve this command"), shown);
    assertEquals(Outcome.Ending.AWAITING, last.ending());
    assertTrue(last.text().contains("Approve running ./gradlew test"), last.text());
    assertTrue(last.text().contains("[apr_1]"), last.text());
  }

  @Test
  void retained_calls_allow_equivalent_encoding_but_refuse_changed_execution_fields()
      throws Exception {
    Files.writeString(environment, "server:\n  mode: open\n");
    Environments configured = new Environments(name -> 7L, id -> environment, null);
    RunTool run =
        new RunTool(
            new io.aeyer.plowshare.server.files.ProviderRouter(home -> List.of(new Machine())),
            () -> configured,
            () -> false);
    Home home = Home.of("payments");
    HookContext context =
        new HookContext(
            "coder", false, java.util.Set.of("run"), "payments", "cnv_asked", HookContext.SERVER);
    String retained =
        run.retainedArguments(
            "{\"command\":[\"program\"],\"stdin\":\"same\",\"timeout_seconds\":17}", home);
    String formatted =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(retained).toPrettyString();
    ToolPre allowed =
        run.gateRetained(
            retained,
            "server",
            home,
            context,
            (at, arguments) -> new ToolPre(formatted, null, List.of()));
    assertFalse(allowed.isDenied());
    run.run(allowed.arguments(), home);
    assertEquals(1, ran.size());
    for (String changed :
        List.of(
            retained.replace("\"program\"", "\"other\""),
            retained.replace("\"same\"", "\"different\""),
            retained.replace(":17", ":18"))) {
      assertTrue(
          run.gateRetained(
                  retained,
                  "server",
                  home,
                  context,
                  (at, arguments) -> new ToolPre(changed, null, List.of()))
              .isDenied());
    }
    assertEquals(1, ran.size(), "Rechecking changed fields must not execute another command");
    Files.writeString(environment, "server:\n  mode: open\n  timeout: 5s\n");
    assertTrue(
        run.gateRetained(
                retained,
                "server",
                home,
                context,
                (at, arguments) -> new ToolPre(arguments, null, List.of()))
            .isDenied(),
        "A changed environment must refuse, not silently shorten the approved timeout");
    Files.writeString(environment, "server:\n  mode: open\n  timeout: 30s\n");
    ToolPre checked =
        run.gateRetained(
            retained,
            "server",
            home,
            context,
            (at, arguments) -> new ToolPre(arguments, null, List.of()));
    assertFalse(checked.isDenied());
    Files.writeString(environment, "server:\n  mode: open\n  timeout: 5s\n");
    assertTrue(run.run(checked.arguments(), home).contains("timeout"));
    assertEquals(1, ran.size(), "A limit change after the gate must still preserve the exact call");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
  void approval_resumes_the_original_call_without_another_model_request_for_run(int scenario)
      throws Exception {
    boolean denied = scenario == 1;
    failRetainedResult = scenario == 2;
    Files.writeString(environment, "server:\n  mode: ask\n");
    var retained =
        new java.util.concurrent.atomic.AtomicReference<
            io.aeyer.plowshare.server.approvals.SuspendedRunCalls.Call>();
    var consent = new java.util.concurrent.atomic.AtomicReference<>("asked");
    var entered = new java.util.concurrent.CountDownLatch(1);
    suspendedCalls = mock(io.aeyer.plowshare.server.approvals.SuspendedRunCalls.class);
    when(suspendedCalls.pending(any())).thenReturn(List.of());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              retained.set(
                  new io.aeyer.plowshare.server.approvals.SuspendedRunCalls.Call(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3),
                      invocation.getArgument(5),
                      "waiting",
                      "allowed",
                      "reviewer",
                      null,
                      null,
                      invocation.getArgument(4)));
              entered.countDown();
              return null;
            })
        .when(suspendedCalls)
        .retain(any(), any(), any(), any(), any(), any());
    when(suspendedCalls.find(any()))
        .thenAnswer(
            invocation -> {
              var call = retained.get();
              return call == null
                  ? Optional.empty()
                  : Optional.of(
                      new io.aeyer.plowshare.server.approvals.SuspendedRunCalls.Call(
                          call.approval(),
                          call.conversation(),
                          call.session(),
                          call.side(),
                          call.tool(),
                          call.state(),
                          consent.get(),
                          "reviewer",
                          denied ? "Publishing is unnecessary" : null,
                          null));
            });
    when(suspendedCalls.claim(any())).thenReturn(true);
    when(approvals.find("apr_1")).thenReturn(Optional.of(question("apr_1")));
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(question("apr_1")));
    when(approvals.ask(anyLong(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(question("apr_1"));
    runCall =
        "{\"command\":[\"python3\",\"connect.py\",\"Hello from Professor Farnsworth!\",\"\"],\"timeout_seconds\":17,\"stdin\":\"first line\\nquoted \\\"data\\\"\\n\"}";

    String result;
    try (var jobs = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var running = jobs.submit(() -> runAs(Hooks.NONE, "original-session"));
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(ran.isEmpty(), "A pending approval must not execute anything");
      assertEquals(1, activeModel.sent.size(), "No inference is needed while a person decides");
      consent.set(denied ? "denied" : "allowed");
      result = running.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    if (failRetainedResult) {
      assertEquals(Outcome.Ending.UNAVAILABLE, last.ending());
      assertEquals(
          1,
          last.modelCalls(),
          "A failed result append must not erase the original inference count");
      assertEquals(
          1, activeModel.sent.size(), "No model is asked to reconstruct a completed command");
      assertEquals(1, ran.size());
      verify(suspendedCalls).complete(org.mockito.ArgumentMatchers.eq("apr_1"), any());
      verify(suspendedCalls, never()).acknowledged(any());
      return;
    }
    if (denied) {
      assertTrue(result.contains("denied by reviewer"), result);
      assertTrue(result.contains("Publishing is unnecessary"), result);
      assertTrue(ran.isEmpty());
      verify(suspendedCalls, never()).claim("apr_1");
    } else {
      assertTrue(result.contains("built"), result);
      assertEquals(1, ran.size());
      assertEquals(
          List.of("python3", "connect.py", "Hello from Professor Farnsworth!", ""), ran.getFirst());
      assertEquals(Duration.ofSeconds(17), executedTimeout);
      assertEquals(repo, executedCwd);
      assertEquals("first line\nquoted \"data\"\n", executedInput);
      verify(suspendedCalls).claim("apr_1");
    }
    assertEquals(Outcome.Ending.ANSWERED, last.ending());
    assertEquals(
        2, last.modelCalls(), "One request for run, followed by one response to its real result");
    assertTrue(retained.get().tool().arguments().contains("\"timeout_seconds\":17"));
    assertEquals("original-session", retained.get().session());
    assertEquals(
        1,
        lastEntries.stream()
            .filter(e -> e.kind() == EntryKind.ANSWER && !e.toolCalls().isEmpty())
            .count());
    assertEquals(1, lastEntries.stream().filter(e -> e.kind() == EntryKind.TOOL_RESULT).count());
    assertTrue(
        lastSent.stream()
            .flatMap(List::stream)
            .noneMatch(
                m ->
                    m.content().contains("Run it again")
                        || m.content().contains("[approval]")
                        || m.content().contains("Waiting for a person")));
    verify(approvals, org.mockito.Mockito.times(1))
        .ask(anyLong(), any(), any(), any(), any(), any(), any(), any());
    verify(suspendedCalls).acknowledged("apr_1");
  }

  @Test
  void a_delegated_question_is_keyed_to_the_root_and_keeps_the_child_that_asked() throws Exception {
    Files.writeString(environment, "server:\n  mode: ask\n");
    ConversationRecord root = mock(ConversationRecord.class);
    when(root.id()).thenReturn("cnv_root");
    when(root.agent()).thenReturn("interlocutor");
    when(root.origin()).thenReturn(Origin.TURN);
    approvalRoots = ignored -> Optional.of(root);
    when(approvals.consume(anyLong(), eq("cnv_root"), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(approvals.ask(
            eq(7L),
            eq("cnv_root"),
            eq("cnv_asked"),
            eq("interlocutor"),
            eq("server"),
            eq(List.of("./gradlew", "test")),
            any(),
            isNull()))
        .thenReturn(
            new RunApproval(
                "apr_root",
                7L,
                "cnv_root",
                "cnv_asked",
                "interlocutor",
                "server",
                List.of("./gradlew", "test"),
                "/repo",
                null,
                RunApproval.ASKED,
                null,
                null,
                null,
                null,
                Instant.now()));

    runAs(Hooks.NONE, "tab-1");

    assertEquals(Outcome.Ending.AWAITING, last.ending());
    verify(approvals)
        .ask(
            eq(7L),
            eq("cnv_root"),
            eq("cnv_asked"),
            eq("interlocutor"),
            eq("server"),
            eq(List.of("./gradlew", "test")),
            any(),
            isNull());
  }

  @Test
  void in_ask_mode_a_run_no_person_started_is_denied_rather_than_left_waiting() throws Exception {
    Files.writeString(environment, "server:\n  mode: ask\n");
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    String shown = runAs(Hooks.NONE, null);

    assertTrue(ran.isEmpty());
    assertTrue(shown.contains("nobody can be asked"), shown);
    verify(approvals, never()).ask(anyLong(), any(), any(), any(), any(), any(), any(), any());
    assertEquals(Outcome.Ending.ANSWERED, last.ending());
  }

  @Test
  void an_event_started_run_asks_its_pinned_account() throws Exception {
    Files.writeString(environment, "server:\n  mode: ask\n");
    ConversationRecord root = mock(ConversationRecord.class);
    when(root.id()).thenReturn("cnv_asked");
    when(root.agent()).thenReturn("coder");
    when(root.origin()).thenReturn(Origin.EVENT);
    approvalRoots = ignored -> Optional.of(root);
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    RunApproval question =
        new RunApproval(
            "apr_event",
            7L,
            "cnv_asked",
            "cnv_asked",
            "enzo",
            "coder",
            "server",
            List.of("./gradlew", "test"),
            "/repo",
            null,
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            null,
            Instant.now());
    when(approvals.ask(
            eq(7L),
            eq("cnv_asked"),
            eq("cnv_asked"),
            eq("enzo"),
            eq("coder"),
            eq("server"),
            eq(List.of("./gradlew", "test")),
            any(),
            isNull()))
        .thenReturn(question);

    runAs(Hooks.NONE, null, "enzo");

    assertEquals(Outcome.Ending.AWAITING, last.ending());
    verify(delivery, never()).drain(any());
  }

  @Test
  void a_person_asked_about_a_command_with_stdin_is_shown_its_input() throws Exception {
    Files.writeString(environment, "server:\n  mode: ask\n");
    runCall = "{\"command\": [\"./gradlew\", \"test\"], \"stdin\": \"yes\\nand more\\n\"}";
    String input = RunApproval.input("yes\nand more\n");
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(approvals.ask(
            eq(7L),
            eq("cnv_asked"),
            eq("cnv_asked"),
            eq("coder"),
            eq("server"),
            eq(List.of("./gradlew", "test")),
            any(),
            eq(input)))
        .thenReturn(
            new RunApproval(
                "apr_in",
                7L,
                "cnv_asked",
                "cnv_asked",
                "coder",
                "server",
                List.of("./gradlew", "test"),
                "/repo",
                input,
                RunApproval.ASKED,
                null,
                null,
                null,
                null,
                Instant.now()));

    runAs(Hooks.NONE, "tab-1");

    assertTrue(ran.isEmpty());
    assertEquals(Outcome.Ending.AWAITING, last.ending());
    assertTrue(input.startsWith("given input: `yes\\nand more\\n` (13 bytes"), input);
    assertTrue(last.text().contains("given input: `yes\\nand more\\n`"), last.text());
    verify(approvals)
        .consume(
            anyLong(),
            eq("cnv_asked"),
            eq("server"),
            eq(List.of("./gradlew", "test")),
            any(),
            eq("yes\nand more\n"));
  }

  @Test
  void a_hook_reason_and_the_input_are_both_shown() throws Exception {
    Files.writeString(environment, "server:\n  mode: open\n");
    runCall = "{\"command\": [\"./gradlew\", \"test\"], \"stdin\": \"y\"}";
    String shown = "'allowlist': gradle publishes — " + RunApproval.input("y");
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(approvals.ask(anyLong(), any(), any(), any(), any(), any(), any(), eq(shown)))
        .thenReturn(question("apr_hooked"));

    runAs(
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
            return new ToolPre(
                argumentsJson, null, List.of(), false, "'allowlist': gradle publishes");
          }
        },
        "tab-1");

    assertEquals(Outcome.Ending.AWAITING, last.ending());
    verify(approvals).ask(anyLong(), any(), any(), any(), any(), any(), any(), eq(shown));
  }

  @Test
  void a_hook_that_asks_sends_the_call_to_a_person_even_when_open() throws Exception {
    Files.writeString(environment, "server:\n  mode: open\n");
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(approvals.ask(
            anyLong(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq("'allowlist': gradle publishes")))
        .thenReturn(question("apr_2"));

    runAs(
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
            return new ToolPre(
                argumentsJson, null, List.of(), false, "'allowlist': gradle publishes");
          }
        },
        "tab-1");

    assertTrue(ran.isEmpty());
    assertEquals(Outcome.Ending.AWAITING, last.ending());
  }

  @Test
  void an_approval_a_person_gave_lets_a_gated_call_run_without_asking_again() throws Exception {
    Files.writeString(environment, "server:\n  mode: gated\n");
    RunApproval allowed =
        new RunApproval(
            "apr_3",
            7L,
            "cnv_asked",
            "cnv_asked",
            "coder",
            "server",
            List.of("./gradlew", "test"),
            "/repo",
            null,
            RunApproval.ALLOWED,
            RunApproval.CONVERSATION,
            null,
            "enzo",
            Instant.now(),
            Instant.now());
    when(approvals.consume(
            anyLong(),
            eq("cnv_asked"),
            eq("server"),
            eq(List.of("./gradlew", "test")),
            any(),
            isNull()))
        .thenReturn(Optional.of(allowed));

    runAs(Hooks.NONE, "tab-1");

    assertEquals(1, ran.size());
  }

  @Test
  void a_hook_that_denies_wins_over_an_approval() throws Exception {
    Files.writeString(environment, "server:\n  mode: open\n");

    String shown =
        runAs(
            new Hooks() {
              @Override
              public ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
                return new ToolPre(argumentsJson, "'allowlist': never", List.of());
              }
            },
            "tab-1");

    assertTrue(ran.isEmpty());
    assertTrue(shown.contains("never"), shown);
    verify(approvals, never()).consume(anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void with_no_environment_the_command_never_starts_and_the_model_is_told_why() throws Exception {
    String shown = runAs(Hooks.NONE);

    assertTrue(ran.isEmpty());
    assertTrue(shown.contains("mode off"), shown);
  }

  @Test
  void an_open_side_starts_the_command() throws Exception {
    Files.writeString(environment, "server:\n  mode: open\n");

    String shown = runAs(Hooks.NONE);

    assertEquals(List.of(List.of("./gradlew", "test")), ran);
    assertTrue(shown.contains("built"), shown);
  }

  @Test
  void gated_starts_the_command_only_when_a_hook_explicitly_allows_it() throws Exception {
    Files.writeString(environment, "server:\n  mode: gated\n");

    String silent = runAs(Hooks.NONE);
    assertTrue(ran.isEmpty());
    assertTrue(silent.contains("allow: true"), silent);

    runAs(
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
            assertEquals("gated", context.environment().mode());
            return new ToolPre(argumentsJson, null, List.of(), true);
          }
        });
    assertEquals(1, ran.size());
  }

  // --- the port a run is handed: the harness's check goes the same way ----------------------

  /** The port the run loop hands a run's extras, captured from a run under {@code hooks}. */
  private Commands.Port portOf(Hooks hooks, BooleanSupplier cancelled) throws Exception {
    Scripted model = new Scripted(runCall);
    JobRuntime runtime =
        new JobRuntime(
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
                new NoOpTokenLedger()),
            List.of(),
            null,
            (home, grants, sessionId, owner) -> List.of(new Machine()));
    runtime.useEnvironments(new Environments(name -> 7L, id -> environment, null));
    runtime.useHooks(hooks);
    java.util.concurrent.atomic.AtomicReference<Commands.Port> port =
        new java.util.concurrent.atomic.AtomicReference<>();
    runtime.useRunExtras(
        context -> {
          port.set(context.commands());
          return RunExtras.Extras.NONE;
        });
    Path agents = Files.createDirectories(tmp.resolve("agents-" + System.nanoTime()));
    Files.writeString(
        agents.resolve("conductor.md"),
        """
                ---
                name: conductor
                description: conducts
                model: fast
                tools: []
                scopes: [workspace:write]
                max-turns: 4
                max-model-calls: 8
                ---
                You conduct.
                """);
    AgentDefinition conductor = AgentRegistry.of(agents, runtime.knownTools()).get("conductor");
    runtime.run(
        conductor,
        "conduct",
        Home.of("payments"),
        Budget.of(20),
        cancelled,
        null,
        JobWatch.UNWATCHED,
        new InConversation());
    return port.get();
  }

  /**
   * Final review F1, at the seam: the port a run's extras are handed judges a command with the same
   * tool_pre chain the loop hands run's gate — the project's hooks, asked for the run tool, with
   * the side's environment in the context — so a hook that denies a run call denies the check, even
   * on an open side.
   */
  @Test
  void the_port_a_run_is_handed_judges_a_command_with_the_run_tool_s_own_hooks() throws Exception {
    Files.writeString(environment, "server:\n  mode: open\n");
    List<String> asked = new ArrayList<>();
    Commands.Port port =
        portOf(
            new Hooks() {
              @Override
              public ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
                asked.add(tool + " " + context.environment() + " " + argumentsJson);
                return new ToolPre(argumentsJson, "no gradle here", List.of());
              }
            },
            () -> false);

    Commands.Placed placed = port.place(Home.of("payments"), null, List.of("./gradlew", "test"));
    Commands.Verdict verdict = port.judge(placed);

    assertEquals("no gradle here", verdict.denied());
    assertEquals(
        List.of(
            "run "
                + new HookContext.RunEnvironment("server", "open", false, "none")
                + " {\"command\":[\"./gradlew\",\"test\"],\"cwd\":\""
                + repo
                + "\"}"),
        asked);
  }

  /** Final review F4, at the seam: the port runs a command under the job's own cancel. */
  @Test
  void the_port_a_run_is_handed_runs_a_command_under_the_job_s_cancel() throws Exception {
    Files.writeString(environment, "server:\n  mode: open\n");
    java.util.concurrent.atomic.AtomicBoolean cancel =
        new java.util.concurrent.atomic.AtomicBoolean();
    Commands.Port port = portOf(Hooks.NONE, cancel::get);

    port.run(port.place(Home.of("payments"), null, List.of("./gradlew", "test")));

    assertEquals(false, lastCancel.getAsBoolean());
    cancel.set(true);
    assertEquals(true, lastCancel.getAsBoolean(), "the job's cancel reaches the command");
  }

  // --- slices 2–3 of spec 2026-09-28-hooks-reach-the-log: what a run's gates record ---------

  /** A model that calls one named tool with no arguments, then answers. */
  private static final class CallsOnce implements LlmTransport {
    private final String tool;
    final List<List<ChatMessage>> sent = Collections.synchronizedList(new ArrayList<>());

    CallsOnce(String tool) {
      this.tool = tool;
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      sent.add(List.copyOf(messages));
      return sent.size() == 1
          ? new Completion(
              "", "tool_calls", TokenUsage.UNKNOWN, List.of(new ToolCall("c1", tool, "{}")))
          : new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
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

  private JobRuntime runtimeOver(LlmTransport model) {
    JobRuntime runtime =
        new JobRuntime(
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
                new NoOpTokenLedger()),
            List.of(),
            null,
            (home, grants, sessionId, owner) -> List.of(new Machine()));
    runtime.useEnvironments(new Environments(name -> 7L, id -> environment, null));
    runtime.useApprovals(approvals);
    runtime.useApprovalRoots(approvalRoots);
    runtime.useApprovalDelivery(delivery);
    return runtime;
  }

  private AgentDefinition coderFor(JobRuntime runtime) throws Exception {
    Path agents = Files.createDirectories(tmp.resolve("agents-" + System.nanoTime()));
    Files.writeString(
        agents.resolve("coder.md"),
        """
                ---
                name: coder
                description: runs the build
                model: fast
                tools: [run]
                scopes: [workspace:write]
                max-turns: 4
                max-model-calls: 8
                ---
                You run the build.
                """);
    return AgentRegistry.of(agents, runtime.knownTools()).get("coder");
  }

  /** A provider's tool that judges one command through the run's own port, as a check does. */
  private static AgentTool judging(RunExtras.Context context) {
    return new AgentTool() {
      @Override
      public ToolSchema schema() {
        return ToolSchema.from(
            "judge_probe", "judges a command", Map.of("type", "object", "properties", Map.of()));
      }

      @Override
      public String run(String argumentsJson, Home home) {
        Commands.Verdict verdict =
            context
                .commands()
                .judge(context.commands().place(home, null, List.of("./gradlew", "test")));
        return verdict.denied() == null ? "allowed" : verdict.denied();
      }
    };
  }

  /** Spec §5.2: the run tool's tool.pre verdict on a check's command was thrown away. */
  @Test
  void a_command_the_port_judges_has_its_verdict_written_after_the_call_that_judged_it()
      throws Exception {
    Files.writeString(environment, "server:\n  mode: open\n");
    JobRuntime runtime = runtimeOver(new CallsOnce("judge_probe"));
    List<RunExtras.Context> handed = new ArrayList<>();
    runtime.useRunExtras(
        context -> {
          handed.add(context);
          return new RunExtras.Extras(List.of(judging(context)), null, false);
        });
    runtime.useHooks(
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
            return new ToolPre(
                argumentsJson,
                null,
                List.of(
                    new HookRecord(
                        "allowlist",
                        "a.js",
                        Tier.PROJECT,
                        Stage.TOOL_PRE,
                        tool,
                        HookRecord.ALLOW,
                        null,
                        null,
                        null,
                        1)),
                true);
          }
        });
    AgentDefinition coder = coderFor(runtime);
    InConversation conversation = new InConversation();

    runtime.run(
        coder,
        "judge it",
        Home.of("payments"),
        Budget.of(20),
        () -> false,
        "tab-1",
        JobWatch.UNWATCHED,
        conversation,
        TurnCap.from(coder),
        List.of(),
        null);

    List<LoggedEntry> entries = List.copyOf(conversation.entries);
    int result = -1;
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).kind() == EntryKind.TOOL_RESULT
          && "allowed".equals(entries.get(i).content())) {
        result = i;
      }
    }
    assertTrue(result >= 0, "the probe ran: " + entries);
    java.util.function.Predicate<LoggedEntry> verdict =
        entry ->
            entry.kind() == EntryKind.HOOK
                && entry.content().contains("\"hook\":\"allowlist\"")
                && entry.content().contains("\"tool\":\"run\"");
    List<String> after =
        entries.subList(result + 1, entries.size()).stream()
            .filter(verdict)
            .map(LoggedEntry::content)
            .toList();
    // Exactly one, in the whole log: a record drained twice would still pass an anyMatch.
    assertEquals(
        1, entries.stream().filter(verdict).count(), "the verdict is written once: " + entries);
    assertEquals(
        1,
        after.size(),
        "the run tool's verdict on the judged command is a HOOK entry after the call: " + after);
    assertInstanceOf(
        InTurnHooks.class,
        handed.get(0).hooks(),
        "a provider is handed the run's own in-turn gates");
  }

  // --- slice 3 of spec 2026-09-28-hooks-reach-the-log §3: approval.pre before a person is asked

  /** Spec 2026-09-28-hooks-reach-the-log §3: approval.pre refuses without anyone being asked. */
  @Test
  void an_approval_pre_denial_refuses_the_command_and_asks_nobody() throws Exception {
    Files.writeString(environment, "server:\n  mode: ask\n");
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    String shown =
        runAs(
            new Hooks() {
              @Override
              public Gate approvalPre(HookContext context, Approving approving) {
                return new Gate(
                    "'prod-guard': not against production",
                    List.of(),
                    List.of(
                        new HookRecord(
                            "prod-guard",
                            "g.js",
                            Tier.PROJECT,
                            Stage.APPROVAL_PRE,
                            null,
                            HookRecord.DENY,
                            "not against production",
                            null,
                            null,
                            1)));
              }
            },
            "tab-1");

    assertTrue(ran.isEmpty());
    assertTrue(
        shown.contains("the call was refused by a hook: 'prod-guard': not against" + " production"),
        shown);
    verify(approvals, never()).ask(anyLong(), any(), any(), any(), any(), any(), any(), any());
    verify(approvals, never())
        .ask(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    assertEquals(Outcome.Ending.ANSWERED, last.ending(), "nobody was asked, so nothing waits");
  }

  @Test
  void an_approval_pre_note_is_part_of_the_question_the_person_sees() throws Exception {
    Files.writeString(environment, "server:\n  mode: ask\n");
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(approvals.ask(anyLong(), any(), any(), any(), any(), any(), any(), anyString()))
        .thenReturn(question("apr_n"));
    List<HookContext> contexts = new ArrayList<>();
    List<Approving> shownTo = new ArrayList<>();

    runAs(
        new Hooks() {
          @Override
          public Gate approvalPre(HookContext context, Approving approving) {
            contexts.add(context);
            shownTo.add(approving);
            return new Gate(null, List.of("the build only reads the repo"), List.of());
          }
        },
        "tab-1");

    verify(approvals)
        .ask(
            eq(7L),
            eq("cnv_asked"),
            eq("cnv_asked"),
            eq("coder"),
            eq("server"),
            eq(List.of("./gradlew", "test")),
            any(),
            eq("the build only reads the repo"));
    assertEquals("ask", contexts.get(0).environment().mode());
    assertEquals("server", contexts.get(0).environment().side());
    assertEquals(List.of("./gradlew", "test"), shownTo.get(0).argv());
    assertEquals(null, shownTo.get(0).reason(), "mode ask gives the question no reason of its own");
    assertTrue(shownTo.get(0).attended());
    assertEquals(RunApproval.SCOPES, shownTo.get(0).scopes());
    assertEquals(Outcome.Ending.AWAITING, last.ending());
  }

  @Test
  void approval_pre_is_not_asked_when_nobody_could_be() throws Exception {
    Files.writeString(environment, "server:\n  mode: ask\n");
    when(approvals.consume(anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    List<Approving> shownTo = new ArrayList<>();

    String shown =
        runAs(
            new Hooks() {
              @Override
              public Gate approvalPre(HookContext context, Approving approving) {
                shownTo.add(approving);
                return Gate.NOTHING;
              }
            },
            null);

    assertTrue(shown.contains("nobody can be asked"), shown);
    assertEquals(List.of(), shownTo);
  }
}
