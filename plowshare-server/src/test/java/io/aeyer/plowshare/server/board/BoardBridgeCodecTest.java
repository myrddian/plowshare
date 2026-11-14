package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BoardBridgeCodecTest {
  @Test
  void request_text_is_preserved_and_reply_destination_is_optional_but_bounded() {
    assertEquals(
        new BoardBridge.Payload("  Body\ntext 😀  ", "collector.inbox"),
        BoardBridgeCodec.request(
            "{\"schema\":\"plowshare-board-request/1\",\"body\":\"  Body\\ntext 😀  \",\"reply_to\":\"collector.inbox\"}"));
    assertNull(
        BoardBridgeCodec.request("{\"schema\":\"plowshare-board-request/1\",\"body\":\"Body\"}")
            .replyTo());
  }

  @Test
  void untrusted_fields_types_versions_duplicates_and_invalid_text_are_refused_without_content() {
    String valid =
        "{\"schema\":\"plowshare-board-request/1\",\"body\":\"secret\",\"reply_to\":null}";
    for (String invalid :
        List.of(
            valid.replace("/1", "/2"),
            valid.replace("\"secret\"", "true"),
            valid.replace("\"secret\"", "\"\""),
            valid.replace("\"secret\"", "\"\\u0000\""),
            valid.replace("\"secret\"", "\"\\ud800\""),
            valid.replace("null", "\"../topic\""),
            valid.replace("null", "17"),
            valid.replace("null", "null,\"from\":\"forged\""),
            valid.replace("null", "null,\"reply_to\":null"),
            valid + "{}",
            "[]",
            "null",
            "{}")) {
      var failure =
          assertThrows(IllegalArgumentException.class, () -> BoardBridgeCodec.request(invalid));
      assertEquals("Invalid board bridge request", failure.getMessage());
      assertNull(failure.getCause());
    }
    assertThrows(
        IllegalArgumentException.class, () -> new BoardBridge.Payload("x".repeat(16001), null));
  }
}
