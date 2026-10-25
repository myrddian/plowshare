package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.frames.Envelope;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DispatchPolicyTest {
  private DispatchPolicy policy(String type, Map<String, Object> payload, String account) {
    return DispatchPolicy.decode(
        new Envelope("id", type, Envelope.CURRENT_VERSION, payload), account, "socket");
  }

  @Test
  void future_operations_and_hidden_write_status_remain_conservative() {
    assertEquals(
        DispatchPolicy.Kind.BARRIER,
        policy("information.status", Map.of("revision", "invalid"), "alice").kind());
    assertEquals(
        DispatchPolicy.Kind.BARRIER,
        policy("conversation.future", Map.of("conversation", "same"), "alice").kind());
    assertEquals(DispatchPolicy.Kind.BARRIER, policy("schedule.future", Map.of(), "alice").kind());
  }

  @Test
  void library_read_classification_preserves_acting_context_order_and_mutation_barriers() {
    var context = java.util.UUID.randomUUID();
    for (String type :
        java.util.List.of("document.list", "document.detail", "inbox.list", "orchestration.list")) {
      var acting =
          DispatchPolicy.decode(
              new Envelope(
                  "read",
                  type,
                  Envelope.CURRENT_VERSION,
                  Map.of(),
                  new io.aeyer.plowshare.protocol.ActingContext.Selection(context)),
              "alice",
              "socket");
      assertEquals(DispatchPolicy.Kind.RESOURCE, acting.kind(), type);
      assertEquals(
          java.util.List.of(new DispatchPolicy.Resource("acting", "socket", context.toString())),
          acting.resources());
    }
    for (String type : java.util.List.of("document.ask", "inbox.read", "orchestration.answer"))
      assertEquals(DispatchPolicy.Kind.BARRIER, policy(type, Map.of(), "alice").kind(), type);
  }

  @Test
  void actual_result_identity_and_shared_project_schedule_identity_cross_accounts() {
    assertEquals(
        policy("web.search", Map.of("query", "  HeLLo   world ", "max", 20), "alice"),
        policy("web.search", Map.of("query", "hello world", "max", 20), "bob"));
    assertEquals(
        policy("schedule.pause", Map.of("project", "shared"), "alice"),
        policy("schedule.sync", Map.of("project", "shared"), "bob"));
    assertNotEquals(
        policy("schedule.pause", Map.of(), "alice"), policy("schedule.pause", Map.of(), "bob"));
  }

  @Test
  void observation_does_not_retain_the_lane_that_can_unblock_it() {
    var consume = policy("relay.consume", Map.of("project", "p", "topic", "t"), "alice");
    assertEquals(DispatchPolicy.Kind.OBSERVER, consume.kind());
    assertTrue(consume.resources().isEmpty());
    assertEquals(
        DispatchPolicy.Kind.RESOURCE,
        policy("relay.ack", Map.of("project", "p", "topic", "t", "group", "g"), "alice").kind());
    assertEquals(
        DispatchPolicy.Kind.OBSERVER, policy("information.await", Map.of(), "alice").kind());
  }
}
