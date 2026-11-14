package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.archive.ProjectNames;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import java.util.Optional;

/**
 * Resolves an Application project through the project repository's identity and row lookups. The id
 * is resolved to its current name first, so a rename between the two keyed reads yields empty (the
 * save is then refused) rather than another project's row.
 */
public final class ProjectStoreApplicationProjects implements ApplicationProjects {
  private final ProjectNames names;
  private final ProjectWorkspaces projects;

  public ProjectStoreApplicationProjects(ProjectNames names, ProjectWorkspaces projects) {
    this.names = names;
    this.projects = projects;
  }

  @Override
  public Optional<ApplicationProject> find(long projectId) {
    if (projectId < 1) throw new IllegalArgumentException("Invalid project id");
    return names
        .nameForId(projectId)
        .flatMap(projects::find)
        .map(row -> new ApplicationProject(row.name(), ProjectType.parse(row.type())));
  }
}
