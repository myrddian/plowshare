package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.RelayPorts;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayPortFramesTest {
  final RelayPorts ports = mock(RelayPorts.class);
  final RelayPortFrames frames = new RelayPortFrames(ports);
  final Asking asking = new Asking("session", "owner");

  @Test
  void exact_group_inspection_rejects_extra_fields_before_repository_access() {
    var query =
        Map.<String, Object>of("project", "project", "topic", "any.topic", "group", "detectors");
    frames.inspectGroup(query, asking);
    verify(ports).inspectGroup(eq("owner"), any());
    reset(ports);
    var invalid = new HashMap<>(query);
    invalid.put("generation", UUID.randomUUID().toString());
    assertThrows(CallerFault.class, () -> frames.inspectGroup(invalid, asking));
    verifyNoInteractions(ports);
  }

  @Test
  void group_retirement_requires_exact_typed_coordinates_before_authorization() {
    var valid =
        Map.<String, Object>of(
            "project",
            "project",
            "topic",
            "any.topic",
            "group",
            "detectors",
            "generation",
            UUID.randomUUID().toString());
    frames.deleteGroup(valid, asking);
    verify(ports).deleteGroup(eq("owner"), any());
    reset(ports);
    for (var change :
        Map.<String, Object>of("group", " ", "generation", "bad", "project", 17, "unexpected", true)
            .entrySet()) {
      var invalid = new HashMap<>(valid);
      invalid.put(change.getKey(), change.getValue());
      assertThrows(CallerFault.class, () -> frames.deleteGroup(invalid, asking));
    }
    var missing = new HashMap<>(valid);
    missing.remove("generation");
    assertThrows(CallerFault.class, () -> frames.deleteGroup(missing, asking));
    verifyNoInteractions(ports);
  }

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
      var invalid = new HashMap<>(valid);
      invalid.put(pair.getKey(), pair.getValue());
      assertThrows(CallerFault.class, () -> frames.publish(invalid, asking));
    }
    verifyNoInteractions(ports);
  }
}
