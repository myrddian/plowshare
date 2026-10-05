package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkspaceRelayProjectFilesTest {
  @Test
  void owning_tier_is_explicit_and_missing_remote_files_never_fall_back_to_server() {
    var projects = mock(ProjectWorkspaces.class);
    var server = mock(RelayProjectFiles.class);
    var remote = mock(RelayProjectFiles.class);
    var access = new RelayProjectFiles.Access("operator", "project", 9);
    var files = new WorkspaceRelayProjectFiles(projects, server, remote);
    when(remote.read(access, "active.json")).thenReturn(Optional.empty());
    assertEquals(Optional.empty(), files.read(access, "active.json"));
    files.requireAccess(access);
    verify(remote).requireAccess(access);
    verifyNoInteractions(server);
    when(remote.read(access, "active.json"))
        .thenThrow(new WorkspaceUnavailableException("offline"));
    assertThrows(WorkspaceUnavailableException.class, () -> files.read(access, "active.json"));
    verifyNoInteractions(server);

    when(projects.find("project"))
        .thenReturn(
            Optional.of(
                new ProjectRecord("project", Path.of("/server/project"), List.of(), List.of())));
    when(server.read(access, "active.json")).thenReturn(Optional.of("server"));
    assertEquals(Optional.of("server"), files.read(access, "active.json"));
    when(projects.find("project")).thenReturn(Optional.empty());
    when(projects.personalOwner("project")).thenReturn(Optional.of("operator"));
    assertEquals(Optional.of("server"), files.read(access, "active.json"));
    files.requireAccess(access);
    verify(server).requireAccess(access);
    assertThrows(IllegalArgumentException.class, () -> files.read(access, "../secret"));
  }
}
