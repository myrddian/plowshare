package io.aeyer.plowshare.server.hooks;

import java.util.List;

/**
 * What {@code log.open} decided: text for the log's fixed opening, and the record of every decision
 * (spec 2026-09-28-hooks-reach-the-log decision 9).
 */
public record LogOpen(List<String> additions, List<HookRecord> records) {

  public static final LogOpen NOTHING = new LogOpen(List.of(), List.of());

  public LogOpen {
    additions = List.copyOf(additions);
    records = List.copyOf(records);
  }

  /** The opening block: every addition in layer and file order, a blank line between. */
  public String joined() {
    return String.join("\n\n", additions);
  }
}
