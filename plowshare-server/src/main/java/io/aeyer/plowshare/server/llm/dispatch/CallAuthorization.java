package io.aeyer.plowshare.server.llm.dispatch;

import java.util.Objects;

/** Live authority rechecked on the inference worker after queueing, before any transport effect. */
@FunctionalInterface
public interface CallAuthorization {
  CallAuthorization DIRECT =
      new CallAuthorization() {
        @Override
        public void require() {}

        @Override
        public Disclosure disclosure() {
          return Disclosure.LIVE;
        }
      };

  /** Preview disclosure is independent of whether inference has a retained allowance binding. */
  enum Disclosure {
    BUFFERED,
    LIVE
  }

  /**
   * Unknown/internal callers withhold previews until completion. LIVE permits ordinary droppable
   * observations, not bypassing output filters or final authority checks. Already delivered
   * previews cannot be retracted; contained and information-dependent work retains its own gates.
   */
  default Disclosure disclosure() {
    return Disclosure.BUFFERED;
  }

  void require();

  /** Internal SYSTEM calls additionally bind the materialized request; never sent on the wire. */
  default void requireRequest(ChatRequest request) {
    require();
  }

  /** Internal embedding calls retain exact model and input binding. */
  default void requireRequest(EmbeddingRequest request) {
    require();
  }

  /** Explicit allowance evidence, absent for unclassified compatibility calls. */
  default InferenceBinding binding() {
    return null;
  }

  /**
   * Add a check-only fence while preserving the owner's evidence and disclosure classification. The
   * added check cannot reclassify previews; it is not a second disclosure policy.
   */
  default CallAuthorization and(CallAuthorization other) {
    Objects.requireNonNull(other);
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
      public void requireRequest(EmbeddingRequest request) {
        original.requireRequest(request);
        other.require();
      }

      @Override
      public Disclosure disclosure() {
        return original.disclosure();
      }

      @Override
      public InferenceBinding binding() {
        return original.binding();
      }
    };
  }
}
