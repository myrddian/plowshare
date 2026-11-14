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

  /**
   * Atomic source switch for a project Manager's schedule-only revision (ADR 0068).
   *
   * <p>Unlike {@link #activateDeployment}, this neither requires server administration nor
   * FileStore {@code MANAGER} access: the new revision lands beside the active one in the
   * destination the server already staged for this placed project. It takes no account and performs
   * no authority check: its only permitted caller is the deployment store's commit transaction,
   * which has already checked the caller is a current project Manager and that the staged root
   * changes only {@code schedules/*.json}, under the same project row lock.
   *
   * <p>It refuses unless the project is MANAGED, its stored placement is still {@code expected},
   * {@code placement} keeps {@code expected}'s FileStore, writable areas and revisions directory,
   * and {@code verifiedRoot} is what the placement resolves to now. It then moves the project's
   * workspace and stored placement to the new revision and changes nothing else.
   */
  ProjectRecord activateScheduleRevision(
      String name,
      ApplicationPlacement placement,
      ApplicationPlacement expected,
      Path verifiedRoot);

  record ServerWorkspace(Path root, boolean application, ApplicationPlacement placement) {
    public ServerWorkspace(Path root, boolean application) {
      this(root, application, null);
    }

    public ServerWorkspace {
      java.util.Objects.requireNonNull(root);
    }
  }
}
