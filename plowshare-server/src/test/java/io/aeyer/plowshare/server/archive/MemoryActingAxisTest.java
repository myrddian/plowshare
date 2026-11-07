package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.server.agents.MemoryTools;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.information.InformationActor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which axis the two memory bindings carry, and the proof that changing it moved no ordinary run.
 *
 * <h2>What changed and why it was a defect</h2>
 *
 * <p>{@code JobRuntime}'s memory bindings used to pass {@code informationActor.attribution()} to
 * {@code MemoryTools.Write} and the run's raw {@code callerHandle} to {@code MemoryTools.Recall}.
 * Both of those are the CONTAINER's handle for a run inside a contained Application. The value
 * lands on {@code MemoryWrites.Request#acting()} / {@code MemoryQueries.Request#acting()}, and
 * every place it is read is an authority gate, not a provenance column: {@code MemoryStore.pin}'s
 * {@code authority_accounts} arm and its {@code contained_memory_actor_allows} arm, the write
 * authority {@code EXISTS} in {@code MemoryStore.require}, {@code information_lock_scope}, and
 * {@code PersonalSpaces.requireOwn} plus {@code ScopedPermissions.allows} in the two {@code Bound*}
 * classes. So the memory authority question for a contained run was asked about its container while
 * every other gate in this slice was asked about the child, and V232's arm — which only a contained
 * principal can select — had no production caller that could present one.
 *
 * <p>It was fail-closed rather than an escalation only because {@code ScopedPermissions} resolves
 * no role on a {@code contained:} project. That is a ceiling one layer above {@code MemoryStore},
 * not a property of the gate, and whether it should open is an open authority decision. Both
 * bindings now pass {@code acting()}.
 *
 * <h2>Why the risk is low, measured rather than assumed</h2>
 *
 * <p>{@link #anUncontainedRunsMemoryRequestsAreUnchanged} is the safety argument. For an
 * uncontained actor {@code acting()} and {@code attribution()} are the same string, so the request
 * a binding builds is the same RECORD, component for component, whichever axis it was given — and
 * record equality here covers the acting axis, the home, the proposal and the origin together, so
 * it is byte-identity and not a resemblance. The {@code alive}/{@code authority}/{@code validity}
 * runnables are deliberately shared between the two bindings so that {@code Origin}'s own record
 * equality is decided by its invocation string rather than by lambda identity.
 *
 * <p>{@link #aContainedRunsMemoryRequestsNameTheChild} is the other half: given the acting axis,
 * the request names the child's bare principal and not the container's handle. <strong>It does not
 * measure which axis {@code JobRuntime} chooses</strong> — it is handed one — so a revert of that
 * choice would not fail it. The assertion that would fail is {@code
 * ContainedInformationActorTest.aContainedRunActsAsItsPrincipalAndIsAttributedToItsContainer}'s
 * call to {@code JobRuntime.memoryActingAxis}, which is the binding's own decision, named so it can
 * be exercised rather than reproduced. What the store then does with the principal is PostgreSQL's,
 * and is measured in {@code ContainedMemorySchemaTest}; nothing here claims a contained run
 * completes a memory write end to end, because above the store {@code ScopedPermissions} still
 * refuses it.
 */
class MemoryActingAxisTest {
  private static final String CONTAINER = "alice";
  private static final UUID PRINCIPAL = UUID.randomUUID();
  private static final String ARGS =
      "{\"summary\":\"claim\",\"scope\":\"scope\",\"body\":\"body\"}";

  private final Archive archive = mock(Archive.class);
  private final Scribe scribe = mock(Scribe.class);

  /** Shared between both bindings so {@code Origin}'s record equality is about the axis. */
  private final Runnable alive = () -> {};

  private final Runnable authority = () -> {};

  private void writing() {
    when(archive.maxBodyChars()).thenReturn(1000);
    when(archive.writing()).thenReturn(true);
    when(archive.write(any()))
        .thenReturn(
            new io.aeyer.plowshare.protocol.WriteResult(
                VerdictKind.NEW, "mem_one", "filed", null, List.of()));
  }

  private void querying() {
    when(archive.querying()).thenReturn(true);
    when(archive.recall(any(MemoryQueries.Request.class)))
        .thenReturn(new Archive.Recall(List.of(), 0));
  }

  /** The write binding, given one axis's value, driven once. */
  private void write(String axis, Home home) {
    var tool =
        new MemoryTools.Write(archive, () -> scribe).forRun(null, "log", axis, alive, authority);
    tool.calledAs("call");
    tool.run(ARGS, home);
  }

  /** The recall binding, given one axis's value, driven once. */
  private void recall(String axis, Home home) {
    var tool = new MemoryTools.Recall(archive).forRun(axis, "log", alive);
    tool.calledAs("call");
    tool.run("{\"question\":\"what is known\"}", home);
  }

  /**
   * The equivalence that makes C1 safe: an ordinary run's requests are the identical record whether
   * the binding is handed the acting axis or the attribution axis.
   */
  @Test
  void anUncontainedRunsMemoryRequestsAreUnchanged() {
    writing();
    querying();
    var actor = InformationActor.of(CONTAINER);
    var home = Home.of("project");
    assertEquals(
        actor.acting(), actor.attribution(), "the premise: an ordinary run's axes are one handle");

    // The write path: once as the binding used to be wired, once as it is now.
    write(actor.attribution(), home);
    write(actor.acting(), home);
    var writes = ArgumentCaptor.forClass(MemoryWrites.Request.class);
    verify(archive, times(2)).write(writes.capture());
    assertEquals(
        writes.getAllValues().getFirst(),
        writes.getAllValues().getLast(),
        "record equality: the acting axis, home, proposal and origin all unchanged");
    assertEquals(CONTAINER, writes.getAllValues().getLast().acting());

    // The read path: once with the raw callerHandle the binding used to pass, once with acting().
    recall(CONTAINER, home);
    recall(actor.acting(), home);
    var reads = ArgumentCaptor.forClass(MemoryQueries.Request.class);
    verify(archive, times(2)).recall(reads.capture());
    assertEquals(
        reads.getAllValues().getFirst(),
        reads.getAllValues().getLast(),
        "record equality, including the embedding origin and the read scope's inputs");
    assertEquals(CONTAINER, reads.getAllValues().getLast().acting());
  }

  /**
   * The behaviour change itself. A contained run's two axes differ, and it is the CHILD that the
   * request names — which is the only way V232's arm can be selected, since the arm is keyed on
   * {@code contained_memory_actor_allows} and no handle can satisfy it.
   */
  @Test
  void aContainedRunsMemoryRequestsNameTheChild() {
    writing();
    querying();
    var actor = InformationActor.contained(PRINCIPAL.toString(), CONTAINER);
    var home = Home.of("contained:" + UUID.randomUUID());
    assertNotEquals(actor.acting(), actor.attribution(), "the premise: the axes differ here");

    write(actor.acting(), home);
    recall(actor.acting(), home);

    var write = ArgumentCaptor.forClass(MemoryWrites.Request.class);
    verify(archive).write(write.capture());
    assertEquals(
        PRINCIPAL.toString(),
        write.getValue().acting(),
        "the write gate is asked about the child, not its container");
    assertNotEquals(CONTAINER, write.getValue().acting());

    var read = ArgumentCaptor.forClass(MemoryQueries.Request.class);
    verify(archive).recall(read.capture());
    assertEquals(
        PRINCIPAL.toString(),
        read.getValue().acting(),
        "and so is the read gate, which is what V232's read arm needs to be selectable");
    assertNotEquals(CONTAINER, read.getValue().acting());
  }

  /**
   * The fence the axis change must not loosen. A run that resolves no information actor gets no
   * identity, and the bindings refuse before any archive call — {@code JobRuntime} passes {@code
   * null} for that case, which is how a contained home with no resolvable principal fails closed
   * instead of reading as its container.
   */
  @Test
  void aRunWithNoResolvedActorReachesNoArchive() {
    writing();
    querying();
    var home = Home.of("project");
    assertThrows(IllegalStateException.class, () -> write(null, home));
    assertThrows(IllegalStateException.class, () -> recall(null, home));
    verify(archive, never()).write(any());
    verify(archive, never()).recall(any(MemoryQueries.Request.class));
  }
}
