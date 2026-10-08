package io.aeyer.plowshare.server.board;

import java.util.Optional;
import java.util.UUID;

/** Account-owned person-post receipts. Read and save must share the board mutation transaction. */
public interface BoardPostRepository {
  sealed interface Action permits Open, Post, Retry {}

  record Open(
      String project, String title, String label, String body, Integer maxModelCalls, String swarm)
      implements Action {
    public Open(String project, String title, String label, String body, Integer maxModelCalls) {
      this(project, title, label, body, maxModelCalls, null);
    }
  }

  record Post(String project, String topic, String body) implements Action {}

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

  void save(String account, UUID request, Action action, String message);
}
