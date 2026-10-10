package io.aeyer.plowshare.server.api;

/**
 * The body of agent.define and POST /v1/agents, with the resolved view after the owning write.
 * Global and project definitions now refresh without restarting the process. The restart field
 * remains in the wire contract for compatibility and is false for successful writes.
 *
 * @param agent the current resolved definition view
 * @param restartRequired retained compatibility flag; false for current successful writes
 */
public record DefinedAgent(AgentView agent, boolean restartRequired) {}
