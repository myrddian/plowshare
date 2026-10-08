package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceApplicationResourcesTest {
  @TempDir Path root;
  ProjectWorkspaces projects;
  ProjectNames names;
  ApplicationPolicy policy;
  WorkspaceApplicationResources resources;

  @BeforeEach
  void setup() {
    projects = mock(ProjectWorkspaces.class);
    names = mock(ProjectNames.class);
    policy = mock(ApplicationPolicy.class);
    when(names.nameForId(7)).thenReturn(Optional.of("app"));
    when(policy.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()));
    when(projects.find("app"))
        .thenReturn(
            Optional.of(
                new ProjectRecord("app", root, List.of(), List.of(), "MANAGED", List.of())));
    when(projects.effectiveExclusions(any())).thenReturn(List.of());
    resources = new WorkspaceApplicationResources(projects, names, policy);
  }

  @Test
  void unavailable_application_refuses_without_external_fallback() {
    when(policy.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertThrows(WorkspaceRefusedException.class, () -> resources.directory(7L, "agents"));
    when(policy.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of()));
    assertTrue(resources.directory(7L, "agents").isEmpty());
  }

  @Test
  void absent_resource_is_an_empty_deployed_tier_and_unknown_directories_are_refused() {
    assertEquals(Optional.of(root.resolve("agents")), resources.directory(7L, "agents"));
    assertThrows(IllegalArgumentException.class, () -> resources.directory(7L, "../private"));
    assertThrows(
        IllegalArgumentException.class, () -> resources.directory(7L, ".plowshare/agents"));
    for (String name :
        List.of("", "agents", "bots", "skills", "orchestrations", "hooks", "schedules"))
      assertEquals(Optional.of(root.resolve(name)), resources.directory(7L, name));
  }

  @Test
  void hidden_application_layout_refuses_instead_of_silently_ignoring_definitions()
      throws Exception {
    Files.createDirectories(root.resolve(".plowshare/agents"));
    assertThrows(WorkspaceRefusedException.class, () -> resources.directory(7L, "agents"));
  }

  @Test
  void root_settings_ignore_unrelated_git_and_private_areas_but_keep_their_own_fences()
      throws Exception {
    Path git = Files.createDirectories(root.resolve(".git"));
    Files.writeString(git.resolve("config"), "repository state");
    Path privateArea = Files.createDirectories(root.resolve("private"));
    Path secret = Files.writeString(privateArea.resolve("secret"), "private");
    Files.createSymbolicLink(root.resolve("unrelated-link"), secret);
    when(projects.effectiveExclusions(any())).thenReturn(List.of(git, privateArea));
    assertEquals(Optional.of(root), resources.directory(7L, ""));
    Files.createSymbolicLink(root.resolve("environment.yml"), secret);
    assertThrows(WorkspaceRefusedException.class, () -> resources.directory(7L, ""));
    Files.delete(root.resolve("environment.yml"));
    Path policy = Files.writeString(root.resolve("environment.yml"), "server: {mode: off}");
    when(projects.effectiveExclusions(any())).thenReturn(List.of(git, privateArea, policy));
    assertThrows(WorkspaceRefusedException.class, () -> resources.directory(7L, ""));
  }

  @Test
  void resource_links_and_private_descendants_are_refused() throws Exception {
    Path directory = Files.createDirectories(root.resolve("agents"));
    Path target = Files.writeString(root.resolve("private.txt"), "private");
    Files.createSymbolicLink(directory.resolve("linked.md"), target);
    assertThrows(WorkspaceRefusedException.class, () -> resources.directory(7L, "agents"));
    Files.delete(directory.resolve("linked.md"));
    Path excluded = Files.writeString(directory.resolve("private.md"), "private");
    when(projects.effectiveExclusions(any())).thenReturn(List.of(excluded));
    assertThrows(WorkspaceRefusedException.class, () -> resources.directory(7L, "agents"));
  }
}
