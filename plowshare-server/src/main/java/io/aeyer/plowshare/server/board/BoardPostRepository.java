package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Account-owned forum receipts. Read and save must share the board mutation transaction. */
public interface BoardPostRepository {
  /** A different intent already owns this account/request UUID; no new effect was accepted. */
  final class Conflict extends CallerFault {
    public Conflict() {
      super("This requestId was already used for a different board action.");
    }
  }

  sealed interface Action permits Open, Post, Retry, Close, Bridge, BridgeExecution {}

  record Open(
      String project, String title, String label, String body, Integer maxModelCalls, String swarm)
      implements Action {
    public Open(String project, String title, String label, String body, Integer maxModelCalls) {
      this(project, title, label, body, maxModelCalls, null);
    }
  }

  record Post(String project, String topic, String body) implements Action {}

  /** The discriminator keeps a close distinct from a post with the same topic and body. */
  record Close(String action, String project, String topic, String body, List<String> cites)
      implements Action {
    public Close {
      if (!"close".equals(action)) throw new IllegalArgumentException("Invalid close action");
      project = RelayPort.identity(project);
      topic = BoardClosing.identifier(topic, "bdt_");
      body = BoardClosing.text(body);
      cites = List.copyOf(cites);
      if (cites.size() > 200) throw new IllegalArgumentException("Too many close citations");
      cites.forEach(value -> BoardClosing.identifier(value, "bdm_"));
    }

    public Close(String project, String topic, String body, List<String> cites) {
      this("close", project, topic, body, List.copyOf(cites));
    }
  }

  /**
   * Full immutable bridge intent, including disclosure destination, participates in deduplication.
   */
  record Bridge(
      String action,
      long sourceProject,
      String sourceEvent,
      String destination,
      String project,
      String body,
      String replyTo)
      implements Action {
    public Bridge {
      if (!Set.of("board.open", "board.post", "board.close").contains(action) || sourceProject < 1)
        throw new IllegalArgumentException("Invalid board bridge action");
      sourceEvent = RelayPort.identity(sourceEvent);
      destination = RelayPort.identity(destination);
      project = RelayPort.identity(project);
      body = BoardClosing.text(body);
      if (replyTo != null) replyTo = RelayPort.topic(replyTo);
    }
  }

  /** New execution identities cannot collide with historical person bridge receipts. */
  record BridgeExecution(Bridge command, String definition, UUID sourceEvent) implements Action {
    public BridgeExecution {
      Objects.requireNonNull(command);
      definition = RelayPort.identity(definition);
      Objects.requireNonNull(sourceEvent);
      if (!sourceEvent.toString().equals(command.sourceEvent()))
        throw new IllegalArgumentException("Execution receipt differs from its source");
    }
  }

  record Retry(String action, String project, String topic, String member, int maxTurns)
      implements Action {
    public Retry(String project, String topic, String member, int maxTurns) {
      this("retry", project, topic, member, maxTurns);
    }
  }

  /**
   * Locks the receipt identity, rejects a changed action and returns a prior message when present.
   */
  Optional<String> lockAndRead(String account, UUID request, Action action);

  /**
   * Read-only receipt lookup. A changed intent fails; absence must never trigger uncertain replay.
   */
  Optional<String> find(String account, UUID request, Action action);

  void save(String account, UUID request, Action action, String message);
}
