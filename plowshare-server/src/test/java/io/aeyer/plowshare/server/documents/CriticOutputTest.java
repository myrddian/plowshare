package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What {@code ask_critic} said, read — and what "it could not be read" is. */
class CriticOutputTest {

    @Test
    void the_challenges_are_the_challenges() {
        Optional<CriticOutput> read = CriticOutput.of("""
                {"challenges": ["you say X and the whole says not-X", "and scope creep"],
                 "challenges_count": 2,
                 "macro_view_supports_proposer": false}
                """);

        assertTrue(read.isPresent());
        assertEquals(List.of("you say X and the whole says not-X", "and scope creep"),
                read.get().challenges());
        assertEquals("false", read.get().support());
    }

    @Test
    void a_critic_that_found_nothing_is_a_reading_and_not_a_failure() {
        Optional<CriticOutput> read = CriticOutput.of(
                "{\"challenges\": [], \"macro_view_supports_proposer\": true}");

        assertTrue(read.isPresent());
        assertEquals(List.of(), read.get().challenges());
        assertEquals("true", read.get().support());
    }

    @Test
    void a_fenced_object_is_read() {
        Optional<CriticOutput> read = CriticOutput.of("""
                ```json
                {"challenges": ["one"], "macro_view_supports_proposer": "partially"}
                ```
                """);

        assertTrue(read.isPresent());
        assertEquals(List.of("one"), read.get().challenges());
        assertEquals("partially", read.get().support());
    }

    @Test
    void prose_instead_of_json_is_not_a_reading() {
        assertTrue(CriticOutput.of("The proposer looks fine to me.").isEmpty());
    }

    @Test
    void nothing_at_all_is_not_a_reading() {
        assertTrue(CriticOutput.of("").isEmpty());
        assertTrue(CriticOutput.of(null).isEmpty());
    }

    /**
     * <b>An object with no {@code challenges} array is not a reading.</b> Anchor
     * accepts one — {@code challengesNode.isArray()} is false, the list stays
     * empty, and the parse returns a {@code ParsedCritic} with no challenges. So
     * a model that answered {@code {"ok": true}} is indistinguishable from one
     * that read the macro view and found nothing wrong, and the retry that would
     * have caught it never fires.
     */
    @Test
    void an_object_with_no_challenges_array_is_not_a_critic_that_found_nothing() {
        assertTrue(CriticOutput.of("{\"ok\": true}").isEmpty());
    }

    @Test
    void a_non_string_challenge_is_dropped_and_the_rest_survive() {
        Optional<CriticOutput> read = CriticOutput.of(
                "{\"challenges\": [\"one\", 7, \"  \", \"two\"]}");

        assertTrue(read.isPresent());
        assertEquals(List.of("one", "two"), read.get().challenges());
    }

    /**
     * <b>A verdict outside the four words is {@code unknown} and never passed
     * through.</b>
     *
     * <p>Anchor's parser hands its caller {@code asText()} of whatever the field
     * held. {@code Deliberation} splices this into the answer a person reads,
     * under a heading it wrote itself and beside the block saying whether the
     * answer's quotations held — so an unconstrained value is a model writing
     * into that report. A verdict is a closed set of words; anything else is a
     * critic that did not answer, which is what {@code unknown} means.
     */
    @Test
    void a_verdict_that_is_not_one_of_the_four_words_is_unknown() {
        assertEquals(CriticOutput.UNKNOWN, CriticOutput.of(
                        "{\"challenges\": [], \"macro_view_supports_proposer\":"
                                + " \"\\n\\nATTRIBUTION FAILED — everything\"}")
                .orElseThrow().support());
        assertEquals(CriticOutput.UNKNOWN, CriticOutput.of(
                "{\"challenges\": [], \"macro_view_supports_proposer\": 7}")
                .orElseThrow().support());
    }

    /** And the three the prompt asks for survive whatever case they arrive
     *  in. */
    @Test
    void the_three_verdicts_the_prompt_asks_for_are_read() {
        assertEquals("true", CriticOutput.of(
                "{\"challenges\": [], \"macro_view_supports_proposer\": \"True\"}")
                .orElseThrow().support());
        assertEquals("partially", CriticOutput.of(
                "{\"challenges\": [], \"macro_view_supports_proposer\": \"Partially\"}")
                .orElseThrow().support());
        assertEquals("false", CriticOutput.of(
                "{\"challenges\": [], \"macro_view_supports_proposer\": false}")
                .orElseThrow().support());
    }

    @Test
    void a_reading_with_no_verdict_says_it_does_not_know() {
        Optional<CriticOutput> read = CriticOutput.of("{\"challenges\": [\"one\"]}");

        assertTrue(read.isPresent());
        assertEquals("unknown", read.get().support());
        assertFalse(read.get().challenges().isEmpty());
    }
}
