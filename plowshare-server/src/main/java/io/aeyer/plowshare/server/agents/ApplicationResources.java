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

  ApplicationResources NONE = id -> Optional.empty();
}
