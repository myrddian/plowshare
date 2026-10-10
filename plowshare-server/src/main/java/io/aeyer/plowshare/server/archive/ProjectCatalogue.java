package io.aeyer.plowshare.server.archive;

import java.util.List;

/** Repository view of registered server sources for bounded background reconciliation. */
public interface ProjectCatalogue {
  /**
   * Current server workspace records; unavailable storage is a failure, never an empty catalogue.
   */
  List<ProjectRecord> all();

  /** Current durable ID, or null for a source no longer registered. */
  Long id(String name);
}
