package io.aeyer.plowshare.protocol.frames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What survives the wire for {@link Envelope} and {@link Outcome}, and what {@link Code} actually
 * covers — asked together because all three are one task: the shape both sides of the socket agree
 * on.
 *
 * <h2>The mapper is built here to match a channel's, and that is a duplication with a reason</h2>
 *
 * <p>{@code FileFramesTest} explains it and this class copies the arrangement: this module keeps
 * Jackson databind off its main classpath on purpose, so it cannot import the mapper a real channel
 * will configure, and testing through a fresh default mapper instead would risk a field that binds
 * under a default configuration and not under the one the wire actually uses.
 */
class EnvelopeTest {

  private static ObjectMapper wire() {
    return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  @Test
  void an_envelope_round_trips_with_protocol_version_in_snake_case() throws Exception {
    Envelope sent =
        new Envelope("r1", "conversation.turns", Envelope.CURRENT_VERSION, Map.of("k", "v"));

    String json = wire().writeValueAsString(sent);
    assertTrue(
        json.contains("\"protocol_version\""),
        "the wire spells it snake_case even though the record field does not: " + json);
    assertFalse(json.contains("protocolVersion"), json);

    Envelope bound = wire().readValue(json, Envelope.class);
    assertEquals("r1", bound.id());
    assertEquals("conversation.turns", bound.type());
    assertEquals(Envelope.CURRENT_VERSION, bound.protocolVersion());
    assertEquals(Map.of("k", "v"), bound.payload());
  }

  @Test
  void a_push_carries_no_id_and_the_key_is_absent_rather_than_null() throws Exception {
    Envelope push = new Envelope(null, "job.event", Envelope.CURRENT_VERSION, null);

    String json = wire().writeValueAsString(push);
    assertFalse(
        json.contains("\"id\""),
        "a push answers no request and correlates with"
            + " nothing, so the key is missing rather than null: "
            + json);

    Envelope bound = wire().readValue(json, Envelope.class);
    assertNull(bound.id());
  }

  @Test
  void a_frame_naming_a_different_version_is_refused_by_the_constructor() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Envelope("r1", "conversation.turns", "plowshare-v2", null));

    assertTrue(
        thrown.getMessage().contains(Envelope.CURRENT_VERSION),
        "the refusal names the version this build speaks: " + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains("plowshare-v2"),
        "and the version the frame proposed instead: " + thrown.getMessage());
  }

  @Test
  void a_frame_naming_a_different_version_fails_to_bind_at_all() {
    String json =
        "{\"id\":\"r1\",\"type\":\"conversation.turns\","
            + "\"protocol_version\":\"plowshare-v2\"}";

    ValueInstantiationException thrown =
        assertThrows(
            ValueInstantiationException.class, () -> wire().readValue(json, Envelope.class));

    assertTrue(
        thrown.getCause() instanceof IllegalArgumentException,
        "the wire's rejection is this record's own invariant, not a new one: " + thrown);
  }

  @Test
  void a_frame_with_no_type_is_refused_by_the_constructor() {
    assertThrows(
        NullPointerException.class, () -> new Envelope("r1", null, Envelope.CURRENT_VERSION, null));
  }

  @Test
  void an_outcome_with_a_null_said_serialises_with_the_key_absent() throws Exception {
    Outcome outcome = Outcome.ok(Map.of("turns", 3));

    String json = wire().writeValueAsString(outcome);
    assertFalse(
        json.contains("\"said\""),
        "the server said nothing, so the key is missing rather than null: " + json);
    assertTrue(json.contains("\"code\""), json);

    Outcome bound = wire().readValue(json, Outcome.class);
    assertEquals(Code.OK, bound.code());
    assertNull(bound.said());
    assertEquals(Map.of("turns", 3), bound.payload());
  }

  @Test
  void an_outcome_with_a_sentence_carries_it_and_nothing_it_did_not_say() throws Exception {
    Outcome outcome = Outcome.failed(Code.CONFLICT, "the conversation's budget is spent");

    String json = wire().writeValueAsString(outcome);
    assertTrue(json.contains("the conversation's budget is spent"), json);
    assertFalse(
        json.contains("\"payload\""),
        "a failure carries no payload here, and the key is missing rather than null: " + json);

    Outcome bound = wire().readValue(json, Outcome.class);
    assertEquals(Code.CONFLICT, bound.code());
    assertEquals("the conversation's budget is spent", bound.said());
    assertNull(bound.payload());
  }

  @Test
  void an_outcome_with_no_code_is_not_an_answer_to_anything() {
    assertThrows(NullPointerException.class, () -> new Outcome(null, null, null));
  }

  /**
   * {@code ApiExceptionHandler}'s table, restated as the {@code (status, slug)} pairs it produces —
   * the same derivation {@link Code}'s own javadoc argues, checked here against the actual enum
   * rather than trusted from prose. Row 10 contributes three pairs sharing one slug; every other
   * row collapses onto a pair some other row already contributed.
   */
  private static Set<Map.Entry<Integer, String>> tableRows() {
    Set<Map.Entry<Integer, String>> rows = new HashSet<>();
    rows.add(Map.entry(400, "bad_request")); // rows 1, 2
    rows.add(Map.entry(404, "not_found")); // rows 3, 5
    rows.add(Map.entry(409, "conflict")); // rows 4, 6, 7, 8
    rows.add(Map.entry(415, "unsupported_document")); // row 9
    rows.add(Map.entry(400, "image_not_stored")); // row 10, EMPTY
    rows.add(Map.entry(415, "image_not_stored")); // row 10, UNRECOGNISED
    rows.add(Map.entry(413, "image_not_stored")); // row 10, TOO_LARGE
    rows.add(Map.entry(422, "validation_failed")); // row 11
    rows.add(Map.entry(503, "archive_unavailable")); // row 12
    rows.add(Map.entry(503, "embedding_unavailable")); // row 13
    rows.add(Map.entry(503, "config_unavailable")); // row 14
    rows.add(Map.entry(503, "model_unavailable")); // row 14a, schedule.read
    rows.add(Map.entry(500, "internal_error")); // row 15
    return rows;
  }

  @Test
  void code_covers_every_distinct_status_in_the_survey_table_plus_ok() {
    Set<Map.Entry<Integer, String>> covered = new HashSet<>();
    for (Code code : Code.values()) {
      if (SUCCESSES.contains(code)) {
        continue;
      }
      covered.add(Map.entry(code.httpStatus(), code.slug()));
    }

    assertEquals(
        tableRows(),
        covered,
        "every distinct (status, slug) pair the table produces has exactly one"
            + " Code constant, and no constant answers a pair the table never"
            + " produced");
    assertEquals(
        tableRows().size() + SUCCESSES.size(),
        Code.values().length,
        "the table's distinct pairs, plus the successes it could never name,"
            + " and nothing else");
  }

  @Test
  void ok_carries_no_slug() {
    assertNull(
        Code.OK.slug(), "a slug is a word for a failure a caller acts on, and a success has none");
    assertEquals(200, Code.OK.httpStatus());
  }

  @Test
  void every_code_but_a_success_carries_a_slug() {
    for (Code code : EnumSet.complementOf(EnumSet.copyOf(SUCCESSES))) {
      assertTrue(
          code.slug() != null && !code.slug().isEmpty(),
          code + " answers a failure and must name it");
    }
    for (Code code : SUCCESSES) {
      assertNull(
          code.slug(),
          code
              + " succeeded, so it has no failure to name -- a slug here would"
              + " train a client to switch on one");
    }
  }

  /**
   * The successes, which are not rows in any table.
   *
   * <p>{@code ApiExceptionHandler}'s table enumerates failures, so this enum was first derived
   * without any success but {@link Code#OK} -- while six endpoints across four controllers answer
   * 202 and two answer 201. The two assertions above are written against this set rather than
   * against {@link Code#OK} alone so that adding a fourth success is one edit here, not a puzzle
   * about why two unrelated tests went red.
   */
  private static final Set<Code> SUCCESSES =
      Set.of(Code.OK, Code.ACCEPTED, Code.NO_CONTENT, Code.CREATED);
}
