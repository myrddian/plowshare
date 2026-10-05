package io.aeyer.plowshare.server.relay;

/** Pinned, one-way work destination. Authority is resolved live, never granted by a route. */
public record RelayWork(String agent, String project, String definition) {
  public RelayWork {
    agent = RelayValues.identity(agent, "agent");
    if (project != null) project = RelayValues.identity(project, "destination project");
    if (definition != null)
      definition = RelayValues.identity(definition, "orchestration definition");
  }

  static void require(String receiver, RelayWork work, boolean scripted) {
    boolean execution =
        java.util.Set.of("agent.run", "script.run", "orchestration.start").contains(receiver);
    if (execution != (work != null))
      throw new IllegalArgumentException("execution receivers require a pinned work destination");
    if (!execution) return;
    if (receiver.equals("orchestration.start") != (work.definition() != null))
      throw new IllegalArgumentException("only orchestration.start requires a definition");
    if (receiver.equals("script.run") != scripted)
      throw new IllegalArgumentException(
          "script.run requires a handler; other work receivers cannot use one");
  }
}
