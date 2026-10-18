package io.aeyer.plowshare.server.approvals;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local presence of suspended stacks, not consent or execution authority. Durable state
 * remains in the repositories; after restart its absence selects retained-call recovery.
 */
public final class ActiveRunApprovals {
  private final ConcurrentHashMap<String, String> roots = new ConcurrentHashMap<>();

  public void entered(String approval, String root) {
    if (roots.putIfAbsent(approval, root) != null)
      throw new IllegalStateException("Approval already has a suspended stack");
  }

  public void left(String approval) {
    roots.remove(approval);
  }

  public boolean contains(String approval) {
    return roots.containsKey(approval);
  }

  public boolean waitingIn(String root) {
    return roots.containsValue(root);
  }
}
