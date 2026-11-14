package io.aeyer.plowshare.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The bundled declaration opts out of implicit accounts; live authorization belongs to the server.
 */
class ApplicationExamplesTest {
  @Test
  void bundled_declaration_has_no_implicit_accounts_or_command_execution() throws Exception {
    Path application = Path.of(System.getProperty("a2a.examples")).resolve("application");
    var manifest =
        new ObjectMapper().readTree(Files.readString(application.resolve("plowshare.json")));
    assertEquals(1, manifest.path("version").intValue());
    assertEquals("a2a", manifest.path("name").textValue());
    assertTrue(manifest.path("access").path("accounts").isArray());
    assertTrue(manifest.path("access").path("accounts").isEmpty());
    assertEquals("off", manifest.path("commands").path("local").path("mode").textValue());
    assertEquals("off", manifest.path("commands").path("server").path("mode").textValue());
    assertFalse(Files.exists(application.resolve("plowshare")));
  }
}
