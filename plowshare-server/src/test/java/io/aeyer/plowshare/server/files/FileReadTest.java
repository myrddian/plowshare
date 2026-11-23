package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileReadTest {
  @Test
  void consecutive_windows_reassemble_the_original_bytes() {
    String text = "\ufeff  雪\r\n\r\n  {\"error\":true}\nlast\r";
    var first = read(text, 0, 2);
    var last = read(text, first.window().nextOffset(), 2);
    assertEquals("\ufeff  雪\r\n\r\n", first.text());
    assertEquals("  {\"error\":true}\nlast\r", last.text());
    assertEquals(4, first.window().totalLines());
    assertEquals(Span.LINES, first.window().stoppedBy());
    assertNull(last.window().nextOffset());
    assertArrayEquals(
        text.getBytes(StandardCharsets.UTF_8),
        (first.text() + last.text()).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void blank_empty_and_past_end_are_successful_windows() {
    assertEquals(" \t\r\n", read(" \t\r\n", 0, 2).text());
    assertEquals(1, read(" \t\r\n", 0, 2).window().linesRead());
    assertEquals("", read("", 0, 1).text());
    assertEquals("", read("a\n", 9, 1).text());
    assertEquals(1, read("a\n", 9, 1).window().totalLines());
  }

  @Test
  void byte_limit_counts_the_original_separators_and_never_splits_a_line() {
    String exact = "x".repeat(Window.MAX_WINDOW_BYTES - 2) + "\r\n";
    var first = read(exact + "next", 0, 2);
    assertEquals(exact, first.text());
    assertEquals(Window.MAX_WINDOW_BYTES, first.window().byteLength());
    assertEquals(Span.BYTES, first.window().stoppedBy());
    assertEquals(1, first.window().nextOffset());
    assertEquals(
        FileResult.LINE_TOO_WIDE,
        assertThrows(FileRefusedException.class, () -> read("x" + exact, 0, 1)).facts().reason());
  }

  @Test
  void invalid_utf8_and_nul_are_refused_without_a_partial_window() {
    assertThrows(
        FileRefusedException.class,
        () -> FileRead.utf8("notes", new byte[] {'x', '\n', (byte) 0xff}, Window.of(0, 1), false));
    assertThrows(FileRefusedException.class, () -> read("x\n\0", 0, 1));
  }

  private static FileRead.Exact read(String text, int offset, int limit) {
    return FileRead.utf8(
        "notes", text.getBytes(StandardCharsets.UTF_8), Window.of(offset, limit), false);
  }
}
