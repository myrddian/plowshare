package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.llm.dispatch.CallAuthorization;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared SYSTEM dispatch binding; consumers own admission, spending and publication transactions.
 */
public interface SemanticEmbedding {
  record Operation(
      SystemOperations.Purpose purpose,
      Home home,
      ScopeId scope,
      String binding,
      Runnable validity) {
    public Operation {
      Objects.requireNonNull(purpose);
      Objects.requireNonNull(home);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(validity);
      if (binding == null || binding.isBlank())
        throw new IllegalArgumentException("Embedding binding required");
    }
  }

  /**
   * Caller must atomically charge and retain this result within its owning operation transaction.
   */
  CallAuthorization authorize(
      Operation operation, EmbeddingPermit.Task task, Supplier<Budget.Claim> claim);
}
