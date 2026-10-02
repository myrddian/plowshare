package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.SamplingProfiles;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** What boot makes of a {@code fallback:} against the pools this server has. */
class FallbackWiringTest {

    private static final AgentDefinition OSINT = new AgentDefinition(
            "osint", "d", "big", List.of(), List.of(), List.of(), 4, 8, "You research.")
            .fallback(new AgentDefinition.Fallback(
                    Set.of(AgentDefinition.Fallback.Trigger.REFUSAL), "low_refusal_osint", 1,
                    Sampling.NONE));

    @Test
    void no_low_refusal_model_means_the_agent_runs_as_though_it_declared_no_fallback() {
        LlmDispatcher onlyThePrimary = new LlmDispatcher(List.of(
                pool("vllm", "gpt-oss-120b", Map.of("big", "gpt-oss-120b"))),
                new NoOpTokenLedger());

        AgentRegistry.Loaded wired = AgentsConfig.sampled(loaded(OSINT), onlyThePrimary,
                SamplingProfiles.read(null));

        assertTrue(wired.enabled().containsKey("osint"),
                "a missing remedy is not a fault in the agent: it stays enabled");
        assertEquals(AgentDefinition.Fallback.NONE, wired.enabled().get("osint").fallback(),
                "and its refusals are its answers, exactly as with no fallback declared");
    }

    @Test
    void a_served_low_refusal_model_keeps_the_fallback() {
        LlmDispatcher both = new LlmDispatcher(List.of(
                pool("vllm", "gpt-oss-120b", Map.of("big", "gpt-oss-120b")),
                pool("spark", "small-osint", Map.of("low_refusal_osint", "small-osint"))),
                new NoOpTokenLedger());

        AgentRegistry.Loaded wired = AgentsConfig.sampled(loaded(OSINT), both,
                SamplingProfiles.read(null));

        AgentDefinition.Fallback fallback = wired.enabled().get("osint").fallback();
        assertEquals("low_refusal_osint", fallback.model());
        assertTrue(fallback.permits(AgentDefinition.Fallback.Trigger.REFUSAL, 0));
    }

    private static AgentRegistry.Loaded loaded(AgentDefinition definition) {
        return new AgentRegistry.Loaded(Map.of(definition.name(), definition), Map.of(),
                Map.of(), Map.of());
    }

    private static LlmPool pool(String name, String wireModel, Map<String, String> classes) {
        return new LlmPool(name, List.of(wireModel), classes, 1, 1, Duration.ofSeconds(1),
                new LlmTransport() {
                    @Override
                    public OptionalInt contextLength(String model) {
                        return OptionalInt.empty();
                    }

                    @Override
                    public String poolName() {
                        return name;
                    }

                    @Override
                    public void close() {
                    }

                    @Override
                    public Embeddings embed(String model, List<String> input) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Completion stream(String model, List<ChatMessage> messages,
                            Sampling sampling, List<ToolSchema> tools, Deltas sink,
                            BooleanSupplier abandoned) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Completion complete(String model, List<ChatMessage> messages,
                            Sampling sampling, List<ToolSchema> tools) {
                        throw new UnsupportedOperationException();
                    }
                });
    }
}
