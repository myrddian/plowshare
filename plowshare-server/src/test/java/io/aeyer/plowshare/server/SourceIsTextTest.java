package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every file this repository's containment invariants scan is a file they can read.
 *
 * <h2>Why this is a test and not a habit</h2>
 *
 * <p>Task 7 shipped a test file with a raw {@code 0x00} in it — a {@code "\0"} escape written
 * without its backslash, which compiles and runs correctly and asserts exactly what it was meant
 * to. Nothing warned: {@code javac} was happy, the test passed, and git diffed it as text because
 * the byte fell past git's 8000-byte binary sniff window.
 *
 * <p><b>What it broke was every tool that reads the tree.</b> {@code file(1)} reported {@code
 * data}; {@code grep} skips a file containing a NUL and returns <em>nothing at all</em> for it,
 * silently and with a zero exit status. This slice's containment invariants are greps — the client
 * stays Spring-free, HTTP clients stay confined, the reference box's address appears nowhere
 * outside {@code docs/}, no API key in {@code src} — and <b>an invariant that cannot see a file is
 * not an invariant</b>. A file in that shape is a hole in all four, and the hole is invisible from
 * inside them because their evidence of compliance is the same empty output as their evidence of
 * absence.
 *
 * <p>That is the second invariant this slice has found counting the wrong thing, after the {@code
 * OkHttpClient} grep that matched two files whose only mention was javadoc. Both were found by
 * somebody looking, which is what this replaces.
 *
 * <h2>It covers what the invariants cover, which is not only Java</h2>
 *
 * <p><b>The first version of this file filtered {@code endsWith(".java")} and would have missed two
 * of the four greps it names.</b> The API-key grep and the tracked-tree address grep are not
 * Java-only, and there are {@code .yml}, {@code .xml}, {@code .sql} and {@code .md} files under
 * {@code src} today — a key in a NUL-bearing {@code application.yml} would be exactly as invisible.
 *
 * <p><b>Neither of those two patterns is written out here, and that is on purpose.</b> Quoting the
 * key prefix put a hit into the very grep this class exists to protect — the guard tripping its own
 * invariant, which is the fault {@code plowshare-server/build.gradle.kts} already names one level
 * up about the reference box's address. Found by running the four invariants after writing this
 * file, which is the only way it could have been found. A guard that watched a narrower set than
 * the invariant it protects is the same shape as a guard that watched only its own module, which
 * this file already argues against.
 *
 * <p><b>The modules come from {@code settings.gradle.kts} rather than a list here</b>, for the same
 * reason: a hardcoded triple asserts that those three exist, not that they are all of them, so a
 * fourth module would be added to the build and silently not watched.
 *
 * <h2>What this instrument cannot do, measured rather than assumed</h2>
 *
 * <p><b>It only runs when Gradle decides this module's tests are out of date.</b> Measured while
 * checking that it catches anything at all: a NUL put back into a {@code plowshare-client} test and
 * {@code :plowshare-server:test} run without {@code --rerun-tasks} reported {@code BUILD
 * SUCCESSFUL} — the task was up-to-date, so this never executed. With {@code --rerun-tasks} it
 * fails, and names the file.
 *
 * <p>That is trap 5 — an instrument that cannot record the thing it is cited for — and it is stated
 * rather than fixed. The fix would be declaring every module's sources as inputs to this module's
 * test task, which makes the whole server suite re-run on any client edit; the cost outweighs it,
 * because this project's stated verification deletes every module's test-results directory and runs
 * the whole suite with {@code --rerun-tasks}, so the guard does run on every occasion a count is
 * verified. What it will not do is fail fast for somebody who has only touched another module.
 */
class SourceIsTextTest {

  /**
   * The repository root.
   *
   * <p>Gradle runs a test with its working directory set to the module's project directory, so the
   * parent is the root. Asserted rather than assumed — {@code
   * the_modules_this_walks_are_the_ones_the_build_declares} fails loudly if that changes, which is
   * better than a guard that quietly walks an empty directory and reports everything in it as
   * clean.
   */
  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  /** {@code include("plowshare-…")} in the build's own module list. */
  private static final Pattern INCLUDED = Pattern.compile("\"(plowshare-[a-z-]+)\"");

  /**
   * Explicit binary assets: adding one requires naming it here, while every source file remains
   * subject to the NUL guard.
   */
  private static final Set<String> BINARY_ASSETS =
      Set.of(
          "gradle/wrapper/gradle-wrapper.jar",
          "client-assets/fonts/BarlowCondensed-ExtraBold.ttf",
          "client-assets/fonts/Lato-Regular.ttf",
          "client-assets/fonts/Lato-Bold.ttf",
          "plowshare-desktop/assets/icons/source.png",
          "plowshare-desktop/assets/icons/plowshare.png",
          "plowshare-desktop/assets/icons/plowshare.icns",
          "plowshare-desktop/assets/icons/plowshare.ico");

  @Test
  void the_modules_this_walks_are_the_ones_the_build_declares() throws IOException {
    List<String> modules = modules();

    assertTrue(
        modules.size() >= 3,
        "a guard that walked the wrong directory would pass by finding nothing,"
            + " which is the exact failure it exists to catch — "
            + ROOT);
    assertTrue(
        modules.contains("plowshare-server")
            && modules.contains("sdk/java")
            && modules.contains("plowshare-protocol"),
        "the three that exist today are among them — " + modules);
    for (String module : modules) {
      assertTrue(
          Files.isDirectory(ROOT.resolve(module).resolve("src")),
          module
              + " is in settings.gradle.kts and has no src directory, so either"
              + " the build or this guard is looking in the wrong place");
    }
  }

  @Test
  void no_file_the_invariants_scan_contains_a_byte_that_makes_it_invisible_to_grep()
      throws IOException {
    List<String> unreadable = new ArrayList<>();
    int seen = 0;
    for (String module : modules()) {
      try (Stream<Path> tree = Files.walk(ROOT.resolve(module).resolve("src"))) {
        for (Path candidate : (Iterable<Path>) tree::iterator) {
          if (!Files.isRegularFile(candidate)) {
            continue;
          }
          seen++;
          if (holdsNul(candidate)) {
            unreadable.add(ROOT.relativize(candidate).toString());
          }
        }
      }
    }

    assertEquals(
        List.of(),
        unreadable,
        "a NUL makes grep skip the file entirely and return nothing with a zero exit"
            + " status, so every containment invariant in this slice passes over"
            + " it without being able to say so. Write the escape: \"\\0\"");
    // The fixture is only a fixture if it walked something. A filter typo
    // above would otherwise report a clean tree it never opened.
    assertTrue(
        seen > 100,
        "it walked "
            + seen
            + " files under src, which is too few to"
            + " have been this repository");
  }

  @Test
  void the_only_unreadable_files_are_the_explicit_binary_assets() throws IOException {
    // The wider statement, for the invariant that scans the whole tracked
    // tree rather than only src. Build outputs and tool directories are
    // skipped because they are not tracked and are full of legitimate
    // binaries; what is left is what a `git ls-files` grep would meet.
    List<String> unreadable = new ArrayList<>();
    // The source tests above also cover untracked code. This wider guard covers
    // the actual tracked tree, so unrelated local attachments are outside its scope.
    Process listing = new ProcessBuilder("git", "ls-files", "-z").directory(ROOT.toFile()).start();
    String paths =
        new String(
            listing.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    try {
      assertEquals(0, listing.waitFor(), "cannot inventory the tracked tree");
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new IOException("tracked tree inventory interrupted", stopped);
    }
    for (String relative : paths.split("\\u0000")) {
      Path candidate = ROOT.resolve(relative);
      if (Files.isRegularFile(candidate) && !skipped(relative) && holdsNul(candidate))
        unreadable.add(relative);
    }

    assertEquals(
        BINARY_ASSETS,
        Set.copyOf(unreadable),
        "the address grep runs over every tracked file outside docs/, and this is the"
            + " list of the ones it cannot read");
  }

  private static List<String> modules() throws IOException {
    List<String> modules = new ArrayList<>();
    String settings = Files.readString(ROOT.resolve("settings.gradle.kts"));
    // Gradle identities remain stable when physical source directories move.
    java.util.Map<String, String> directories = new java.util.HashMap<>();
    Matcher locations =
        Pattern.compile("project\\(\":([^\"]+)\"\\)\\.projectDir = file\\(\"([^\"]+)\"\\)")
            .matcher(settings);
    while (locations.find()) directories.put(locations.group(1), locations.group(2));
    Matcher included = INCLUDED.matcher(settings);
    while (included.find()) {
      String directory = directories.getOrDefault(included.group(1), included.group(1));
      if (!modules.contains(directory)) {
        modules.add(directory);
      }
    }
    return modules;
  }

  /**
   * What the walk above is not a statement about: paths {@code .gitignore} excludes, which no
   * {@code git ls-files} grep would ever meet.
   *
   * <p><b>The deployment data directory joined this list, and it joined it as a failure rather than
   * as tidiness.</b> {@code /plowshare-server/data/} is gitignored runtime state and has been since
   * it existed, so the test's own comment already said it was out of scope — but nothing here
   * skipped it, and the omission was invisible until something in it held a NUL. Image upload
   * landed on 2026-09-07, and from the first live picture stored on a developer's machine this test
   * fails with a PNG in its message: a guard that goes red because the server was run once is a
   * guard whose next failure gets fixed by deleting it.
   *
   * <p>Named as literal prefixes, matching the two {@code .gitignore} lines rather than a substring
   * anywhere: an unanchored {@code data/} would match {@code
   * plowshare-server/src/main/java/.../server/data/}, which is source this walk must keep reading.
   * {@code .gitignore} says the same thing about itself in the same words.
   */
  private static boolean skipped(String relative) {
    return relative.isEmpty()
        || relative.startsWith(".")
        || relative.contains("/.")
        || relative.startsWith("node_modules/")
        || relative.contains("/node_modules/")
        || relative.startsWith("build/")
        || relative.contains("/build/")
        || relative.startsWith("data/")
        || relative.startsWith("exports/")
        || relative.startsWith("plowshare-server/data/")
        || relative.startsWith("plowshare-server/exports/");
  }

  private static boolean holdsNul(Path file) throws IOException {
    for (byte b : Files.readAllBytes(file)) {
      if (b == 0) {
        return true;
      }
    }
    return false;
  }
}
