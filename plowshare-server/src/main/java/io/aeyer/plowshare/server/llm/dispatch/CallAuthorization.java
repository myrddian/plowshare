package io.aeyer.plowshare.server.llm.dispatch;

/** Live authority rechecked on the inference worker after queueing, before any transport effect. */
@FunctionalInterface
public interface CallAuthorization {
  CallAuthorization DIRECT = () -> {};

  void require();
}
