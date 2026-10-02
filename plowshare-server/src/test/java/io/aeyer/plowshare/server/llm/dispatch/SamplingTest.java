package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.dispatch.Sampling.Effort;
import io.aeyer.plowshare.server.llm.dispatch.Sampling.Parameter;
import java.util.EnumSet;
import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The thing an agent's intent resolves to, and the thing a transport filters.
 *
 * <p><b>Every field is absent-able, and that is the whole point.</b> Stage 6
 * chose a primitive {@code double} for temperature and recorded the exact
 * condition for revisiting it — a second source of a default. A profile is that
 * second source, so {@code no temperature} and {@code temperature 0.0} have to
 * be two different requests: the first sends no {@code temperature} key at all
 * and lets the model's own {@code model.yaml} apply, and the second sends the
 * one value the measured runaway came from.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SamplingTest {

    @Test
    void nothing_declared_is_not_the_same_as_zero_declared() {
        assertTrue(Sampling.NONE.isEmpty());
        assertEquals(OptionalDouble.empty(), Sampling.NONE.temperature());

        Sampling greedy = Sampling.NONE.withTemperature(0.0d);
        assertFalse(greedy.isEmpty(), "temperature 0.0 is a declaration and not a silence");
        assertEquals(OptionalDouble.of(0.0d), greedy.temperature());
    }

    @Test
    void a_later_layer_overrides_only_what_it_states() {
        Sampling profile = Sampling.NONE.withTemperature(1.0d).withTopP(0.95d).withTopK(64);
        Sampling agent = Sampling.NONE.withTemperature(0.2d);

        Sampling resolved = profile.overriddenBy(agent);

        assertEquals(OptionalDouble.of(0.2d), resolved.temperature());
        assertEquals(OptionalDouble.of(0.95d), resolved.topP(),
                "an override that named only a temperature must not erase the profile's"
                        + " truncation, or a per-agent temperature would silently become a"
                        + " different configuration");
        assertEquals(64, resolved.topK().orElseThrow());
    }

    @Test
    void what_a_transport_cannot_carry_is_dropped_and_reported() {
        Sampling full = Sampling.NONE
                .withTemperature(1.0d)
                .withTopP(0.95d)
                .withTopK(64)
                .withMaxTokens(30_000)
                .withReasoningEffort(Effort.LOW);

        Sampling carried = full.carriedBy(EnumSet.of(Parameter.TEMPERATURE, Parameter.TOP_P));

        assertEquals(EnumSet.of(Parameter.TEMPERATURE, Parameter.TOP_P), carried.present());
        assertEquals(EnumSet.of(Parameter.TOP_K, Parameter.MAX_TOKENS,
                        Parameter.REASONING_EFFORT),
                full.notCarriedBy(EnumSet.of(Parameter.TEMPERATURE, Parameter.TOP_P)),
                "a dropped parameter has to be nameable, because the contract is that it is"
                        + " dropped with a note rather than silently");
    }

    @Test
    void a_value_json_cannot_encode_is_refused_where_it_was_written() {
        assertThrows(IllegalArgumentException.class,
                () -> Sampling.NONE.withTemperature(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> Sampling.NONE.withTopP(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Sampling.NONE.withTopK(0));
        assertThrows(IllegalArgumentException.class, () -> Sampling.NONE.withMaxTokens(-1));
    }

    /** Three levels and no fourth; §8.1 settled that in as many words. */
    @Test
    void the_intent_vocabulary_is_closed_at_three() {
        assertEquals(3, Sampling.Intent.values().length);
        assertEquals(Sampling.Intent.BALANCED, Sampling.Intent.DEFAULT);
    }
}
