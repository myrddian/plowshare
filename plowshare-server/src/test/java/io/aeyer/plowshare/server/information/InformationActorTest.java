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
   * The seam is closed, and this is what it resolves to.
   *
   * <p><strong>This method used to assert the opposite.</strong> It was {@code
   * aContainedActorIsNotResolvableYet} and it pinned an {@code IllegalStateException} out of {@code
   * forRun}, placed deliberately one slice earlier so that the unwired resolution path was visible
   * to the compiler and the suite rather than latent. That raise is <strong>gone</strong>: {@code
   * forRun(InformationActor, Home)} now branches to {@code InformationAccess.containedRun}, which
   * delegates to {@link InformationAccess#contained(String, String, String)}. A reader looking for
   * the former raise will not find one, and should not go hunting.
   *
   * <p>What is pinned instead is the resolution's four decided properties: the gates are asked
   * about the child's principal, the rows are accounted to its container, the selection is the
   * child's own {@code contained:} project, and {@code includeGlobal} is false — so no gate's
   * global arm, each of which wants a credentialed general-read membership this bare principal has
   * no row for, is ever asked for.
   *
   * <p>{@code verifyNoInteractions(members)} is kept and is now a stronger statement than it was.
   * The contained branch asks <strong>no membership question at all</strong>: it does not call
   * {@code requireSelection}, because a contained project carries no {@code PROJECT} scope and so
   * grants no role to anybody — not the principal and not its container — and relaxing that gate
   * would amount to granting the child credential authority. The run's own {@code
   * KnowledgeReadScope} supplies the ceiling and the SQL arms (V223, V229, V231, V232) supply the
   * admission. A future change that reached for a role here would fail this line.
   *
   * <p>The home is a contained one, which the old fixture's {@code "research"} no longer is the
   * right shape for: an ordinary project is refused by {@code contained(...)}'s own prefix test, so
   * it is asserted beside the positive case rather than used as it.
   */
  @Test
  void aContainedActorResolvesWithoutAskingAboutMembership() {
    var members = mock(ProjectMembers.class);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    String principal = UUID.randomUUID().toString();
    String project = "contained:" + UUID.randomUUID();
    var actor = InformationActor.contained(principal, "parent");

    var context = access.forRun(actor, io.aeyer.plowshare.protocol.Home.of(project));

    assertEquals(principal, context.acting(), "the gates are asked about the child");
    assertEquals("parent", context.attribution(), "the rows are accounted to its container");
    assertEquals(InformationContext.Scope.PROJECT, context.selection().scope());
    assertEquals(project, context.selection().project(), "the child's own home, by name");
    assertFalse(context.selection().includeGlobal(), "Global is excluded, deliberately");

    // An ordinary project home on the contained branch is refused by contained(...)'s prefix test,
    // so the branch cannot be used to resolve somebody else's project under a bare principal.
    assertThrows(
        CallerFault.class,
        () -> access.forRun(actor, io.aeyer.plowshare.protocol.Home.of("research")));

    // NO MEMBERSHIP QUESTION, on either call. This is the line a future requireSelection would
    // break, and it is the property that keeps the child credential-free.
    verifyNoInteractions(members);
  }
}
