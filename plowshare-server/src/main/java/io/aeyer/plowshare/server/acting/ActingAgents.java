package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.server.agents.AgentDefinition;

/**
 * Resolves an exported concrete definition in the subject's Personal scope, without client files.
 */
@FunctionalInterface
public interface ActingAgents {
  AgentDefinition resolve(String subject, String agent);
}
