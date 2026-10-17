package io.aeyer.plowshare.server.llm.dispatch;

import java.util.Objects;
import java.util.UUID;

/** A claimed call from one retained operation allowance; never a monetary reservation. */
public record InferenceBinding(UUID id, UUID operation, UUID budget, int ordinal, Integer limit) {
  public InferenceBinding {
    Objects.requireNonNull(id);
    Objects.requireNonNull(operation);
    Objects.requireNonNull(budget);
    if (ordinal < 1 || limit != null && (limit < 1 || ordinal > limit))
      throw new IllegalArgumentException("Invalid claimed operation allowance");
  }
}
