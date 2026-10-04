package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoundCommandsTest {
  final CommandInvocations store = mock(CommandInvocations.class);
  final SkillResolver skills = mock(SkillResolver.class);
  final OrchestrationResolver orchestrations = mock(OrchestrationResolver.class);
  final Callers callers = mock(Callers.class);
  final SkillRuntime runtime = mock(SkillRuntime.class);
  final Transcript transcript = mock(Transcript.class);
  final AgentDefinition caller =
      new AgentDefinition("bot", "Bot", "model", List.of(), List.of(), List.of(), 8, 8, "Bot role")
          .withSkills(List.of("review"));
  final Budget budget = Budget.of(8);
  final TurnEnd end = new TurnEnd();
  final UUID id = UUID.randomUUID();
  final BoundCommands commands = new BoundCommands(store, skills, orchestrations, callers, runtime);
  final AtomicReference<CommandInvocations.Bound> stored = new AtomicReference<>();
  final DefinitionResolver.Caller authority = new DefinitionResolver.Caller(null, "session");

  @BeforeEach
  void setup() {
    when(transcript.conversationId()).thenReturn("parent");
    when(transcript.usage())
        .thenReturn(
            UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT)
                .withExecution(
                    UsageLineage.root("parent"),
                    UsageLineage.root("job"),
                    UsageLineage.NONE,
                    "bot",
                    1L,
                    1L));
    when(callers.callerForConversation("parent", "session")).thenReturn(authority);
    when(orchestrations.forCaller(authority)).thenReturn(Map.of());
    when(store.bind(eq("alice"), eq("parent"), eq("job"), eq("bot"), any(), anyString(), any()))
        .thenAnswer(
            call -> {
              CommandCatalog.Entry entry = call.getArgument(4);
              var bound =
                  new CommandInvocations.Bound(
                      "alice",
                      id,
                      "parent",
                      "job",
                      "bot",
                      entry.command(),
                      entry.kind(),
                      entry.name(),
                      entry.hash(),
                      call.getArgument(5),
                      call.getArgument(6),
                      "bound",
                      null);
              stored.set(bound);
              return bound;
            });
    when(store.find(eq("alice"), eq("parent"), eq("bot"), eq(id)))
        .thenAnswer(call -> Optional.ofNullable(stored.get()));
    when(store.claim(any())).thenAnswer(call -> stored.get().state().equals("bound"));
    doAnswer(
            call -> {
              var old = stored.get();
              stored.set(
                  new CommandInvocations.Bound(
                      old.account(),
                      old.id(),
                      old.conversation(),
                      old.sourceRun(),
                      old.caller(),
                      old.command(),
                      old.kind(),
                      old.name(),
                      old.hash(),
                      old.arguments(),
                      old.mode(),
                      call.getArgument(1),
                      call.getArgument(2)));
              return null;
            })
        .when(store)
        .ended(any(), anyString(), anyString());
    offer("mode: NEW\n");
  }

  void offer(String fields) {
    var skill =
        SkillDefinition.parse(
            new DefinitionSource.Definition(
                "review",
                "package/SKILL.md",
                "---\nname: review\ndescription: Review\n" + fields + "---\nInstructions."),
            OrchestrationDefinition.Tier.GLOBAL);
    when(skills.forCaller(authority))
        .thenReturn(
            new SkillResolver.Catalog(
                Map.of("review", new SkillResolver.Resolved(skill, mock(SkillSource.class))),
                Map.of()));
  }

  BoundCommands.Prepared prepare(boolean incoming, String input) {
    return commands.prepare(
        incoming,
        input,
        caller,
        transcript,
        Home.global(),
        budget,
        () -> false,
        "session",
        "alice",
        end,
        new LinkedHashMap<>());
  }

  @Test
  void dispatch_keeps_original_arguments_and_same_receipt_never_runs_twice() {
    var prepared = prepare(true, "/skill:review Review exactly this\nincluding this line");
    assertNull(prepared.refusal());
    assertTrue(prepared.notice().contains("The following command has been invoked"));
    assertTrue(prepared.notice().contains(id.toString()));
    assertEquals("Review exactly this\nincluding this line", stored.get().arguments());
    assertNotNull(prepared.fence("skill_run"));
    assertNotNull(prepared.fence("agent_run"));
    assertNull(prepared.fence("file_read"));
    assertNotNull(prepared.unfinished());
    var tool = prepared.tool();
    assertTrue(
        tool.run("{\"invocation\":\"" + id + "\",\"arguments\":\"replaced\"}", Home.global())
            .contains("only"));
    verifyNoInteractions(runtime);
    when(runtime.dispatch(
            any(),
            any(),
            same(caller),
            same(transcript),
            same(budget),
            any(),
            eq("session"),
            eq("alice"),
            same(end)))
        .thenReturn("Reviewed");
    assertEquals("Reviewed", tool.run("{\"invocation\":\"" + id + "\"}", Home.global()));
    assertNull(prepared.unfinished());
    assertTrue(tool.run("{\"invocation\":\"" + id + "\"}", Home.global()).contains("not replayed"));
    verify(runtime, times(1))
        .dispatch(
            argThat(
                bound ->
                    bound.arguments().contains("including this line") && bound.id().equals(id)),
            any(),
            same(caller),
            same(transcript),
            same(budget),
            any(),
            eq("session"),
            eq("alice"),
            same(end));
  }

  @Test
  void direct_instructions_are_loaded_during_preparation_without_a_model_dispatch_or_replay() {
    offer("mode: DIRECT\n");
    when(runtime.dispatch(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            "Read and execute these instructions.\nInstructions.\nOriginal arguments: request");
    var prepared = prepare(true, "/skill:review request");
    assertNull(prepared.refusal());
    assertNull(prepared.tool());
    assertNull(prepared.unfinished());
    assertTrue(
        prepared.notice().contains("The user has issued the following command: /skill:review"));
    assertTrue(
        prepared.notice().contains("Read the skill instructions below and execute them yourself"));
    assertTrue(prepared.notice().contains("Instructions."));
    assertFalse(prepared.notice().contains("\"definitionHash\""));
    assertTrue(prepared.fence(SkillRuntime.RUN).contains("already activated"));
    var saved = stored.get();
    assertEquals("finished", saved.state());
    when(store.bind(any(), any(), any(), any(), any(), any(), any())).thenReturn(saved);
    var repeated = prepare(true, "/skill:review request");
    assertNull(repeated.refusal());
    assertTrue(repeated.notice().contains("It was not replayed"));
    verify(runtime, times(1))
        .dispatch(
            argThat(bound -> bound.id().equals(id) && bound.arguments().equals("request")),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void direct_activation_failure_is_recorded_and_not_presented_as_ready_to_execute() {
    offer("mode: DIRECT\n");
    when(runtime.dispatch(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("The pinned definition changed. Nothing ran."));
    var prepared = prepare(true, "/skill:review request");
    assertTrue(prepared.refusal().contains("pinned definition changed"));
    assertNull(prepared.notice());
    assertEquals("failed", stored.get().state());
    when(store.pending("alice", "parent", "bot")).thenReturn(List.of(stored.get()));
    assertNotNull(prepare(false, "Resume").refusal());
    verify(runtime, times(1))
        .dispatch(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void direct_commands_require_authority_and_ordinary_text_never_loads_the_skill() {
    offer("mode: DIRECT\n");
    assertNull(prepare(true, "Review this work").notice());
    verifyNoInteractions(runtime);
    doThrow(new IllegalStateException("Project access refused"))
        .when(callers)
        .requireWork(any(), any());
    assertNotNull(prepare(true, "/skill:review request").refusal());
    verifyNoInteractions(runtime);
    verify(store, never()).claim(any());
  }

  @Test
  void invalid_or_ungranted_commands_are_not_downgraded_to_chat_or_given_a_default_mode() {
    assertNotNull(prepare(true, "/skill:unknown request").refusal());
    assertNotNull(prepare(true, "/skill:review").refusal());
    assertNotNull(prepare(true, "/skill:review --mode=DIRECT request").refusal());
    offer("");
    assertNotNull(prepare(true, "/skill:review request").refusal());
    assertNull(prepare(true, "/skill:review --mode=INHERITED request").refusal());
    verifyNoInteractions(runtime);
  }

  @Test
  void continuation_recovers_pending_binding_but_normal_text_never_auto_triggers() {
    var original = prepare(true, "/skill:review request");
    when(store.pending("alice", "parent", "bot")).thenReturn(List.of(stored.get()));
    clearInvocations(store, skills, orchestrations);
    var continuation = prepare(false, "Resume");
    assertEquals(original.notice(), continuation.notice());
    verify(store, never()).bind(any(), any(), any(), any(), any(), any(), any());
    verifyNoInteractions(skills, orchestrations, runtime);
    assertNull(prepare(true, "Review this work please").tool());
  }

  @Test
  void dispatch_failure_is_durable_and_does_not_claim_success() {
    var prepared = prepare(true, "/skill:review request");
    when(runtime.dispatch(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("The definition changed. Nothing ran."));
    assertThrows(
        IllegalStateException.class,
        () -> prepared.tool().run("{\"invocation\":\"" + id + "\"}", Home.global()));
    assertEquals("failed", stored.get().state());
    assertTrue(prepared.unfinished().contains("definition changed"));
  }
}
