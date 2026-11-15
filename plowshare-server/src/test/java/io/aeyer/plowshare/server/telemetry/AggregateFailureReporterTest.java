package io.aeyer.plowshare.server.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.aeyer.plowshare.server.telemetry.FailureReporter.Source;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AggregateFailureReporterTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @AfterEach
  void closeRegistry() {
    registry.close();
  }

  @Test
  void countersKeepEveryFailureWhileDiagnosticSamplesExcludeMessagesAndIdentities() {
    var time = new AtomicLong();
    var reporter = new AggregateFailureReporter(Duration.ofSeconds(30), time::get);
    var logger = (Logger) LoggerFactory.getLogger(AggregateFailureReporter.class);
    var captured = new ListAppender<ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    try {
      reporter.bind(registry);
      var failure =
          new SessionGoneException(
              "private-session-identity", new IllegalStateException("private-provider-token"));
      reporter.report(Source.RUNTIME, failure);
      reporter.report(Source.RUNTIME, failure);
      assertEquals(1, captured.list.size());
      time.set(Duration.ofSeconds(30).toNanos());
      reporter.report(Source.RUNTIME, failure);
      reporter.report(Source.WEBSOCKET, new ReadUnavailableException());
      reporter.report(Source.RUNTIME, new WorkspaceUnavailableException("private-path"));
      assertEquals(3, count(registry, "runtime", "session_gone"));
      assertEquals(1, count(registry, "websocket", "read_unavailable"));
      assertEquals(1, count(registry, "runtime", "workspace_unavailable"));
      assertEquals(4, captured.list.size());
      for (var event : captured.list) {
        assertTrue(event.getFormattedMessage().contains("stack="));
        assertFalse(event.getFormattedMessage().contains("private-"));
        assertEquals(null, event.getThrowableProxy());
      }
      assertTrue(captured.list.getFirst().getFormattedMessage().contains("SessionGoneException"));
      assertTrue(captured.list.getFirst().getFormattedMessage().contains("IllegalStateException"));
      assertEquals(18, registry.getMeters().size(), "Only fixed source/kind combinations exist");
      assertTrue(
          registry.getMeters().stream().allMatch(meter -> meter.getId().getTags().size() == 2));
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }
  }

  @Test
  void concurrentObservationsDoNotLoseCounts() throws Exception {
    var reporter = new AggregateFailureReporter(Duration.ofSeconds(30), () -> 0);
    try (var executor = Executors.newFixedThreadPool(4)) {
      reporter.bind(registry);
      for (int task = 0; task < 100; task++)
        executor.submit(() -> reporter.report(Source.HTTP, new ReadUnavailableException()));
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      assertEquals(100, count(registry, "http", "read_unavailable"));
    }
  }

  @Test
  void invalidDiagnosticIntervalsFailAtConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AggregateFailureReporter(Duration.ZERO, System::nanoTime));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AggregateFailureReporter(Duration.ofSeconds(-1), System::nanoTime));
  }

  private static double count(SimpleMeterRegistry registry, String source, String kind) {
    return registry
        .get("plowshare.failures")
        .tags("source", source, "kind", kind)
        .functionCounter()
        .count();
  }
}
