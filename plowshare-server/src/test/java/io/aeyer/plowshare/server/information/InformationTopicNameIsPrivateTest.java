package io.aeyer.plowshare.server.information;

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
 * The pin that stops the topic name leaking back into a shared place.
 *
 * <p>Structural rather than behavioural, for the same reason {@code
 * InformationEventFenceRemovedTest} is: "nothing outside this subsystem knows the topic name" is a
 * statement about every file, including ones added later, and no behavioural test can make it. A
 * derived name withdraws an address only for as long as nobody writes it down again — in a
 * constant, a deployment's configuration, a client, a fixture — and the moment one file does, the
 * property this change exists for is gone while every other test still passes.
 */
class InformationTopicNameIsPrivateTest {
  /** The directory that owns the name. Both halves of it: the implementation and its pins. */
  private static final List<String> OWNER =
      List.of(
          "plowshare-server/src/main/java/io/aeyer/plowshare/server/information",
          "plowshare-server/src/test/java/io/aeyer/plowshare/server/information");

  /** The literal the name used to be, spelled so this file is not its own counterexample. */
  private static final String WITHDRAWN = String.join(".", "information", "changed");

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
   * Every source file under {@code from}, with generated and vendored trees pruned.
   *
   * <p>Pruned during the walk rather than filtered afterwards: {@code node_modules} and the Gradle
   * build directories hold far more files than the repository does, and a scan that descended into
   * them would be slow enough to be turned off.
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
      // A file carrying a source extension that is not readable UTF-8 text is not something a
      // topic name was written into by hand or by a build.
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
   * <p>The whole tree, not just the server: a topic name leaks by being written into a client, an
   * SDK, a deployment manifest or a document, and those are the places a shared identifier ends up.
   * One scope's name stands for all of them — it is the only one any file could have copied,
   * because it is the only one the pins spell.
   */
  @Test
  void the_derived_topic_name_appears_in_no_file_outside_the_subsystem_that_derives_it() {
    String derived = InformationTopics.of(new Relay.ProjectScope(7));
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

  /**
   * And the literal it replaced, in the module that used to publish to it.
   *
   * <p>Scoped to {@code plowshare-server}, with the shipped migrations excluded, because those are
   * the two places a <i>Relay topic</i> can still be named. {@code information.changed} also
   * survives elsewhere as {@code AccountEvent.InformationChanged}'s wire kind — the name of a push
   * notification in the protocol and in five SDKs. That is a genuinely public identifier other
   * parties hold, which is exactly what a topic name is not, and renaming it would be a change to
   * the client contract rather than to this subsystem's private vocabulary. Shipped migrations name
   * the old topic by necessity and are never edited; V190 names it because renaming it is its job.
   */
  @Test
  void the_withdrawn_literal_names_no_relay_topic_anywhere_in_the_server() {
    assertEquals(
        List.of(),
        sources(ROOT.resolve("plowshare-server/src")).stream()
            .filter(path -> text(path).contains(WITHDRAWN))
            .filter(path -> !path.toString().contains("/db/migration/"))
            .filter(path -> !owned(path))
            .map(ROOT::relativize)
            .map(Path::toString)
            .sorted()
            .toList(),
        "the information topic's old literal must not survive as a topic name");
  }

  /** The scan is capable of finding something, so an empty result above means something. */
  @Test
  void the_scan_reaches_the_files_it_claims_to_check() {
    var found =
        sources(ROOT).stream()
            .filter(path -> text(path).contains(WITHDRAWN))
            .map(ROOT::relativize)
            .map(Path::toString)
            .toList();
    assertTrue(
        found.contains(
            "plowshare-server/src/main/resources/db/migration/"
                + "V190__information_topic_derived_name.sql"),
        "the migration that renames the literal must be reachable by this scan: " + found);
    assertTrue(
        found.stream().anyMatch(path -> path.startsWith("sdk/")),
        "and so must the push-event kind in the SDKs, which is a different identifier: " + found);
  }
}
