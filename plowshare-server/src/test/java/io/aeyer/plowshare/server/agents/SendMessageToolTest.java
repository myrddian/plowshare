package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SendMessageToolTest {
  @Test
  void defaults_and_provider_identity_are_bound_per_invocation() {
    var sent = new ArrayList<SendMessageTool.Request>();
    var tool =
        new SendMessageTool(
            (request, home) -> {
              sent.add(request);
              return "queued";
            });
    tool.calledAs("call-1");
    assertEquals(
        "queued", tool.run("{\"to\":\"reviewer\",\"body\":\"Review\"}", Home.of("project")));
    assertFalse(sent.getFirst().replyExpected());
    assertFalse(sent.getFirst().finalReply());
    assertEquals("call-1", sent.getFirst().callId());
    tool.run("{\"reply_to\":\"message-1\",\"body\":\"Done\"}", Home.of("project"));
    assertTrue(sent.getLast().finalReply());
    assertNull(
        sent.getLast().callId(), "An earlier provider ID must not attach to another invocation");
  }

  @Test
  void invalid_requests_never_reach_the_transport() {
    var tool =
        new SendMessageTool(
            (request, home) -> {
              fail("Invalid request was sent");
              return "";
            });
    for (String arguments :
        java.util.List.of(
            "{\"body\":\"Hello\"}",
            "{\"to\":\"reviewer\",\"body\":\"Hello\",\"from\":\"forged\"}",
            "{\"to\":\"reviewer\",\"body\":\"Hello\",\"reply_expected\":\"true\"}",
            "{\"to\":\"reviewer\",\"body\":\"Hello\",\"final\":true}",
            "{\"to\":\"reviewer\",\"body\":\"Hello\",\"lifetime\":\"forever\"}",
            "{\"reply_to\":\"message-1\",\"body\":\"Hello\",\"lifetime\":\"task\"}",
            "{\"to\":\"ins_123\",\"body\":\"Hello\",\"lifetime\":\"task\"}")) {
      assertFalse(tool.run(arguments, Home.of("project")).isBlank(), arguments);
    }
  }

  @Test
  void destination_project_and_named_routes_do_not_allow_sender_or_reply_scope_forgery() {
    var sent = new ArrayList<SendMessageTool.Request>();
    var tool =
        new SendMessageTool(
            (request, home) -> {
              sent.add(request);
              return "queued";
            });
    assertEquals(
        "queued",
        tool.run(
            "{\"to_project\":\"notifications\",\"to\":\"notifier\",\"body\":\"Hello\"}",
            Home.of("payments")));
    assertEquals("notifications", sent.getLast().toProject());
    assertEquals(
        "queued", tool.run("{\"route\":\"notify\",\"body\":\"Hello\"}", Home.of("payments")));
    assertEquals("notify", sent.getLast().route());
    for (String args :
        java.util.List.of(
            "{\"route\":\"notify\",\"to\":\"other\",\"body\":\"Hello\"}",
            "{\"route\":\"notify\",\"to_project\":\"other\",\"body\":\"Hello\"}",
            "{\"route\":\"notify\",\"reply_to\":\"message\",\"body\":\"Hello\"}",
            "{\"to_project\":\"other\",\"reply_to\":\"message\",\"body\":\"Hello\"}",
            "{\"to_project\":true,\"to\":\"other\",\"body\":\"Hello\"}")) {
      assertNotEquals("queued", tool.run(args, Home.of("payments")));
    }
    assertEquals(2, sent.size());
  }
}
