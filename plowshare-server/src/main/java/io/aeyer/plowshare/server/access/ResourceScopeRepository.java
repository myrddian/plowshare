package io.aeyer.plowshare.server.access;

import java.util.List;
import java.util.UUID;

/**
 * Durable authorization scopes, including null for global resources. No SQL escapes this boundary.
 */
public interface ResourceScopeRepository {
  enum Kind {
    CONVERSATION,
    JOB,
    MEMORY,
    PROPOSAL,
    TOPIC,
    INSTANCE,
    ORCHESTRATION,
    ORCHESTRATION_RECEIPT,
    MESSAGE,
    TRIGGER,
    SCHEDULE,
    APPROVAL,
    OUTGOING
  }

  List<String> projects(AccessRequest.Resource resource);

  /**
   * Resolves a committed start receipt using both its authenticated owner and request UUID. An
   * empty list means no owned receipt; a null project denotes global work. The caller must still
   * check current project/token permissions and the run's ordinary visibility. This read never
   * admits or resumes work.
   */
  List<String> orchestrationReceiptProjects(String account, UUID requestId);

  boolean triggerOwnedBy(String trigger, String account);
}
