package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RelayPortTest {
  @Test
  void topic_capacity_is_independent_of_subscriber_and_group_limits() {
    String topic = "a".repeat(1024);
    assertEquals(topic, RelayPort.topic(topic));
    assertThrows(IllegalArgumentException.class, () -> RelayPort.topic(topic + "a"));
    assertThrows(IllegalArgumentException.class, () -> RelayPort.topic("a/b"));
    assertDoesNotThrow(() -> RelayPort.name("a".repeat(160)));
    assertThrows(IllegalArgumentException.class, () -> RelayPort.name("a".repeat(161)));
    assertDoesNotThrow(() -> RelayPort.group("a".repeat(140)));
    assertThrows(IllegalArgumentException.class, () -> RelayPort.group("a".repeat(141)));
  }

  @Test
  void text_limit_counts_raw_utf8_bytes_and_preserves_content() {
    int max = RelayPort.MAX_TEXT_BYTES;
    for (String text :
        new String[] {
          "x".repeat(max),
          "é".repeat(max / 2),
          "😀".repeat(max / 4),
          "界".repeat(max / 3) + "x".repeat(max % 3),
          "\u0001".repeat(max)
        }) {
      assertSame(text, RelayPort.text(text));
      assertThrows(IllegalArgumentException.class, () -> RelayPort.text(text + "x"));
    }
  }

  @Test
  void malformed_unicode_blank_and_nul_are_refused_without_repair() {
    for (String text : new String[] {"", " ", "x\0", "\ud800", "x\udfff", "\ud800x"})
      assertThrows(IllegalArgumentException.class, () -> RelayPort.text(text));
    assertThrows(IllegalArgumentException.class, () -> RelayPort.text(null));
  }
}
