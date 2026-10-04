package io.aeyer.plowshare.protocol;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * One command, started with no shell, an environment built from nothing, stdin only when given,
 * bounded output and a deadline — the whole of what {@code run} does once a gate has let it
 * through.
 *
 * <h2>Why this is in the protocol module</h2>
 *
 * <p>{@link Window}'s reason once more: the server's {@code LocalProvider} and the Java client both
 * execute this, so a command cannot mean one thing on the server and another on a laptop. The TUI's
 * copy is held to the same cases, {@code
 * src/test/resources/io/aeyer/plowshare/protocol/commands.json}.
 *
 * <h2>What it does not do</h2>
 *
 * <p><b>It does not isolate.</b> A command it starts can read anything its OS user can, hidden
 * paths included — the fence judges the working directory and a gate judges the command line, and
 * neither reaches inside a process. {@code environment.yml}'s {@code isolation} is where that will
 * change; today it accepts only {@code none}, and saying so is better than a check that looks like
 * a sandbox.
 *
 * <p>It does not check the working directory against a fence either. That is each caller's, because
 * each has its own {@code permitted}.
 */
public final class CommandRunner {

  /** How long a process gets after being asked to stop before it is forced. */
  static final Duration GRACE = Duration.ofSeconds(5);

  /**
   * The most input a command is given, in UTF-8 bytes: 64 KiB.
   *
   * <p><b>Bounded as output is, and for the same reason.</b> Input rides in the one {@link
   * FileRequest#RUN} frame to a client, and an acceptance command's {@code stdin:} is written by a
   * model into spec.md (spec 2026-09-29 §1b) — unbounded, a spec could put a frame on the wire
   * larger than the channel takes, which is the {@code 1009} that closed the file channel once
   * already ({@link FileRequest#read}). A person's answers to a game's prompts are a few bytes; 64
   * KiB is room for any such script and far below every frame bound. Every side refuses past it —
   * this runner (the server's {@code LocalProvider} and the Java client), the server before it
   * sends, and the TUI's runner — and the acceptance parser refuses it first, where the conductor
   * can still change the line.
   */
  public static final int MAX_STDIN_BYTES = 64 * 1024;

  /**
   * Why {@code stdin} cannot be given to a command, or null when it can.
   *
   * @param stdin the input, or null for none
   * @return the sentence refusing it, or null when it is within {@link #MAX_STDIN_BYTES}
   */
  public static String stdinRefusal(String stdin) {
    if (stdin == null) {
      return null;
    }
    int bytes = stdin.getBytes(StandardCharsets.UTF_8).length;
    return bytes <= MAX_STDIN_BYTES
        ? null
        : "a command's input is "
            + bytes
            + " bytes, more than the "
            + MAX_STDIN_BYTES
            + " a command may be given";
  }

  private CommandRunner() {}

  /**
   * How much earlier than its deadline a stopped command may report having been stopped and still
   * count as having run for it: the 100 ms each side's wait loop polls at.
   */
  static final Duration RAN_FOR_SLACK = Duration.ofMillis(100);

  /**
   * Whether a command run with {@code runsFor} as its deadline was still running when the deadline
   * came — an acceptance command's {@code runs-for:} (spec 2026-10-01, the acceptance checker §1):
   * the way a command shows a program starts and stays up.
   *
   * <p><b>The deadline is the stop.</b> Every side that runs a command — this runner, the Java
   * client's, the TUI's — stops a command at its deadline and kills its whole tree, so a command
   * given {@code runsFor} as its timeout is stopped exactly as {@code runs-for} asks, on any side,
   * with nothing new on the wire. What is new is only the reading: a timed-out outcome is the pass,
   * and an exit before the deadline — whatever its code — the failure. A side whose own timeout is
   * shorter stops it sooner, and that is no pass: the outcome must show the command ran for the
   * whole of {@code runsFor}.
   *
   * @param outcome how the command ended
   * @param runsFor how long it had to keep running
   * @return whether it was still running at {@code runsFor} and was stopped there
   */
  public static boolean ranFor(Outcome outcome, Duration runsFor) {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(runsFor, "runsFor");
    return outcome.timedOut()
        && !outcome.cancelled()
        && outcome.exitCode() == null
        && outcome.millis() >= runsFor.minus(RAN_FOR_SLACK).toMillis();
  }

  /**
   * What to run, already resolved from the environment.
   *
   * @param stdin what to write to the command's input, or null to close it at once — an acceptance
   *     command's {@code stdin:} (spec 2026-09-29 §1b)
   */
  public record Command(
      List<String> argv,
      Path cwd,
      Map<String, String> env,
      List<String> inherit,
      Duration timeout,
      long outputBytes,
      String stdin) {

    public Command {
      argv = List.copyOf(argv);
      Objects.requireNonNull(cwd, "cwd");
      env = Map.copyOf(env);
      inherit = List.copyOf(inherit);
      Objects.requireNonNull(timeout, "timeout");
    }

    /** No input: stdin is closed at once, as every command before stdin existed. */
    public Command(
        List<String> argv,
        Path cwd,
        Map<String, String> env,
        List<String> inherit,
        Duration timeout,
        long outputBytes) {
      this(argv, cwd, env, inherit, timeout, outputBytes, null);
    }
  }

  /**
   * How a command ended.
   *
   * @param exitCode null when it never exited on its own — timed out or cancelled
   * @param stdoutCut bytes dropped from the front of stdout, which keeps its tail
   */
  public record Outcome(
      Integer exitCode,
      boolean timedOut,
      boolean cancelled,
      String stdout,
      long stdoutCut,
      String stderr,
      long stderrCut,
      long millis) {}

  /** A command that could not be started, with the sentence saying why. */
  public static final class Refused extends IllegalArgumentException {

    Refused(String sentence) {
      super(sentence);
    }
  }

  /**
   * The environment a command starts with: nothing, then the named host variables, then the
   * explicit ones.
   */
  public static Map<String, String> environment(
      Map<String, String> host, List<String> inherit, Map<String, String> env) {
    Map<String, String> built = new LinkedHashMap<>();
    for (String name : inherit) {
      String value = host.get(name);
      if (value != null) {
        built.put(name, value);
      }
    }
    built.putAll(env);
    return built;
  }

  /**
   * The executable {@code argv[0]} names: itself when it has a separator in it, resolved against
   * the working directory, and otherwise the first match on the command's own {@code PATH} — never
   * this process's.
   *
   * @throws Refused when nothing on that {@code PATH} is called that
   */
  public static String executable(String argv0, Path cwd, Map<String, String> environment) {
    if (argv0.isEmpty()) {
      throw new Refused("a command's first argument is the program to run, and it was empty");
    }
    if (argv0.indexOf('/') >= 0 || argv0.indexOf(File.separatorChar) >= 0) {
      Path named = cwd.resolve(argv0);
      if (!Files.isRegularFile(named) || !Files.isExecutable(named)) {
        throw new Refused("there is no program at " + argv0 + " that can be run");
      }
      return named.toString();
    }
    String path = environment.get("PATH");
    if (path != null) {
      for (String directory : path.split(File.pathSeparator)) {
        if (directory.isEmpty()) {
          continue;
        }
        for (String suffix : suffixes()) {
          Path candidate = Path.of(directory, argv0 + suffix);
          if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
            return candidate.toString();
          }
        }
      }
    }
    throw new Refused(
        "no program called '"
            + argv0
            + "' is on this command's PATH"
            + (path == null ? ", which is not set; inherit PATH in environment.yml" : ""));
  }

  private static List<String> suffixes() {
    return File.separatorChar == '\\' ? List.of("", ".exe", ".cmd", ".bat") : List.of("");
  }

  /**
   * Run it to the end, the deadline, or the cancel.
   *
   * @param host the variables {@code inherit} is read from — this process's, outside tests
   * @param cancelled asked every 100 ms while the command runs
   * @throws Refused when the command is empty, the directory is not one, its input is past {@link
   *     #MAX_STDIN_BYTES}, or the program cannot be found or started
   */
  public static Outcome run(Command command, Map<String, String> host, BooleanSupplier cancelled) {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(cancelled, "cancelled");
    if (command.argv().isEmpty()) {
      throw new Refused("a command needs at least the program to run");
    }
    if (!Files.isDirectory(command.cwd())) {
      throw new Refused("the working directory " + command.cwd() + " is not a directory");
    }
    String tooMuchInput = stdinRefusal(command.stdin());
    if (tooMuchInput != null) {
      throw new Refused(tooMuchInput);
    }
    Map<String, String> environment = environment(host, command.inherit(), command.env());
    List<String> argv = new ArrayList<>(command.argv());
    argv.set(0, executable(argv.get(0), command.cwd(), environment));

    ProcessBuilder builder = new ProcessBuilder(argv).directory(command.cwd().toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    long started = System.nanoTime();
    Process process;
    try {
      process = builder.start();
    } catch (IOException unstartable) {
      throw new Refused(
          "'" + command.argv().get(0) + "' could not be started: " + unstartable.getMessage());
    }
    // Input, when the command has some (spec 2026-09-29 §1b's acceptance commands): written on
    // its own thread, so a program that never reads it cannot hold this one, then closed.
    Thread feeder =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (var in = process.getOutputStream()) {
                    if (command.stdin() != null) {
                      in.write(command.stdin().getBytes(StandardCharsets.UTF_8));
                    }
                  } catch (IOException ignored) {
                    // A process that has already exited has no stdin to write to or close.
                  }
                });
    Tail stdout = new Tail(command.outputBytes());
    Tail stderr = new Tail(command.outputBytes());
    Thread outReader = Thread.ofVirtual().start(() -> stdout.drain(process.getInputStream()));
    Thread errReader = Thread.ofVirtual().start(() -> stderr.drain(process.getErrorStream()));

    long deadline = started + command.timeout().toNanos();
    boolean timedOut = false;
    boolean wasCancelled = false;
    try {
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (cancelled.getAsBoolean()) {
          wasCancelled = true;
          break;
        }
        if (System.nanoTime() >= deadline) {
          timedOut = true;
          break;
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      wasCancelled = true;
    }
    if (timedOut || wasCancelled) {
      stop(process);
    }
    join(outReader);
    join(errReader);
    Integer exit = timedOut || wasCancelled ? null : process.exitValue();
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    return new Outcome(
        exit,
        timedOut,
        wasCancelled,
        stdout.text(),
        stdout.dropped(),
        stderr.text(),
        stderr.dropped(),
        millis);
  }

  /** Descendants first, so a child is not re-parented out of reach; then forced. */
  private static void stop(Process process) {
    List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
    tree.forEach(ProcessHandle::destroy);
    process.destroy();
    try {
      process.waitFor(GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    tree.forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /**
   * A reader that stops waiting: a grandchild that outlived the kill and holds the pipe open would
   * otherwise keep this call from ever returning.
   */
  private static void join(Thread reader) {
    try {
      reader.join(GRACE.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** The last {@code limit} bytes of a stream, and how many came before them. */
  static final class Tail {

    private final byte[] ring;
    private int start;
    private int size;
    private long total;

    Tail(long limit) {
      this.ring = new byte[(int) Math.max(1, Math.min(limit, Integer.MAX_VALUE))];
    }

    void drain(InputStream in) {
      byte[] buffer = new byte[8192];
      try (in) {
        int read;
        while ((read = in.read(buffer)) >= 0) {
          add(buffer, read);
        }
      } catch (IOException closed) {
        // The process was killed and its pipe went with it; what was read stands.
      }
    }

    synchronized void add(byte[] bytes, int count) {
      total += count;
      for (int i = 0; i < count; i++) {
        if (size < ring.length) {
          ring[(start + size) % ring.length] = bytes[i];
          size++;
        } else {
          ring[start] = bytes[i];
          start = (start + 1) % ring.length;
        }
      }
    }

    synchronized long dropped() {
      return total - size;
    }

    synchronized String text() {
      byte[] kept = new byte[size];
      for (int i = 0; i < size; i++) {
        kept[i] = ring[(start + i) % ring.length];
      }
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(kept))
            .toString();
      } catch (java.nio.charset.CharacterCodingException unreachable) {
        throw new IllegalStateException(unreachable);
      }
    }
  }
}
