package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class IsolationScratchTest {
  @TempDir Path root;

  private Path job() throws Exception {
    return Files.createTempDirectory(
        root,
        "job-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  @Test
  void removes_only_known_regular_policy_files_and_empty_placeholders() throws Exception {
    Path job = job();
    Files.createFile(job.resolve("options"));
    Files.createFile(job.resolve("file"));
    Files.createDirectory(job.resolve("directory"));
    IsolationScratch.remove(job, root);
    assertFalse(Files.exists(job));
  }

  @Test
  void preserves_unknown_contents_and_link_aliases_without_touching_their_targets()
      throws Exception {
    Path outside = Files.writeString(root.resolve("private"), "preserved");
    Path unknown = job();
    Files.createFile(unknown.resolve("options"));
    Files.createFile(unknown.resolve("unknown"));
    IsolationScratch.remove(unknown, root);
    assertTrue(Files.exists(unknown.resolve("options")));
    Path linked = job();
    Files.createSymbolicLink(linked.resolve("options"), outside);
    IsolationScratch.remove(linked, root);
    assertTrue(Files.isSymbolicLink(linked.resolve("options")));
    Path hard = job();
    Files.createLink(hard.resolve("options"), outside);
    IsolationScratch.remove(hard, root);
    assertTrue(Files.exists(hard.resolve("options")));
    Path directoryLink = root.resolve("foreign");
    Files.createSymbolicLink(directoryLink, unknown);
    IsolationScratch.remove(directoryLink, root);
    assertTrue(Files.isSymbolicLink(directoryLink));
    assertEquals("preserved", Files.readString(outside));
  }
}
