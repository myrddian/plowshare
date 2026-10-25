package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walks the repository's own source files, for the pins that assert a topic name is written down
 * nowhere.
 *
 * <p>Shared by {@link FiredEventTopicNameIsPrivateTest} and {@link ScheduleTopicNameIsPrivateTest},
 * which make the same structural claim about two families. The machinery is here rather than
 * duplicated because a scan that silently stopped reaching part of the tree would make both pins
 * pass for the wrong reason, and one copy is one thing to keep honest. {@code
 * InformationTopicNameIsPrivateTest} carries its own copy for its own subsystem.
 */
final class SourceScan {

  private SourceScan() {}

  /** Directories that hold generated output, dependencies or history rather than source. */
  private static final Set<String> SKIPPED =
      Set.of(".git", ".gradle", ".claude", "node_modules", "build", "dist", "out", "target", "bin");

  private static final Set<String> EXTENSIONS =
      Set.of(
          ".java",
          ".kt",
          ".kts",
          ".sql",
          ".ts",
          ".tsx",
          ".js",
          ".mjs",
          ".cjs",
          ".py",
          ".go",
          ".cs",
          ".json",
          ".yaml",
          ".yml",
          ".toml",
          ".md",
          ".properties",
          ".sh",
          ".xml");

  /** The repository root, found by walking up to the directory that holds the server module. */
  static Path root() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.isDirectory(candidate.resolve("plowshare-server/src/main")))
      candidate = candidate.getParent();
    assertNotNull(candidate, "the test must run inside the repository");
    return candidate;
  }

  /**
   * Every source file under {@code from} whose text contains {@code needle}.
   *
   * <p>Generated and vendored trees are pruned during the walk rather than filtered afterwards:
   * {@code node_modules} and the build directories hold far more files than the repository does,
   * and a scan that descended into them would be slow enough to be turned off.
   */
  static List<Path> containing(Path from, String needle) {
    var found = new ArrayList<Path>();
    try {
      Files.walkFileTree(
          from,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
              return SKIPPED.contains(directory.getFileName().toString())
                  ? FileVisitResult.SKIP_SUBTREE
                  : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String name = file.getFileName().toString();
              if (EXTENSIONS.stream().anyMatch(name::endsWith) && text(file).contains(needle))
                found.add(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException unreadable) {
      throw new AssertionError(unreadable);
    }
    return found;
  }

  static String text(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      // A file carrying a source extension that is not readable UTF-8 text is not something a topic
      // name was written into by hand or by a build.
      return "";
    }
  }

  /** Whether the path is one of the two halves of the events subsystem that owns these names. */
  static boolean ownedByEvents(Path root, Path path) {
    String relative = root.relativize(path).toString().replace('\\', '/');
    return relative.startsWith("plowshare-server/src/main/java/io/aeyer/plowshare/server/events")
        || relative.startsWith("plowshare-server/src/test/java/io/aeyer/plowshare/server/events");
  }
}
