package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleDefinitionsTest {
  private final ScheduleDefinitionStore store = mock(ScheduleDefinitionStore.class);
  private final ScheduleFiles files = mock(ScheduleFiles.class);
  private final ProjectStore projects = mock(ProjectStore.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ScheduleDefinitions.Authority authority = mock(ScheduleDefinitions.Authority.class);
  private final ScheduleDefinitionStore.Source source =
      new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "server");
  private final ScheduledWork definition =
      ScheduleDefinitionCodecTest.definition("agent", null, null);
  private final ScheduledWork.File prior =
      new ScheduledWork.File(
          "daily",
          "project",
          "server",
          "schedules/daily.json",
          "scheduled-1-daily",
          definition,
          "active",
          null);
  private final ScheduleControlAuthority controls = mock(ScheduleControlAuthority.class);
  private final ApplicationProjects applications = mock(ApplicationProjects.class);
  private final ApplicationScheduleAudit audit = mock(ApplicationScheduleAudit.class);
  private final ScheduleDefinitions service =
      new FileScheduleDefinitions(
          store, files, projects, members, authority, controls, applications, audit);

  @BeforeEach
  void configure() {
    when(store.sources()).thenReturn(List.of(source));
    when(store.files(source)).thenReturn(List.of(prior));
    when(store.snapshot(source, "daily"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduleDefinitionStore.FileSnapshot(
                    source, prior.name(), prior.internalName(), definition, null, "active", null)));
    when(members.mayWork("project", "owner")).thenReturn(true);
  }

  @Test
  void editsRevalidateAndProjectWhileAnInvalidSiblingIsRefused() {
    when(files.read(source))
        .thenReturn(
            List.of(
                new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition)),
                new ScheduleFiles.Entry("broken", "{bad}")));
    service.poll();
    verify(authority).validate(source, definition);
    verify(store).apply(eq(source), eq("daily"), eq(definition), any());
    verify(store).reject(eq(source), eq("broken"), anyString());
    verify(store, never()).remove(any(), anyString());
  }

  @Test
  void missingFilesAreRemovedOnlyAfterACompleteSuccessfulScan() {
    when(files.read(source)).thenReturn(List.of());
    service.poll();
    verify(store).remove(source, "daily");
  }

  @Test
  void anOfflineFolderSuspendsRatherThanDeletesItsOldProjection() {
    when(files.read(source)).thenThrow(new WorkspaceUnavailableException("offline"));
    service.poll();
    verify(store).reject(source, "daily", "offline");
    verify(store, never()).remove(any(), anyString());
  }

  @Test
  void revokedAuthorityStopsEvenUnchangedFilesBeforeIO() {
    when(members.mayWork("project", "owner")).thenReturn(false);
    service.poll();
    verifyNoInteractions(files, authority);
    verify(store).reject(eq(source), eq("daily"), contains("execution access"));
  }

  @Test
  void savingBindsOwnershipAndWritesBeforeReconciliation() {
    when(projects.id("project")).thenReturn(7L);
    when(members.mayManage("project", "owner")).thenReturn(true);
    when(store.register("owner", 7L, "server")).thenReturn(source);
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    var result =
        service.save(
            "owner", new ScheduledWork.Save("daily", "project", "server", definition, false));
    assertEquals(prior, result);
    verify(store).files(source); // deletion discovery only, never a final full-source reload
    verify(store).snapshot(source, "daily");
    var order = inOrder(files, store);
    order.verify(files).write(source, "daily", ScheduleDefinitionCodec.write(definition), false);
    order.verify(files).read(source);
    order.verify(store).apply(eq(source), eq("daily"), eq(definition), any());
  }

  @Test
  void aFirstOfflineEnrollmentReportsFailureInsteadOfAnEmptySuccessfulScan() {
    when(projects.id("project")).thenReturn(7L);
    when(members.mayManage("project", "owner")).thenReturn(true);
    when(store.register("owner", 7L, "server")).thenReturn(source);
    when(store.files(source)).thenReturn(List.of());
    when(files.read(source)).thenThrow(new WorkspaceUnavailableException("offline"));
    assertThrows(
        RuntimeException.class,
        () -> service.sync("owner", new ScheduledWork.Sync("project", "server")));
    verify(store, never()).remove(any(), anyString());
  }

  @Test
  void duplicateNamesRefuseTheScanBeforeAnyProjection() {
    when(files.read(source))
        .thenReturn(
            List.of(
                new ScheduleFiles.Entry("new", ScheduleDefinitionCodec.write(definition)),
                new ScheduleFiles.Entry("new", ScheduleDefinitionCodec.write(definition))));
    service.poll();
    verify(store, never()).apply(any(), anyString(), any(), any());
    verify(store).reject(eq(source), eq("daily"), contains("Duplicate"));
  }

  @Test
  void pausingAnInvalidFileDoesNotOverwriteItWithTheLastGoodDefinition() {
    when(store.sourceOf("scheduled-1-daily", "owner")).thenReturn(java.util.Optional.of(source));
    when(store.managed("scheduled-1-daily", "owner"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduledWork.File(
                    prior.name(),
                    prior.project(),
                    prior.source(),
                    prior.path(),
                    prior.internalName(),
                    definition,
                    "refused",
                    "invalid JSON")));
    when(files.read(source)).thenReturn(List.of(new ScheduleFiles.Entry("daily", "{broken}")));
    assertThrows(RuntimeException.class, () -> service.pause("scheduled-1-daily", true, "owner"));
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void applicationPauseAndResumeControlRuntimeWithoutWritingARelease() {
    when(store.sourceOf(prior.internalName(), "owner")).thenReturn(java.util.Optional.of(source));
    when(store.managed(prior.internalName(), "owner")).thenReturn(java.util.Optional.of(prior));
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    when(files.applicationOwned(source)).thenReturn(true);
    when(members.mayManage("project", "owner")).thenReturn(true);
    assertTrue(service.pause(prior.internalName(), true, "owner"));
    assertTrue(service.pause(prior.internalName(), false, "owner"));
    verify(store).pause(eq(source), eq("daily"), eq(definition), eq(true), any());
    verify(store).pause(eq(source), eq("daily"), eq(definition), eq(false), any());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void applicationControlRequiresCurrentOperationalAuthority() {
    when(store.sourceOf(prior.internalName(), "owner")).thenReturn(java.util.Optional.of(source));
    when(store.managed(prior.internalName(), "owner")).thenReturn(java.util.Optional.of(prior));
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    when(files.applicationOwned(source)).thenReturn(true);
    doThrow(new io.aeyer.plowshare.server.faults.CallerFault("Operational access revoked"))
        .when(controls)
        .requireApplicationControl(source, "owner");
    assertThrows(RuntimeException.class, () -> service.pause(prior.internalName(), false, "owner"));
    verify(store, never()).pause(any(), anyString(), any(), anyBoolean(), any());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void applicationControlRequiresCurrentActionGrantsEvenForAManager() {
    when(store.sourceOf(prior.internalName(), "owner")).thenReturn(java.util.Optional.of(source));
    when(store.managed(prior.internalName(), "owner")).thenReturn(java.util.Optional.of(prior));
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    when(files.applicationOwned(source)).thenReturn(true);
    when(members.mayManage("project", "owner")).thenReturn(true);
    doThrow(new io.aeyer.plowshare.server.faults.CallerFault("Action grant revoked"))
        .when(authority)
        .validate(source, definition);
    assertThrows(RuntimeException.class, () -> service.pause(prior.internalName(), false, "owner"));
    verify(store, never()).pause(any(), anyString(), any(), anyBoolean(), any());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void foreignAndUnavailableSourcesCannotControlAnApplication() {
    assertFalse(service.pause(prior.internalName(), false, "foreign"));
    when(store.sourceOf(prior.internalName(), "owner")).thenReturn(java.util.Optional.of(source));
    when(files.read(source)).thenThrow(new WorkspaceUnavailableException("offline"));
    assertThrows(RuntimeException.class, () -> service.pause(prior.internalName(), false, "owner"));
    verify(store, never()).pause(any(), anyString(), any(), anyBoolean(), any());
  }

  @Test
  void projectInspectionIncludesAnotherOwnersSourceButChecksLiveAccess() {
    var foreign =
        new ScheduleDefinitionStore.Source(2, "other-owner", 8L, "other-project", "server");
    when(store.sources()).thenReturn(List.of(source, foreign));
    assertEquals(List.of(prior), service.list("@service/fixture", "project"));
    verify(members).requireRole("project", "@service/fixture", ProjectRole.VIEWER);
    verify(store, never()).files(foreign);
    doThrow(new io.aeyer.plowshare.server.faults.CallerFault("revoked"))
        .when(members)
        .requireRole("project", "@service/fixture", ProjectRole.VIEWER);
    clearInvocations(store);
    assertThrows(RuntimeException.class, () -> service.list("@service/fixture", "project"));
    verifyNoInteractions(store);
  }

  @Test
  void pagedInspectionAuthorizesUniqueProjectSourcesBeforeAnyDefinitionHydration() {
    var workspace = new ScheduleDefinitionStore.Source(2, "owner", 7L, "project", "workspace");
    var denied = new ScheduleDefinitionStore.Source(3, "owner", 8L, "denied", "server");
    when(store.sourcesForInspection("owner", null)).thenReturn(List.of(source, workspace, denied));
    when(members.readableProjects(java.util.Set.of("project", "denied"), "owner"))
        .thenReturn(java.util.Set.of("project"));
    when(members.readableProjects(java.util.Set.of("project"), "owner"))
        .thenReturn(java.util.Set.of("project"));
    var page = new SchedulePage("scheduled-1-before", 2);
    var selection =
        new ScheduleDefinitionStore.FileSelection("owner", null, List.of(source, workspace));
    when(store.filesPage(selection, page)).thenReturn(List.of(prior));
    assertEquals(List.of(prior), service.page("owner", null, page));
    verify(members).readableProjects(java.util.Set.of("project", "denied"), "owner");
    verify(members).readableProjects(java.util.Set.of("project"), "owner");
    verify(members, never()).mayUse(anyString(), anyString());
    verify(store).filesPage(selection, page);
    verify(store, never()).files(any());
    verify(store, never()).sources();
    verifyNoInteractions(files);
  }

  @Test
  void unscopedPageRefusesWhenAnAdmittedProjectIsRevokedDuringItsRead() {
    when(store.sourcesForInspection("owner", null)).thenReturn(List.of(source));
    when(members.readableProjects(java.util.Set.of("project"), "owner"))
        .thenReturn(java.util.Set.of("project"))
        .thenReturn(java.util.Set.of());
    var page = new SchedulePage(null, 1);
    var selection = new ScheduleDefinitionStore.FileSelection("owner", null, List.of(source));
    when(store.filesPage(selection, page)).thenReturn(List.of(prior));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> service.page("owner", null, page));
    verify(members, times(2)).readableProjects(java.util.Set.of("project"), "owner");
    verify(store).filesPage(selection, page);
    verify(members, never()).mayUse(anyString(), anyString());
  }

  @Test
  void unscopedPageRejectsAFileWhoseSourceMovedIntoAnUnreadableProjectDuringLoad() {
    when(store.sourcesForInspection("owner", null)).thenReturn(List.of(source));
    when(members.readableProjects(java.util.Set.of("project"), "owner"))
        .thenReturn(java.util.Set.of("project"));
    when(members.readableProjects(java.util.Set.of("moved-project"), "owner"))
        .thenReturn(java.util.Set.of());
    var moved =
        new ScheduledWork.File(
            prior.name(),
            "moved-project",
            prior.source(),
            prior.path(),
            prior.internalName(),
            prior.definition(),
            prior.status(),
            prior.error());
    var selection = new ScheduleDefinitionStore.FileSelection("owner", null, List.of(source));
    var page = new SchedulePage(null, 1);
    when(store.filesPage(selection, page)).thenReturn(List.of(moved));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> service.page("owner", null, page));
    verify(members).readableProjects(java.util.Set.of("project"), "owner");
    verify(members).readableProjects(java.util.Set.of("moved-project"), "owner");
    verify(store).filesPage(selection, page);
  }

  @Test
  void unscopedDisclosureRechecksOnlyActualReturnedProjectsWithinThePageBound() {
    var other = new ScheduleDefinitionStore.Source(2, "owner", 8L, "another-project", "server");
    when(store.sourcesForInspection("owner", null)).thenReturn(List.of(source, other));
    var candidates = java.util.Set.of("project", "another-project");
    when(members.readableProjects(candidates, "owner")).thenReturn(candidates);
    when(members.readableProjects(java.util.Set.of("project"), "owner"))
        .thenReturn(java.util.Set.of("project"));
    var selection =
        new ScheduleDefinitionStore.FileSelection("owner", null, List.of(source, other));
    var page = new SchedulePage(null, 1);
    when(store.filesPage(selection, page)).thenReturn(List.of(prior));
    assertEquals(List.of(prior), service.page("owner", null, page));
    verify(members).readableProjects(candidates, "owner");
    verify(members).readableProjects(java.util.Set.of("project"), "owner");
    verifyNoMoreInteractions(members);
  }

  @Test
  void pagedProjectInspectionKeepsForeignRegistrarAndRefusesWithdrawalAfterCacheRead() {
    when(store.sourcesForInspection("viewer", "project")).thenReturn(List.of(source));
    var page = new SchedulePage(null, 1);
    var selection = new ScheduleDefinitionStore.FileSelection("viewer", "project", List.of(source));
    when(store.filesPage(selection, page)).thenReturn(List.of(prior));
    assertEquals(List.of(prior), service.page("viewer", "project", page));
    verify(members, times(2)).requireRole("project", "viewer", ProjectRole.VIEWER);
    doNothing()
        .doThrow(new io.aeyer.plowshare.server.faults.CallerFault("revoked"))
        .when(members)
        .requireRole("project", "viewer", ProjectRole.VIEWER);
    assertThrows(RuntimeException.class, () -> service.page("viewer", "project", page));
    verify(store, never()).files(any());
  }

  @Test
  void projectInspectionRefusesPrivateGlobalAndUntrimmedNamesBeforeReading() {
    for (String project :
        new String[] {"", " project", "personal:owner", "Personal:owner", "client:owner"})
      assertThrows(RuntimeException.class, () -> service.list("owner", project));
    verify(store, never()).sources();
  }

  @Test
  void executorControlsHistoricalRegistrarWithoutTransferringOwnership() {
    when(store.sourceOf(prior.internalName())).thenReturn(java.util.Optional.of(source));
    when(store.managed(prior.internalName(), source.account()))
        .thenReturn(java.util.Optional.of(prior));
    when(files.applicationOwned(source)).thenReturn(true);
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    assertTrue(service.pause(prior.internalName(), true, "executor"));
    verify(controls, times(2)).requireApplicationControl(source, "executor");
    verify(authority, atLeastOnce()).validate(source, definition);
    verify(store).pause(eq(source), eq("daily"), eq(definition), eq(true), any());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
    verify(store, never()).register(anyString(), any(), anyString());
  }

  @Test
  void foreignApplicationControlRefusesBeforeReconciliationAndNoLegacyFallback() {
    when(store.sourceOf(prior.internalName())).thenReturn(java.util.Optional.of(source));
    when(files.applicationOwned(source)).thenReturn(true);
    doThrow(new io.aeyer.plowshare.server.faults.CallerFault("foreign"))
        .when(controls)
        .requireApplicationControl(source, "foreign");
    assertThrows(
        RuntimeException.class, () -> service.pause(prior.internalName(), true, "foreign"));
    verify(files, never()).read(any());
    verify(store, never()).pause(any(), anyString(), any(), anyBoolean(), any());
  }

  @Test
  void mutableForeignSourceRetainsOwnerBoundary() {
    when(store.sourceOf(prior.internalName())).thenReturn(java.util.Optional.of(source));
    assertFalse(service.pause(prior.internalName(), true, "foreign"));
    verifyNoInteractions(controls);
    verify(files, never()).read(any());
  }

  @Test
  void revocationDuringReconciliationFencesOperationalMutation() {
    when(store.sourceOf(prior.internalName())).thenReturn(java.util.Optional.of(source));
    when(store.managed(prior.internalName(), source.account()))
        .thenReturn(java.util.Optional.of(prior));
    when(files.applicationOwned(source)).thenReturn(true);
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    doNothing()
        .doThrow(new io.aeyer.plowshare.server.faults.CallerFault("revoked"))
        .when(controls)
        .requireApplicationControl(source, "executor");
    assertThrows(
        RuntimeException.class, () -> service.pause(prior.internalName(), true, "executor"));
    verify(store, never()).pause(any(), anyString(), any(), anyBoolean(), any());
  }

  @Test
  void operationalAuthorityDoesNotAuthorizeDefinitionAuthoringOrRemoval() {
    when(store.sourceOf(prior.internalName(), "owner")).thenReturn(java.util.Optional.of(source));
    assertThrows(RuntimeException.class, () -> service.forget(prior.internalName(), "owner"));
    // The Application save admits a current project Manager only. Operational (pause) authority
    // and Contributor work access do not reach it.
    application(ApplicationProjects.ProjectType.DISJOINT);
    when(members.mayManage("project", "owner")).thenReturn(false);
    when(members.mayManage("project", "operator")).thenReturn(false);
    when(members.mayWork("project", "operator")).thenReturn(true);
    for (String actor : List.of("owner", "operator"))
      assertThrows(
          io.aeyer.plowshare.server.faults.CallerFault.class,
          () ->
              service.save(
                  actor, new ScheduledWork.Save("daily", "project", "server", definition, true)));
    verifyNoInteractions(controls, audit);
    verify(files, never()).delete(any(), anyString());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
    verify(files, never()).writeApplication(any(), anyString(), anyString(), anyBoolean());
    verify(store, never()).register(anyString(), any(), anyString());
  }

  /** A hand-registered Application whose server source "owner" registered. */
  private void application(ApplicationProjects.ProjectType type) {
    when(projects.id("project")).thenReturn(7L);
    when(files.applicationOwned(
            argThat(
                s ->
                    s != null
                        && Long.valueOf(7L).equals(s.projectId())
                        && s.source().equals("server"))))
        .thenReturn(true);
    when(applications.find(7L))
        .thenReturn(
            java.util.Optional.of(new ApplicationProjects.ApplicationProject("project", type)));
    when(store.registered(7L, "server")).thenReturn(java.util.Optional.of(source));
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
  }

  private ScheduledWork.File saveApplication(String actor, ScheduledWork work) {
    return service.save(actor, new ScheduledWork.Save("daily", "project", "server", work, true));
  }

  @Test
  void managerSavesDisjointApplicationScheduleInPlace() {
    application(ApplicationProjects.ProjectType.DISJOINT);
    when(members.mayManage("project", "owner")).thenReturn(true);
    assertEquals(prior, saveApplication("owner", definition));
    var order = inOrder(authority, files, store, audit);
    order.verify(authority).validate(source, definition);
    order
        .verify(files)
        .writeApplication(source, "daily", ScheduleDefinitionCodec.write(definition), true);
    order.verify(files).read(source);
    order.verify(store).apply(eq(source), eq("daily"), eq(definition), any());
    order.verify(audit).saved("owner", "project/daily");
    verify(store, never()).register(anyString(), any(), anyString());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void managerWhoIsNotTheRegistrarCanSave() {
    application(ApplicationProjects.ProjectType.DISJOINT);
    when(members.mayManage("project", "manager")).thenReturn(true);
    assertEquals(prior, saveApplication("manager", definition));
    // Actions are validated under the registered owner, whose registration is never changed:
    // once before the write and again when reconciliation projects the file.
    verify(authority, times(2)).validate(source, definition);
    verify(authority, never())
        .validate(argThat(s -> s != null && s.account().equals("manager")), any());
    verify(files)
        .writeApplication(source, "daily", ScheduleDefinitionCodec.write(definition), true);
    verify(audit).saved("manager", "project/daily");
    verify(store, never()).register(anyString(), any(), anyString());
  }

  @Test
  void contributorCannotSaveApplicationSchedule() {
    application(ApplicationProjects.ProjectType.DISJOINT);
    when(members.mayWork("project", "contributor")).thenReturn(true);
    var refusal =
        assertThrows(
            io.aeyer.plowshare.server.faults.CallerFault.class,
            () -> saveApplication("contributor", definition));
    assertEquals(
        "Managing schedule definitions requires project MANAGER access", refusal.getMessage());
    verify(files, never()).writeApplication(any(), anyString(), anyString(), anyBoolean());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
    verifyNoInteractions(authority, audit);
  }

  @Test
  void missingRegisteredSourceIsRefused() {
    application(ApplicationProjects.ProjectType.DISJOINT);
    when(members.mayManage("project", "owner")).thenReturn(true);
    when(store.registered(7L, "server")).thenReturn(java.util.Optional.empty());
    var refusal =
        assertThrows(
            io.aeyer.plowshare.server.faults.CallerFault.class,
            () -> saveApplication("owner", definition));
    assertEquals("No registered schedule source for this Application", refusal.getMessage());
    verify(files, never()).writeApplication(any(), anyString(), anyString(), anyBoolean());
    verify(store, never()).register(anyString(), any(), anyString());
    verifyNoInteractions(authority, audit);
  }

  @Test
  void actionsTheOwnerLacksAreRefusedBeforeWriting() {
    application(ApplicationProjects.ProjectType.DISJOINT);
    when(members.mayManage("project", "manager")).thenReturn(true);
    doThrow(new io.aeyer.plowshare.server.faults.CallerFault("The owner cannot run that agent"))
        .when(authority)
        .validate(source, definition);
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> saveApplication("manager", definition));
    verify(files, never()).writeApplication(any(), anyString(), anyString(), anyBoolean());
    verifyNoInteractions(audit);
  }

  @Test
  void aRegisteredOwnerWithoutExecutionAccessIsRefusedBeforeWriting() {
    application(ApplicationProjects.ProjectType.DISJOINT);
    when(members.mayManage("project", "manager")).thenReturn(true);
    when(members.mayWork("project", "owner")).thenReturn(false);
    var refusal =
        assertThrows(
            io.aeyer.plowshare.server.faults.CallerFault.class,
            () -> saveApplication("manager", definition));
    assertEquals(
        "The registered schedule owner no longer has execution access", refusal.getMessage());
    verify(files, never()).writeApplication(any(), anyString(), anyString(), anyBoolean());
    verifyNoInteractions(authority, audit);
  }

  @Test
  void invalidCronIsRefusedBeforeWriting() {
    application(ApplicationProjects.ProjectType.DISJOINT);
    when(members.mayManage("project", "owner")).thenReturn(true);
    var invalid =
        new ScheduledWork(
            definition.version(),
            "not a cron",
            definition.zone(),
            definition.paused(),
            definition.action(),
            definition.target(),
            definition.limits());
    assertThrows(RuntimeException.class, () -> saveApplication("owner", invalid));
    verify(files, never()).writeApplication(any(), anyString(), anyString(), anyBoolean());
    verifyNoInteractions(audit);
  }

  @Test
  void managedApplicationSavesAwaitTheRevisionPath() {
    application(ApplicationProjects.ProjectType.MANAGED);
    when(members.mayManage("project", "owner")).thenReturn(true);
    var refusal =
        assertThrows(
            UnsupportedOperationException.class, () -> saveApplication("owner", definition));
    assertEquals(
        "MANAGED Application schedule saves arrive in the next task", refusal.getMessage());
    verify(files, never()).writeApplication(any(), anyString(), anyString(), anyBoolean());
    verifyNoInteractions(audit);
  }

  @Test
  void workspaceAndGlobalSavesUnchanged() {
    // An ordinary project server source still registers and writes through the ordinary path.
    when(projects.id("project")).thenReturn(7L);
    when(members.mayManage("project", "owner")).thenReturn(true);
    when(store.register("owner", 7L, "server")).thenReturn(source);
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    assertEquals(prior, saveApplication("owner", definition));
    verify(files).write(source, "daily", ScheduleDefinitionCodec.write(definition), true);

    // A workspace source and a global source are never Application sources.
    var workspace = new ScheduleDefinitionStore.Source(2, "owner", 7L, "project", "workspace");
    when(store.register("owner", 7L, "workspace")).thenReturn(workspace);
    when(store.snapshot(workspace, "daily"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduleDefinitionStore.FileSnapshot(
                    workspace, "daily", "scheduled-2-daily", definition, null, "active", null)));
    when(files.read(workspace))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    service.save(
        "owner", new ScheduledWork.Save("daily", "project", "workspace", definition, false));
    verify(files).write(workspace, "daily", ScheduleDefinitionCodec.write(definition), false);

    var global = new ScheduleDefinitionStore.Source(3, "admin", null, null, "server");
    when(members.isServerAdmin("admin")).thenReturn(true);
    when(store.register("admin", null, "server")).thenReturn(global);
    when(store.snapshot(global, "daily"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduleDefinitionStore.FileSnapshot(
                    global, "daily", "scheduled-3-daily", definition, null, "active", null)));
    when(files.read(global))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    service.save("admin", new ScheduledWork.Save("daily", null, "server", definition, true));
    verify(files).write(global, "daily", ScheduleDefinitionCodec.write(definition), true);

    verify(files, never()).writeApplication(any(), anyString(), anyString(), anyBoolean());
    verifyNoInteractions(applications, audit);
  }
}
