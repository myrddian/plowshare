package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Projection;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery;
import io.aeyer.plowshare.server.llm.ModelOutputRecoveryCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Actual sandbox and projection boundaries, with no database or inference. */
class ModelOutputRecoveryScriptTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SOURCE =
      """
      export const manifest = {};
      export function step(input) {
        return modelOutput.recover(input.result, input.contract, input.checkpoint || null);
      }
      """;
  private final List<ModelOutputRecovery.Diagnostic> diagnostics = new ArrayList<>();

  private JsonNode input(String raw) throws Exception {
    return JSON.createObjectNode()
        .put("result", raw)
        .set(
            "contract",
            JSON.readTree(
                """
        {"name":"fixture","worker":"summary_worker","fields":{
          "name":{"words":3,"required":true},"notes":{"words":4,"required":false}}}
        """));
  }

  @Test
  void summarisation_is_an_ordinary_journalable_command_with_a_retained_checkpoint()
      throws Exception {
    JsonNode first =
        ScriptProgram.step(
            SOURCE,
            input(
                "{\"name\":\"Fixture\",\"notes\":\"Possibly an unknown device in this network\"}"),
            diagnostics::add);
    assertEquals("SUMMARISE", first.path("action").asText());
    var command = (ScriptStore.Tool) ScriptCommands.read(first.get("command"));
    assertEquals("agent_run", command.name());
    assertTrue(command.outputRecovery());
    assertTrue(JSON.readTree(command.arguments()).path("recovery").asBoolean());
    assertEquals("summary_worker", JSON.readTree(command.arguments()).path("agent").asText());
    JsonNode next = input("{\"notes\":\"Possibly an unknown device\"}");
    ((ObjectNode) next).set("checkpoint", first.get("checkpoint"));
    JsonNode result = ScriptProgram.step(SOURCE, next, diagnostics::add);
    assertEquals("ACCEPT", result.path("action").asText());
    assertEquals("Fixture", result.path("value").path("name").asText());
    assertEquals(2, diagnostics.size());
    assertEquals(diagnostics.getFirst().originalHash(), diagnostics.getLast().originalHash());
  }

  @Test
  void diagnostic_decisions_are_readable_in_the_log_but_never_projected() throws Exception {
    ScriptProgram.step(
        SOURCE, input("{\"name\":\"Fixture\",\"notes\":\"Unconfirmed\"}"), diagnostics::add);
    String encoded =
        ModelOutputRecoveryCodec.diagnostic(
            diagnostics.getFirst(), "cnv_fixture", 2, "sha256:fixture");
    LoggedEntry logged = LoggedEntry.diagnostic(encoded);
    JsonNode decision = JSON.readTree(logged.content());
    assertEquals("model_output_recovery", decision.path("type").asText());
    assertEquals("ACCEPT", decision.path("action").asText());
    assertEquals(1, decision.path("previousCommand").asInt());
    assertFalse(decision.has("sourceCommand"));
    assertEquals(EntryKind.DIAGNOSTIC, logged.kind());
    assertFalse(logged.kind().projects());
    var record =
        new EntryRecord(
            "cnv_fixture",
            1,
            logged.kind(),
            logged.content(),
            null,
            List.of(),
            null,
            null,
            1,
            null,
            null,
            null,
            null);
    for (boolean canList : List.of(false, true))
      assertTrue(Projection.of(List.of(record), canList).isEmpty());
    assertFalse(encoded.contains("\"notes\":\"Unconfirmed\""));
  }

  @Test
  void a_later_script_exception_does_not_erase_the_validation_decision() throws Exception {
    String source =
        SOURCE
            .replace("return modelOutput.recover", "const plan = modelOutput.recover")
            .replace(
                "input.checkpoint || null);",
                "input.checkpoint || null); throw new Error('later failure');");
    assertThrows(
        IllegalStateException.class,
        () ->
            ScriptProgram.step(
                source, input("{\"name\":\"Fixture\",\"notes\":\"\"}"), diagnostics::add));
    assertEquals(1, diagnostics.size());
    assertEquals(ModelOutputRecovery.Action.ACCEPT, diagnostics.getFirst().action());
  }

  @Test
  void the_host_observer_never_crosses_into_script_input_or_checkpoint_json() throws Exception {
    var input =
        new ScriptStore.Input(
            "cnv_fixture",
            "request",
            0,
            UUID.randomUUID(),
            null,
            null,
            List.of(),
            ScriptProgram.sourceHash(SOURCE),
            diagnostics::add);
    JsonNode encoded = JSON.valueToTree(input);
    assertFalse(encoded.has("recoveryDiagnostics"));
    JsonNode invalid = this.input("{}");
    ((ObjectNode) invalid.get("contract")).put("worker", "../../other-account");
    assertThrows(
        IllegalStateException.class, () -> ScriptProgram.step(SOURCE, invalid, diagnostics::add));
  }

  @Test
  void an_interrupted_recovery_call_is_not_replayed_without_a_positive_receipt() throws Exception {
    JsonNode first =
        ScriptProgram.step(
            SOURCE,
            input(
                "{\"name\":\"Fixture\",\"notes\":\"Possibly an unknown device in this network\"}"));
    var command = (ScriptStore.Tool) ScriptCommands.read(first.get("command"));
    var store = mock(ScriptStore.class);
    when(store.latest("cnv_fixture"))
        .thenReturn(
            Optional.of(
                new ScriptStore.Step(
                    2,
                    ScriptProgram.sourceHash(SOURCE),
                    command,
                    null,
                    null,
                    true,
                    command.arguments())));
    doCallRealMethod().when(store).requireRecoverable("cnv_fixture");
    assertThrows(CallerFault.class, () -> store.requireRecoverable("cnv_fixture"));
    assertTrue(store.delegateResult("cnv_fixture", 2).isEmpty());
  }

  @Test
  void legacy_script_json_recovery_also_emits_a_non_semantic_diagnostic() throws Exception {
    String source =
        "export const manifest={}; export function step(input){return llmJson.parse(input.result);}";
    ScriptProgram.step(
        source,
        JSON.createObjectNode().put("result", "```json\n{\"name\":\"Fixture\"}\n```"),
        diagnostics::add);
    assertEquals(1, diagnostics.size());
    assertEquals(ModelOutputRecovery.Action.PARSED, diagnostics.getFirst().action());
    assertEquals("json-syntax", diagnostics.getFirst().contract());
    assertTrue(diagnostics.getFirst().reason().contains("domain validation"));
  }
}
