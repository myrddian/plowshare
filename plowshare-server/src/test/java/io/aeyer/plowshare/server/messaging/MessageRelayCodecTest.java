package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageRelayCodecTest {
  private static final String ID = "11111111-1111-4111-8111-111111111111";
  private static final String RECEIVE =
      "{\"schema\":\"plowshare-message-request/1\",\"requestId\":\""
          + ID
          + "\",\"client\":\"client-1\",\"context\":null,\"body\":\"Question 😀\",\"command\":null}";
  private static final String CANCEL =
      "{\"schema\":\"plowshare-message-cancel/1\",\"requestId\":\""
          + ID
          + "\",\"client\":\"client-1\",\"task\":\""
          + ID
          + "\"}";
  private static final String INSPECT =
      "{\"schema\":\"plowshare-message-inspect/1\",\"requestId\":\""
          + ID
          + "\",\"client\":\"client-1\",\"task\":null,\"originalRequest\":\""
          + ID
          + "\",\"cursor\":null}";

  @Test
  void validates_all_three_typed_actions_with_explicit_nulls() {
    var receive =
        assertInstanceOf(
            MessageRelayPayload.Receive.class,
            MessageRelayCodec.command("message.receive", RECEIVE));
    assertEquals("Question 😀", receive.body());
    assertInstanceOf(
        MessageRelayPayload.Cancel.class, MessageRelayCodec.command("message.cancel", CANCEL));
    assertInstanceOf(
        MessageRelayPayload.Inspect.class, MessageRelayCodec.command("message.inspect", INSPECT));
  }

  @Test
  void every_action_rejects_missing_duplicate_unknown_fields_and_coercion() {
    for (var entry :
        List.of(
            List.of("message.receive", RECEIVE),
            List.of("message.cancel", CANCEL),
            List.of("message.inspect", INSPECT))) {
      String receiver = entry.get(0), valid = entry.get(1);
      for (String invalid :
          List.of(
              "null",
              "[]",
              "{}",
              valid + " {}",
              valid.replace("\"client\":\"client-1\"", "\"client\":17"),
              valid.replace(
                  "\"client\":\"client-1\"", "\"client\":\"client-1\",\"client\":\"other\""),
              valid.replace("\"client\":\"client-1\",", ""),
              valid.replace("\"client\":", "\"account\":\"forged\",\"client\":"),
              valid.replace(ID, "1-1-1-1-1"))) {
        assertThrows(
            IllegalArgumentException.class,
            () -> MessageRelayCodec.command(receiver, invalid),
            invalid);
      }
    }
  }

  @Test
  void receive_defends_body_command_context_and_authority_boundaries() {
    for (String invalid :
        List.of(
            RECEIVE.replace("Question 😀", " "),
            RECEIVE.replace("Question 😀", "x".repeat(65537)),
            RECEIVE.replace("\"command\":null", "\"command\":\"unqualified\""),
            RECEIVE.replace("\"context\":null", "\"context\":true"),
            RECEIVE.replace("\"body\":", "\"agent\":\"forged\",\"body\":"),
            RECEIVE.replace("\"body\":", "\"instance\":\"bdmi_Forged\",\"body\":"),
            RECEIVE.replace("\"body\":", "\"replyTopic\":\"forged\",\"body\":"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> MessageRelayCodec.command("message.receive", invalid));
    }
  }

  @Test
  void inspection_requires_one_scoped_selector_and_canonical_progress() {
    for (String invalid :
        List.of(
            INSPECT.replace("\"task\":null", "\"task\":\"" + ID + "\""),
            INSPECT.replace("\"originalRequest\":\"" + ID + "\"", "\"originalRequest\":null"),
            INSPECT.replace("\"cursor\":null", "\"cursor\":\"arbitrary\""))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> MessageRelayCodec.command("message.inspect", invalid));
    }
  }

  @Test
  void bounded_unicode_and_whitespace_chunks_round_trip_with_iso_instants() {
    UUID id = UUID.fromString(ID);
    Instant at = Instant.parse("2026-01-01T00:00:00Z");
    var reply =
        new MessageRelayPayload.Reply(
            "bdm_Reply", "😀".repeat(16384), "0", false, true, false, null, at);
    var blank =
        new MessageRelayPayload.Reply("bdm_Other", " \n\t", "0", true, false, false, null, at);
    var task =
        new MessageRelayPayload.Task(
            id,
            id,
            id,
            "worker",
            "bdm_Request",
            "COMPLETED",
            "ANSWERED",
            List.of(reply, blank),
            at);
    var snapshot =
        new MessageRelayPayload.Snapshot(
            "plowshare-message-task/1",
            id,
            "source-event",
            "client-1",
            "7",
            task,
            null,
            new MessageRelayPayload.Cursor(id, 10, 9, 16384, false).encode(),
            null);
    String encoded = MessageRelayCodec.write(snapshot);
    assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length < 256 * 1024);
    assertTrue(encoded.contains("2026-01-01T00:00:00Z"));
    assertEquals(snapshot, MessageRelayCodec.stored(encoded));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessageRelayPayload.Reply(
                "bdm_Reply", "😀".repeat(16385), "0", true, true, false, null, at));
  }
}
