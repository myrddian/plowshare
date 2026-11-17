package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.ExternalConversations;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.api.OpenConversationRequestEx;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One get-or-open request, returning the ordinary conversation view after alias commit. */
public final class ConversationOpenExHandler implements FrameHandler {
  private final ExternalConversations conversations;

  public ConversationOpenExHandler(ExternalConversations conversations) {
    this.conversations = Objects.requireNonNull(conversations);
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    var asked =
        ExternalConversationPayloads.decode(
            payload,
            OpenConversationRequestEx.class,
            FrameTypes.CONVERSATION_OPEN_EX,
            Set.of("project", "source", "externalConversation"),
            List.of("maxModelCalls", "maxTurns", "noTurnCap", "noBudget"));
    var opened =
        conversations.open(
            new ExternalConversations.Locator(
                asked.project(), asked.source(), asked.externalConversation()),
            new ExternalConversations.Open(
                asked.maxModelCalls(), asked.maxTurns(), asked.noBudget(), asked.noTurnCap()),
            new ExternalConversations.Caller(
                asking.requireHandle(FrameTypes.CONVERSATION_OPEN_EX),
                asking.sessionId(),
                asking.externalAdmission()));
    return Outcome.ok(ConversationView.of(opened));
  }
}
