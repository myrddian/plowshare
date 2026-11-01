package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Disk configuration acceptance without a database, model endpoint or rebuilt server resources. */
class ModelMediaProfilesTest {
  private static final String MODES =
      """
      modes:
        default:
          precise: {temperature: 0.6}
          balanced: {temperature: 0.7}
          exploratory: {}
      """;

  private static final String MEDIA =
      """
      media:
        inputs: [text/plain, IMAGE/PNG, audio/wav, video/mp4]
        outputs:
          text: {formats: [text/plain]}
          image: {formats: [image/png]}
          mixed: {formats: [text/plain, image/png]}
      """;

  @TempDir Path directory;

  @Test
  void disk_profiles_resolve_sampling_and_independent_input_output_combinations()
      throws IOException {
    write("version: 2\n" + MODES + MEDIA);
    var resolved =
        SamplingProfiles.read(directory).resolve("vendor/DEPLOYED-v1", Sampling.Intent.PRECISE);
    var media = resolved.media().orElseThrow();

    assertEquals("external", resolved.profile());
    assertEquals("default", resolved.mode());
    assertEquals(0.6, resolved.sampling().temperature().orElseThrow());
    assertEquals(Set.of("text/plain", "image/png", "audio/wav", "video/mp4"), media.inputs());
    assertEquals(Set.of("text/plain"), media.outputs().get("text").formats());
    assertEquals(Set.of("image/png"), media.outputs().get("image").formats());
    assertEquals(Set.of("text/plain", "image/png"), media.outputs().get("mixed").formats());
  }

  @Test
  void image_only_outputs_do_not_require_text_or_native_image_inputs() throws IOException {
    write(
        "version: 2\n"
            + MODES
            + "media: {inputs: [text/plain], outputs: {image: {formats: [image/png]}}}\n");
    var media =
        SamplingProfiles.read(directory)
            .resolve("deployed", Sampling.Intent.BALANCED)
            .media()
            .orElseThrow();
    assertEquals(Set.of("text/plain"), media.inputs());
    assertEquals(Set.of("image"), media.outputs().keySet());
    assertEquals(Set.of("image/png"), media.outputs().get("image").formats());
  }

  @Test
  void legacy_and_unmapped_profiles_have_no_inferred_media_capabilities() throws IOException {
    write(MODES);
    var profiles = SamplingProfiles.read(directory);
    assertTrue(profiles.resolve("deployed", Sampling.Intent.BALANCED).media().isEmpty());
    assertTrue(profiles.resolve("gemma-3", Sampling.Intent.BALANCED).media().isEmpty());
    assertTrue(profiles.resolve("unknown", Sampling.Intent.BALANCED).media().isEmpty());
    assertFalse(profiles.resolve("unknown", Sampling.Intent.BALANCED).matched());
  }

  @Test
  void an_external_replacement_drops_prior_media_and_loaded_snapshots_are_immutable()
      throws IOException {
    write("version: 2\n" + MODES + MEDIA);
    var first = SamplingProfiles.read(directory);
    write(MODES);
    var second = SamplingProfiles.read(directory);
    assertTrue(first.resolve("deployed", Sampling.Intent.BALANCED).media().isPresent());
    assertTrue(second.resolve("deployed", Sampling.Intent.BALANCED).media().isEmpty());
    assertTrue(second.resolve("qwen3.5", Sampling.Intent.BALANCED).matched());
  }

  @Test
  void exact_mapping_beats_the_family_and_json_profiles_are_discovered() throws IOException {
    write("version: 2\n" + MODES + MEDIA);
    Files.writeString(
        directory.resolve("only-text.json"),
        """
        {"version":2,"modes":{"default":{"precise":{},"balanced":{},"exploratory":{}}},
         "media":{"inputs":["text/plain"],"outputs":{"text":{"formats":["text/plain"]}}}}
        """);
    Files.writeString(
        directory.resolve("models.yaml"),
        "models: {deployed: {profile: external}, deployed-special: {profile: only-text}}\n");
    var profiles = SamplingProfiles.read(directory);
    assertEquals(
        "only-text", profiles.resolve("deployed-special", Sampling.Intent.BALANCED).profile());
    assertEquals(
        Set.of("text/plain"),
        profiles
            .resolve("deployed-special", Sampling.Intent.BALANCED)
            .media()
            .orElseThrow()
            .inputs());
    assertEquals(
        "external", profiles.resolve("deployed-other", Sampling.Intent.BALANCED).profile());
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "3", "2.0", "'2'", "null", "true"})
  void unsupported_or_coerced_versions_fail_at_load(String version) throws IOException {
    refuses("version: " + version + "\n" + MODES + MEDIA);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "media: null",
        "media: {inputs: [], outputs: {text: {formats: [text/plain]}}}",
        "media: {inputs: [image/*], outputs: {text: {formats: [text/plain]}}}",
        "media: {inputs: ['image/png; x=1'], outputs: {text: {formats: [text/plain]}}}",
        "media: {inputs: [not-a-mime], outputs: {text: {formats: [text/plain]}}}",
        "media: {inputs: [image/png, IMAGE/PNG], outputs: {text: {formats: [text/plain]}}}",
        "media: {inputs: [42], outputs: {text: {formats: [text/plain]}}}",
        "media: {inputs: [text/plain], outputs: {}}",
        "media: {inputs: [text/plain], outputs: {text: {formats: []}}}",
        "media: {inputs: [text/plain], outputs: {text: {formats: [text/plain], extra: true}}}",
        "media: {inputs: [text/plain], outputs: {Bad: {formats: [text/plain]}}}",
        "media: {inputs: [text/plain], outputs: {text: {formats: [text/plain]}}, extra: true}",
        "media: {inputs: [text/plain], inputs: [image/png], outputs: {text: {formats: [text/plain]}}}"
      })
  void invalid_media_is_refused_before_resolution(String media) throws IOException {
    refuses("version: 2\n" + MODES + media + "\n");
  }

  @Test
  void unknown_version_two_fields_and_intents_are_refused() throws IOException {
    refuses("version: 2\n" + MODES + MEDIA + "typo: true\n");
    refuses(
        "version: 2\n" + MODES.replace("exploratory: {}", "exploratory: {}\n    typo: {}") + MEDIA);
    refuses("version: 2\n" + MODES.replace("default:", "other:") + MEDIA);
    refuses(MODES + MEDIA);
  }

  @Test
  void version_two_missing_mapping_mode_fails_instead_of_using_a_fallback() throws IOException {
    write("version: 2\n" + MODES + MEDIA);
    Files.writeString(
        directory.resolve("models.yaml"),
        "models: {deployed: {profile: external, mode: missing}}\n");
    var refused = assertThrows(IllegalStateException.class, () -> SamplingProfiles.read(directory));
    assertTrue(refused.getMessage().contains("models.yaml"));
    assertTrue(refused.getMessage().contains("missing"));
    // Legacy sampling configurations retain their warning-and-default behavior.
    Files.writeString(directory.resolve("external.yaml"), MODES);
    assertEquals(
        "default",
        SamplingProfiles.read(directory).resolve("deployed", Sampling.Intent.BALANCED).mode());
  }

  @Test
  void oversized_files_and_duplicate_file_stems_are_refused() throws IOException {
    refuses("version: 2\n" + MODES + "#" + "x".repeat(262144));
    write("version: 2\n" + MODES + MEDIA);
    Files.writeString(directory.resolve("external.yml"), MODES);
    assertThrows(IllegalStateException.class, () -> SamplingProfiles.read(directory));
  }

  @Test
  void typed_declarations_defend_bounds_and_do_not_expose_mutable_collections() {
    var inputs = new LinkedHashSet<>(Set.of("IMAGE/PNG"));
    var formats = new LinkedHashSet<>(Set.of("image/png"));
    var outputs =
        new LinkedHashMap<>(Map.of("image", new ModelMediaCapabilities.OutputMode(formats)));
    var media = new ModelMediaCapabilities(inputs, outputs);
    inputs.clear();
    formats.clear();
    outputs.clear();
    assertEquals(Set.of("image/png"), media.inputs());
    assertEquals(Set.of("image/png"), media.outputs().get("image").formats());
    assertThrows(UnsupportedOperationException.class, () -> media.inputs().clear());
    assertThrows(UnsupportedOperationException.class, () -> media.outputs().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> media.outputs().get("image").formats().clear());
    var oversized = new LinkedHashSet<String>();
    for (int i = 0; i < 33; i++) oversized.add("image/type" + i);
    assertThrows(
        IllegalArgumentException.class, () -> new ModelMediaCapabilities.OutputMode(oversized));
  }

  private void write(String source) throws IOException {
    Files.writeString(directory.resolve("external.yaml"), source);
    Files.writeString(
        directory.resolve("models.yaml"), "models: {deployed: {profile: external}}\n");
  }

  private void refuses(String source) throws IOException {
    write(source);
    var refused = assertThrows(IllegalStateException.class, () -> SamplingProfiles.read(directory));
    assertTrue(refused.getMessage().contains("external.yaml"), refused.getMessage());
  }
}
