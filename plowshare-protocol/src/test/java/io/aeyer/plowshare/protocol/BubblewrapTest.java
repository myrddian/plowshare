package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class BubblewrapTest {
  @TempDir Path temporary;
  private Path root;
  private Path runtime;
  private Path binary;
  private Path probe;
  private Path file;
  private Path directory;
  private Bubblewrap sandbox;
  private FileAccess reads;

  @BeforeEach
  void prepare() throws Exception {
    root = Files.createDirectory(temporary.resolve("workspace")).toRealPath();
    runtime = Files.createDirectory(temporary.resolve("runtime")).toRealPath();
    binary = program(temporary.resolve("bwrap"));
    probe = program(runtime.resolve("probe"));
    file = Files.createFile(temporary.resolve("opaque"));
    directory = Files.createDirectory(temporary.resolve("opaque-directory"));
    Files.createDirectory(root.resolve(".plowshare"));
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"test\"}");
    reads = FileAccess.of(List.of(root), List.of());
    sandbox =
        new Bubblewrap(
            new Bubblewrap.Configuration(
                binary, probe, List.of(runtime), temporary.resolve("scratch")));
  }

  private Path program(Path path) throws Exception {
    Files.writeString(path, "#!/bin/sh\nexit 0\n");
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    return path;
  }

  private List<String> arguments(FileAccess writes) throws Exception {
    return sandbox.arguments(reads, writes, root, file, directory);
  }

  @Test
  void wraps_only_explicit_roots_and_preserves_configuration() throws Exception {
    Files.writeString(root.resolve(".secret"), "secret");
    List<String> argv = arguments(reads);
    assertTrue(argv.contains("--unshare-all"));
    assertTrue(argv.contains("--disable-userns"));
    assertTrue(argv.contains("--die-with-parent"));
    assertFalse(argv.contains("--share-net"));
    assertEquals(
        List.of("--chdir", root.toString(), "--"), argv.subList(argv.size() - 3, argv.size()));
    assertMount(argv, "--bind", root, root);
    assertMount(argv, "--ro-bind", directory, root.resolve(".plowshare"));
    assertMount(argv, "--ro-bind", file, root.resolve(".secret"));
    assertMount(argv, "--ro-bind", root.resolve("plowshare.json"), root.resolve("plowshare.json"));
  }

  @Test
  void restricts_writes_to_the_selected_subdirectory() throws Exception {
    Path output = Files.createDirectory(root.resolve("output"));
    List<String> argv = arguments(FileAccess.of(List.of(output), List.of()));
    assertMount(argv, "--ro-bind", root, root);
    assertMount(argv, "--bind", output, output);
    assertFalse(
        argv.contains("--bind") && argv.get(argv.indexOf("--bind") + 1).equals(root.toString()));
  }

  @Test
  void masks_an_explicit_exclusion_and_refuses_link_aliases() throws Exception {
    Path secret = Files.writeString(root.resolve("private"), "secret");
    reads = FileAccess.of(List.of(root), List.of(secret));
    assertMount(arguments(reads), "--ro-bind", file, secret);
    Files.createSymbolicLink(root.resolve("alias"), temporary);
    assertThrows(CommandRunner.Refused.class, () -> arguments(reads));
  }

  @Test
  void refuses_hard_links_to_files_outside_the_fence() throws Exception {
    Files.createLink(root.resolve("alias"), probe);
    assertThrows(CommandRunner.Refused.class, () -> arguments(reads));
  }

  @Test
  void refuses_an_unprotected_writable_root_and_oversized_runtime_grants() throws Exception {
    Files.delete(root.resolve("plowshare.json"));
    assertThrows(CommandRunner.Refused.class, () -> arguments(reads));
    var broad =
        new Bubblewrap(
            new Bubblewrap.Configuration(
                binary, probe, List.of(temporary.toRealPath()), temporary.resolve("scratch")));
    assertThrows(
        CommandRunner.Refused.class, () -> broad.arguments(reads, reads, root, file, directory));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Bubblewrap.Configuration(
                Path.of("bwrap"), probe, List.of(runtime), temporary.resolve("scratch")));
  }

  private static void assertMount(List<String> argv, String option, Path source, Path target) {
    boolean found = false;
    for (int index = 0; index < argv.size() - 2; index++) {
      if (argv.subList(index, index + 3)
          .equals(List.of(option, source.toString(), target.toString()))) found = true;
    }
    assertTrue(found, () -> "missing mount for " + target);
  }
}
