package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.ContainedHomes;
import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The service-layer gates, asked about a contained run.
 *
 * <p>Before this, {@code requireSelection} and {@code requireWork} asked {@link ScopedPermissions}
 * about the acting principal and a contained run was refused there — the first refusal on the path,
 * reached from {@code InformationCatalogue} on every admission. The repository layer below had been
 * open to that principal since V229/V232; nothing above it was. These tests pin the dispatch that
 * closes the gap and, as importantly, the three things it must not change.
 *
 * <p>The containment source is mocked. The predicate behind it — {@code contained_own_home}, which
 * is what decides ownership in production — is a SQL function, so its own behaviour is pinned by
 * {@code JdbcContainedHomesDatabaseTest} against a real database instead of restated here.
 */
class ContainedHomeGateTest {
  private final String principal = UUID.randomUUID().toString();
  private final String project = "contained:" + UUID.randomUUID();

  private InformationAccess access(ScopedPermissions permissions, ContainedHomes homes) {
    return new InformationAccess(
        permissions,
        io.aeyer.plowshare.server.requests.RequestProjects.EXPLICIT_ONLY,
        AccountKnowledgePermissions.DENY,
        homes);
  }

  /**
   * The capability this adds: a child selects and writes its own home, and the role gate is never
   * asked about it.
   *
   * <p>{@code verify(..., never())} is the load-bearing half. Admitting the child by *also* asking
   * {@link ScopedPermissions} would leave the role family resolving a contained project, which is
   * the widening V216 refused; the dispatch must answer from containment alone.
   */
  @Test
  void aContainedRunSelectsAndWorksInItsOwnHomeWithoutTheRoleGate() {
    var permissions = mock(ScopedPermissions.class);
    ContainedHomes homes = (actor, named) -> principal.equals(actor) && project.equals(named);
    var access = access(permissions, homes);
    var context = access.forRun(InformationActor.contained(principal, "parent"), Home.of(project));

    assertDoesNotThrow(() -> access.requireSelection(context));
    assertDoesNotThrow(() -> access.requireWork(context));

    verify(permissions, never()).allows(any(), any(), any());
  }

  /** Ownership is its own home and nothing else; a foreign project falls through and is refused. */
  @Test
  void aContainedRunIsRefusedAProjectItDoesNotOwn() {
    var permissions = mock(ScopedPermissions.class);
    when(permissions.allows(any(), any(), any())).thenReturn(false);
    // Owns nothing: the stand-in for a sibling's or an ancestor's project.
    var access = access(permissions, (actor, named) -> false);
    var context = access.forRun(InformationActor.contained(principal, "parent"), Home.of(project));

    assertEquals(
        "this account may not read the selected project's information",
        assertThrows(CallerFault.class, () -> access.requireSelection(context)).getMessage());
  }

  /**
   * The refusal this must preserve. V229 admits WRITE for a contained actor and deliberately not
   * SET_AUDIENCE — a child never decides who may read its output — so ownership of a home must not
   * become audience authority, even for the home it owns.
   */
  @Test
  void aContainedRunThatOwnsItsHomeStillMayNotSetAnAudience() {
    var permissions = mock(ScopedPermissions.class);
    when(permissions.allows(any(), any(), any())).thenReturn(false);
    var access = access(permissions, (actor, named) -> true);
    var context = access.forRun(InformationActor.contained(principal, "parent"), Home.of(project));

    assertEquals(
        "This account needs MANAGER access to change information audiences",
        assertThrows(CallerFault.class, () -> access.requireAudience(context)).getMessage());
  }

  /**
   * The fail-closed default: a composition with no containment source behaves exactly as the server
   * did before this path existed, losing the capability rather than opening one.
   */
  @Test
  void theDefaultSourceRefusesEveryContainedRun() {
    var permissions = mock(ScopedPermissions.class);
    when(permissions.allows(any(), any(), any())).thenReturn(false);
    var access = access(permissions, ContainedHomes.NONE);
    var context = access.forRun(InformationActor.contained(principal, "parent"), Home.of(project));

    assertThrows(CallerFault.class, () -> access.requireSelection(context));
    assertThrows(CallerFault.class, () -> access.requireWork(context));
  }

  /**
   * An ordinary run is untouched: the role gate still decides it, both ways, and a containment
   * source that answered true for an ordinary handle could not smuggle one past the role gate —
   * which is why the production source keys on {@code contained_knowledge_actor} rather than on a
   * comparison of the context's two axes.
   */
  @Test
  void anOrdinaryRunIsStillDecidedByTheRoleGate() {
    var permissions = mock(ScopedPermissions.class);
    var access = access(permissions, ContainedHomes.NONE);
    var home = Home.of("research");
    // Stubbed before the context is built: forRun resolves an ordinary run's selection through the
    // same gate, so an unstubbed mock refuses there and never reaches the assertions below.
    when(permissions.allows(any(), any(), any())).thenReturn(true);
    var context = access.forRun("alice", home);

    assertDoesNotThrow(() -> access.requireSelection(context));

    when(permissions.allows(any(), any(), any())).thenReturn(false);
    assertThrows(CallerFault.class, () -> access.requireSelection(context));
    verify(permissions, atLeastOnce())
        .allows(
            new ScopedPermissions.Principal("alice"),
            new ScopedPermissions.ProjectTarget("research"),
            ScopedPermissions.Action.READ);
  }
}
