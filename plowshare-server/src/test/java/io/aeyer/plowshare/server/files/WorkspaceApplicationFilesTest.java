package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceApplicationFilesTest {
  @TempDir Path root;
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ProjectMembers members = mock(ProjectMembers.class);
  ApplicationPolicy policy = mock(ApplicationPolicy.class);
  ApplicationFiles files = new WorkspaceApplicationFiles(projects, members, policy);
  ApplicationFiles.Caller caller = new ApplicationFiles.Caller("app", "reader");
  ProjectRecord row;

  @BeforeEach
  void source() throws Exception {
    row = new ProjectRecord("app", root, List.of(), List.of(), "DISJOINT", List.of("generated"));
    when(projects.find("app")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(row)).thenReturn(List.of(root.resolve("private")));
    when(policy.read("app"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.CONTRIBUTOR)));
    when(members.mayWork("app", "reader")).thenReturn(true);
    Files.createDirectory(root.resolve("generated"));
    Files.writeString(root.resolve("generated/output.txt"), "old");
    Files.writeString(root.resolve("source.txt"), "source");
  }

  @Test
  void server_authority_is_readable_but_not_directly_editable_even_with_source_write_access()
      throws Exception {
    row = new ProjectRecord("app", root, List.of(), List.of(), "DISJOINT", List.of("."));
    when(projects.find("app")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(row)).thenReturn(List.of());
    when(members.mayManage("app", "reader")).thenReturn(true);
    Files.createDirectory(root.resolve("server"));
    Files.writeString(root.resolve("server/tools.json"), "{\"version\":1,\"bindings\":[]}");
    var source = files.read(caller, "server/tools.json");
    assertFalse(source.writable());
    assertThrows(
        CallerFault.class, () -> files.save(caller, source.path(), "changed", source.revision()));
  }

  @Test
  void lists_and_reads_server_files_without_a_local_attachment() {
    var listing = files.list(caller, "");
    assertEquals(
        List.of("generated", "source.txt"),
        listing.entries().stream().map(ApplicationFiles.Entry::name).toList());
    assertEquals("source", files.read(caller, "source.txt").text());
    assertFalse(files.read(caller, "source.txt").writable());
    assertTrue(files.read(caller, "generated/output.txt").writable());
  }

  @Test
  void saves_only_the_reviewed_revision_and_writable_area() throws Exception {
    var old = files.read(caller, "generated/output.txt");
    var saved = files.save(caller, old.path(), "new", old.revision());
    assertEquals("new", Files.readString(root.resolve(old.path())));
    assertNotEquals(old.revision(), saved.revision());
    assertThrows(
        CallerFault.class, () -> files.save(caller, old.path(), "overwrite", old.revision()));
    var source = files.read(caller, "source.txt");
    assertThrows(
        CallerFault.class, () -> files.save(caller, source.path(), "new", source.revision()));
    when(members.mayWork("app", "reader")).thenReturn(false);
    assertFalse(files.read(caller, old.path()).writable());
    assertThrows(
        CallerFault.class, () -> files.save(caller, old.path(), "overwrite", saved.revision()));
    assertEquals("new", Files.readString(root.resolve(old.path())));
  }

  @Test
  void refuses_traversal_links_exclusions_binary_oversize_and_revoked_applications()
      throws Exception {
    Files.createSymbolicLink(root.resolve("linked"), root.resolve("source.txt"));
    Files.writeString(root.resolve("private"), "secret");
    assertFalse(
        files.list(caller, "").entries().stream()
            .anyMatch(e -> e.name().equals("linked") || e.name().equals("private")));
    for (String path :
        List.of(
            "../outside",
            "/absolute",
            "generated/../source.txt",
            "generated\\output.txt",
            ".git/config",
            "linked",
            "private")) assertThrows(CallerFault.class, () -> files.read(caller, path), path);
    Files.writeString(
        root.resolve("generated/output.txt"), "x".repeat(WorkspaceApplicationFiles.MAX_BYTES + 1));
    assertThrows(CallerFault.class, () -> files.read(caller, "generated/output.txt"));
    Files.write(root.resolve("generated/output.txt"), new byte[] {(byte) 0xff});
    assertThrows(CallerFault.class, () -> files.read(caller, "generated/output.txt"));
    when(policy.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertThrows(CallerFault.class, () -> files.list(caller, ""));
    when(policy.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of()));
    assertThrows(CallerFault.class, () -> files.read(caller, "source.txt"));
  }

  @Test
  void membership_revocation_and_malformed_text_never_replace_source() throws Exception {
    var before = files.read(caller, "generated/output.txt");
    assertThrows(
        CallerFault.class, () -> files.save(caller, before.path(), "\uD800", before.revision()));
    doThrow(new CallerFault("No Application access"))
        .when(members)
        .requireRole("app", "reader", ProjectRole.VIEWER);
    assertThrows(CallerFault.class, () -> files.read(caller, before.path()));
    assertThrows(
        CallerFault.class, () -> files.save(caller, before.path(), "new", before.revision()));
    assertEquals("old", Files.readString(root.resolve(before.path())));
  }

  @Test
  void replacement_preserves_posix_executable_permissions() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.getFileStore(root).supportsFileAttributeView("posix"));
    var path = root.resolve("generated/output.txt");
    var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---");
    Files.setPosixFilePermissions(path, permissions);
    var before = files.read(caller, "generated/output.txt");
    files.save(caller, before.path(), "new", before.revision());
    assertEquals(permissions, Files.getPosixFilePermissions(path));
  }

  @Test
  void root_runtime_resources_need_manager_authority_even_in_a_writable_application()
      throws Exception {
    row = new ProjectRecord("app", root, List.of(), List.of(), "MANAGED", List.of("."));
    when(projects.find("app")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(row)).thenReturn(List.of());
    for (String path :
        List.of(
            "AGENTS.md",
            "AGENT.md",
            "skills.yml",
            "environment.yml",
            "agents/worker.md",
            "bots/worker.md",
            "skills/review/SKILL.md",
            "orchestrations/review.md",
            "hooks/rule.js",
            "schedules/daily.json",
            "swarm/review.md",
            "Relay/topics.json")) {
      Path target = root.resolve(path);
      Files.createDirectories(target.getParent());
      Files.writeString(target, "source");
      when(members.mayManage("app", "reader")).thenReturn(false);
      var document = files.read(caller, path);
      assertFalse(document.writable(), path);
      assertThrows(
          CallerFault.class, () -> files.save(caller, path, "changed", document.revision()), path);
      when(members.mayManage("app", "reader")).thenReturn(true);
      assertTrue(files.read(caller, path).writable(), path);
    }
  }

  @Test
  void access_manifest_edits_need_manager_authority_and_remain_valid() throws Exception {
    row = new ProjectRecord("app", root, List.of(), List.of(), "MANAGED", List.of("."));
    when(projects.find("app")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(row)).thenReturn(List.of());
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"app\"}");
    var document = files.read(caller, "plowshare.json");
    assertFalse(document.writable());
    assertThrows(
        CallerFault.class,
        () -> files.save(caller, document.path(), document.text(), document.revision()));
    when(members.mayManage("app", "reader")).thenReturn(true);
    assertTrue(files.read(caller, document.path()).writable());
    assertThrows(
        CallerFault.class,
        () -> files.save(caller, document.path(), "invalid", document.revision()));
    files.save(caller, document.path(), document.text(), document.revision());
    assertEquals(document.text(), Files.readString(root.resolve(document.path())));
  }
}
