package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.hooks.script.HooksProperties;
import io.aeyer.plowshare.server.hooks.script.ScriptHooks;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The spec's own example hook, written to a project directory and run through a turn. */
class ProjectHooksEndToEndTest {

  @TempDir Path data;

  private HookEngine engine;
  private ScriptHooks project;

  private static final String EXAMPLE =
      """
            import type { Hook } from '@plowshare/hooks'

            export default {
                name: 'no-secrets-in-writes',
                stages: {
                    'tool.pre': {
                        tools: ['probe_write'],
                        handle(call) {
                            if (/BEGIN (RSA|OPENSSH) PRIVATE KEY/.test(String(call.args.content ?? ''))) {
                                return { deny: 'this looks like a private key; it was not written' }
                            }
                            return { allow: true }
                        },
                    },
                },
            } satisfies Hook
            """;

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
      return at < steps.size()
          ? steps.get(at).get()
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

  private static final class Log implements Transcript {
    final List<LoggedEntry> entries = new ArrayList<>();

    @Override
    public List<ChatMessage> before() {
      return List.of();
    }

    @Override
    public String conversationId() {
      return "cnv_e2e";
    }

    @Override
    public void promptMeasured(int promptTokens) {}

    @Override
    public void record(LoggedEntry entry) {
      entries.add(entry);
    }
  }

  private static final class Probe implements AgentTool {
    final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    @Override
    public ToolSchema schema() {
      return ToolSchema.from(
          "probe_write", "a probe", Map.of("type", "object", "properties", Map.of()));
    }

    @Override
    public String run(String argumentsJson, Home home) {
      seen.add(argumentsJson);
      return "written";
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    engine = new HookEngine();
    Path hooks = data.resolve("projects").resolve("7").resolve("hooks");
    Files.createDirectories(hooks);
    Files.writeString(hooks.resolve("10-secrets.ts"), EXAMPLE);
    project =
        new ScriptHooks(
            name -> "ledger".equals(name) ? 7L : null,
            id -> data.resolve("projects").resolve(Long.toString(id)).resolve("hooks"),
            engine,
            new HooksProperties(),
            Instant::now);
  }

  @AfterEach
  void tearDown() {
    project.close();
    engine.close();
  }

  private Outcome turn(Scripted model, Probe probe, Log log, Home home) throws Exception {
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
            List.of(probe));
    runtime.useHooks(project);
    Path fixtures = Path.of(ProjectHooksEndToEndTest.class.getResource("/agents").toURI());
    AgentDefinition contrarian =
        AgentRegistry.of(
                fixtures,
                Set.of("probe_read", "probe_write", "memory_write", AgentRegistry.AGENT_RUN))
            .get("contrarian");
    return runtime.run(
        contrarian,
        "save the key",
        home,
        Budget.of(20),
        () -> false,
        null,
        JobWatch.UNWATCHED,
        log);
  }

  @Test
  void the_specs_example_refuses_a_private_key_in_its_own_project() throws Exception {
    Probe probe = new Probe();
    Scripted model =
        new Scripted()
            .then(
                () ->
                    new Completion(
                        "",
                        "tool_calls",
                        TokenUsage.UNKNOWN,
                        List.of(
                            new ToolCall(
                                "c1",
                                "probe_write",
                                "{\"content\":\"-----BEGIN OPENSSH PRIVATE KEY-----\"}"))))
            .then(
                () ->
                    new Completion("I could not write it.", "stop", TokenUsage.UNKNOWN, List.of()));
    Log log = new Log();

    Outcome outcome = turn(model, probe, log, Home.of("ledger"));

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals(List.of(), probe.seen, "the write never ran");
    String shown =
        model.sent.get(1).stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .findFirst()
            .orElseThrow();
    assertTrue(shown.contains("this looks like a private key"), shown);
    LoggedEntry hook =
        log.entries.stream()
            .filter(e -> e.kind() == EntryKind.HOOK && e.content().contains("no-secrets-in-writes"))
            .findFirst()
            .orElseThrow();
    assertTrue(hook.content().contains("\"file\":\"10-secrets.ts\""), hook.content());
    assertTrue(hook.content().contains("\"decision\":\"deny\""), hook.content());
  }

  @Test
  void the_global_tier_runs_no_project_hooks() throws Exception {
    Probe probe = new Probe();
    Scripted model =
        new Scripted()
            .then(
                () ->
                    new Completion(
                        "",
                        "tool_calls",
                        TokenUsage.UNKNOWN,
                        List.of(
                            new ToolCall(
                                "c1",
                                "probe_write",
                                "{\"content\":\"-----BEGIN OPENSSH PRIVATE KEY-----\"}"))));
    Log log = new Log();

    turn(model, probe, log, Home.global());

    assertEquals(1, probe.seen.size(), "no project, no project hooks");
    assertFalse(
        log.entries.stream()
            .anyMatch(
                e -> e.kind() == EntryKind.HOOK && e.content().contains("no-secrets-in-writes")));
  }
}
