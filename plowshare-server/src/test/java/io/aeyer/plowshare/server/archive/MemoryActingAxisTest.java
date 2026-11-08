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
 * What the memory bindings build from each axis, on both sides of the acting/attribution split.
 *
 * <h2>What changed and why it was a defect</h2>
 *
 * <p>{@code JobRuntime} has THREE memory bindings and every one of them used to pass the
 * CONTAINER's identity for a run inside a contained Application: {@code
 * informationActor.attribution()} to {@code MemoryTools.Write}, the run's raw {@code callerHandle}
 * to {@code MemoryTools.Recall}, and the turn's {@code owner} to the automatic reminder's {@code
 * Reminding#forQueries}. (The third was found by a second review, after the first two were fixed;
 * it is covered in {@code ContainedInformationActorTest}, which is where the reminder site is
 * driven.) The value lands on {@code MemoryWrites.Request#acting()} / {@code
 * MemoryQueries.Request#acting()}, and every place it is read is an authority gate, not a
 * provenance column: {@code MemoryStore.pin}'s {@code authority_accounts} arm and its {@code
 * contained_memory_actor_allows} arm, the write authority {@code EXISTS} in {@code
 * MemoryStore.require}, {@code information_lock_scope}, and {@code PersonalSpaces.requireOwn} plus
 * {@code ScopedPermissions.allows} in the two {@code Bound*} classes. So the memory authority
 * question for a contained run was asked about its container while every other gate in this slice
 * was asked about the child.
 *
 * <p>It was fail-closed rather than an escalation only because {@code ScopedPermissions} resolves
 * no role on a {@code contained:} project. That is a ceiling one layer above {@code MemoryStore},
 * not a property of the gate, and whether it should open is an open authority decision. All three
 * bindings now pass {@code acting()}, through {@code JobRuntime.memoryActingAxis}.
 *
 * <p>V232's arms are selectable only by a contained principal, and the fix changed what reaches
 * them asymmetrically. The WRITE arm is now evaluated by a production caller: {@code
 * BoundMemoryWrites.write} calls {@code memories.pin(request)} before any recipient check, and
 * {@code requireRecipient} is reached pre-commit through {@code open()} → {@code require(false)},
 * so the arm is asked with the child's principal and the operation then refuses one layer up
 * without writing. The READ arm is not, because {@code BoundMemoryQueries.requireScope} precedes
 * {@code pin}; it is reached only from a direct store call, which {@code ContainedMemorySchemaTest}
 * makes.
 *
 * <h2>What this class measures, and what measures the rest</h2>
 *
 * <p>Both tests here are handed an axis and assert what the bindings then build. <strong>Neither
 * measures which axis {@code JobRuntime} chooses</strong>, so neither fails if a binding is
 * reverted. What they are for is the shape of the request on each side of the change.
 *
 * <p>{@link #anUncontainedRunsMemoryRequestsAreUnchanged} shows that the two axes produce the same
 * RECORD — component for component, the acting axis, the home, the proposal and the origin
 * together, with the {@code alive}/{@code authority}/{@code validity} runnables shared so {@code
 * Origin}'s own record equality turns on its invocation string and not on lambda identity. On the
 * write half both calls receive literally the same string, so <strong>that assertion cannot fail
 * unless its premise assertion fails first</strong>; it is a statement of the shape, not an
 * independent measurement, and calling it "the safety argument" would be an over-claim. The
 * property it describes — that an uncontained run's axes are one handle, and that the identity the
 * memory path is handed is unchanged — IS measured, in {@code
 * ContainedInformationActorTest.anUncontainedRunActsAndIsAttributedAsItsCaller} and {@code
 * anUncontainedRunsAutomaticReminderIsBoundToItsCaller}, the second of which drives a real run.
 *
 * <p>{@link #aContainedRunsMemoryRequestsNameTheChild} is the other side: given the acting axis,
 * the request names the child's bare principal and not the container's handle. The test that fails
 * on revert of a real binding is {@code
 * ContainedInformationActorTest.aContainedRunsAutomaticReminderIsBoundToTheChildsPrincipal}, which
 * reaches the reminder site through the public {@code Reminding#forQueries} seam; the two {@code
 * MemoryTools} bindings inside the private {@code offeredTo} are covered by no test, which {@code
 * JobRuntime.memoryActingAxis}'s javadoc states. What the store then does with the principal is
 * PostgreSQL's and is measured in {@code ContainedMemorySchemaTest}; nothing here claims a
 * contained run completes a memory write end to end, because above the store {@code
 * ScopedPermissions} still refuses it.
 */
class MemoryActingAxisTest {
  private static final String CONTAINER = "alice";
  private static final UUID PRINCIPAL = UUID.randomUUID();
  private static final String ARGS =
      "{\"summary\":\"claim\",\"scope\":\"scope\",\"body\":\"body\"}";

  private final Archive archive = mock(Archive.class);
  private final Scribe scribe = mock(Scribe.class);

  /** Shared between the two calls so {@code Origin}'s record equality is about the axis. */
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
   * An ordinary run's requests are the identical record whether the binding is handed the acting
   * axis or the attribution axis.
   *
   * <p>On the write half both calls receive literally the same string, so the record-equality
   * assertion cannot fail unless the premise assertion above it fails first. That is the point — it
   * states the shape — but it is not an independent measurement, and the class javadoc names the
   * two tests that do measure the property.
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
