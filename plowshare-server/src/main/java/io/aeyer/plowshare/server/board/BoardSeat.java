package io.aeyer.plowshare.server.board;

/**
 * One occupant's place on one topic, and its own conversation — spec §4. The occupant is a member's
 * agent name, or {@link #OPENER} for the seat a bot or a member opened the topic from.
 */
public record BoardSeat(
    String topic,
    String occupant,
    String conversation,
    boolean passed,
    String failedEnding,
    int silentWakes,
    String seenThrough,
    int alertsUsed) {

  /** Not a legal agent name, so it can never collide with a member's. */
  public static final String OPENER = "@opener";

  public boolean isOpener() {
    return OPENER.equals(occupant);
  }
}
