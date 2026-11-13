package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An admitted Relay publication's forum write. The receiver supplies the authenticated publisher,
 * source scope and pinned destination after checking live source and reply ingress authority. A
 * route grants no membership or seat rights. Every forum write requires live source-project write
 * authority; post/close destinations must be in that same project.
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

  sealed interface Actor permits Person, Agent {
    String account();
  }

  record Person(String account) implements Actor {
    public Person {
      account = RelayPort.identity(account);
    }
  }

  /**
   * Proven server execution; the owning account grants write authority, never person authorship.
   */
  record Agent(String account, String definition, UUID sourceEvent) implements Actor {
    public Agent {
      account = RelayPort.identity(account);
      definition = RelayPort.identity(definition);
      Objects.requireNonNull(sourceEvent);
    }

    public UUID effectRequestId() {
      return UUID.nameUUIDFromBytes(
          ("native-board-effect/1\n" + sourceEvent).getBytes(StandardCharsets.UTF_8));
    }
  }

  record Command(
      UUID requestId,
      Actor actor,
      long sourceProject,
      String sourceEvent,
      Target target,
      Payload payload,
      RelayCausation causation) {
    public Command(
        UUID requestId,
        String account,
        long sourceProject,
        String sourceEvent,
        Target target,
        Payload payload,
        RelayCausation causation) {
      this(requestId, new Person(account), sourceProject, sourceEvent, target, payload, causation);
    }

    public String account() {
      return actor.account();
    }

    public Command {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(actor);
      if (sourceProject < 1) throw new IllegalArgumentException("Bridge requires a project source");
      sourceEvent = RelayPort.identity(sourceEvent);
      if (actor instanceof Agent agent
          && (!agent.sourceEvent().toString().equals(sourceEvent)
              || !agent.effectRequestId().equals(requestId)))
        throw new IllegalArgumentException("Native board identity differs from its proven source");
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
