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
  private final ScheduleDefinitions service =
      new FileScheduleDefinitions(store, files, projects, members, authority, controls);

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
    when(projects.id("project")).thenReturn(7L);
    assertThrows(
        RuntimeException.class,
        () ->
            service.save(
                "owner", new ScheduledWork.Save("daily", "project", "server", definition, true)));
    verifyNoInteractions(controls);
    verify(files, never()).delete(any(), anyString());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
    verify(store, never()).register(anyString(), any(), anyString());
  }
}
