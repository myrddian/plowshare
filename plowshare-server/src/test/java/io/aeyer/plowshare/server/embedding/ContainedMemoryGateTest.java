package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.ContainedHomes;
import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.archive.MemoryQueries;
import io.aeyer.plowshare.server.archive.MemoryReadRepository;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code BoundMemoryQueries.requireScope}, asked about a contained run.
 *
 * <p>This gate asked {@link ScopedPermissions} about {@code request.acting()}, which for a
 * contained run is the child's bare principal, and a contained project resolves a role for nobody —
 * so the memory read path refused a contained run one layer above the store that V232 had already
 * opened. {@code ContainedInformationReachDatabaseTest} records that as the SQL fact; this pins the
 * dispatch above it.
 *
 * <p>A zero limit is what makes the gate testable without an embedding pool: {@code read} runs the
 * gate, pins, rechecks and then returns an empty result before any encoder is asked for. So a
 * passing gate is observable as a returned result rather than as the absence of one exception.
 */
class ContainedMemoryGateTest {
  private final String principal = UUID.randomUUID().toString();
  private final Home home = Home.of("contained:" + UUID.randomUUID());

  private MemoryQueries.Request request() {
    return new MemoryQueries.Request(
        principal,
        home,
        MemoryQueries.Kind.RECALL,
        "query",
        0,
        false,
        new QueryEmbeddings.Origin(null, () -> {}));
  }

  private BoundMemoryQueries queries(ScopedPermissions permissions, ContainedHomes homes) {
    var memories = mock(MemoryReadRepository.class);
    return new BoundMemoryQueries(
        memories,
        mock(QuerySources.class),
        UnitOfWork.NONE,
        mock(SemanticEmbedding.class),
        mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
        () -> null,
        () -> 10,
        Clock.systemUTC(),
        permissions,
        homes);
  }

  /** The capability: a child reads its own memory home, and the role gate is never asked. */
  @Test
  void aContainedRunReadsItsOwnMemoryHomeWithoutTheRoleGate() {
    var permissions = mock(ScopedPermissions.class);
    var result =
        queries(
                permissions,
                (actor, named) -> principal.equals(actor) && home.project().equals(named))
            .read(request());

    assertNotNull(result);
    assertEquals(0, result.memories().size());
    verify(permissions, never()).allows(any(), any(), any());
  }

  /**
   * Ownership is its own home only; anything else falls through to the role gate and is refused.
   */
  @Test
  void aContainedRunIsRefusedAHomeItDoesNotOwn() {
    var permissions = mock(ScopedPermissions.class);
    when(permissions.allows(any(), any(), any())).thenReturn(false);

    assertEquals(
        "Memory scope is not readable",
        assertThrows(
                CallerFault.class,
                () -> queries(permissions, (actor, named) -> false).read(request()))
            .getMessage());
  }

  /** The fail-closed default refuses every contained run, as before this path existed. */
  @Test
  void theDefaultSourceRefusesEveryContainedRun() {
    var permissions = mock(ScopedPermissions.class);
    when(permissions.allows(any(), any(), any())).thenReturn(false);

    assertThrows(
        CallerFault.class, () -> queries(permissions, ContainedHomes.NONE).read(request()));
  }

  /**
   * An ordinary run is untouched, and the containment source is not even consulted for it: an
   * ordinary project name fails {@code ownsContainedHome}'s prefix test before any query, which is
   * the property that keeps this gate off the database on the path every run takes.
   */
  @Test
  void anOrdinaryRunNeitherConsultsTheContainmentSourceNorChangesItsAnswer() {
    var permissions = mock(ScopedPermissions.class);
    when(permissions.allows(any(), any(), any())).thenReturn(true);
    var consulted = new boolean[1];
    ContainedHomes homes =
        (actor, named) -> {
          consulted[0] = true;
          return true;
        };
    var ordinary =
        new MemoryQueries.Request(
            "alice",
            Home.of("research"),
            MemoryQueries.Kind.RECALL,
            "query",
            0,
            false,
            new QueryEmbeddings.Origin(null, () -> {}));

    assertNotNull(queries(permissions, homes).read(ordinary));
    assertFalse(consulted[0], "an ordinary project name never reaches the containment source");
    verify(permissions)
        .allows(
            new ScopedPermissions.Principal("alice"),
            new ScopedPermissions.ProjectTarget("research"),
            ScopedPermissions.Action.READ);
  }
}
