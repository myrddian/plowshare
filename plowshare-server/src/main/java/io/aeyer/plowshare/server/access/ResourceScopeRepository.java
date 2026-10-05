package io.aeyer.plowshare.server.access;

import java.util.List;

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
    MESSAGE,
    TRIGGER,
    SCHEDULE,
    APPROVAL,
    OUTGOING
  }

  List<String> projects(AccessRequest.Resource resource);

  boolean triggerOwnedBy(String trigger, String account);
}
