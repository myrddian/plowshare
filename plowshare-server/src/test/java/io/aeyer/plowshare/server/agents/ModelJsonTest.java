package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The one reader of model-emitted JSON, tested by name.
 *
 * <p>Every branch here is also exercised through {@code ScribeTest} and {@code
 * CuratorTest}, which is what an extraction ought to inherit. This class exists
 * anyway, and the reason is the shape of the risk rather than the coverage
 * number: the two callers frame these details into <em>their own</em> sentences
 * — "the scribe's answer could not be read (…)" and "its ruling could not be
 * read: …" — so a test through either of them passes on a sentence its caller
 * wrote, and neither can say what this class promises on its own. A third caller
 * will read this file to find out.
 */
class ModelJsonTest {

    @Test
    void shared_recovery_reads_enrichment_formatting_and_preserves_lone_backslashes() {
        assertEquals("keep", ModelJson.object("{'decision':'keep',}").path("decision").asText());
        JsonNode read = ModelJson.object("{\n\"decision\":\"keep\"\n\"reason\":\"the \\epsilon bound\"\n}");
        assertEquals("keep", read.path("decision").asText());
        assertEquals("the \\epsilon bound", read.path("reason").asText());
    }

    @Test
    void multiple_objects_are_not_silently_reduced_to_the_first_judgment() {
        assertThrows(ModelJson.Unreadable.class,
                () -> ModelJson.object("{\"decision\":\"keep\"} {\"decision\":\"drop\"}"));
    }

    /** A model that stops on its first token has said nothing, which is a
     *  different fact from having said something unparseable. */
    @Test
    void nothing_at_all_says_so() {
        assertEquals("it said nothing",
                assertThrows(ModelJson.Unreadable.class, () -> ModelJson.object("")).getMessage());
    }

    /**
     * Null is the same fact as empty and not a {@link NullPointerException}.
     *
     * <p>{@code Completion} documents its content as never null and {@code
     * OpenAiTransport} reads it with {@code asText("")}, so the shipped path
     * cannot produce one — but the record does not enforce it, and an NPE out of
     * here would be thrown through a write rather than filed. {@code JobRuntime}
     * coalesces the same field for the same reason.
     */
    @Test
    void a_null_answer_is_the_same_as_an_empty_one() {
        assertEquals("it said nothing",
                assertThrows(ModelJson.Unreadable.class,
                        () -> ModelJson.object(null)).getMessage());
    }

    /** Whitespace only. Stripped first, so it reaches the same branch as empty
     *  rather than looking for a brace in a blank string. */
    @Test
    void whitespace_only_is_the_same_as_an_empty_one() {
        assertEquals("it said nothing",
                assertThrows(ModelJson.Unreadable.class,
                        () -> ModelJson.object("   \n  ")).getMessage());
    }

    @Test
    void prose_with_no_object_in_it_says_so() {
        assertEquals("there is no JSON object in it",
                assertThrows(ModelJson.Unreadable.class,
                        () -> ModelJson.object("I think probably yes")).getMessage());
    }

    /** A closing brace before any opening one is not a span. The same sentence
     *  as no braces at all, deliberately: from where the model stands there is
     *  no object either way. */
    @Test
    void a_closing_brace_before_an_opening_one_is_not_a_span() {
        assertEquals("there is no JSON object in it",
                assertThrows(ModelJson.Unreadable.class,
                        () -> ModelJson.object("} nonsense {")).getMessage());
    }

    /** Braces around something Jackson cannot read is the one failure that
     *  reaches the parser, and it is a different sentence from the two above —
     *  a caller that could not tell them apart could not tell "it wrote no JSON"
     *  from "it wrote broken JSON", which have different fixes. */
    @Test
    void braces_around_something_that_is_not_json_says_so() {
        assertEquals("it is not valid JSON",
                assertThrows(ModelJson.Unreadable.class,
                        () -> ModelJson.object("{verdict: probably new}")).getMessage());
    }

    @Test
    void a_bare_object_is_read() {
        JsonNode read = ModelJson.object("{\"decision\": \"keep\"}");

        assertEquals("keep", read.path("decision").asText());
    }

    /** A small model wraps JSON in a fence more often than not, and refusing
     *  that would make every answer unreadable for a formatting habit. */
    @Test
    void an_object_inside_a_code_fence_is_read() {
        JsonNode read = ModelJson.object(
                "Here you go:\n```json\n{\"decision\": \"promote\"}\n```\nHope that helps.");

        assertEquals("promote", read.path("decision").asText());
    }

    /**
     * The span runs to the LAST closing brace, not the first.
     *
     * <p>Nothing either caller sends today nests an object, so {@code indexOf}
     * would pass every test in both of their files — this is the one that would
     * not. It is here because the span is the only interesting decision this
     * class makes, and a nested payload is what a third caller is most likely to
     * bring.
     */
    @Test
    void a_nested_object_is_read_whole_rather_than_truncated_at_the_first_brace() {
        JsonNode read = ModelJson.object("{\"a\": {\"b\": 1}, \"c\": 2}");

        assertEquals(1, read.path("a").path("b").asInt());
        assertEquals(2, read.path("c").asInt());
    }

    /** A non-object between braces is not branched on — the substring starts at
     *  a brace, so Jackson answers or throws. What must not happen is a silent
     *  null a caller dereferences. */
    @Test
    void what_comes_back_is_never_null() {
        assertTrue(ModelJson.object("prefix {\"a\": 1} suffix").isObject());
    }

    // --- a model writing mathematics -------------------------------------------

    /**
     * <b>Every LaTeX command is an invalid JSON escape</b>, and an agent asked
     * to describe mathematics inside a JSON string writes them.
     *
     * <p>Measured on a corpus of arXiv papers: two of the first five
     * deliberations lost their critic entirely to this, and both of the
     * challenges lost were correct. The parse stopped at {@code \e} and the one
     * retry failed identically, because the same mathematics was still there to
     * describe on the second attempt.
     */
    @Test
    void a_latex_command_in_a_string_does_not_take_the_whole_answer_down() {
        JsonNode read = ModelJson.object(
                "{\"challenges\": [\"the bound $(1-\\epsilon)(2s+t)$ is not what the"
                        + " summaries say\"], \"challenges_count\": 1}");

        assertEquals(1, read.path("challenges_count").asInt());
        assertTrue(read.path("challenges").get(0).asText().contains("\\epsilon"),
                "the backslash is what the model meant and it survives: "
                        + read.path("challenges").get(0).asText());
    }

    /** Several of them, which is what a paper about inequalities produces. */
    @Test
    void more_than_one_lone_backslash_is_repaired() {
        JsonNode read = ModelJson.object(
                "{\"a\": \"$\\epsilon$ and $\\ge$ and $\\log\\log(s+t)$\"}");

        assertEquals("$\\epsilon$ and $\\ge$ and $\\log\\log(s+t)$", read.path("a").asText());
    }

    /**
     * <b>A backslash that was already escaped is not escaped twice.</b>
     *
     * <p>The repair runs only after the strict parse has failed, so well-formed
     * JSON never reaches it — but a document holding both a real escape and a
     * lone one does, and doubling the first would change text the model got
     * right.
     */
    @Test
    void an_escape_that_was_already_correct_is_left_alone() {
        JsonNode read = ModelJson.object(
                "{\"a\": \"a real backslash \\\\ then a line\\nbreak then \\epsilon\"}");

        assertEquals("a real backslash \\ then a line\nbreak then \\epsilon",
                read.path("a").asText());
    }

    /** And a four-hex-digit u escape is a real escape, not a lone backslash. */
    @Test
    void a_unicode_escape_survives_the_repair() {
        JsonNode read = ModelJson.object(
                "{\"a\": \"\\u03b5 is epsilon, \\epsilon is not\"}");

        assertEquals("\u03b5 is epsilon, \\epsilon is not", read.path("a").asText());
    }

    /** The repair is not a licence to accept anything: a truncated object is
     *  still unreadable, and the caller still gets told which. */
    @Test
    void a_broken_object_is_still_refused_after_the_repair() {
        assertThrows(ModelJson.Unreadable.class,
                () -> ModelJson.object("{\"challenges\": [\"unterminated"));
    }
}
