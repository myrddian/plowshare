package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import java.util.UUID;

/**
 * Operation-owned metadata, never authority. Pre-wire checkpoints must finish before transport;
 * response/terminal evidence may be buffered for the owner's next transaction.
 */
public interface InferenceProgress {
  InferenceProgress NONE = new InferenceProgress() {};

  default void accounted(InferenceCapture call) {}

  default void queued() {}

  default void started() {}

  default void attempting(UUID attemptId) {}

  /** Must not introduce a persistence failure after a provider effect; accounting owns receipts. */
  default void response(Integer status) {}

  /** Buffer terminal evidence without throwing a persistence failure into usable output. */
  default void finished(CallLifecycle outcome) {}
}
