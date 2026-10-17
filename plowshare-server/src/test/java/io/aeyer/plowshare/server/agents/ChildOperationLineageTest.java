package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Existing budgets/calls stay independent of the account's human administrative authority. */
class ChildOperationLineageTest {
  final OperationAuthority operations = mock(OperationAuthority.class);
  final Home home = Home.of("one");
  final AgentDefinition leaf =
      new AgentDefinition(
          "child", "child", "fast", List.of(), List.of(), List.of(), 3, 999, "Help");

  OperationAuthority.Admission admission(Set<AuthorityAction> actions) {
    var account = new AuthorityIds.AccountId(UUID.randomUUID());
    return new OperationAuthority.Admission(
        UUID.randomUUID(),
        "executor",
        account,
        new AuthorityIds.ScopeId(UUID.randomUUID()),
        home,
        GrantRecipient.account(account),
        actions.stream()
            .map(action -> new OperationAuthority.Support(action, UUID.randomUUID()))
            .toList());
  }

  @Test
  void child_binding_retains_distinct_identity_and_the_same_shared_allowance() {
    var parent =
        admission(
            Set.of(
                AuthorityAction.READ,
                AuthorityAction.WRITE,
                AuthorityAction.RUN_MODEL,
                AuthorityAction.CONVERSATION_CREATE));
    var childAdmission = admission(OperationExecution.actions(leaf));
    when(operations.derive(parent, OperationExecution.actions(leaf))).thenReturn(childAdmission);
    var child = mock(Transcript.class);
    when(child.conversationId()).thenReturn("child-conversation");
    var budget = Budget.of(2);
    budget.bindConversation("source-conversation", claim -> {});
    assertTrue(budget.trySpend());
    var runtime = new JobRuntime(operations, mock(LlmDispatcher.class), List.of());
    assertSame(
        childAdmission,
        runtime.childOperation(parent, "parent-conversation", child, leaf, home, budget));
    verify(operations)
        .bind(
            new OperationAuthority.Conversation(
                "child-conversation",
                "child",
                childAdmission,
                "parent-conversation",
                "source-conversation",
                OperationExecution.fingerprint(leaf)));
    assertEquals(1, budget.spent());
    assertEquals(2, budget.limit());
    assertEquals(999, leaf.maxModelCalls(), "the child definition does not replace its allowance");
  }

  @Test
  void child_creation_refuses_missing_parent_actions_home_or_durable_identity() {
    var runtime = new JobRuntime(operations, mock(LlmDispatcher.class), List.of());
    var parent = admission(OperationExecution.actions(leaf));
    assertThrows(CallerFault.class, () -> runtime.requireChild(parent, leaf, home));
    var complete =
        admission(
            Set.of(
                AuthorityAction.READ,
                AuthorityAction.WRITE,
                AuthorityAction.RUN_MODEL,
                AuthorityAction.CONVERSATION_CREATE));
    assertThrows(CallerFault.class, () -> runtime.requireChild(complete, leaf, Home.of("other")));
    assertThrows(
        CallerFault.class,
        () ->
            runtime.childOperation(complete, "parent", Transcript.NONE, leaf, home, Budget.of(2)));
    verify(operations, never()).derive(any(), any());
    verify(operations, never()).bind(any());
  }

  @Test
  void continuation_uses_the_envelope_and_refuses_replaced_account_agent_home_or_budget() {
    var budget = Budget.of(2);
    budget.bindConversation("original-source", claim -> {});
    var envelope = admission(OperationExecution.actions(leaf));
    var bound =
        new OperationAuthority.Conversation(
            "child-conversation",
            "child",
            envelope,
            "parent",
            "original-source",
            OperationExecution.fingerprint(leaf));
    when(operations.conversation("child-conversation")).thenReturn(Optional.of(bound));
    when(operations.derive(envelope, envelope.actions())).thenReturn(envelope);
    assertSame(
        envelope,
        OperationExecution.admit(
            operations, "executor", home, leaf, "child-conversation", Origin.DELEGATION, budget));
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.admit(
                operations,
                "creator",
                home,
                leaf,
                "child-conversation",
                Origin.DELEGATION,
                budget));
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.admit(
                operations,
                "executor",
                Home.of("other"),
                leaf,
                "child-conversation",
                Origin.DELEGATION,
                budget));
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.requireBinding(
                bound,
                "executor",
                home,
                new AgentDefinition(
                    "replacement-agent",
                    "replacement",
                    "fast",
                    List.of(),
                    List.of(),
                    List.of(),
                    3,
                    3,
                    "Help"),
                budget));
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.admit(
                operations,
                "executor",
                home,
                leaf,
                "child-conversation",
                Origin.DELEGATION,
                Budget.of(999)));
    verify(operations, never()).admit(any(), any(), any());
  }

  @Test
  void old_independent_consumers_cannot_adopt_new_grants_or_allowances() {
    for (var origin : List.of(Origin.ORCHESTRATION, Origin.DELEGATION))
      assertThrows(
          CallerFault.class,
          () ->
              OperationExecution.admit(
                  operations, "executor", home, leaf, "legacy", origin, Budget.of(2)));
    verify(operations, never()).admit(any(), any(), any());
  }

  @Test
  void real_parent_child_runtime_shares_claims_and_withholds_results_after_ancestor_revocation() {
    for (boolean revoke : List.of(false, true)) {
      reset(operations);
      var dispatcher = mock(LlmDispatcher.class);
      var parent =
          new AgentDefinition(
              "parent",
              "parent",
              "fast",
              List.of("agent_run"),
              List.of("child"),
              List.of(),
              5,
              3,
              "Delegate");
      var registry = new AgentRegistry(Map.of("parent", parent, "child", leaf));
      var original = admission(OperationExecution.actions(parent));
      var childAdmission = admission(OperationExecution.actions(leaf));
      when(operations.derive(original, OperationExecution.actions(leaf)))
          .thenReturn(childAdmission);
      var live = new java.util.concurrent.atomic.AtomicBoolean(true);
      doAnswer(
              call -> {
                if (!live.get()) throw new CallerFault("ancestor revoked");
                return null;
              })
          .when(operations)
          .require(any());
      var claims = mock(InferenceCalls.class);
      var runtime =
          new JobRuntime(
              dispatcher,
              List.of(),
              () -> registry,
              null,
              java.time.Instant::now,
              Reminding.NONE,
              io.aeyer.plowshare.server.images.ImageStore.NONE,
              RefusalDetector.NEVER,
              System::nanoTime,
              AgentDelegates.fixed(() -> registry),
              operations,
              claims);
      var parentLog = mock(Transcript.class, CALLS_REAL_METHODS);
      var childLog = mock(Transcript.class, CALLS_REAL_METHODS);
      when(parentLog.conversationId()).thenReturn("parent-log");
      when(childLog.conversationId()).thenReturn("child-log");
      when(parentLog.delegate(eq(leaf), eq(home), any())).thenReturn(childLog);
      var budget = Budget.of(3);
      budget.bindConversation("source", claim -> {});
      var observed = new ArrayList<Budget.Claim>();
      when(claims.claimed(any(OperationAuthority.Admission.class), any()))
          .thenAnswer(
              call -> {
                observed.add(call.getArgument(1));
                OperationAuthority.Admission admitted = call.getArgument(0);
                return (io.aeyer.plowshare.server.llm.dispatch.CallAuthorization)
                    () -> operations.require(admitted);
              });
      var callNumber = new java.util.concurrent.atomic.AtomicInteger();
      when(dispatcher.stream(any(), any(), any()))
          .thenAnswer(
              call -> {
                int n = callNumber.incrementAndGet();
                if (n == 1)
                  return new io.aeyer.plowshare.server.llm.dispatch.Completion(
                      null,
                      "tool_calls",
                      io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                      List.of(
                          new io.aeyer.plowshare.protocol.ToolCall(
                              "delegate", "agent_run", "{\"agent\":\"child\",\"task\":\"Help\"}")));
                if (n == 2 && revoke) live.set(false);
                return new io.aeyer.plowshare.server.llm.dispatch.Completion(
                    n == 2 ? "private child answer" : "done",
                    "stop",
                    io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                    List.of());
              });
      org.junit.jupiter.api.function.Executable execute =
          () ->
              runtime.runOperation(
                  parent,
                  "Help",
                  home,
                  budget,
                  () -> false,
                  null,
                  JobWatch.UNWATCHED,
                  parentLog,
                  TurnCap.of(5),
                  List.of(),
                  "executor",
                  false,
                  original);
      if (revoke) {
        assertThrows(CallerFault.class, execute);
        verify(childLog, never())
            .record(argThat(entry -> entry.toString().contains("private child answer")));
        verify(parentLog, never())
            .record(argThat(entry -> entry.toString().contains("private child answer")));
        assertEquals(2, budget.spent());
      } else {
        assertDoesNotThrow(execute);
        assertEquals(3, budget.spent());
      }
      verify(operations)
          .bind(
              new OperationAuthority.Conversation(
                  "child-log",
                  "child",
                  childAdmission,
                  "parent-log",
                  "source",
                  OperationExecution.fingerprint(leaf)));
      assertEquals(budget.spent(), observed.size());
      assertEquals(1, observed.stream().map(Budget.Claim::budget).distinct().count());
      assertEquals(
          List.of(1, 2), observed.subList(0, 2).stream().map(Budget.Claim::ordinal).toList());
      assertTrue(
          observed.stream()
              .allMatch(claim -> claim.limit() == 3 && "source".equals(claim.conversation())));
      verify(claims).claimed(eq(childAdmission), any());
    }
  }

  @Test
  void resumed_definition_cannot_add_capabilities_under_the_same_agent_name() {
    var budget = Budget.of(3);
    var envelope = admission(OperationExecution.actions(leaf));
    var bound =
        new OperationAuthority.Conversation(
            "child",
            "child",
            envelope,
            "parent",
            budget.reference(),
            OperationExecution.fingerprint(leaf));
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.requireBinding(
                bound, "executor", home, leaf.withTools(List.of("file_write")), budget));
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.requireBinding(
                bound,
                "executor",
                home,
                leaf.withScopes(
                    List.of(io.aeyer.plowshare.server.files.Grant.parse("workspace:write"))),
                budget));
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.requireBinding(
                bound, "executor", home, leaf.withCalls(List.of("elevated")), budget));
    assertEquals(
        OperationExecution.fingerprint(leaf),
        OperationExecution.fingerprint(leaf.withCaps(2, 2)),
        "existing operator limits do not replace the capability ceiling or budget source");
  }

  @Test
  void
      native_intermediary_needs_no_inference_action_and_cannot_acquire_one_from_ambient_authority() {
    var nativeChild = mock(AgentDefinition.class);
    when(nativeChild.nativeOnly()).thenReturn(true);
    when(nativeChild.calls()).thenReturn(List.of("descendant"));
    var nativeActions =
        Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.CONVERSATION_CREATE);
    assertEquals(nativeActions, OperationExecution.actions(nativeChild));
    var nativeParent = admission(nativeActions);
    assertEquals(nativeActions, OperationExecution.childActions(nativeParent, nativeChild));
    var mixedParent =
        admission(
            Set.of(
                AuthorityAction.READ,
                AuthorityAction.WRITE,
                AuthorityAction.CONVERSATION_CREATE,
                AuthorityAction.RUN_MODEL));
    assertEquals(mixedParent.actions(), OperationExecution.childActions(mixedParent, nativeChild));
    verifyNoInteractions(operations);
  }
}
