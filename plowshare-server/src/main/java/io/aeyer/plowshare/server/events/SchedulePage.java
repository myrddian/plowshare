package io.aeyer.plowshare.server.events;

/** Exclusive stable-name cursor for user-facing pages; never bounds reconciliation scans. */
public record SchedulePage(String after, int limit) {
  public static final int DEFAULT_LIMIT = 50;

  public SchedulePage {
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("Schedule page limit must be between 1 and 100");
    if (after != null
        && (after.isBlank()
            || !after.equals(after.strip())
            || after.length() > 512
            || after.codePoints().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException(
          "Choose a bounded schedule cursor without whitespace or controls");
  }
}
