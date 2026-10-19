package io.aeyer.plowshare.server.llm.dispatch;

import java.util.UUID;

/** Retains allowance claims and binds each to at most one dispatcher submission. */
public interface InferenceAdmissions {
  void retain(InferenceBinding binding);

  void submitted(InferenceBinding binding);

  void require(InferenceBinding binding);

  void accounted(InferenceBinding binding, UUID call);
}
