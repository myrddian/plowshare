package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.Objects;

/** Operation-owned input authorization. The embedding foundation never supplies an allowance. */
public interface EmbeddingPermit {
  enum Mode {
    QUERY,
    DOCUMENT
  }

  /** Exact preprocessed batch and model-space identity, resolved by the owning embedding client. */
  record Task(Mode mode, String profile, java.util.List<String> sources, EmbeddingRequest request) {
    public Task(Mode mode, String profile, EmbeddingRequest request) {
      this(mode, profile, request.input(), request);
    }

    public Task {
      Objects.requireNonNull(mode);
      sources = java.util.List.copyOf(sources);
      Objects.requireNonNull(request);
      if (profile == null || profile.isBlank())
        throw new IllegalArgumentException("Embedding profile required");
    }
  }

  Mode mode();

  void require();

  CallAuthorization authorize(EmbeddingRequest request);

  default CallAuthorization authorize(Task task) {
    if (task.mode() != mode())
      throw new IllegalStateException("Embedding task differs from operation");
    return authorize(task.request());
  }
}
