package io.aeyer.plowshare.protocol.transport;

import java.nio.charset.StandardCharsets;

/** Private connection control, independent of the selected packet encoding. */
public final class ConcurrentDispatch {
  public static final String TYPE = "transport.dispatch";
  public static final String MODE = "concurrent-v1";

  /** Client call window; the server separately owns at most one physical reply handoff. */
  public static final int MAX_PENDING = 64;

  public static final int MAX_ID_BYTES = 128;

  private ConcurrentDispatch() {}

  /** Exact selection: this control never changes domain authority. */
  public record Selection(String mode) {
    public Selection {
      if (!MODE.equals(mode)) throw new IllegalArgumentException("Unsupported dispatch mode");
    }
  }

  /**
   * Effective per-connection bounds confirmed before any domain request is submitted.
   *
   * <p>{@code maxPending} is the depth the server advertises and the client then adopts as its call
   * window. It may be any value in {@code [1, MAX_PENDING]}: {@link #MAX_PENDING} is the protocol
   * ceiling, not a required value, so a server may advertise a shallower queue than clients could
   * tolerate. Clients already accept that whole range. The mode and ID bound stay exact because
   * they are part of the wire identity of this protocol version.
   */
  public record Selected(String mode, int maxPending, int maxIdBytes) {
    public Selected {
      if (!MODE.equals(mode)
          || maxPending < 1
          || maxPending > MAX_PENDING
          || maxIdBytes != MAX_ID_BYTES)
        throw new IllegalArgumentException("Invalid dispatch selection");
    }

    /** Selection at the protocol ceiling. */
    public Selected() {
      this(MODE, MAX_PENDING, MAX_ID_BYTES);
    }
  }

  public static void requireId(String id) {
    if (id == null
        || id.isBlank()
        || !id.equals(id.strip())
        || boundarySpace(id.codePointAt(0))
        || boundarySpace(id.codePointBefore(id.length()))
        || id.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES
        || id.codePoints()
            .anyMatch(value -> Character.isISOControl(value) || value >= 0xD800 && value <= 0xDFFF))
      throw new IllegalArgumentException("Invalid request correlation ID");
  }

  private static boolean boundarySpace(int point) {
    return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xFEFF;
  }
}
