package io.aeyer.plowshare.server.files;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;

/** Metadata for a successful byte-exact UTF-8 window. The payload is stored separately. */
public record FileReadWindow(
    int offset,
    int linesRead,
    int totalLines,
    int byteLength,
    int limit,
    int requestedLimit,
    String stoppedBy) {
  public static final String WITHHELD =
      """
      {"error":{"source":"plowshare","code":"FILE_READ_WITHHELD","message":"The file window could not be released without changes."}}
      """
          .strip();

  public FileReadWindow {
    if (offset < 0
        || linesRead < 0
        || totalLines < 0
        || byteLength < 0
        || byteLength > Window.MAX_WINDOW_BYTES
        || limit < 1
        || limit > Window.MAX_WINDOW_LINES
        || requestedLimit < limit
        || linesRead > limit
        || linesRead > Math.max(0, totalLines - offset)
        || ((linesRead == 0) != (byteLength == 0))
        || (offset < totalLines && linesRead == 0)
        || !(linesRead < totalLines - offset
            ? Span.LINES.equals(stoppedBy) || Span.BYTES.equals(stoppedBy)
            : Span.END.equals(stoppedBy)))
      throw new IllegalArgumentException("Invalid lossless file window");
  }

  @JsonProperty(value = "nextOffset", access = JsonProperty.Access.READ_ONLY)
  public Integer nextOffset() {
    return Span.END.equals(stoppedBy) ? null : offset + linesRead;
  }

  public FileReadWindow requested(int number) {
    return new FileReadWindow(offset, linesRead, totalLines, byteLength, limit, number, stoppedBy);
  }

  public String note(String callId) {
    Integer next = nextOffset();
    return "File read window for tool call "
        + callId
        + ". Offset "
        + offset
        + " (zero-based), "
        + linesRead
        + " of "
        + totalLines
        + " lines, "
        + byteLength
        + " bytes."
        + (requestedLimit > limit
            ? " The requested limit " + requestedLimit + " was capped at " + limit + "."
            : "")
        + (next == null
            ? " End of file."
            : " Continue file_read on the same path with offset "
                + next
                + "."
                + (Span.BYTES.equals(stoppedBy) ? " The byte limit stopped this window." : ""));
  }
}
