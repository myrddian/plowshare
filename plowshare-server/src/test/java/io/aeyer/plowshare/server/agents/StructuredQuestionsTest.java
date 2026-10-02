package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Asked;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Option;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Question;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Refused;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A question with options — spec 2026-09-29-orchestration-studio §2.1 — read from what a model
 * sent, rendered to the text every reader that does not know the shape reads, and stored.
 */
class StructuredQuestionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static final String STORE = """
            [{"header": "Store", "question": "Which database?", "options": [
              {"label": "Postgres", "description": "The one we run."},
              {"label": "SQLite", "description": "A file.", "preview": "db.sqlite"}]}]""";

    @Test
    void reads_a_question_with_its_options_in_order() throws Exception {
        List<Question> read = StructuredQuestions.read(json(STORE));

        assertEquals(List.of(new Question("Store", "Which database?", false, List.of(
                new Option("Postgres", "The one we run.", null),
                new Option("SQLite", "A file.", "db.sqlite")))), read);
    }

    @Test
    void multi_is_read_and_defaults_to_false() throws Exception {
        List<Question> read = StructuredQuestions.read(json("""
                [{"header": "Clients", "question": "Which?", "multi": true, "options": [
                  {"label": "TUI", "description": "t"}, {"label": "Console", "description": "c"}]}]"""));

        assertTrue(read.get(0).multi());
    }

    @Test
    void refuses_no_questions_and_more_than_four() {
        Refused none = assertThrows(Refused.class, () -> StructuredQuestions.read(json("[]")));
        assertEquals("'questions', when present, is a list of 1 to 4 questions; it had 0.",
                none.getMessage());
        String five = "[" + String.join(",", java.util.Collections.nCopies(5, """
                {"header": "H", "question": "Q?", "options": [
                  {"label": "a", "description": "a"}, {"label": "b", "description": "b"}]}""")) + "]";
        Refused many = assertThrows(Refused.class, () -> StructuredQuestions.read(json(five)));
        assertEquals("'questions', when present, is a list of 1 to 4 questions; it had 5.",
                many.getMessage());
    }

    @Test
    void refuses_one_option_and_five() {
        Refused one = assertThrows(Refused.class, () -> StructuredQuestions.read(json("""
                [{"header": "H", "question": "Q?", "options": [{"label": "a", "description": "a"}]}]""")));
        assertEquals("question 1 needs 2 to 4 options; it had 1.", one.getMessage());
    }

    @Test
    void refuses_a_long_header_a_duplicate_label_and_a_duplicate_header() {
        Refused header = assertThrows(Refused.class, () -> StructuredQuestions.read(json("""
                [{"header": "Thirteen chars", "question": "Q?", "options": [
                  {"label": "a", "description": "a"}, {"label": "b", "description": "b"}]}]""")));
        assertEquals("question 1's 'header' is 14 characters; at most 12.", header.getMessage());

        Refused label = assertThrows(Refused.class, () -> StructuredQuestions.read(json("""
                [{"header": "H", "question": "Q?", "options": [
                  {"label": "a", "description": "a"}, {"label": "a", "description": "b"}]}]""")));
        assertEquals("question 1 has the label 'a' twice; an answer names an option by its label.",
                label.getMessage());

        Refused twice = assertThrows(Refused.class, () -> StructuredQuestions.read(json("""
                [{"header": "H", "question": "Q?", "options": [
                  {"label": "a", "description": "a"}, {"label": "b", "description": "b"}]},
                 {"header": "H", "question": "R?", "options": [
                  {"label": "a", "description": "a"}, {"label": "b", "description": "b"}]}]""")));
        assertEquals("question 2's header 'H' is already another question's; each header names one"
                + " question.", twice.getMessage());
    }

    @Test
    void refuses_a_missing_description_and_an_oversized_preview() {
        Refused description = assertThrows(Refused.class, () -> StructuredQuestions.read(json("""
                [{"header": "H", "question": "Q?", "options": [
                  {"label": "a"}, {"label": "b", "description": "b"}]}]""")));
        assertEquals("question 1, option 1 needs 'description' as text.", description.getMessage());

        String big = "x".repeat(StructuredQuestions.MOST_PREVIEW + 1);
        Refused preview = assertThrows(Refused.class, () -> StructuredQuestions.read(json("""
                [{"header": "H", "question": "Q?", "options": [
                  {"label": "a", "description": "a", "preview": "%s"},
                  {"label": "b", "description": "b"}]}]""".formatted(big))));
        assertEquals("question 1, option 1's preview is 4097 characters; at most 4096.",
                preview.getMessage());
    }

    @Test
    void renders_the_lead_then_each_question_with_its_options_and_how_to_answer() throws Exception {
        String text = StructuredQuestions.render("Two things first.",
                StructuredQuestions.read(json(STORE)));

        assertEquals("""
                Two things first.
                1. [Store] Which database?
                   - Postgres — The one we run.
                   - SQLite — A file.
                   (choose one, or answer in words)""", text);
    }

    @Test
    void a_structure_round_trips_and_ignores_fields_it_does_not_know() throws Exception {
        Asked asked = new Asked("Two things first.", StructuredQuestions.read(json(STORE)));

        String stored = StructuredQuestions.structure(asked);
        assertEquals(asked, StructuredQuestions.parse(stored));

        String withMore = stored.substring(0, stored.length() - 1) + ",\"sha256\":\"sha256:ab\"}";
        assertEquals(asked, StructuredQuestions.parse(withMore));
        assertNull(asked.questions().get(0).options().get(0).preview());
    }

    @Test
    void an_unreadable_stored_structure_is_an_illegal_state() {
        assertThrows(IllegalStateException.class, () -> StructuredQuestions.parse("{\"lead\":1}"));
    }
}
