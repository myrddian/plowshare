package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.images.ImageStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillRuntimeTest {
  final SkillResolver resolver = mock(SkillResolver.class);
  final Callers callers = mock(Callers.class);
  final SkillExecutions executions = mock(SkillExecutions.class);
  final JobRuntime runtime = mock(JobRuntime.class);
  final Transcript parent = mock(Transcript.class);
  final Transcript child = mock(Transcript.class);
  final Budget budget = Budget.of(8);
  final TurnEnd end = new TurnEnd();
  final SkillRuntime skills =
      new SkillRuntime(resolver, callers, executions, runtime, ImageStore.NONE);
  final UUID id = UUID.randomUUID();
  final Home home = Home.of("project");
  final DefinitionResolver.Caller authority = new DefinitionResolver.Caller(7L, "session");
  final AgentDefinition agent =
      new AgentDefinition(
              "worker",
              "Worker",
              "model",
              List.of("file_read"),
              List.of(),
              List.of(),
              4,
              8,
              "Own role")
          .withSkills(List.of("review"));
  final AgentDefinition executor =
      new AgentDefinition(
              "interlocutor",
              "Executor",
              "model",
              List.of("file_read"),
              List.of(),
              List.of(),
              4,
              8,
              "Executor role")
          .withSkills(List.of("*"));

  @BeforeEach
  void setup() {
    when(parent.conversationId()).thenReturn("parent");
    when(child.conversationId()).thenReturn("child");
    when(callers.callerForConversation("parent", "session")).thenReturn(authority);
    when(callers.readAgent("interlocutor", authority)).thenReturn(executor);
    when(runtime.knownTools(org.mockito.ArgumentMatchers.any()))
        .thenReturn(java.util.Set.of("file_read"));
    when(runtime.activity()).thenReturn(RunActivity.NONE);
    when(parent.delegate(any(), eq(home), isNull())).thenReturn(child);
    when(executions.find("alice", id)).thenReturn(Optional.empty());
    when(executions.claim(
            eq("alice"), eq(id), anyString(), eq("parent"), anyString(), any(), any()))
        .thenReturn(true);
  }

  SkillDefinition offer(String fields) {
    return offer(fields, true);
  }

  SkillDefinition offer(String fields, boolean visible) {
    SkillDefinition skill =
        SkillDefinition.parse(
            new DefinitionSource.Definition(
                "review",
                "skills/review/SKILL.md",
                "---\nname: review\ndescription: Review work\nagentVisible: "
                    + visible
                    + "\n"
                    + fields
                    + "---\nPinned specialist instructions."),
            OrchestrationDefinition.Tier.PROJECT);
    SkillSource source = mock(SkillSource.class);
    when(resolver.forCaller(authority))
        .thenReturn(
            new SkillResolver.Catalog(
                Map.of("review", new SkillResolver.Resolved(skill, source)), Map.of()));
    return skill;
  }

  String invoke(AgentDefinition caller, String mode) {
    return skills
        .forRun(caller, parent, budget, () -> false, "session", "alice", end)
        .getFirst()
        .run(
            "{\"name\":\"review\",\"arguments\":\"Inspect the change\",\"invocation\":\""
                + id
                + "\",\"mode\":\""
                + mode
                + "\"}",
            home);
  }

  @Test
  void new_uses_the_shared_budget_and_pins_the_child_before_model_work() {
    offer("mode: NEW\n");
    var outcome = new Outcome(Outcome.Ending.ANSWERED, "Reviewed", 1, 1, "");
    when(runtime.run(
            eq(executor),
            anyString(),
            eq(home),
            same(budget),
            any(),
            eq("session"),
            same(JobWatch.UNWATCHED),
            same(child),
            any(),
            eq(List.of()),
            eq("alice")))
        .thenReturn(outcome);
    assertTrue(invoke(agent, "NEW").contains("Reviewed"));
    var order = inOrder(executions, runtime);
    order
        .verify(executions)
        .claim(
            eq("alice"),
            eq(id),
            anyString(),
            eq("parent"),
            eq("interlocutor"),
            any(),
            eq(SkillDefinition.Mode.NEW));
    order.verify(executions).running("alice", id, "child");
    order
        .verify(runtime)
        .run(
            eq(executor),
            anyString(),
            eq(home),
            same(budget),
            any(),
            eq("session"),
            same(JobWatch.UNWATCHED),
            same(child),
            any(),
            eq(List.of()),
            eq("alice"));
    verify(child).closed(anyString(), eq(outcome));
  }

  @Test
  void direct_runs_under_the_current_agent_and_keeps_its_role_and_grants() {
    SkillDefinition skill = offer("mode: DIRECT\nallowed-tools: file_read\n");
    assertTrue(invoke(agent, "DIRECT").contains("Pinned specialist instructions"));
    verify(executions).running("alice", id, "parent");
    verify(callers, never()).readAgent(anyString(), any());
    verify(parent, never()).delegate(any(), any(), any());
    verify(runtime, never())
        .run(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    when(executions.active("parent"))
        .thenReturn(
            List.of(
                new SkillExecutions.Execution(
                    "alice",
                    id,
                    "input",
                    "parent",
                    "parent",
                    "worker",
                    skill,
                    SkillDefinition.Mode.DIRECT,
                    "awaiting",
                    null)));
    var resumed = skills.decorate("parent", agent);
    assertTrue(resumed.prompt().startsWith("Own role"));
    assertTrue(resumed.prompt().contains("Pinned specialist instructions"));
    assertEquals(agent.scopes(), resumed.scopes());
    assertEquals(agent.skills(), resumed.skills());
    assertNotNull(skills.refusal("parent", "run"));
    assertNull(skills.refusal("parent", "file_read"));
    assertNull(skills.refusal("parent", "skill_read"));
  }

  @Test
  void a_receipt_is_inspected_without_resolving_or_replaying_work() {
    offer("mode: DIRECT\n");
    AtomicReference<SkillExecutions.Execution> saved = new AtomicReference<>();
    doAnswer(
            invocation -> {
              saved.set(
                  new SkillExecutions.Execution(
                      "alice",
                      id,
                      invocation.getArgument(2),
                      "parent",
                      "parent",
                      "worker",
                      invocation.getArgument(5),
                      SkillDefinition.Mode.DIRECT,
                      "running",
                      null));
              return true;
            })
        .when(executions)
        .claim(eq("alice"), eq(id), anyString(), eq("parent"), anyString(), any(), any());
    invoke(agent, "DIRECT");
    when(executions.find("alice", id)).thenReturn(Optional.of(saved.get()));
    clearInvocations(resolver, executions, parent, runtime);
    assertTrue(invoke(agent, "DIRECT").contains("not replayed"));
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
    verifyNoInteractions(resolver, runtime);
    verify(parent, never()).delegate(any(), any(), any());
    assertTrue(invoke(agent, "NEW").contains("different request"));
  }

  @Test
  void grants_and_unimplemented_modes_refuse_before_a_claim_or_child() {
    offer("");
    assertTrue(
        skills
            .forRun(
                agent.withSkills(List.of()), parent, budget, () -> false, "session", "alice", end)
            .isEmpty());
    assertTrue(invoke(agent, "NEW").contains("configured context mode"));
    offer("mode: INHERITED\n");
    assertTrue(invoke(agent, "INHERITED").contains("Nothing ran"));
    offer("mode: SUMMARISED\n");
    assertTrue(invoke(agent, "SUMMARISED").contains("Nothing ran"));
    offer("mode: NEW\n");
    when(callers.readAgent("interlocutor", authority)).thenReturn(executor.withSkills(List.of()));
    assertTrue(invoke(agent, "NEW").contains("executor is not granted"));
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
    verify(parent, never()).delegate(any(), any(), any());
  }

  @Test
  void
      descriptions_are_disclosed_only_for_granted_model_visible_skills_without_loading_instructions() {
    offer("mode: DIRECT\n", false);
    assertNull(skills.discovery(agent, home, "session", "parent", "alice"));
    offer("mode: DIRECT\n");
    String catalog = skills.discovery(agent, home, "session", "parent", "alice");
    assertTrue(catalog.contains("Review work"));
    assertFalse(catalog.contains("Pinned specialist instructions"));
    assertNull(
        skills.discovery(agent.withSkills(List.of("other")), home, "session", "parent", "alice"));
    clearInvocations(resolver);
    assertNull(skills.discovery(agent.withSkills(List.of()), home, "session", "parent", "alice"));
    verifyNoInteractions(resolver);
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void hidden_skills_refuse_model_selection_but_explicit_bound_commands_still_run() {
    SkillDefinition hidden = offer("mode: DIRECT\n", false);
    assertTrue(invoke(agent, "DIRECT").contains("hidden from model invocation"));
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
    var bound =
        new CommandInvocations.Bound(
            "alice",
            id,
            "parent",
            "run",
            "worker",
            "/skill:review",
            "skill",
            "review",
            hidden.hash(),
            "Inspect the change",
            "DIRECT",
            "bound",
            null);
    assertTrue(
        skills
            .dispatch(bound, home, agent, parent, budget, () -> false, "session", "alice", end)
            .contains("Pinned specialist instructions"));
    verify(executions).running("alice", id, "parent");
  }

  @Test
  void bound_portable_direct_skill_accepts_empty_input_and_preserves_exact_user_data() {
    var skill = offer("", false);
    for (String input : List.of("", "  Inspect this\nexact change  ")) {
      var bound =
          new CommandInvocations.Bound(
              "alice",
              id,
              "parent",
              "run",
              "worker",
              "/skill:review",
              "skill",
              "review",
              skill.hash(),
              input,
              "DIRECT",
              "bound",
              null);
      String result =
          skills.dispatch(bound, home, agent, parent, budget, () -> false, "session", "alice", end);
      assertTrue(result.contains("Pinned specialist instructions"));
      assertTrue(result.endsWith("Invocation arguments (user data):\n" + input));
    }
    verify(parent, never()).delegate(any(), any(), any());
  }

  @Test
  void malformed_skill_inputs_refuse_before_claiming() {
    offer("mode: DIRECT\n");
    var tool =
        skills.forRun(agent, parent, budget, () -> false, "session", "alice", end).getFirst();
    for (String input : List.of("null", "42", "[]")) {
      String result =
          tool.run(
              "{\"name\":\"review\",\"arguments\":" + input + ",\"invocation\":\"" + id + "\"}",
              home);
      assertTrue(result.contains("not a string"));
    }
    assertTrue(
        tool.run("{\"name\":\"review\",\"invocation\":\"" + id + "\"}", home).contains("missing"));
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void becoming_hidden_after_discovery_refuses_a_new_model_invocation() {
    offer("mode: DIRECT\n");
    assertNotNull(skills.discovery(agent, home, "session", "parent", "alice"));
    offer("mode: DIRECT\n", false);
    assertTrue(invoke(agent, "DIRECT").contains("hidden from model invocation"));
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void execution_and_next_context_inspection_receive_the_same_scoped_discovery_prompt() {
    offer("mode: DIRECT\n");
    var host =
        new JobRuntime(mock(io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher.class), List.of());
    host.useSkills(skills);
    String prompt = host.withAgentRules(agent, home, "session", "parent").prompt();
    assertTrue(prompt.startsWith("Own role"));
    assertTrue(prompt.contains("Review work"));
    assertFalse(prompt.contains("Pinned specialist instructions"));
    offer("mode: DIRECT\n", false);
    assertEquals("Own role", host.withAgentRules(agent, home, "session", "parent").prompt());
    verify(executions, never()).claim(any(), any(), any(), any(), any(), any(), any());
  }
}
