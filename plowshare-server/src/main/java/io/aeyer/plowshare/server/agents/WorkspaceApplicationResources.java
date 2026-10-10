package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.server.archive.ProjectNames;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Checks the live manifest and mandatory workspace fence before a resource loader reaches disk. */
@Component
public final class WorkspaceApplicationResources implements ApplicationResources {
  private final ProjectWorkspaces projects;
  private final ProjectNames names;
  private final ApplicationPolicy applications;

  public WorkspaceApplicationResources(
      ProjectWorkspaces projects, ProjectNames names, ApplicationPolicy applications) {
    this.projects = projects;
    this.names = names;
    this.applications = applications;
  }

  @Override
  public Optional<Path> root(Long id) {
    if (id == null) return Optional.empty();
    var name = names.nameForId(id);
    if (name.isEmpty()) return Optional.empty();
    var boundary = applications.read(name.get());
    if (boundary.kind() == ApplicationPolicy.Kind.EXTERNAL) return Optional.empty();
    if (boundary.kind() == ApplicationPolicy.Kind.INVALID)
      throw new WorkspaceRefusedException(
          "Application resources require a valid deployed manifest");
    var row =
        projects
            .find(name.get())
            .orElseThrow(() -> new WorkspaceRefusedException("Application source is unavailable"));
    if (Files.exists(row.workspace().resolve(".plowshare"), LinkOption.NOFOLLOW_LINKS))
      throw new WorkspaceRefusedException(
          "Application resources belong directly in the root; remove .plowshare/");
    var fence =
        FileAccess.of(java.util.List.of(row.workspace()), projects.effectiveExclusions(row));
    if (!fence.permits(row.workspace()))
      throw new WorkspaceRefusedException("Application source is excluded");
    return Optional.of(row.workspace());
  }

  @Override
  public Optional<Path> directory(Long id, String relative) {
    if (!java.util.Set.of(
            "", "agents", "bots", "skills", "orchestrations", "hooks", "schedules", "server")
        .contains(relative))
      throw new IllegalArgumentException("Unknown Application resource directory");
    return root(id)
        .map(
            root -> {
              Path directory = root.resolve(relative);
              try {
                Path at = root;
                for (Path segment : root.relativize(directory)) {
                  at = at.resolve(segment);
                  if (Files.isSymbolicLink(at)) throw new IOException("Linked resource directory");
                }
                if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                  if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Resource directory unavailable");
                  try (var paths = resourcePaths(directory, relative.isEmpty())) {
                    var entries = paths.limit(4097).toList();
                    if (entries.size() > 4096 || entries.stream().anyMatch(Files::isSymbolicLink))
                      throw new IOException("Linked or oversized resources");
                    var row = projects.find(names.nameForId(id).orElseThrow()).orElseThrow();
                    var fence = row.reach(projects.effectiveExclusions(row));
                    for (Path path : entries)
                      if (!fence.permits(path)) throw new IOException("Excluded resource");
                  }
                }
                return directory;
              } catch (IOException invalid) {
                throw new WorkspaceRefusedException(
                    "Application resources are unavailable or outside their workspace fence");
              }
            });
  }

  /**
   * Root settings and agent instructions are the root tier's resources. Unrelated repository files,
   * Git metadata and runtime output areas must not enter that scan; their access has other owners.
   * Named resource directories retain their complete bounded, fenced scan.
   */
  private static java.util.stream.Stream<Path> resourcePaths(Path directory, boolean root)
      throws IOException {
    if (!root) return Files.walk(directory);
    var settings =
        java.util.stream.Stream.of("AGENTS.md", "AGENT.md", "skills.yml", "environment.yml")
            .map(directory::resolve);
    Path agents = directory.resolve("agents");
    if (Files.notExists(agents, LinkOption.NOFOLLOW_LINKS)) return settings;
    if (Files.isSymbolicLink(agents) || !Files.isDirectory(agents, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Agent instructions need a regular resource directory");
    return java.util.stream.Stream.concat(settings, Files.walk(agents));
  }
}
