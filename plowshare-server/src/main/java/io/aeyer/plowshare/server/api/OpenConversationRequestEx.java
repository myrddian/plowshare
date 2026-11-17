package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.ExternalConversations;

/** Exact WS-only external locator; creation choices have the ordinary conversation meaning. */
public record OpenConversationRequestEx(
    String project,
    String source,
    String externalConversation,
    Integer maxModelCalls,
    Integer maxTurns,
    Boolean noTurnCap,
    Boolean noBudget) {
  public OpenConversationRequestEx {
    new ExternalConversations.Locator(project, source, externalConversation);
    new ExternalConversations.Open(maxModelCalls, maxTurns, noBudget, noTurnCap);
  }
}
