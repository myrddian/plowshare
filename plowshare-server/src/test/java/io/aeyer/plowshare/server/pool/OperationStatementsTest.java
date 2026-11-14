package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Statements are counted per thread inside an open scope and recorded once when it closes. */
@SuppressWarnings("try")
class OperationStatementsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerJdbcExecutionTrace trace =
      new MicrometerJdbcExecutionTrace(registry, "main");

  /** Later test classes in this JVM rely on a bound registry; rebind whatever a test unbound. */
  @AfterEach
  void rebind() {
    OperationStatements.bind(registry);
  }

  private void statements(int count) {
    for (int i = 0; i < count; i++) {
      trace.begin("SELECT 1", "executeQuery").close();
    }
  }

  private DistributionSummary summary(OperationStatements.Kind kind) {
    return registry
        .find(OperationStatements.METER)
        .tag("kind", kind.name().toLowerCase(Locale.ROOT))
        .summary();
  }

  @Test
  void recordsStatementsExecutedInsideTheScope() {
    try (var ignored = OperationStatements.open(OperationStatements.Kind.TOOL_CALL)) {
      statements(3);
    }
    var summary = summary(OperationStatements.Kind.TOOL_CALL);
    assertEquals(1, summary.count());
    assertEquals(3.0, summary.totalAmount());
  }

  @Test
  void statementsOutsideAnyScopeRecordNothing() {
    statements(2);
    assertEquals(0, summary(OperationStatements.Kind.TOOL_CALL).count());
  }

  @Test
  void nestedScopesBothRecordTheirOwnStatements() {
    try (var ignored = OperationStatements.open(OperationStatements.Kind.SOCKET_REQUEST)) {
      statements(1);
      try (var ignore = OperationStatements.open(OperationStatements.Kind.JOB_STEP)) {
        statements(2);
      }
      statements(1);
    }
    assertEquals(2.0, summary(OperationStatements.Kind.JOB_STEP).totalAmount());
    // The outer scope also sees the inner statements: it is the whole request.
    assertEquals(4.0, summary(OperationStatements.Kind.SOCKET_REQUEST).totalAmount());
  }

  @Test
  void scopeIsSafeWithoutAMeterRegistry() {
    OperationStatements.unbind();
    try (var ignored = OperationStatements.open(OperationStatements.Kind.TOOL_CALL)) {
      statements(1);
    }
    assertEquals(0, summary(OperationStatements.Kind.TOOL_CALL).count());
  }

  @Test
  void aScopeThatEndsInAnExceptionStillRecordsAndRestoresTheCounter() {
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (var ignored = OperationStatements.open(OperationStatements.Kind.TOOL_CALL)) {
            statements(2);
            throw new IllegalStateException("tool failed");
          }
        });
    var summary = summary(OperationStatements.Kind.TOOL_CALL);
    assertEquals(1, summary.count());
    assertEquals(2.0, summary.totalAmount());
    // Statements after the failed scope are outside every scope, and a later scope starts clean.
    statements(5);
    try (var ignored = OperationStatements.open(OperationStatements.Kind.TOOL_CALL)) {
      statements(1);
    }
    assertEquals(2, summary.count());
    assertEquals(3.0, summary.totalAmount());
  }
}
