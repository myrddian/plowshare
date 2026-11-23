package io.aeyer.plowshare.server.approvals;

import java.time.Instant;
import java.util.Optional;

/** Optional debug metadata, scoped to an already authorized conversation and call. */
public interface ToolCallTraces {
  record Trace(
      String invocationId,
      Instant firstObservedAt,
      Instant sentAt,
      Instant replyObservedAt,
      Instant replyTimedOutAt,
      String requestId) {}

  void observe(String invocation, ToolEffectReceipt.Kind observation);

  Optional<Trace> read(String conversation, String invocation);
}
