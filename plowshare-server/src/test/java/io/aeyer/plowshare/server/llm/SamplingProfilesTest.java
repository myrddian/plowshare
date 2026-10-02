package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.Sampling.Intent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two artifacts a person owns: a mapping from wire model to profile, and a
 * profile per model family.
 *
 * <p><b>What is pinned here is the shape and the resolution order, plus the one
 * number the measurement forbids.</b> The values themselves belong to the files
 * and are argued there; what a test can own is that a wire model reaches the
 * profile a person pointed it at, that a family falls back to its family's file,
 * that a mode is read from the mapping rather than guessed, and that a model
 * nobody wrote a profile for sends nothing at all rather than something somebody
 * picked.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SamplingProfilesTest {

    /** No directory at all: the shipped profiles are still live. A person owns
     *  the directory; they do not have to create one to get a correct request. */
    private static SamplingProfiles shipped() {
        return SamplingProfiles.read(null);
    }

    @Test
    void a_shipped_family_resolves_to_its_vendors_own_numbers() {
        SamplingProfiles.Resolved resolved =
                shipped().resolve("google/gemma-4-26b-a4b", Intent.BALANCED);

        assertEquals("gemma", resolved.profile());
        assertEquals(OptionalDouble.of(1.0d), resolved.sampling().temperature());
        assertEquals(OptionalDouble.of(0.95d), resolved.sampling().topP());
        assertEquals(OptionalInt.of(64), resolved.sampling().topK());
    }

    /**
     * The constraint the measurement imposes, pinned so nobody "improves" it.
     *
     * <p>Gemma's own guidance mentions lowering temperature toward 0.2 for
     * deterministic tasks. 0.0 was <em>measured</em> on this project's own ingest
     * producing 3 997 reasoning tokens and empty content, deterministically, and
     * nothing between 0.0 and 1.0 has been tested at all. So {@code precise}
     * tightens with truncation, which narrows the distribution without the
     * degenerate-loop risk low temperature demonstrably carries on this family.
     */
    @Test
    void precise_never_lowers_the_temperature_below_what_the_vendor_recommends() {
        for (String model : new String[] {
                "google/gemma-4-26b-a4b", "gemma-3-27b-it", "qwen3.5-9b", "gpt-oss-20b"}) {
            SamplingProfiles profiles = shipped();
            OptionalDouble precise = profiles.resolve(model, Intent.PRECISE)
                    .sampling().temperature();
            OptionalDouble balanced = profiles.resolve(model, Intent.BALANCED)
                    .sampling().temperature();
            assertEquals(balanced, precise,
                    model + "'s precise entry moves the temperature away from the value its"
                            + " vendor recommends. Nothing between that value and zero has been"
                            + " measured on any model this project runs, and zero itself was"
                            + " measured returning empty content; precise tightens with top_p"
                            + " and top_k, never with temperature");
        }
    }

    @Test
    void precise_is_tighter_than_balanced_where_the_vendor_named_a_tighter_value() {
        SamplingProfiles profiles = shipped();
        assertTrue(profiles.resolve("gemma-3-27b-it", Intent.PRECISE).sampling().topP()
                        .getAsDouble()
                < profiles.resolve("gemma-3-27b-it", Intent.BALANCED).sampling().topP()
                        .getAsDouble(),
                "Gemma's guidance names a tighter top_p for deterministic tasks, so precise has"
                        + " somewhere to go that is not the temperature");
    }

    @Test
    void a_family_matches_by_prefix_so_a_new_version_needs_no_new_line() {
        SamplingProfiles profiles = shipped();
        assertEquals("gemma", profiles.resolve("gemma-3-27b-it", Intent.BALANCED).profile());
        assertEquals("gemma",
                profiles.resolve("google/gemma-4-26b-a4b", Intent.BALANCED).profile());
    }

    /**
     * §8.3: nothing in a request tells the server whether the model behind a
     * wire name is thinking, and the recommended temperature differs by a factor
     * of two, so it is declared in the mapping.
     */
    @Test
    void thinking_mode_is_read_from_the_mapping_and_never_inferred(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("models.yaml"), """
                models:
                  qwen3:
                    profile: qwen3
                    mode: non-thinking
                """);
        SamplingProfiles overridden = SamplingProfiles.read(dir);

        assertEquals("thinking", shipped().resolve("qwen3.5-9b", Intent.BALANCED).mode());
        assertEquals("non-thinking",
                overridden.resolve("qwen3.5-9b", Intent.BALANCED).mode());
        assertNotEquals(
                shipped().resolve("qwen3.5-9b", Intent.BALANCED).sampling().temperature(),
                overridden.resolve("qwen3.5-9b", Intent.BALANCED).sampling().temperature(),
                "the two modes are recommended a factor of two apart, so a mode read wrong is"
                        + " not a small error");
    }

    @Test
    void a_model_nobody_wrote_a_profile_for_sends_nothing_at_all() {
        SamplingProfiles.Resolved resolved =
                shipped().resolve("some-model-nobody-has-heard-of", Intent.PRECISE);

        assertNull(resolved.profile(), "DEFAULT is the absence of a profile, not a profile");
        assertEquals(Sampling.NONE, resolved.sampling(),
                "the safe default is silence: the endpoint resolves model defaults ->"
                        + " model.yaml -> load-time -> inference-time, so a request carrying"
                        + " nothing runs the model at its own vendor's numbers");
    }

    @Test
    void a_persons_own_file_replaces_a_shipped_profile_of_the_same_name(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("gemma.yaml"), """
                modes:
                  default:
                    precise:     {temperature: 0.55, top_k: 8}
                    balanced:    {temperature: 0.55, top_k: 8}
                    exploratory: {temperature: 0.55, top_k: 8}
                """);

        assertEquals(OptionalDouble.of(0.55d),
                SamplingProfiles.read(dir).resolve("gemma-3-27b-it", Intent.BALANCED)
                        .sampling().temperature());
    }

    @Test
    void a_person_adds_a_model_with_one_line_and_no_new_profile(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("models.yaml"), """
                models:
                  my-own-gemma-build:
                    profile: gemma
                """);

        assertEquals("gemma",
                SamplingProfiles.read(dir).resolve("my-own-gemma-build-v2", Intent.PRECISE)
                        .profile());
    }

    @Test
    void a_profile_that_omits_an_intent_is_refused_naming_the_file(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("gemma.yaml");
        Files.writeString(file, """
                modes:
                  default:
                    balanced: {temperature: 1.0}
                """);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> SamplingProfiles.read(dir));
        assertTrue(refused.getMessage().contains(file.toString()), refused.getMessage());
        assertTrue(refused.getMessage().contains("precise"), refused.getMessage());
    }

    @Test
    void a_mapping_pointing_at_a_profile_nobody_wrote_is_refused(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("models.yaml"), """
                models:
                  llama:
                    profile: llama
                """);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> SamplingProfiles.read(dir));
        assertTrue(refused.getMessage().contains("llama"), refused.getMessage());
    }

    @Test
    void an_unknown_reasoning_effort_is_refused_rather_than_sent(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("gemma.yaml"), """
                modes:
                  default:
                    precise:     {reasoning_effort: enormous}
                    balanced:    {temperature: 1.0}
                    exploratory: {temperature: 1.0}
                """);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> SamplingProfiles.read(dir));
        assertTrue(refused.getMessage().contains("enormous"), refused.getMessage());
    }

    /** JSON is YAML, so both spellings the design offers a person actually work. */
    @Test
    void a_profile_may_be_written_as_json(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("gemma.json"), """
                {"modes": {"default": {
                  "precise":     {"temperature": 0.75},
                  "balanced":    {"temperature": 0.75},
                  "exploratory": {"temperature": 0.75}}}}
                """);

        assertEquals(OptionalDouble.of(0.75d),
                SamplingProfiles.read(dir).resolve("gemma-3-27b-it", Intent.PRECISE)
                        .sampling().temperature());
    }
}
