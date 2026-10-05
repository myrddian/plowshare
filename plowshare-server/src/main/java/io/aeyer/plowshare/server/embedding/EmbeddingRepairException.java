package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.EmbeddingException;

/** A failed owned batch retains successful slot publication and reports dispatch submissions. */
public final class EmbeddingRepairException extends EmbeddingException {
  private final int modelCalls;

  public EmbeddingRepairException(EmbeddingException cause, int modelCalls) {
    super(cause.getMessage(), cause);
    if (modelCalls < 0) throw new IllegalArgumentException("negative embedding call count");
    this.modelCalls = modelCalls;
  }

  public int modelCalls() {
    return modelCalls;
  }
}
