package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.acting.PersonalRunAuthority;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Real runtime and standard fences, with mocked repositories/providers and no external effects. */
class RetainedExecutionTest {
  final Home home = Home.of("personal:616c696365");
  final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
  final ResourceScopeRepository resources = mock(ResourceScopeRepository.class);
  final AtomicBoolean allowed = new AtomicBoolean(true);
  final Budget budget = Budget.of(12);
  final Transcript transcript = mock(Transcript.class, CALLS_REAL_METHODS);
  final Runnable check =
      () -> {
        if (!allowed.get()) throw new CallerFault("revoked");
      };
  final RunAuthority authority = new PersonalRunAuthority("alice", check, resources);

  RetainedExecutionTest() {
    when(transcript.conversationId()).thenReturn("cnv_ROOT");
    when(resources.projects(any())).thenReturn(List.of(home.project()));
  }

  AgentDefinition definition(
      String name, List<String> tools, List<String> calls, boolean exported) {
    return new AgentDefinition(
        name, name, "fast", tools, calls, List.of(), 8, 12, "Answer clearly", exported, true);
  }

  Completion calls(ToolCall... calls) {
    return new Completion(null, "tool_calls", TokenUsage.UNKNOWN, List.of(calls));
  }

  Completion answer() {
    return new Completion("finished", "stop", TokenUsage.UNKNOWN, List.of());
  }

  AgentTool tool(String name) {
    var tool = mock(AgentTool.class);
    when(tool.schema()).thenReturn(ToolSchema.from(name, name, Map.of("type", "object")));
    when(tool.run(anyString(), eq(home))).thenReturn("visible result");
    return tool;
  }

  Outcome run(JobRuntime runtime, AgentDefinition definition) {
    var admission = new RunAdmission(check, job -> {}, () -> {}, "alice", authority);
    return runtime
        .forAdmittedTurn(admission)
        .run(
            definition,
            "Hello",
            home,
            budget,
            () -> false,
            null,
            JobWatch.UNWATCHED,
            transcript,
            TurnCap.of(8),
            List.of(),
            "alice",
            false);
  }

  @Test
  void standard_grant_controls_offering_and_dispatch() {
    var date = tool("get_date");
    var search = tool("search");
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            calls(new ToolCall("one", "search", "{}"), new ToolCall("two", "get_date", "{}")),
            answer());
    var result =
        run(
            new JobRuntime(dispatcher, List.of(date, search)),
            definition("chat", List.of("get_date"), List.of(), true));
    assertEquals(Outcome.Ending.ANSWERED, result.ending());
    verify(date).run("{}", home);
    verify(search, never()).run(anyString(), any());
  }

  @Test
  void hook_denial_is_preserved_and_hook_rewrite_is_authorized_before_dispatch() {
    var read = tool("memory_read");
    var foreign = new AccessRequest.Resource(ResourceScopeRepository.Kind.MEMORY, "mem_FOREIGN");
    when(resources.projects(foreign)).thenReturn(List.of("another-project"));
    var hooks =
        new Hooks() {
          public ToolPre toolPre(HookContext context, String tool, String arguments) {
            return ToolPre.allowed("{\"ids\":[\"mem_FOREIGN\"]}");
          }
        };
    var runtime = new JobRuntime(dispatcher, List.of(read));
    runtime.useHooks(hooks);
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            calls(new ToolCall("one", "memory_read", "{\"ids\":[\"mem_LOCAL\"]}")), answer());
    run(runtime, definition("chat", List.of("memory_read"), List.of(), true));
    verify(read, never()).run(anyString(), any());
    verify(resources).projects(foreign);
    reset(dispatcher);
    runtime.useHooks(
        new Hooks() {
          public ToolPre toolPre(HookContext context, String tool, String arguments) {
            return new ToolPre(arguments, "blocked by normal policy", List.of());
          }
        });
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            calls(new ToolCall("two", "memory_read", "{\"ids\":[\"mem_LOCAL\"]}")), answer());
    run(runtime, definition("chat", List.of("memory_read"), List.of(), true));
    verify(read, never()).run(anyString(), any());
  }

  @Test
  void revocation_withholds_the_result_and_stops_the_remaining_batch() {
    var read = tool("memory_read");
    var date = tool("get_date");
    when(read.run(anyString(), eq(home)))
        .thenAnswer(
            call -> {
              allowed.set(false);
              return "must not escape";
            });
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            calls(
                new ToolCall("one", "memory_read", "{\"ids\":[\"mem_LOCAL\"]}"),
                new ToolCall("two", "get_date", "{}")));
    assertThrows(
        CallerFault.class,
        () ->
            run(
                new JobRuntime(dispatcher, List.of(read, date)),
                definition("chat", List.of("memory_read", "get_date"), List.of(), true)));
    verify(date, never()).run(anyString(), any());
    verify(dispatcher, times(1)).stream(any(), any(), any());
    verify(transcript, never())
        .record(argThat(entry -> entry.toString().contains("must not escape")));
  }

  @Test
  void file_provider_keeps_definition_grants_subject_and_no_bridge_session() {
    RunProviders providers = mock(RunProviders.class);
    when(providers.forRun(any(), anyList(), any(), any())).thenReturn(List.of());
    var agent =
        definition("chat", List.of("file_roots"), List.of(), true)
            .withScopes(List.of(io.aeyer.plowshare.server.files.Grant.parse("workspace:read")));
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(calls(new ToolCall("one", "file_roots", "{}")), answer());
    var result = run(new JobRuntime(dispatcher, List.of(), null, providers, Instant::now), agent);
    assertEquals(Outcome.Ending.ANSWERED, result.ending());
    verify(providers, atLeastOnce()).forRun(home, agent.scopes(), null, "alice");
    verify(providers, never()).forRun(any(), anyList(), notNull(), any());
  }

  @Test
  void private_child_inherits_retained_authority_and_the_same_budget() {
    var parent = definition("parent", List.of("agent_run"), List.of("child"), true);
    var child = definition("child", List.of("get_date"), List.of(), false);
    var childLog = mock(Transcript.class, CALLS_REAL_METHODS);
    when(childLog.conversationId()).thenReturn("cnv_CHILD");
    when(transcript.delegate(eq(child), eq(home), any())).thenReturn(childLog);
    var registry = new AgentRegistry(Map.of("parent", parent, "child", child));
    var date = tool("get_date");
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            calls(new ToolCall("one", "agent_run", "{\"agent\":\"child\",\"task\":\"Help\"}")),
            calls(new ToolCall("two", "get_date", "{}")),
            answer(),
            answer());
    var outcome =
        run(new JobRuntime(dispatcher, List.of(date), () -> registry, null, Instant::now), parent);
    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    verify(date).run("{}", home);
    verify(transcript).delegate(child, home, "one");
    verify(resources, atLeastOnce())
        .projects(
            new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "cnv_CHILD"));
    assertEquals(4, budget.spent(), "the child does not create a second allowance");
  }

  @Test
  void scoped_resource_authority_refuses_unknown_global_other_personal_and_other_home() {
    var resource = new AccessRequest.Resource(ResourceScopeRepository.Kind.MEMORY, "mem_ONE");
    for (List<String> scope :
        List.of(
            List.<String>of(),
            Collections.<String>singletonList(null),
            List.of("research"),
            List.of("personal:626f62"))) {
      when(resources.projects(resource)).thenReturn(scope);
      assertThrows(CallerFault.class, () -> authority.require(home, List.of(resource)));
    }
    assertThrows(CallerFault.class, () -> authority.require(Home.of("research"), List.of()));
    when(resources.projects(resource)).thenReturn(List.of(home.project()));
    authority.require(home, List.of(resource));
  }

  @Test
  void resource_relocation_during_read_withholds_the_content() {
    var read = tool("memory_read");
    var resource = new AccessRequest.Resource(ResourceScopeRepository.Kind.MEMORY, "mem_LOCAL");
    when(read.run(anyString(), eq(home)))
        .thenAnswer(
            call -> {
              when(resources.projects(resource)).thenReturn(List.of("research"));
              return "foreign content must not escape";
            });
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            calls(new ToolCall("one", "memory_read", "{\"ids\":[\"mem_LOCAL\"]}")), answer());
    run(
        new JobRuntime(dispatcher, List.of(read)),
        definition("chat", List.of("memory_read"), List.of(), true));
    verify(transcript, never())
        .record(argThat(entry -> entry.toString().contains("foreign content must not escape")));
  }

  @Test
  void changing_child_source_at_queued_dispatch_refuses_without_dropping_authority() {
    var parent = definition("parent", List.of("agent_run"), List.of("child"), true);
    var child = definition("child", List.of(), List.of(), false);
    var childLog = mock(Transcript.class, CALLS_REAL_METHODS);
    when(childLog.conversationId()).thenReturn("cnv_CHILD");
    when(transcript.delegate(eq(child), eq(home), any())).thenReturn(childLog);
    var registry =
        new AtomicReference<>(new AgentRegistry(Map.of("parent", parent, "child", child)));
    var calls = new AtomicInteger();
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              if (calls.getAndIncrement() == 0)
                return calls(
                    new ToolCall("one", "agent_run", "{\"agent\":\"child\",\"task\":\"Help\"}"));
              registry.set(
                  new AgentRegistry(
                      Map.of("parent", parent, "child", child.withPrompt("Changed source"))));
              // The actual LlmPool invokes this after queueing, before the transport sends bytes.
              call.<ChatRequest>getArgument(0).authorization().require();
              fail("Changed child definition cannot dispatch");
              return answer();
            });
    var outcome =
        run(new JobRuntime(dispatcher, List.of(), registry::get, null, Instant::now), parent);
    assertEquals(Outcome.Ending.SUB_AGENT_FAILED, outcome.ending());
    assertEquals(2, calls.get());
  }

  @Test
  void child_transcript_has_durable_parent_and_never_falls_back_when_open_fails() {
    var conversations = mock(io.aeyer.plowshare.server.archive.ConversationStore.class);
    var row = mock(io.aeyer.plowshare.server.archive.ConversationRecord.class);
    var child = definition("child", List.of(), List.of(), false);
    when(row.id()).thenReturn("cnv_CHILD");
    when(conversations.log(any(), eq(home), eq("child"), eq("cnv_ROOT"), isNull(), any(), any()))
        .thenReturn(row);
    var compaction =
        new Compaction(
            dispatcher,
            () -> child,
            mock(io.aeyer.plowshare.server.archive.TurnStore.class),
            mock(io.aeyer.plowshare.server.archive.CompactionStore.class),
            mock(io.aeyer.plowshare.server.archive.EntryStore.class),
            4096,
            conversations);
    try {
      var root = compaction.transcriptFor("cnv_ROOT", child, null);
      root.confine(check, "alice");
      var childLog = root.delegate(child, home, "one");
      assertEquals("cnv_CHILD", childLog.conversationId());
      verify(conversations)
          .log(
              io.aeyer.plowshare.server.archive.Origin.DELEGATION,
              home,
              "child",
              "cnv_ROOT",
              null,
              "alice",
              "one");
      allowed.set(false);
      assertThrows(CallerFault.class, childLog::before);
      allowed.set(true);
      when(conversations.log(any(), any(), any(), any(), any(), any(), any()))
          .thenThrow(new IllegalStateException("storage unavailable"));
      assertThrows(IllegalStateException.class, () -> root.delegate(child, home, "two"));
    } finally {
      compaction.close();
    }
  }

  @Test
  void retained_runtime_refuses_an_account_or_client_session_substitution() {
    var definition = definition("chat", List.of(), List.of(), true);
    var runtime =
        new JobRuntime(dispatcher, List.of())
            .forAdmittedTurn(new RunAdmission(check, job -> {}, () -> {}, "alice", authority));
    assertThrows(
        CallerFault.class,
        () ->
            runtime.run(
                definition,
                "Hello",
                home,
                budget,
                () -> false,
                null,
                JobWatch.UNWATCHED,
                transcript,
                TurnCap.of(8),
                List.of(),
                "bob",
                false));
    assertThrows(
        CallerFault.class,
        () ->
            runtime.run(
                definition,
                "Hello",
                home,
                budget,
                () -> false,
                "bridge-session",
                JobWatch.UNWATCHED,
                transcript,
                TurnCap.of(8),
                List.of(),
                "alice",
                false));
    verifyNoInteractions(dispatcher);
  }

  @Test
  void normal_command_gate_executes_open_commands_and_refuses_new_approval_questions(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var environment = directory.resolve("environment.yml");
    var provider = mock(io.aeyer.plowshare.server.files.FileProvider.class, CALLS_REAL_METHODS);
    when(provider.name()).thenReturn("local");
    when(provider.roots()).thenReturn(List.of(directory.toRealPath()));
    when(provider.run(any(), anyList(), any(), any(), any()))
        .thenReturn(
            new io.aeyer.plowshare.protocol.CommandRunner.Outcome(
                0, false, false, "built", 0, "", 0, 5));
    var runtime =
        new JobRuntime(
            dispatcher,
            List.of(),
            null,
            (at, grants, session, owner) -> List.of(provider),
            Instant::now);
    runtime.useEnvironments(new Environments(name -> 7L, id -> environment, null));
    var approvals = mock(io.aeyer.plowshare.server.approvals.RunApprovalStore.class);
    runtime.useApprovals(approvals);
    var definition = definition("chat", List.of("run"), List.of(), true);
    var command = new ToolCall("one", "run", "{\"command\":[\"build-fixture\"]}");
    java.nio.file.Files.writeString(environment, "server:\n  mode: open\n");
    when(dispatcher.stream(any(), any(), any())).thenReturn(calls(command), answer());
    run(runtime, definition);
    verify(provider).run(any(), eq(List.of("build-fixture")), any(), any(), any());
    clearInvocations(provider);
    reset(dispatcher);
    java.nio.file.Files.writeString(environment, "server:\n  mode: ask\n");
    when(dispatcher.stream(any(), any(), any())).thenReturn(calls(command), answer());
    run(runtime, definition);
    verify(provider, never()).run(any(), anyList(), any(), any(), any());
    verifyNoInteractions(approvals);
  }

  @Test
  void malformed_or_ambiguous_resource_ids_never_reach_the_tool() {
    for (String arguments :
        List.of(
            "{}",
            "null",
            "{\"ids\":[\" mem_ONE\"]}",
            "{\"ids\":[\"mem_ONE\"],\"ids\":[\"mem_TWO\"]}",
            "{\"ids\":42}",
            "{\"ids\":[\"mem_ONE\"]} {}")) {
      assertThrows(CallerFault.class, () -> RetainedToolAccess.resources("memory_read", arguments));
    }
    assertEquals(
        List.of(new AccessRequest.Resource(ResourceScopeRepository.Kind.MEMORY, "mem_ONE")),
        RetainedToolAccess.resources("memory_read", "{\"ids\":[\"mem_ONE\"]}"));
  }
}
