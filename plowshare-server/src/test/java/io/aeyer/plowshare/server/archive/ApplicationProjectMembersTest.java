package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApplicationProjectMembersTest {
  ProjectMembers stored = mock(ProjectMembers.class);
  ApplicationPolicy policy = mock(ApplicationPolicy.class);
  ApplicationProjectMembers members = new ApplicationProjectMembers(stored, policy);

  @Test
  void manifest_cannot_grant_membership_and_caps_existing_roles() {
    when(policy.read("chatbot"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION,
                Map.of("reader", ProjectRole.VIEWER, "worker", ProjectRole.MANAGER)));
    when(stored.role("chatbot", "reader")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(stored.role("chatbot", "worker")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    assertTrue(members.mayUse("chatbot", "reader"));
    assertFalse(members.mayWork("chatbot", "reader"));
    assertFalse(members.mayManage("chatbot", "reader"));
    assertEquals(Optional.of(ProjectRole.CONTRIBUTOR), members.role("chatbot", "worker"));
    assertFalse(members.mayManage("chatbot", "worker"));
    when(stored.role("chatbot", "worker")).thenReturn(Optional.empty());
    assertFalse(members.mayUse("chatbot", "worker"));
  }

  @Test
  void admin_management_is_separate_from_application_visibility_and_revocation_is_live() {
    when(stored.isServerAdmin("admin")).thenReturn(true);
    when(stored.role("chatbot", "admin")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(policy.read("chatbot"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()));
    assertTrue(members.isServerAdmin("admin"));
    assertFalse(members.mayUse("chatbot", "admin"));
    assertThrows(
        CallerFault.class, () -> members.requireRole("chatbot", "admin", ProjectRole.VIEWER));
    members.assign("chatbot", "reader", ProjectRole.VIEWER, "admin", true);
    verify(stored).assign("chatbot", "reader", ProjectRole.VIEWER, "admin", true);
    when(policy.read("chatbot"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("admin", ProjectRole.CONTRIBUTOR)));
    assertTrue(members.mayWork("chatbot", "admin"));
    when(policy.read("chatbot"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertFalse(members.mayUse("chatbot", "admin"));
  }

  @Test
  void legacy_membership_is_preserved() {
    when(stored.role("legacy", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(policy.read("legacy"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of()));
    assertEquals(Optional.of(ProjectRole.VIEWER), members.role("legacy", "reader"));
    assertFalse(members.application("legacy"));
  }

  @Test
  void machine_token_keeps_its_ceiling_and_uses_its_owners_explicit_manifest_grant() {
    var stored = mock(ProjectMembers.class);
    var policy = mock(ApplicationPolicy.class);
    var effective = new ApplicationProjectMembers(stored, policy);
    String principal = "@service/fixture";
    when(stored.authorityAccount(principal)).thenReturn(Optional.of("integration"));
    when(stored.role("chatbot", principal)).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(policy.read("chatbot"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("integration", ProjectRole.MANAGER)));
    assertEquals(Optional.of(ProjectRole.VIEWER), effective.role("chatbot", principal));
    when(policy.read("chatbot"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()));
    assertFalse(effective.mayUse("chatbot", principal));
  }
}
