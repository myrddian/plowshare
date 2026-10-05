package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

class ScriptHostTest {
  private final FixtureScriptHost host = new FixtureScriptHost(Duration.ofSeconds(5));

  @Test
  void worker_output_preserves_unicode_when_stdout_uses_ascii() throws Exception {
    String message = "Office temperature is 30 °C. — 温度";
    var input = Json.object();
    input.put(
        "source", "export default {onCompletion(e,c){c.state.message=e.reportText;return []}};");
    input.put("handler", "onCompletion");
    input.set(
        "event",
        Json.object()
            .put("id", "run-fixture")
            .put("state", "completed")
            .put("reportText", message));
    input.set("context", Json.object());
    var builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dsun.stdout.encoding=US-ASCII",
            "-cp",
            System.getProperty("integration.worker.classpath"),
            ScriptWorker.class.getName());
    builder.environment().clear();
    Process worker = builder.start();
    try {
      try (var stdin = worker.getOutputStream()) {
        stdin.write(Json.MAPPER.writeValueAsBytes(input));
      }
      assertTrue(worker.waitFor(10, TimeUnit.SECONDS), "worker did not finish");
      assertEquals(0, worker.exitValue());
      var output =
          Json.parse(
              new String(
                  worker.getInputStream().readNBytes(Json.MAX_MESSAGE + 1),
                  StandardCharsets.UTF_8));
      assertEquals(message, output.path("state").path("message").asText());
    } finally {
      worker.destroyForcibly();
    }
  }

  @Test
  void handler_returns_effects_and_explicit_state_without_external_io() throws Exception {
    var ctx = Json.object();
    ctx.set("state", Json.object().put("count", 2));
    ctx.set(
        "states",
        Json.object()
            .set("house", Json.object().set("temperature", Json.object().put("state", "29"))));
    var output =
        host.evaluate(
            "export default {onEvent(e,c){c.state.count++; return"
                + " [c.startPipeline('heat',{reading:c.readStates('house',['temperature'])},{key:'investigate'})]}};",
            "onEvent",
            Json.object(),
            ctx);
    assertEquals(3, output.path("state").path("count").asInt());
    assertEquals("pipeline.start", output.path("effects").get(0).path("kind").asText());
    assertEquals(
        "29",
        output
            .path("effects")
            .get(0)
            .path("input")
            .path("reading")
            .path("temperature")
            .path("state")
            .asText());
  }

  @Test
  void host_environment_network_and_file_load_are_unavailable() throws Exception {
    var output =
        host.evaluate(
            "export default {onEvent(e,c){c.state.host=typeof Java;c.state.env=typeof"
                + " process;c.state.network=typeof fetch;c.state.file=typeof"
                + " load;return []}};",
            "onEvent",
            Json.object(),
            Json.object());
    for (String name : new String[] {"env", "network", "file"})
      assertEquals("undefined", output.path("state").path(name).asText(), name);
    assertThrows(
        IOException.class,
        () ->
            host.evaluate(
                "export default"
                    + " {onEvent(){Java.type('java.lang.System').getenv();return"
                    + " []}};",
                "onEvent",
                Json.object(),
                Json.object()));
    assertThrows(
        IOException.class,
        () ->
            host.evaluate(
                "import x from 'file:///etc/passwd';export default {};",
                "onEvent",
                Json.object(),
                Json.object()));
  }

  @Test
  void infinite_loop_is_killed_and_next_invocation_still_works() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            new FixtureScriptHost(Duration.ofSeconds(2))
                .evaluate(
                    "export default {onEvent(){while(true){}}};",
                    "onEvent",
                    Json.object(),
                    Json.object()));
    assertEquals(
        0,
        host.evaluate("export default {};", "onCompletion", Json.object(), Json.object())
            .path("effects")
            .size());
  }

  @Test
  void oversized_input_and_output_are_refused() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            host.evaluate(
                "export default {};",
                "onEvent",
                Json.object().put("large", "x".repeat(Json.MAX_MESSAGE)),
                Json.object()));
    assertThrows(
        IOException.class,
        () ->
            host.evaluate(
                "export default"
                    + " {onEvent(e,c){c.state.large='x'.repeat(300000);return"
                    + " []}};",
                "onEvent",
                Json.object(),
                Json.object()));
  }

  @Test
  void thrown_handler_never_returns_modified_state() {
    assertThrows(
        IOException.class,
        () ->
            host.evaluate(
                "export default {onEvent(e,c){c.state.count=9;throw" + " Error('bad')}};",
                "onEvent",
                Json.object(),
                Json.object()));
  }

  private static final class FixtureScriptHost {
    private final ScriptHost host;

    FixtureScriptHost(Duration timeout) {
      host = new ScriptHost(timeout);
    }

    com.fasterxml.jackson.databind.JsonNode evaluate(
        String source,
        String handler,
        com.fasterxml.jackson.databind.JsonNode event,
        com.fasterxml.jackson.databind.JsonNode context)
        throws IOException {
      var input = (com.fasterxml.jackson.databind.node.ObjectNode) context.deepCopy();
      if (input.path("states").isObject())
        input
            .path("states")
            .fields()
            .forEachRemaining(
                binding -> {
                  binding
                      .getValue()
                      .fields()
                      .forEachRemaining(
                          reading -> {
                            var value =
                                (com.fasterxml.jackson.databind.node.ObjectNode) reading.getValue();
                            value.put("alias", reading.getKey());
                            if (!value.has("availability")) value.put("availability", "available");
                          });
                });
      var captured = IntegrationCodec.decode(input, IntegrationContracts.ScriptContext.class);
      var envelope =
          event.has("large")
              ? new IntegrationContracts.EventEnvelope(
                  new IntegrationContracts.Completion(
                      "run-fixture", "completed", event.path("large").asText(), null))
              : IntegrationFixtures.event(event);
      return IntegrationCodec.tree(host.evaluate(source, handler, envelope, captured));
    }
  }
}
