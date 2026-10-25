package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.applications.ContainedPrincipals.ContainedPrincipal;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import java.lang.reflect.RecordComponent;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Contract tests over an in-memory fake. The persistence-dependent assertions (cascade, absence of
 * credential columns) are in {@code ContainedPrincipalSchemaTest}.
 */
class ContainedPrincipalsTest {
  /** Mirrors the repository contract: one principal per scope, idempotent issue. */
  static class Fake implements ContainedPrincipals {
    final Map<ScopeId, ContainedPrincipal> rows = new HashMap<>();

    public ContainedPrincipal issue(AuthorityScope.Contained scope) {
      return rows.computeIfAbsent(
          scope.id(), s -> new ContainedPrincipal(UUID.randomUUID(), scope.id()));
    }

    public void revoke(ScopeId scope) {
      rows.remove(scope);
    }
  }

  private final Fake principals = new Fake();

  private static AuthorityScope.Contained scope() {
    return new AuthorityScope.Contained(
        new ScopeId(UUID.randomUUID()), 7L, new ScopeId(UUID.randomUUID()));
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
    assertEquals(scope.id(), principals.issue(scope).scope());
  }

  @Test
  void authenticationCannotResolveAContainedPrincipal() {
    var principal = principals.issue(scope());
    // Neither the id nor a derived name is a service credential or principal, so the
    // service-token authentication path has nothing to match.
    assertFalse(ServiceCredentials.credential(principal.id().toString()));
    assertFalse(ServiceCredentials.principal(principal.id().toString()));
  }

  @Test
  void issuingTwiceForOneScopeReturnsTheSamePrincipal() {
    var scope = scope();
    assertEquals(principals.issue(scope), principals.issue(scope));
    assertNotEquals(principals.issue(scope).id(), principals.issue(scope()).id());
  }

  @Test
  void revokingTheScopeRemovesThePrincipal() {
    var scope = scope();
    var first = principals.issue(scope);
    principals.revoke(scope.id());
    assertTrue(principals.rows.isEmpty());
    assertNotEquals(first.id(), principals.issue(scope).id());
  }

  @Test
  void aContainedPrincipalIsNotAServiceCredentialPrincipal() {
    var principal = principals.issue(scope());
    assertFalse(ServiceCredentials.principal(principal.id().toString()));
    assertFalse(ServiceCredentials.principal("contained:" + principal.id()));
    assertFalse(ServiceCredentials.principal(principal.scope().value().toString()));
  }
}
