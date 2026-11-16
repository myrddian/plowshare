package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.AgentDefinition;

/** Current personal-context bot resolution; no fallback to another owner or execution identity. */
@FunctionalInterface
public interface AvailableApprovalBots {
  AgentDefinition selected(String owner, String name);
}
