package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.*;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class ApplicationGrantLineageTest {
  private final ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  private final ApplicationPolicy applications = mock(ApplicationPolicy.class);
  private final GrantLineage lineage = new ApplicationGrantLineage(grants, applications);

  @Test
  void membership_and_lineage_share_one_current_target_policy_and_recheck_on_next_decision() {
    var stored = mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    var membership =
        new io.aeyer.plowshare.server.archive.ApplicationProjectMembers(
            stored, applications, lineage);
    when(stored.role("app", "reader")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(grants.projectLineages("app", "reader"))
        .thenReturn(List.of(List.of(binding("reader", StandardRole.MANAGER))));
    boundary(Map.of("reader", ProjectRole.VIEWER));
    assertEquals(Optional.of(ProjectRole.VIEWER), membership.role("app", "reader"));
    verify(applications, times(1)).read("app");
    boundary(Map.of());
    assertTrue(membership.role("app", "reader").isEmpty());
    verify(applications, times(2)).read("app");
  }

  @Test
  void a_narrowed_or_invalid_issuer_manifest_invalidates_dependent_grants_live() {
    var child = binding("reader", StandardRole.CONTRIBUTOR);
    var parent = binding("issuer", StandardRole.MANAGER);
    when(grants.projectLineages("app", "reader")).thenReturn(List.of(List.of(child, parent)));
    when(grants.lineage(child.id())).thenReturn(List.of(child, parent));
    boundary(Map.of("reader", ProjectRole.MANAGER, "issuer", ProjectRole.VIEWER));
    assertFalse(lineage.valid(child.id()));
    assertTrue(lineage.projectRole("app", "reader", Optional.of(ProjectRole.MANAGER)).isEmpty());
    boundary(Map.of("reader", ProjectRole.MANAGER, "issuer", ProjectRole.MANAGER));
    clearInvocations(grants, applications);
    assertEquals(
        Optional.of(ProjectRole.CONTRIBUTOR),
        lineage.projectRole("app", "reader", Optional.of(ProjectRole.MANAGER)));
    verify(grants).projectLineages("app", "reader");
    verify(grants, never()).lineage(any());
    verify(grants, never()).projectBindings(anyString(), anyString());
    verify(applications, times(1)).read("app");
    when(applications.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertFalse(lineage.valid(child.id()));
  }

  @Test
  void direct_token_ceiling_and_each_binding_stay_narrower_than_the_account() {
    var manager = binding("service", StandardRole.MANAGER);
    when(grants.projectLineages("app", "service")).thenReturn(List.of(List.of(manager)));
    when(grants.lineage(manager.id())).thenReturn(List.of(manager));
    boundary(Map.of("service", ProjectRole.MANAGER));
    assertEquals(
        Optional.of(ProjectRole.VIEWER),
        lineage.projectRole("app", "service", Optional.of(ProjectRole.VIEWER)));
    boundary(Map.of("service", ProjectRole.VIEWER));
    assertEquals(
        Optional.of(ProjectRole.VIEWER),
        lineage.projectRole("app", "service", Optional.of(ProjectRole.MANAGER)));
    assertTrue(lineage.projectRole("app", "service", Optional.empty()).isEmpty());
  }

  @Test
  void administrative_control_never_becomes_a_data_role_and_unavailable_lineage_is_not_cached() {
    var admin = binding("admin", StandardRole.ADMIN);
    when(grants.projectLineages("app", "admin")).thenReturn(List.of(List.of(admin)));
    assertTrue(lineage.projectRole("app", "admin", Optional.of(ProjectRole.MANAGER)).isEmpty());
    when(grants.lineage(admin.id())).thenReturn(List.of());
    assertFalse(lineage.valid(admin.id()));
    when(grants.lineage(admin.id())).thenThrow(new IllegalStateException("unavailable"));
    assertThrows(IllegalStateException.class, () -> lineage.valid(admin.id()));
  }

  private void boundary(Map<String, ProjectRole> accounts) {
    when(applications.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, accounts));
  }

  private Binding binding(String handle, StandardRole role) {
    return binding(handle, role, "app");
  }

  private Binding binding(String handle, StandardRole role, String project) {
    var recipient = GrantRecipient.account(new AccountId(UUID.randomUUID()));
    return new Binding(
        UUID.randomUUID(),
        recipient,
        new ScopeId(UUID.randomUUID()),
        role,
        role,
        Coverage.SELF,
        recipient,
        Optional.of(UUID.randomUUID()),
        Optional.empty(),
        1,
        1,
        false,
        "ROLE",
        OffsetDateTime.now(),
        Optional.empty(),
        Optional.empty(),
        Set.of(AuthorityAction.READ),
        handle,
        Optional.of(project));
  }

  @Test
  void sharing_the_target_policy_does_not_skip_another_projects_issuer_boundary() {
    var stored = mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    var membership =
        new io.aeyer.plowshare.server.archive.ApplicationProjectMembers(
            stored, applications, lineage);
    when(stored.role("app", "reader")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(grants.projectLineages("app", "reader"))
        .thenReturn(
            List.of(
                List.of(
                    binding("reader", StandardRole.CONTRIBUTOR),
                    binding("issuer", StandardRole.MANAGER, "issuer-app"))));
    boundary(Map.of("reader", ProjectRole.MANAGER));
    when(applications.read("issuer-app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertTrue(membership.role("app", "reader").isEmpty());
    verify(applications, times(1)).read("app");
    verify(applications, times(1)).read("issuer-app");
    when(applications.read("issuer-app"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("issuer", ProjectRole.MANAGER)));
    assertEquals(Optional.of(ProjectRole.CONTRIBUTOR), membership.role("app", "reader"));
  }
}
