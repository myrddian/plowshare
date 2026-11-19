package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.personal.PersonalWorkspaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class ProjectConfigurationsTest {
  @Test
  void client_private_names_never_resolve_a_server_workspace() {
    var projects = mock(ProjectStore.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<PersonalWorkspaces> personal = mock(ObjectProvider.class);
    var configurations = new ProjectConfigurations(projects, projects, personal);
    assertSame(ProjectConfiguration.NONE, configurations.read("client:session:house"));
    verifyNoInteractions(projects, personal);
  }

  @Test
  void project_ids_use_the_registered_workspace_and_exclusions(@TempDir Path root)
      throws Exception {
    Files.writeString(
        root.resolve("plowshare"),
        "{\"version\":1,\"name\":\"house\",\"caps\":{\"autoIncrease\":true}}");
    var projects = mock(ProjectStore.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<PersonalWorkspaces> personal = mock(ObjectProvider.class);
    var project = new ProjectRecord("house", root, List.of(), List.of());
    when(projects.nameForId(7L)).thenReturn(Optional.of("house"));
    when(projects.find("house")).thenReturn(Optional.of(project));
    when(projects.effectiveExclusions(project)).thenReturn(List.of());
    var configurations = new ProjectConfigurations(projects, projects, personal);
    assertTrue(configurations.read(7L).caps().increasesAutomatically());
    assertSame(ProjectConfiguration.NONE, configurations.read((Long) null));
    when(projects.effectiveExclusions(project)).thenReturn(List.of(root.resolve("plowshare")));
    assertThrows(IllegalArgumentException.class, () -> configurations.read(7L));
    verifyNoInteractions(personal);
  }

  @Test
  void security_guidance_uses_the_registered_root_or_exact_personal_mirror(@TempDir Path root)
      throws Exception {
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path mirror = Files.createDirectory(root.resolve("mirror"));
    Files.writeString(workspace.resolve("security.md"), "Ask before writes.");
    Files.writeString(mirror.resolve("security.md"), "Check Personal edit intent.");
    var projects = mock(ProjectStore.class);
    var personalSpace = mock(PersonalWorkspaces.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<PersonalWorkspaces> personal = mock(ObjectProvider.class);
    when(personal.getIfAvailable()).thenReturn(personalSpace);
    var row = new ProjectRecord("app", workspace, List.of(), List.of());
    when(projects.find("app")).thenReturn(Optional.of(row));
    when(personalSpace.workspace("personal:616c696365")).thenReturn(Optional.of(mirror));
    var configurations = new ProjectConfigurations(projects, projects, personal);
    assertEquals("Ask before writes.", configurations.securityGuidance("app", () -> {}));
    assertEquals(
        "Check Personal edit intent.",
        configurations.securityGuidance("personal:616c696365", () -> {}));
    assertEquals("", configurations.securityGuidance("client:session:app", () -> {}));
    when(projects.effectiveExclusions(row)).thenReturn(List.of(workspace.resolve("security.md")));
    assertThrows(
        IllegalArgumentException.class, () -> configurations.securityGuidance("app", () -> {}));
  }
}
