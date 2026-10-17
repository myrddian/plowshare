package io.aeyer.plowshare.server.llm.dispatch;

/** Live authority rechecked on the inference worker after queueing, before any transport effect. */
@FunctionalInterface
public interface CallAuthorization {
  CallAuthorization DIRECT = () -> {};

  void require();

  /** Internal SYSTEM calls additionally bind the materialized request; never sent on the wire. */
  default void requireRequest(ChatRequest request) {
    require();
  }

  /** Explicit allowance evidence, absent for unclassified compatibility calls. */
  default InferenceBinding binding() {
    return null;
  }

  /** Preserve evidence while intersecting a further fence; never replace the original authority. */
  default CallAuthorization and(CallAuthorization other) {
    java.util.Objects.requireNonNull(other);
    CallAuthorization original = this;
    return new CallAuthorization() {
      @Override
      public void require() {
        original.require();
        other.require();
      }

      @Override
      public void requireRequest(ChatRequest request) {
        original.requireRequest(request);
        other.require();
      }

      @Override
      public InferenceBinding binding() {
        return original.binding();
      }
    };
  }
}
