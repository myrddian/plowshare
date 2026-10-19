package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * Exact project-name lookup for existing definition contracts. Only omission may select the
 * caller's existing Personal/deployment catalogue. A malformed, missing or unavailable explicit
 * project must never turn into another definition tier. Account UUIDs are not decoded here: even
 * UUID-shaped text is looked up only as an ordinary project name.
 */
public final class RequestedProjectId {
  private RequestedProjectId() {}

  /** Historical entry point; explicit values now use the same strict rule as other lookups. */
  public static Long lenient(ProjectStore projects, String project) {
    return resolve(projects, project);
  }

  /** A retained non-Global resource must still name its existing project. */
  public static Long of(ProjectStore projects, Home home) {
    return home.isGlobal() ? null : resolve(projects, home.project());
  }

  /** Storage outages propagate; a catalogue cannot silently replace the selected context. */
  public static Long forListing(ProjectStore projects, String project) {
    return resolve(projects, project);
  }

  /** Omission keeps the existing operator definition tier; named destinations must exist. */
  public static Long forWrite(ProjectStore projects, String project) {
    return resolve(projects, project);
  }

  private static Long resolve(ProjectStore projects, String project) {
    if (project == null) return null;
    RequestedHome.in(project);
    Long id;
    try {
      id = projects.id(project);
    } catch (ValidationException malformed) {
      throw new CallerFault(malformed.getMessage(), malformed);
    }
    if (id == null || id <= 0)
      throw new CallerFault(
          "no project called '"
              + project
              + "' exists or is currently available; no definition fallback was used");
    return id;
  }
}
