package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.RelayPorts;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayPortFramesTest {
  final RelayPorts ports = mock(RelayPorts.class);
  final RelayPortFrames frames = new RelayPortFrames(ports);
  final Asking asking = new Asking("session", "owner");

  @Test
  void strict_publication_boundary_rejects_unknown_coerced_and_invalid_fields() {
    var valid =
        Map.<String, Object>of(
            "requestId",
            UUID.randomUUID().toString(),
            "project",
            "project",
            "topic",
            "any.topic",
            "text",
            "complete message",
            "occurredAt",
            "2026-01-01T00:00:00Z");
    frames.publish(valid, asking);
    verify(ports).publish(eq("owner"), any());
    reset(ports);
    for (var pair :
        Map.<String, Object>of(
                "publisher", "admin", "text", 17, "occurredAt", "invalid", "requestId", "not-uuid")
            .entrySet()) {
      var invalid = new java.util.HashMap<>(valid);
      invalid.put(pair.getKey(), pair.getValue());
      assertThrows(CallerFault.class, () -> frames.publish(invalid, asking));
    }
    verifyNoInteractions(ports);
  }
}
