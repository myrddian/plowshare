package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.session.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisteredScheduleFilesTest {
  @TempDir Path root;

  @Test
  void deployed_schedule_reads_are_sessionless_and_edits_require_a_new_release() throws Exception {
    var source = new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "server");
    var channel = mock(SessionChannel.class);
    var files =
        new RegisteredScheduleFiles(
            new DataLayout(null),
            channel,
            mock(PresenceRegistry.class),
            mock(SessionRegistry.class));
    files.useApplicationResources(id -> java.util.Optional.of(root));
    Path folder = Files.createDirectories(root.resolve("schedules"));
    Files.writeString(folder.resolve("daily.json"), "{}");
    assertEquals(java.util.List.of(new ScheduleFiles.Entry("daily", "{}")), files.read(source));
    assertNull(files.executionSession(source));
    assertThrows(
        IllegalArgumentException.class, () -> files.write(source, "daily", "changed", true));
    assertThrows(IllegalArgumentException.class, () -> files.delete(source, "daily"));
    verifyNoInteractions(channel);
  }

  @Test
  void createsReadsReplacesAndDeletesARealDefinitionsFile() throws Exception {
    var source = new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "server");
    var data = new DataLayout(root);
    var files =
        new RegisteredScheduleFiles(
            data,
            mock(SessionChannel.class),
            mock(PresenceRegistry.class),
            mock(SessionRegistry.class));
    files.write(source, "daily", "{}", false);
    assertEquals("{}", files.read(source).getFirst().text());
    assertThrows(
        WorkspaceUnavailableException.class,
        () -> files.write(source, "daily", "replacement", false));
    files.write(source, "daily", "replacement", true);
    assertEquals("replacement", Files.readString(data.schedulesFor(7L).resolve("daily.json")));
    files.delete(source, "daily");
    assertTrue(files.read(source).isEmpty());
  }

  @Test
  void refusesLinksAndTraversalWithoutReadingTheirTargets() throws Exception {
    var source = new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "server");
    var data = new DataLayout(root);
    var files =
        new RegisteredScheduleFiles(
            data,
            mock(SessionChannel.class),
            mock(PresenceRegistry.class),
            mock(SessionRegistry.class));
    var folder = data.schedulesFor(7L);
    Files.createDirectories(folder);
    var outside = root.resolve("private.txt");
    Files.writeString(outside, "private");
    Files.createSymbolicLink(folder.resolve("daily.json"), outside);
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(source));
    assertThrows(
        IllegalArgumentException.class, () -> files.write(source, "../escape", "{}", true));
    assertEquals("private", Files.readString(outside));
  }

  @Test
  void readsRemoteNamesInTheirOwnPlatformAndRejectsForeignSessionOwnership() {
    var source = new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "workspace");
    var channel = mock(SessionChannel.class);
    var presences = mock(PresenceRegistry.class);
    var sessions = mock(SessionRegistry.class);
    when(presences.serving("project"))
        .thenReturn(
            java.util.Optional.of(new Presence("live", "machine", "C:\\work\\project", "project")));
    when(sessions.accountOf("live")).thenReturn(java.util.Optional.of("owner"));
    when(channel.ask(eq("live"), any(), any()))
        .thenAnswer(
            call -> {
              io.aeyer.plowshare.protocol.FileRequest request = call.getArgument(1);
              if (request.op().equals("roots"))
                return new io.aeyer.plowshare.protocol.FileReply(
                    request.id(), "ok", null, java.util.List.of("C:\\work\\project"), null, null);
              if (request.op().equals("glob"))
                return new io.aeyer.plowshare.protocol.FileReply(
                    request.id(),
                    "ok",
                    null,
                    java.util.List.of("C:\\work\\project\\.plowshare\\schedules\\daily.json"),
                    null,
                    null);
              assertEquals("schedules", request.purpose());
              return io.aeyer.plowshare.protocol.FileReply.answered(
                  request.id(),
                  io.aeyer.plowshare.protocol.Window.of(0, 2000).cut(java.util.List.of("{}")));
            });
    var files = new RegisteredScheduleFiles(new DataLayout(root), channel, presences, sessions);
    assertEquals(java.util.List.of(new ScheduleFiles.Entry("daily", "{}")), files.read(source));
    when(sessions.accountOf("live")).thenReturn(java.util.Optional.of("other"));
    clearInvocations(channel);
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(source));
    verifyNoInteractions(channel);
  }
}
