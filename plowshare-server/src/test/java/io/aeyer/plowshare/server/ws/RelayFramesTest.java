package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayControl;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayFramesTest {
  private final RelayInspection inspection = mock(RelayInspection.class);
  private final RelayProcessing processing = mock(RelayProcessing.class);
  private final RelayOperations operations = mock(RelayOperations.class);
  private final RelayFrames frames = new RelayFrames(inspection, processing, operations);

  @Test
  void project_inspection_defaults_omitted_system_flag_without_accepting_null_or_coercion() {
    var asking = new Asking("session", "owner");
    var topics = new RelayLog.TopicsQuery("project", false, null);
    var log = new RelayLog.Query("project", false, "event", "0", 100);
    when(inspection.topics("owner", topics))
        .thenReturn(new RelayLog.Topics(new RelayLog.Scope("project", false), List.of()));
    frames.topics(Map.of("project", "project"), asking);
    frames.log(Map.of("project", "project", "topic", "event", "after", "0", "limit", 100), asking);
    verify(inspection).topics("owner", topics);
    verify(inspection).log("owner", log);

    var invalid = new java.util.HashMap<String, Object>(Map.of("project", "project"));
    for (var value : new Object[] {null, "false", 0}) {
      invalid.put("system", value);
      assertThrows(CallerFault.class, () -> frames.topics(invalid, asking));
      invalid.put("topic", "event");
      assertThrows(CallerFault.class, () -> frames.log(invalid, asking));
      invalid.remove("topic");
    }
    verifyNoMoreInteractions(inspection);
  }

  @Test
  void invalid_requests_are_refused_before_business_logic() {
    for (var payload :
        List.of(
            Map.<String, Object>of(),
            Map.<String, Object>of("project", "project", "system", true),
            Map.<String, Object>of("project", "project", "limit", 1.5),
            Map.<String, Object>of("project", "project", "limit", "1"),
            Map.<String, Object>of("project", "project", "grant", "admin"),
            Map.<String, Object>of("project", 5)))
      assertThrows(CallerFault.class, () -> frames.topics(payload, new Asking("session", "owner")));
    assertThrows(
        CallerFault.class,
        () ->
            frames.log(
                Map.of("project", "project", "topic", "event", "after", "9223372036854775808"),
                new Asking("session", "owner")));
    assertThrows(
        CallerFault.class,
        () ->
            frames.process(
                Map.of("project", "project", "limit", 33), new Asking("session", "owner")));
    verifyNoInteractions(inspection, processing);
  }

  @Test
  void socket_identity_is_authority_and_system_inspection_is_explicit() {
    var query = new RelayLog.TopicsQuery(null, true, null);
    when(inspection.topics("owner", query))
        .thenReturn(new RelayLog.Topics(new RelayLog.Scope(null, true), List.of()));
    assertNotNull(frames.topics(Map.of("system", true), new Asking("session", "owner")).payload());
    verify(inspection).topics("owner", query);
    assertThrows(
        CallerFault.class, () -> frames.topics(Map.of("system", true), new Asking("session")));
    assertEquals(4, frames.frames().size());
  }

  @Test
  void controls_reject_invalid_discriminants_and_use_socket_identity() {
    String id = UUID.randomUUID().toString();
    var body =
        new java.util.HashMap<String, Object>(
            Map.of(
                "requestId",
                id,
                "project",
                "project",
                "topic",
                "event",
                "topicGeneration",
                id,
                "action",
                "ACKNOWLEDGE_GAP",
                "subscriber",
                "relay.notices.release",
                "subscriptionGeneration",
                id,
                "expiredThrough",
                "9007199254740993",
                "reason",
                "Acknowledge expired work"));
    var request =
        new RelayControl.Request(
            id,
            "project",
            "event",
            id,
            RelayControl.Action.ACKNOWLEDGE_GAP,
            "relay.notices.release",
            id,
            null,
            null,
            "9007199254740993",
            null,
            "Acknowledge expired work");
    var result =
        new RelayControl.Result(
            id,
            "project",
            "event",
            request.action(),
            request.subscriber(),
            null,
            "GAP_ACKNOWLEDGED",
            "9007199254740993",
            Instant.now());
    when(operations.operate("owner", request)).thenReturn(result);
    assertEquals(result, frames.operate(body, new Asking("session", "owner")).payload());
    body.put("deliveryId", id);
    assertThrows(CallerFault.class, () -> frames.operate(body, new Asking("session", "owner")));
    body.remove("deliveryId");
    body.put("expiredThrough", 9007199254740993L);
    assertThrows(CallerFault.class, () -> frames.operate(body, new Asking("session", "owner")));
    verify(operations).operate("owner", request);
    verifyNoMoreInteractions(operations);
  }
}
