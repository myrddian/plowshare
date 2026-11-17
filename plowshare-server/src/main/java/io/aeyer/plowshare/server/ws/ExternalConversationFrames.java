package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.ExternalConversations;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Explicitly registered platform facade; ordinary conversation and agent frames stay compatible.
 */
@Component
public final class ExternalConversationFrames implements FrameArea {
  private final ExternalConversations conversations;

  public ExternalConversationFrames(ExternalConversations conversations) {
    this.conversations = Objects.requireNonNull(conversations);
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.CONVERSATION_OPEN_EX,
        new ConversationOpenExHandler(conversations),
        FrameTypes.AGENT_RUN_EX,
        new AgentRunExHandler(conversations));
  }
}
