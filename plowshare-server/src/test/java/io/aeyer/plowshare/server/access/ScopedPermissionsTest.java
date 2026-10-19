package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.ScopedPermissions.Action;
import io.aeyer.plowshare.server.access.ScopedPermissions.Principal;
import io.aeyer.plowshare.server.access.ScopedPermissions.ProjectTarget;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ApplicationProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The effective role is the sole source of authority; these tests need no database. */
class ScopedPermissionsTest {
  private final ProjectMembers stored = mock(ProjectMembers.class);
  private final ScopedPermissions permissions = new EffectiveProjectPermissions(stored);
  private final Principal alice = new Principal("alice");
  private final ProjectTarget project = new ProjectTarget("research");

  @ParameterizedTest
  @EnumSource(ProjectRole.class)
  void roleMatrixDoesNotGrantAnotherAccountOrProject(ProjectRole role) {
    when(stored.role("research", "alice")).thenReturn(Optional.of(role));
    assertTrue(permissions.allows(alice, project, Action.READ));
    assertEquals(role != ProjectRole.VIEWER, permissions.allows(alice, project, Action.WRITE));
    assertEquals(
        role == ProjectRole.MANAGER, permissions.allows(alice, project, Action.SET_AUDIENCE));
    for (Action action : Action.values()) {
      assertFalse(permissions.allows(new Principal("bob"), project, action));
      assertFalse(permissions.allows(alice, new ProjectTarget("other"), action));
    }
    verify(stored, never()).isServerAdmin(any());
    verify(stored, never()).isMember(any(), any());
  }

  @Test
  void absentAuthorityAndOrdinaryProjectNamedGlobalHaveNoSpecialPrivilege() {
    var global = new ProjectTarget("global");
    for (Action action : Action.values()) {
      assertFalse(permissions.allows(alice, project, action));
      assertFalse(permissions.allows(alice, global, action));
    }
    when(stored.role("global", "alice")).thenReturn(Optional.of(ProjectRole.VIEWER));
    assertTrue(permissions.allows(alice, global, Action.READ));
    assertFalse(permissions.allows(alice, global, Action.WRITE));
  }

  @Test
  void invalidRequestsNeverReachTheAuthoritySourceAndFailuresNeverAllow() {
    for (String invalid : new String[] {null, "", " \t"}) {
      assertThrows(IllegalArgumentException.class, () -> new Principal(invalid));
      assertThrows(IllegalArgumentException.class, () -> new ProjectTarget(invalid));
    }
    for (String invalid : new String[] {" research", "research ", "re\nsearch", "x".repeat(1025)}) {
      assertThrows(IllegalArgumentException.class, () -> new ProjectTarget(invalid));
    }
    assertThrows(NullPointerException.class, () -> permissions.allows(null, project, Action.READ));
    assertThrows(NullPointerException.class, () -> permissions.allows(alice, null, Action.READ));
    assertThrows(NullPointerException.class, () -> permissions.allows(alice, project, null));
    verifyNoInteractions(stored);
    var failure = new IllegalStateException("authority unavailable");
    when(stored.role("research", "alice")).thenThrow(failure);
    for (Action action : Action.values()) {
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class, () -> permissions.allows(alice, project, action)));
    }
  }

  @Test
  void applicationManifestLimitsAdminAndRevocationIsLive() {
    var policy = mock(ApplicationPolicy.class);
    ScopedPermissions effective =
        new EffectiveProjectPermissions(
            new ApplicationProjectMembers(
                stored,
                policy,
                io.aeyer.plowshare.server.access.GrantLineageFixture.passthrough()));
    when(stored.isServerAdmin("alice")).thenReturn(true);
    when(stored.role("research", "alice")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(policy.read("research"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("alice", ProjectRole.VIEWER)));
    assertTrue(effective.allows(alice, project, Action.READ));
    assertFalse(effective.allows(alice, project, Action.WRITE));
    when(policy.read("research"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()));
    assertFalse(effective.allows(alice, project, Action.READ));
    when(policy.read("research"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertFalse(effective.allows(alice, project, Action.WRITE));
    verify(stored, never()).isServerAdmin(any());
  }

  @Test
  void tokenKeepsItsOwnRoleCeilingAndRequiresItsOwnersManifestGrant() {
    var policy = mock(ApplicationPolicy.class);
    ScopedPermissions effective =
        new EffectiveProjectPermissions(
            new ApplicationProjectMembers(
                stored,
                policy,
                io.aeyer.plowshare.server.access.GrantLineageFixture.passthrough()));
    var token = new Principal("@service/fixture");
    when(stored.authorityAccount(token.value())).thenReturn(Optional.of("integration"));
    when(stored.role("research", token.value())).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(stored.role("research", "integration")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(policy.read("research"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("integration", ProjectRole.MANAGER)));
    assertTrue(effective.allows(token, project, Action.READ));
    assertFalse(effective.allows(token, project, Action.WRITE));
    when(policy.read("research"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of(token.value(), ProjectRole.MANAGER)));
    assertFalse(effective.allows(token, project, Action.READ));
    verify(stored, never()).role("research", "integration");
  }
}
