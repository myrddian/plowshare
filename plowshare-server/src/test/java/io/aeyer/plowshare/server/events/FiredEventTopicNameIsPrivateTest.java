package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.relay.Relay;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The pin that stops the fired-event topic name leaking back into a shared place.
 *
 * <p>Structural rather than behavioural, because "nothing outside this subsystem knows the topic
 * name" is a statement about every file including ones added later, and no behavioural test can
 * make it. A derived name withdraws an address only for as long as nobody writes it down again — in
 * a constant, a deployment's configuration, a client, a fixture — and the moment one file does, the
 * property is gone while every other test still passes. {@code InformationTopicNameIsPrivateTest}
 * establishes this shape; this is the same pin for the second family to move.
 */
class FiredEventTopicNameIsPrivateTest {
  /** The directory that owns the name. Both halves of it: the implementation and its pins. */
  private static final List<String> OWNER =
      List.of(
          "plowshare-server/src/main/java/io/aeyer/plowshare/server/events",
          "plowshare-server/src/test/java/io/aeyer/plowshare/server/events");

  private static final Path ROOT = repository();

  private static Path repository() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.isDirectory(candidate.resolve("plowshare-server/src/main")))
      candidate = candidate.getParent();
    assertNotNull(candidate, "the test must run inside the repository");
    return candidate;
  }

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

  /**
   * Every source file under {@code from}, with generated and vendored trees pruned during the walk
   * rather than filtered afterwards: {@code node_modules} and the build directories hold far more
   * files than the repository does, and a scan that descended into them would be slow enough to be
   * turned off.
   */
  private static List<Path> sources(Path from) {
    var found = new java.util.ArrayList<Path>();
    try {
      Files.walkFileTree(
          from,
          new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                Path directory, java.nio.file.attribute.BasicFileAttributes attributes) {
              return SKIPPED.contains(directory.getFileName().toString())
                  ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                  : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
              String name = file.getFileName().toString();
              if (EXTENSIONS.stream().anyMatch(name::endsWith)) found.add(file);
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException failure) {
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException unreadable) {
      throw new AssertionError(unreadable);
    }
    return found;
  }

  private static String text(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      // A file carrying a source extension that is not readable UTF-8 text is not something a topic
      // name was written into by hand or by a build.
      return "";
    }
  }

  private static boolean owned(Path path) {
    String relative = ROOT.relativize(path).toString().replace('\\', '/');
    return OWNER.stream().anyMatch(relative::startsWith);
  }

  /**
   * The derived name itself, anywhere in the repository.
   *
   * <p>The whole tree and not just the server: a topic name leaks by being written into a client,
   * an SDK, a deployment manifest or a document, and those are the places a shared identifier ends
   * up. Both scopes are checked, because the derivation is total over them and either could be
   * copied.
   */
  @Test
  void the_derived_topic_name_appears_in_no_file_outside_the_subsystem_that_derives_it() {
    for (Relay.Scope scope : List.of(Relay.SystemScope.SERVER, new Relay.ProjectScope(7))) {
      String derived = EventTopics.of(scope);
      assertEquals(
          List.of(),
          sources(ROOT).stream()
              .filter(path -> text(path).contains(derived))
              .filter(path -> !owned(path))
              .map(ROOT::relativize)
              .map(Path::toString)
              .sorted()
              .toList(),
          "a derived topic name written down outside its own subsystem is an address again");
    }
  }

  /**
   * And the legible prefix names no topic outside the subsystem either.
   *
   * <p>The prefix on its own is not an address — nothing can publish to {@code event.fired.} — but
   * a file outside this package that spelled it would be the first half of writing the name down,
   * and is the shape a later change would take if it needed to refer to the topic.
   */
  @Test
  void the_topics_legible_prefix_names_nothing_outside_the_subsystem() {
    String prefix = EventTopics.of(Relay.SystemScope.SERVER).substring(0, "event.fired.".length());
    assertEquals(
        List.of(),
        sources(ROOT).stream()
            .filter(path -> text(path).contains(prefix))
            .filter(path -> !owned(path))
            .map(ROOT::relativize)
            .map(Path::toString)
            .sorted()
            .toList(),
        "the fired-event topic's namespace must not be spelled outside the subsystem that owns it");
  }

  /**
   * The scan is capable of finding something, so an empty result above means something.
   *
   * <p>{@code event.fire} is the operation's name: a genuinely public identifier that the protocol
   * and every SDK hold, which is exactly what a topic name is not. It is therefore the right
   * control — present in many files, and correctly not the topic.
   */
  @Test
  void the_scan_reaches_the_files_it_claims_to_check() {
    String operation = String.join(".", "event", "fire");
    var found =
        sources(ROOT).stream()
            .filter(path -> text(path).contains(operation))
            .map(ROOT::relativize)
            .map(Path::toString)
            .toList();
    assertTrue(
        found.stream().anyMatch(path -> path.startsWith("sdk/")),
        "the operation name must be reachable in the SDKs, which is a different identifier: "
            + found);
    assertTrue(
        found.contains(
            "plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameTypes.java"),
        "and in the frame catalogue: " + found);
  }
}
