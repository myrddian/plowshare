package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.RelayPort;
import org.junit.jupiter.api.Test;

class RelayExecutionPublisherTest {
  @Test
  void executionCoordinatesAreStableAndDisjointFromAuthenticatedSdkPublishers() {
    String identity = RelayExecutionPublisher.identity("alice", "writer");
    assertEquals(identity, RelayExecutionPublisher.identity("alice", "writer"));
    assertNotEquals(identity, RelayExecutionPublisher.identity("alice", "reviewer"));
    assertNotEquals(identity, RelayExecutionPublisher.identity("bob", "writer"));
    assertNotEquals(identity, RelayPort.publisher("alice"));
    assertTrue(RelayExecutionPublisher.reserved(identity));
    assertTrue(RelayExecutionPublisher.reserved("execution:malformed"));
    assertFalse(RelayExecutionPublisher.reserved(RelayPort.publisher("alice")));
    assertFalse(RelayExecutionPublisher.reserved(null));
    assertThrows(
        IllegalArgumentException.class, () -> RelayExecutionPublisher.identity("", "writer"));
  }
}
