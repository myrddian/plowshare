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
  void a_narrowed_or_invalid_issuer_manifest_invalidates_dependent_grants_live() {
    var child = binding("reader", StandardRole.CONTRIBUTOR);
    var parent = binding("issuer", StandardRole.MANAGER);
    when(grants.projectBindings("app", "reader")).thenReturn(List.of(child));
    when(grants.lineage(child.id())).thenReturn(List.of(child, parent));
    boundary(Map.of("reader", ProjectRole.MANAGER, "issuer", ProjectRole.VIEWER));
    assertFalse(lineage.valid(child.id()));
    assertTrue(lineage.projectRole("app", "reader", Optional.of(ProjectRole.MANAGER)).isEmpty());
    boundary(Map.of("reader", ProjectRole.MANAGER, "issuer", ProjectRole.MANAGER));
    assertEquals(
        Optional.of(ProjectRole.CONTRIBUTOR),
        lineage.projectRole("app", "reader", Optional.of(ProjectRole.MANAGER)));
    when(applications.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertFalse(lineage.valid(child.id()));
  }

  @Test
  void direct_token_ceiling_and_each_binding_stay_narrower_than_the_account() {
    var manager = binding("service", StandardRole.MANAGER);
    when(grants.projectBindings("app", "service")).thenReturn(List.of(manager));
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
    when(grants.projectBindings("app", "admin")).thenReturn(List.of(admin));
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
        Optional.of("app"));
  }
}
