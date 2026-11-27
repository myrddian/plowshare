package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.access.OperationAuthority.Support;
import io.aeyer.plowshare.server.goals.GoalAgents;
import io.aeyer.plowshare.server.goals.GoalHandoffs;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoalHandoffSkillTest {
  private final Home home = Home.of("project");
  private final SkillResolver resolver = mock(SkillResolver.class);
  private final Callers callers = mock(Callers.class);
  private final SkillExecutions executions = mock(SkillExecutions.class);
  private final GoalHandoffs handoffs = mock(GoalHandoffs.class);
  private final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
  private final JobRuntime runtime =
      new JobRuntime(mock(OperationAuthority.class), dispatcher, List.of());
  private final SkillRuntime skills =
      new SkillRuntime(
          resolver,
          callers,
          executions,
          runtime,
          ImageStore.NONE,
          () -> handoffs,
          this::originalAdmission);
  private final AgentDefinition caller =
      new AgentDefinition(
              "caller",
              "Caller",
              "model",
              List.of("result_list"),
              List.of("worker"),
              List.of(),
              8,
              8,
              "Help")
          .withSkills(List.of("goal"));
  private final Transcript transcript = mock(Transcript.class, CALLS_REAL_METHODS);
  private final UUID invocation = UUID.randomUUID();
  private final Admission admission = admission();
  private final GoalHandoffs.Caller binding = new GoalHandoffs.Caller("parent", caller, admission);
  private final GoalAgents.Start request =
      new GoalAgents.Start(
          home.project(), "worker", "Review the release notes", "Cite missing items", invocation);
  private final AtomicReference<String> skillState = new AtomicReference<>();
  private SkillDefinition skill;

  @BeforeEach
  void bindSkill() {
    var scope = new DefinitionResolver.Caller(7L, null, "alice");
    var catalog =
        SkillResolver.load(
            List.of(
                new SkillResolver.Layer(
                    OrchestrationDefinition.Tier.SHIPPED, SkillSource.shipped())));
    skill = catalog.skills().get("goal").definition();
    assertEquals(SkillDefinition.Mode.DIRECT, skill.mode());
    assertTrue(skill.agentVisible());
    when(resolver.forCaller(scope)).thenReturn(catalog);
    when(callers.callerForConversation("parent", null)).thenReturn(scope);
    when(transcript.conversationId()).thenReturn("parent");
    when(transcript.usage())
        .thenReturn(
            UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT)
                .withExecution(
                    UsageLineage.root("parent"),
                    UsageLineage.root("job"),
                    UsageLineage.NONE,
                    "caller",
                    1L,
                    1L));
    doNothing().when(transcript).recordDurably(any());
    when(handoffs.enabled()).thenReturn(true);
    when(executions.claimHandoff(
            eq("alice"), eq(invocation), anyString(), eq("parent"), eq("caller"), eq(skill), any()))
        .thenReturn(true);
    doAnswer(
            call -> {
              skillState.set("running");
              return null;
            })
        .when(executions)
        .running("alice", invocation, "parent");
    doAnswer(
            call -> {
              Outcome outcome = call.getArgument(1);
              skillState.set(outcome.ending() == Outcome.Ending.AWAITING ? "awaiting" : null);
              return null;
            })
        .when(executions)
        .closed(eq("parent"), any());
    when(executions.active("parent"))
        .thenAnswer(
            call ->
                skillState.get() == null
                    ? List.of()
                    : List.of(
                        new SkillExecutions.Execution(
                            "alice",
                            invocation,
                            "request",
                            "parent",
                            "parent",
                            "caller",
                            skill,
                            SkillDefinition.Mode.DIRECT,
                            skillState.get(),
                            null,
                            originalHandoff())));
    when(handoffs.start(binding, request))
        .thenReturn(
            new GoalAgents.Started(UUID.randomUUID(), "goal-log", "goal-inbox", 1, invocation));
    runtime.useSkills(skills);
  }

  @Test
  void explicitCommandCanClarifyThenCommitsUnderTheOriginalCallingDefinition() {
    when(transcript.speaker()).thenReturn(Speaker.person("alice"));
    var commands = mock(CommandInvocations.class);
    var orchestrations = mock(OrchestrationResolver.class);
    var bound = command("bound");
    var state = new AtomicReference<>(bound);
    when(commands.bind(any(), any(), any(), any(), any(), any(), any())).thenReturn(bound);
    when(commands.find("alice", "parent", "caller", invocation))
        .thenAnswer(call -> Optional.of(state.get()));
    when(commands.claim(bound)).thenReturn(true);
    doAnswer(
            call -> {
              state.set(command("finished"));
              return null;
            })
        .when(commands)
        .ended(eq(bound), eq("finished"), anyString());
    runtime.useBoundCommands(
        new BoundCommands(commands, resolver, orchestrations, callers, skills));
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            tool(
                "question", GoalSkillTools.CLARIFY, "{\"question\":\"What would count as done?\"}"),
            startTool());

    var first = run("/skill:goal Review the release notes");
    verify(executions)
        .claimHandoff(
            eq("alice"),
            eq(invocation),
            anyString(),
            eq("parent"),
            eq("caller"),
            eq(skill),
            eq(
                new SkillExecutions.Handoff(
                    admission.id(), OperationExecution.fingerprint(caller), true, null)));
    assertEquals(Outcome.Ending.AWAITING, first.ending(), first.text());
    assertEquals("What would count as done?", first.text());
    var later =
        new Admission(
            UUID.randomUUID(),
            admission.executor(),
            admission.account(),
            admission.scope(),
            admission.home(),
            admission.source(),
            admission.support());
    var settled = run("Cite missing items", later);
    assertEquals(Outcome.Ending.ANSWERED, settled.ending(), settled.text());
    assertTrue(settled.text().contains("Message address: goal-inbox"), settled.text());
    verify(handoffs).start(binding, request);
  }

  @Test
  void modelSelectedInvocationUsesItsOwnRetainedUuid() {
    when(transcript.speaker()).thenReturn(Speaker.person("alice"));
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            tool(
                "select",
                SkillRuntime.RUN,
                """
            {"name":"goal","arguments":"Review the release notes","invocation":"%s"}
            """
                    .formatted(invocation)),
            startTool());

    var settled = run("Hand off a review of the release notes");
    assertEquals(Outcome.Ending.ANSWERED, settled.ending(), settled.text());
    assertTrue(settled.text().contains("Goal conversation: goal-log"), settled.text());
    verify(executions)
        .claimHandoff(
            eq("alice"),
            eq(invocation),
            anyString(),
            eq("parent"),
            eq("caller"),
            eq(skill),
            eq(originalHandoff()));
    verify(handoffs).start(binding, request);
  }

  @Test
  void handoffToolRefusesWithoutAnActiveInvocationOrWithAForgedProject() {
    var start =
        skills.goalTools(caller, transcript, null, "alice", new TurnEnd(), admission).stream()
            .filter(tool -> tool.schema().name().equals(GoalSkillTools.START))
            .findFirst()
            .orElseThrow();
    assertThrows(
        ToolArguments.BadArguments.class,
        () -> start.run(startTool().toolCalls().getFirst().arguments(), home));
    skillState.set("running");
    assertThrows(
        ToolArguments.BadArguments.class,
        () ->
            start.run(
                "{\"agent\":\"worker\",\"objective\":\"Review\",\"project\":\"other\"}", home));
  }

  private CommandInvocations.Bound command(String state) {
    return new CommandInvocations.Bound(
        "alice",
        invocation,
        "parent",
        "job",
        "caller",
        "/skill:goal",
        "skill",
        "goal",
        skill.hash(),
        "Review the release notes",
        "DIRECT",
        state,
        null);
  }

  private Optional<Admission> originalAdmission(UUID id) {
    return admission.id().equals(id) ? Optional.of(admission) : Optional.empty();
  }

  private SkillExecutions.Handoff originalHandoff() {
    return new SkillExecutions.Handoff(admission.id(), OperationExecution.fingerprint(caller));
  }

  private Outcome run(String text) {
    return run(text, admission);
  }

  private Outcome run(String text, Admission current) {
    return runtime.runOperation(
        caller,
        text,
        home,
        Budget.of(8),
        () -> false,
        null,
        JobWatch.UNWATCHED,
        transcript,
        TurnCap.from(caller),
        List.of(),
        "alice",
        true,
        current);
  }

  private Admission admission() {
    var account = new AccountId(UUID.randomUUID());
    return new Admission(
        UUID.randomUUID(),
        "alice",
        account,
        new ScopeId(UUID.randomUUID()),
        home,
        GrantRecipient.account(account),
        OperationExecution.actions(caller).stream()
            .map(action -> new Support(action, UUID.randomUUID()))
            .toList());
  }

  private static Completion startTool() {
    return tool(
        "start",
        GoalSkillTools.START,
        """
        {"agent":"worker","objective":"Review the release notes","criteria":"Cite missing items"}
        """);
  }

  private static Completion tool(String id, String name, String arguments) {
    return new Completion(
        "", "tool_calls", TokenUsage.UNKNOWN, List.of(new ToolCall(id, name, arguments)));
  }
}
