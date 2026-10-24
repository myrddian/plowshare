package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InformationAccessTest {
  @Test
  void retainedReadCeilingIsTheSameForHumansServicesMemoryAndDocuments() {
    var members = mock(ProjectMembers.class);
    when(members.role(eq("research"), anyString())).thenReturn(Optional.of(ProjectRole.VIEWER));
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    var home = io.aeyer.plowshare.protocol.Home.of("research");
    for (String actor : java.util.List.of("alice", "@service/fixture")) {
      for (boolean general : java.util.List.of(false, true)) {
        var boundary = new io.aeyer.plowshare.server.access.KnowledgeReadScope(home, general);
        var context = access.within(boundary).forRun(actor, home);
        assertEquals(boundary, context.readBoundary());
        var wider = new io.aeyer.plowshare.server.access.KnowledgeReadScope(home, true);
        assertEquals(boundary, context.within(wider).readBoundary());
        assertEquals(
            boundary, access.within(boundary).within(wider).forRun(actor, home).readBoundary());
        assertEquals(general, context.selection().includeGlobal());
        assertEquals(
            general
                ? java.util.List.of(home, io.aeyer.plowshare.protocol.Home.global())
                : java.util.List.of(home),
            boundary.memoryHomes());
        var filter = InformationSql.read(context, "r");
        // The boundary is decided once per query: the readable scope set and the general-read
        // permission are hoisted into scalar subqueries, leaving set membership per row. The
        // ceiling is unchanged, which is what this test is about.
        assertTrue(filter.sql().contains("knowledge_document_admitted"));
        assertTrue(filter.sql().contains("knowledge_readable_scopes"));
        assertEquals(
            java.util.List.of(general, actor, actor, "research"), filter.arguments().subList(5, 9));
        assertEquals(
            boundary,
            context
                .withCorpus(InformationContext.Corpus.CODE)
                .withView(true)
                .withFacets(InformationFacets.NONE)
                .readBoundary());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                access
                    .within(boundary)
                    .forRun(actor, io.aeyer.plowshare.protocol.Home.of("other")));
      }
    }
  }

  @Test
  void retainedContextLosesWriteOnDowngradeAndReadOnRemoval() {
    var members = mock(ProjectMembers.class);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    when(members.role("research", "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    var context = access.resolve("alice", InformationContext.Selection.project("research"));
    assertDoesNotThrow(() -> access.requireWork(context));
    when(members.role("research", "alice")).thenReturn(Optional.of(ProjectRole.VIEWER));
    assertDoesNotThrow(() -> access.requireSelection(context));
    assertEquals(
        "This account needs CONTRIBUTOR access to change the selected project's information",
        assertThrows(CallerFault.class, () -> access.requireWork(context)).getMessage());
    when(members.role("research", "alice")).thenReturn(Optional.empty());
    assertEquals(
        "this account may not read the selected project's information",
        assertThrows(CallerFault.class, () -> access.requireWork(context)).getMessage());
  }

  @Test
  void serviceGeneralReadSelectionDoesNotGrantPersonalOrProjectWork() {
    var members = mock(ProjectMembers.class);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    String token = "@service/fixture";
    assertThrows(
        CallerFault.class, () -> access.resolve(token, InformationContext.Selection.personal()));
    var general = access.resolve(token, InformationContext.Selection.globalRead());
    assertThrows(CallerFault.class, () -> access.requireWork(general));
    assertThrows(
        CallerFault.class,
        () -> access.resolve(token, InformationContext.Selection.project("research")));
    when(members.role("research", token)).thenReturn(Optional.of(ProjectRole.VIEWER));
    var project = access.resolve(token, InformationContext.Selection.project("research"));
    assertTrue(project.selection().includeGlobal());
    assertThrows(CallerFault.class, () -> access.requireWork(project));
    when(members.role("research", token)).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    assertDoesNotThrow(() -> access.requireWork(project));
    assertThrows(CallerFault.class, () -> access.requireAudience(project));
  }

  @Test
  void personalRunUsesAccountInformationAndNeverAnotherAccountsPersonalSpace() {
    var members = mock(ProjectMembers.class);
    when(members.role(io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice"), "alice"))
        .thenReturn(java.util.Optional.of(ProjectRole.MANAGER));
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    for (String project :
        java.util.List.of(
            io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice"),
            io.aeyer.plowshare.server.personal.PersonalSpaces.address("alice"))) {
      var context = access.forRun("alice", io.aeyer.plowshare.protocol.Home.of(project));
      assertEquals(
          InformationContext.Selection.project(
              io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice")),
          context.selection());
      assertTrue(context.typedView());
    }
    for (String foreign :
        java.util.List.of(
            io.aeyer.plowshare.server.personal.PersonalSpaces.name("bob"),
            io.aeyer.plowshare.server.personal.PersonalSpaces.address("bob"))) {
      assertThrows(
          CallerFault.class,
          () -> access.forRun("alice", io.aeyer.plowshare.protocol.Home.of(foreign)));
    }
    // A foreign personal space is refused before any permission is consulted, by name.
    verify(members, never())
        .role(argThat(project -> project != null && project.contains("626f62")), anyString());
  }

  @Test
  void personalMappingPreservesServiceProjectAndSharedCeilings() {
    var members = mock(ProjectMembers.class);
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    String machine = "@service/fixture";
    assertThrows(
        CallerFault.class,
        () ->
            access.forRun(
                machine,
                io.aeyer.plowshare.protocol.Home.of(
                    io.aeyer.plowshare.server.personal.PersonalSpaces.name(machine))));
    when(members.role("research", machine)).thenReturn(Optional.of(ProjectRole.VIEWER));
    var context = access.forRun(machine, io.aeyer.plowshare.protocol.Home.of("research"));
    assertEquals(InformationContext.Scope.PROJECT, context.selection().scope());
    assertEquals("research", context.selection().project());
    assertFalse(context.selection().includeGlobal());
  }

  @Test
  void omission_means_personal_plus_shared_and_needs_an_authenticated_account() {
    ProjectMembers members = mock(ProjectMembers.class);
    // Omission means personal, and personal is the account's own project, so it is governed by
    // that project's permissions. The grant is an account default; production's
    // authority_project_role answers MANAGER to an account on its own personal project.
    when(members.role(io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice"), "alice"))
        .thenReturn(java.util.Optional.of(ProjectRole.MANAGER));
    InformationAccess access = new InformationAccess(new EffectiveProjectPermissions(members));
    InformationContext context = access.resolve("alice", null);
    assertEquals(
        InformationContext.Selection.project(
            io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice")),
        context.selection());
    assertThrows(CallerFault.class, () -> access.resolve(null, null));
    assertThrows(CallerFault.class, () -> access.resolve(" ", null));
  }

  @Test
  void project_admission_is_pure_and_rechecks_old_contexts() {
    ProjectMembers members = mock(ProjectMembers.class);
    when(members.role("research", "alice"))
        .thenReturn(Optional.of(ProjectRole.VIEWER))
        .thenReturn(Optional.empty());
    InformationAccess access = new InformationAccess(new EffectiveProjectPermissions(members));
    InformationContext context =
        access.resolve("alice", InformationContext.Selection.project("research"));
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationSql.read(
                access.admitted(context), "d"));
    verify(members, times(2)).role("research", "alice");
    verify(members, never()).isMember(anyString(), anyString());
  }

  @Test
  void selection_shapes_do_not_allow_project_or_sql_injection() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InformationContext.Selection(InformationContext.Scope.PERSONAL, "research", true));
    assertThrows(IllegalArgumentException.class, () -> InformationContext.Selection.project(" "));
    InformationAccess access =
        new InformationAccess(new EffectiveProjectPermissions(injectionMembers()));
    var filter =
        io.aeyer.plowshare.server.information.InformationSql.read(
            access.admitted(access.resolve("alice' OR true --", null)), "d");
    assertFalse(filter.sql().contains("alice"));
    // The resolved project name hex-encodes the handle, so the hostile text cannot appear in the
    // project argument at all; the handle itself remains a bound parameter.
    assertEquals(
        java.util.Arrays.asList(
            "alice' OR true --",
            "project",
            io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice' OR true --"),
            true,
            false),
        filter.arguments());
    assertFalse(
        io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice' OR true --").contains("'"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            io.aeyer.plowshare.server.information.InformationSql.read(
                access.admitted(access.resolve("alice", null)), "d) OR true --"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            io.aeyer.plowshare.server.information.InformationSql.read(
                access.admitted(access.resolve("alice", null)), "ip"));
    assertThrows(
        IllegalArgumentException.class,
        () -> InformationSql.boundary(access.resolve("alice", null), "r.id) OR true --"));
    assertThrows(UnsupportedOperationException.class, () -> filter.arguments().add("bob"));
  }

  /**
   * Personal is the account's own knowledge home. Java admits the selection and the SQL destination
   * function owns the live write policy, so no account-home ADMIN authority is consulted here. The
   * DENY account permissions mirror a deployment that issues no account-knowledge grant at all.
   */
  @Test
  void personal_write_needs_no_account_home_authority() {
    ProjectMembers members = mock(ProjectMembers.class);
    when(members.role(io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice"), "alice"))
        .thenReturn(java.util.Optional.of(ProjectRole.MANAGER));
    var access = new InformationAccess(new EffectiveProjectPermissions(members));
    var personal = access.resolve("alice", InformationContext.Selection.personal());
    // Still no account-home authority: the knowledge gate is never consulted for a personal
    // write. What changed is that personal is the account's own project, so it is governed by
    // that project's permissions rather than by nothing at all.
    assertEquals(InformationContext.Scope.PROJECT, personal.selection().scope());
    assertEquals(
        io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice"),
        personal.selection().project());
    assertDoesNotThrow(() -> access.requireWork(personal));
    assertDoesNotThrow(() -> access.requireSelection(personal));
  }

  /**
   * Account scope addresses the Global knowledge home only, never the caller's own account. Content
   * WRITE and SET_AUDIENCE are separate actions over that home: holding one must not confer the
   * other.
   */
  @Test
  void account_scope_is_the_global_home_with_separate_write_and_audience_authority() {
    ProjectMembers members = mock(ProjectMembers.class);
    var selection =
        InformationContext.Selection.account(
            io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value());
    var denied = new InformationAccess(new EffectiveProjectPermissions(members));
    var unauthorized = denied.resolve("alice", selection);
    assertEquals(InformationContext.Scope.ACCOUNT, unauthorized.selection().scope());
    assertEquals(
        io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value(),
        unauthorized.selection().account());
    assertDoesNotThrow(() -> denied.requireSelection(unauthorized));
    assertEquals(
        "This account needs scoped human ADMIN authority for this account home",
        assertThrows(CallerFault.class, () -> denied.requireWork(unauthorized)).getMessage());
    assertEquals(
        "This account needs scoped human ADMIN authority for this account home",
        assertThrows(CallerFault.class, () -> denied.requireAudience(unauthorized)).getMessage());
    var writeOnly =
        new InformationAccess(
            new EffectiveProjectPermissions(members),
            io.aeyer.plowshare.server.requests.RequestProjects.EXPLICIT_ONLY,
            (actor, account, action) ->
                action == io.aeyer.plowshare.server.access.AuthorityAction.WRITE);
    var writable = writeOnly.resolve("alice", selection);
    assertDoesNotThrow(() -> writeOnly.requireWork(writable));
    assertThrows(CallerFault.class, () -> writeOnly.requireAudience(writable));
    var audienceOnly =
        new InformationAccess(
            new EffectiveProjectPermissions(members),
            io.aeyer.plowshare.server.requests.RequestProjects.EXPLICIT_ONLY,
            (actor, account, action) ->
                action == io.aeyer.plowshare.server.access.AuthorityAction.SET_AUDIENCE);
    var audience = audienceOnly.resolve("alice", selection);
    assertDoesNotThrow(() -> audienceOnly.requireAudience(audience));
    assertThrows(CallerFault.class, () -> audienceOnly.requireWork(audience));
    verifyNoInteractions(members);
  }

  /**
   * The personal project of a hostile handle. {@code PersonalSpaces.name} hex-encodes the handle,
   * so the project name cannot carry the handle's text at all — the injection is neutralised by
   * construction rather than by escaping.
   */
  private static ProjectMembers injectionMembers() {
    var members = mock(ProjectMembers.class);
    when(members.role(anyString(), anyString()))
        .thenReturn(java.util.Optional.of(ProjectRole.MANAGER));
    return members;
  }
}
