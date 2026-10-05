package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectRelayProcessingTest {
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final RelaySubscriptionWork work = mock(RelaySubscriptionWork.class);
  private final RelayProcessing processing = new ProjectRelayProcessing(projects, members, work);

  @Test
  void explicit_processing_uses_subscription_ownership_and_preserves_total_bounds() {
    when(projects.id("project")).thenReturn(9L);
    when(members.mayWork("project", "operator")).thenReturn(true);
    var second = new Relay.SubscriptionKey(SUB.topic(), "relay.notices.other");
    when(work.subscriptions(ACCESS)).thenReturn(List.of(SUB, second));
    when(work.process(eq(ACCESS), eq(SUB), anyString(), eq(1), eq(1)))
        .thenReturn(new RelaySubscriptionWork.Result(1, 1, null));
    when(work.process(eq(ACCESS), eq(second), anyString(), eq(0), eq(0)))
        .thenReturn(new RelaySubscriptionWork.Result(0, 0, new Relay.Gap(0, 5)));
    var result = processing.process("operator", new RelayLog.Process("project", 1));
    assertEquals(1, result.admitted());
    assertEquals(1, result.dispatched());
    assertEquals("5", result.gaps().getFirst().expiredThrough());
  }

  @Test
  void absent_authority_or_project_fails_before_subscription_processing() {
    assertThrows(
        CallerFault.class,
        () -> processing.process("operator", new RelayLog.Process("project", 1)));
    verifyNoInteractions(work, projects);
    when(members.mayWork("project", "operator")).thenReturn(true);
    assertThrows(
        CallerFault.class,
        () -> processing.process("operator", new RelayLog.Process("project", 1)));
    verifyNoInteractions(work);
  }
}
