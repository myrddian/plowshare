package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** One closed SYSTEM embedding lane for admitted preparation and search operations. */
@Component
public final class BoundSemanticEmbedding implements SemanticEmbedding {
  private final SystemOperations system;

  public BoundSemanticEmbedding(SystemOperations system) {
    this.system = Objects.requireNonNull(system);
  }

  public CallAuthorization authorize(
      Operation operation, EmbeddingPermit.Task task, Supplier<Budget.Claim> claim) {
    var expected =
        switch (operation.purpose()) {
          case DOCUMENT_PREPARATION -> EmbeddingPermit.Mode.DOCUMENT;
          case SEMANTIC_QUERY -> EmbeddingPermit.Mode.QUERY;
          default -> throw new IllegalStateException("Unsupported embedding operation");
        };
    if (task.mode() != expected)
      throw new IllegalStateException("Embedding task differs from operation");
    operation.validity().run();
    var request = task.request();
    var definition =
        new AgentDefinition(
            "semantic_embedding",
            "Semantic embedding",
            request.specifier(),
            List.of(),
            List.of(),
            List.of(),
            1,
            1,
            "Embed the admitted batch");
    // The client supplies a typed profile; exact final inputs are also captured by SYSTEM carriage.
    var work =
        system.admit(
            operation.purpose(),
            operation.home(),
            operation.scope(),
            definition,
            EmbeddingEvidence.digest(List.of(operation.binding(), EmbeddingEvidence.task(task))),
            operation.validity());
    return work.claimed(request, Objects.requireNonNull(claim.get()));
  }
}
