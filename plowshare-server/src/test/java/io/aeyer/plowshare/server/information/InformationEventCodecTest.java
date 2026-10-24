package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The information-event notice's wire contract. The notice travels inside Relay's general message,
 * so nothing but this codec establishes that every field published survives the round trip and that
 * a malformed one is refused rather than coerced.
 *
 * <p>It also pins what the notice must <i>not</i> carry. A Relay publication is readable by the
 * topic's audience and outlives the record it describes, so an owner-only field in one is a second
 * ungated read path and a deletion that does not delete. {@code detail} is the field that was, and
 * {@code the_notice_carries_no_owner_only_field} is what stops it coming back.
 */
class InformationEventCodecTest {
  private static final UUID REVISION = UUID.fromString("0f4a2d8e-7b31-4c56-9a02-5d1e8c7f3b44");

  private static InformationEventCodec.Notice notice(String documentSubtype, long generation) {
    return new InformationEventCodec.Notice(
        REVISION.toString(), generation, "derive", "ready", "document", documentSubtype, "author");
  }

  @Test
  void every_published_field_survives_the_round_trip() {
    var full = notice("markdown", 3);
    var encoded = InformationEventCodec.write(full);
    var decoded = InformationEventCodec.read(encoded);

    assertEquals(full, decoded);
    // Field by field as well as by record equality: a codec that dropped two fields and a record
    // that compared the remaining five would still satisfy the equality above.
    assertEquals(REVISION.toString(), decoded.revision());
    assertEquals(3, decoded.generation());
    assertEquals("derive", decoded.stage());
    assertEquals("ready", decoded.action());
    assertEquals("document", decoded.documentType());
    assertEquals("markdown", decoded.documentSubtype());
    assertEquals("author", decoded.owner());
    assertTrue(
        encoded.contains(InformationEventCodec.VERSION),
        "the envelope version travels with the notice");
  }

  /**
   * The pin that keeps owner-only content out of the broker. It asserts the field set rather than
   * the absence of one name, so any future field has to be argued for here, where the reason a
   * publication cannot hold narrower-than-topic content is written down.
   */
  @Test
  void the_notice_carries_no_owner_only_field() {
    assertEquals(
        List.of(
            "revision",
            "generation",
            "stage",
            "action",
            "documentType",
            "documentSubtype",
            "owner"),
        java.util.Arrays.stream(InformationEventCodec.Notice.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList(),
        "detail is owner-only and must never enter a Relay publication");
    String encoded = InformationEventCodec.write(notice("markdown", 1));
    assertFalse(encoded.contains("detail"), "the wire shape must not name detail either");
  }

  @Test
  void an_absent_subtype_round_trips_as_absent_rather_than_empty() {
    var sparse = notice(null, 1);
    var decoded = InformationEventCodec.read(InformationEventCodec.write(sparse));
    assertEquals(sparse, decoded);
    assertNull(decoded.documentSubtype(), "an unset subtype is carried as absent, never as empty");
  }

  @Test
  void boundary_lengths_and_generations_round_trip_while_the_next_value_out_is_refused() {
    var longest = notice("s".repeat(1024), Long.MAX_VALUE);
    assertEquals(longest, InformationEventCodec.read(InformationEventCodec.write(longest)));

    assertThrows(
        IllegalArgumentException.class, () -> notice("s".repeat(1025), 1), "subtype bound");
    assertThrows(IllegalArgumentException.class, () -> notice(null, 0), "generation floor");
    assertThrows(IllegalArgumentException.class, () -> notice(null, -1), "generation floor");
    for (String invalid : List.of(" padded", "control\n", "separator ", "s".repeat(1025)))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new InformationEventCodec.Notice(
                  REVISION.toString(), 1, invalid, "ready", "document", null, "author"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InformationEventCodec.Notice(
                "not-a-uuid", 1, "derive", "ready", "document", null, "author"));
  }

  @Test
  void a_notice_that_is_not_this_envelope_is_refused_rather_than_partly_read() {
    String valid = InformationEventCodec.write(notice("markdown", 2));
    for (String corrupt :
        List.of(
            valid.replace(InformationEventCodec.VERSION, "plowshare-information-event/2"),
            valid.replace("\"generation\":2", "\"generation\":2.5"),
            valid.replace("\"generation\":2", "\"generation\":\"2\""),
            valid.replace("\"owner\":\"author\"", "\"owner\":\"author\",\"extra\":1"),
            // A payload still carrying the removed field is one field too many, so a stored row
            // that V189 did not rewrite is refused rather than quietly read.
            valid.replace("\"owner\":\"author\"", "\"owner\":\"author\",\"detail\":\"private\""),
            valid.replace(",\"owner\":\"author\"", ""),
            valid.replace("\"documentType\":\"document\"", "\"documentType\":null"),
            valid + "{}",
            "[]",
            "\"text\"",
            "{}"))
      assertThrows(
          IllegalArgumentException.class,
          () -> InformationEventCodec.read(corrupt),
          () -> "accepted a corrupt notice: " + corrupt);
    assertThrows(IllegalArgumentException.class, () -> InformationEventCodec.read(""));
    assertThrows(NullPointerException.class, () -> InformationEventCodec.write(null));
  }

  @Test
  void a_refusal_never_quotes_the_notice_it_refused() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                InformationEventCodec.read(
                    InformationEventCodec.write(notice("confidential-subtype", 1))
                        .replace("\"generation\":1", "\"generation\":true")));
    assertFalse(
        refused.getMessage().contains("confidential"), "a diagnostic must not quote the payload");
  }
}
