package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The wire shape of a fired event, pinned at its boundaries.
 *
 * <p>No database: the codec is a pure function and the round trip is the whole contract. What the
 * publication then does with a decoded notice is pinned in {@link RelayEventPublicationTest} and
 * {@code RelayEventPublicationDatabaseTest}.
 */
class FiredEventCodecTest {

  /** The longest text {@code EventPayload.Text} admits, and the shortest. */
  private static final int LONGEST_TEXT = 1048576;

  private static FiredEventCodec.Notice through(FiredEventCodec.Notice notice) {
    return FiredEventCodec.read(FiredEventCodec.write(notice));
  }

  @Test
  void an_empty_payload_round_trips_as_an_empty_payload() {
    var notice = new FiredEventCodec.Notice("daily", new EventPayload.Empty());
    assertEquals(notice, through(notice));
  }

  @Test
  void text_round_trips_verbatim() {
    var notice = new FiredEventCodec.Notice("daily", new EventPayload.Text("x=1"));
    assertEquals(notice, through(notice));
  }

  /**
   * The point of the null rather than an omission: an empty payload and a one-character text are
   * different notices, and neither may decode as the other.
   */
  @Test
  void an_empty_payload_is_not_the_empty_string_and_the_two_do_not_collapse() {
    var empty = new FiredEventCodec.Notice("daily", new EventPayload.Empty());
    var shortest = new FiredEventCodec.Notice("daily", new EventPayload.Text("x"));
    assertEquals(empty, through(empty));
    assertEquals(shortest, through(shortest));
    assertNotEquals(through(empty), through(shortest));
    assertTrue(FiredEventCodec.write(empty).contains("\"text\":null"));
  }

  @Test
  void the_longest_admissible_text_round_trips_and_one_character_more_is_not_a_payload() {
    String longest = "x".repeat(LONGEST_TEXT);
    var notice = new FiredEventCodec.Notice("daily", new EventPayload.Text(longest));
    assertEquals(notice, through(notice));
    // Refused by the family rather than by the codec, which is the point: the codec cannot admit a
    // payload the firing inbox would reject.
    assertThrows(
        IllegalArgumentException.class, () -> new EventPayload.Text("x".repeat(LONGEST_TEXT + 1)));
  }

  /** Multi-byte text, because the parser's allowance is in bytes and the family's is in chars. */
  @Test
  void text_whose_characters_are_several_bytes_each_round_trips() {
    var notice = new FiredEventCodec.Notice("daily", new EventPayload.Text("ごはん 🍚 café"));
    assertEquals(notice, through(notice));
  }

  @Test
  void the_longest_admissible_event_name_round_trips_and_one_character_more_is_not_a_name() {
    var notice = new FiredEventCodec.Notice("e".repeat(1024), new EventPayload.Empty());
    assertEquals(notice, through(notice));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FiredEventCodec.Notice("e".repeat(1025), new EventPayload.Empty()));
  }

  /**
   * Every state an unusable event name can be in, not only the one the implementation happens to
   * check first.
   */
  @Test
  void an_event_name_that_is_not_a_bounded_identity_is_no_notice() {
    for (String name :
        new String[] {
          null,
          "",
          "   ",
          " padded",
          "padded ",
          "new\nline",
          "tab\there",
          "nul\0byte",
          "line separator",
          "paragraph separator",
          "e".repeat(1025)
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> new FiredEventCodec.Notice(name, new EventPayload.Empty()),
          "accepted as an event name: " + name);
  }

  /**
   * Only the two manual families may be carried. A wake or a scheduled payload reaching this codec
   * would mean something other than {@code event.fire} had published on the topic.
   */
  @Test
  void every_payload_family_outside_the_two_manual_ones_is_no_notice() {
    for (EventPayload outside :
        new EventPayload[] {
          null,
          new EventPayload.Scheduled("tick", Instant.parse("2026-10-05T01:00:00Z")),
          new EventPayload.Seat(
              new io.aeyer.plowshare.server.board.SeatWake("opened", "hello", "enzo", 1)),
          new EventPayload.Message(
              new io.aeyer.plowshare.server.board.MessageWake("msg_1", null, null, null, null))
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> new FiredEventCodec.Notice("daily", outside),
          "accepted as a fired payload: " + outside);
  }

  /**
   * Every malformed envelope, each named so a regression says which shape stopped being refused.
   */
  @Test
  void a_payload_that_is_not_this_envelope_does_not_decode() {
    String valid =
        FiredEventCodec.write(new FiredEventCodec.Notice("daily", new EventPayload.Text("x=1")));
    assertEquals(
        new FiredEventCodec.Notice("daily", new EventPayload.Text("x=1")),
        FiredEventCodec.read(valid));
    for (String corrupt :
        new String[] {
          // Not an object, or not JSON at all.
          "\"daily\"",
          "[]",
          "17",
          "null",
          "not json",
          // The right fields, no version.
          "{\"event\":\"daily\",\"text\":\"x=1\"}",
          // A version this codec does not own.
          "{\"schema\":\"plowshare-event-fired/2\",\"event\":\"daily\",\"text\":\"x=1\"}",
          "{\"schema\":\"plowshare-information-event/1\",\"event\":\"daily\",\"text\":\"x=1\"}",
          // A field outside the allowlist, with the count made up so size alone does not refuse it.
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\",\"privilege\":\"admin\"}",
          // One field too many.
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\",\"text\":\"x=1\",\"x\":1}",
          // The text key omitted rather than null, which is what makes an absent value corrupt.
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\"}",
          // The event key omitted.
          "{\"schema\":\"plowshare-event-fired/1\",\"text\":\"x=1\",\"other\":null}",
          // Right keys, wrong types.
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":17,\"text\":\"x=1\"}",
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\",\"text\":17}",
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\",\"text\":{}}",
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":null,\"text\":\"x=1\"}",
          // A text the family refuses, carried on an otherwise well-formed envelope.
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\",\"text\":\"\"}",
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\",\"text\":\"  \"}",
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\" padded\",\"text\":\"x=1\"}",
          // A duplicate key, which a lenient parser would silently resolve to one of them.
          "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\",\"event\":\"other\","
              + "\"text\":\"x=1\"}",
          // Trailing content after a valid object.
          valid + "{}",
          valid + " trailing"
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> FiredEventCodec.read(corrupt),
          "decoded as a notice: " + corrupt);
  }

  @Test
  void nothing_at_all_does_not_decode() {
    for (String absent : new String[] {null, "", "   "})
      assertThrows(IllegalArgumentException.class, () -> FiredEventCodec.read(absent));
  }

  /**
   * The refusal says nothing about what it rejected. A diagnostic that quoted the payload would
   * disclose a publication's content to whoever provoked the failure.
   */
  @Test
  void a_refusal_does_not_quote_the_payload_it_rejected() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                FiredEventCodec.read(
                    "{\"schema\":\"plowshare-event-fired/1\",\"event\":\"daily\","
                        + "\"text\":\"a-secret-nobody-should-see\"}"
                        + "trailing"));
    assertEquals("invalid fired event notice", thrown.getMessage());
  }
}
