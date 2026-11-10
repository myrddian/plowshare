package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.server.access.ContainedHomes;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.ScopedAuthority;
import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.access.SystemOperations;
import io.aeyer.plowshare.server.agents.scribe.MemoryJudgements;
import io.aeyer.plowshare.server.archive.MemoryFiling;
import io.aeyer.plowshare.server.archive.MemoryWriteRepository;
import io.aeyer.plowshare.server.archive.MemoryWrites;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code BoundMemoryWrites.Write.requireRecipient}, asked about a contained run.
 *
 * <p>The companion of {@link ContainedMemoryGateTest}, which pins the read gate. This gate asked
 * {@link ScopedPermissions} about {@code request.acting()} — for a contained run the child's bare
 * principal, for which a contained project resolves a role for nobody — and so refused a contained
 * run one layer above the store that V232 had already opened.
 *
 * <p>Its condition is {@code !owns && (!READ || !WRITE)}, and that parenthesisation is
 * load-bearing: the ordinary refusal and the contained admission share one expression, so a test
 * that pinned only the admission would leave a dropped negation free to open every project home to
 * every authenticated account. Both directions are asserted here for that reason.
 *
 * <p><b>How the gate is reached without an embedding stack.</b> {@code write} runs {@code
 * Write.open}, whose {@code require()} asks this gate before anything is embedded or filed. With
 * {@code ScopedAuthority.allows} stubbed false, the very next check after the gate — SYSTEM's own
 * authority over the pinned scope — refuses with a distinct message. So {@code "SYSTEM memory
 * operation authority unavailable"} is the observable proof that the recipient gate admitted the
 * caller, and {@code "Memory scope is not writable"} that it did not.
 */
class ContainedMemoryWriteGateTest {
  private static final String PAST_THE_GATE = "SYSTEM memory operation authority unavailable";
  private static final String REFUSED = "Memory scope is not writable";

  private final String principal = UUID.randomUUID().toString();
  private final Home home = Home.of("contained:" + UUID.randomUUID());

  private MemoryWrites.Request request(String acting, Home target) {
    return new MemoryWrites.Request(
        acting,
        target,
        new MemoryProposal("summary", "scope", "body", "agent", ""),
        MemoryWrites.Origin.request(() -> {}));
  }

  /** Runs {@code write} far enough to ask the recipient gate, and returns the refusal it met. */
  private String gate(
      MemoryWrites.Request request, ScopedPermissions permissions, ContainedHomes homes) {
    var archive = mock(MemoryFiling.class);
    when(archive.maxBodyChars()).thenReturn(1 << 20);
    var memories = mock(MemoryWriteRepository.class);
    when(memories.pin(any()))
        .thenReturn(new QuerySources.Pin(UUID.randomUUID(), UUID.randomUUID()));
    var authority = mock(ScopedAuthority.class);
    when(authority.allows(any(), any(), any())).thenReturn(false);
    var writes =
        new BoundMemoryWrites(
            archive,
            mock(MemoryJudgements.class),
            memories,
            mock(QuerySources.class),
            mock(EmbeddingWorkRepository.class),
            UnitOfWork.NONE,
            mock(SemanticEmbedding.class),
            mock(SystemOperations.class),
            mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
            () -> null,
            () -> 0,
            permissions,
            authority,
            mock(IdentityRepository.class),
            homes);

    return assertThrows(CallerFault.class, () -> writes.write(request)).getMessage();
  }

  /** The capability: a child files into its own memory home, and the role gate is never asked. */
  @Test
  void aContainedRunWritesItsOwnMemoryHomeWithoutTheRoleGate() {
    var permissions = mock(ScopedPermissions.class);

    assertEquals(
        PAST_THE_GATE,
        gate(
            request(principal, home),
            permissions,
            (actor, named) -> principal.equals(actor) && home.project().equals(named)));
    verify(permissions, never()).allows(any(), any(), any());
  }

  /**
   * The refusal beside it, which no test had pinned: an account the role gate answers no for is
   * refused, contained source or not. This is the half a dropped negation would invert.
   */
  @Test
  void anAccountWithNoRoleInTheProjectIsRefused() {
    var permissions = mock(ScopedPermissions.class);
    when(permissions.allows(any(), any(), any())).thenReturn(false);

    assertEquals(
        REFUSED, gate(request("alice", Home.of("research")), permissions, (a, n) -> false));
  }

  /** READ alone is not enough; the gate wants both actions, and asks for both. */
  @Test
  void readWithoutWriteIsRefused() {
    var permissions = mock(ScopedPermissions.class);
    var alice = new ScopedPermissions.Principal("alice");
    var research = new ScopedPermissions.ProjectTarget("research");
    when(permissions.allows(alice, research, ScopedPermissions.Action.READ)).thenReturn(true);
    when(permissions.allows(alice, research, ScopedPermissions.Action.WRITE)).thenReturn(false);

    assertEquals(
        REFUSED, gate(request("alice", Home.of("research")), permissions, (a, n) -> false));
  }

  /** The fail-closed default refuses every contained run, as before this path existed. */
  @Test
  void theDefaultSourceRefusesEveryContainedRun() {
    var permissions = mock(ScopedPermissions.class);
    when(permissions.allows(any(), any(), any())).thenReturn(false);

    assertEquals(REFUSED, gate(request(principal, home), permissions, ContainedHomes.NONE));
  }

  /**
   * An ordinary run is untouched, and the containment source is not consulted for it: an ordinary
   * project name fails {@code ownsContainedHome}'s prefix test before any query, which is what
   * keeps this gate off the database on the path every run takes.
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

    assertEquals(PAST_THE_GATE, gate(request("alice", Home.of("research")), permissions, homes));
    assertFalse(consulted[0], "an ordinary project name never reaches the containment source");
    verify(permissions)
        .allows(
            new ScopedPermissions.Principal("alice"),
            new ScopedPermissions.ProjectTarget("research"),
            ScopedPermissions.Action.WRITE);
  }
}
