package io.aeyer.plowshare.integrations.privacy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Runs the per-device assessment script in the real sandbox with fake agents and tools. No model,
 * database or server process is involved; each step round-trips state as a durable restart would.
 */
class NetworkDeviceAssessmentTest {
  private static final String DEVICE = "mac:02:00:00:00:00:01";
  private static final String DOSSIER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String PREVIOUS = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String PROFILE = "66666666-6666-4666-8666-666666666666";
  private static final String ISSUE = "88888888-8888-4888-8888-888888888888";
  private static final String REPORT = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  private static final String RESOURCE = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  private static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void accepted_assessment_writes_one_stable_report_revision_with_findings_and_review()
      throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.finish();
    assertEquals(1, host.writes);
    assertEquals(1, host.assessorTasks.size());
    assertEquals(1, host.reviewerTasks.size());
    var report = host.report;
    assertEquals("report", report.path("operation").asText());
    assertEquals(host.writeRequest, report.path("requestId").asText());
    assertEquals("Network privacy assessment / " + DEVICE, report.path("name").asText());
    assertEquals(PREVIOUS, report.path("feedback").asText());
    assertEquals(List.of(DOSSIER, PROFILE, ISSUE), texts(report.path("inputs")));
    assertEquals(1, report.path("objectives").size());
    var finding = report.path("findings").get(0);
    assertEquals(1, report.path("findings").size());
    assertEquals("c1", finding.path("id").asText());
    assertEquals(report.path("objectives").get(0).asText(), finding.path("objective").asText());
    assertEquals("holds", finding.path("verdict").asText());
    assertTrue(finding.path("claim").asText().contains("Viewing measurement by OzTAM"));
    assertFalse(finding.path("rationale").asText().isBlank());
    var reviews = report.path("reviews");
    assertEquals(1, reviews.size());
    assertEquals("accepted", reviews.get(0).path("outcome").asText());
    assertTrue(reviews.get(0).path("text").asText().contains("Supported by the dossier"));
    var result = JSON.readTree(host.command.path("arguments").path("result").asText());
    assertEquals(DEVICE, result.path("device_id").asText());
    assertEquals(DOSSIER, result.path("dossier").asText());
    assertEquals(REPORT, result.path("report").path("revision").asText());
    assertEquals(RESOURCE, result.path("report").path("resource").asText());
    assertEquals("accepted", result.path("review").asText());
    assertFalse(result.path("corrected").asBoolean());
    assertEquals("holds", result.path("concerns").get(0).path("verdict").asText());
    for (var todo : host.input.path("todos")) assertEquals("done", todo.path("status").asText());
    assertTrue(host.reads > 1, "The dossier must be assembled from every read window");
  }

  @Test
  void first_assessment_has_no_feedback_and_agents_see_only_the_dossier_data() throws Exception {
    var host = new Host(null);
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.finish();
    assertFalse(host.report.has("feedback"));
    var data = payload(host.assessorTasks.getFirst());
    assertEquals(DEVICE, data.path("device_id").asText());
    assertEquals("Lounge TV", data.path("dossier").path("identity").path("name").asText());
    assertFalse(data.path("dossier").has("write_id"));
    var reviewed = payload(host.reviewerTasks.getFirst());
    assertEquals(assessment(), reviewed.path("assessment"));
    assertEquals(data.path("dossier"), reviewed.path("dossier"));
    assertTrue(host.reviewerTasks.getFirst().contains("per concern"));
  }

  @Test
  void needs_corrections_runs_one_correction_then_removes_refuted_and_demotes_weakened()
      throws Exception {
    var host = new Host(PREVIOUS);
    var draft = twoConcerns();
    host.assessor.add(draft.toString());
    host.reviewer.add(
        review("needs_corrections", "c1", "weakened", "c2", "refuted")
            .put("notes", "Lookups alone do not show what was sent.")
            .toString());
    // The correction keeps both concerns unchanged; the script still applies the verdicts.
    host.assessor.add(draft.toString());
    host.finish();
    assertEquals(2, host.assessorTasks.size());
    assertEquals(1, host.reviewerTasks.size(), "The reviewer is not asked again");
    var correction = payload(host.assessorTasks.get(1));
    assertEquals(draft, correction.path("assessment"));
    assertEquals(
        "Lookups alone do not show what was sent.",
        correction.path("review").path("notes").asText());
    assertEquals(
        "refuted", correction.path("review").path("concerns").get(1).path("verdict").asText());
    String text = host.report.path("text").asText();
    assertFalse(text.contains("Sends viewing history to Sony"));
    assertTrue(text.contains("Viewing measurement by OzTAM"));
    assertTrue(text.contains("Worth checking, lowered from potential breach after review."));
    assertFalse(text.contains("Level: Potential breach"));
    var findings = host.report.path("findings");
    assertEquals(2, findings.size());
    assertEquals("weakened", findings.get(0).path("verdict").asText());
    assertEquals("refuted", findings.get(1).path("verdict").asText());
    assertEquals("needs_corrections", host.report.path("reviews").get(0).path("outcome").asText());
    var result = JSON.readTree(host.command.path("arguments").path("result").asText());
    assertTrue(result.path("corrected").asBoolean());
    assertEquals("worth_checking", result.path("concerns").get(0).path("level").asText());
    assertEquals("refuted", result.path("concerns").get(1).path("verdict").asText());
  }

  @Test
  void a_correction_cannot_add_a_concern_the_reviewer_never_saw() throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("needs_corrections", "c1", "weakened").toString());
    // The repair run repeats the added concern, so the run fails.
    host.assessor.add(twoConcerns().toString());
    host.assessor.add(twoConcerns().toString());
    var failure = assertThrows(IllegalStateException.class, host::finish);
    assertTrue(failure.getMessage().contains("c2"), failure.getMessage());
    assertTrue(host.assessorTasks.get(2).contains("c2"), host.assessorTasks.get(2));
    assertEquals(0, host.writes);
  }

  @Test
  void not_checked_and_invalid_agent_json_write_nothing() throws Exception {
    for (String reviewer :
        new String[] {
          review("not_checked", "c1", "holds").toString(),
          "not JSON",
          "E_NO_EXEC: tool unavailable",
          review("guessed", "c1", "holds").toString(),
          review("accepted", "c1", "not_checked").toString(),
          review("accepted", "c9", "holds").toString(),
          review("accepted").toString(),
          review("accepted", "c1", "holds").put("notes", "").toString(),
          review("accepted", "c1", "holds").put("extra", true).toString()
        }) {
      var host = new Host(PREVIOUS);
      host.assessor.add(assessment().toString());
      // not_checked and host refusals end the run at once; other invalid
      // reviews get the one repair run and fail on the second answer.
      host.reviewer.add(reviewer);
      host.reviewer.add(reviewer);
      assertThrows(IllegalStateException.class, host::finish, reviewer);
      assertEquals(0, host.writes, reviewer);
      boolean immediate =
          reviewer.startsWith("E_NO_EXEC") || reviewer.contains("\"not_checked\",\"concerns\"");
      assertEquals(immediate ? 1 : 2, host.reviewerTasks.size(), reviewer);
    }
    for (String assessor : new String[] {"not JSON", "<|channel|>final " + assessment(), "[]"}) {
      var host = new Host(PREVIOUS);
      // The first invalid answer gets one repair run; the second fails the run.
      host.assessor.add(assessor);
      host.assessor.add(assessor);
      assertThrows(IllegalStateException.class, host::finish, assessor);
      assertEquals(2, host.assessorTasks.size());
      assertEquals(0, host.writes);
      assertEquals(0, host.reviewerTasks.size());
    }
  }

  @Test
  void markup_tables_and_identifiers_in_agent_fields_are_rejected() throws Exception {
    List<Consumer<ObjectNode>> edits =
        List.of(
            a -> a.put("in_short", "Ports 443<br>8009 are open."),
            a -> a.put("in_short", "| Port | Service |"),
            a -> a.put("in_short", "Read revision " + DOSSIER + " for details."),
            a -> ((ObjectNode) a.path("what_it_is")).put("text", "A <b>TV</b>."),
            a -> ((ObjectNode) a.path("who_it_talks_to").get(0)).put("meaning", "|---|---|"),
            a -> ((ObjectNode) a.path("what_it_sends")).put("unknown", "See " + PROFILE),
            a -> ((ObjectNode) a.path("concerns").get(0)).put("fact", "Line one\nLine two"),
            a -> ((ObjectNode) a.path("concerns").get(0)).put("title", "## Heading"),
            a -> ((ObjectNode) a.path("concerns").get(0)).put("inference", "It exfiltrates data."),
            a -> ((ArrayNode) a.path("questions")).add("<script>alert(1)</script>"),
            a -> ((ArrayNode) a.path("what_it_is").path("basis")).add("A | B | C"));
    for (var edit : edits) {
      var value = assessment();
      edit.accept(value);
      var host = new Host(PREVIOUS);
      host.assessor.add(value.toString());
      host.assessor.add(value.toString());
      assertThrows(IllegalStateException.class, host::finish, value.toString());
      assertEquals(0, host.reviewerTasks.size(), value.toString());
      assertEquals(0, host.writes);
    }
  }

  @Test
  void unknown_enums_and_missing_fields_are_rejected() throws Exception {
    List<Consumer<ObjectNode>> edits =
        List.of(
            a -> ((ObjectNode) a.path("what_it_is")).put("confidence", "certain"),
            a -> ((ObjectNode) a.path("concerns").get(0)).put("level", "critical"),
            a -> ((ObjectNode) a.path("concerns").get(0)).put("confidence", "sure"),
            a -> a.remove("questions"),
            a -> ((ObjectNode) a.path("concerns").get(0)).remove("fact"));
    for (var edit : edits) {
      var value = assessment();
      edit.accept(value);
      var host = new Host(PREVIOUS);
      host.assessor.add(value.toString());
      host.assessor.add(value.toString());
      assertThrows(IllegalStateException.class, host::finish, value.toString());
      assertEquals(0, host.writes, value.toString());
    }
  }

  @Test
  void form_problems_are_fixed_and_listed_in_the_evidence() throws Exception {
    record Case(Consumer<ObjectNode> edit, String note, Consumer<JsonNode> check) {}
    List<Case> cases =
        List.of(
            // Advertising is a known purpose, but this dossier has no advertising lookups.
            new Case(
                a -> ((ObjectNode) a.path("who_it_talks_to").get(0)).put("purpose", "advertising"),
                "this device has no advertising lookups",
                r -> assertFalse(r.path("text").asText().contains("OzTAM measures"))),
            new Case(
                a -> ((ObjectNode) a.path("who_it_talks_to").get(0)).put("purpose", "spyware"),
                "this device has no spyware lookups",
                r -> {}),
            new Case(
                a -> ((ArrayNode) a.path("concerns").get(0).path("signals")).add("upload_heavy"),
                "Removed signal",
                r -> assertEquals(1, r.path("findings").size())),
            new Case(
                a -> ((ArrayNode) a.path("concerns").get(0).path("signals")).add("made_up"),
                "Removed signal",
                r -> {}),
            new Case(
                a -> ((ObjectNode) a.path("concerns").get(0)).put("id", "concern-1"),
                "Renumbered concern ids",
                r -> assertEquals("c1", r.path("findings").get(0).path("id").asText())),
            new Case(
                a -> a.put("verdict", "accepted"), "Ignored unexpected assessment fields", r -> {}),
            new Case(
                a -> a.put("in_short", "This is a long sentence. ".repeat(20).trim()),
                "Shortened in short",
                r -> {}),
            new Case(
                a ->
                    ((ObjectNode) a.path("concerns").get(0))
                        .put("fact", "The TV looked up OzTAM. " + "x".repeat(500)),
                "Shortened concerns 1 fact to 400 characters",
                r -> assertTrue(r.path("text").asText().contains("The TV looked up OzTAM."))));
    for (var item : cases) {
      var value = assessment();
      item.edit().accept(value);
      var host = new Host(PREVIOUS);
      host.assessor.add(value.toString());
      host.reviewer.add(review("accepted", "c1", "holds").toString());
      host.finish();
      assertEquals(1, host.writes, value.toString());
      assertEquals(1, host.assessorTasks.size(), "Form fixes need no repair run");
      var text = host.report.path("text").asText();
      var evidence = text.substring(text.indexOf("Automatic fixes to the assessor answer:"));
      assertTrue(evidence.contains(item.note()), evidence);
      item.check().accept(host.report);
    }
  }

  @Test
  void a_concern_without_a_valid_signal_becomes_an_open_question() throws Exception {
    // PROD's first TV run cited the DNS purpose "telemetry" as a signal code.
    var value = assessment();
    ((ArrayNode) value.path("concerns").get(0).path("signals")).removeAll().add("telemetry");
    var host = new Host(PREVIOUS);
    host.assessor.add(value.toString());
    host.reviewer.add(review("accepted").toString());
    host.finish();
    assertEquals(1, host.writes);
    assertEquals(1, host.assessorTasks.size());
    var reviewed = payload(host.reviewerTasks.getFirst()).path("assessment");
    assertEquals(0, reviewed.path("concerns").size());
    assertTrue(
        reviewed
            .path("questions")
            .get(1)
            .asText()
            .contains("Worth checking, though nothing in this data shows it: Viewing measurement"));
    String first = host.assessorTasks.get(0).split("\\nDATA:\\n", 2)[0];
    assertTrue(
        first.contains("Allowed concern signals for this device, copied exactly: measurement_dns."),
        first);
    assertTrue(host.report.path("text").asText().contains("Moved concern"));
  }

  @Test
  void a_safety_problem_gets_one_repair_run_with_the_validator_message() throws Exception {
    var invalid = assessment();
    invalid.put("in_short", "Ports 443<br>8009 are open.");
    var host = new Host(PREVIOUS);
    host.assessor.add(invalid.toString());
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.finish();
    assertEquals(1, host.writes);
    assertEquals(2, host.assessorTasks.size());
    String repair = host.assessorTasks.get(1).split("\\nDATA:\\n", 2)[0];
    assertTrue(repair.contains("rejected by the validator"), repair);
    assertTrue(repair.contains("HTML and markup are not allowed"), repair);
    assertEquals(assessment(), payload(host.reviewerTasks.getFirst()).path("assessment"));
  }

  @Test
  void a_reviewer_may_name_exfiltration_to_refute_it() throws Exception {
    // PROD's second TV run failed here: the reviewer is told to refute
    // exfiltration claims and naturally wrote the word in its notes.
    var host = new Host(PREVIOUS);
    host.assessor.add(assessment().toString());
    var reviewed = review("accepted", "c1", "holds");
    reviewed.put("notes", "No exfiltration is claimed; DNS lookups cannot show it.");
    ((ObjectNode) reviewed.path("concerns").get(0))
        .put("note", "Holds; it makes no exfiltration claim.");
    host.reviewer.add(reviewed.toString());
    host.finish();
    assertEquals(1, host.writes);
    assertEquals(1, host.reviewerTasks.size());
    assertTrue(
        host.report.path("reviews").get(0).path("text").asText().contains("No exfiltration"));
    assertFalse(host.report.path("text").asText().toLowerCase().contains("exfiltrat"));
  }

  @Test
  void an_invalid_review_gets_one_repair_run() throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add(assessment().toString());
    host.reviewer.add(
        review("accepted", "c1", "holds").put("notes", "See <b>this</b>.").toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.finish();
    assertEquals(1, host.writes);
    assertEquals(2, host.reviewerTasks.size());
    String repair = host.reviewerTasks.get(1).split("\\nDATA:\\n", 2)[0];
    assertTrue(repair.contains("Your previous review was rejected by the validator"), repair);
    assertTrue(repair.contains("HTML and markup are not allowed"), repair);
  }

  @Test
  void the_assessor_and_reviewer_share_one_repair() throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add("not JSON");
    host.assessor.add(assessment().toString());
    host.reviewer.add("not JSON");
    assertThrows(IllegalStateException.class, host::finish);
    assertEquals(1, host.reviewerTasks.size());
    assertEquals(0, host.writes);
  }

  @Test
  void traffic_volumes_and_direction_are_written_by_code() throws Exception {
    // PROD's third TV run said the TV "transmitted" 35 MB that it had received.
    var value = assessment();
    ((ObjectNode) value.path("what_it_sends"))
        .put("observed", "The TV transmitted 4.2 GB and received 310 MB. Most of it is streaming.");
    value.put("in_short", "The TV uploaded 4.2 GB. It also looked up OzTAM.");
    var host = new Host(PREVIOUS);
    host.assessor.add(value.toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.finish();
    assertEquals(1, host.writes);
    assertEquals(1, host.assessorTasks.size(), "Removing byte totals needs no repair run");
    var text = host.report.path("text").asText();
    assertTrue(
        text.contains("the network counted 310 MB sent by this device and 4.2 GB sent to it."),
        text);
    assertFalse(text.contains("transmitted 4.2 GB"), text);
    assertFalse(text.contains("uploaded 4.2 GB"), text);
    assertTrue(text.contains("Most of it is streaming."), text);
    assertTrue(text.contains("It also looked up OzTAM."), text);
    assertTrue(text.contains("Removed a byte total from what it sends"), text);
    assertTrue(text.contains("Removed a byte total from in short"), text);
    String task = host.assessorTasks.getFirst().split("\\nDATA:\\n", 2)[0];
    assertTrue(task.contains("to_device_bytes is what the device received"), task);
    assertTrue(
        host.reviewerTasks.getFirst().contains("to_device_bytes is what the device received"));
  }

  @Test
  void host_refusals_are_not_repaired() throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add("E_NO_EXEC: delegate unavailable");
    assertThrows(IllegalStateException.class, host::finish);
    assertEquals(1, host.assessorTasks.size());
    assertEquals(0, host.writes);
  }

  @Test
  void the_draft_and_correction_share_one_repair() throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add("not JSON");
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("needs_corrections", "c1", "weakened").toString());
    host.assessor.add("not JSON");
    assertThrows(IllegalStateException.class, host::finish);
    assertEquals(3, host.assessorTasks.size());
    assertEquals(0, host.writes);
  }

  @Test
  void rendered_markdown_for_a_tv_that_talks_to_oztam_reads_plainly() throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.finish();
    String text = host.report.path("text").asText();
    assertEquals(resource("tv-assessment.md"), text);
    assertFalse(text.contains("<"), "No HTML");
    assertFalse(text.contains("|"), "No tables");
    assertFalse(text.toLowerCase().contains("exfiltrat"));
    String body = text.substring(0, text.indexOf("\n## Evidence\n"));
    assertFalse(UUID_TEXT.matcher(body).find(), "Identifiers belong only in the appendix");
    assertFalse(body.contains(DEVICE), "Device identifiers belong only in the appendix");
    assertTrue(text.startsWith("# Lounge TV: privacy assessment\n"));
    assertTrue(text.indexOf("## In short") < text.indexOf("## What it is"));
    assertTrue(text.substring(text.indexOf("\n## Evidence\n")).contains(DOSSIER));
  }

  @Test
  void supplemental_information_lists_ranked_advisories_and_sanitises_device_text()
      throws Exception {
    var host =
        Host.of(
            PREVIOUS,
            dossier(
                section -> {
                  var upnp = (ObjectNode) section.path("descriptions").get(0);
                  upnp.put("model_name", "<b>X|Y</b> `rm` [link](x) " + "Z".repeat(100))
                      .put("firmware", "6.6.0 " + PROFILE);
                  var lookup = (ObjectNode) section.path("advisories");
                  lookup.put("total", 7).put("omitted", 0);
                  var list = lookup.putArray("advisories");
                  list.addObject()
                      .put("cve", "CVE-2026-0001")
                      .put("severity", "HIGH")
                      .put("published_on", "2026-02-03")
                      .put("known_exploited", true)
                      .put(
                          "description",
                          "Remote code execution | via <script> in the cast service.");
                  list.addObject()
                      .put("cve", "CVE-2025-1234")
                      .putNull("severity")
                      .put("published_on", "2025-06-01")
                      .put("known_exploited", false)
                      .put("description", "Information disclosure.");
                }));
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.finish();
    String text = host.report.path("text").asText();
    String section =
        text.substring(
            text.indexOf("\n## Supplemental information\n"), text.indexOf("\n## Evidence\n"));
    assertTrue(
        section.contains(
            "- CVE-2026-0001 (high, published 2026-02-03, known exploited): Remote code"
                + " execution via script in the cast service."),
        section);
    assertTrue(
        section.contains("- CVE-2025-1234 (severity not rated, published 2025-06-01)"), section);
    assertTrue(section.contains("): 7 found."), section);
    assertTrue(section.contains("5 more are not listed here."), section);
    assertTrue(section.contains("not proof that this device is affected"), section);
    assertTrue(section.contains("firmware 6.6.0 (identifier removed)"), section);
    assertFalse(section.contains("<"), section);
    assertFalse(section.contains("|"), section);
    assertFalse(section.contains("`"), section);
    assertFalse(section.contains("]("), section);
    assertTrue(section.contains("b X Y /b rm link (x) ZZZ"), section);
    assertFalse(section.contains("Z".repeat(80)), "Device text is bounded");
    assertFalse(UUID_TEXT.matcher(section).find(), section);
    assertTrue(text.indexOf("## Open questions") < text.indexOf("## Supplemental information"));
  }

  @Test
  void supplemental_information_states_plainly_when_nothing_is_known() throws Exception {
    var empty =
        Host.of(
            PREVIOUS,
            dossier(
                section -> {
                  section.putNull("described_at").putNull("updated_at").putNull("advisories");
                  section.putArray("descriptions");
                  section.putArray("vendor_notes");
                }));
    empty.assessor.add(assessment().toString());
    empty.reviewer.add(review("accepted", "c1", "holds").toString());
    empty.finish();
    assertTrue(
        empty
            .report
            .path("text")
            .asText()
            .contains(
                "## Supplemental information\n\nNothing is known about this device beyond its"
                    + " network traffic: it has not been asked to describe itself and no advisories"
                    + " were looked up.\n\n## Evidence"));
    var silent =
        Host.of(
            PREVIOUS,
            dossier(
                section -> {
                  section.putArray("descriptions");
                  ((ObjectNode) section.path("advisories"))
                      .put("status", "model_unknown")
                      .putNull("model")
                      .putNull("model_source")
                      .putNull("total");
                }));
    silent.assessor.add(assessment().toString());
    silent.reviewer.add(review("accepted", "c1", "holds").toString());
    silent.finish();
    String text = silent.report.path("text").asText();
    assertTrue(text.contains("did not describe itself when last asked (2026-01-01)"), text);
    assertTrue(text.contains("that is not a finding"), text);
    assertTrue(text.contains("no public model name was available"), text);
    // A version 1 document, retained before the section existed, still renders.
    var legacy = (ObjectNode) JSON.readTree(resource("tv-dossier.json"));
    legacy.put("version", 1);
    ((ObjectNode) legacy.path("dossier")).remove("supplemental");
    var old = Host.of(PREVIOUS, legacy.toString());
    old.assessor.add(assessment().toString());
    old.reviewer.add(review("accepted", "c1", "holds").toString());
    old.finish();
    assertTrue(
        old.report
            .path("text")
            .asText()
            .contains("No supplemental information was collected for this device."));
  }

  @Test
  void agents_are_told_supplemental_facts_are_context_and_bad_sections_stop_the_run()
      throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.finish();
    for (String task : List.of(host.assessorTasks.getFirst(), host.reviewerTasks.getFirst())) {
      String instructions = task.split("\\nDATA:\\n", 2)[0];
      assertTrue(instructions.contains("DATA.dossier.supplemental is context"), instructions);
    }
    assertEquals(
        "BRAVIA KD-55X85K",
        payload(host.assessorTasks.getFirst())
            .path("dossier")
            .path("supplemental")
            .path("descriptions")
            .get(0)
            .path("model_name")
            .asText());
    List<Consumer<ObjectNode>> edits =
        List.of(
            s -> s.put("extra", true),
            s -> s.remove("vendor_notes"),
            s -> ((ObjectNode) s.path("descriptions").get(0)).put("source", "ssdp"),
            s -> ((ObjectNode) s.path("advisories")).put("status", "guessed"),
            s ->
                ((ObjectNode) s.path("vendor_notes").get(0).path("sources").get(0))
                    .put("url", "javascript:alert(1)"),
            s -> ((ObjectNode) s.path("descriptions").get(0)).put("firmware", "x".repeat(129)));
    for (var edit : edits) {
      var invalid = Host.of(PREVIOUS, dossier(edit));
      assertThrows(IllegalStateException.class, invalid::finish);
      assertEquals(0, invalid.assessorTasks.size());
      assertEquals(0, invalid.writes);
    }
    var version1 = (ObjectNode) JSON.readTree(resource("tv-dossier.json"));
    version1.put("version", 1);
    var invalid = Host.of(PREVIOUS, version1.toString());
    assertThrows(IllegalStateException.class, invalid::finish);
    assertEquals(0, invalid.assessorTasks.size());
  }

  @Test
  void a_dossier_for_another_device_or_unready_extraction_stops_before_any_model_call()
      throws Exception {
    var foreign = new Host(PREVIOUS, DEVICE.replace("01", "02"));
    assertThrows(IllegalStateException.class, foreign::finish);
    assertEquals(0, foreign.assessorTasks.size());
    var pending = new Host(PREVIOUS);
    pending.extraction = "pending";
    assertThrows(IllegalStateException.class, pending::finish);
    assertEquals(0, pending.assessorTasks.size());
    assertEquals(0, pending.writes);
  }

  @Test
  void malformed_start_requests_are_refused() throws Exception {
    for (var request :
        List.<Map<String, Object>>of(
            Map.of("version", 1, "device_id", DEVICE, "dossier", DOSSIER),
            request(Map.of("device_id", "ip:10.1.2.10")),
            request(Map.of("dossier", "not-a-uuid")),
            request(Map.of("previous", DOSSIER)),
            request(Map.of("requested_at", "2026-11-11T09:30:00+10:00")),
            request(Map.of("version", 2)),
            request(Map.of("extra", true)))) {
      var host = new Host(PREVIOUS);
      host.setRequest(request);
      assertThrows(IllegalStateException.class, host::next, request.toString());
    }
  }

  @Test
  void report_receipt_is_authoritative() throws Exception {
    var host = new Host(PREVIOUS);
    host.assessor.add(assessment().toString());
    host.reviewer.add(review("accepted", "c1", "holds").toString());
    host.receipt = Map.of("revision", "invalid", "resource", RESOURCE);
    assertThrows(IllegalStateException.class, host::finish);
    assertFalse(host.command.path("tool").asText().equals("orchestration_finish"));
    var reused = new Host(PREVIOUS);
    reused.assessor.add(assessment().toString());
    reused.reviewer.add(review("accepted", "c1", "holds").toString());
    reused.receipt = Map.of("revision", PREVIOUS, "resource", RESOURCE);
    assertThrows(IllegalStateException.class, reused::finish);
  }

  /** The TV dossier document with its supplemental section edited. */
  private static String dossier(Consumer<ObjectNode> edit) throws Exception {
    var document = (ObjectNode) JSON.readTree(resource("tv-dossier.json"));
    edit.accept((ObjectNode) document.path("dossier").path("supplemental"));
    return document.toString();
  }

  private static ObjectNode assessment() throws Exception {
    return (ObjectNode)
        JSON.readTree(
            """
            {"in_short":"The TV looked up OzTAM, an audience-measurement company, 14 times in eight hours. That is consistent with an app on the TV reporting what is watched. Nothing else stands out.",
             "what_it_is":{"text":"A Sony smart TV used for streaming.","confidence":"medium",
               "basis":["The device name suggests a TV.","The network card was made by Sony.","Most lookups are for Netflix and Sony services."]},
             "who_it_talks_to":[
               {"party":"OzTAM","purpose":"measurement","observation":"Looked up 14 times; none were blocked.","meaning":"OzTAM measures Australian TV audiences for broadcasters."},
               {"party":"Netflix","purpose":"streaming","observation":"Looked up 120 times.","meaning":"Ordinary streaming use."}],
             "what_it_sends":{"observed":"Most of the volume went to the TV, which is consistent with streaming video.","unknown":"Where the outgoing bytes went, and what they contained, cannot be told from this data."},
             "concerns":[
               {"id":"c1","title":"Viewing measurement by OzTAM","level":"worth_checking",
                "fact":"The TV looked up an OzTAM measurement name 14 times.",
                "inference":"A broadcaster app on the TV is probably reporting viewing to OzTAM.",
                "confidence":"medium","signals":["measurement_dns"],
                "mitigation":"Block oztam.com.au in Pi-hole. Some catch-up apps may refuse to play.",
                "verify":"Check that the Pi-hole query log shows the OzTAM lookups as blocked."}],
             "questions":["Which apps on the TV are used for broadcast catch-up?"]}
            """);
  }

  private static ObjectNode twoConcerns() throws Exception {
    var value = assessment();
    ((ObjectNode) value.path("concerns").get(0)).put("level", "potential_breach");
    var second = ((ObjectNode) value.path("concerns").get(0)).deepCopy();
    second
        .put("id", "c2")
        .put("title", "Sends viewing history to Sony")
        .put("level", "worth_checking");
    ((ArrayNode) value.path("concerns")).add(second);
    return value;
  }

  private static ObjectNode review(String verdict, String... concerns) {
    var value = JSON.createObjectNode().put("verdict", verdict);
    var list = value.putArray("concerns");
    for (int i = 0; i < concerns.length; i += 2)
      list.addObject()
          .put("id", concerns[i])
          .put("verdict", concerns[i + 1])
          .put("note", "Checked against the dossier signals.");
    value.put("notes", "Supported by the dossier; coverage gaps are stated.");
    return value;
  }

  private static Map<String, Object> request(Map<String, Object> changes) {
    var value = new LinkedHashMap<String, Object>();
    value.put("version", 1);
    value.put("device_id", DEVICE);
    value.put("dossier", DOSSIER);
    value.put("previous", PREVIOUS);
    value.put("requested_at", "2026-11-11T09:30:00.123456Z");
    value.putAll(changes);
    return value;
  }

  private static JsonNode payload(String task) throws Exception {
    return JSON.readTree(task.split("\\nDATA:\\n", 2)[1]);
  }

  private static List<String> texts(JsonNode array) {
    var result = new ArrayList<String>();
    array.forEach(value -> result.add(value.asText()));
    return result;
  }

  private static String resource(String name) throws Exception {
    try (var stream = NetworkDeviceAssessmentTest.class.getResourceAsStream(name)) {
      assertNotNull(stream, name);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** A minimal host oracle with scripted agent replies and an in-memory Information store. */
  private static final class Host {
    final String source =
        Files.readString(
            Path.of(System.getProperty("privacy.application"))
                .resolve("orchestrations/assess_network_device.js"));
    final ObjectNode input = JSON.createObjectNode();
    final Deque<String> assessor = new ArrayDeque<>();
    final Deque<String> reviewer = new ArrayDeque<>();
    final List<String> assessorTasks = new ArrayList<>();
    final List<String> reviewerTasks = new ArrayList<>();
    final String dossier;
    String extraction = "ready";
    Map<String, Object> receipt =
        Map.of("revision", REPORT, "resource", RESOURCE, "created", false);
    JsonNode command;
    JsonNode report;
    String writeRequest;
    int sequence;
    int reads;
    int waits;
    int writes;

    Host(String previous) throws Exception {
      this(previous, DEVICE);
    }

    Host(String previous, String retainedDevice) throws Exception {
      this(previous, resource("tv-dossier.json").replace(DEVICE, retainedDevice), true);
    }

    /** A host whose retained dossier is exactly {@code retained}. */
    static Host of(String previous, String retained) throws Exception {
      return new Host(previous, retained, true);
    }

    private Host(String previous, String retained, boolean unused) throws Exception {
      dossier = retained;
      var start = request(Map.of());
      start.put("previous", previous);
      setRequest(start);
      input.putNull("state");
      input.putNull("result");
      var todos = input.putArray("todos");
      for (String stage : new String[] {"inspect", "assess", "retain"})
        todos.addObject().put("id", stage).put("stageId", stage).put("status", "pending");
    }

    void setRequest(Map<String, ?> request) throws Exception {
      input.put(
          "message", JSON.writeValueAsString(Map.of("request", JSON.writeValueAsString(request))));
    }

    void next() throws Exception {
      input.put("sequence", sequence++);
      input.put(
          "requestId",
          UUID.nameUUIDFromBytes(("command-" + sequence).getBytes(StandardCharsets.UTF_8))
              .toString());
      var output = ScriptProgram.step(source, JSON.readTree(input.toString()));
      input.set("state", JSON.readTree(output.path("state").toString()));
      command = output.path("command");
    }

    void accept(String result) throws Exception {
      input.put("result", result);
      next();
    }

    void respond() throws Exception {
      var args = command.path("arguments");
      switch (command.path("tool").asText()) {
        case "todo_write" -> {
          var op = args.path("ops").get(0);
          for (var todo : input.path("todos"))
            if (todo.path("id").equals(op.path("id")))
              ((ObjectNode) todo).put("status", op.path("status").asText());
          accept("updated");
        }
        case "information_read" -> {
          if (args.path("operation").asText().equals("await")) {
            waits++;
            String revision = args.path("sources").get(0).path("revision").asText();
            accept(
                JSON.writeValueAsString(
                    Map.of(
                        "outcomes", List.of(Map.of("revision", revision, "state", extraction)))));
          } else {
            assertEquals(DOSSIER, args.path("revision").asText());
            int offset = args.path("offset").asInt();
            // A deployment may serve smaller windows than requested.
            int end =
                Math.min(dossier.length(), offset + Math.min(1000, args.path("limit").asInt()));
            reads++;
            accept(
                JSON.writeValueAsString(
                    Map.of(
                        "revision",
                        DOSSIER,
                        "start",
                        offset,
                        "end",
                        end,
                        "total",
                        dossier.length(),
                        "text",
                        dossier.substring(offset, end))));
          }
        }
        case "agent_run" -> {
          String agent = args.path("agent").asText();
          String task = args.path("task").asText();
          if (agent.equals("privacy_device_assessor")) {
            assessorTasks.add(task);
            accept(assertNotNullReply(assessor.poll(), agent));
          } else if (agent.equals("privacy_reviewer")) {
            reviewerTasks.add(task);
            accept(assertNotNullReply(reviewer.poll(), agent));
          } else fail("Unexpected agent " + agent);
        }
        case "information_write" -> {
          writes++;
          report = args.deepCopy();
          writeRequest = input.path("requestId").asText();
          accept(JSON.writeValueAsString(receipt));
        }
        default -> fail("Unexpected command: " + command);
      }
    }

    private static String assertNotNullReply(String reply, String agent) {
      assertNotNull(reply, "No scripted reply for " + agent);
      return reply;
    }

    void finish() throws Exception {
      next();
      for (int i = 0; i < 64; i++) {
        if (command.path("tool").asText().equals("orchestration_finish")) return;
        respond();
      }
      fail("Orchestration did not finish");
    }
  }
}
