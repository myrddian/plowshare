package io.aeyer.plowshare.server.telemetry;

/**
 * Observes whole work lifetimes without imposing execution deadlines. Callers supply fixed code
 * operation names, never user input, principals, selections or provider/SQL payloads.
 */
public interface WorkPerformanceReporter {
  enum Kind {
    REQUEST,
    SEARCH,
    WORKFLOW,
    EVENT,
    BACKGROUND,
    CACHE_READ
  }

  /** Closing an observation only releases telemetry resources; it cannot cancel observed work. */
  interface Observation extends AutoCloseable {
    @Override
    void close();
  }

  Observation UNOBSERVED = () -> {};
  WorkPerformanceReporter NONE = (kind, operation) -> UNOBSERVED;

  /** Nonblocking, best-effort observation. Pressure or logging failure cannot fail actual work. */
  Observation watch(Kind kind, String operation);
}
