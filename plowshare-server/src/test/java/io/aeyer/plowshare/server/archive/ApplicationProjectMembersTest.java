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
  ApplicationProjectMembers members =
      new ApplicationProjectMembers(
          stored, policy, io.aeyer.plowshare.server.access.GrantLineageFixture.passthrough());

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
    when(stored.mayAdminister("chatbot", "admin")).thenReturn(true);
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
    var effective =
        new ApplicationProjectMembers(
            stored, policy, io.aeyer.plowshare.server.access.GrantLineageFixture.passthrough());
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

  @Test
  void
      batched_read_presence_preserves_manifest_and_lineage_intersections_without_stored_role_reads() {
    var lineage = mock(io.aeyer.plowshare.server.access.GrantLineage.class);
    var effective = new ApplicationProjectMembers(stored, policy, lineage);
    var candidates =
        java.util.List.of(
            "viewer", "worker", "manager", "external", "invalid", "removed", "broken-chain");
    when(stored.readableProjects(candidates.stream().sorted().toList(), "reader"))
        .thenReturn(java.util.Set.copyOf(candidates));
    when(lineage.projectRole(anyString(), eq("reader"), any(), any()))
        .thenAnswer(invocation -> invocation.<Optional<ProjectRole>>getArgument(2));
    when(lineage.projectRole(eq("broken-chain"), eq("reader"), any(), any()))
        .thenReturn(Optional.empty());
    for (var role : ProjectRole.values()) {
      String project =
          switch (role) {
            case VIEWER -> "viewer";
            case CONTRIBUTOR -> "worker";
            case MANAGER -> "manager";
          };
      when(stored.role(project, "reader")).thenReturn(Optional.of(role));
      when(policy.read(project))
          .thenReturn(
              new ApplicationPolicy.Boundary(
                  ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.MANAGER)));
    }
    when(stored.role("external", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(policy.read("external"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of()));
    for (String project : java.util.List.of("invalid", "removed", "broken-chain"))
      when(stored.role(project, "reader")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(policy.read("invalid"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    when(policy.read("removed"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()));
    when(policy.read("broken-chain"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.MANAGER)));
    var expected =
        candidates.stream()
            .filter(project -> effective.mayUse(project, "reader"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    clearInvocations(stored, lineage, policy);
    var actual = effective.readableProjects(candidates, "reader");
    assertEquals(java.util.Set.of("viewer", "worker", "manager", "external"), actual);
    assertEquals(expected, actual);
    verify(stored).readableProjects(candidates.stream().sorted().toList(), "reader");
    verify(stored, never()).role(anyString(), anyString());
    when(policy.read("viewer"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertFalse(effective.readableProjects(candidates, "reader").contains("viewer"));
  }

  @Test
  void batched_machine_read_resolves_owner_once_and_never_inherits_missing_manifest_grants() {
    var lineage = mock(io.aeyer.plowshare.server.access.GrantLineage.class);
    var effective = new ApplicationProjectMembers(stored, policy, lineage);
    String principal = "@service/fixture";
    var projects = java.util.List.of("allowed", "denied");
    when(stored.readableProjects(projects, principal)).thenReturn(java.util.Set.copyOf(projects));
    when(stored.authorityAccount(principal)).thenReturn(Optional.of("integration"));
    when(lineage.projectRole(anyString(), eq("integration"), any(), any()))
        .thenReturn(Optional.of(ProjectRole.VIEWER));
    when(policy.read("allowed"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("integration", ProjectRole.MANAGER)));
    when(policy.read("denied"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()));
    assertEquals(java.util.Set.of("allowed"), effective.readableProjects(projects, principal));
    verify(stored).authorityAccount(principal);
    verify(stored, never()).role(anyString(), anyString());
    when(stored.authorityAccount(principal)).thenReturn(Optional.empty());
    assertTrue(effective.readableProjects(projects, principal).isEmpty());
  }
}
