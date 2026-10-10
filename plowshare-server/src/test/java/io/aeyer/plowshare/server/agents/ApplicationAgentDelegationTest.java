package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.images.ImageStore;
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
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Application definition loading and delegation, with no database or model endpoint. */
class ApplicationAgentDelegationTest {
  @TempDir Path root;
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final SessionRegistry sessions = mock(SessionRegistry.class);
  private final ProjectStore projects = mock(ProjectStore.class);
  private final AgentRegistry boot = new AgentRegistry(Map.of());
  private DefinitionResolver resolver;
  private Callers callers;

  @BeforeEach
  void setup() throws Exception {
    when(projects.id("alpha")).thenReturn(7L);
    when(projects.id("bravo")).thenReturn(8L);
    for (String project : List.of("alpha", "bravo")) {
      when(members.mayUse(project, "service")).thenReturn(true);
      when(members.mayWork(project, "service")).thenReturn(true);
      write(project, "boss", "tools: [agent_run]\ncalls: [helper]\n", "caller " + project);
      write(project, "helper", "exported: false\n", "helper " + project);
    }
    resolver =
        new DefinitionResolver(
            boot,
            new DataLayout(null),
            id -> id == 7L || id == 8L,
            Set.of("agent_run"),
            Set.of(),
            mock(SessionChannel.class),
            session -> false,
            (id, session) -> false,
            DefinitionChecks.NONE);
    resolver.useApplicationResources(
        id ->
            Optional.ofNullable(
                id == null
                    ? null
                    : id == 7L ? root.resolve("alpha") : id == 8L ? root.resolve("bravo") : null));
    callers =
        new Callers(resolver, projects, mock(Turn.class), new CallerAccess(sessions, members));
  }

  private void write(String project, String name, String extra, String prompt)
      throws java.io.IOException {
    Path directory = Files.createDirectories(root.resolve(project).resolve("agents"));
    Files.writeString(
        directory.resolve(name + ".md"),
        "---\nname: "
            + name
            + "\ndescription: description "
            + prompt
            + "\nmodel: fast\nmax-turns: 4\nmax-model-calls: 8\n"
            + extra
            + "---\n"
            + prompt);
  }

  private JobRuntime runtime(Scripted transport) {
    var dispatcher =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "scripted",
                    List.of("model"),
                    Map.of("fast", "model"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    return new JobRuntime(
        dispatcher,
        List.of(),
        () -> boot,
        null,
        Instant::now,
        Reminding.NONE,
        ImageStore.NONE,
        RefusalDetector.PHRASES,
        System::nanoTime,
        callers);
  }

  private Outcome run(JobRuntime runtime, String project) {
    var home = Home.of(project);
    var boss = callers.visible(home, null, "service").get("boss");
    return runtime.run(
        boss,
        "investigate",
        home,
        Budget.of(8),
        () -> false,
        null,
        JobWatch.UNWATCHED,
        Transcript.NONE,
        TurnCap.none(),
        List.of(),
        "service");
  }

  @Test
  void application_delegate_is_described_and_runs_without_a_client_or_data_directory() {
    var transport = new Scripted();
    assertEquals(Outcome.Ending.ANSWERED, run(runtime(transport), "alpha").ending());
    assertEquals(3, transport.requests.size());
    assertTrue(transport.schemas.get(1).isEmpty(), "the child does not inherit the parent's tools");
    assertTrue(
        transport.schemas.getFirst().getFirst().description().contains("description helper alpha"));
    assertTrue(transport.requests.get(1).getFirst().content().contains("helper alpha"));
    verify(members).mayWork("alpha", "service");
  }

  @Test
  void same_named_delegates_resolve_only_in_the_inherited_project() {
    var transport = new Scripted();
    run(runtime(transport), "bravo");
    assertEquals(3, transport.requests.size());
    assertTrue(transport.requests.get(1).getFirst().content().contains("helper bravo"));
    assertFalse(transport.requests.get(1).getFirst().content().contains("helper alpha"));
    verify(members, never()).mayWork(eq("alpha"), any());
  }

  @Test
  void undeclared_name_is_refused_before_delegate_resolution() {
    var transport = new Scripted();
    transport.wanted = "other";
    run(runtime(transport), "alpha");
    assertEquals(2, transport.requests.size());
    assertTrue(transport.toolResult().contains("may not call 'other'"));
    verify(members, never()).mayWork(any(), any());
  }

  @Test
  void removing_a_delegate_after_schema_construction_does_not_run_a_stale_definition() {
    var transport = new Scripted();
    transport.beforeCall =
        () -> {
          try {
            Files.delete(root.resolve("alpha/agents/helper.md"));
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
          resolver.invalidate(7L);
        };
    run(runtime(transport), "alpha");
    assertEquals(2, transport.requests.size());
    assertTrue(transport.toolResult().contains("not in the set this run can delegate into"));
    verify(members).mayWork("alpha", "service");
  }

  @Test
  void a_project_removed_after_schema_construction_cannot_fall_back_to_global_definitions() {
    var transport = new Scripted();
    transport.beforeCall = () -> when(projects.id("alpha")).thenReturn(null);
    run(runtime(transport), "alpha");
    assertEquals(2, transport.requests.size());
    assertTrue(transport.toolResult().startsWith("E_NO_ACCESS:"), transport.toolResult());
    assertTrue(transport.toolResult().contains("current project is unavailable"));
  }

  @Test
  void work_permission_revoked_after_schema_construction_refuses_before_starting_a_child() {
    var transport = new Scripted();
    transport.beforeCall = () -> when(members.mayWork("alpha", "service")).thenReturn(false);
    run(runtime(transport), "alpha");
    assertEquals(2, transport.requests.size());
    assertTrue(transport.toolResult().startsWith("E_NO_ACCESS:"), transport.toolResult());
    assertTrue(transport.toolResult().contains("CONTRIBUTOR"));
  }

  @Test
  void a_reloaded_delegate_cannot_acquire_workspace_grants_the_admitted_caller_lacks() {
    var transport = new Scripted();
    transport.beforeCall =
        () -> {
          try {
            write("alpha", "helper", "scopes: [workspace:write]\n", "broader helper");
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
          resolver.invalidate(7L);
        };
    run(runtime(transport), "alpha");
    assertEquals(2, transport.requests.size());
    assertTrue(transport.toolResult().contains("workspace grants exceed"), transport.toolResult());
  }

  @Test
  void foreign_session_and_missing_identity_cannot_acquire_project_definitions() {
    when(sessions.accountOf("foreign")).thenReturn(Optional.of("someone_else"));
    assertThrows(CallerFault.class, () -> callers.visible(Home.of("alpha"), "foreign", "service"));
    assertThrows(CallerFault.class, () -> callers.find(Home.of("alpha"), null, null, "helper"));
  }

  @Test
  void read_only_members_can_inspect_descriptions_but_cannot_delegate() {
    when(members.mayWork("alpha", "service")).thenReturn(false);
    assertTrue(callers.visible(Home.of("alpha"), null, "service").find("helper").isPresent());
    assertThrows(
        CallerFault.class, () -> callers.find(Home.of("alpha"), null, "service", "helper"));
  }

  private static final class Scripted implements LlmTransport {
    final List<List<ChatMessage>> requests = new ArrayList<>();
    final List<List<ToolSchema>> schemas = new ArrayList<>();
    String wanted = "helper";
    Runnable beforeCall = () -> {};

    String toolResult() {
      return requests.getLast().stream()
          .filter(message -> message.role() == ChatMessage.Role.TOOL)
          .map(ChatMessage::content)
          .findFirst()
          .orElseThrow();
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      requests.add(List.copyOf(messages));
      schemas.add(List.copyOf(tools));
      if (requests.size() == 1) {
        beforeCall.run();
        return new Completion(
            "",
            "tool_calls",
            TokenUsage.UNKNOWN,
            List.of(
                new ToolCall(
                    "delegate",
                    "agent_run",
                    "{\"agent\":\"" + wanted + "\",\"task\":\"assess\"}")));
      }
      return new Completion("assessment complete", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      var result = complete(model, messages, sampling, tools);
      if (!result.content().isEmpty()) sink.answered(result.content());
      return result;
    }

    @Override
    public Embeddings embed(String model, List<String> input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }
}
