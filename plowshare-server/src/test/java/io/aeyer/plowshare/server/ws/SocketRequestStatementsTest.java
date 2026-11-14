package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.transport.ConcurrentDispatch;
import io.aeyer.plowshare.server.pool.MicrometerJdbcExecutionTrace;
import io.aeyer.plowshare.server.pool.OperationStatements;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One socket request is one {@code socket_request} observation on either dispatch path, so a scope
 * added to the frame receipt (which both paths share) would show up here as a doubled count.
 */
class SocketRequestStatementsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerJdbcExecutionTrace trace =
      new MicrometerJdbcExecutionTrace(registry, "main");

  private FrameRouter router() {
    return new FrameRouter(
        Map.of(
            "information.facets",
            (payload, asking) -> {
              trace.begin("SELECT 1", "executeQuery").close();
              return Outcome.ok(Map.of());
            }));
  }

  private DistributionSummary socketRequests() {
    return registry.find(OperationStatements.METER).tag("kind", "socket_request").summary();
  }

  @Test
  void aSequentialFrameIsCountedOnce() throws Exception {
    try (var fixture = new ConcurrentEventChannelTest.Fixture(false, router())) {
      fixture.send("one", "information.facets", Map.of());
      fixture.reply();
      assertEquals(1, socketRequests().count());
      assertEquals(1.0, socketRequests().totalAmount());
    }
  }

  @Test
  void aConcurrentFrameIsCountedOnce() throws Exception {
    try (var fixture = new ConcurrentEventChannelTest.Fixture(false, router())) {
      fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
      fixture.reply();
      // The selection is itself a request that ran no statement; measure the next one against it.
      long before = socketRequests().count();
      double statementsBefore = socketRequests().totalAmount();
      fixture.send("one", "information.facets", Map.of());
      fixture.reply();
      assertEquals(1, socketRequests().count() - before);
      assertEquals(1.0, socketRequests().totalAmount() - statementsBefore);
    }
  }
}
