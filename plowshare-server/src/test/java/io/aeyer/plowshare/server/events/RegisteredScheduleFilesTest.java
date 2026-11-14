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
    assertTrue(files.applicationOwned(source));
    verifyNoInteractions(channel);
  }

  @Test
  void deployedWriteStillRefusesThroughOrdinaryWrite() throws Exception {
    var files = application();
    Path folder = Files.createDirectories(root.resolve("schedules"));
    Files.writeString(folder.resolve("daily.json"), "{}");
    var refusal =
        assertThrows(
            IllegalArgumentException.class, () -> files.write(SOURCE, "daily", "changed", true));
    assertEquals(
        "Application schedules are written only through the Application save path",
        refusal.getMessage());
    assertEquals("{}", Files.readString(folder.resolve("daily.json")));
  }

  @Test
  void deleteStillRefusedWithNewMessage() throws Exception {
    var files = application();
    Path folder = Files.createDirectories(root.resolve("schedules"));
    Files.writeString(folder.resolve("daily.json"), "{}");
    var refusal = assertThrows(IllegalArgumentException.class, () -> files.delete(SOURCE, "daily"));
    assertEquals(
        "Application schedules cannot be deleted; save a replacement definition instead",
        refusal.getMessage());
    assertTrue(Files.exists(folder.resolve("daily.json")));
  }

  @Test
  void applicationWriteReplacesAtomicallyWithOverwrite() throws Exception {
    var files = application();
    Path folder = Files.createDirectories(root.resolve("schedules"));
    Files.writeString(folder.resolve("daily.json"), "{}");
    files.writeApplication(SOURCE, "daily", "replacement", true);
    assertEquals("replacement", Files.readString(folder.resolve("daily.json")));
    try (var left = Files.list(folder)) {
      // The staged temporary file is renamed over the target, never left beside it.
      assertEquals(java.util.List.of(folder.resolve("daily.json")), left.toList());
    }
  }

  @Test
  void applicationWriteRefusesExistingWithoutOverwrite() throws Exception {
    var files = application();
    Path folder = Files.createDirectories(root.resolve("schedules"));
    Files.writeString(folder.resolve("daily.json"), "{}");
    var refusal =
        assertThrows(
            io.aeyer.plowshare.server.faults.CallerFault.class,
            () -> files.writeApplication(SOURCE, "daily", "replacement", false));
    assertEquals("A schedule named daily already exists", refusal.getMessage());
    assertEquals("{}", Files.readString(folder.resolve("daily.json")));
    files.writeApplication(SOURCE, "weekly", "{}", false);
    assertEquals("{}", Files.readString(folder.resolve("weekly.json")));
    try (var left = Files.list(folder)) {
      assertEquals(2, left.count());
    }
  }

  @Test
  void applicationWriteCreatesMissingSchedulesFolderInsideRoot() throws Exception {
    var files = application();
    assertFalse(Files.exists(root.resolve("schedules")));
    files.writeApplication(SOURCE, "daily", "{}", false);
    assertEquals("{}", Files.readString(root.resolve("schedules").resolve("daily.json")));
    assertThrows(
        IllegalArgumentException.class,
        () -> files.writeApplication(SOURCE, "../escape", "{}", true));
    assertFalse(Files.exists(root.resolve("escape.json")));
  }

  @Test
  void applicationWriteRefusesSymlinkedSchedulesFolder() throws Exception {
    var files = application();
    Path elsewhere = Files.createTempDirectory("elsewhere");
    try {
      Files.createSymbolicLink(root.resolve("schedules"), elsewhere);
      assertThrows(
          WorkspaceUnavailableException.class,
          () -> files.writeApplication(SOURCE, "daily", "{}", true));
      try (var written = Files.list(elsewhere)) {
        assertEquals(0, written.count());
      }
    } finally {
      Files.deleteIfExists(elsewhere);
    }
  }

  @Test
  void applicationWriteEnforcesSizeLimit() throws Exception {
    var files = application();
    var refusal =
        assertThrows(
            IllegalArgumentException.class,
            () -> files.writeApplication(SOURCE, "daily", "x".repeat(65537), true));
    assertEquals("Schedule file exceeds 64 KiB", refusal.getMessage());
    assertFalse(Files.exists(root.resolve("schedules").resolve("daily.json")));
    // The folder's count limit is checked before a new file would make it unreadable.
    Path folder = Files.createDirectories(root.resolve("schedules"));
    for (int i = 0; i < 256; i++) Files.writeString(folder.resolve("s" + i + ".json"), "{}");
    // A write that would cross a limit is the caller's fault, not an unavailable workspace.
    var count =
        assertThrows(
            io.aeyer.plowshare.server.faults.CallerFault.class,
            () -> files.writeApplication(SOURCE, "extra", "{}", false));
    assertEquals("Schedule folder exceeds its file or byte limit", count.getMessage());
    assertFalse(Files.exists(folder.resolve("extra.json")));
    // Replacing an existing file does not add to the count.
    files.writeApplication(SOURCE, "s0", "{\"v\":1}", true);
    // The 1 MiB folder total is enforced too.
    for (int i = 0; i < 17; i++)
      Files.writeString(folder.resolve("s" + i + ".json"), "x".repeat(60000));
    var total =
        assertThrows(
            io.aeyer.plowshare.server.faults.CallerFault.class,
            () -> files.writeApplication(SOURCE, "s20", "x".repeat(60000), true));
    assertEquals("Schedule folder exceeds its file or byte limit", total.getMessage());
    assertEquals("{}", Files.readString(folder.resolve("s20.json")));
  }

  /**
   * Swaps {@code schedules/} for a link to a folder outside the root at one checkpoint, as a
   * concurrent writer on the host could, and asserts nothing lands outside the root.
   */
  private void refusesFolderSwappedAt(String point) throws Exception {
    Path elsewhere = Files.createTempDirectory("elsewhere");
    try {
      refusesFolderSwappedAt(point, elsewhere.resolve("moved"));
    } finally {
      Files.deleteIfExists(elsewhere);
    }
  }

  /**
   * Swaps {@code schedules/} for a link to {@code moved} at one checkpoint and asserts the write is
   * refused, nothing lands in {@code moved} and the staged file is removed.
   */
  private void refusesFolderSwappedAt(String point, Path moved) throws Exception {
    var files = application();
    Path folder = Files.createDirectories(root.resolve("schedules"));
    files.checkpoint =
        at -> {
          if (!at.equals(point)) return;
          try {
            Files.move(folder, moved);
            Files.createSymbolicLink(folder, moved);
          } catch (java.io.IOException failed) {
            throw new java.io.UncheckedIOException(failed);
          }
        };
    try {
      assertThrows(
          WorkspaceUnavailableException.class,
          () -> files.writeApplication(SOURCE, "daily", "{}", true));
      try (var written = Files.list(moved)) {
        assertEquals(java.util.List.of(), written.toList());
      }
    } finally {
      Files.deleteIfExists(folder);
      try (var left = Files.list(moved)) {
        for (Path path : left.toList()) Files.delete(path);
      }
      Files.deleteIfExists(moved);
    }
  }

  @Test
  void applicationWriteRefusesFolderSwappedForALinkBeforeStaging() throws Exception {
    refusesFolderSwappedAt("stage");
  }

  @Test
  void applicationWriteRefusesFolderSwappedForALinkBeforeTheMove() throws Exception {
    refusesFolderSwappedAt("move");
  }

  /**
   * A link to another folder inside the same root ({@code agents/}) is refused too: the folder's
   * real path must be the root's own {@code schedules/}, not merely somewhere under the root.
   */
  @Test
  void applicationWriteRefusesFolderSwappedForALinkInsideTheRootBeforeStaging() throws Exception {
    refusesFolderSwappedAt("stage", root.resolve("agents"));
  }

  @Test
  void applicationWriteRefusesFolderSwappedForALinkInsideTheRootBeforeTheMove() throws Exception {
    refusesFolderSwappedAt("move", root.resolve("agents"));
  }

  private static final ScheduleDefinitionStore.Source SOURCE =
      new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "server");

  private RegisteredScheduleFiles application() {
    var files =
        new RegisteredScheduleFiles(
            new DataLayout(null),
            mock(SessionChannel.class),
            mock(PresenceRegistry.class),
            mock(SessionRegistry.class));
    files.useApplicationResources(id -> java.util.Optional.of(root));
    return files;
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
