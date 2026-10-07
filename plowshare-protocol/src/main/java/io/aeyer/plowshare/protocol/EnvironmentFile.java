package io.aeyer.plowshare.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code environment.yml}: whether, and how, {@code run} may start a command on each side — the
 * server, and the machine a person works on.
 *
 * <h2>Why this is in the protocol module</h2>
 *
 * <p>{@link Replacement}'s reason. The server reads a project's file and a session's {@code
 * .plowshare/environment.yml} to decide what a run may do, and each client reads its own again
 * before it starts anything, so that a server cannot run a command on a machine whose own file
 * forbids it. The server and the Java client execute this class; the TUI's copy is held to the case
 * table {@code src/test/resources/io/aeyer/plowshare/protocol/environments.json}.
 *
 * <h2>A fixed subset, not YAML</h2>
 *
 * <p>Three top-level keys, {@code local:}, {@code server:} and {@code caps:} — each a block of
 * lines indented by two spaces; {@code inherit} a flow list; {@code env} a nested block indented by
 * four; comments and blank lines. <b>Anything else refuses the whole file</b>, with a sentence
 * naming the line. A real YAML parser would accept files this subset's other implementation reads
 * differently, and the file decides whether commands run: two readings of it is the one
 * disagreement that must not be possible.
 */
public final class EnvironmentFile {

  public static final String OFF = "off";
  public static final String GATED = "gated";

  /** A call no hook allowed or denied goes to a person. Spec 2026-09-15, asking a person. */
  public static final String ASK = "ask";

  public static final String OPEN = "open";

  public static final String LOCAL = "local";
  public static final String SERVER = "server";

  /** The top-level section the person's caps live in — spec 2026-09-29 §2. */
  public static final String CAPS = "caps";

  /** {@code caps:}'s settings, in the order the refusal names them. */
  private static final List<String> CAP_SETTINGS =
      List.of("steps", "budget", "auto-continue", "time", "failed-checks", "auto-increase");

  /** The longest {@code time} cap, in minutes: a week. */
  public static final int MOST_TIME_MINUTES = 7 * 24 * 60;

  /** The most failed checks {@code failed-checks} may allow before the person is asked. */
  public static final int MOST_FAILED_CHECKS = 100;

  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(60);
  public static final long DEFAULT_OUTPUT_BYTES = 1024L * 1024;
  public static final long MAX_OUTPUT_BYTES = 8L * 1024 * 1024;
  public static final List<String> DEFAULT_INHERIT = List.of("PATH", "HOME", "LANG");

  /** The commands that are a shell, by basename with any {@code .exe} removed. */
  public static final Set<String> SHELLS =
      Set.of(
          "bash", "sh", "zsh", "fish", "dash", "ksh", "csh", "tcsh", "cmd", "powershell", "pwsh");

  private static final Set<String> KEYS =
      Set.of("mode", "shells", "inherit", "env", "timeout", "output", "isolation");
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern DURATION = Pattern.compile("([0-9]{1,6})(s|m)");
  private static final Pattern SIZE = Pattern.compile("([0-9]{1,9})(KiB|MiB)");

  private EnvironmentFile() {}

  /** Typed command policy from a project JSON manifest; parsing JSON belongs to the I/O caller. */
  public static Parsed commands(Map<String, ?> commands) {
    if (!Set.of(LOCAL, SERVER).containsAll(commands.keySet()))
      throw new IllegalArgumentException("Unknown project command side");
    if (commands.values().stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Command policy must be an object");
    return new Parsed(commandSection(commands.get(LOCAL)), commandSection(commands.get(SERVER)));
  }

  private static Section commandSection(Object value) {
    if (value == null) return null;
    if (!(value instanceof Map<?, ?> fields))
      throw new IllegalArgumentException("Command policy must be an object");
    StringBuilder yaml = new StringBuilder("local:\n");
    Map<String, String> env = null;
    for (var field : fields.entrySet()) {
      if (!(field.getKey() instanceof String key) || !KEYS.contains(key))
        throw new IllegalArgumentException("Unknown command setting");
      Object entry = field.getValue();
      if (key.equals("env")) {
        if (!(entry instanceof Map<?, ?> variables))
          throw new IllegalArgumentException("Command env must be an object");
        env = new LinkedHashMap<>();
        for (var variable : variables.entrySet()) {
          if (!(variable.getKey() instanceof String name)
              || !NAME.matcher(name).matches()
              || !(variable.getValue() instanceof String text)
              || text.matches("(?s).*[\\r\\n\\x00].*"))
            throw new IllegalArgumentException("Invalid command environment variable");
          env.put(name, text);
        }
        continue;
      }
      String literal;
      if (key.equals("inherit")) {
        if (!(entry instanceof List<?> names)
            || names.stream()
                .anyMatch(name -> !(name instanceof String text) || !NAME.matcher(text).matches()))
          throw new IllegalArgumentException("Invalid command inherit array");
        literal = "[" + String.join(", ", names.stream().map(String.class::cast).toList()) + "]";
      } else if (key.equals("shells")) {
        if (!(entry instanceof Boolean))
          throw new IllegalArgumentException("Command shells must be boolean");
        literal = entry.toString();
      } else {
        if (!(entry instanceof String text) || text.matches("(?s).*[\\r\\n\\x00#].*"))
          throw new IllegalArgumentException("Invalid command setting: " + key);
        literal = text;
      }
      yaml.append("  ").append(key).append(": ").append(literal).append('\n');
    }
    var parsed = parse(yaml.toString()).local();
    return new Section(
        parsed.mode(),
        parsed.shells(),
        parsed.inherit(),
        env,
        parsed.timeout(),
        parsed.outputBytes(),
        parsed.isolation());
  }

  /** What one side may do, every key resolved. */
  public record Side(
      String mode,
      boolean shells,
      List<String> inherit,
      Map<String, String> env,
      Duration timeout,
      long outputBytes,
      String isolation) {

    public static final Side DEFAULT =
        new Side(
            OFF, false, DEFAULT_INHERIT, Map.of(), DEFAULT_TIMEOUT, DEFAULT_OUTPUT_BYTES, "none");

    public Side {
      Objects.requireNonNull(mode, "mode");
      inherit = List.copyOf(inherit);
      env = Map.copyOf(env);
      Objects.requireNonNull(timeout, "timeout");
      Objects.requireNonNull(isolation, "isolation");
    }

    /** This side with every key the section set replaced, and nothing else. */
    public Side with(Section section) {
      if (section == null) {
        return this;
      }
      return new Side(
          section.mode() != null ? section.mode() : mode,
          section.shells() != null ? section.shells() : shells,
          section.inherit() != null ? section.inherit() : inherit,
          section.env() != null ? section.env() : env,
          section.timeout() != null ? section.timeout() : timeout,
          section.outputBytes() != null ? section.outputBytes() : outputBytes,
          section.isolation() != null ? section.isolation() : isolation);
    }

    /** This side, forced off, which is what a file that could not be read means. */
    public Side off() {
      return new Side(OFF, shells, inherit, env, timeout, outputBytes, isolation);
    }

    public boolean isOff() {
      return OFF.equals(mode);
    }
  }

  /** One section as written: a key the file did not set is null. */
  public record Section(
      String mode,
      Boolean shells,
      List<String> inherit,
      Map<String, String> env,
      Duration timeout,
      Long outputBytes,
      String isolation) {

    static final Section EMPTY = new Section(null, null, null, null, null, null, null);
  }

  /**
   * A file, parsed. A section the file does not have is null.
   *
   * @param caps the person's caps (spec 2026-09-29 §2), or null for a file with no {@code caps:}
   */
  public record Parsed(Section local, Section server, Caps caps) {

    public static final Parsed EMPTY = new Parsed(null, null, null);

    /** A file with no {@code caps:} — every caller written before caps existed. */
    public Parsed(Section local, Section server) {
      this(local, server, null);
    }
  }

  /**
   * The {@code caps:} section as written; a key the file did not set is null. Spec 2026-09-29 §2:
   * {@code steps} overrides a definition's {@code max-turns}, {@code budget} its {@code
   * max-model-calls}, and {@code auto-continue} is how many caps a run may pass without asking.
   * Measured 2026-09-30 ({@code orc_318DFD3782228160}, 663 minutes and nobody asked): {@code time}
   * is how long a run may go, in minutes, before it asks whether to go on, and {@code
   * failed-checks} how many times its check may fail before it asks.
   *
   * @param steps steps per turn, 1..10000
   * @param budget model calls per run, 1..1000000
   * @param autoContinue caps passed without asking, per run, 0..100
   * @param time minutes a run goes before it asks, 1..{@value #MOST_TIME_MINUTES}
   * @param failedChecks check failures a run takes before it asks, 1..{@value #MOST_FAILED_CHECKS}
   * @param autoIncrease whether the project authorizes repeated finite step and budget grants
   */
  public record Caps(
      Integer steps,
      Integer budget,
      Integer autoContinue,
      Integer time,
      Integer failedChecks,
      Boolean autoIncrease) {

    /** Nothing set: every value is the definition's own. */
    public static final Caps NONE = new Caps(null, null, null, null, null, null);

    public Caps(
        Integer steps, Integer budget, Integer autoContinue, Integer time, Integer failedChecks) {
      this(steps, budget, autoContinue, time, failedChecks, null);
    }

    /** The first three settings alone — every caller written before {@code time} existed. */
    public Caps(Integer steps, Integer budget, Integer autoContinue) {
      this(steps, budget, autoContinue, null, null);
    }
  }

  /** A file that is not this grammar, with the sentence saying where. */
  public static final class Unreadable extends IllegalArgumentException {

    private final int line;

    Unreadable(int line, String sentence) {
      super(line > 0 ? "line " + line + ": " + sentence : sentence);
      this.line = line;
    }

    /** The 1-based line at fault, or 0 for the file as a whole. */
    public int line() {
      return line;
    }
  }

  /** Whether a command's first argument names a shell. */
  public static boolean isShell(String argv0) {
    if (argv0 == null) {
      return false;
    }
    String base = argv0;
    int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
    if (slash >= 0) {
      base = base.substring(slash + 1);
    }
    base = base.toLowerCase(Locale.ROOT);
    if (base.endsWith(".exe")) {
      base = base.substring(0, base.length() - 4);
    }
    return SHELLS.contains(base);
  }

  /**
   * The file's sections.
   *
   * @throws Unreadable for anything outside the grammar
   */
  public static Parsed parse(String text) {
    Objects.requireNonNull(text, "text");
    if (text.startsWith("﻿")) {
      text = text.substring(1);
    }
    String[] lines = text.split("\r?\n", -1);
    Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
    Map<String, Object> current = null;
    String currentName = null;
    Map<String, String> env = null;
    for (int i = 0; i < lines.length; i++) {
      int number = i + 1;
      String raw = lines[i];
      if (raw.indexOf('\t') >= 0) {
        throw new Unreadable(number, "a tab is not allowed; indent with spaces");
      }
      String line = withoutComment(raw, number);
      if (line.isBlank()) {
        continue;
      }
      int indent = 0;
      while (indent < line.length() && line.charAt(indent) == ' ') {
        indent++;
      }
      String body = line.substring(indent).stripTrailing();
      if (indent == 0) {
        if (!body.equals(LOCAL + ":") && !body.equals(SERVER + ":") && !body.equals(CAPS + ":")) {
          throw new Unreadable(
              number, "expected 'local:', 'server:' or 'caps:', not '" + body + "'");
        }
        String name = body.substring(0, body.length() - 1);
        if (sections.containsKey(name)) {
          throw new Unreadable(number, "'" + name + "' appears twice");
        }
        current = new LinkedHashMap<>();
        currentName = name;
        sections.put(name, current);
        env = null;
        continue;
      }
      if (current == null) {
        throw new Unreadable(number, "a setting must be inside 'local:', 'server:' or 'caps:'");
      }
      if (indent == 4 && env != null) {
        String[] pair = pair(body, number);
        if (!NAME.matcher(pair[0]).matches()) {
          throw new Unreadable(number, "'" + pair[0] + "' is not a variable name");
        }
        if (env.containsKey(pair[0])) {
          throw new Unreadable(number, "'" + pair[0] + "' appears twice");
        }
        env.put(pair[0], scalar(pair[1], number));
        continue;
      }
      if (indent != 2) {
        throw new Unreadable(
            number,
            "a setting is indented by two spaces, and a variable" + " under 'env:' by four");
      }
      env = null;
      String[] pair = pair(body, number);
      String key = pair[0];
      String value = pair[1];
      if (CAPS.equals(currentName)) {
        if (!CAP_SETTINGS.contains(key)) {
          throw new Unreadable(
              number,
              "'"
                  + key
                  + "' is not a caps setting; the caps"
                  + " settings are "
                  + String.join(", ", CAP_SETTINGS));
        }
        if (current.containsKey(key)) {
          throw new Unreadable(number, "'" + key + "' appears twice");
        }
        if (key.equals("auto-increase")) {
          String flag = scalar(value, number);
          if (!flag.equals("true") && !flag.equals("false"))
            throw new Unreadable(number, "auto-increase is true or false, not '" + flag + "'");
          current.put(key, Boolean.valueOf(flag));
          continue;
        }
        int least = key.equals("auto-continue") ? 0 : 1;
        int most =
            switch (key) {
              case "steps" -> 10_000;
              case "budget" -> 1_000_000;
              case "time" -> MOST_TIME_MINUTES;
              case "failed-checks" -> MOST_FAILED_CHECKS;
              default -> 100;
            };
        current.put(key, whole(scalar(value, number), least, most, key, number));
        continue;
      }
      if (!KEYS.contains(key)) {
        throw new Unreadable(
            number,
            "'"
                + key
                + "' is not a setting; the settings are "
                + String.join(
                    ", ",
                    List.of("mode", "shells", "inherit", "env", "timeout", "output", "isolation")));
      }
      if (current.containsKey(key)) {
        throw new Unreadable(number, "'" + key + "' appears twice");
      }
      switch (key) {
        case "mode" -> {
          String mode = scalar(value, number);
          if (!Set.of(OFF, GATED, ASK, OPEN).contains(mode)) {
            throw new Unreadable(number, "mode is off, gated, ask or open, not '" + mode + "'");
          }
          current.put(key, mode);
        }
        case "shells" -> {
          String flag = scalar(value, number);
          if (!flag.equals("true") && !flag.equals("false")) {
            throw new Unreadable(number, "shells is true or false, not '" + flag + "'");
          }
          current.put(key, Boolean.valueOf(flag));
        }
        case "inherit" -> current.put(key, list(value, number));
        case "env" -> {
          if (!value.isEmpty()) {
            throw new Unreadable(
                number,
                "env takes its variables on the lines below it," + " indented by four spaces");
          }
          env = new LinkedHashMap<>();
          current.put(key, env);
        }
        case "timeout" -> current.put(key, duration(scalar(value, number), number));
        case "output" -> current.put(key, size(scalar(value, number), number));
        case "isolation" -> {
          String isolation = scalar(value, number);
          if (!isolation.equals("none") && !isolation.equals("bubblewrap")) {
            throw new Unreadable(
                number, "isolation must be none or bubblewrap, not '" + isolation + "'");
          }
          current.put(key, isolation);
        }
        default -> throw new IllegalStateException(key);
      }
    }
    return new Parsed(
        section(sections.get(LOCAL)), section(sections.get(SERVER)), caps(sections.get(CAPS)));
  }

  @SuppressWarnings("unchecked")
  private static Section section(Map<String, Object> keys) {
    if (keys == null) {
      return null;
    }
    return new Section(
        (String) keys.get("mode"),
        (Boolean) keys.get("shells"),
        (List<String>) keys.get("inherit"),
        keys.containsKey("env") ? Map.copyOf((Map<String, String>) keys.get("env")) : null,
        (Duration) keys.get("timeout"),
        (Long) keys.get("output"),
        (String) keys.get("isolation"));
  }

  private static Caps caps(Map<String, Object> keys) {
    if (keys == null) {
      return null;
    }
    return new Caps(
        (Integer) keys.get("steps"),
        (Integer) keys.get("budget"),
        (Integer) keys.get("auto-continue"),
        (Integer) keys.get("time"),
        (Integer) keys.get("failed-checks"),
        (Boolean) keys.get("auto-increase"));
  }

  private static Integer whole(String value, int least, int most, String key, int number) {
    if (!value.matches("[0-9]{1,7}")) {
      throw new Unreadable(number, key + " is a whole number, not '" + value + "'");
    }
    int n = Integer.parseInt(value);
    if (n < least || n > most) {
      throw new Unreadable(number, key + " is from " + least + " to " + most + ", not " + value);
    }
    return n;
  }

  private static String withoutComment(String raw, int number) {
    boolean quoted = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == '#' && !quoted && (i == 0 || raw.charAt(i - 1) == ' ')) {
        return raw.substring(0, i);
      }
    }
    if (quoted) {
      throw new Unreadable(number, "a quoted value is not closed");
    }
    return raw;
  }

  private static String[] pair(String body, int number) {
    int colon = body.indexOf(':');
    if (colon <= 0) {
      throw new Unreadable(number, "expected 'name: value', not '" + body + "'");
    }
    String key = body.substring(0, colon);
    String rest = body.substring(colon + 1);
    if (!rest.isEmpty() && rest.charAt(0) != ' ') {
      throw new Unreadable(number, "put a space after the colon in '" + body + "'");
    }
    return new String[] {key, rest.strip()};
  }

  private static String scalar(String value, int number) {
    if (value.isEmpty()) {
      throw new Unreadable(number, "a value is missing");
    }
    if (value.startsWith("\"")) {
      if (value.length() < 2 || !value.endsWith("\"")) {
        throw new Unreadable(number, "a quoted value is not closed");
      }
      String inner = value.substring(1, value.length() - 1);
      if (inner.indexOf('"') >= 0 || inner.indexOf('\\') >= 0) {
        throw new Unreadable(number, "a quoted value may not contain a quote or a backslash");
      }
      return inner;
    }
    if (value.startsWith("[") || value.startsWith("{") || value.startsWith("'")) {
      throw new Unreadable(number, "'" + value + "' is not a plain value");
    }
    return value;
  }

  private static List<String> list(String value, int number) {
    if (!value.startsWith("[") || !value.endsWith("]")) {
      throw new Unreadable(number, "inherit is a list like [PATH, HOME]");
    }
    String inner = value.substring(1, value.length() - 1).strip();
    List<String> names = new ArrayList<>();
    if (inner.isEmpty()) {
      return List.of();
    }
    for (String part : inner.split(",", -1)) {
      String name = part.strip();
      if (!NAME.matcher(name).matches()) {
        throw new Unreadable(number, "'" + name + "' is not a variable name");
      }
      if (names.contains(name)) {
        throw new Unreadable(number, "'" + name + "' appears twice");
      }
      names.add(name);
    }
    return List.copyOf(names);
  }

  private static Duration duration(String value, int number) {
    Matcher matched = DURATION.matcher(value);
    if (!matched.matches()) {
      throw new Unreadable(
          number,
          "timeout is a number of seconds or minutes, like 90s or" + " 10m, not '" + value + "'");
    }
    long amount = Long.parseLong(matched.group(1));
    Duration duration =
        matched.group(2).equals("s") ? Duration.ofSeconds(amount) : Duration.ofMinutes(amount);
    if (duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(MAX_TIMEOUT) > 0) {
      throw new Unreadable(number, "timeout is from 1s to 60m, not '" + value + "'");
    }
    return duration;
  }

  private static Long size(String value, int number) {
    Matcher matched = SIZE.matcher(value);
    if (!matched.matches()) {
      throw new Unreadable(
          number, "output is a size in KiB or MiB, like 512KiB or 1MiB, not '" + value + "'");
    }
    long amount = Long.parseLong(matched.group(1));
    long bytes = amount * (matched.group(2).equals("KiB") ? 1024L : 1024L * 1024);
    if (bytes < 1024 || bytes > MAX_OUTPUT_BYTES) {
      throw new Unreadable(number, "output is from 1KiB to 8MiB, not '" + value + "'");
    }
    return bytes;
  }
}
