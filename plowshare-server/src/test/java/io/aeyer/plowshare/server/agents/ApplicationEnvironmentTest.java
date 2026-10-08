package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationEnvironmentTest {
  @TempDir Path root;

  @Test
  void application_root_policy_overrides_the_data_tier_and_is_read_without_a_session()
      throws Exception {
    Path application = Files.createDirectory(root.resolve("application"));
    Path legacy = Files.writeString(root.resolve("environment.yml"), "caps:\n  steps: 90\n");
    Path policy =
        Files.writeString(
            application.resolve("environment.yml"), "server:\n  mode: open\ncaps:\n  steps: 4\n");
    var environments = new Environments(name -> 7L, id -> legacy, null);
    environments.useApplicationResources(id -> Optional.of(application));
    assertFalse(environments.resolve("app", null).server().isOff());
    assertEquals(4, environments.caps("app", null).steps().value());
    Files.delete(policy);
    assertTrue(environments.resolve("app", null).server().isOff());
    assertNull(environments.caps("app", null).steps().value());
  }

  @Test
  void malformed_or_linked_application_policy_cannot_enable_commands() throws Exception {
    var environments = new Environments(name -> 7L, id -> null, null);
    environments.useApplicationResources(id -> Optional.of(root));
    Path policy = Files.writeString(root.resolve("environment.yml"), "server: bad");
    assertTrue(environments.resolve("app", null).server().isOff());
    Files.delete(policy);
    Path target = Files.writeString(root.resolve("private.yml"), "server:\n  mode: open\n");
    Files.createSymbolicLink(policy, target);
    assertTrue(environments.resolve("app", null).server().isOff());
  }
}
