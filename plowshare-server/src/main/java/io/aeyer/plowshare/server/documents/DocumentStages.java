package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import java.util.function.Function;

/**
 * A model stage is gated outside transactions; callers retain their existing context construction.
 */
@FunctionalInterface
public interface DocumentStages {
  DocumentStages NONE = (definition, task, log, home, owner, work) -> work.apply(task);

  default DocumentStages forDocument(java.util.UUID revision) {
    return this;
  }

  final class Blocked extends RuntimeException {
    private final int modelCalls;

    public Blocked(String reason) {
      this(reason, 0);
    }

    public Blocked(String reason, int calls) {
      super("Document stage blocked: " + reason);
      modelCalls = calls;
    }

    public int modelCalls() {
      return modelCalls;
    }
  }

  Outcome run(
      AgentDefinition definition,
      String task,
      String log,
      Home home,
      String owner,
      Function<String, Outcome> work);
}
