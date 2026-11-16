package io.aeyer.plowshare.server.pool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts and times every physical JDBC execute as {@value #METER}.
 *
 * <p>Tags are {@code pool} (which pool the statement ran on: {@code main}, {@code
 * projection-versions} or {@code listen}), {@code method} (the JDBC execute method) and {@code
 * outcome} ({@code ok} or {@code failed}). All three are fixed sets. The SQL text, its hash, binds
 * and identities are never tags, so the meter set stays constant whatever the traffic; statement
 * attribution is the job of the opt-in JFR events.
 *
 * <p>The count is the exact number of round trips, which is what a query-efficiency change must
 * reduce. A batch is one execute, as in {@link JdbcExecutionTrace}.
 *
 * <p>The histogram includes exact 500 ms and 1 s buckets for query latency tracking. Failed and
 * cancelled executions retain their durations; cancellations also increment a separate bounded
 * counter. Timing ends at execute return/throw, excluding acquisition and later result iteration.
 */
public final class MicrometerJdbcExecutionTrace implements JdbcExecutionTrace {
  public static final String METER = "plowshare.jdbc.statements";

  private final MeterRegistry registry;
  private final String pool;

  /**
   * Timers by method and outcome, so the hot path is one map lookup instead of building tags. The
   * key set is bounded by the JDBC execute method names times two outcomes.
   */
  private final ConcurrentHashMap<TimerKey, Timer> timers = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<String, Counter> cancellations = new ConcurrentHashMap<>();

  private record TimerKey(String method, boolean failed) {}

  public MicrometerJdbcExecutionTrace(MeterRegistry registry, String pool) {
    this.registry = Objects.requireNonNull(registry);
    this.pool = Objects.requireNonNull(pool);
    OperationStatements.bind(registry);
  }

  private Timer timer(String method, boolean failed) {
    return timers.computeIfAbsent(
        new TimerKey(method, failed),
        key ->
            Timer.builder(METER)
                .description("Physical JDBC statement executions and their duration")
                .tag("pool", pool)
                .tag("method", method)
                .tag("outcome", failed ? "failed" : "ok")
                .serviceLevelObjectives(Duration.ofMillis(500), Duration.ofSeconds(1))
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(2))
                .register(registry));
  }

  @Override
  public Span begin(String sql, String method) {
    OperationStatements.statement();
    var sample = Timer.start(registry);
    return new Span() {
      private boolean failed;
      private boolean cancelled;

      @Override
      public void failed() {
        failed = true;
      }

      @Override
      public void failed(Throwable failure) {
        failed();
        // Query cancellation is not proof of a timeout: PostgreSQL uses the same SQLSTATE for
        // explicit cancellation. Retain only this fixed classification, never the exception.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
          if (cause instanceof SQLTimeoutException
              || cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) {
            cancelled = true;
            break;
          }
        }
      }

      @Override
      public void close() {
        sample.stop(timer(method, failed));
        if (cancelled)
          cancellations
              .computeIfAbsent(
                  method,
                  key ->
                      Counter.builder("plowshare.jdbc.cancellations")
                          .description("JDBC executions ending in query cancellation or timeout")
                          .tag("pool", pool)
                          .tag("method", key)
                          .register(registry))
              .increment();
      }
    };
  }
}
