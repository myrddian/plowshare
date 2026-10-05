package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalWorkspaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerRelayProjectFilesTest {
  @TempDir Path directory;
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final PersonalWorkspaces personal = mock(PersonalWorkspaces.class);
  private final RelayProjectFiles.Access access =
      new RelayProjectFiles.Access("operator", "project", 9);
  private final RelayProjectFiles files = new ServerRelayProjectFiles(projects, members, personal);
  private ProjectRecord project;

  @BeforeEach
  void workspace() throws Exception {
    directory = directory.toRealPath();
    project = new ProjectRecord("project", directory, List.of(), List.of(), "default", List.of());
    when(members.mayWork("project", "operator")).thenReturn(true);
    when(projects.id("project")).thenReturn(9L);
    when(projects.find("project")).thenReturn(Optional.of(project));
    when(projects.effectiveExclusions(project)).thenReturn(List.of());
    Files.createDirectories(directory.resolve("Relay/notices/scripts"));
  }

  @Test
  void personal_uses_only_the_owning_private_union_and_refuses_foreign_or_missing_storage()
      throws Exception {
    String name = "personal:6f70657261746f72";
    var own = new RelayProjectFiles.Access("operator", name, 10);
    when(members.mayWork(name, "operator")).thenReturn(true);
    when(projects.id(name)).thenReturn(10L);
    when(projects.personalOwner(name)).thenReturn(Optional.of("operator"));
    when(personal.workspace(name)).thenReturn(Optional.of(directory));
    Files.writeString(directory.resolve("Relay/active.json"), "{\"version\":1,\"active\":[]}");
    assertEquals(Optional.of("{\"version\":1,\"active\":[]}"), files.read(own, "active.json"));
    verify(projects, never()).find(name);
    verify(projects, never()).effectiveExclusions(any());
    when(projects.personalOwner(name)).thenReturn(Optional.of("someone"));
    assertThrows(CallerFault.class, () -> files.read(own, "active.json"));
    when(projects.personalOwner(name)).thenReturn(Optional.of("operator"));
    when(personal.workspace(name)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> files.read(own, "active.json"));
  }

  @Test
  void missing_activation_is_empty_and_exact_source_is_preserved() throws Exception {
    assertEquals(Optional.empty(), files.read(access, "active.json"));
    String source = "export const greeting = '雪';\n";
    Files.writeString(directory.resolve("Relay/notices/routes.js"), source);
    assertEquals(Optional.of(source), files.read(access, "notices/routes.js"));
  }

  @Test
  void live_membership_stable_identity_and_remote_workspace_are_required() {
    when(members.mayWork("project", "operator")).thenReturn(false);
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    verifyNoInteractions(projects);
    when(members.mayWork("project", "operator")).thenReturn(true);
    when(projects.id("project")).thenReturn(10L);
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    when(projects.id("project")).thenReturn(9L);
    when(projects.find("project")).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    assertThrows(
        CallerFault.class,
        () -> files.requireAccess(new RelayProjectFiles.Access("operator", "client:private", 9)));
  }

  @Test
  void traversal_links_nonregular_files_and_exclusions_fail() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> files.read(access, "../secret"));
    assertThrows(
        IllegalArgumentException.class,
        () -> files.read(access, "notices/scripts/../../secret.js"));
    Files.createSymbolicLink(
        directory.resolve("Relay/notices/routes.js"), directory.resolve("missing-private.js"));
    assertThrows(CallerFault.class, () -> files.read(access, "notices/routes.js"));
    Files.delete(directory.resolve("Relay/notices/routes.js"));
    Files.createDirectory(directory.resolve("Relay/notices/routes.js"));
    assertThrows(CallerFault.class, () -> files.read(access, "notices/routes.js"));
    when(projects.effectiveExclusions(project)).thenReturn(List.of(directory.resolve("Relay")));
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
  }

  @Test
  void linked_parent_even_with_missing_file_is_refused() throws Exception {
    Path outside = Files.createDirectory(directory.resolve("outside"));
    Files.createSymbolicLink(directory.resolve("Relay/linked"), outside);
    assertThrows(CallerFault.class, () -> files.read(access, "linked/routes.js"));
  }

  @Test
  void oversized_or_invalid_utf8_files_fail_without_parser_or_path_excerpts() throws Exception {
    Path active = directory.resolve("Relay/active.json");
    Files.writeString(active, "x".repeat(65537));
    assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    Files.write(active, new byte[] {(byte) 0xC3, (byte) 0x28});
    var error = assertThrows(CallerFault.class, () -> files.read(access, "active.json"));
    assertNull(error.getCause());
    assertFalse(error.getMessage().contains(directory.toString()));
    Files.writeString(directory.resolve("Relay/notices/routes.js"), "x".repeat(131073));
    assertThrows(CallerFault.class, () -> files.read(access, "notices/routes.js"));
  }
}
