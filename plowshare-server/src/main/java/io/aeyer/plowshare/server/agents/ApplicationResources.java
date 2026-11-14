package io.aeyer.plowshare.server.agents;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Registered Application resources resolve on the server; unavailable Applications never fall back.
 */
public interface ApplicationResources {
  Optional<Path> root(Long projectId);

  default Optional<Path> directory(Long projectId, String relative) {
    return root(projectId).map(root -> root.resolve(relative));
  }

  /**
   * The same directory, given a root the caller has already resolved for {@code projectId}. Callers
   * that resolve several directories of one Application within one operation pass the root along
   * instead of asking {@link #root} again; implementations that re-validate a directory against
   * live state do so here exactly as in {@link #directory(Long, String)}.
   */
  default Optional<Path> directory(Long projectId, String relative, Optional<Path> root) {
    return root.map(resolved -> resolved.resolve(relative));
  }

  ApplicationResources NONE = id -> Optional.empty();
}
