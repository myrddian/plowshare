package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class InformationAccessTest {
  @Test
  void personalRunUsesAccountInformationAndNeverAnotherAccountsPersonalSpace() {
    var members = mock(ProjectMembers.class);
    var access = new InformationAccess(members);
    for (String project :
        java.util.List.of(
            io.aeyer.plowshare.server.personal.PersonalSpaces.name("alice"),
            io.aeyer.plowshare.server.personal.PersonalSpaces.address("alice"))) {
      var context = access.forRun("alice", io.aeyer.plowshare.protocol.Home.of(project));
      assertEquals(InformationContext.Selection.personal(), context.selection());
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
    verifyNoInteractions(members);
  }

  @Test
  void personalMappingPreservesServiceProjectAndSharedCeilings() {
    var members = mock(ProjectMembers.class);
    var access = new InformationAccess(members);
    String machine = "@service/fixture";
    assertThrows(
        CallerFault.class,
        () ->
            access.forRun(
                machine,
                io.aeyer.plowshare.protocol.Home.of(
                    io.aeyer.plowshare.server.personal.PersonalSpaces.name(machine))));
    when(members.mayUse("research", machine)).thenReturn(true);
    var context = access.forRun(machine, io.aeyer.plowshare.protocol.Home.of("research"));
    assertEquals(InformationContext.Scope.PROJECT, context.selection().scope());
    assertEquals("research", context.selection().project());
    assertFalse(context.selection().includeShared());
  }

  @Test
  void omission_means_personal_plus_shared_and_needs_an_authenticated_account() {
    ProjectMembers members = mock(ProjectMembers.class);
    InformationAccess access = new InformationAccess(members);
    InformationContext context = access.resolve("alice", null);
    assertEquals(InformationContext.Selection.personal(), context.selection());
    assertThrows(CallerFault.class, () -> access.resolve(null, null));
    assertThrows(CallerFault.class, () -> access.resolve(" ", null));
    verifyNoInteractions(members);
  }

  @Test
  void project_admission_is_pure_and_rechecks_old_contexts() {
    ProjectMembers members = mock(ProjectMembers.class);
    when(members.mayUse("research", "alice")).thenReturn(true, false);
    InformationAccess access = new InformationAccess(members);
    InformationContext context =
        access.resolve("alice", InformationContext.Selection.project("research"));
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationSql.read(
                access.admitted(context), "d"));
    verify(members, times(2)).mayUse("research", "alice");
    verify(members, never()).isMember(anyString(), anyString());
  }

  @Test
  void selection_shapes_do_not_allow_project_or_sql_injection() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InformationContext.Selection(InformationContext.Scope.PERSONAL, "research", true));
    assertThrows(IllegalArgumentException.class, () -> InformationContext.Selection.project(" "));
    InformationAccess access = new InformationAccess(mock(ProjectMembers.class));
    var filter =
        io.aeyer.plowshare.server.information.InformationSql.read(
            access.admitted(access.resolve("alice' OR true --", null)), "d");
    assertFalse(filter.sql().contains("alice"));
    assertEquals(
        java.util.Arrays.asList("alice' OR true --", "personal", null, true, false),
        filter.arguments());
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
    assertThrows(UnsupportedOperationException.class, () -> filter.arguments().add("bob"));
  }
}
