package io.aeyer.plowshare.server.archive;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server source registration. Repository operations require server-admin authority and are atomic.
 */
public interface ServerProjects extends ProjectWorkspaces {
  /**
   * Provision once while holding the project identity lock; callers clean up only their new files.
   */
  ProjectRecord createServer(
      String name,
      String type,
      List<String> writePaths,
      String handle,
      Supplier<ServerWorkspace> workspace);

  /**
   * Admit or update alias placement. First adoption must resolve to the existing source directory.
   */
  ProjectRecord place(String name, ApplicationPlacement placement, String handle);

  /** Atomic source switch for the deployment owner; refuses unrelated or moved workspaces. */
  ProjectRecord activateDeployment(
      String name,
      ApplicationPlacement placement,
      ApplicationPlacement expected,
      Path verifiedRoot,
      String handle);

  record ServerWorkspace(Path root, boolean application, ApplicationPlacement placement) {
    public ServerWorkspace(Path root, boolean application) {
      this(root, application, null);
    }

    public ServerWorkspace {
      java.util.Objects.requireNonNull(root);
    }
  }
}
