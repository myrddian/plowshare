package io.aeyer.plowshare.integrations.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Executes the application workflow in the real script sandbox, without models or a database. */
class ProductResearchTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REVISION = "11111111-1111-4111-8111-111111111111";
  private static final String EVIDENCE = "22222222-2222-4222-8222-222222222222";
  private static final String TICKET = "33333333-3333-4333-8333-333333333333";
  private static final String REPORT = "44444444-4444-4444-8444-444444444444";
  private static final String URL = "https://privacy.example.org/tv-research";
  private static final String PASSAGE =
      "Some models collect viewing data when the feature is enabled.";

  @Test
  void retains_exact_evidence_then_reviews_before_writing_an_answer() throws Exception {
    var host = new Host(false);
    host.reachDraft();
    host.reply(draft(EVIDENCE));
    assertEquals("privacy_product_reviewer", host.args().path("agent").asText());
    assertTrue(host.args().path("task").asText().contains(PASSAGE));
    host.reply(Map.of("verdict", "accepted", "notes", "Checked the claim and model uncertainty."));
    assertEquals("report", host.args().path("operation").asText());
    assertTrue(host.args().path("text").asText().contains("[evidence:" + EVIDENCE + "]"));
    assertEquals(REVISION, host.args().path("inputs").get(0).asText());
    host.reply(Map.of("revision", REPORT));
    host.ackTodo();
    assertEquals("orchestration_finish", host.command.path("tool").asText());
    var answer = JSON.readTree(host.args().path("result").asText());
    assertEquals(REPORT, answer.path("report").asText());
    assertEquals("privacy", answer.path("sources").get(0).path("kind").asText());
  }

  @Test
  void reviewer_corrections_are_reviewed_again_and_second_rejection_writes_nothing()
      throws Exception {
    var host = new Host(false);
    host.reachDraft();
    host.reply(draft(EVIDENCE));
    host.reply(Map.of("verdict", "needs_corrections", "notes", "Narrow firmware applicability."));
    assertEquals("privacy_product_researcher", host.args().path("agent").asText());
    assertTrue(host.args().path("task").asText().startsWith("MODE: correct"));
    host.reply(draft(EVIDENCE));
    assertEquals("privacy_product_reviewer", host.args().path("agent").asText());
    assertThrows(
        IllegalStateException.class,
        () -> host.reply(Map.of("verdict", "needs_corrections", "notes", "Still unsupported.")));
  }

  @Test
  void invented_citations_and_foreign_source_coordinates_are_rejected() throws Exception {
    var host = new Host(false);
    host.reachDraft();
    assertThrows(
        IllegalStateException.class, () -> host.reply(draft(UUID.randomUUID().toString())));
    var foreign = new Host(false);
    foreign.discover();
    foreign.reply(Map.of("id", TICKET));
    foreign.reply(
        Map.of(
            "outcomes",
            List.of(Map.of("acquisition", TICKET, "state", "ready", "revision", REVISION))));
    assertThrows(
        IllegalStateException.class,
        () ->
            foreign.reply(
                List.of(
                    Map.of(
                        "matched",
                        true,
                        "revision",
                        REPORT,
                        "start",
                        0,
                        "end",
                        PASSAGE.length(),
                        "text",
                        PASSAGE))));
  }

  @Test
  void existing_library_sources_are_read_without_another_acquisition() throws Exception {
    var host = new Host(true);
    host.discover();
    assertEquals("search", host.args().path("operation").asText());
    assertEquals(REVISION, host.args().path("revision").asText());
    assertFalse(host.command.toString().contains("acquire"));
  }

  @Test
  void discovery_titles_use_words_with_tolerance_and_keep_the_full_title() throws Exception {
    // More than 200 characters, and 25% above the 200-word target. Unicode
    // whitespace follows the shared recovery policy rather than ASCII splitting.
    String title = String.join("\u00a0\u0085\ufeff", Collections.nCopies(250, "research"));
    var host = new Host(false);
    host.ackTodo();
    host.reply(discovery(title));
    assertEquals("acquire", host.args().path("operation").asText());
    assertEquals(title, host.output.path("state").path("sources").get(0).path("title").asText());
    assertFalse(host.args().path("name").asText().contains(title));
  }

  @Test
  void overlong_title_is_shortened_once_without_repeating_discovery_or_changing_its_url()
      throws Exception {
    var host = new Host(false);
    host.ackTodo();
    host.reply(discovery("research ".repeat(251)));
    assertEquals("privacy_output_recovery", host.args().path("agent").asText());
    assertTrue(host.args().path("recovery").asBoolean());
    assertFalse(host.args().path("task").asText().contains(URL));
    String shortened = "research ".repeat(225);
    host.reply(Map.of("field_0", shortened));
    assertEquals("acquire", host.args().path("operation").asText());
    assertEquals(URL, host.args().path("url").asText());
    assertEquals(
        shortened, host.output.path("state").path("sources").get(0).path("title").asText());
  }

  @Test
  void invalid_title_text_names_the_field_without_disclosing_its_contents() throws Exception {
    for (String title : List.of("\u0085\ufeff\u00a0", "private-question\u0007payload")) {
      var host = new Host(false);
      host.ackTodo();
      var failure = assertThrows(IllegalStateException.class, () -> host.reply(discovery(title)));
      assertTrue(failure.getMessage().contains("discover.sources[0].title"));
      assertFalse(failure.getMessage().contains("private-question"));
    }
  }

  @Test
  void unsuccessful_shortening_preserves_research_for_review_without_another_model_attempt()
      throws Exception {
    var host = new Host(false);
    host.ackTodo();
    String title = "research ".repeat(251);
    host.reply(discovery(title));
    host.reply(Map.of("field_0", title));
    assertEquals("orchestration_ask", host.command.path("tool").asText());
    assertEquals(
        title,
        host.output
            .path("state")
            .path("recovering")
            .path("value")
            .path("sources")
            .get(0)
            .path("title")
            .asText());
    host.reply(Map.of("answer", "continue"));
    assertEquals("orchestration_ask", host.command.path("tool").asText());
    assertTrue(host.output.path("state").path("report").isNull());
  }

  @Test
  void long_prose_below_word_targets_passes_drafting_and_review() throws Exception {
    var host = new Host(false);
    host.reachDraft();
    ObjectNode answer = JSON.valueToTree(draft(EVIDENCE));
    answer.put("summary", "uncertainty ".repeat(400));
    host.reply(answer);
    assertEquals("privacy_product_reviewer", host.args().path("agent").asText());
    host.reply(Map.of("verdict", "accepted", "notes", "reviewed ".repeat(600)));
    assertEquals("report", host.args().path("operation").asText());
  }

  @Test
  void a_raw_delivery_overflow_retains_the_draft_for_review_before_writing_a_report()
      throws Exception {
    var host = new Host(false);
    host.reachDraft();
    ObjectNode answer = JSON.valueToTree(draft(EVIDENCE));
    String enormousWord = "x".repeat(65537);
    answer.put("summary", enormousWord);
    host.reply(answer);
    host.reply(Map.of("verdict", "accepted", "notes", "Checked."));
    assertEquals("orchestration_ask", host.command.path("tool").asText());
    assertEquals(enormousWord, host.output.path("state").path("draft").path("summary").asText());
    assertTrue(host.output.path("state").path("report").isNull());
  }

  @Test
  void shortening_a_claim_preserves_other_prose_and_evidence_before_independent_review()
      throws Exception {
    var host = new Host(false);
    host.reachDraft();
    ObjectNode answer = JSON.valueToTree(draft(EVIDENCE));
    ((ObjectNode) answer.path("findings").get(0)).put("claim", "documented ".repeat(313));
    host.reply(answer);
    assertEquals("privacy_output_recovery", host.args().path("agent").asText());
    host.reply(Map.of("field_1", "Documented viewing data collection."));
    assertEquals("privacy_product_reviewer", host.args().path("agent").asText());
    var retained = host.output.path("state").path("draft");
    assertEquals(answer.path("summary"), retained.path("summary"));
    assertEquals(answer.path("gaps"), retained.path("gaps"));
    assertEquals(
        answer.path("findings").get(0).path("evidence"),
        retained.path("findings").get(0).path("evidence"));
  }

  private static Map<String, Object> discovery(String title) {
    return Map.of(
        "sources", List.of(Map.of("url", URL, "title", title, "kind", "privacy")),
        "searched", List.of("privacy"),
        "gaps", List.of());
  }

  @Test
  void unsafe_discovery_urls_fail_before_any_fetch() throws Exception {
    for (String url :
        List.of(
            "http://example.org/",
            "https://127.0.0.1/",
            "https://tv.local/",
            "https://user:secret@example.org/")) {
      var host = new Host(false);
      host.ackTodo();
      assertThrows(
          IllegalStateException.class,
          () ->
              host.reply(
                  Map.of(
                      "sources",
                      List.of(Map.of("url", url, "title", "Unsafe", "kind", "research")),
                      "searched",
                      List.of("research"),
                      "gaps",
                      List.of())));
    }
  }

  @Test
  void no_search_results_can_produce_a_reviewed_gap_answer_without_fabricated_findings()
      throws Exception {
    var host = new Host(false);
    host.ackTodo();
    host.reply(
        Map.of(
            "sources",
            List.of(),
            "searched",
            List.of("privacy"),
            "gaps",
            List.of("No relevant source found.")));
    host.reply(
        Map.of(
            "summary",
            "Applicability remains unknown.",
            "findings",
            List.of(),
            "gaps",
            List.of("Need model and firmware."),
            "next_checks",
            List.of("Check the TV's About screen.")));
    host.reply(Map.of("verdict", "accepted", "notes", "The answer candidly states its gaps."));
    assertEquals("report", host.args().path("operation").asText());
    assertTrue(host.args().path("inputs").isEmpty());
  }

  private static Map<String, Object> draft(String evidence) {
    return Map.of(
        "summary", "The feature is documented for some models; this device is not yet matched.",
        "findings",
            List.of(
                Map.of(
                    "claim",
                    PASSAGE,
                    "applicability",
                    "Exact model and firmware unknown.",
                    "evidence",
                    List.of(evidence))),
        "gaps", List.of("Need model and firmware."),
        "next_checks", List.of("Read the device's About screen."));
  }

  private static final class Host {
    private final String source;
    private final ObjectNode input = JSON.createObjectNode();
    private final ObjectNode todo;
    private JsonNode output;
    private JsonNode command;

    Host(boolean known) throws Exception {
      source =
          Files.readString(
              Path.of(System.getProperty("privacy.application"))
                  .resolve("orchestrations/research_product_question.js"));
      var request =
          JSON.createObjectNode()
              .put("version", 1)
              .put("product", "lg-tv")
              .put("question", "What viewing data is collected?");
      request.putNull("dossier");
      request.putArray("probes");
      var sources = request.putArray("known_sources");
      if (known)
        sources
            .addObject()
            .put("url", URL)
            .put("title", "Tracking study")
            .put("kind", "privacy")
            .put("revision", REVISION);
      input.put("message", JSON.createObjectNode().put("request", request.toString()).toString());
      todo =
          input
              .putArray("todos")
              .addObject()
              .put("stageId", "research")
              .put("id", "research-todo")
              .put("status", "pending");
      next();
    }

    JsonNode args() {
      return command.path("arguments");
    }

    void next() {
      input.put("requestId", UUID.randomUUID().toString());
      output = ScriptProgram.step(source, input);
      command = output.path("command");
    }

    void reply(Object value) throws Exception {
      input.set("state", output.path("state"));
      input.put("result", JSON.writeValueAsString(value));
      next();
    }

    void ackTodo() throws Exception {
      var ops = args().path("ops");
      todo.put("status", ops.get(0).path("status").asText());
      reply(Map.of("ok", true));
    }

    void discover() throws Exception {
      ackTodo();
      reply(
          Map.of(
              "sources",
              List.of(Map.of("url", URL, "title", "Tracking study", "kind", "privacy")),
              "searched",
              List.of("official", "privacy", "research", "reporting"),
              "gaps",
              List.of()));
    }

    void reachDraft() throws Exception {
      discover();
      reply(Map.of("id", TICKET));
      reply(
          Map.of(
              "outcomes",
              List.of(Map.of("acquisition", TICKET, "state", "ready", "revision", REVISION))));
      reply(
          List.of(
              Map.of(
                  "matched",
                  true,
                  "revision",
                  REVISION,
                  "start",
                  0,
                  "end",
                  PASSAGE.length(),
                  "text",
                  PASSAGE)));
      assertEquals("evidence", args().path("operation").asText());
      assertEquals(PASSAGE, args().path("quote").asText());
      reply(Map.of("evidence", EVIDENCE));
      assertTrue(args().path("task").asText().startsWith("MODE: draft"));
    }
  }
}
