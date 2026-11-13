package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.board.BoardClosing;
import io.aeyer.plowshare.server.board.SwarmSelection;
import java.util.Set;

/** Pinned, one-way work destination. Authority is resolved live, never granted by a route. */
public record RelayWork(
    String agent,
    String project,
    String definition,
    String topic,
    String swarm,
    String replyTopic,
    String channel) {
  public RelayWork(String agent, String project, String definition) {
    this(agent, project, definition, null, null, null, null);
  }

  public RelayWork(String agent, String project, String definition, String topic, String swarm) {
    this(agent, project, definition, topic, swarm, null, null);
  }

  public RelayWork {
    if (agent != null) agent = RelayValues.identity(agent, "agent");
    if (replyTopic != null) replyTopic = RelayValues.topic(replyTopic);
    if (channel != null) channel = RelayValues.identity(channel, "message channel");
    if (project != null) project = RelayValues.identity(project, "destination project");
    if (definition != null)
      definition = RelayValues.identity(definition, "orchestration definition");
    if (topic != null) topic = BoardClosing.identifier(topic, "bdt_");
    if (swarm != null) SwarmSelection.requireName(swarm);
    if ((agent != null ? 1 : 0) + (topic != null ? 1 : 0) + (swarm != null ? 1 : 0) != 1)
      throw new IllegalArgumentException("Relay work requires exactly one destination kind");
    if (agent == null && definition != null)
      throw new IllegalArgumentException("Only execution work has an orchestration definition");
  }

  static void require(String receiver, RelayWork work, boolean scripted) {
    boolean messaging =
        Set.of("message.receive", "message.cancel", "message.inspect").contains(receiver);
    if (messaging) {
      if (work == null
          || scripted
          || work.agent() == null
          || work.project() != null
          || work.definition() != null
          || work.topic() != null
          || work.swarm() != null
          || work.replyTopic() == null
          || work.channel() == null)
        throw new IllegalArgumentException(
            "Messaging receivers require a pinned agent, replyTopic and shared channel");
      return;
    }
    if (work != null && (work.replyTopic() != null || work.channel() != null))
      throw new IllegalArgumentException("Message return pins belong only to messaging receivers");
    boolean board = Set.of("board.open", "board.post", "board.close").contains(receiver);
    if (board) {
      if (work == null
          || scripted
          || work.agent() != null
          || work.project() != null
          || receiver.equals("board.open") != (work.swarm() != null)
          || !receiver.equals("board.open") != (work.topic() != null))
        throw new IllegalArgumentException("Board receivers require a pinned topic or named swarm");
      return;
    }
    boolean execution = Set.of("agent.run", "script.run", "orchestration.start").contains(receiver);
    if (execution != (work != null))
      throw new IllegalArgumentException("execution receivers require a pinned work destination");
    if (!execution) return;
    if (work.agent() == null)
      throw new IllegalArgumentException("Execution receivers require an agent");
    if (receiver.equals("orchestration.start") != (work.definition() != null))
      throw new IllegalArgumentException("only orchestration.start requires a definition");
    if (receiver.equals("script.run") != scripted)
      throw new IllegalArgumentException(
          "script.run requires a handler; other work receivers cannot use one");
  }
}
