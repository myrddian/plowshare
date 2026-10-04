package io.aeyer.plowshare.server.board;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who a message wakes — spec 2026-09-29, the project board and the swarm, §6. Pure: the board owes
 * a wake to each seat these rules name. Only what is addressed to a seat wakes it, so a swarm
 * settles rather than chattering; and a message never wakes its own author.
 */
public final class WakeRules {

  public enum Reason {
    OPENED("opened"),
    REPLY("reply"),
    MENTION("mention"),
    ALERT("alert"),
    REQUEST("request"),
    QUIET("quiet"),
    EXHAUSTED("exhausted");

    private final String wire;

    Reason(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }

    public static Reason fromWire(String wire) {
      for (Reason reason : values()) {
        if (reason.wire.equals(wire)) {
          return reason;
        }
      }
      throw new IllegalArgumentException("no wake reason is called '" + wire + "'");
    }
  }

  /** One seat to wake, and the first reason that named it. */
  public record Wake(String occupant, Reason reason) {}

  private WakeRules() {}

  /** A topic just opened with {@code opening}: every member but its author. */
  public static List<Wake> opened(BoardMessage opening, List<String> members) {
    String author = opening.authorOccupant();
    return members.stream()
        .filter(member -> !member.equals(author))
        .map(member -> new Wake(member, Reason.OPENED))
        .toList();
  }

  /**
   * Who {@code message} wakes once written. The order is the order of precedence when one seat is
   * named twice: a reply, a mention, an alert, a request.
   *
   * @param repliedTo the message it answers, or {@code null}
   * @param members the swarm's members as they stand
   */
  public static List<Wake> after(
      BoardMessage message, BoardMessage repliedTo, List<String> members) {
    Map<String, Reason> chosen = new LinkedHashMap<>();
    if (repliedTo != null && repliedTo.authorOccupant() != null) {
      chosen.putIfAbsent(repliedTo.authorOccupant(), Reason.REPLY);
    }
    for (String mentioned : message.mentions()) {
      if (members.contains(mentioned)) {
        chosen.putIfAbsent(mentioned, Reason.MENTION);
      }
    }
    if (message.alert()) {
      members.forEach(member -> chosen.putIfAbsent(member, Reason.ALERT));
    }
    if (BoardMessage.REQUEST.equals(message.kind())) {
      chosen.putIfAbsent(BoardSeat.OPENER, Reason.REQUEST);
    }
    String author = message.authorOccupant();
    if (author != null) {
      chosen.remove(author);
    }
    return chosen.entrySet().stream()
        .map(entry -> new Wake(entry.getKey(), entry.getValue()))
        .toList();
  }
}
