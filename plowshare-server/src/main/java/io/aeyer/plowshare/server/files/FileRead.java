package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** A provider's raw text window or the existing rendered view for a legacy or converted read. */
public sealed interface FileRead permits FileRead.Exact, FileRead.View {
  record Exact(String text, FileReadWindow window) implements FileRead {}

  record View(Span span) implements FileRead {}

  static Exact utf8(String path, byte[] bytes, Window window, boolean remote) {
    // Use the same strict decoder as the text view. NUL is treated as binary because it cannot
    // be retained in PostgreSQL text. In either case no partial payload is released.
    try {
      StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
    } catch (CharacterCodingException malformed) {
      throw notText(path, remote);
    }
    int total = 0;
    int taken = 0;
    int start = 0;
    int end = 0;
    String stop = Span.END;
    for (int cursor = 0; cursor < bytes.length; total++) {
      int lineStart = cursor;
      while (cursor < bytes.length && bytes[cursor] != '\r' && bytes[cursor] != '\n') {
        if (bytes[cursor++] == 0) throw notText(path, remote);
      }
      if (cursor < bytes.length
          && bytes[cursor++] == '\r'
          && cursor < bytes.length
          && bytes[cursor] == '\n') cursor++;
      if (total < window.offset() || !Span.END.equals(stop)) continue;
      int lineBytes = cursor - lineStart;
      if (taken == 0 && lineBytes > Window.MAX_WINDOW_BYTES)
        throw new FileRefusedException(
            FileResult.lineTooWide(
                FileRequest.READ, path, total, lineBytes, Window.MAX_WINDOW_BYTES),
            remote);
      if (end - start >= Window.MAX_WINDOW_BYTES
          || (taken < window.limit() && end - start + lineBytes > Window.MAX_WINDOW_BYTES)) {
        stop = Span.BYTES;
      } else if (taken == window.limit()) {
        stop = Span.LINES;
      } else {
        if (taken++ == 0) start = lineStart;
        end = cursor;
      }
    }
    return new Exact(
        new String(bytes, start, end - start, StandardCharsets.UTF_8),
        new FileReadWindow(
            window.offset(), taken, total, end - start, window.limit(), window.limit(), stop));
  }

  private static FileRefusedException notText(String path, boolean remote) {
    return new FileRefusedException(
        FileResult.notText(FileRequest.READ, path, FileResult.NOT_UTF8), remote);
  }
}
