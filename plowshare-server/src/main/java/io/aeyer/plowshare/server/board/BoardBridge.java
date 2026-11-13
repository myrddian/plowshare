package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An admitted Relay publication's forum write. The receiver supplies the authenticated publisher,
 * source scope and pinned destination after checking live ingress authority. A route grants no read
 * or seat rights. Closing additionally checks the publisher's destination-project write authority.
 */
public interface BoardBridge {
  record Payload(String body, String replyTo) {
    public Payload {
      body = BoardClosing.text(body);
      if (replyTo != null) replyTo = RelayPort.topic(replyTo);
    }
  }

  sealed interface Target permits Open, Post, Close {}

  /** The receiver supplies its authorized source project; routes cannot override it. */
  record Open(String project, String swarm) implements Target {
    public Open {
      project = RelayPort.identity(project);
      SwarmSelection.requireName(swarm);
    }
  }

  record Post(String topic) implements Target {
    public Post {
      topic = BoardClosing.identifier(topic, "bdt_");
    }
  }

  record Close(String topic) implements Target {
    public Close {
      topic = BoardClosing.identifier(topic, "bdt_");
    }
  }

  record Command(
      UUID requestId,
      String account,
      long sourceProject,
      String sourceEvent,
      Target target,
      Payload payload,
      RelayCausation causation) {
    public Command {
      Objects.requireNonNull(requestId);
      account = RelayPort.identity(account);
      if (sourceProject < 1) throw new IllegalArgumentException("Bridge requires a project source");
      sourceEvent = RelayPort.identity(sourceEvent);
      Objects.requireNonNull(target);
      Objects.requireNonNull(payload);
    }
  }

  record Receipt(String topic, String message) {}

  /** Read-only live destination preflight. An unavailable destination is a definite refusal. */
  void require(Command command);

  /** Writes forum action, receipt and reply binding in one owning transaction. */
  Receipt accept(Command command);

  /**
   * Read-only matching receipt lookup after uncertainty; absence never authorizes another write.
   */
  Optional<Receipt> inspect(Command command);
}
