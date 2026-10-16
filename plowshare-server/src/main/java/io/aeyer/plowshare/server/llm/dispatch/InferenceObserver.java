package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Per-call metadata observer. Never accepts content, requests, credentials, or exception messages.
 */
public interface InferenceObserver {
  InferenceObserver NONE =
      new InferenceObserver() {
        @Override
        public boolean enabled() {
          return false;
        }

        @Override
        public void queued() {}

        @Override
        public void started() {}

        @Override
        public Attempt attempt() {
          return Attempt.NONE;
        }

        @Override
        public void finished(CallLifecycle outcome) {}

        @Override
        public InferenceCapture capture() {
          return null;
        }
      };

  /** Invoked inside the worker's failure boundary, after queueing and before transport. */
  default void beforeDispatch() {}

  /** Adds authorization without changing accounting attribution, including SYSTEM processing. */
  static InferenceObserver authorized(InferenceObserver delegate, CallAuthorization authority) {
    return new InferenceObserver() {
      @Override
      public boolean enabled() {
        return delegate.enabled();
      }

      @Override
      public void preflight(io.aeyer.plowshare.server.llm.counting.PromptCount count) {
        delegate.preflight(count);
      }

      @Override
      public void queued() {
        delegate.queued();
      }

      @Override
      public void started() {
        delegate.started();
      }

      @Override
      public void beforeDispatch() {
        authority.require();
        delegate.beforeDispatch();
      }

      @Override
      public Attempt attempt() {
        return delegate.attempt();
      }

      @Override
      public void finished(CallLifecycle outcome) {
        delegate.finished(outcome);
      }

      @Override
      public InferenceCapture capture() {
        return delegate.capture();
      }
    };
  }

  default void preflight(io.aeyer.plowshare.server.llm.counting.PromptCount count) {}

  boolean enabled();

  void queued();

  void started();

  Attempt attempt();

  void finished(CallLifecycle outcome);

  InferenceCapture capture();

  interface Attempt {
    Attempt NONE =
        new Attempt() {
          @Override
          public void response(Integer status, String requestId) {}

          @Override
          public void usage(TokenUsage usage) {}

          @Override
          public void reason(String reason) {}

          @Override
          public void output() {}

          @Override
          public void finished(CallLifecycle outcome) {}
        };

    void response(Integer status, String requestId);

    void usage(TokenUsage usage);

    void reason(String reason);

    void output();

    void finished(CallLifecycle outcome);

    default void succeeded(TokenUsage usage, String reason) {
      usage(usage);
      reason(reason);
      finished("content_filter".equals(reason) ? CallLifecycle.REFUSED : CallLifecycle.SUCCEEDED);
    }

    default void failed(Throwable failure) {
      finished(outcome(failure));
    }
  }

  /**
   * Compatibility for transports with exactly one attempt; retrying transports override the
   * observed overloads.
   */
  static <T> T once(
      InferenceObserver observer,
      Supplier<T> work,
      Function<T, TokenUsage> usage,
      Function<T, String> reason) {
    Attempt attempt = observer.attempt();
    try {
      T result = work.get();
      attempt.succeeded(usage.apply(result), reason.apply(result));
      return result;
    } catch (RuntimeException | Error failure) {
      attempt.failed(failure);
      throw failure;
    }
  }

  /** Classify only exception types, without retaining or interpreting their text. */
  static CallLifecycle outcome(Throwable failure) {
    if (failure instanceof CallerAbandonedException) {
      return CallLifecycle.CANCELLED;
    }
    int depth = 0;
    for (Throwable cause = failure; cause != null && depth++ < 16; cause = cause.getCause()) {
      if (cause instanceof InterruptedException) {
        return CallLifecycle.INTERRUPTED;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return CallLifecycle.FAILED;
  }
}
