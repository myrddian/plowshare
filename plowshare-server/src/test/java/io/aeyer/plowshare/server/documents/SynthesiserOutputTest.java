package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What comes back from {@code ask_synthesiser}, read.
 *
 * <p>Anchor's {@code SynthesiserOutputParserTest} in substance: the three failure modes its javadoc
 * records as observed on a real paper — the missing {@code RESPONSE:} label, the code fence, the
 * trailing echo — plus the one thing this port grounds in that Anchor has no way to express, a
 * paragraph id and the words a claim rests on.
 */
class SynthesiserOutputTest {

  private static final UUID PARAGRAPH = UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  void the_response_is_what_stands_between_the_two_markers() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                I argue that the bound is tight.

                GROUNDING:
                {"grounded_in": [], "confidence": "high"}
                """);

    assertEquals("I argue that the bound is tight.", read.response());
    assertEquals(List.of(), read.grounding());
  }

  /** Anchor's first observed failure: the model omits the label and starts. */
  @Test
  void a_response_that_never_says_response_is_still_the_response() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                I argue that the bound is tight.

                GROUNDING:
                {"grounded_in": []}
                """);

    assertEquals("I argue that the bound is tight.", read.response());
  }

  /** Anchor's third: a fence around the JSON. */
  @Test
  void a_fenced_grounding_block_is_read() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                I argue that the bound is tight.

                GROUNDING:
                ```json
                {"grounded_in": [{"paragraph": "11111111-2222-3333-4444-555555555555",
                                  "quote": "the bound is tight"}]}
                ```
                """);

    assertEquals(
        List.of(new SynthesiserOutput.Grounded(PARAGRAPH, "the bound is tight")), read.grounding());
  }

  @Test
  void an_answer_with_no_grounding_block_grounds_in_nothing_and_still_answers() {
    SynthesiserOutput read =
        SynthesiserOutput.of("RESPONSE:\nI argue that the bound is" + " tight.");

    assertEquals("I argue that the bound is tight.", read.response());
    assertEquals(List.of(), read.grounding());
    assertTrue(
        read.groundingWasUnreadable().isEmpty(),
        "a synthesiser that emitted no block at all did not emit an unreadable one");
  }

  /**
   * <b>An unreadable block is said and not swallowed.</b> Anchor returns {@code
   * Map.of("raw_output", …)} and the caller renders whatever it likes; here the same fact has to
   * reach the reader, because a deliberation whose grounding could not be read is a deliberation
   * whose claims are unchecked.
   */
  @Test
  void a_grounding_block_that_is_not_json_says_so_rather_than_reading_as_no_grounding() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                I argue that the bound is tight.

                GROUNDING:
                grounded in the introduction, mostly
                """);

    assertEquals("I argue that the bound is tight.", read.response());
    assertEquals(List.of(), read.grounding());
    assertTrue(
        read.groundingWasUnreadable().isPresent(),
        "the block was there and could not be read, which is not the same as an answer"
            + " that grounded in nothing");
  }

  @Test
  void an_entry_whose_paragraph_is_not_a_uuid_is_dropped_and_the_rest_survive() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                Two claims.

                GROUNDING:
                {"grounded_in": [
                   {"paragraph": "chunk 3", "quote": "the first one here"},
                   {"paragraph": "11111111-2222-3333-4444-555555555555",
                    "quote": "the second one here"}
                ]}
                """);

    assertEquals(
        List.of(new SynthesiserOutput.Grounded(PARAGRAPH, "the second one here")),
        read.grounding());
  }

  /**
   * <b>A quotation too short to tell one paragraph from another is not an attribution.</b>
   *
   * <p>The check {@code Deliberation} runs is a substring, so what a quotation buys is that finding
   * it where it says it is counts as evidence. A blank one is in every paragraph in the corpus and
   * {@code "."} very nearly is, so both would validate against whatever they named. Dropped and not
   * failed: an entry this short is not a wrong attribution, it is not an attribution, and reporting
   * it as a failure would fire the loudest signal this system has at a model that was terse.
   */
  @Test
  void a_quote_too_short_to_be_evidence_is_not_an_attribution() {
    for (String quote : List.of("   ", ".", "the", "the bound")) {
      SynthesiserOutput read =
          SynthesiserOutput.of(
              """
                    RESPONSE:
                    One claim.

                    GROUNDING:
                    {"grounded_in": [
                       {"paragraph": "11111111-2222-3333-4444-555555555555", "quote": "%s"}
                    ]}
                    """
                  .formatted(quote));

      assertEquals(List.of(), read.grounding(), quote);
    }
  }

  /** And three words is where one starts being one. */
  @Test
  void three_words_is_an_attribution() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                One claim.

                GROUNDING:
                {"grounded_in": [
                   {"paragraph": "11111111-2222-3333-4444-555555555555",
                    "quote": "the bound is"}
                ]}
                """);

    assertEquals(
        List.of(new SynthesiserOutput.Grounded(PARAGRAPH, "the bound is")), read.grounding());
  }

  @Test
  void nothing_at_all_reads_as_an_empty_answer_rather_than_throwing() {
    SynthesiserOutput read = SynthesiserOutput.of(null);

    assertEquals("", read.response());
    assertEquals(List.of(), read.grounding());
  }

  // --- the two challenge lists --------------------------------------------

  /**
   * The fields both prompts have always asked for and neither parser read.
   *
   * <p>Until this, a synthesiser that adjudicated its critic and one that ignored it produced
   * output no reader and no machine could tell apart.
   */
  @Test
  void the_challenges_taken_up_and_the_ones_set_aside_are_both_read() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                I argue that the bound is tight.

                GROUNDING:
                {"grounded_in": [],
                 "incorporated_critic_challenges": [1, 3],
                 "rejected_critic_challenges":
                     [{"challenge": 2, "reason": "paragraph 9 states the exception"}]}
                """);

    assertEquals(List.of(1, 3), read.incorporated());
    assertEquals(
        List.of(new SynthesiserOutput.Rejection(2, "paragraph 9 states the exception")),
        read.rejected());
  }

  /**
   * A rejection is the reason or it is nothing.
   *
   * <p>Setting a challenge aside is the one thing the synthesiser does that neither other agent can
   * — overrule a critic from evidence the critic was not given — and the reason is the whole of
   * that. What a dropped rejection becomes downstream is a challenge nothing ruled on, which is
   * what an unexplained one amounts to.
   */
  @Test
  void a_rejection_with_no_reason_is_not_a_rejection() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                I argue that the bound is tight.

                GROUNDING:
                {"grounded_in": [],
                 "rejected_critic_challenges": [{"challenge": 1, "reason": "   "},
                                                {"challenge": 2}]}
                """);

    assertEquals(List.of(), read.rejected());
  }

  /**
   * Anchor's own example is a bare number, and a model that copies it has said which challenge it
   * dismissed and nothing about why.
   *
   * <p>It reads as unruled rather than as a rejection, which is the safe direction: the challenge
   * is reported to the reader instead of being recorded as answered.
   */
  @Test
  void a_bare_number_in_the_rejected_list_rules_on_nothing() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                I argue that the bound is tight.

                GROUNDING:
                {"grounded_in": [], "rejected_critic_challenges": [1, 2]}
                """);

    assertEquals(List.of(), read.rejected());
  }

  /**
   * {@code asInt} answers 0 for a string, and 0 is not a challenge under one-based numbering — so a
   * model that wrote out the challenge in words would otherwise be recorded as having ruled on one
   * that does not exist.
   */
  @Test
  void a_challenge_named_in_words_is_not_a_number_and_rules_on_nothing() {
    SynthesiserOutput read =
        SynthesiserOutput.of(
            """
                RESPONSE:
                I argue that the bound is tight.

                GROUNDING:
                {"grounded_in": [],
                 "incorporated_critic_challenges": ["the first one", 2]}
                """);

    assertEquals(List.of(2), read.incorporated());
  }

  /**
   * An answer that emitted no block ruled on nothing, and that is not the same as an answer whose
   * lists were empty — but both read as empty here, because it is {@code Deliberation} that holds
   * them against the critic.
   */
  @Test
  void an_answer_with_no_grounding_block_rules_on_nothing() {
    SynthesiserOutput read = SynthesiserOutput.of("RESPONSE:\nThe bound is tight.");

    assertEquals(List.of(), read.incorporated());
    assertEquals(List.of(), read.rejected());
  }
}
