package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Reads only a registered server workspace or the exact Personal workspace. */
@Component
public final class ProjectConfigurations {
  private final ProjectStore projects;
  private final ObjectProvider<PersonalSpaces> personal;

  public ProjectConfigurations(ProjectStore projects, ObjectProvider<PersonalSpaces> personal) {
    this.projects = projects;
    this.personal = personal;
  }

  public ProjectConfiguration read(String project) {
    if (io.aeyer.plowshare.server.archive.ClientProjects.privateProject(project))
      return ProjectConfiguration.NONE;
    var row = projects.find(project);
    if (row.isPresent())
      return ProjectConfiguration.server(
          row.get().workspace(), projects.effectiveExclusions(row.get()), project);
    var space = personal.getIfAvailable();
    return space == null
        ? ProjectConfiguration.NONE
        : space
            .workspace(project)
            .map(root -> ProjectConfiguration.server(root, List.of(), project))
            .orElse(ProjectConfiguration.NONE);
  }

  public ProjectConfiguration read(Long id) {
    return id == null
        ? ProjectConfiguration.NONE
        : projects.nameForId(id).map(this::read).orElse(ProjectConfiguration.NONE);
  }
}
