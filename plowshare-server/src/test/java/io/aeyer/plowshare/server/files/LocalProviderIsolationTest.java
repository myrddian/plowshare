package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.CommandIsolation;
import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.images.ImageStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProviderIsolationTest {
  @TempDir Path root;
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final AtomicInteger starts = new AtomicInteger();
  private EnvironmentFile.Side side;
  private Path output;
  private ProjectRecord project;

  @BeforeEach
  void prepare() throws Exception {
    root = root.toRealPath();
    output = Files.createDirectory(root.resolve("output"));
    project = new ProjectRecord("test", root, List.of(), List.of(), "DISJOINT", List.of("output"));
    when(projects.find("test")).thenReturn(Optional.of(project));
    when(projects.effectiveExclusions(project)).thenReturn(List.of());
    side =
        EnvironmentFile.Side.DEFAULT.with(
            EnvironmentFile.parse("server:\n  mode: open\n  isolation: bubblewrap\n").server());
  }

  private LocalProvider provider(Mode mode) {
    CommandIsolation isolation =
        (command, reads, writes, host, cancelled) -> {
          starts.incrementAndGet();
          assertEquals(root, command.cwd());
          assertTrue(reads.permits(root));
          assertTrue(writes.permits(output.resolve("new.txt")));
          assertFalse(writes.permits(root.resolve("private.txt")));
          return new CommandRunner.Outcome(0, false, false, "", 0, "", 0, 1);
        };
    return new LocalProvider(
        projects,
        Home.of("test"),
        List.of(new Grant(Scope.WORKSPACE, mode)),
        ImageStore.NONE,
        isolation);
  }

  @Test
  void supplies_the_current_read_and_write_fences_without_requiring_a_writable_cwd() {
    provider(Mode.WRITE)
        .run(root, List.of("test-program"), side, Duration.ofSeconds(1), () -> false);
    assertEquals(1, starts.get());
  }

  @Test
  void refuses_read_only_grants_before_the_backend_can_start() {
    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            provider(Mode.READ)
                .run(root, List.of("test-program"), side, Duration.ofSeconds(1), () -> false));
    assertEquals(0, starts.get());
  }

  @Test
  void refuses_an_empty_write_policy_before_the_backend_can_start() {
    project = new ProjectRecord("test", root, List.of(), List.of(), "DISJOINT", List.of());
    when(projects.find("test")).thenReturn(Optional.of(project));
    when(projects.effectiveExclusions(project)).thenReturn(List.of());
    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            provider(Mode.WRITE)
                .run(root, List.of("test-program"), side, Duration.ofSeconds(1), () -> false));
    assertEquals(0, starts.get());
  }

  @Test
  void preserves_unconfined_refusals_and_does_not_fall_back_when_unconfigured() {
    var raw =
        new EnvironmentFile.Side(
            "open", false, List.of(), Map.of(), Duration.ofSeconds(1), 1024, "none");
    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            provider(Mode.WRITE)
                .run(output, List.of("test-program"), raw, Duration.ofSeconds(1), () -> false));
    var unconfigured =
        new LocalProvider(
            projects, Home.of("test"), List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)));
    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            unconfigured.run(
                root, List.of("test-program"), side, Duration.ofSeconds(1), () -> false));
    assertEquals(0, starts.get());
  }
}
