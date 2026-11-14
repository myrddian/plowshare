package io.aeyer.plowshare.server.pool;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/** Composition helper: the trace every traced pool shares. */
public final class JdbcExecutionTraces {
  private JdbcExecutionTraces() {}

  /**
   * The JFR trace, plus the statement meter for {@code pool} when a {@link MeterRegistry} exists.
   * Without a registry nothing is registered and only the JFR trace runs.
   */
  public static JdbcExecutionTrace forPool(ObjectProvider<MeterRegistry> registries, String pool) {
    var jfr = new JfrJdbcExecutionTrace();
    var registry = registries.getIfAvailable();
    return registry == null
        ? jfr
        : new CompositeJdbcExecutionTrace(
            List.of(jfr, new MicrometerJdbcExecutionTrace(registry, pool)));
  }
}
