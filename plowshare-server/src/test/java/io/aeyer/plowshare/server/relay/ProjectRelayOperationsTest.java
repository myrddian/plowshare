package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayControl;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectRelayOperationsTest {
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final RelayRouting routing = mock(RelayRouting.class);
  private final RelayDispatch dispatch = mock(RelayDispatch.class);
  private final RelayOperationRepository repository = mock(RelayOperationRepository.class);
  private final RelayPublicationSignals signals = mock(RelayPublicationSignals.class);
  private final UnitOfWork transactions = mock(UnitOfWork.class);
  private final RelayOperations operations =
      new ProjectRelayOperations(
          members, projects, routing, dispatch, repository, signals, transactions);
  private final RelayProjectFiles.Access access =
      new RelayProjectFiles.Access("operator", "project", 9);

  private RelayControl.Request request(RelayControl.Action action) {
    return new RelayControl.Request(
        UUID.randomUUID().toString(),
        "project",
        "release.observed",
        UUID.randomUUID().toString(),
        action,
        "relay.notices.release",
        UUID.randomUUID().toString(),
        action == RelayControl.Action.RECONCILE ? UUID.randomUUID().toString() : null,
        action == RelayControl.Action.RECONCILE ? "1" : null,
        null,
        action == RelayControl.Action.RECONCILE ? "UNCERTAIN" : null,
        "Inspect retained work");
  }

  private void authorize() {
    when(members.mayManage("project", "operator")).thenReturn(true);
    when(projects.id("project")).thenReturn(9L);
  }

  @Test
  void manager_and_personal_owner_authority_precede_receipt_or_source_reads() {
    var request = request(RelayControl.Action.REMOVE_SUBSCRIPTION);
    assertThrows(CallerFault.class, () -> operations.operate("operator", request));
    authorize();
    when(projects.personalOwner("project")).thenReturn(Optional.of("other"));
    assertThrows(CallerFault.class, () -> operations.operate("operator", request));
    verifyNoInteractions(repository, routing, dispatch);
  }

  @Test
  void retained_removal_receipt_is_read_without_requiring_deleted_configuration() {
    authorize();
    var request = request(RelayControl.Action.REMOVE_SUBSCRIPTION);
    var result =
        new RelayControl.Result(
            request.requestId(),
            "project",
            request.topic(),
            request.action(),
            request.subscriber(),
            null,
            "REMOVED",
            null,
            Instant.now());
    when(repository.receipt(access, request)).thenReturn(Optional.of(result));
    assertEquals(result, operations.operate("operator", request));
    verifyNoInteractions(routing, dispatch);
    verify(repository, never()).apply(any(), any(), any());
  }

  @Test
  void active_or_unreadable_configuration_cannot_be_removed() {
    authorize();
    var request = request(RelayControl.Action.REMOVE_SUBSCRIPTION);
    var declared =
        new RelayRouting.Subscription(
            "release", "release.observed", RelayPayload.Kind.TEXT, Relay.Start.OLDEST_RETAINED);
    var bundle =
        new RelayRouting.Package(
            "notices",
            RelayDeliveries.SourcePin.of("notices/routes.js", "export const subscriptions=[];"),
            new RelayRouting.Manifest(List.of(declared)));
    when(routing.load(access)).thenReturn(new RelayRouting.Project(List.of(bundle), Map.of()));
    assertThrows(CallerFault.class, () -> operations.operate("operator", request));
    when(routing.load(access)).thenThrow(new CallerFault("offline"));
    assertThrows(CallerFault.class, () -> operations.operate("operator", request));
    verify(repository, never()).apply(any(), any(), any());
  }

  @Test
  void reconcile_inspects_only_and_rechecks_live_project_identity_before_mutating() {
    authorize();
    var request = request(RelayControl.Action.RECONCILE);
    var key =
        new RelayDeliveries.DeliveryKey(
            new Relay.SubscriptionKey(new Relay.TopicKey(9, request.topic()), request.subscriber()),
            UUID.fromString(request.deliveryId()));
    when(dispatch.inspect(access, key))
        .thenAnswer(
            call -> {
              when(projects.id("project")).thenReturn(10L);
              return Optional.empty();
            });
    assertThrows(CallerFault.class, () -> operations.operate("operator", request));
    verify(dispatch).inspect(access, key);
    verifyNoMoreInteractions(dispatch);
    verify(repository, never()).apply(any(), any(), any());
  }

  @Test
  void inconclusive_receipt_is_applied_as_unknown_and_commit_hint_never_dispatches() {
    authorize();
    var request = request(RelayControl.Action.RECONCILE);
    var result =
        new RelayControl.Result(
            request.requestId(),
            "project",
            request.topic(),
            request.action(),
            request.subscriber(),
            request.deliveryId(),
            "UNCERTAIN",
            null,
            Instant.now());
    when(repository.apply(access, request, Optional.empty())).thenReturn(result);
    assertEquals(result, operations.operate("operator", request));
    verify(dispatch).inspect(eq(access), any());
    verifyNoMoreInteractions(dispatch);
    verify(transactions).afterCommit(any());
  }
}
