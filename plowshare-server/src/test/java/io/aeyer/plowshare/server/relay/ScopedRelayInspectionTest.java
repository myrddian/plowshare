package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScopedRelayInspectionTest {
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final RelayLogRepository logs = mock(RelayLogRepository.class);
  private final RelayInspection inspection = new ScopedRelayInspection(members, projects, logs);

  @Test
  void no_log_query_occurs_without_live_membership_or_private_union_ownership() {
    assertThrows(
        CallerFault.class,
        () -> inspection.topics("reader", new RelayLog.TopicsQuery("project", false, null)));
    when(members.mayUse("project", "reader")).thenReturn(true);
    when(projects.personalOwner("project")).thenReturn(Optional.of("another-account"));
    when(members.isServerAdmin("reader")).thenReturn(true);
    assertThrows(
        CallerFault.class,
        () -> inspection.topics("reader", new RelayLog.TopicsQuery("project", false, null)));
    verifyNoInteractions(logs);
  }

  @Test
  void system_scope_requires_live_administration_and_never_uses_a_project() {
    var query = new RelayLog.TopicsQuery(null, true, null);
    assertThrows(CallerFault.class, () -> inspection.topics("reader", query));
    when(members.isServerAdmin("reader")).thenReturn(true);
    when(logs.topics(Relay.SystemScope.SERVER, 100)).thenReturn(List.of());
    assertTrue(inspection.topics("reader", query).scope().system());
    verifyNoInteractions(projects);
  }

  @Test
  void
      inspection_preserves_large_positions_expired_gaps_and_availability_without_acknowledgement() {
    long position = 9007199254740993L;
    var key = new Relay.TopicKey(9, "release.observed");
    var topic =
        new Relay.Topic(
            key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), position, position - 1);
    var sub = new Relay.Subscription(new Relay.SubscriptionKey(key, "reader"), 0, Instant.EPOCH);
    when(members.mayUse("project", "reader")).thenReturn(true);
    when(projects.id("project")).thenReturn(9L);
    when(logs.read(key, 0, 100, "reader"))
        .thenReturn(new RelayLogRepository.Snapshot(topic, 0, List.of(), List.of(sub), List.of()));
    var read =
        inspection.log(
            "reader", new RelayLog.Query("project", false, "release.observed", null, null));
    assertEquals(Long.toString(position), read.topic().through());
    assertEquals(Long.toString(position - 1), read.gapThrough());
    assertEquals("0", read.subscribers().getFirst().seenThrough());
    assertEquals(Long.toString(position - 1), read.subscribers().getFirst().gapThrough());
    verify(logs).read(key, 0, 100, "reader");
    verifyNoMoreInteractions(logs);
  }
}
