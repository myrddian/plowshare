package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.aeyer.plowshare.server.board.WakeRules.Reason;
import io.aeyer.plowshare.server.board.WakeRules.Wake;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec 2026-09-29 §6, one row per test. */
class WakeRulesTest {

  private static final List<String> MEMBERS = List.of("researcher", "spec_writer", "critic");

  private static BoardMessage message(
      String authorKind, String author, String kind, boolean alert, String... mentions) {
    return new BoardMessage(
        "bdm_x",
        "bdt_1",
        null,
        authorKind,
        author,
        null,
        null,
        kind,
        BoardMessage.REQUEST.equals(kind) ? "split" : null,
        "text",
        alert,
        List.of(mentions),
        Instant.EPOCH);
  }

  private static BoardMessage by(String member) {
    return message(BoardMessage.BY_MEMBER, member, BoardMessage.POST, false);
  }

  @Test
  void a_topic_opening_wakes_every_member_but_its_author() {
    assertEquals(
        List.of(
            new Wake("researcher", Reason.OPENED),
            new Wake("spec_writer", Reason.OPENED),
            new Wake("critic", Reason.OPENED)),
        WakeRules.opened(
            message(BoardMessage.BY_OPENER, "aristoxenus", BoardMessage.POST, false), MEMBERS));
    assertEquals(
        List.of(new Wake("researcher", Reason.OPENED), new Wake("critic", Reason.OPENED)),
        WakeRules.opened(by("spec_writer"), MEMBERS));
  }

  @Test
  void a_reply_wakes_the_author_of_what_it_answers() {
    assertEquals(
        List.of(new Wake("researcher", Reason.REPLY)),
        WakeRules.after(by("critic"), by("researcher"), MEMBERS));
    assertEquals(
        List.of(new Wake(BoardSeat.OPENER, Reason.REPLY)),
        WakeRules.after(
            by("critic"),
            message(BoardMessage.BY_OPENER, "aristoxenus", BoardMessage.POST, false),
            MEMBERS));
  }

  @Test
  void a_reply_to_a_person_wakes_no_seat() {
    assertEquals(
        List.of(),
        WakeRules.after(
            by("critic"),
            message(BoardMessage.BY_PERSON, "enzo", BoardMessage.POST, false),
            MEMBERS));
  }

  @Test
  void a_mention_wakes_the_member_named_and_only_a_member() {
    assertEquals(
        List.of(new Wake("critic", Reason.MENTION)),
        WakeRules.after(
            message(
                BoardMessage.BY_MEMBER, "researcher", BoardMessage.POST, false, "critic", "enzo"),
            null,
            MEMBERS));
  }

  @Test
  void an_alert_wakes_every_member_but_its_author() {
    assertEquals(
        List.of(new Wake("spec_writer", Reason.ALERT), new Wake("critic", Reason.ALERT)),
        WakeRules.after(
            message(BoardMessage.BY_MEMBER, "researcher", BoardMessage.POST, true), null, MEMBERS));
  }

  @Test
  void a_request_wakes_the_opener() {
    assertEquals(
        List.of(new Wake(BoardSeat.OPENER, Reason.REQUEST)),
        WakeRules.after(
            message(BoardMessage.BY_MEMBER, "researcher", BoardMessage.REQUEST, false),
            null,
            MEMBERS));
  }

  @Test
  void a_message_never_wakes_its_author_and_one_seat_is_woken_once_for_its_first_reason() {
    assertEquals(
        List.of(new Wake("researcher", Reason.REPLY), new Wake("spec_writer", Reason.ALERT)),
        WakeRules.after(
            message(
                BoardMessage.BY_MEMBER, "critic", BoardMessage.POST, true, "researcher", "critic"),
            by("researcher"),
            MEMBERS));
  }
}
