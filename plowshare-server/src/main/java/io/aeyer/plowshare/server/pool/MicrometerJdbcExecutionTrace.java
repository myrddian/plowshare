package io.aeyer.plowshare.server.pool;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
                .register(registry));
  }

  @Override
  public Span begin(String sql, String method) {
    OperationStatements.statement();
    var sample = Timer.start(registry);
    return new Span() {
      private boolean failed;

      @Override
      public void failed() {
        failed = true;
      }

      @Override
      public void close() {
        sample.stop(timer(method, failed));
      }
    };
  }
}
