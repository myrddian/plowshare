package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.access.OperationRecords;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationLogAccess;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrationReceipts.Child;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrationReceipts.Key;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Caller;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Owner;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Request;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Run;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class RetainedGoalOrchestrationsTest {
  private static final String NAME = "research";
  private static final String HASH = "sha256:pinned";
  private static final String TASK = "  Run the review as written.  ";
  private final UUID invocation = UUID.randomUUID();
  private final Runnable requireArmedWait = mock(Runnable.class);
  private final Owner owner = new Owner("cnv_goal", "worker", "enzo", UUID.randomUUID());
  private final Home home = Home.of("story");
  private final Key key = new Key(owner, invocation, home.project(), NAME, HASH);
  private final Child child = new Child("orc_child", "cnv_child");
  private final Run result =
      new Run(
          child.id(),
          child.conversation(),
          "finished",
          Instant.parse("2026-11-27T10:20:00Z"),
          "report");
  private final ConversationDefinitions definitions = mock(ConversationDefinitions.class);
  private final GrantedOrchestrations grants = mock(GrantedOrchestrations.class);
  private final OperationAuthority authority = mock(OperationAuthority.class);
  private final OperationRecords operations = mock(OperationRecords.class);
  private final InformationLogAccess information = mock(InformationLogAccess.class);
  private final GoalOrchestrationReceipts receipts = mock(GoalOrchestrationReceipts.class);
  private final RetainedGoalOrchestrations.Start starts =
      mock(RetainedGoalOrchestrations.Start.class);
  private final RetainedGoalOrchestrations.Cancel cancels =
      mock(RetainedGoalOrchestrations.Cancel.class);
  private final GoalOrchestrations port =
      new RetainedGoalOrchestrations(
          definitions, grants, authority, operations, information, receipts, starts, cancels);
  private final AgentDefinition pinned = mock(AgentDefinition.class);
  private final AgentDefinition current = mock(AgentDefinition.class);
  private final Admission admitted = mock(Admission.class);
  private final Caller caller = new Caller(owner.conversation(), pinned, admitted);
  private final DefinitionResolver.Caller lookup = new DefinitionResolver.Caller(7L, null, "enzo");

  @BeforeEach
  void caller() {
    when(pinned.name()).thenReturn(owner.agent());
    when(pinned.orchestrations()).thenReturn(List.of(NAME));
    when(admitted.id()).thenReturn(owner.admission());
    when(admitted.executor()).thenReturn(owner.executor());
    when(admitted.home()).thenReturn(home);
    when(admitted.actions())
        .thenReturn(Set.of(AuthorityAction.CONVERSATION_CREATE, AuthorityAction.READ));
    when(current.name()).thenReturn(owner.agent());
    when(definitions.callerForConversation(owner.conversation(), null)).thenReturn(lookup);
    when(definitions.readAgent(owner.agent(), lookup)).thenReturn(current);
  }

  @Test
  void startsTheExactGrantAsAnAdmissionBoundRootAndPreservesTheTask() {
    var definition = granted();
    var started = mock(OrchestrationRecord.class);
    when(started.id()).thenReturn(child.id());
    when(starts.start(any(), eq(admitted), eq(key), eq(requireArmedWait))).thenReturn(started);
    assertEquals(
        Optional.of(new GoalOrchestrations.Definition(NAME, HASH)), port.granted(caller, NAME));
    assertEquals(
        child.id(),
        port.start(caller, new Request(invocation, NAME, TASK, HASH), requireArmedWait));
    var open = ArgumentCaptor.forClass(Orchestrations.Start.class);
    verify(starts).start(open.capture(), eq(admitted), eq(key), eq(requireArmedWait));
    assertSame(definition, open.getValue().definition());
    assertEquals(home, open.getValue().home());
    assertEquals(TASK, open.getValue().request());
    assertEquals(owner.conversation(), open.getValue().callerConversation());
    assertEquals(owner.agent(), open.getValue().callerAgent());
    assertEquals(owner.executor(), open.getValue().callerHandle());
    assertNull(open.getValue().parent());
    assertNull(open.getValue().authenticatedWebTask());
  }

  @Test
  void changedDefinitionAndRemovedLiveGrantRefuseBeforeDispatch() {
    granted();
    assertThrows(
        CallerFault.class,
        () ->
            port.start(
                caller, new Request(invocation, NAME, TASK, "sha256:changed"), requireArmedWait));
    assertTrue(port.stillGranted(caller, NAME, HASH));
    when(grants.granted(current, lookup, NAME)).thenReturn(Optional.empty());
    assertFalse(port.stillGranted(caller, NAME, HASH));
    assertThrows(CallerFault.class, () -> port.inspect(caller, invocation, NAME, HASH));
    verifyNoInteractions(starts, receipts, information);
  }

  @Test
  void inspectionCannotReplayAnUnconfirmedStartOrDiscloseAWithdrawnLog() {
    granted();
    assertTrue(port.inspect(caller, invocation, NAME, HASH).isEmpty());
    when(receipts.find(key)).thenReturn(Optional.of(result));
    bindChild(owner.admission());
    doThrow(new NotFoundFault("log inputs withdrawn"))
        .when(information)
        .requireLog(child.conversation(), owner.executor());
    assertThrows(NotFoundFault.class, () -> port.inspect(caller, invocation, NAME, HASH));
    verifyNoInteractions(starts);
  }

  @Test
  void terminalRecheckRequiresExactAttemptAndFreshDisclosureWithoutFetchingText() {
    bindChild(owner.admission());
    assertFalse(port.stillTerminal(caller, invocation, NAME, HASH, result));
    when(receipts.stillTerminal(key, child, result.state(), result.endedAt())).thenReturn(true);
    assertTrue(port.stillTerminal(caller, invocation, NAME, HASH, result));
    verify(information).requireLog(child.conversation(), owner.executor());
    verifyNoInteractions(starts, definitions, grants);
    verify(receipts, never()).find(any());
  }

  @Test
  void cancellationUsesImmutableLineageAndNeverSubstitutesAnAccountOwnedRun() {
    when(operations.find(owner.admission())).thenReturn(Optional.of(admitted));
    when(receipts.active(key)).thenReturn(Optional.of(child));
    bindChild(UUID.randomUUID());
    assertFalse(port.cancel(owner, invocation, NAME, HASH));
    verifyNoInteractions(cancels);
    bindChild(owner.admission());
    when(cancels.cancel(child.id(), "Goal " + owner.conversation())).thenReturn(true);
    assertTrue(port.cancel(owner, invocation, NAME, HASH));
    verifyNoInteractions(authority, definitions, grants, information);
  }

  @Test
  void boundedRequestsRefuseMalformedNamesAndTaskText() {
    for (String name : List.of(" work", "work\nnow", "w".repeat(1025))) {
      assertThrows(CallerFault.class, () -> new Request(invocation, name, TASK, HASH));
    }
    for (String request : List.of(" ", "work\0now", "w".repeat(16001))) {
      assertThrows(CallerFault.class, () -> new Request(invocation, NAME, request, HASH));
    }
  }

  private OrchestrationDefinition granted() {
    var definition = mock(OrchestrationDefinition.class);
    when(definition.name()).thenReturn(NAME);
    when(definition.hash()).thenReturn(HASH);
    when(grants.granted(current, lookup, NAME)).thenReturn(Optional.of(definition));
    return definition;
  }

  private void bindChild(UUID parent) {
    var childAdmission = mock(Admission.class);
    var childOperation = UUID.randomUUID();
    when(childAdmission.id()).thenReturn(childOperation);
    when(childAdmission.executor()).thenReturn(owner.executor());
    var bound = mock(OperationAuthority.Conversation.class);
    when(bound.parent()).thenReturn(owner.conversation());
    when(bound.admission()).thenReturn(childAdmission);
    when(operations.conversation(child.conversation())).thenReturn(Optional.of(bound));
    when(operations.parent(childOperation)).thenReturn(Optional.of(parent));
  }
}
