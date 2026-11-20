package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.approvals.SecurityPolicyFiles;
import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.archive.ProjectNames;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.personal.PersonalWorkspaces;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Reads only a registered server workspace or the exact Personal workspace. */
@Component
public final class ProjectConfigurations {
  private record Location(Path root, List<Path> exclusions) {}

  private final ProjectWorkspaces projects;
  private final ProjectNames names;
  private final ObjectProvider<PersonalWorkspaces> personal;

  public ProjectConfigurations(
      ProjectWorkspaces projects, ProjectNames names, ObjectProvider<PersonalWorkspaces> personal) {
    this.projects = projects;
    this.names = names;
    this.personal = personal;
  }

  public ProjectConfiguration read(String project) {
    return location(project)
        .map(at -> ProjectConfiguration.server(at.root(), at.exclusions(), project))
        .orElse(ProjectConfiguration.NONE);
  }

  String securityGuidance(String project, Runnable requireActive) {
    return webSecurityGuidance(project, null, requireActive);
  }

  String webSecurityGuidance(String project, Verb operation, Runnable requireActive) {
    return location(project)
        .filter(at -> at.root() != null)
        .map(
            at -> {
              var source = SecurityPolicyFiles.directory(at.root());
              var fence = FileAccess.of(List.of(at.root()), at.exclusions());
              return SecurityPolicyFiles.workspace(
                  name -> {
                    requireActive.run();
                    if (!fence.permits(at.root().resolve(name)))
                      throw new IllegalArgumentException(
                          "Security guidance is outside the workspace fence");
                    return source.read(name);
                  },
                  operation);
            })
        .orElse("");
  }

  private Optional<Location> location(String project) {
    if (ClientProjects.privateProject(project)) return Optional.empty();
    var row = projects.find(project);
    if (row.isPresent())
      return Optional.of(
          new Location(row.get().workspace(), projects.effectiveExclusions(row.get())));
    var space = personal.getIfAvailable();
    return space == null
        ? Optional.empty()
        : space.workspace(project).map(root -> new Location(root, List.of()));
  }

  public ProjectConfiguration read(Long id) {
    return id == null
        ? ProjectConfiguration.NONE
        : names.nameForId(id).map(this::read).orElse(ProjectConfiguration.NONE);
  }
}
