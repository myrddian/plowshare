package io.aeyer.plowshare.server.hooks;

/**
 * Whether an addition belongs to this turn or to the conversation.
 *
 * <p>{@link #VOLATILE}: sent this turn only, recorded as a {@code HOOK} entry, never replayed —
 * {@code Reminding}'s shape. {@link #DURABLE}: recorded as a {@code NOTICE} where it was sent, and
 * replayed, so the next turn's prefix extends rather than changes.
 */
public enum Mode {
  VOLATILE("volatile"),
  DURABLE("durable");

  private final String wireName;

  Mode(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static Mode of(String wireName) {
    for (Mode mode : values()) {
      if (mode.wireName.equals(wireName)) {
        return mode;
      }
    }
    throw new IllegalArgumentException(
        "'" + wireName + "' is not a mode; an addition is" + " volatile or durable");
  }
}
