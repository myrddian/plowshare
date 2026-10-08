package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.relay.RelayRouteProgram;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeApplicationPackageValidatorTest {
  @Test
  void hooks_use_the_runtime_module_and_typescript_parser_without_execution(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"app\"}");
    Path hooks = Files.createDirectories(root.resolve("hooks"));
    Files.writeString(
        hooks.resolve("10-js.js"),
        "throw new Error('validation must not execute this'); export default {name:'js',stages:{'prompt.pre':{handle(){return {};}}}};");
    Files.writeString(
        hooks.resolve("20-ts.ts"),
        "const name: string='ts'; export default {name,stages:{'prompt.pre':{handle(){return {};}}}};");
    try (var engine = new HookEngine()) {
      var validator =
          new RuntimeApplicationPackageValidator(
              new AgentRegistry(Map.of()),
              Set.of(),
              DefinitionChecks.NONE,
              mock(SwarmScheduler.Pools.class),
              engine,
              mock(RelayRouteProgram.class));
      assertDoesNotThrow(() -> validator.validate("app", root));
      Files.createDirectories(root.resolve(".plowshare/agents"));
      assertThrows(CallerFault.class, () -> validator.validate("app", root));
      Files.delete(root.resolve(".plowshare/agents"));
      Files.delete(root.resolve(".plowshare"));
      Files.writeString(hooks.resolve("10-js.js"), "export default {");
      assertThrows(CallerFault.class, () -> validator.validate("app", root));
    }
  }
}
