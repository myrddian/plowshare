package io.aeyer.plowshare.integrations.privacy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Exercises journaled Application commands through the real sandbox without models or a database.
 */
class NetworkPrivacyInvestigationTest {
  private static final String CURRENT = "11111111-1111-4111-8111-111111111111";
  private static final String BASELINE = "22222222-2222-4222-8222-222222222222";
  private static final String SCAN = "33333333-3333-4333-8333-333333333333";
  private static final String REPORT = "44444444-4444-4444-8444-444444444444";
  private static final String RESOURCE = "55555555-5555-4555-8555-555555555555";
  private static final String ANALYSIS =
      "## Facts\nActual analyst response: \"timeout\", not closed.\n\nGap: DNS unavailable.";
  private static final String CORRECTION =
      "## Facts\nTCP timeout; no claim of a closed port or exfiltration.\n\nGap: DNS unavailable.";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void actual_assessment_and_complete_sources_cross_the_review_boundary_and_form_one_draft()
      throws Exception {
    var host = new Host();
    host.advanceTo("privacy_reviewer");
    var payload =
        JSON.readTree(
            host.command.path("arguments").path("task").asText().split("\\nDATA:\\n", 2)[1]);
    assertEquals(ANALYSIS, payload.path("assessment").asText());
    assertEquals(2, payload.path("sources").size());
    for (var source : payload.path("sources")) {
      assertEquals(
          host.sources.get(source.path("revision").asText()), source.path("text").asText());
    }
    assertTrue(host.reads > 2, "Full source windows must be assembled before delegation");
    host.accept(
        JSON.writeValueAsString(
            Map.of(
                "verdict",
                "needs_corrections",
                "assessment",
                CORRECTION,
                "notes",
                "Preserve timeout and DNS coverage gap.")));
    host.finish();
    assertEquals(1, host.writes);
    assertEquals(host.writeRequest, host.report.path("requestId").asText());
    assertEquals(2, host.report.path("inputs").size());
    assertEquals(CURRENT, host.report.path("inputs").get(0).asText());
    assertEquals(BASELINE, host.report.path("inputs").get(1).asText());
    assertTrue(host.report.path("text").asText().contains(CORRECTION));
    assertTrue(host.command.path("arguments").path("result").asText().contains(REPORT));
    for (var todo : host.input.path("todos")) assertEquals("done", todo.path("status").asText());
  }

  @Test
  void unchecked_review_is_retained_explicitly_as_unverified() throws Exception {
    var host = new Host();
    host.advanceTo("privacy_reviewer");
    host.accept(
        "{\"verdict\":\"not_checked\",\"assessment\":\"\",\"notes\":\"Unable to verify supplied evidence.\"}");
    host.finish();
    String report = host.report.path("text").asText();
    assertTrue(report.contains("Unreviewed assessment"));
    assertTrue(report.contains(ANALYSIS));
    assertTrue(report.contains("assessment remains unverified"));
  }

  @Test
  void absent_assessment_stops_before_review_or_report() throws Exception {
    var host = new Host();
    host.advanceTo("privacy_analyst");
    assertThrows(IllegalStateException.class, () -> host.accept(" \n "));
    assertEquals(0, host.writes);
  }

  @Test
  void host_refusals_and_invalid_reviews_do_not_become_successful_assessments() throws Exception {
    for (String response :
        new String[] {
          "E_NO_ACCESS: permission withdrawn",
          "E_NO_CONNECTION: provider unavailable",
          "E_NO_EXEC: tool unavailable",
          "E_GENERAL_TOOL_FAILURE: failure",
          "Information request refused: no access",
          "not JSON",
          "{\"verdict\":\"accepted\",\"notes\":\"ok\",\"assessment\":\"\"}",
          "{\"verdict\":\"guessed\",\"notes\":\"ok\",\"assessment\":\"text\"}"
        }) {
      var host = new Host();
      host.advanceTo("privacy_reviewer");
      assertThrows(IllegalStateException.class, () -> host.accept(response), response);
      assertEquals(0, host.writes);
    }
  }

  @Test
  void failed_or_wrong_source_readiness_prevents_delegation() throws Exception {
    for (String response :
        new String[] {
          "{\"outcomes\":[{\"revision\":\"" + CURRENT + "\",\"state\":\"failed\"}]}",
          "{\"outcomes\":[{\"revision\":\"" + BASELINE + "\",\"state\":\"ready\"}]}"
        }) {
      var host = new Host();
      host.next();
      host.ackTodo();
      assertThrows(IllegalStateException.class, () -> host.accept(response));
      assertEquals(0, host.writes);
      assertEquals(0, host.delegations);
    }
  }

  @Test
  void pending_extraction_has_a_bound_and_does_not_trigger_models() throws Exception {
    var host = new Host();
    host.next();
    host.ackTodo();
    String pending = "{\"outcomes\":[{\"revision\":\"" + CURRENT + "\",\"state\":\"pending\"}]}";
    for (int i = 0; i < 7; i++) host.accept(pending);
    assertThrows(IllegalStateException.class, () -> host.accept(pending));
    assertEquals(0, host.delegations);
    assertEquals(0, host.writes);
  }

  @Test
  void inconsistent_read_windows_cannot_supply_evidence_to_agents() throws Exception {
    var host = new Host();
    host.next();
    host.ackTodo();
    host.respond();
    assertEquals("read", host.command.path("arguments").path("operation").asText());
    assertThrows(
        IllegalStateException.class,
        () ->
            host.accept(
                "{\"revision\":\""
                    + CURRENT
                    + "\",\"start\":0,\"end\":3,\"total\":3,\"text\":\"{}\"}"));
    assertEquals(0, host.delegations);
  }

  @Test
  void todo_status_and_report_receipt_are_authoritative() throws Exception {
    var denied = new Host();
    denied.next();
    assertThrows(IllegalStateException.class, () -> denied.accept("updated"));
    var host = new Host();
    host.advanceTo("privacy_reviewer");
    host.accept(
        "{\"verdict\":\"accepted\",\"assessment\":\"Supported assessment\",\"notes\":\"Evidence reviewed\"}");
    while (!host.command.path("tool").asText().equals("information_write")) host.respond();
    assertThrows(
        IllegalStateException.class,
        () -> host.accept("{\"revision\":\"invalid\",\"resource\":\"" + RESOURCE + "\"}"));
    assertFalse(host.command.path("tool").asText().equals("orchestration_finish"));
  }

  /** A minimal host oracle: each step round-trips state just as a durable restart would. */
  private static final class Host {
    final String source =
        Files.readString(
            Path.of(System.getProperty("privacy.application"))
                .resolve("orchestrations/investigate_network.js"));
    final ObjectNode input = JSON.createObjectNode();
    final Map<String, String> sources = new LinkedHashMap<>();
    JsonNode command;
    JsonNode report;
    String writeRequest;
    int sequence;
    int reads;
    int writes;
    int delegations;

    Host() throws Exception {
      sources.put(
          CURRENT,
          JSON.writeValueAsString(
              Map.of(
                  "scan_id",
                  SCAN,
                  "collector",
                  "test-collector",
                  "mode",
                  "tcp",
                  "previous_revision",
                  BASELINE,
                  "padding",
                  "retained 😀 evidence ".repeat(600))));
      sources.put(
          BASELINE,
          JSON.writeValueAsString(
              Map.of(
                  "collector",
                  "test-collector",
                  "mode",
                  "tcp",
                  "padding",
                  "baseline evidence ".repeat(600))));
      String completion =
          JSON.writeValueAsString(
              Map.of(
                  "version",
                  1,
                  "scan_id",
                  SCAN,
                  "revision",
                  CURRENT,
                  "collector",
                  "test-collector",
                  "mode",
                  "tcp",
                  "changes",
                  java.util.List.of(),
                  "issues",
                  java.util.List.of("DNS unavailable")));
      String request =
          JSON.writeValueAsString(Map.of("payload", Map.of("kind", "TEXT", "text", completion)));
      input.put("message", JSON.writeValueAsString(Map.of("request", request)));
      input.putNull("state");
      input.putNull("result");
      var todos = input.putArray("todos");
      for (String stage : new String[] {"inspect", "assess", "retain"})
        todos.addObject().put("id", stage).put("stageId", stage).put("status", "pending");
    }

    void next() throws Exception {
      input.put("sequence", sequence++);
      input.put(
          "requestId",
          UUID.nameUUIDFromBytes(
                  ("command-" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString());
      var output = ScriptProgram.step(source, JSON.readTree(input.toString()));
      input.set("state", JSON.readTree(output.path("state").toString()));
      command = output.path("command");
    }

    void accept(String result) throws Exception {
      input.put("result", result);
      next();
    }

    void ackTodo() throws Exception {
      var op = command.path("arguments").path("ops").get(0);
      for (var todo : input.path("todos"))
        if (todo.path("id").equals(op.path("id")))
          ((ObjectNode) todo).put("status", op.path("status").asText());
      accept("updated");
    }

    void respond() throws Exception {
      var args = command.path("arguments");
      switch (command.path("tool").asText()) {
        case "todo_write" -> ackTodo();
        case "information_read" -> {
          if (args.path("operation").asText().equals("await")) {
            String revision = args.path("sources").get(0).path("revision").asText();
            accept(
                JSON.writeValueAsString(
                    Map.of(
                        "outcomes",
                        java.util.List.of(Map.of("revision", revision, "state", "ready")))));
          } else {
            String revision = args.path("revision").asText();
            String text = sources.get(revision);
            int offset = args.path("offset").asInt();
            int end = Math.min(text.length(), offset + args.path("limit").asInt());
            reads++;
            accept(
                JSON.writeValueAsString(
                    Map.of(
                        "revision",
                        revision,
                        "start",
                        offset,
                        "end",
                        end,
                        "total",
                        text.length(),
                        "text",
                        text.substring(offset, end))));
          }
        }
        case "agent_run" -> {
          delegations++;
          assertEquals("privacy_analyst", args.path("agent").asText());
          accept(ANALYSIS);
        }
        case "information_write" -> {
          writes++;
          report = args.deepCopy();
          writeRequest = input.path("requestId").asText();
          accept(
              JSON.writeValueAsString(
                  Map.of("revision", REPORT, "resource", RESOURCE, "created", true)));
        }
        default -> fail("Unexpected command: " + command);
      }
    }

    void advanceTo(String agent) throws Exception {
      next();
      for (int i = 0; i < 40; i++) {
        if (command.path("tool").asText().equals("agent_run")
            && command.path("arguments").path("agent").asText().equals(agent)) return;
        respond();
      }
      fail("Delegate was not reached");
    }

    void finish() throws Exception {
      for (int i = 0; i < 20; i++) {
        if (command.path("tool").asText().equals("orchestration_finish")) return;
        respond();
      }
      fail("Orchestration did not finish");
    }
  }
}
