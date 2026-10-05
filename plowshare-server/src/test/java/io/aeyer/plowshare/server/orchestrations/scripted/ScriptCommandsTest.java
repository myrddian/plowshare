package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScriptCommandsTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"finish\":5}",
        "{\"finish\":\"done\",\"tool\":\"run\",\"arguments\":{}}",
        "{\"waitMs\":1.5}",
        "{\"waitMs\":\"10\"}",
        "{\"waitMs\":0}",
        "{\"waitMs\":1001}",
        "{\"waitMs\":10,\"tool\":\"run\",\"arguments\":{}}",
        "{\"tool\":1,\"arguments\":{}}",
        "{\"tool\":\"run\",\"arguments\":[]}",
        "{\"tool\":\"run\",\"arguments\":{},\"grant\":\"admin\"}",
        "{\"tool\":\"run\\n\",\"arguments\":{}}",
        "{\"tool\":\"run\"}"
      })
  void invalid_commands_are_refused_before_dispatch(String source) throws Exception {
    var node = JSON.readTree(source);
    assertThrows(IllegalStateException.class, () -> ScriptCommands.read(node));
  }

  @Test
  void readiness_recovery_requires_the_exact_native_observer() throws Exception {
    var await =
        (ScriptStore.Tool)
            ScriptCommands.read(
                JSON.readTree(
                    "{\"tool\":\"information_read\",\"arguments\":{\"operation\":\"await\"}}"));
    assertTrue(await.readinessObserver());
    var read =
        (ScriptStore.Tool)
            ScriptCommands.read(
                JSON.readTree(
                    "{\"tool\":\"information_read\",\"arguments\":{\"operation\":\"read\"}}"));
    assertFalse(read.readinessObserver());
    assertEquals(
        new ScriptStore.Wait(1000), ScriptCommands.read(JSON.readTree("{\"waitMs\":1000}")));
  }

  @Test
  void handler_finish_is_a_pure_typed_terminal_command() throws Exception {
    assertEquals(
        new ScriptStore.Finish("done"),
        ScriptCommands.read(JSON.readTree("{\"finish\":\"done\"}")));
    assertThrows(IllegalArgumentException.class, () -> new ScriptStore.Finish("x".repeat(65537)));
  }

  @Test
  void cached_arguments_may_change_encoding_but_never_fields_or_values() {
    assertTrue(
        ScriptStore.sameArguments(
            "{\"agent\":\"worker\",\"task\":\"work\"}",
            "{ \"task\": \"work\", \"agent\": \"worker\" }"));
    assertFalse(
        ScriptStore.sameArguments(
            "{\"agent\":\"worker\",\"task\":\"work\"}",
            "{\"agent\":\"different\",\"task\":\"work\"}"));
    assertFalse(
        ScriptStore.sameArguments(
            "{\"task\":\"work\"}", "{\"task\":\"work\",\"grant\":\"admin\"}"));
    assertFalse(
        ScriptStore.sameArguments(
            "{\"task\":\"work\"}", "{\"task\":\"work\",\"task\":\"different\"}"));
  }

  @org.junit.jupiter.api.Test
  void direct_commands_cannot_forge_argument_shapes_or_readiness_flags() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new ScriptStore.Tool("information_read", "[]", false));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new ScriptStore.Tool("information_read", "{\"operation\":\"await\"}", false));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new ScriptStore.Tool("information_read", "{\"operation\":\"log_read\"}", true));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new ScriptStore.Tool("fetch", "{} {}", false));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new ScriptStore.Tool("fetch", "{\"url\":\"first\",\"url\":\"second\"}", false));
  }
}
