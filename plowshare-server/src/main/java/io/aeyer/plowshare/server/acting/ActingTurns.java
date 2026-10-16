package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.RunAdmission;

/**
 * Starts through the existing conversation turn, budget, job, accounting and cancellation owners.
 */
@FunctionalInterface
public interface ActingTurns {
  void start(String conversation, AgentDefinition agent, String task, RunAdmission admission);
}
