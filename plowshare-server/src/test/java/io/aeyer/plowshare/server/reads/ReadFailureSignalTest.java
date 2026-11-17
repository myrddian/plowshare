package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReadFailureSignalTest {
  @Test
  void nonCapacityReasonsAreNotBackpressure() {
    var failures =
        List.of(
            ReadFailedException.deadlineExceeded(),
            ReadFailedException.invalidated(),
            ReadFailedException.interrupted(),
            ReadFailedException.closed(),
            ReadFailedException.queryCancelled(null));
    assertEquals(
        List.of(ReadFailedException.Reason.values()),
        failures.stream().map(ReadFailedException::reason).toList());
    for (var failure : failures) assertFalse(ReadUnavailableException.class.isInstance(failure));
  }

  @Test
  void cancellationAndInterruptionPreserveDiagnosticCausesWithoutPublishingTheirMessages() {
    var cause = new IllegalStateException("private SQL text or path");
    for (var failure :
        List.of(
            ReadFailedException.queryCancelled(cause), ReadFailedException.interrupted(cause))) {
      assertSame(cause, failure.getCause());
      assertFalse(failure.getMessage().contains(cause.getMessage()));
    }
  }
}
