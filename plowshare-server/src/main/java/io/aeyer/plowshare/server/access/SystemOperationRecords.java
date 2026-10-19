package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import java.util.Objects;
import java.util.UUID;

/** Immutable internal purpose evidence, distinct from ordinary public operation admission. */
public interface SystemOperationRecords {
  record Envelope(
      UUID id,
      SystemOperations.Purpose purpose,
      ScopeId scope,
      UUID grant,
      String agent,
      String model,
      String bindingHash) {
    public Envelope {
      Objects.requireNonNull(id);
      Objects.requireNonNull(purpose);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(grant);
      Objects.requireNonNull(agent);
      Objects.requireNonNull(model);
      if (agent.isBlank()
          || model.isBlank()
          || bindingHash == null
          || !bindingHash.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid SYSTEM operation binding");
    }
  }

  void retain(Envelope envelope);

  void require(Envelope envelope);
}
