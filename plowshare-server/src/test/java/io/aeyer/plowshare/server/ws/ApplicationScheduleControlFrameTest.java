package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.events.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationScheduleControlFrameTest {
  @TempDir Path root;

  @Test
  void servicePauseAndResumePassBothLivePermissionLayersAndPreserveHistoricalOwner()
      throws Exception {
    String executor = "@service/fixture";
    Files.writeString(
        root.resolve("plowshare.json"),
        "{\"version\":1,\"name\":\"application\",\"executionAccount\":\"" + executor + "\"}");
    var source = new ScheduleDefinitionStore.Source(1, "installer", 7L, "application", "server");
    var definition =
        new ScheduledWork(
            1,
            "0 */15 * * * *",
            "UTC",
            true,
            new ScheduledWork.Action("agent", "analyst", null, "Inspect observations", null),
            new ScheduledWork.Target("mailbox", null, null, null, null),
            new ScheduledWork.Limits(1, 1, 1));
    var file =
        new ScheduledWork.File(
            "daily",
            "application",
            "server",
            "schedules/daily.json",
            "scheduled-1-daily",
            definition,
            "active",
            null);
    var store = mock(ScheduleDefinitionStore.class);
    when(store.sourceOf(file.internalName())).thenReturn(Optional.of(source));
    when(store.managed(file.internalName(), "installer")).thenReturn(Optional.of(file));
    when(store.files(source)).thenReturn(List.of(file));
    var files = mock(ScheduleFiles.class);
    when(files.applicationOwned(source)).thenReturn(true);
    when(files.read(source))
        .thenReturn(
            List.of(new ScheduleFiles.Entry("daily", ScheduleDefinitionCodec.write(definition))));
    var resources = mock(ApplicationResources.class);
    when(resources.root(7L)).thenReturn(Optional.of(root));
    var members = mock(ProjectMembers.class);
    when(members.mayWork("application", "installer")).thenReturn(true);
    when(members.mayWork("application", executor)).thenReturn(true);
    var action = mock(ScheduleDefinitions.Authority.class);
    ScheduleDefinitions definitions =
        new FileScheduleDefinitions(
            store,
            files,
            mock(ProjectStore.class),
            members,
            action,
            new ApplicationScheduleControlAuthority(resources, members));
    var schedules = mock(ScheduleStore.class);
    var frames =
        new EventFrames(
            schedules,
            mock(TriggerStore.class),
            mock(FiringStore.class),
            mock(io.aeyer.plowshare.server.events.EventPublication.class),
            mock(Callers.class),
            mock(ScheduleReader.class));
    frames.useScheduleDefinitions(
        new org.springframework.beans.factory.support.StaticListableBeanFactory(
                Map.of("definitions", definitions))
            .getBeanProvider(ScheduleDefinitions.class));
    var scope = mock(ResourceScopeRepository.class);
    when(scope.projects(
            new AccessRequest.Resource(ResourceScopeRepository.Kind.SCHEDULE, file.internalName())))
        .thenReturn(List.of("application"));
    var accounts = mock(AdminStore.class);
    var router = new FrameRoutingConfig().frameRouter(List.of(frames));
    router.useAuthorization(new ProjectAuthorization(scope, members, accounts));
    for (boolean paused : List.of(true, false)) {
      var outcome =
          router.route(
              FrameParity.frame(
                  "schedule.pause",
                  "{\"schedule\":\"scheduled-1-daily\",\"paused\":" + paused + "}"),
              new Asking("session", executor));
      assertEquals(Code.NO_CONTENT, outcome.code(), outcome.said());
      verify(store).pause(eq(source), eq("daily"), eq(definition), eq(paused), any());
    }
    verify(schedules, never()).pause(anyString(), anyBoolean(), anyString());
    verify(files, never()).write(any(), anyString(), anyString(), anyBoolean());
    verifyNoInteractions(accounts);
    when(members.mayWork("application", executor)).thenReturn(false);
    var refused =
        router.route(
            FrameParity.frame(
                "schedule.pause", "{\"schedule\":\"scheduled-1-daily\",\"paused\":false}"),
            new Asking("session", executor));
    assertNotEquals(Code.NO_CONTENT, refused.code());
    verify(store, times(2)).pause(any(), anyString(), any(), anyBoolean(), any());
  }
}
