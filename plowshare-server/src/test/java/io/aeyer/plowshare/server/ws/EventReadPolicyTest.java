package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventReadPolicyTest {
  @Test
  void only_audited_independent_reads_with_valid_envelopes_are_admitted() {
    for (String type :
        List.of(
            "document.list",
            "document.detail",
            "inbox.list",
            "orchestration.list",
            "information.facets",
            "information.list",
            "information.read",
            "information.documents.list",
            "information.documents.facets",
            "relay.topics",
            "relay.log",
            "schedule.list",
            "schedule.files")) {
      assertTrue(EventReadPolicy.independent(frame("request", type)), type);
    }
    for (String type :
        List.of(
            "inbox.read",
            "orchestration.answer",
            "document.ask",
            "information.status",
            "information.await",
            "information.search",
            "information.tags",
            "schedule.sync",
            "relay.process",
            "relay.port.consume",
            "usage.subscribe",
            "acting.context.open",
            "future.read",
            "information.views.save")) {
      assertFalse(EventReadPolicy.independent(frame("request", type)), type);
    }
  }

  @Test
  void anExplicitlyNullActingSelectionRemainsAnOrderedMalformedFrame() {
    String ordinary = frame("request", "relay.log");
    assertTrue(EventReadPolicy.independent(ordinary));
    assertFalse(
        EventReadPolicy.independent(
            ordinary.replace("\"payload\":{}", "\"payload\":{},\"actingFor\":null")));
  }

  @Test
  void malformed_unknown_and_acting_frames_are_ordered_barriers() {
    for (String frame :
        List.of(
            "not json",
            "null",
            "[]",
            frame("request", "relay.log") + " {}",
            frame("request", "relay.log").replace("\"id\":\"request\"", "\"id\":17"),
            frame("request", "relay.log").replace("plowshare-v1", "other"),
            frame("request", "relay.log").replace("\"payload\":{}", "\"payload\":[]"),
            frame("request", "relay.log").replace("\"payload\":{}", "\"payload\":null"),
            frame("request", "relay.log").replace("\"type\":", "\"type\":\"relay.log\",\"type\":"),
            frame("request", "relay.log")
                .replace(
                    "\"payload\":{}",
                    "\"payload\":{},\"actingFor\":{\"context\":\"00000000-0000-0000-0000-000000000001\"}"))) {
      assertFalse(EventReadPolicy.independent(frame), frame);
    }
  }

  static String frame(String id, String type) {
    return "{\"id\":\""
        + id
        + "\",\"type\":\""
        + type
        + "\",\"protocol_version\":\"plowshare-v1\",\"payload\":{}}";
  }
}
