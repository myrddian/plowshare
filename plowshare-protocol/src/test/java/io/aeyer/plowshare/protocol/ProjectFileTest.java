package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFileTest {
  @Test
  void agent_mutations_cannot_enable_project_policy_but_the_existing_cli_remains_editable(
      @TempDir Path root) throws Exception {
    Path manifest = root.resolve("plowshare");
    assertFalse(ProjectFile.modelMayChange(root, root.resolve("plowshare.json")));
    assertFalse(ProjectFile.modelMayChange(root, manifest));
    Files.writeString(
        manifest, "{\"version\":1,\"name\":\"house\",\"caps\":{\"autoIncrease\":true}}");
    assertFalse(ProjectFile.modelMayChange(root, manifest));
    Files.writeString(manifest, "#!/bin/sh\necho test\n");
    assertTrue(ProjectFile.modelMayChange(root, manifest));
    assertTrue(ProjectFile.modelMayChange(root, root.resolve("source.ts")));
  }
}
