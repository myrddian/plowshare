package io.aeyer.plowshare.server.telemetry;

/** Process-local failure observations; never changes an outcome or retries failed work. */
public interface FailureReporter {
  enum Source {
    WEBSOCKET,
    HTTP,
    RUNTIME
  }

  /**
   * Counts a failure at its handling boundary. Call only for server/dependency failures, not normal
   * caller refusals. Nested boundaries can observe the same underlying incident independently.
   * Implementations must keep labels bounded and exclude payloads and exception messages.
   */
  void report(Source source, Throwable failure);

  FailureReporter NONE = (source, failure) -> {};
}
