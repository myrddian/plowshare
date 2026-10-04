package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
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
    ObjectProvider<PersonalSpaces> personal = mock(ObjectProvider.class);
    var configurations = new ProjectConfigurations(projects, personal);
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
    ObjectProvider<PersonalSpaces> personal = mock(ObjectProvider.class);
    var project = new ProjectRecord("house", root, List.of(), List.of());
    when(projects.nameForId(7L)).thenReturn(Optional.of("house"));
    when(projects.find("house")).thenReturn(Optional.of(project));
    when(projects.effectiveExclusions(project)).thenReturn(List.of());
    var configurations = new ProjectConfigurations(projects, personal);
    assertTrue(configurations.read(7L).caps().increasesAutomatically());
    assertSame(ProjectConfiguration.NONE, configurations.read((Long) null));
    when(projects.effectiveExclusions(project)).thenReturn(List.of(root.resolve("plowshare")));
    assertThrows(IllegalArgumentException.class, () -> configurations.read(7L));
    verifyNoInteractions(personal);
  }
}
