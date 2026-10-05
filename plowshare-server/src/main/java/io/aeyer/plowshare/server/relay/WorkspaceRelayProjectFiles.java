package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import java.util.Objects;
import java.util.Optional;

/** Chooses the owning workspace tier; a remote read failure never falls back to server files. */
public final class WorkspaceRelayProjectFiles implements RelayProjectFiles {
  private final ProjectWorkspaces projects;
  private final RelayProjectFiles server;
  private final RelayProjectFiles remote;

  public WorkspaceRelayProjectFiles(
      ProjectWorkspaces projects, RelayProjectFiles server, RelayProjectFiles remote) {
    this.projects = Objects.requireNonNull(projects);
    this.server = Objects.requireNonNull(server);
    this.remote = Objects.requireNonNull(remote);
  }

  @Override
  public void requireAccess(Access access) {
    selected(access).requireAccess(access);
  }

  @Override
  public Optional<String> read(Access access, String path) {
    RelayProjectFiles.path(path);
    return selected(access).read(access, path);
  }

  private RelayProjectFiles selected(Access access) {
    return projects.personalOwner(access.project()).isPresent()
            || projects.find(access.project()).isPresent()
        ? server
        : remote;
  }
}
