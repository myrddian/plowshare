package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagePayloadCodecTest {
  private final MessagePayloadCodec codec = new MessagePayloadCodec(1024);

  private static MessagePayload request(MessagePayload.Body body) {
    return new MessagePayload(
        "bdm_0123456789ABCDEF",
        "ins_Sender",
        "ins_Recipient",
        body,
        null,
        "cnv_Target",
        true,
        false);
  }

  @Test
  void the_versioned_wire_contract_preserves_text_and_keeps_routing_inside_the_payload() {
    var payload = request(new MessagePayload.Inline("  Hello\n\"reader\" é😀  "));
    String wire = codec.write(payload);
    assertEquals(
        "{\"schema\":\"plowshare-message/1\",\"message\":\"bdm_0123456789ABCDEF\","
            + "\"sender\":\"ins_Sender\",\"recipient\":\"ins_Recipient\","
            + "\"body\":{\"kind\":\"inline\",\"text\":\"  Hello\\n\\\"reader\\\" é😀  \"},"
            + "\"reply_to\":null,\"conversation\":\"cnv_Target\",\"reply_expected\":true,\"final\":false}",
        wire);
    assertEquals(payload, codec.read(wire));
    assertEquals(wire, codec.write(codec.read(wire)));
  }

  @Test
  void progress_and_final_replies_preserve_their_explicit_flags() {
    for (boolean last : List.of(false, true)) {
      var reply =
          new MessagePayload(
              "bdm_Reply",
              "ins_Recipient",
              "ins_Sender",
              new MessagePayload.Inline("Answer"),
              "bdm_Request",
              "cnv_OriginalSender",
              false,
              last);
      assertEquals(reply, codec.read(codec.write(reply)));
    }
  }

  @Test
  void retained_bodies_reference_the_same_owning_message_without_inline_content() {
    var payload = request(new MessagePayload.Retained("bdm_0123456789ABCDEF"));
    String wire = codec.write(payload);
    assertTrue(
        wire.contains("\"body\":{\"kind\":\"retained\",\"message\":\"bdm_0123456789ABCDEF\"}"));
    assertFalse(wire.contains("\"text\""));
    assertEquals(payload, codec.read(wire));
    assertThrows(
        IllegalArgumentException.class, () -> request(new MessagePayload.Retained("bdm_Other")));
    assertThrows(IllegalArgumentException.class, () -> new MessagePayload.Retained("file_Path"));
  }

  @Test
  void malformed_addresses_and_illegal_reply_shapes_are_refused_at_construction() {
    for (String address :
        List.of(
            "",
            "ins_",
            "ins_a/b",
            " ins_A",
            "ins_A\n",
            "ins_é",
            "ins_A_B",
            "ins_" + "A".repeat(253))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new MessagePayload(
                  "bdm_A",
                  address,
                  "ins_B",
                  new MessagePayload.Inline("Body"),
                  null,
                  "cnv_C",
                  false,
                  false));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                "notAMessage",
                "ins_A",
                "ins_B",
                new MessagePayload.Inline("Body"),
                null,
                "cnv_C",
                false,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                "bdm_A",
                "ins_A",
                "ins_B",
                new MessagePayload.Inline("Body"),
                null,
                "ins_NotConversation",
                false,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                "bdm_A",
                "ins_A",
                "ins_B",
                new MessagePayload.Inline("Body"),
                "bdm_A",
                "cnv_C",
                false,
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                "bdm_A",
                "ins_A",
                "ins_B",
                new MessagePayload.Inline("Body"),
                null,
                "cnv_C",
                false,
                true));
    assertThrows(NullPointerException.class, () -> request(null));
  }

  @Test
  void utf8_allowances_are_explicit_and_text_is_never_truncated_or_replaced() {
    var small = new MessagePayloadCodec(8);
    for (String text : List.of("éééé", "😀😀", "12345678", "\n\t\"\\body")) {
      var payload = request(new MessagePayload.Inline(text));
      assertEquals(payload, small.read(small.write(payload)));
    }
    for (String text : List.of("ééééa", "😀😀a", "123456789")) {
      var payload = request(new MessagePayload.Inline(text));
      assertThrows(IllegalArgumentException.class, () -> small.write(payload));
      assertThrows(IllegalArgumentException.class, () -> small.read(codec.write(payload)));
    }
    for (String text : List.of("", "  \n", "NUL\0", "\uD800", "\uDC00"))
      assertThrows(IllegalArgumentException.class, () -> new MessagePayload.Inline(text));
    assertThrows(IllegalArgumentException.class, () -> new MessagePayloadCodec(0));
    assertThrows(IllegalArgumentException.class, () -> new MessagePayloadCodec(-1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessagePayloadCodec(RelayPort.MAX_TEXT_BYTES + 1));
  }

  @Test
  void unknown_missing_duplicate_wrong_type_or_nested_fields_never_partly_decode() {
    String valid = codec.write(request(new MessagePayload.Inline("Body")));
    for (String invalid :
        List.of(
            valid.replace(MessagePayloadCodec.VERSION, "plowshare-message/2"),
            valid.replace("\"schema\":\"plowshare-message/1\"", "\"schema\":1"),
            valid.replace("\"sender\":\"ins_Sender\"", "\"sender\":true"),
            valid.replace("\"recipient\":\"ins_Recipient\"", "\"recipient\":null"),
            valid.replace("\"conversation\":\"cnv_Target\"", "\"conversation\":[]"),
            valid.replace("\"reply_to\":null,", ""),
            valid.replace("\"reply_to\":null", "\"reply_to\":17"),
            valid.replace("\"reply_expected\":true", "\"reply_expected\":\"true\""),
            valid.replace("\"reply_expected\":true", "\"reply_expected\":1"),
            valid.replace("\"final\":false", "\"final\":null"),
            valid.replace("\"final\":false", "\"final\":true"),
            valid.replace("\"final\":false", "\"final\":false,\"from\":\"ins_Forged\""),
            valid.replace("\"final\":false", "\"final\":false,\"final\":false"),
            valid.replace("\"text\":\"Body\"", "\"text\":17"),
            valid.replace("\"text\":\"Body\"", "\"text\":\"Body\",\"grant\":\"admin\""),
            valid.replace("\"text\":\"Body\"", "\"text\":\"Body\",\"text\":\"Other\""),
            valid.replace("\"text\":\"Body\"", "\"message\":\"bdm_Ref\""),
            valid.replace("\"kind\":\"inline\"", "\"kind\":\"unknown\""),
            valid.replace("\"text\":\"Body\"", "\"text\":{\"nested\":[[[[0]]]]}"),
            valid.replace("\"text\":\"Body\"", "\"text\":\"\\u0000\""),
            valid + "{}",
            "[]",
            "null",
            "true",
            "{}",
            "")) {
      var failure = assertThrows(IllegalArgumentException.class, () -> codec.read(invalid));
      assertEquals("Invalid messaging payload", failure.getMessage());
      assertNull(failure.getCause(), "Parser causes may reveal message contents");
    }
    assertThrows(IllegalArgumentException.class, () -> codec.read(null));
  }

  @Test
  void retained_reference_fields_are_just_as_strict_as_inline_fields() {
    String valid = codec.write(request(new MessagePayload.Retained("bdm_0123456789ABCDEF")));
    for (String invalid :
        List.of(
            valid.replace(
                "\"kind\":\"retained\",\"message\":\"bdm_0123456789ABCDEF\"",
                "\"kind\":\"retained\",\"message\":\"bdm_Other\""),
            valid.replace("\"kind\":\"retained\"", "\"kind\":\"retained\",\"text\":\"hidden\""),
            valid.replace(
                "\"kind\":\"retained\",\"message\":\"bdm_0123456789ABCDEF\"",
                "\"kind\":\"retained\",\"message\":null"))) {
      assertThrows(IllegalArgumentException.class, () -> codec.read(invalid));
    }
  }

  @Test
  void large_encoded_input_is_refused_without_quoting_its_content() {
    var small = new MessagePayloadCodec(8);
    String valid = small.write(request(new MessagePayload.Inline("secret")));
    var failure =
        assertThrows(IllegalArgumentException.class, () -> small.read(valid + " ".repeat(5000)));
    assertEquals("Invalid messaging payload", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void maximum_identity_lengths_fit_the_wire_budget_without_shortening() {
    String message = "bdm_" + "A".repeat(252);
    String sender = "ins_" + "B".repeat(252);
    String recipient = "ins_" + "C".repeat(252);
    String conversation = "cnv_" + "D".repeat(252);
    var payload =
        new MessagePayload(
            message,
            sender,
            recipient,
            new MessagePayload.Retained(message),
            "bdm_" + "E".repeat(252),
            conversation,
            false,
            true);
    var small = new MessagePayloadCodec(1);
    assertEquals(payload, small.read(small.write(payload)));
  }
}
