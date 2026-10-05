package io.aeyer.plowshare.server.agents;

/** Live resolution and authorization of an exported work agent. */
public interface WorkCallers {
  void requireWork(String project, String handle);

  void requireSession(String session, String handle);

  DefinitionResolver.Caller callerFor(String project, String session, String handle);

  AgentDefinition requireAgent(String name, DefinitionResolver.Caller caller);
}
