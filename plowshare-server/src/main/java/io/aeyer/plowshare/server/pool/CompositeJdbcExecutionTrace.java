package io.aeyer.plowshare.server.pool;

import java.util.List;
import java.util.Objects;

/**
 * Fans one physical JDBC execute out to several {@link JdbcExecutionTrace}s, so the opt-in JFR
 * events and the always-on meters observe the same invocation without a second proxy layer.
 *
 * <p>Spans are closed in reverse order of opening. A trace that throws from {@code begin}, {@code
 * failed} or {@code close} must not hide the statement's own outcome or starve the remaining
 * traces, so those failures are discarded here: observation never changes what the database call
 * does.
 */
public final class CompositeJdbcExecutionTrace implements JdbcExecutionTrace {
  private final List<JdbcExecutionTrace> traces;

  public CompositeJdbcExecutionTrace(List<JdbcExecutionTrace> traces) {
    this.traces = List.copyOf(Objects.requireNonNull(traces));
  }

  @Override
  public Span begin(String sql, String method) {
    var spans = new Span[traces.size()];
    int opened = 0;
    for (var trace : traces) {
      try {
        spans[opened] = trace.begin(sql, method);
      } catch (RuntimeException observabilityFailure) {
        // Intentionally discarded: this trace loses one sample, the statement still runs.
        continue;
      }
      opened++;
    }
    int count = opened;
    return new Span() {
      @Override
      public void failed() {
        for (int i = 0; i < count; i++) {
          try {
            spans[i].failed();
          } catch (RuntimeException observabilityFailure) {
            // Intentionally discarded; see the class contract.
          }
        }
      }

      @Override
      public void close() {
        for (int i = count - 1; i >= 0; i--) {
          try {
            spans[i].close();
          } catch (RuntimeException observabilityFailure) {
            // Intentionally discarded; see the class contract.
          }
        }
      }
    };
  }
}
