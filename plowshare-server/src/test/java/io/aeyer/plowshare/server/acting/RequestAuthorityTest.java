package io.aeyer.plowshare.server.acting;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.ProjectRole;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestAuthorityTest {
  private static final String ACTOR = "@service/12345678-1234-1234-1234-123456789abc";
  private final ActingScope scope =
      new ActingScope(ProjectRole.CONTRIBUTOR, Set.of("research"), true);

  @Test
  void directRequestsKeepTheirAuthenticatedHumanOrServiceIdentity() {
    for (String account : Set.of("alice", ACTOR)) {
      var direct = new RequestAuthority.Direct(account);
      assertEquals(account, direct.actor());
      assertEquals(account, direct.subject());
    }
  }

  @Test
  void delegationKeepsSeparateImmutableActorSubjectAndProofReference() {
    UUID proof = UUID.randomUUID();
    var delegation = new RequestAuthority.Delegation("connector", "account-users", proof, scope);
    var authority = new RequestAuthority.Delegated(ACTOR, "alice", delegation);
    assertEquals(ACTOR, authority.actor());
    assertEquals("alice", authority.subject());
    assertEquals(proof, authority.delegation().proof());
    assertEquals(scope, authority.delegation().scope());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestAuthority.Delegated("alice", "alice", delegation));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestAuthority.Delegated(ACTOR, ACTOR, delegation));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", " alice", "alice ", "alice\n", "@service/no-token", "@service/1-1-1-1-1"})
  void invalidAccountAndNoncanonicalServiceIdentitiesAreRejected(String account) {
    assertThrows(IllegalArgumentException.class, () -> new RequestAuthority.Direct(account));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " project",
        "project ",
        "project\n",
        "personal:616c696365",
        "Personal:alice",
        "client:local"
      })
  void specialOrMalformedProjectsCannotEnterAnOrdinaryProjectScope(String project) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActingScope(ProjectRole.VIEWER, Set.of(project), false));
    assertThrows(IllegalArgumentException.class, () -> new ActingTarget.Project(project));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestAuthority.Delegation(project, "account-users", UUID.randomUUID(), scope));
  }

  @Test
  void delegationCannotRepresentAnAdministrativeCeiling() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActingScope(ProjectRole.MANAGER, Set.of("research"), true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActingPolicySource.Grant(
                "connector", "account-users", ProjectRole.MANAGER, true, Set.of()));
  }

  @Test
  void callersCannotMutateAdmittedProjectsOrAcceptedProofKinds() {
    var projects = new HashSet<>(Set.of("research"));
    var admitted = new ActingScope(ProjectRole.CONTRIBUTOR, projects, true);
    projects.add("another-project");
    assertEquals(Set.of("research"), admitted.projects());
    assertThrows(
        UnsupportedOperationException.class, () -> admitted.projects().add("another-project"));

    var kinds = new HashSet<>(Set.of(ActingPolicySource.ProofKind.ACTING_TOKEN));
    var grant =
        new ActingPolicySource.Grant("connector", "account-users", ProjectRole.VIEWER, true, kinds);
    kinds.clear();
    assertEquals(Set.of(ActingPolicySource.ProofKind.ACTING_TOKEN), grant.proofs());
    assertThrows(UnsupportedOperationException.class, () -> grant.proofs().clear());
  }

  @Test
  void projectCollectionAndRequiredReferencesAreBounded() {
    var projects = new HashSet<String>();
    for (int i = 0; i < 257; i++) projects.add("project-" + i);
    assertThrows(
        IllegalArgumentException.class, () -> new ActingScope(ProjectRole.VIEWER, projects, false));
    assertThrows(IllegalArgumentException.class, () -> new RequestAuthority.Direct(null));
    assertThrows(
        NullPointerException.class,
        () -> new RequestAuthority.Delegation("connector", "account-users", null, scope));
    assertThrows(
        NullPointerException.class,
        () ->
            new RequestAuthority.Delegation("connector", "account-users", UUID.randomUUID(), null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ActingPolicySource.Token(
                UUID.randomUUID(),
                "connector",
                ACTOR,
                "account-users",
                "alice",
                scope,
                null,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActingPolicySource.Token(
                UUID.randomUUID(),
                "connector",
                "alice",
                "account-users",
                "alice",
                scope,
                Instant.MAX,
                false));
  }
}
