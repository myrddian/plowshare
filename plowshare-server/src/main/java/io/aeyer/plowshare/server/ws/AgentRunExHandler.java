package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.ExternalConversations;
import io.aeyer.plowshare.server.api.RunAgentRequestEx;
import io.aeyer.plowshare.server.api.StartedJob;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolve an external locator and reach ordinary run admission without a client lookup RPC. */
public final class AgentRunExHandler implements FrameHandler {
  private final ExternalConversations conversations;

  public AgentRunExHandler(ExternalConversations conversations) {
    this.conversations = Objects.requireNonNull(conversations);
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    var asked =
        ExternalConversationPayloads.decode(
            payload,
            RunAgentRequestEx.class,
            FrameTypes.AGENT_RUN_EX,
            Set.of("project", "source", "externalConversation", "agent", "task"),
            List.of("session", "maxModelCalls", "maxTurns", "noTurnCap", "images"));
    var started =
        conversations.run(
            new ExternalConversations.Locator(
                asked.project(), asked.source(), asked.externalConversation()),
            new ExternalConversations.Run(
                asked.agent(),
                asked.task(),
                asked.session(),
                asked.maxModelCalls(),
                asked.maxTurns(),
                asked.noTurnCap(),
                asked.images()),
            new ExternalConversations.Caller(
                asking.requireHandle(FrameTypes.AGENT_RUN_EX),
                asking.sessionId(),
                asking.externalAdmission()));
    return new Outcome(
        Code.ACCEPTED,
        null,
        new StartedJob(started.id(), started.agent(), null, started.conversation()));
  }
}
