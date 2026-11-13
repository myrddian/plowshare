package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A structural pin on the function-local {@code jit=off} settings of the authority routines whose
 * plans otherwise pay seconds of JIT compilation per call (V196, V198, V235, V240).
 *
 * <p>{@code CREATE OR REPLACE FUNCTION} without a {@code SET} clause silently resets function
 * configuration. That is how V235 left {@code information_log_readable} compiling about 170 JIT
 * functions per call until V240 restored the setting. The full-db plan test covers only two of
 * these routines, so this default-suite check replays the migrations in version order and requires
 * the last statement that touches each routine's configuration to leave {@code jit} off.
 */
class FunctionJitSettingsTest {
  /** Routines that must keep {@code jit=off}. Add a routine here when its migration sets it. */
  private static final List<String> JIT_OFF =
      List.of(
          "information_log_readable",
          "information_readable_input_selections",
          "information_unreadable_logs",
          "knowledge_readable_selection",
          "knowledge_readable_candidates",
          "knowledge_readable_targets");

  private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
  private static final Pattern JIT_OFF_CLAUSE =
      Pattern.compile("\\bSET\\s+jit\\s*(=|TO)\\s*'?off'?", Pattern.CASE_INSENSITIVE);

  @Test
  void every_listed_routine_ends_with_jit_off() {
    var migrations = migrations();
    for (String function : JIT_OFF) {
      var last = lastConfiguration(function, migrations);
      assertTrue(last.isPresent(), function + " is never defined by a migration");
      assertTrue(
          last.get().jitOff(),
          function
              + " loses jit=off in "
              + last.get().migration()
              + ": CREATE OR REPLACE resets function settings, so restate SET jit='off'");
    }
  }

  @Test
  void a_replacement_without_set_is_detected_as_a_reset() {
    // Guards the parser itself: V235 replaced information_log_readable without SET jit. Replaying
    // the history only up to V237 must report the setting as lost.
    var throughV237 =
        migrations().stream().filter(migration -> migration.version() <= 237).toList();
    var last = lastConfiguration("information_log_readable", throughV237).orElseThrow();
    assertEquals("V235__retained_log_read_authority.sql", last.migration());
    assertFalse(last.jitOff());
  }

  private record Migration(int version, String name, String sql) {}

  private record Configuration(String migration, int offset, boolean jitOff) {}

  /**
   * Returns the last statement in version order that defines the routine or alters its
   * configuration. A definition's header (up to its body) decides whether it declares {@code SET
   * jit}; an {@code ALTER FUNCTION} decides by its own clause, and {@code RESET} clears it.
   */
  private static Optional<Configuration> lastConfiguration(
      String function, List<Migration> migrations) {
    var create =
        Pattern.compile(
            "\\bCREATE\\s+(OR\\s+REPLACE\\s+)?FUNCTION\\s+" + Pattern.quote(function) + "\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    var alter =
        Pattern.compile(
            "\\bALTER\\s+FUNCTION\\s+" + Pattern.quote(function) + "\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    Configuration last = null;
    for (var migration : migrations) {
      var events = new ArrayList<Configuration>();
      String sql = withoutComments(migration.sql());
      Matcher defined = create.matcher(sql);
      while (defined.find()) {
        int body = sql.indexOf("$", defined.end());
        String header = sql.substring(defined.end(), body < 0 ? sql.length() : body);
        events.add(
            new Configuration(
                migration.name(), defined.start(), JIT_OFF_CLAUSE.matcher(header).find()));
      }
      Matcher altered = alter.matcher(sql);
      while (altered.find()) {
        int end = sql.indexOf(';', altered.end());
        String statement = sql.substring(altered.end(), end < 0 ? sql.length() : end);
        boolean setsJit = JIT_OFF_CLAUSE.matcher(statement).find();
        boolean resets =
            Pattern.compile("\\bRESET\\b", Pattern.CASE_INSENSITIVE).matcher(statement).find();
        if (setsJit || resets)
          events.add(new Configuration(migration.name(), altered.start(), setsJit));
      }
      events.sort(Comparator.comparingInt(Configuration::offset));
      if (!events.isEmpty()) last = events.get(events.size() - 1);
    }
    return Optional.ofNullable(last);
  }

  /** Comments may quote SQL (V240 names CREATE OR REPLACE); only statements count. */
  private static String withoutComments(String sql) {
    return sql.replaceAll("(?m)--.*$", "");
  }

  private static List<Migration> migrations() {
    Path directory = repository().resolve("plowshare-server/src/main/resources/db/migration");
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .map(path -> path.getFileName().toString())
          .map(name -> Optional.of(VERSION.matcher(name)).filter(Matcher::matches).map(m -> name))
          .flatMap(Optional::stream)
          .map(
              name -> {
                Matcher version = VERSION.matcher(name);
                assertTrue(version.matches());
                return new Migration(
                    Integer.parseInt(version.group(1)), name, read(directory.resolve(name)));
              })
          .sorted(Comparator.comparingInt(Migration::version))
          .toList();
    } catch (IOException unreadable) {
      throw new AssertionError(unreadable);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new AssertionError(unreadable);
    }
  }

  private static Path repository() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.isDirectory(candidate.resolve("plowshare-server/src/main")))
      candidate = candidate.getParent();
    assertNotNull(candidate, "the test must run inside the repository");
    return candidate;
  }
}
