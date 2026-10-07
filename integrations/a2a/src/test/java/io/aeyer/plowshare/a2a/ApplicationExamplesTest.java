package io.aeyer.plowshare.a2a;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The bundled root is an Application with no implicit account access. */
class ApplicationExamplesTest {
  @Test
  void bundled_manifest_is_an_application_and_hidden_until_explicitly_granted() throws Exception {
    Path application = Path.of(System.getProperty("a2a.examples")).resolve("application");
    var boundary =
        WorkspaceApplicationPolicy.parse(
            Files.readString(application.resolve("plowshare.json")), "a2a");
    assertEquals(ApplicationPolicy.Kind.APPLICATION, boundary.kind());
    assertTrue(boundary.accounts().isEmpty());
    assertTrue(boundary.limit("ungranted", Optional.of(ProjectRole.MANAGER)).isEmpty());
    assertFalse(Files.exists(application.resolve("plowshare")));
  }
}
