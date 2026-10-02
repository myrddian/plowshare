package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.CallerOrchestrationTools.Actions;
import io.aeyer.plowshare.server.agents.CallerOrchestrationTools.Offer;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one named {@code orchestrate_<name>} tool a grant offers, as a model reads its schema and
 * calls it: {@code wait} is optional, defaults through to a fake {@link Actions}, and a bad value
 * is refused by its own sentence rather than reaching the call.
 */
class CallerOrchestrationToolsTest {

    private static final Home home = Home.global();
    private static final Offer OFFER =
            new Offer("code_implementation", "Takes a change.", List.of("goal", "code"));

    @Test
    void the_start_tool_takes_an_optional_wait_flag() {
        AgentTool start = startTool(new FakeActions());

        ToolSchema schema = start.schema();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) schema.parameters().get("properties");
        assertTrue(properties.containsKey("wait"), properties.keySet().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> wait = (Map<String, Object>) properties.get("wait");
        assertEquals("boolean", wait.get("type"));
        assertEquals(List.of("request"), schema.parameters().get("required"));
        assertTrue(schema.description().endsWith("Returns its handle at once."),
                schema.description());
    }

    @Test
    void a_non_boolean_wait_is_refused_by_its_message() {
        FakeActions actions = new FakeActions();
        AgentTool start = startTool(actions);

        String result = start.run("{\"request\": \"do it\", \"wait\": \"soon\"}", home);

        assertEquals("orchestrate_code_implementation needs 'wait', when present, to be true or"
                + " false; it was \"soon\".", result);
        assertTrue(actions.starts.isEmpty());
    }

    @Test
    void wait_defaults_to_true_when_omitted() {
        FakeActions actions = new FakeActions();
        AgentTool start = startTool(actions);

        start.run("{\"request\": \"do it\"}", home);

        assertEquals(List.of(new FakeActions.StartCall("code_implementation", "do it", null,
                true)), actions.starts);
    }

    @Test
    void wait_false_reaches_the_actions() {
        FakeActions actions = new FakeActions();
        AgentTool start = startTool(actions);

        start.run("{\"request\": \"do it\", \"wait\": false}", home);

        assertEquals(List.of(new FakeActions.StartCall("code_implementation", "do it", null,
                false)), actions.starts);
    }

    private static AgentTool startTool(Actions actions) {
        for (AgentTool candidate : CallerOrchestrationTools.forRun(List.of(OFFER), actions)) {
            if (candidate.schema().name().equals("orchestrate_code_implementation")) {
                return candidate;
            }
        }
        throw new AssertionError("no start tool found");
    }

    /** Records every {@code start} call it saw. */
    private static final class FakeActions implements Actions {

        record StartCall(String name, String request, String context, boolean shouldWait) {}

        final List<StartCall> starts = new ArrayList<>();

        @Override
        public String start(String name, String request, String context, boolean wait) {
            starts.add(new StartCall(name, request, context, wait));
            return "{}";
        }

        @Override
        public String answer(String id, String answer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String status(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String cancel(String id) {
            throw new UnsupportedOperationException();
        }
    }
}
