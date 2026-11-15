package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScriptProgramManifestTest {
  private static String script(String declaration) {
    return "// plowshare-script v1\nexport const manifest = {"
        + declaration
        + "}; export function step() {throw new Error('Must not execute a workflow step');}";
  }

  @Test
  void only_an_explicit_boolean_native_declaration_suppresses_default_completion_attention() {
    assertTrue(ScriptProgram.nativeOnly(script("'native-only': true")));
    assertFalse(ScriptProgram.nativeOnly(script("'native-only': false")));
    assertFalse(ScriptProgram.nativeOnly(script("name: 'model_work'")));
    assertFalse(ScriptProgram.nativeOnly("---\nname: model_work\nmodel: reasoning\n---\nWork"));
  }

  @Test
  void malformed_declarations_and_script_failures_cannot_silently_suppress_attention() {
    for (String declaration :
        new String[] {"'native-only': 'true'", "'native-only': null", "'native-only': 1"}) {
      assertThrows(
          IllegalStateException.class, () -> ScriptProgram.nativeOnly(script(declaration)));
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            ScriptProgram.nativeOnly(
                "// plowshare-script v1\nthrow new Error('Broken manifest');"));
  }
}
