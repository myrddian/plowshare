package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationWritableAreasTest {
  @TempDir Path temporary;

  @Test
  void legacy_application_write_roots_cannot_modify_server_authority_or_manifest()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("legacy"));
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"app\"}");
    Files.createDirectory(root.resolve("server"));
    Path tools =
        Files.writeString(root.resolve("server/tools.json"), "{\"version\":1,\"bindings\":[]}");
    var row = new ProjectRecord("app", root, List.of(), List.of(), "DISJOINT", List.of("."));
    var projects = mock(ProjectWorkspaces.class);
    when(projects.find("app")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(row)).thenReturn(List.of());
    var provider =
        new LocalProvider(
            projects, Home.of("app"), List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)));
    assertThrows(WorkspaceRefusedException.class, () -> provider.write(tools, "changed authority"));
    assertThrows(WorkspaceRefusedException.class, () -> provider.delete(root.resolve("server")));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> provider.write(root.resolve("plowshare.json"), "changed grants"));
    provider.write(root.resolve("output.txt"), "allowed");
    assertEquals("allowed", Files.readString(root.resolve("output.txt")));
  }

  @Test
  void runtime_writes_do_not_grant_user_access_and_user_grants_do_not_widen_runtime_areas()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("source"));
    Path outputs = Files.createDirectory(temporary.resolve("outputs"));
    Path adjacent = Files.createDirectory(temporary.resolve("adjacent"));
    var source = new FileStoreReference("applications", "app");
    var area = new FileStoreReference("artifacts", "reports");
    var row =
        new ProjectRecord(
            "app",
            root,
            List.of(),
            List.of(),
            "DISJOINT",
            List.of(),
            new ApplicationPlacement(source, List.of(area)),
            List.of(outputs));
    var projects = mock(ProjectWorkspaces.class);
    when(projects.find("app")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(row)).thenReturn(List.of(outputs.resolve("private")));
    var writer =
        new LocalProvider(
            projects,
            Home.of("app"),
            List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)),
            io.aeyer.plowshare.server.images.ImageStore.NONE);
    Path report = outputs.resolve("report.txt");
    writer.write(report, "old");
    assertThrows(
        WorkspaceRefusedException.class, () -> writer.write(root.resolve("source.txt"), "no"));
    assertThrows(
        WorkspaceRefusedException.class, () -> writer.write(adjacent.resolve("leak.txt"), "no"));
    assertThrows(
        WorkspaceRefusedException.class, () -> writer.write(outputs.resolve("private"), "no"));
    Files.createSymbolicLink(outputs.resolve("escape"), adjacent);
    assertThrows(
        WorkspaceRefusedException.class,
        () -> writer.write(outputs.resolve("escape/leak.txt"), "no"));
    assertThrows(
        WorkspaceRefusedException.class, () -> writer.move(report, root.resolve("report.txt")));
    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            writer.run(
                outputs,
                List.of("true"),
                EnvironmentFile.Side.DEFAULT,
                Duration.ofSeconds(1),
                () -> false));

    var members = mock(ProjectMembers.class);
    var policy = mock(ApplicationPolicy.class);
    var stores = mock(FileStores.class);
    when(policy.read("app"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.CONTRIBUTOR)));
    when(members.authorityAccount("reader")).thenReturn(Optional.of("reader"));
    when(members.mayWork("app", "reader")).thenReturn(true);
    var files = new WorkspaceApplicationFiles(projects, members, policy, stores);
    var caller = new ApplicationFiles.Caller("app", "reader", area);
    assertThrows(CallerFault.class, () -> files.read(caller, "report.txt"));
    when(stores.permits(area, "reader", ProjectRole.VIEWER)).thenReturn(true);
    assertFalse(files.read(caller, "report.txt").writable());
    when(stores.permits(area, "reader", ProjectRole.CONTRIBUTOR)).thenReturn(true);
    var document = files.read(caller, "report.txt");
    assertEquals(area, document.location());
    files.save(caller, "report.txt", "new", document.revision());
    assertEquals("new", Files.readString(report));
    assertThrows(
        CallerFault.class,
        () ->
            files.list(
                new ApplicationFiles.Caller(
                    "app", "reader", new FileStoreReference("artifacts", "other")),
                ""));
    when(stores.permits(area, "reader", ProjectRole.VIEWER)).thenReturn(false);
    assertThrows(CallerFault.class, () -> files.read(caller, "report.txt"));
    var revoked =
        new ProjectRecord(
            "app",
            root,
            List.of(),
            List.of(),
            "DISJOINT",
            List.of(),
            new ApplicationPlacement(source, List.of()),
            List.of());
    when(projects.find("app")).thenReturn(Optional.of(revoked));
    assertThrows(WorkspaceRefusedException.class, () -> writer.write(report, "revoked"));
    assertEquals("new", Files.readString(report));
  }
}
