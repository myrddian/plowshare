package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnedRelaySubscriptionWorkTest {
  private final RelayProjectFiles files = mock(RelayProjectFiles.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final RelayRouting routing = mock(RelayRouting.class);
  private final Relay relay = mock(Relay.class);
  private final RelayDispatch dispatch = mock(RelayDispatch.class);
  private final RelayConsumerRepository consumers = mock(RelayConsumerRepository.class);
  private final RelaySubscriptionWork work =
      new OwnedRelaySubscriptionWork(files, members, routing, relay, dispatch, consumers);
  private final RelayConsumerRepository.Lease lease =
      new RelayConsumerRepository.Lease(
          SUB, "worker", "operator", 4, Instant.now().plusSeconds(120));

  @BeforeEach
  void setup() {
    when(members.mayWork("project", "operator")).thenReturn(true);
    configuration(Map.of());
    when(consumers.acquire(eq(SUB), eq("worker"), eq("operator"), any()))
        .thenReturn(Optional.of(lease));
    when(dispatch.next(eq(ACCESS), eq(SUB), eq("worker"), any(), any()))
        .thenAnswer(call -> call.getArgument(4, RelayDispatch.ClaimSource.class).claim());
    when(relay.read(SUB, 1))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(SUB, 0, Instant.EPOCH), Optional.empty(), List.of()));
  }

  @Test
  void another_live_owner_prevents_routing_and_dispatch() {
    when(consumers.acquire(eq(SUB), eq("worker"), eq("operator"), any()))
        .thenReturn(Optional.empty());
    assertFalse(work.process(ACCESS, SUB, "worker", 1, 1).progressed());
    verifyNoInteractions(dispatch);
    verify(relay, never()).read(any(), anyInt());
    verify(routing, never()).admit(any(), any(), any(), any());
  }

  @Test
  void routing_commit_is_ownership_fenced_and_failure_releases_the_lease() {
    var input = delivery(RelayDeliveries.State.DISPATCHING, false).publication();
    var decision =
        new RelayDeliveries.Decision(
            delivery(RelayDeliveries.State.DISPATCHING, false).routing(), List.of());
    when(relay.read(SUB, 1))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(SUB, 0, Instant.EPOCH), Optional.empty(), List.of(input)));
    when(routing.admit(eq(ACCESS), eq(SUB), eq(input), any()))
        .thenAnswer(
            call ->
                call.getArgument(3, RelayRouting.AdmissionCommit.class)
                    .admit(new RelayDeliveries.AdmissionKey(SUB, input.position()), decision));
    when(consumers.admit(eq(lease), any(), eq(decision)))
        .thenThrow(new IllegalStateException("stale"));
    assertThrows(IllegalStateException.class, () -> work.process(ACCESS, SUB, "worker", 1, 0));
    verify(consumers).admit(eq(lease), any(), eq(decision));
    verify(consumers).release(lease);
    verify(relay, never()).advanceSeen(any(), anyLong());
  }

  @Test
  void expiry_gap_stops_admission_without_stopping_pinned_branch_dispatch() {
    when(relay.read(SUB, 1))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(SUB, 0, Instant.EPOCH),
                Optional.of(new Relay.Gap(0, 5)),
                List.of()));
    when(consumers.claim(eq(lease), any()))
        .thenReturn(Optional.of(delivery(RelayDeliveries.State.ACCEPTED, false)));
    var result = work.process(ACCESS, SUB, "worker", 1, 1);
    assertEquals(1, result.dispatched());
    assertEquals(0, result.admitted());
    assertEquals(5, result.gap().throughInclusive());
    verify(relay, never()).acknowledgeGap(any(), anyLong());
    verify(routing, never()).admit(any(), any(), any(), any());
  }

  @Test
  void retained_work_drains_before_attempting_admission_so_full_queues_can_make_progress() {
    var input = delivery(RelayDeliveries.State.DISPATCHING, false).publication();
    when(consumers.claim(eq(lease), any()))
        .thenReturn(Optional.of(delivery(RelayDeliveries.State.ACCEPTED, false)));
    when(relay.read(SUB, 1))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(SUB, 0, Instant.EPOCH), Optional.empty(), List.of(input)));
    when(routing.admit(eq(ACCESS), eq(SUB), eq(input), any()))
        .thenThrow(new CallerFault("pending cap"));
    assertThrows(CallerFault.class, () -> work.process(ACCESS, SUB, "worker", 1, 1));
    var order = inOrder(consumers, routing);
    order.verify(consumers).claim(eq(lease), any());
    order.verify(routing).admit(eq(ACCESS), eq(SUB), eq(input), any());
    verify(consumers).release(lease);
  }

  @Test
  void deactivation_and_authority_changes_prevent_new_broker_work() {
    when(routing.load(ACCESS)).thenReturn(new RelayRouting.Project(List.of(), Map.of()));
    assertFalse(work.process(ACCESS, SUB, "worker", 1, 1).progressed());
    verifyNoInteractions(relay, consumers, dispatch);
    when(members.mayWork("project", "operator")).thenReturn(false);
    assertThrows(CallerFault.class, () -> work.subscriptions(ACCESS));
  }

  private void configuration(Map<String, Relay.Policy> policies) {
    var declared =
        new RelayRouting.Subscription(
            "release", "release.observed", RelayPayload.Kind.TEXT, Relay.Start.OLDEST_RETAINED);
    var bundle =
        new RelayRouting.Package(
            "notices",
            delivery(RelayDeliveries.State.DISPATCHING, false).routing(),
            new RelayRouting.Manifest(List.of(declared)));
    when(routing.load(ACCESS)).thenReturn(new RelayRouting.Project(List.of(bundle), policies));
  }

  @Test
  void contributor_can_discover_and_process_with_declared_policies_without_applying_them() {
    var declared = new Relay.Policy(Duration.ofDays(1), 1L);
    configuration(Map.of(SUB.topic().name(), declared));
    var input = delivery(RelayDeliveries.State.DISPATCHING, false).publication();
    when(relay.read(SUB, 1))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(SUB, 0, Instant.EPOCH), Optional.empty(), List.of(input)));
    when(consumers.claim(eq(lease), any()))
        .thenReturn(Optional.of(delivery(RelayDeliveries.State.ACCEPTED, false)));
    assertEquals(List.of(SUB), work.subscriptions(ACCESS));
    verifyNoInteractions(relay, consumers, dispatch);
    var result = work.process(ACCESS, SUB, "worker", 1, 1);
    assertEquals(1, result.admitted());
    assertEquals(1, result.dispatched());
    verify(relay).registerTopic(SUB.topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    verify(relay, never()).configureTopic(any(), any(), any());
    verify(routing).admit(eq(ACCESS), eq(SUB), eq(input), any());
    verify(consumers).release(lease);
  }

  @Test
  void manager_can_apply_declared_policy_before_consuming() {
    var declared = new Relay.Policy(Duration.ofDays(1), 1L);
    configuration(Map.of(SUB.topic().name(), declared));
    when(members.mayManage("project", "operator")).thenReturn(true);
    work.process(ACCESS, SUB, "worker", 1, 0);
    var order = inOrder(relay, consumers);
    order.verify(relay).configureTopic(SUB.topic(), RelayPayload.Kind.TEXT, declared);
    order.verify(relay).subscribe(SUB, Relay.Start.OLDEST_RETAINED);
    order.verify(consumers).acquire(eq(SUB), eq("worker"), eq("operator"), any());
    verify(relay, never()).registerTopic(any(), any(), any());
  }

  @Test
  void removing_manager_authority_after_discovery_preserves_processing_without_policy_writes() {
    configuration(Map.of(SUB.topic().name(), new Relay.Policy(Duration.ofDays(1), 1L)));
    when(members.mayManage("project", "operator")).thenReturn(true);
    assertEquals(List.of(SUB), work.subscriptions(ACCESS));
    when(members.mayManage("project", "operator")).thenReturn(false);
    work.process(ACCESS, SUB, "worker", 1, 0);
    verify(relay, never()).configureTopic(any(), any(), any());
    verify(relay).registerTopic(SUB.topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
  }

  @Test
  void contributor_cannot_apply_a_new_policy_on_a_later_processing_pass() {
    configuration(Map.of(SUB.topic().name(), new Relay.Policy(Duration.ofDays(1), 1L)));
    work.process(ACCESS, SUB, "worker", 1, 0);
    configuration(Map.of(SUB.topic().name(), new Relay.Policy(Duration.ofDays(30), 1000L)));
    work.process(ACCESS, SUB, "worker", 1, 0);
    verify(relay, never()).configureTopic(any(), any(), any());
    verify(relay, times(2))
        .registerTopic(SUB.topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
  }

  @Test
  void inactive_packages_do_not_apply_policies_even_for_managers() {
    when(members.mayManage("project", "operator")).thenReturn(true);
    when(routing.load(ACCESS))
        .thenReturn(
            new RelayRouting.Project(
                List.of(), Map.of(SUB.topic().name(), new Relay.Policy(Duration.ofDays(1), 1L))));
    assertEquals(List.of(), work.subscriptions(ACCESS));
    assertFalse(work.process(ACCESS, SUB, "worker", 1, 1).progressed());
    verifyNoInteractions(relay, consumers, dispatch);
  }

  @Test
  void losing_work_authority_after_discovery_prevents_broker_work() {
    configuration(Map.of(SUB.topic().name(), Relay.Policy.systemDefault()));
    assertEquals(List.of(SUB), work.subscriptions(ACCESS));
    when(members.mayWork("project", "operator")).thenReturn(false);
    assertThrows(CallerFault.class, () -> work.process(ACCESS, SUB, "worker", 1, 1));
    verifyNoInteractions(relay, consumers, dispatch);
  }
}
