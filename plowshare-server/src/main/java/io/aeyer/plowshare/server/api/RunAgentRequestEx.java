package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.ExternalConversations;
import java.util.List;

/** Exact external-conversation turn request; no internal ID or create-on-miss routing selector. */
public record RunAgentRequestEx(
    String project,
    String source,
    String externalConversation,
    String agent,
    String task,
    String session,
    Integer maxModelCalls,
    Integer maxTurns,
    Boolean noTurnCap,
    List<String> images) {
  public RunAgentRequestEx {
    new ExternalConversations.Locator(project, source, externalConversation);
    new ExternalConversations.Run(agent, task, session, maxModelCalls, maxTurns, noTurnCap, images);
    images = images == null ? List.of() : List.copyOf(images);
  }
}
