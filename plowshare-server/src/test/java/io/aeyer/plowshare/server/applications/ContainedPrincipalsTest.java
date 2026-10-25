package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.applications.ContainedPrincipals.ContainedPrincipal;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.lang.reflect.RecordComponent;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Exercises the real repository over a mocked {@code JdbcTemplate}, so each case fails if the
 * production code is removed. Behaviour that needs actual PostgreSQL (cascade, constraint, column
 * set) is in {@code ContainedPrincipalSchemaTest}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ContainedPrincipalsTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ContainedPrincipals principals = new JdbcContainedPrincipalRepository(jdbc);

  private static AuthorityScope.Contained scope() {
    return new AuthorityScope.Contained(
        new ScopeId(UUID.randomUUID()), 7L, new ScopeId(UUID.randomUUID()));
  }

  @SuppressWarnings("unchecked")
  private void resolves(ContainedPrincipal principal) {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(principal));
  }

  @Test
  void anIssuedPrincipalCarriesNoCredentialMaterial() {
    // The value type is identity only: a UUID and the scope it belongs to. A credential-bearing
    // component would have to appear here and fail this assertion.
    var names =
        Arrays.stream(ContainedPrincipal.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertEquals(List.of("id", "scope"), names);
    var scope = scope();
    var expected = new ContainedPrincipal(UUID.randomUUID(), scope.id());
    resolves(expected);
    assertEquals(expected, principals.issue(scope));
  }

  @Test
  void authenticationCannotResolveAContainedPrincipal() {
    var scope = scope();
    var principal = new ContainedPrincipal(UUID.randomUUID(), scope.id());
    resolves(principal);
    var issued = principals.issue(scope);
    // Neither the id nor a derived name is a service credential or principal, so the
    // service-token authentication path has nothing to match.
    assertFalse(ServiceCredentials.credential(issued.id().toString()));
    assertFalse(ServiceCredentials.principal(issued.id().toString()));
  }

  @Test
  void issuingTwiceForOneScopeReturnsTheSamePrincipal() {
    var scope = scope();
    var principal = new ContainedPrincipal(UUID.randomUUID(), scope.id());
    resolves(principal);
    assertEquals(principals.issue(scope), principals.issue(scope));
    // Idempotence is the storage's: a second issue must be a no-op insert, never a second row.
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
    assertTrue(sql.getValue().contains("ON CONFLICT (scope_id) DO NOTHING"), sql.getValue());
    verify(jdbc, times(2))
        .update(
            anyString(),
            eq(scope.id().value()),
            eq(scope.projectId()),
            eq(scope.container().value()));
  }

  /**
   * Revocation presents the same fully described scope issuing presents, and the statement checks
   * all three columns against the authority record. A bare scope id checked neither, which was
   * reachable the moment destruction called it.
   */
  @Test
  void revokingTheScopeRemovesThePrincipalOnlyWhereTheRecordAgrees() {
    var scope = scope();
    principals.revoke(scope);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc)
        .update(
            sql.capture(),
            eq(scope.id().value()),
            eq(scope.projectId()),
            eq(scope.container().value()));
    assertTrue(sql.getValue().contains("s.kind='CONTAINED'"), sql.getValue());
    assertTrue(sql.getValue().contains("s.project_id=?"), sql.getValue());
    assertTrue(sql.getValue().contains("s.container=?"), sql.getValue());
  }

  @Test
  void issuingForAScopeThatDoesNotResolveIsRefusedWithTheContainmentRefusal() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    var refusal = assertThrows(CallerFault.class, () -> principals.issue(scope()));
    assertEquals("Identity or scope mapping is unavailable or restricted", refusal.getMessage());
  }

  @Test
  void aContainedPrincipalIsNotAServiceCredentialPrincipal() {
    var id = UUID.randomUUID();
    assertFalse(ServiceCredentials.principal(id.toString()));
    assertFalse(ServiceCredentials.principal("contained:" + id));
    assertTrue(ServiceCredentials.principal("@service/x"));
  }
}
