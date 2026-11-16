package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Statement meters are counted per physical execute with fixed tags and never change results. */
class MicrometerJdbcExecutionTraceTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private static DataSource source(PreparedStatement statement) throws SQLException {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    return source;
  }

  private long count(String method, String outcome) {
    var timer =
        registry
            .find("plowshare.jdbc.statements")
            .tags("pool", "main", "method", method, "outcome", outcome)
            .timer();
    return timer == null ? 0 : timer.count();
  }

  @Test
  void countsEachExecuteByPoolMethodAndOutcome() throws Exception {
    var statement = mock(PreparedStatement.class);
    when(statement.executeUpdate()).thenThrow(new SQLException("boom"));
    var jfr = mock(JdbcExecutionTrace.class);
    var span = mock(JdbcExecutionTrace.Span.class);
    when(jfr.begin(anyString(), anyString())).thenReturn(span);
    var trace =
        new CompositeJdbcExecutionTrace(
            List.of(jfr, new MicrometerJdbcExecutionTrace(registry, "main")));
    var data = new TracedDataSource(source(statement), trace);
    try (var connection = data.getConnection();
        var prepared = connection.prepareStatement("SELECT 1")) {
      prepared.executeQuery();
      prepared.executeQuery();
      assertThrows(SQLException.class, prepared::executeUpdate);
    }
    assertEquals(2, count("executeQuery", "ok"));
    assertEquals(1, count("executeUpdate", "failed"));
    assertEquals(0, count("executeUpdate", "ok"));
    verify(jfr, times(3)).begin(anyString(), anyString());
    verify(span, times(3)).close();
    verify(span).failed(any(Throwable.class));
  }

  @Test
  void prometheusExportsExactThresholdBucketsAndCancelledExecutions() throws Exception {
    var clock = new MockClock();
    var prometheus =
        new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new PrometheusRegistry(), clock);
    try {
      var trace = new MicrometerJdbcExecutionTrace(prometheus, "main");
      var statement = mock(PreparedStatement.class);
      var cancelled = new SQLException("private cancellation details", "57014");
      when(statement.executeQuery())
          .thenAnswer(
              call -> {
                clock.add(Duration.ofMillis(100));
                return null;
              })
          .thenAnswer(
              call -> {
                clock.add(Duration.ofMillis(750));
                return null;
              })
          .thenAnswer(
              call -> {
                clock.add(Duration.ofMillis(1250));
                throw cancelled;
              });
      var composite = new CompositeJdbcExecutionTrace(List.of(new JfrJdbcExecutionTrace(), trace));
      try (var connection = new TracedDataSource(source(statement), composite).getConnection();
          var prepared = connection.prepareStatement("SELECT 'private search text'")) {
        prepared.executeQuery();
        prepared.executeQuery();
        assertEquals(cancelled, assertThrows(SQLException.class, prepared::executeQuery));
      }
      String scrape = prometheus.scrape();
      for (String outcome : List.of("ok", "failed")) {
        var counts =
            prometheus
                .get(MicrometerJdbcExecutionTrace.METER)
                .tags("pool", "main", "method", "executeQuery", "outcome", outcome)
                .timer()
                .takeSnapshot()
                .histogramCounts();
        double under500 = outcome.equals("ok") ? 1 : 0;
        double under1000 = outcome.equals("ok") ? 2 : 0;
        assertEquals(
            under500,
            Arrays.stream(counts)
                .filter(c -> c.bucket() == 500_000_000)
                .findFirst()
                .orElseThrow()
                .count());
        assertEquals(
            under1000,
            Arrays.stream(counts)
                .filter(c -> c.bucket() == 1_000_000_000)
                .findFirst()
                .orElseThrow()
                .count());
      }
      assertEquals(1, prometheus.get("plowshare.jdbc.cancellations").counter().count());
      assertTrue(scrape.contains("plowshare_jdbc_statements_seconds_bucket"));
      assertTrue(scrape.contains("le=\"0.5\""));
      assertTrue(scrape.contains("le=\"1.0\""));
      assertTrue(scrape.contains("plowshare_jdbc_cancellations_total"));
      assertTrue(!scrape.contains("private"));
    } finally {
      prometheus.close();
    }
  }

  @Test
  void aTraceThatThrowsNeverHidesTheStatementOrStarvesTheOthers() throws Exception {
    var broken = mock(JdbcExecutionTrace.class);
    when(broken.begin(anyString(), anyString())).thenThrow(new IllegalStateException("broken"));
    var trace =
        new CompositeJdbcExecutionTrace(
            List.of(broken, new MicrometerJdbcExecutionTrace(registry, "main")));
    var data = new TracedDataSource(source(mock(PreparedStatement.class)), trace);
    try (var connection = data.getConnection();
        var prepared = connection.prepareStatement("SELECT 1")) {
      prepared.executeQuery();
    }
    assertEquals(1, count("executeQuery", "ok"));
  }

  @Test
  void noRegistryRegistersNothingAndStillTraces() throws Exception {
    var none = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
    var trace = JdbcExecutionTraces.forPool(none, "main");
    assertTrue(trace instanceof JfrJdbcExecutionTrace);
    var data = new TracedDataSource(source(mock(PreparedStatement.class)), trace);
    try (var connection = data.getConnection();
        var prepared = connection.prepareStatement("SELECT 1")) {
      prepared.executeQuery();
    }
    assertNull(registry.find("plowshare.jdbc.statements").timer());
  }

  @Test
  void aRegistryAddsTheMeterNextToTheJfrTrace() throws Exception {
    var beans = new StaticListableBeanFactory();
    beans.addBean("registry", registry);
    var trace = JdbcExecutionTraces.forPool(beans.getBeanProvider(MeterRegistry.class), "main");
    assertTrue(trace instanceof CompositeJdbcExecutionTrace);
    var data = new TracedDataSource(source(mock(PreparedStatement.class)), trace);
    try (var connection = data.getConnection();
        var prepared = connection.prepareStatement("SELECT 1")) {
      prepared.executeQuery();
    }
    assertEquals(1, count("executeQuery", "ok"));
  }
}
