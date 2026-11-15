package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises skill delegation through the real runtime, including its retained-authority guard. */
class SkillOperationLineageTest {
  private final Home home = Home.of("fixture");
  private final OperationAuthority operations = mock(OperationAuthority.class);
  private final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
  private final Callers callers = mock(Callers.class);
  private final SkillResolver resolver = mock(SkillResolver.class);
  private final SkillExecutions executions = mock(SkillExecutions.class);
  private final Transcript parent = mock(Transcript.class, CALLS_REAL_METHODS);
  private final Transcript child = mock(Transcript.class, CALLS_REAL_METHODS);
  private final Budget budget = Budget.of(8);
  private final UUID invocation = UUID.randomUUID();
  private final AgentDefinition main =
      new AgentDefinition("main", "Main", "model", List.of(), List.of(), List.of(), 8, 8, "Help")
          .withSkills(List.of("information-discovery"));
  private final AgentDefinition librarian =
      ShippedDefinitions.registry(BoundTools.boundByThisServer()).get("librarian");
  private final JobRuntime runtime =
      new JobRuntime(operations, dispatcher, librarian.tools().stream().map(this::tool).toList());
  private final OperationAuthority.Admission parentOperation =
      admission(OperationExecution.actions(main));
  private final OperationAuthority.Admission childOperation =
      new OperationAuthority.Admission(
          UUID.randomUUID(),
          "alice",
          parentOperation.account(),
          parentOperation.scope(),
          home,
          parentOperation.source(),
          parentOperation.support());
  private final SkillRuntime skills =
      new SkillRuntime(resolver, callers, executions, runtime, ImageStore.NONE);
  private final List<String> recorded = new ArrayList<>();
  private SkillDefinition skill;

  @BeforeEach
  void setup() {
    var authority = new DefinitionResolver.Caller(7L, "session", "alice");
    var catalog =
        SkillResolver.load(
            List.of(
                new SkillResolver.Layer(
                    OrchestrationDefinition.Tier.SHIPPED, SkillSource.shipped())));
    when(resolver.forCaller(authority)).thenReturn(catalog);
    skill = catalog.skills().get("information-discovery").definition();
    when(callers.callerForConversation("parent", "session")).thenReturn(authority);
    when(callers.callerForConversation("child", "session")).thenReturn(authority);
    doNothing().when(parent).recordDurably(any());
    when(callers.readAgent("librarian", authority)).thenReturn(librarian);
    when(callers.visible(home, "session", "alice"))
        .thenReturn(new AgentRegistry(Map.of("librarian", librarian)));
    when(callers.find(home, "session", "alice", "librarian")).thenReturn(Optional.of(librarian));
    when(parent.conversationId()).thenReturn("parent");
    when(child.conversationId()).thenReturn("child");
    when(parent.delegate(eq(librarian), eq(home), isNull())).thenReturn(child);
    doAnswer(
            call -> {
              recorded.add(call.getArgument(0).toString());
              return null;
            })
        .when(parent)
        .record(any());
    when(executions.claim(
            eq("alice"), eq(invocation), anyString(), eq("parent"), eq("librarian"), any(), any()))
        .thenReturn(true);
    when(operations.derive(parentOperation, OperationExecution.actions(librarian)))
        .thenReturn(childOperation);
    runtime.useSkills(skills);
  }

  private AgentTool tool(String name) {
    var tool = mock(AgentTool.class);
    when(tool.schema()).thenReturn(ToolSchema.from(name, "fixture", Map.of("type", "object")));
    return tool;
  }

  private OperationAuthority.Admission admission(Set<AuthorityAction> actions) {
    var account = new AccountId(UUID.randomUUID());
    return new OperationAuthority.Admission(
        UUID.randomUUID(),
        "alice",
        account,
        new ScopeId(UUID.randomUUID()),
        home,
        GrantRecipient.account(account),
        actions.stream()
            .map(action -> new OperationAuthority.Support(action, UUID.randomUUID()))
            .toList());
  }

  @Test
  void modelSelectedLibrarianSkillDerivesTheRetainedParentOperation() {
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            new Completion(
                "",
                "tool_calls",
                TokenUsage.UNKNOWN,
                List.of(
                    new ToolCall(
                        "call",
                        SkillRuntime.RUN,
                        "{\"name\":\"information-discovery\",\"arguments\":\"Find reports\",\"invocation\":\""
                            + invocation
                            + "\"}"))),
            new Completion("Found the reports", "stop", TokenUsage.UNKNOWN, List.of()),
            new Completion("Here are the reports", "stop", TokenUsage.UNKNOWN, List.of()));

    Outcome outcome = run("Find reports");

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals("Here are the reports", outcome.text(), () -> String.join("\n", recorded));
    assertChildLineage();
    assertEquals(3, budget.spent(), "parent and child spend the same allowance");
  }

  @Test
  void explicitLibrarianCommandRetainsTheSameParentOperationWithoutParentInference() {
    var store = mock(CommandInvocations.class);
    var orchestrations = mock(OrchestrationResolver.class);
    var bound =
        new CommandInvocations.Bound(
            "alice",
            invocation,
            "parent",
            "job",
            "main",
            "/skill:information-discovery",
            "skill",
            "information-discovery",
            skill.hash(),
            "Find reports",
            "NEW",
            "bound",
            null);
    var finished =
        new CommandInvocations.Bound(
            bound.account(),
            bound.id(),
            bound.conversation(),
            bound.sourceRun(),
            bound.caller(),
            bound.command(),
            bound.kind(),
            bound.name(),
            bound.hash(),
            bound.arguments(),
            bound.mode(),
            "finished",
            "Found the reports");
    var ended = new AtomicBoolean();
    when(parent.usage())
        .thenReturn(
            UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT)
                .withExecution(
                    UsageLineage.root("parent"),
                    UsageLineage.root("job"),
                    UsageLineage.NONE,
                    "main",
                    1L,
                    1L));
    when(store.bind(any(), any(), any(), any(), any(), any(), any())).thenReturn(bound);
    when(store.find("alice", "parent", "main", invocation))
        .thenAnswer(call -> Optional.of(ended.get() ? finished : bound));
    when(store.claim(bound)).thenReturn(true);
    doAnswer(
            call -> {
              ended.set(true);
              return null;
            })
        .when(store)
        .ended(eq(bound), eq("finished"), anyString());
    runtime.useBoundCommands(new BoundCommands(store, resolver, orchestrations, callers, skills));
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(new Completion("Found the reports", "stop", TokenUsage.UNKNOWN, List.of()));

    Outcome outcome = run("/skill:information-discovery Find reports");

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertTrue(outcome.text().contains("Found the reports"), outcome.text());
    assertChildLineage();
    assertEquals(1, budget.spent(), "the explicit command charges only the child inference");
  }

  @Test
  void missingParentEnvelopeCannotBeRemintedFromAmbientGrants() {
    assertRefused(null, home, "Delegation exceeds its retained parent envelope");
  }

  @Test
  void missingChildCreationAuthorityStillRefuses() {
    assertRefused(
        admission(Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL)),
        home,
        "Delegation exceeds its retained parent envelope");
  }

  @Test
  void anotherExecutionHomeStillRefuses() {
    assertRefused(
        parentOperation, Home.of("other"), "Delegation exceeds its retained parent envelope");
  }

  @Test
  void revokedParentRefusesBeforeOpeningTheChild() {
    doThrow(new CallerFault("Parent authority revoked")).when(operations).require(parentOperation);
    assertRefused(parentOperation, home, "Parent authority revoked");
  }

  private void assertRefused(
      OperationAuthority.Admission operation, Home requestedHome, String error) {
    // Resolve the same definitions; changing an execution home must still fail at the envelope.
    when(callers.visible(requestedHome, "session", "alice"))
        .thenReturn(new AgentRegistry(Map.of("librarian", librarian)));
    when(callers.find(requestedHome, "session", "alice", "librarian"))
        .thenReturn(Optional.of(librarian));
    var tool =
        skills
            .forRun(main, parent, budget, () -> false, "session", "alice", new TurnEnd(), operation)
            .getFirst();
    var failure =
        assertThrows(
            CallerFault.class,
            () ->
                tool.run(
                    "{\"name\":\"information-discovery\",\"arguments\":\"Find reports\",\"invocation\":\""
                        + invocation
                        + "\"}",
                    requestedHome));
    assertEquals(error, failure.getMessage());
    verify(parent, never()).delegate(any(), any(), any());
    verify(operations, never()).derive(any(), any());
    verify(operations, never()).admit(any(), any(), any());
    verify(dispatcher, never()).stream(any(), any(), any());
    verify(executions).failed(eq("alice"), eq(invocation), anyString());
  }

  private Outcome run(String task) {
    return runtime.runOperation(
        main,
        task,
        home,
        budget,
        () -> false,
        "session",
        JobWatch.UNWATCHED,
        parent,
        TurnCap.from(main),
        List.of(),
        "alice",
        true,
        parentOperation);
  }

  private void assertChildLineage() {
    verify(operations).derive(parentOperation, OperationExecution.actions(librarian));
    verify(operations)
        .bind(
            new OperationAuthority.Conversation(
                "child",
                "librarian",
                childOperation,
                "parent",
                budget.snapshot().id(),
                OperationExecution.fingerprint(librarian)));
    verify(operations, never()).admit(any(), any(), any());
    verify(executions).running("alice", invocation, "child");
    verify(child).closed(anyString(), argThat(result -> result.text().equals("Found the reports")));
  }
}
