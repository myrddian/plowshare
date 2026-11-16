package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.AgentDefinition;

/**
 * One authorized, tool-free consult using an already selected operation/accounting owner. The
 * implementation claims existing allowance, enforces the shared deadline and owns provider cleanup.
 * No new task, tool execution, fallback identity or model-call replay is permitted.
 */
@FunctionalInterface
public interface ReviewInference {
  String consult(AgentDefinition definition, String system, String evidence, ReviewWork work);
}
