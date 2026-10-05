package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.protocol.Home;

/** Resolve trusted service scope before dispatch. This capability does not authorize requests. */
public interface UsageOwners {
  UsageOwners NONE = (home, account, operation) -> UsageAttribution.LEGACY;

  UsageAttribution in(Home home, String account, UsageAttribution.Operation operation);

  default UsageAttribution orchestration(String id, UsageAttribution.Operation operation) {
    return UsageAttribution.LEGACY;
  }

  default UsageAttribution conversation(String id, int turn, UsageAttribution.Operation operation) {
    return UsageAttribution.LEGACY;
  }

  /**
   * Freeze an internal document-processing root as SYSTEM before its children run. The log's
   * admission owner still controls source access; this identity grants no authority or login.
   */
  default UsageAttribution processing(String id, UsageAttribution.Operation operation) {
    return conversation(id, 0, operation);
  }
}
