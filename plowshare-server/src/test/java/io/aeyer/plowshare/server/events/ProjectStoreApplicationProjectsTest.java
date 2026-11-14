package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectNames;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectStoreApplicationProjectsTest {
  private final ProjectNames names = mock(ProjectNames.class);
  private final ProjectWorkspaces workspaces = mock(ProjectWorkspaces.class);
  private final ApplicationProjects projects =
      new ProjectStoreApplicationProjects(names, workspaces);

  @Test
  void resolvesTheCurrentNameAndType() {
    when(names.nameForId(7)).thenReturn(Optional.of("app"));
    when(workspaces.find("app"))
        .thenReturn(
            Optional.of(
                new ProjectRecord(
                    "app", Path.of("/srv/app"), List.of(), List.of(), "DISJOINT", List.of())));
    assertEquals(
        Optional.of(
            new ApplicationProjects.ApplicationProject(
                "app", ApplicationProjects.ProjectType.DISJOINT)),
        projects.find(7));
  }

  @Test
  void missingProjectsAreEmptyAndUnknownTypesAreRefused() {
    when(names.nameForId(8)).thenReturn(Optional.empty());
    assertTrue(projects.find(8).isEmpty());
    when(names.nameForId(9)).thenReturn(Optional.of("odd"));
    when(workspaces.find("odd"))
        .thenReturn(
            Optional.of(
                new ProjectRecord(
                    "odd", Path.of("/srv/odd"), List.of(), List.of(), "OTHER", List.of())));
    assertThrows(IllegalStateException.class, () -> projects.find(9));
    assertThrows(IllegalArgumentException.class, () -> projects.find(0));
  }
}
