package io.aeyer.plowshare.server.board;

import java.time.Instant;
import java.util.List;

/** Anything said on a topic — spec §4. Whole text, as the record's rows keep it since V64. */
public record BoardMessage(
    String id,
    String topic,
    String replyTo,
    String authorKind,
    String author,
    String conversation,
    Integer entry,
    String kind,
    String title,
    String body,
    boolean alert,
    List<String> mentions,
    Instant postedAt) {

  public static final String BY_MEMBER = "member";
  public static final String BY_OPENER = "opener";
  public static final String BY_PERSON = "person";
  public static final String BY_HARNESS = "harness";

  public static final String POST = "post";
  public static final String PASS = "pass";
  public static final String DOCUMENT = "document";
  public static final String REQUEST = "request";
  public static final String RESOLUTION = "resolution";
  public static final String NOTE = "note";
  public static final String HOOK = "hook";

  public BoardMessage {
    mentions = List.copyOf(mentions);
  }

  /** The seat whose author wrote this, or {@code null} when a person or the harness did. */
  public String authorOccupant() {
    return switch (authorKind) {
      case BY_MEMBER -> author;
      case BY_OPENER -> BoardSeat.OPENER;
      default -> null;
    };
  }
}
