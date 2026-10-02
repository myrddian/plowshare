package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The copyable documentation example must load and run through the actual JSON sandbox. */
class ScriptDocumentationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private String source() throws Exception {
        return Files.readString(Path.of("../docs/examples/scripted-orchestrations/catalogue_inventory.js"));
    }

    private ObjectNode input(JsonNode state, String result, String status) {
        var input = JSON.createObjectNode();
        input.set("state", state);
        input.put("result", result);
        input.put("message", "{\"request\":\"Inspect the catalogue\",\"context\":\"\"}");
        input.put("run", "cnv_documentation");
        input.put("sequence", 0);
        input.put("requestId", "00000000-0000-0000-0000-000000000001");
        input.set("todos", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("id", "actual_todo_id").put("stageId", "inspect").put("status", status)));
        return input;
    }

    @Test
    void complete_example_loads_and_preserves_catalogue_data_across_fresh_contexts() throws Exception {
        String source = source();
        var definition = OrchestrationRegistry.parsePinned("catalogue_inventory", "example.js", source,
                Set.of("information_read"), OrchestrationDefinition.Tier.PROJECT);
        assertEquals(List.of("information_read"), definition.conductor().tools());
        assertEquals("inspect", definition.stages().getFirst().id());

        JsonNode entered = ScriptProgram.step(source, input(JSON.nullNode(), null, "PENDING"));
        assertEquals("todo_write", entered.path("command").path("tool").asText());
        assertEquals("actual_todo_id", entered.at("/command/arguments/ops/0/id").asText());
        assertEquals("in_progress", entered.at("/command/arguments/ops/0/status").asText());

        JsonNode read = ScriptProgram.step(source, input(entered.path("state"), "Updated todo.", "IN_PROGRESS"));
        assertEquals("information_read", read.at("/command/tool").asText());
        assertEquals("list", read.at("/command/arguments/operation").asText());
        assertEquals(20, read.at("/command/arguments/limit").asInt());

        String page = "[{\"id\":\"00000000-0000-0000-0000-000000000009\",\"source_name\":\"Retained α 🧭\"}]";
        JsonNode exit = ScriptProgram.step(source, input(read.path("state"), page, "IN_PROGRESS"));
        assertEquals("done", exit.at("/command/arguments/ops/0/status").asText());
        JsonNode finish = ScriptProgram.step(source, input(exit.path("state"), "Updated todo.", "DONE"));
        assertEquals("orchestration_finish", finish.at("/command/tool").asText());
        assertTrue(finish.at("/command/arguments/result").asText().contains("Retained α 🧭"));
        assertTrue(finish.at("/command/arguments/result").asText().contains("00000000-0000-0000-0000-000000000009"));
    }

    @Test
    void information_refusal_cannot_be_reported_as_an_empty_success() throws Exception {
        String source = source();
        JsonNode entered = ScriptProgram.step(source, input(JSON.nullNode(), null, "PENDING"));
        JsonNode read = ScriptProgram.step(source, input(entered.path("state"), "Updated todo.", "IN_PROGRESS"));
        var failure = assertThrows(IllegalStateException.class, () -> ScriptProgram.step(source,
                input(read.path("state"), "Information request refused: source withdrawn", "IN_PROGRESS")));
        assertTrue(failure.getMessage().contains("inspect the retained refusal"));
    }

    @Test
    void prose_cannot_override_an_actual_stage_refusal() throws Exception {
        String source = source();
        JsonNode entered = ScriptProgram.step(source, input(JSON.nullNode(), null, "PENDING"));
        var failure = assertThrows(IllegalStateException.class, () -> ScriptProgram.step(source,
                input(entered.path("state"), "Everything completed successfully.", "PENDING")));
        assertTrue(failure.getMessage().contains("Stage entry was refused"));
    }
}
