package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two-axis run actor, and the proof that carrying it changes nothing for an uncontained run.
 */
class InformationActorTest {
  @Test
  void ofSetsBothAxesToTheSameHandle() {
    var actor = InformationActor.of("alice");
    assertEquals("alice", actor.acting());
    assertEquals("alice", actor.attribution());
  }

  @Test
  void containedSeparatesTheAxes() {
    String principal = UUID.randomUUID().toString();
    var actor = InformationActor.contained(principal, "parent");
    assertEquals(principal, actor.acting());
    assertEquals("parent", actor.attribution());
  }

  @Test
  void blankActingIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> InformationActor.of(""));
    assertThrows(IllegalArgumentException.class, () -> InformationActor.of("  "));
    assertThrows(IllegalArgumentException.class, () -> InformationActor.of(null));
  }

  @Test
  void nullAttributionIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new InformationActor("alice", null));
    assertThrows(IllegalArgumentException.class, () -> new InformationActor("alice", " "));
  }

  /**
   * Review Focus 1 — the regression the happy path does not exercise. An ordinary run must resolve
   * to the identical context whichever overload it arrives through, attribution included.
   */
  @Test
  void anOrdinaryRunResolvesExactlyAsItDidBefore() {
    var members = mock(ProjectMembers.class);
    when(members.role(eq("research"), anyString())).thenReturn(Optional.of(ProjectRole.VIEWER));
    when(members.role(
            eq(io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice")), anyString()))
        .thenReturn(Optional.of(ProjectRole.MANAGER));
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    for (String project :
        java.util.List.of(
            "research", io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice"))) {
      var home = io.aeyer.plowshare.protocol.Home.of(project);
      var byHandle = access.forRun("alice", home);
      var byActor = access.forRun(InformationActor.of("alice"), home);
      // Record equality covers the raw attribution component, which accountedTo would have
      // filled in: an ordinary context is byte-identical, not merely equivalent.
      assertEquals(byHandle, byActor);
      assertEquals(byHandle.acting(), byActor.acting());
      assertEquals(byHandle.attribution(), byActor.attribution());
      assertEquals(byHandle.selection().scope(), byActor.selection().scope());
      assertEquals(byHandle.selection().project(), byActor.selection().project());
      assertEquals(byHandle.selection().includeGlobal(), byActor.selection().includeGlobal());
      assertEquals(byHandle.readBoundary(), byActor.readBoundary());
    }
  }

  /**
   * The precondition the carrier must not strengthen.
   *
   * <p>{@code AskTool} and both {@code DocumentTools} bindings pass a run's nullable {@code
   * callerHandle} into {@code forRun(String, Home)}. A handle-less run was refused there as a
   * caller fault, and a Global home was refused before the handle was looked at; routing the
   * overload through {@link InformationActor#of} would have turned the first into an {@link
   * IllegalArgumentException} for exactly those three tools.
   */
  @Test
  void aHandlelessRunKeepsItsCallerFaultAndTheGlobalRefusalStillComesFirst() {
    var members = mock(ProjectMembers.class);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    for (String absent : java.util.Arrays.asList(null, "", "   ")) {
      for (String project :
          java.util.List.of(
              "research",
              io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice"),
              io.aeyer.plowshare.server.personal.PersonalSpaces.address("alice"))) {
        var home = io.aeyer.plowshare.protocol.Home.of(project);
        assertEquals(
            "Personal space requires an authenticated account",
            assertThrows(CallerFault.class, () -> access.forRun(absent, home)).getMessage());
      }
      // The home check still runs first, so a Global home is refused as a home and not as a
      // missing account.
      assertEquals(
          "A retained run needs an explicit Personal or project home",
          assertThrows(
                  CallerFault.class,
                  () -> access.forRun(absent, io.aeyer.plowshare.protocol.Home.global()))
              .getMessage());
    }
    // And for an authenticated handle, exactly as InformationScopesTest pins it.
    assertEquals(
        "A retained run needs an explicit Personal or project home",
        assertThrows(
                CallerFault.class,
                () -> access.forRun("alice", io.aeyer.plowshare.protocol.Home.global()))
            .getMessage());
    verifyNoInteractions(members);
  }

  /**
   * The seam, visible rather than latent. {@code forRun} cannot resolve differing axes: its body
   * runs {@code PersonalSpaces.resolveAddress}/{@code requireOwn}, which refuse a contained project
   * name outright. A later slice replaces this raise with a branch into {@link
   * InformationAccess#contained(String, String, String)}. Until then the carrier is inert and says
   * so, rather than resolving a contained actor as though it were its container.
   */
  @Test
  void aContainedActorIsNotResolvableYet() {
    var members = mock(ProjectMembers.class);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    var home = io.aeyer.plowshare.protocol.Home.of("research");
    var actor = InformationActor.contained(UUID.randomUUID().toString(), "parent");
    var raised = assertThrows(IllegalStateException.class, () -> access.forRun(actor, home));
    assertTrue(
        raised.getMessage().contains("contained"),
        () -> "unexpected message: " + raised.getMessage());
    verifyNoInteractions(members);
  }
}
