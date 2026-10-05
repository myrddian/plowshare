package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.hooks.Addition;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.Mode;
import io.aeyer.plowshare.server.hooks.PromptPost;
import io.aeyer.plowshare.server.hooks.PromptPre;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Step;
import io.aeyer.plowshare.server.hooks.StepPost;
import io.aeyer.plowshare.server.hooks.Tier;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The five stages, driven through a real run loop with a scripted model. */
class HookedRunTest {

  // --- the smallest scripted model the run loop accepts ---------------------------

  private static final class Scripted implements LlmTransport {
    private final List<Supplier<Completion>> steps = new ArrayList<>();
    final List<List<ChatMessage>> sent = Collections.synchronizedList(new ArrayList<>());

    Scripted then(Supplier<Completion> step) {
      steps.add(step);
      return this;
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      int at = sent.size();
      sent.add(List.copyOf(messages));
      return at < steps.size() ? steps.get(at).get() : answer("done");
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

  private static final class Recorded implements Transcript {
    final List<LoggedEntry> entries = new ArrayList<>();

    String opening = "";

    @Override
    public String opening() {
      return opening;
    }

    @Override
    public List<ChatMessage> before() {
      return List.of();
    }

    @Override
    public String conversationId() {
      return "cnv_hooked";
    }

    @Override
    public void promptMeasured(int promptTokens) {}

    @Override
    public void record(LoggedEntry entry) {
      entries.add(entry);
    }

    List<EntryKind> kinds() {
      return entries.stream().map(LoggedEntry::kind).toList();
    }
  }

  private static class Probe implements AgentTool {
    final List<String> seen = Collections.synchronizedList(new ArrayList<>());
    private final String name;
    private final String answer;

    Probe() {
      this("probe_write");
    }

    Probe(String name) {
      this(name, "wrote it, and the secret is hunter2");
    }

    Probe(String name, String answer) {
      this.name = name;
      this.answer = answer;
    }

    @Override
    public ToolSchema schema() {
      return ToolSchema.from(name, "a probe", Map.of("type", "object", "properties", Map.of()));
    }

    @Override
    public String run(String argumentsJson, Home home) {
      seen.add(argumentsJson);
      return answer;
    }
  }

  private static Completion answer(String content) {
    return new Completion(content, "stop", TokenUsage.UNKNOWN, List.of());
  }

  private static Completion asking(ToolCall call) {
    return new Completion("", "tool_calls", TokenUsage.UNKNOWN, List.of(call));
  }

  private static JobRuntime runtime(Scripted model, AgentTool... tools) {
    LlmDispatcher dispatcher =
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
    return new JobRuntime(dispatcher, List.of(tools));
  }

  private static AgentDefinition agent(String name) throws Exception {
    Path fixtures = Path.of(HookedRunTest.class.getResource("/agents").toURI());
    return AgentRegistry.of(
            fixtures,
            Set.of("probe_read", "probe_write", AgentRegistry.AGENT_RUN, MemoryTools.WRITE_NAME))
        .get(name);
  }

  private static Outcome run(JobRuntime runtime, String agent, Recorded transcript)
      throws Exception {
    return runtime.run(
        agent(agent),
        "please write the file",
        Home.global(),
        Budget.of(20),
        () -> false,
        null,
        JobWatch.UNWATCHED,
        transcript);
  }

  private static HookRecord record(String hook, Stage stage, String decision) {
    return new HookRecord(hook, "x.ts", Tier.PROJECT, stage, null, decision, null, null, null, 1);
  }

  // --- prompt.pre ------------------------------------------------------------------

  @Test
  void a_volatile_addition_is_sent_last_and_recorded_only_as_a_hook() throws Exception {
    Scripted model = new Scripted();
    JobRuntime runtime = runtime(model);
    runtime.useHooks(
        new Hooks() {
          @Override
          public PromptPre promptPre(HookContext context, String utterance) {
            return new PromptPre(
                List.of(new Addition("policy", "[remember things]", Mode.VOLATILE)),
                List.of(record("policy", Stage.PROMPT_PRE, HookRecord.ADD)));
          }
        });
    Recorded log = new Recorded();

    run(runtime, "echo", log);

    List<ChatMessage> first = model.sent.get(0);
    assertEquals("[remember things]", first.get(first.size() - 1).content());
    assertEquals(List.of(EntryKind.UTTERANCE, EntryKind.HOOK, EntryKind.ANSWER), log.kinds());
  }

  @Test
  void a_durable_addition_is_recorded_as_a_notice_and_sent_before_the_reminder() throws Exception {
    Scripted model = new Scripted();
    ChatMessage reminder = ChatMessage.user("[what the archive holds]");
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
            null,
            Instant::now,
            (definition, home, utterance) -> Optional.of(reminder));
    runtime.useHooks(
        new Hooks() {
          @Override
          public PromptPre promptPre(HookContext context, String utterance) {
            return new PromptPre(
                List.of(
                    new Addition("house", "[house rule]", Mode.DURABLE),
                    new Addition("policy", "[this turn only]", Mode.VOLATILE)),
                List.of(
                    record("house", Stage.PROMPT_PRE, HookRecord.ADD),
                    record("policy", Stage.PROMPT_PRE, HookRecord.ADD)));
          }
        });
    Recorded log = new Recorded();

    run(runtime, "echo", log);

    List<String> tail = model.sent.get(0).stream().map(ChatMessage::content).toList();
    int n = tail.size();
    assertEquals(
        List.of("[house rule]", "[what the archive holds]", "[this turn only]"),
        tail.subList(n - 3, n),
        "durable before the unrecorded reminder, volatile after it: the only order in"
            + " which the next turn's prefix extends");
    assertEquals(
        List.of(
            EntryKind.UTTERANCE,
            EntryKind.NOTICE,
            EntryKind.HOOK,
            EntryKind.HOOK,
            EntryKind.HOOK,
            EntryKind.ANSWER),
        log.kinds(),
        "utterance, the durable notice, the recall audit, the two hook records, the answer");
    assertTrue(log.entries.get(2).content().contains("harness:recall"));
  }

  // --- prompt.post -----------------------------------------------------------------

  @Test
  void a_redacted_reply_is_what_is_recorded_and_returned() throws Exception {
    Scripted model = new Scripted().then(() -> answer("the password is hunter2"));
    JobRuntime runtime = runtime(model);
    runtime.useHooks(
        new Hooks() {
          @Override
          public PromptPost promptPost(HookContext context, String reply, List<String> asked) {
            return new PromptPost(
                reply.replace("hunter2", "[redacted]"),
                List.of(
                    new HookRecord(
                        "scrub",
                        "scrub.ts",
                        Tier.PROJECT,
                        Stage.PROMPT_POST,
                        null,
                        HookRecord.REDACT,
                        null,
                        null,
                        reply,
                        1)));
          }
        });
    Recorded log = new Recorded();

    Outcome outcome = run(runtime, "echo", log);

    assertEquals("the password is [redacted]", outcome.text());
    LoggedEntry answer =
        log.entries.stream().filter(e -> e.kind() == EntryKind.ANSWER).findFirst().orElseThrow();
    assertEquals("the password is [redacted]", answer.content());
    LoggedEntry hook = log.entries.get(log.entries.size() - 1);
    assertEquals(EntryKind.HOOK, hook.kind());
    assertTrue(hook.content().contains("hunter2"), "the original lives only in the hook entry");
  }

  // --- tool.pre / tool.post ----------------------------------------------------------

  @Test
  void a_denied_call_never_runs_and_the_model_is_told_why() throws Exception {
    Probe tool = new Probe("probe_write");
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{\"content\":\"KEY\"}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    runtime.useHooks(
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String name, String arguments) {
            return new ToolPre(
                arguments,
                "this looks like a private key",
                List.of(record("no-secrets", Stage.TOOL_PRE, HookRecord.DENY)));
          }
        });
    Recorded log = new Recorded();

    run(runtime, "contrarian", log);

    assertEquals(List.of(), tool.seen);
    String result =
        model.sent.get(1).stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .findFirst()
            .orElseThrow();
    assertTrue(result.contains("this looks like a private key"), result);
    int at = log.kinds().indexOf(EntryKind.TOOL_RESULT);
    assertEquals(
        EntryKind.HOOK, log.kinds().get(at + 1), "the decision is recorded after the result");
  }

  @Test
  void a_rewrite_is_what_the_tool_runs_with_and_a_redaction_is_what_the_model_sees()
      throws Exception {
    Probe tool = new Probe("probe_write");
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{\"path\":\"a\"}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    runtime.useHooks(
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String name, String arguments) {
            return new ToolPre(
                "{\"path\":\"b\"}",
                null,
                List.of(record("rename", Stage.TOOL_PRE, HookRecord.REWRITE)));
          }

          @Override
          public ToolPost toolPost(
              HookContext context, String name, String arguments, String result) {
            return new ToolPost(
                result.replace("hunter2", "[redacted]"),
                List.of(record("scrub", Stage.TOOL_POST, HookRecord.REDACT)));
          }
        });
    Recorded log = new Recorded();

    run(runtime, "contrarian", log);

    assertEquals(List.of("{\"path\":\"b\"}"), tool.seen);
    String result =
        model.sent.get(1).stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .findFirst()
            .orElseThrow();
    assertFalse(result.contains("hunter2"), result);
  }

  @Test
  void a_redaction_to_nothing_still_tells_the_model_something() throws Exception {
    Probe tool = new Probe("probe_write");
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    runtime.useHooks(
        new Hooks() {
          @Override
          public ToolPost toolPost(
              HookContext context, String name, String arguments, String result) {
            return new ToolPost("", List.of(record("scrub", Stage.TOOL_POST, HookRecord.REDACT)));
          }
        });
    Recorded log = new Recorded();

    run(runtime, "contrarian", log);

    String result =
        model.sent.get(1).stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .findFirst()
            .orElseThrow();
    assertFalse(
        result.isBlank(), "a blank tool message is the confident-empty shape usable exists for");
  }

  @Test
  void a_run_ended_by_a_tool_failure_still_records_what_the_hooks_decided() throws Exception {
    AgentTool gone =
        new Probe("probe_write") {
          @Override
          public String run(String argumentsJson, Home home) {
            throw new SessionGoneException("the client went away");
          }
        };
    Scripted model =
        new Scripted().then(() -> asking(new ToolCall("c1", "probe_write", "{\"path\":\"a\"}")));
    JobRuntime runtime = runtime(model, gone);
    runtime.useHooks(
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String name, String arguments) {
            return new ToolPre(
                "{\"path\":\"b\"}",
                null,
                List.of(record("rename", Stage.TOOL_PRE, HookRecord.REWRITE)));
          }
        });
    Recorded log = new Recorded();

    Outcome outcome = run(runtime, "contrarian", log);

    assertEquals(Outcome.Ending.SESSION_GONE, outcome.ending());
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("\"rename\"")
                        && e.content().contains("\"rewrite\"")),
        "the rewrite happened, and a run that ended on the call it rewrote must not lose it");
  }

  @Test
  void a_hooks_implementation_that_throws_denies_tools_and_adds_nothing_to_prompts()
      throws Exception {
    Probe tool = new Probe("probe_write");
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    runtime.useHooks(
        new Hooks() {
          @Override
          public PromptPre promptPre(HookContext context, String utterance) {
            throw new IllegalStateException("boom");
          }

          @Override
          public ToolPre toolPre(HookContext context, String name, String arguments) {
            throw new IllegalStateException("boom");
          }
        });
    Recorded log = new Recorded();

    Outcome outcome = run(runtime, "contrarian", log);

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals(List.of(), tool.seen, "a broken restriction fails closed");
    assertTrue(
        log.entries.stream()
            .anyMatch(e -> e.kind() == EntryKind.HOOK && e.content().contains("\"failed\"")));
  }

  /**
   * Tool stages fail closed on both sides of the call. A {@code Hooks} whose {@code toolPost}
   * throws never judged the result, so the model is not handed it unexamined — the same as a hook
   * that failed inside {@code ScriptHooks}.
   */
  @Test
  void a_hooks_implementation_whose_tool_post_throws_withholds_the_result() throws Exception {
    Probe tool = new Probe("probe_write");
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    runtime.useHooks(
        new Hooks() {
          @Override
          public ToolPost toolPost(
              HookContext context, String name, String arguments, String result) {
            throw new IllegalStateException("boom");
          }
        });
    Recorded log = new Recorded();

    run(runtime, "contrarian", log);

    String result =
        model.sent.get(1).stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .findFirst()
            .orElseThrow();
    assertEquals("the result was withheld because a hook failed while judging it", result);
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("\"failed\"")
                        && e.content().contains("boom")));
  }

  @Test
  void a_bot_that_can_remember_is_not_told_to_by_the_harness_every_turn() throws Exception {
    // The memory-policy stage is gone: its line arrived as the last user
    // message of every turn and a model answered it as the person. With no
    // project hooks, a bot's request ends with the person's own words.
    Scripted model = new Scripted();
    JobRuntime runtime = runtime(model);
    runtime.useHooks(Hooks.NONE);
    Recorded log = new Recorded();

    run(runtime, "rememberer", log);

    List<ChatMessage> first = model.sent.get(0);
    ChatMessage last = first.get(first.size() - 1);
    assertEquals(ChatMessage.Role.USER, last.role());
    assertTrue(
        first.stream()
            .noneMatch(
                m ->
                    m.content() != null
                        && m.content().contains("nobody has to ask you to: when you learn")));
    assertTrue(
        log.entries.stream()
            .noneMatch(
                e -> e.kind() == EntryKind.HOOK && e.content().contains("harness:memory-policy")));
  }

  @Test
  void with_no_hooks_a_run_sends_and_records_exactly_what_it_always_did() throws Exception {
    Scripted plain = new Scripted();
    Scripted hooked = new Scripted();
    Recorded plainLog = new Recorded();
    Recorded hookedLog = new Recorded();

    run(runtime(plain), "echo", plainLog);
    JobRuntime withNone = runtime(hooked);
    withNone.useHooks(Hooks.NONE);
    run(withNone, "echo", hookedLog);

    assertEquals(plain.sent.get(0), hooked.sent.get(0));
    assertEquals(plainLog.kinds(), hookedLog.kinds());
  }

  // --- step.post ---------------------------------------------------------------------

  @Test
  void step_post_fires_after_each_tool_asking_step_and_its_note_follows_the_results()
      throws Exception {
    Probe tool = new Probe();
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    List<Step> seen = new java.util.ArrayList<>();
    runtime.useHooks(
        new Hooks() {
          @Override
          public StepPost stepPost(HookContext context, Step step) {
            seen.add(step);
            return new StepPost(
                List.of("[Runtime note, not part of any tool's answer: look]"),
                List.of(record("house", Stage.STEP_POST, HookRecord.NOTE)));
          }
        });
    Recorded log = new Recorded();

    run(runtime, "contrarian", log);

    assertEquals(1, seen.size(), "the answering step does not fire step.post");
    assertEquals(1, seen.get(0).number());
    assertEquals("model-fast", seen.get(0).wireModel());
    assertEquals("probe_write", seen.get(0).calls().get(0).name());
    assertEquals(1, seen.get(0).results().size());
    List<ChatMessage> second = model.sent.get(1);
    assertEquals(ChatMessage.Role.TOOL, second.get(second.size() - 2).role());
    assertEquals(
        "[Runtime note, not part of any tool's answer: look]",
        second.get(second.size() - 1).content());
    assertTrue(log.kinds().contains(EntryKind.RUNTIME_NOTE));
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK && e.content().contains("\"stage\":\"step.post\"")));
  }

  /**
   * The stuck trap judges a run by what it can do: a tool it declares but is not offered is not one
   * it holds, and one handed to it beyond its definition is.
   */
  @Test
  void step_post_is_told_the_tools_the_run_was_offered_not_those_its_definition_declares()
      throws Exception {
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, new Probe());
    runtime.useRunExtras(
        context -> new RunExtras.Extras(List.of(new Probe("handed_on")), null, false));
    List<Set<String>> told = new java.util.ArrayList<>();
    runtime.useHooks(
        new Hooks() {
          @Override
          public StepPost stepPost(HookContext context, Step step) {
            told.add(context.tools());
            return StepPost.NOTHING;
          }
        });

    run(runtime, "contrarian", new Recorded());

    assertEquals(
        List.of(Set.of("probe_write", "handed_on")),
        told,
        "probe_read is declared but no tool answers to it; handed_on came with the run");
  }

  @Test
  void a_step_post_hook_that_throws_adds_no_note_and_records_the_failure() throws Exception {
    Probe tool = new Probe();
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    runtime.useHooks(
        new Hooks() {
          @Override
          public StepPost stepPost(HookContext context, Step step) {
            throw new IllegalStateException("boom");
          }
        });
    Recorded log = new Recorded();

    run(runtime, "contrarian", log);

    assertEquals(ChatMessage.Role.TOOL, model.sent.get(1).get(model.sent.get(1).size() - 1).role());
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("\"step.post\"")
                        && e.content().contains("boom")));
  }

  // --- the harness profile chains ahead of the project's hooks ----------------------

  /** A harness hook that notes every step and reports when its run is over. */
  private static final class Noting
      implements io.aeyer.plowshare.server.harness.HarnessHookFactory {
    int built;

    @Override
    public String name() {
      return "harness:noting";
    }

    @Override
    public List<io.aeyer.plowshare.server.harness.Parameter> parameters() {
      return List.of();
    }

    @Override
    public io.aeyer.plowshare.server.hooks.HarnessHook create(
        io.aeyer.plowshare.server.harness.Parameters parameters) {
      built++;
      return new io.aeyer.plowshare.server.hooks.HarnessHook() {
        @Override
        public StepPost stepPost(HookContext context, Step step) {
          return new StepPost(
              List.of("[Runtime note, not part of any tool's answer: harness]"), List.of());
        }

        @Override
        public List<HookRecord> finish() {
          return List.of(
              new HookRecord(
                  "harness:noting",
                  null,
                  Tier.HARNESS,
                  Stage.STEP_POST,
                  null,
                  HookRecord.UNUSED,
                  null,
                  null,
                  null,
                  0));
        }
      };
    }
  }

  private static io.aeyer.plowshare.server.harness.Harness harnessFor(String model, Noting noting) {
    io.aeyer.plowshare.server.harness.HarnessProperties properties =
        new io.aeyer.plowshare.server.harness.HarnessProperties();
    properties.setProfiles(Map.of("guided", List.of(Map.of("hook", "harness:noting"))));
    return new io.aeyer.plowshare.server.harness.Harness(
        io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
            properties, Map.of(model, "guided"), List.of(noting)));
  }

  @Test
  void the_profile_of_the_model_a_run_goes_to_is_built_for_that_run_and_runs_before_project_hooks()
      throws Exception {
    Probe tool = new Probe();
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    Noting noting = new Noting();
    runtime.useHarness(harnessFor("model-fast", noting));
    // A project hook that notes too, so the ordering claim in this test's name
    // is something the assertions below can actually fail on: with only the
    // harness installed, a harness note landing last would look identical to
    // one landing first.
    runtime.useHooks(
        new Hooks() {
          @Override
          public StepPost stepPost(HookContext context, Step step) {
            return new StepPost(
                List.of("[Runtime note, not part of any tool's answer: project]"),
                List.of(record("house", Stage.STEP_POST, HookRecord.NOTE)));
          }
        });
    Recorded log = new Recorded();

    run(runtime, "contrarian", log);
    run(runtime, "contrarian", new Recorded());

    assertEquals(2, noting.built, "one instance per run");
    List<ChatMessage> second = model.sent.get(1);
    int n = second.size();
    assertEquals(
        "[Runtime note, not part of any tool's answer: harness]",
        second.get(n - 2).content(),
        "the harness's note is sent first");
    assertEquals(
        "[Runtime note, not part of any tool's answer: project]",
        second.get(n - 1).content(),
        "and the project's note follows it, in chain order");
    assertTrue(
        log.entries.stream()
            .anyMatch(e -> e.kind() == EntryKind.HOOK && e.content().contains("\"unused\"")),
        "what finish reports is recorded");
  }

  @Test
  void a_model_assigned_no_profile_runs_no_harness_hooks() throws Exception {
    Probe tool = new Probe();
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    Noting noting = new Noting();
    runtime.useHarness(harnessFor("some-other-model", noting));

    run(runtime, "contrarian", new Recorded());

    assertEquals(0, noting.built);
  }

  // --- a hook that cannot be built or cannot finish is not the run's problem --------------

  /** A harness hook factory that always throws while being built. */
  private static final class FailsToBuild
      implements io.aeyer.plowshare.server.harness.HarnessHookFactory {
    int attempts;

    @Override
    public String name() {
      return "harness:fails-to-build";
    }

    @Override
    public List<io.aeyer.plowshare.server.harness.Parameter> parameters() {
      return List.of();
    }

    @Override
    public io.aeyer.plowshare.server.hooks.HarnessHook create(
        io.aeyer.plowshare.server.harness.Parameters parameters) {
      attempts++;
      throw new IllegalStateException("cannot be built");
    }
  }

  /** A harness hook factory whose hook builds cleanly and then throws on finish. */
  private static final class FinishFails
      implements io.aeyer.plowshare.server.harness.HarnessHookFactory {
    @Override
    public String name() {
      return "harness:finish-fails";
    }

    @Override
    public List<io.aeyer.plowshare.server.harness.Parameter> parameters() {
      return List.of();
    }

    @Override
    public io.aeyer.plowshare.server.hooks.HarnessHook create(
        io.aeyer.plowshare.server.harness.Parameters parameters) {
      return new io.aeyer.plowshare.server.hooks.HarnessHook() {
        @Override
        public List<HookRecord> finish() {
          throw new IllegalStateException("finish broke");
        }
      };
    }
  }

  @Test
  void a_harness_hook_that_fails_to_build_does_not_stop_the_run() throws Exception {
    Probe tool = new Probe();
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    FailsToBuild failing = new FailsToBuild();
    io.aeyer.plowshare.server.harness.HarnessProperties properties =
        new io.aeyer.plowshare.server.harness.HarnessProperties();
    properties.setProfiles(Map.of("guided", List.of(Map.of("hook", "harness:fails-to-build"))));
    runtime.useHarness(
        new io.aeyer.plowshare.server.harness.Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                properties, Map.of("model-fast", "guided"), List.of(failing))));
    Recorded log = new Recorded();

    Outcome outcome = run(runtime, "contrarian", log);

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals("ok", outcome.text(), "the model still gets its answer");
    assertEquals(1, failing.attempts, "built once; the build failure must not be retried mid-run");
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("\"harness:fails-to-build\"")
                        && e.content().contains("\"failed\"")),
        "a failed hook entry names the hook");
  }

  @Test
  void a_harness_hook_whose_finish_throws_beside_one_that_answers_still_finishes_the_run()
      throws Exception {
    Probe tool = new Probe();
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    Noting noting = new Noting();
    FinishFails finishFails = new FinishFails();
    io.aeyer.plowshare.server.harness.HarnessProperties properties =
        new io.aeyer.plowshare.server.harness.HarnessProperties();
    properties.setProfiles(
        Map.of(
            "guided",
            List.of(Map.of("hook", "harness:noting"), Map.of("hook", "harness:finish-fails"))));
    runtime.useHarness(
        new io.aeyer.plowshare.server.harness.Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                properties, Map.of("model-fast", "guided"), List.of(noting, finishFails))));
    Recorded log = new Recorded();

    Outcome outcome = run(runtime, "contrarian", log);

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals("ok", outcome.text(), "the model still gets its answer");
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("\"harness:noting\"")
                        && e.content().contains("\"unused\"")),
        "the hook that finished cleanly still has its record");
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("\"harness:finish-fails\"")
                        && e.content().contains("\"failed\"")),
        "and the one whose finish() threw is a failed record rather than lost");
  }

  // --- a reroute switches which profile the harness runs -----------------------------------

  @Test
  void a_reroute_after_a_refusal_switches_which_harness_profile_runs() throws Exception {
    Scripted primaryModel =
        new Scripted().then(() -> answer("I'm sorry, but I can't help with that."));
    Scripted fallbackModel = new Scripted().then(() -> answer("The registrant is IANA."));
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "primary-pool",
                    List.of("big-primary"),
                    Map.of("big", "big-primary"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    primaryModel),
                new LlmPool(
                    "fallback-pool",
                    List.of("small-fallback"),
                    Map.of("low_refusal", "small-fallback"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    fallbackModel)),
            new NoOpTokenLedger());
    JobRuntime runtime = new JobRuntime(dispatcher, List.of());
    AgentDefinition definition =
        new AgentDefinition(
                "osint",
                "a fixture agent that may fall back",
                "big",
                List.of(),
                List.of(),
                List.of(),
                6,
                20,
                "You research public sources for an authorised engagement.")
            .fallback(
                new AgentDefinition.Fallback(
                    Set.of(AgentDefinition.Fallback.Trigger.REFUSAL),
                    "low_refusal",
                    1,
                    Sampling.NONE));
    Noting noting = new Noting();
    io.aeyer.plowshare.server.harness.HarnessProperties properties =
        new io.aeyer.plowshare.server.harness.HarnessProperties();
    properties.setProfiles(Map.of("guided", List.of(Map.of("hook", "harness:noting"))));
    // ONLY THE FALLBACK'S WIRE MODEL IS ASSIGNED A PROFILE. The primary's is
    // given none at all, so Harness.profileFor answers null for it no matter
    // when it is asked -- which means a hook built during this run can only
    // ever be one built for the model the reroute actually sent the call to.
    runtime.useHarness(
        new io.aeyer.plowshare.server.harness.Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                properties, Map.of("small-fallback", "guided"), List.of(noting))));
    Recorded log = new Recorded();

    Outcome outcome =
        runtime.run(
            definition,
            "Who is the registrant of example.org?",
            Home.global(),
            Budget.of(20),
            () -> false,
            null,
            JobWatch.UNWATCHED,
            log);

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals(
        "The registrant is IANA.",
        outcome.text(),
        "the fallback's answer is what the run comes to");
    assertEquals(1, primaryModel.sent.size(), "the primary is asked once, and refuses");
    assertEquals(1, fallbackModel.sent.size(), "the fallback is asked once, after the reroute");
    assertEquals(
        1,
        noting.built,
        "the harness hook was built exactly once, and it could only"
            + " have been built for the fallback's wire model -- the primary's carries no"
            + " profile at all");
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("\"harness:noting\"")
                        && e.content().contains("\"unused\"")));
  }

  @Test
  void a_profile_first_met_after_a_reroute_still_knows_the_persons_question() throws Exception {
    // Refused, so the trap has a sign to fire on at the fallback's one step.
    Probe tool = new Probe("probe_write", "probe_write refused: there is nothing to write");
    Scripted primaryModel =
        new Scripted().then(() -> answer("I'm sorry, but I can't help with that."));
    Scripted fallbackModel =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{}")))
            .then(() -> answer("The registrant is IANA."));
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "primary-pool",
                    List.of("big-primary"),
                    Map.of("big", "big-primary"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    primaryModel),
                new LlmPool(
                    "fallback-pool",
                    List.of("small-fallback"),
                    Map.of("low_refusal", "small-fallback"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    fallbackModel)),
            new NoOpTokenLedger());
    JobRuntime runtime = new JobRuntime(dispatcher, List.of(tool));
    AgentDefinition definition =
        new AgentDefinition(
                "osint",
                "a fixture agent that may fall back",
                "big",
                List.of("probe_write"),
                List.of(),
                List.of(),
                6,
                20,
                "You research public sources for an authorised engagement.")
            .fallback(
                new AgentDefinition.Fallback(
                    Set.of(AgentDefinition.Fallback.Trigger.REFUSAL),
                    "low_refusal",
                    1,
                    Sampling.NONE));
    List<String> briefs = Collections.synchronizedList(new ArrayList<>());
    io.aeyer.plowshare.server.harness.HarnessHookFactory stuck =
        new io.aeyer.plowshare.server.harness.HarnessHookFactory() {
          @Override
          public String name() {
            return io.aeyer.plowshare.server.harness.StuckTrap.NAME;
          }

          @Override
          public List<io.aeyer.plowshare.server.harness.Parameter> parameters() {
            return io.aeyer.plowshare.server.harness.StuckTrapFactory.PARAMETERS;
          }

          @Override
          public io.aeyer.plowshare.server.hooks.HarnessHook create(
              io.aeyer.plowshare.server.harness.Parameters p) {
            return new io.aeyer.plowshare.server.harness.StuckTrap(
                p,
                (specifier, brief, abandoned) -> {
                  briefs.add(brief);
                  return new io.aeyer.plowshare.server.harness.StuckTrap.Advice(
                      "Broaden it.", "advisor-model", 7);
                },
                Runnable::run,
                java.time.ZonedDateTime::now,
                () -> 0L);
          }
        };
    io.aeyer.plowshare.server.harness.HarnessProperties properties =
        new io.aeyer.plowshare.server.harness.HarnessProperties();
    properties.setProfiles(
        Map.of("guided", List.of(Map.of("hook", "harness:stuck", "failure-streak", "1"))));
    // Only the fallback's model has a profile, so the trap is built after prompt.pre fired.
    runtime.useHarness(
        new io.aeyer.plowshare.server.harness.Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                properties, Map.of("small-fallback", "guided"), List.of(stuck))));
    Recorded log = new Recorded();

    Outcome outcome =
        runtime.run(
            definition,
            "Who is the registrant of example.org?",
            Home.global(),
            Budget.of(20),
            () -> false,
            null,
            JobWatch.UNWATCHED,
            log);

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals(1, briefs.size(), "the fallback's tool call reached the trap");
    assertTrue(
        briefs.get(0).contains("\n\nThe question: Who is the registrant of example.org?"),
        briefs.get(0));
    assertEquals(1, tool.seen.size(), "the call ran on the fallback");
  }

  // --- the whole run, end to end: a profile's stuck trap reaching a real model call --

  /** Three refused calls and an answer, with the trap firing on the first refusal. */
  private Scripted refusedThrice(String advice, Recorded log) throws Exception {
    Probe tool = new Probe("probe_write", "probe_write refused: there is nothing to write");
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{\"q\":1}")))
            .then(() -> asking(new ToolCall("c2", "probe_write", "{\"q\":2}")))
            .then(() -> asking(new ToolCall("c3", "probe_write", "{\"q\":3}")))
            .then(() -> answer("ok"));
    JobRuntime runtime = runtime(model, tool);
    io.aeyer.plowshare.server.harness.HarnessHookFactory stuck =
        new io.aeyer.plowshare.server.harness.HarnessHookFactory() {
          @Override
          public String name() {
            return io.aeyer.plowshare.server.harness.StuckTrap.NAME;
          }

          @Override
          public List<io.aeyer.plowshare.server.harness.Parameter> parameters() {
            return io.aeyer.plowshare.server.harness.StuckTrapFactory.PARAMETERS;
          }

          @Override
          public io.aeyer.plowshare.server.hooks.HarnessHook create(
              io.aeyer.plowshare.server.harness.Parameters p) {
            return new io.aeyer.plowshare.server.harness.StuckTrap(
                p,
                (specifier, brief, abandoned) ->
                    new io.aeyer.plowshare.server.harness.StuckTrap.Advice(
                        advice, "advisor-model", 7),
                Runnable::run,
                java.time.ZonedDateTime::now,
                () -> 0L);
          }
        };
    io.aeyer.plowshare.server.harness.HarnessProperties properties =
        new io.aeyer.plowshare.server.harness.HarnessProperties();
    properties.setProfiles(
        Map.of("guided", List.of(Map.of("hook", "harness:stuck", "failure-streak", "1"))));
    runtime.useHarness(
        new io.aeyer.plowshare.server.harness.Harness(
            io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
                properties, Map.of("model-fast", "guided"), List.of(stuck))));

    run(runtime, "contrarian", log);
    return model;
  }

  @Test
  void a_turn_that_keeps_calling_tools_is_handed_the_advisors_note_through_its_profile()
      throws Exception {
    Recorded log = new Recorded();

    Scripted model =
        refusedThrice("{\"note\": \"probe_write refuses every q; try it without one.\"}", log);

    List<ChatMessage> third = model.sent.get(2);
    assertEquals(
        "[Runtime note from the harness, not from the person and not a tool's answer. Keep"
            + " working on your task; this note needs no reply. The last tool call failed or was refused."
            + " A second model that looked at the recent steps suggests: probe_write refuses every q;"
            + " try it without one.]",
        third.get(third.size() - 1).content());
    assertEquals(ChatMessage.Role.USER, third.get(third.size() - 1).role());
    assertEquals(1, log.entries.stream().filter(e -> e.kind() == EntryKind.RUNTIME_NOTE).count());
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("harness:stuck")
                        && e.content().contains("\"note\"")));
  }

  @Test
  void an_advisor_with_nothing_to_say_sends_the_model_nothing_and_leaves_only_the_audit_record()
      throws Exception {
    Recorded log = new Recorded();

    Scripted model = refusedThrice("{\"note\": null}", log);

    for (List<ChatMessage> request : model.sent) {
      assertTrue(
          request.stream()
              .noneMatch(m -> m.content() != null && m.content().contains("Runtime note")),
          "no request carries a note: " + request);
    }
    List<ChatMessage> third = model.sent.get(2);
    assertEquals(
        ChatMessage.Role.TOOL,
        third.get(third.size() - 1).role(),
        "the tool result is the last thing the model is sent");
    assertEquals(0, log.entries.stream().filter(e -> e.kind() == EntryKind.RUNTIME_NOTE).count());
    assertTrue(
        log.entries.stream()
            .anyMatch(
                e ->
                    e.kind() == EntryKind.HOOK
                        && e.content().contains("harness:stuck")
                        && e.content().contains("\"swallowed\"")),
        "the verdict is recorded for a person, where no model reads it");
    assertTrue(
        log.entries.stream()
            .filter(e -> e.kind() == EntryKind.HOOK)
            .noneMatch(e -> e.kind().projects()));
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log decision 9: after the prompt, the same bytes every time.
   */
  @Test
  void a_log_s_opening_follows_the_prompt_byte_for_byte_in_every_request() throws Exception {
    Scripted model = new Scripted();
    JobRuntime runtime = runtime(model);
    Recorded log = new Recorded();
    log.opening = "Answer in the house style.";

    run(runtime, "echo", log);
    run(runtime, "echo", log);

    assertEquals(
        agent("echo").prompt() + "\n\nAnswer in the house style.",
        model.sent.get(0).get(0).content());
    assertEquals(model.sent.get(0).get(0).content(), model.sent.get(1).get(0).content());
  }

  @Test
  void two_requests_within_one_run_carry_the_same_opening() throws Exception {
    Scripted model =
        new Scripted()
            .then(() -> asking(new ToolCall("c1", "probe_write", "{\"content\":\"x\"}")))
            .then(() -> answer("ok"));
    Recorded log = new Recorded();
    log.opening = "Answer in the house style.";

    run(runtime(model, new Probe("probe_write")), "contrarian", log);

    assertEquals(2, model.sent.size(), "a tool call, then the answer");
    assertEquals(
        agent("contrarian").prompt() + "\n\nAnswer in the house style.",
        model.sent.get(0).get(0).content());
    assertEquals(model.sent.get(0).get(0).content(), model.sent.get(1).get(0).content());
  }

  @Test
  void a_log_with_no_opening_sends_the_prompt_alone() throws Exception {
    Scripted model = new Scripted();

    run(runtime(model), "echo", new Recorded());

    assertEquals(agent("echo").prompt(), model.sent.get(0).get(0).content());
  }
}
