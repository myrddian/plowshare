package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.StructuredAnswers.Choice;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Option;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Question;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Refused;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An answer to a question with options, checked against the question it answers. */
class StructuredAnswersTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Question STORE =
      new Question(
          "Store",
          "Which database?",
          false,
          List.of(new Option("Postgres", "p", null), new Option("SQLite", "s", null)));
  private static final Question CLIENTS =
      new Question(
          "Clients",
          "Which?",
          true,
          List.of(new Option("TUI", "t", null), new Option("Console", "c", null)));

  private static JsonNode json(String text) throws Exception {
    return JSON.readTree(text);
  }

  @Test
  void reads_one_choice_per_question_in_the_questions_order() throws Exception {
    List<Choice> read =
        StructuredAnswers.read(
            json(
                """
                [{"header": "Clients", "chosen": ["TUI", "Console"]},
                 {"header": "Store", "chosen": ["SQLite"], "note": "for now"}]"""),
            List.of(STORE, CLIENTS));

    assertEquals(
        List.of(
            new Choice("Store", List.of("SQLite"), null, "for now"),
            new Choice("Clients", List.of("TUI", "Console"), null, null)),
        read);
  }

  @Test
  void other_alone_answers_a_question() throws Exception {
    List<Choice> read =
        StructuredAnswers.read(
            json(
                """
                [{"header": "Store", "other": "whatever the team already runs"}]"""),
            List.of(STORE));

    assertEquals(
        List.of(new Choice("Store", List.of(), "whatever the team already runs", null)), read);
  }

  @Test
  void refuses_an_unknown_label_an_unknown_header_and_a_question_left_out() {
    Refused label =
        assertThrows(
            Refused.class,
            () ->
                StructuredAnswers.read(
                    json(
                        """
                [{"header": "Store", "chosen": ["MySQL"]}]"""),
                    List.of(STORE)));
    assertEquals(
        "'Store' has no option 'MySQL'; its options are 'Postgres', 'SQLite'.", label.getMessage());

    Refused header =
        assertThrows(
            Refused.class,
            () ->
                StructuredAnswers.read(
                    json(
                        """
                [{"header": "Cache", "chosen": ["Postgres"]}]"""),
                    List.of(STORE)));
    assertEquals(
        "choice 1 answers 'Cache', which is not one of the questions: 'Store'.",
        header.getMessage());

    Refused missing =
        assertThrows(
            Refused.class,
            () ->
                StructuredAnswers.read(
                    json(
                        """
                [{"header": "Store", "chosen": ["Postgres"]}]"""),
                    List.of(STORE, CLIENTS)));
    assertEquals(
        "'Clients' is not answered; every question needs a choice or words of its own.",
        missing.getMessage());
  }

  @Test
  void a_single_choice_question_takes_one_choice_or_words_not_both() {
    Refused two =
        assertThrows(
            Refused.class,
            () ->
                StructuredAnswers.read(
                    json(
                        """
                [{"header": "Store", "chosen": ["Postgres", "SQLite"]}]"""),
                    List.of(STORE)));
    assertEquals("'Store' takes one choice; it was given 2.", two.getMessage());

    Refused both =
        assertThrows(
            Refused.class,
            () ->
                StructuredAnswers.read(
                    json(
                        """
                [{"header": "Store", "chosen": ["Postgres"], "other": "or SQLite"}]"""),
                    List.of(STORE)));
    assertEquals("'Store' takes one choice or words of its own, not both.", both.getMessage());

    Refused empty =
        assertThrows(
            Refused.class,
            () ->
                StructuredAnswers.read(
                    json(
                        """
                [{"header": "Store", "chosen": []}]"""),
                    List.of(STORE)));
    assertEquals("'Store' is not answered; choose an option or give 'other'.", empty.getMessage());
  }

  @Test
  void renders_what_the_conductor_is_spoken() {
    String text =
        StructuredAnswers.render(
            List.of(
                new Choice("Store", List.of("SQLite"), null, "for now"),
                new Choice("Clients", List.of("TUI", "Console"), "and the CLI later", null),
                new Choice("Name", List.of(), "triage_bugs", null)),
            "Thanks.");

    assertEquals(
        """
                1. [Store] chose "SQLite" (note: for now)
                2. [Clients] chose "TUI", "Console" and added: and the CLI later
                3. [Name] answered in words: triage_bugs
                Also: Thanks.""",
        text);
  }

  @Test
  void the_stored_structure_names_each_choice() throws Exception {
    var stored =
        StructuredAnswers.structure(
            List.of(new Choice("Store", List.of("SQLite"), null, "for now")));

    assertEquals(
        json(
            "{\"choices\":[{\"header\":\"Store\",\"chosen\":[\"SQLite\"],"
                + "\"note\":\"for now\"}]}"),
        new ObjectMapper().valueToTree(stored));
  }

  @Test
  void direct_choices_cannot_bypass_the_boundary_field_checks() {
    assertThrows(Refused.class, () -> new Choice("heading\n", List.of("x"), null, null));
    assertThrows(Refused.class, () -> new Choice("heading", List.of("x", "x"), null, null));
    assertThrows(Refused.class, () -> new Choice("heading", List.of("x"), "bad\0text", null));
    assertThrows(Refused.class, () -> new Choice("heading", List.of("x"), null, "bad\0text"));
  }
}
