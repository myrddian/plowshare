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
   * <p>There is no longer any raise out of {@code forRun(InformationActor, Home)} for a contained
   * actor: it branches to {@code InformationAccess.containedRun}, which delegates to {@link
   * InformationAccess#contained(String, String, String)}.
   *
   * <p>What is pinned is the resolution's four decided properties: the gates are asked about the
   * child's principal, the rows are accounted to its container, the selection is the child's own
   * {@code contained:} project, and {@code includeGlobal} is false — so no gate's global arm, each
   * of which wants a credentialed general-read membership this bare principal has no row for, is
   * ever asked for.
   *
   * <p>{@code verifyNoInteractions(members)} is kept and is now a stronger statement than it was.
   * The contained branch asks <strong>no membership question at all</strong>: it does not call
   * {@code requireSelection}, because a contained project carries no {@code PROJECT} scope and so
   * grants no role to anybody — not the principal and not its container — and relaxing that gate
   * would amount to granting the child credential authority. The run's own {@code
   * KnowledgeReadScope} supplies the ceiling and the SQL arms (V223, V229, V231, V232) supply the
   * admission. A future change that reached for a role here would fail this line.
   *
   * <p>The home has to be a contained one. {@code contained(...)}'s prefix test refuses an ordinary
   * project name such as {@code "research"} outright, so that name cannot serve as the positive
   * fixture here; it is asserted beside the positive case as the refusal it is.
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
    // The MESSAGE is pinned, not just the type: containedRun raises its own CallerFault for a
    // Global home before the project is read, and requireHome raises one too, so a type-only
    // assertion would pass on a refusal from either of those instead of the prefix test.
    assertEquals(
        "A contained write names the child's own contained project",
        assertThrows(
                CallerFault.class,
                () -> access.forRun(actor, io.aeyer.plowshare.protocol.Home.of("research")))
            .getMessage());

    // NO MEMBERSHIP QUESTION, on either call. This is the line a future requireSelection would
    // break, and it is the property that keeps the child credential-free.
    verifyNoInteractions(members);
  }

  /**
   * D1 — one spelling of the principal reaches every gate.
   *
   * <p>The gates disagree about case: {@code contained_knowledge_actor} (V229:31) matches {@code
   * ~*} and so admits an upper-case UUID, and {@code contained_own_home} (V229:248) casts {@code
   * $1::uuid}, while {@code contained_scope_readable}'s self arm compares {@code
   * principal.id::text=$1} (V223:158-160) against a column Postgres renders lower-case. A
   * mixed-case acting string was therefore admitted by every write arm and V232's memory arm and
   * refused by every read arm. {@link InformationActor#contained} canonicalises, so the string that
   * reaches all of them is the one {@code c.id::text} renders.
   *
   * <p>The lower-casing is bounded by {@code contained_knowledge_actor}'s own regex, and the two
   * assertions below are what makes that non-vacuous rather than an assumption: a UUID-shaped
   * string is rewritten, and a string the gate's regex rejects is returned unchanged — in
   * particular {@code "1-1-1-1-1"}, which {@code UUID.fromString} would have expanded into a valid
   * UUID that Postgres' {@code ::uuid} cast refuses. Expanding it would have been a widening.
   */
  @Test
  void aContainedPrincipalReachesEveryGateInOneSpelling() {
    UUID principal = UUID.randomUUID();
    assertEquals(
        principal.toString(),
        InformationActor.contained(
                principal.toString().toUpperCase(java.util.Locale.ROOT), "parent")
            .acting(),
        "a mixed-case UUID is canonicalised to the spelling c.id::text renders");
    assertEquals(
        principal.toString(),
        InformationActor.contained(principal.toString(), "parent").acting(),
        "and an already-canonical one is untouched");
    // Not UUID-shaped: contained_knowledge_actor's regex is false for these, so they are admitted
    // by no contained arm. They must come back exactly as given, which is what keeps the rewrite
    // from accepting anything the gate would not.
    for (String notAUuid : java.util.List.of("1-1-1-1-1", "Alice", "ABC", principal + "x")) {
      assertEquals(notAUuid, InformationActor.contained(notAUuid, "parent").acting());
    }
    // The attribution axis is a handle, where case is significant, and is never rewritten.
    assertEquals(
        "Parent", InformationActor.contained(principal.toString(), "Parent").attribution());
  }
}
