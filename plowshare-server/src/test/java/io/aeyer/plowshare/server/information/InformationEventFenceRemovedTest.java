package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A structural pin: the installation-wide ordering fence is gone from the source tree, and the row
 * lock it was mistaken for is still there.
 *
 * <p>Structural rather than behavioural on purpose. "No event takes an installation-wide lock" is
 * not a statement about one call — it is a statement about every call, including ones added later,
 * and a behavioural test would only ever cover the paths it happened to exercise. The advisory key
 * was a literal string, so its absence is checkable exactly.
 */
class InformationEventFenceRemovedTest {
  /**
   * The advisory key the removed fence hashed, spelled so this file is not its own counterexample.
   */
  private static final String FENCE_KEY = String.join("-", "information", "event", "order");

  private static final Path ROOT = repository();

  private static Path repository() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.isDirectory(candidate.resolve("plowshare-server/src/main")))
      candidate = candidate.getParent();
    assertNotNull(candidate, "the test must run inside the repository");
    return candidate;
  }

  private static Stream<Path> sources(String module) {
    try {
      return Files.walk(ROOT.resolve(module))
          .filter(Files::isRegularFile)
          .filter(
              path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".java") || name.endsWith(".sql");
              });
    } catch (IOException unreadable) {
      throw new AssertionError(unreadable);
    }
  }

  private static String text(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new AssertionError(unreadable);
    }
  }

  @Test
  void no_installation_wide_advisory_fence_survives_anywhere_on_the_event_path() {
    try (var sources =
        Stream.concat(sources("plowshare-server/src"), sources("plowshare-protocol/src"))) {
      var surviving = sources.filter(path -> text(path).contains(FENCE_KEY)).toList();
      assertEquals(
          List.of(),
          surviving,
          "the installation-wide information-event ordering fence must not survive");
    }
  }

  @Test
  void the_revision_row_lock_the_fence_was_confused_with_is_still_taken() {
    // Deliberately a separate assertion from the one above. Removing the fence by removing the
    // whole lock sequence would satisfy that pin and reintroduce the deadlock this lock prevents:
    // a worker can hold FOR UPDATE on a revision while publishing its own event.
    String records =
        text(
            ROOT.resolve(
                "plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationEventRecords.java"));
    assertTrue(
        records.contains("SELECT id FROM information_revision_allowances WHERE id=? FOR KEY SHARE"),
        "the revision row lock must still precede an information event's publication");
  }

  @Test
  void the_dropped_delivery_tables_have_no_reader_left_in_the_server() {
    try (var sources = sources("plowshare-server/src/main")) {
      var surviving =
          sources
              .filter(
                  path -> {
                    String body = text(path);
                    return Stream.of(
                            "information_events",
                            "information_event_recipients",
                            "information_event_dispatch")
                        .anyMatch(body::contains);
                  })
              // The migrations that created them, and the one that drops them, name them by
              // necessity; shipped migrations are history and are never edited.
              .filter(path -> !path.toString().contains("/db/migration/"))
              .toList();
      assertEquals(List.of(), surviving);
    }
  }
}
