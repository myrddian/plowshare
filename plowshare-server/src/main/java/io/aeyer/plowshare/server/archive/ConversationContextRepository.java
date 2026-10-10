package io.aeyer.plowshare.server.archive;

import java.util.Optional;

/** Durable conversation ownership for context inspection; this lookup grants no authority. */
public interface ConversationContextRepository {
  Optional<Ownership> ownership(String conversation);

  /** Project identity and name are both absent for global context. */
  record Ownership(String principal, String projectId, String project, boolean personal) {
    public Ownership {
      if (principal == null || principal.isBlank())
        throw new IllegalArgumentException("Context ownership requires a principal");
      if ((projectId == null) != (project == null)
          || projectId != null && (projectId.isBlank() || project.isBlank())
          || personal && project == null)
        throw new IllegalArgumentException("Context ownership requires a consistent project");
    }
  }
}
