package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Placing a command on a project's side and running it — what {@link RunTool} does after its gate,
 * and what the harness does for a run's check (spec 2026-09-26). The check has no gate of its own —
 * its consent was given when it was set — but it is judged by the {@code run} tool's hook chain
 * like any {@code run} call: {@link #port}.
 *
 * <p>The side is not chosen: it follows from which provider covers the working directory — a
 * client's {@link RemoteProvider} is {@code local}, anything else {@code server}.
 */
public final class Commands {

  private Commands() {}

  public record Placed(
      List<String> argv,
      Path cwd,
      FileProvider provider,
      String side,
      EnvironmentFile.Side allowed,
      String offBecause) {
    public Placed {
      argv = List.copyOf(argv);
    }

    /**
     * Where this command would run, as a hook is shown it — {@code tool.pre} for {@code run} and
     * {@code approval.pre} (spec 2026-09-28-hooks-reach-the-log decision 5).
     */
    public HookContext.RunEnvironment environment() {
      return new HookContext.RunEnvironment(
          side, allowed.mode(), allowed.shells(), allowed.isolation(), servedBy(provider));
    }
  }

  /**
   * A run's own way to place, judge and run a command, built once per run by {@link JobRuntime}.
   */
  public interface Port {
    Placed place(Home home, Path cwd, List<String> argv);

    /**
     * What the {@code run} tool's tool_pre hook chain says about {@code placed}, asked exactly as
     * {@link RunTool#gate} asks it for a {@code run} call with {@code {"command": argv, "cwd":
     * cwd}}. The environment's own refusal is {@link #refusal}'s, not this.
     */
    Verdict judge(Placed placed);

    CommandRunner.Outcome run(Placed placed);

    /**
     * {@link #run(Placed)} with input — an acceptance command's {@code stdin:} (spec 2026-09-29
     * §1b).
     *
     * @param stdin the input, or null for none
     * @return how the command ended
     */
    default CommandRunner.Outcome run(Placed placed, String stdin) {
      if (stdin == null) {
        return run(placed);
      }
      throw new UnsupportedOperationException("this port cannot give a command input");
    }

    /**
     * {@link #run(Placed, String)} with {@code runsFor} as its deadline in place of the side's own
     * timeout — an acceptance command's {@code runs-for:} (spec 2026-10-01, the acceptance checker
     * §1). Every side stops a command at its deadline and kills its tree, so the deadline is the
     * stop: {@link CommandRunner#ranFor} reads the outcome. The caller has already refused a {@code
     * runsFor} longer than the side lets a command run.
     *
     * @param stdin the input, or null for none
     * @param runsFor how long it must keep running, at most the side's timeout
     * @return how the command ended
     */
    default CommandRunner.Outcome runFor(Placed placed, String stdin, Duration runsFor) {
      throw new UnsupportedOperationException("this port cannot run a command for a time");
    }

    /**
     * The text of {@code relative} under the run's first root — the acceptance gate reading spec.md
     * (spec 2026-09-29 §1b). At most {@link Window#MAX_WINDOW_LINES} lines: one read, the frame's
     * own bound, and a file longer than that is refused rather than cut, since a spec cut short
     * reads as one with no {@code ## Acceptance} section.
     *
     * <p>Here, on the port, because the port is what already knows the run's first root — the
     * directory its commands run in — and reads through the same provider, so spec.md is read from
     * the machine its commands will run on.
     *
     * @param home the run's tier
     * @param relative a project-relative path
     * @return its text
     * @throws WorkspaceRefusedException when there is no such file, or no root to read it in
     */
    default String read(Home home, String relative) {
      throw new UnsupportedOperationException("this port reads no files");
    }
  }

  /**
   * The hooks' answer about one command: denied, with the reason; asked about, with the reason a
   * person is shown; or neither, which is allowed. A deny beats an ask, as in the gate.
   */
  public record Verdict(String denied, String asked) {
    public static final Verdict ALLOWED = new Verdict(null, null);
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * The port a run is handed: {@link #place} over its own router, {@link Port#judge} through its
   * own hook chain, and {@link Port#run} under its own cancel.
   *
   * <h2>Judged by the run tool's chain, not by a second copy of it</h2>
   *
   * <p>Spec 2026-09-26 §3 runs the check "through the same path as a {@code run} tool call", and
   * {@link RunTool#gate} puts a hook's deny ahead of even a person's approval — so a check that
   * skipped the chain let a model-chosen command run on the person's machine on every done move
   * with no hook consulted (final review F1). {@code chain} is the very function the gate is handed
   * in the run loop, and {@code context} the run's own hook context, given the same {@link
   * HookContext.RunEnvironment} the gate adds for the side the command would run on.
   *
   * <p><b>A rewrite denies.</b> A {@code run} call runs a hook's rewrite in place of what the model
   * sent; a check cannot, because what it runs is exactly what a person consented to when it was
   * set. So a hook that would change the command refuses the check instead, in words that say so,
   * rather than running either the original past the hook or a command nobody approved. A chain
   * that throws refuses too: a check nobody could judge does not run.
   *
   * @param cancelled the job's own cancel, asked while a command runs — final review F4: a constant
   *     here left a cancelled run's check running until the side's timeout
   * @param context the run's hook context, before any command's environment is added
   * @param chain the run's tool_pre chain for the {@code run} tool, as the loop hands the gate
   */
  public static Port port(
      ProviderRouter router,
      Environments environments,
      BooleanSupplier cancelled,
      HookContext context,
      BiFunction<HookContext, String, ToolPre> chain) {
    Objects.requireNonNull(router, "router");
    Objects.requireNonNull(environments, "environments");
    Objects.requireNonNull(cancelled, "cancelled");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(chain, "chain");
    return new Port() {
      @Override
      public Placed place(Home home, Path cwd, List<String> argv) {
        return Commands.place(router, environments, home, cwd, argv);
      }

      @Override
      public Verdict judge(Placed placed) {
        String arguments = runArguments(placed);
        ToolPre judged;
        try {
          judged = chain.apply(context.with(placed.environment()), arguments);
        } catch (RuntimeException broken) {
          return new Verdict(
              "a hook failed while judging it, and a check nobody could" + " judge does not run",
              null);
        }
        if (judged.isDenied()) {
          return new Verdict(judged.denied(), null);
        }
        if (!judged.arguments().equals(arguments)) {
          return new Verdict(
              "a hook on tool.pre for run rewrote it, and a check runs"
                  + " exactly the command it was set with or not at all",
              null);
        }
        return judged.asked() != null ? new Verdict(null, judged.asked()) : Verdict.ALLOWED;
      }

      @Override
      public CommandRunner.Outcome run(Placed placed) {
        return placed
            .provider()
            .run(
                placed.cwd(),
                placed.argv(),
                placed.allowed(),
                placed.allowed().timeout(),
                cancelled);
      }

      @Override
      public CommandRunner.Outcome run(Placed placed, String stdin) {
        return placed
            .provider()
            .run(
                placed.cwd(),
                placed.argv(),
                placed.allowed(),
                placed.allowed().timeout(),
                stdin,
                cancelled);
      }

      @Override
      public CommandRunner.Outcome runFor(Placed placed, String stdin, Duration runsFor) {
        return placed
            .provider()
            .run(placed.cwd(), placed.argv(), placed.allowed(), runsFor, stdin, cancelled);
      }

      @Override
      public String read(Home home, String relative) {
        if (home.isGlobal()) {
          throw new WorkspaceRefusedException(
              "the global tier names no filesystem, so" + " there is no " + relative + " to read");
        }
        Path root = firstRoot(router, home);
        Path path = root.resolve(relative).normalize();
        Span span =
            router.providerFor(home, path).read(path, Window.of(0, Window.MAX_WINDOW_LINES));
        if (span.more()) {
          throw new WorkspaceRefusedException(
              relative
                  + " is longer than one read of "
                  + Window.MAX_WINDOW_LINES
                  + " lines or "
                  + Window.MAX_WINDOW_BYTES
                  + " bytes, so the harness cannot read it whole");
        }
        return String.join("\n", span.lines());
      }
    };
  }

  /** What a {@code run} call for {@code placed} would carry, as the chain reads one. */
  private static String runArguments(Placed placed) {
    Map<String, Object> call = new LinkedHashMap<>();
    call.put("command", placed.argv());
    call.put("cwd", placed.cwd().toString());
    try {
      return JSON.writeValueAsString(call);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * @param cwd the directory to run in, or null for the first root the home reaches
   */
  public static Placed place(
      ProviderRouter router, Environments environments, Home home, Path cwd, List<String> argv) {
    Objects.requireNonNull(argv, "argv");
    if (home.isGlobal()) {
      throw new WorkspaceRefusedException(
          "commands do not run in the global tier, which names"
              + " no filesystem; a job that runs commands runs in a project");
    }
    Path where = cwd != null ? cwd : firstRoot(router, home);
    FileProvider provider = router.providerFor(home, where);
    boolean local = provider instanceof RemoteProvider;
    String session = local ? ((RemoteProvider) provider).session() : null;
    Environments.Resolved resolved = environments.resolve(home.project(), session);
    return new Placed(
        argv,
        where,
        provider,
        local ? EnvironmentFile.LOCAL : EnvironmentFile.SERVER,
        local ? resolved.local() : resolved.server(),
        local ? resolved.localWhy() : resolved.serverWhy());
  }

  /**
   * The session whose machine {@code provider} runs a command on, or null for the server's own
   * disk: what a local {@code allow} is held to (spec 2026-09-30-local-hooks-are-served decision
   * 6).
   */
  static String servedBy(FileProvider provider) {
    return provider instanceof RemoteProvider remote ? remote.session() : null;
  }

  private static Path firstRoot(ProviderRouter router, Home home) {
    for (FileProvider provider : router.providersFor(home)) {
      List<Path> roots = provider.roots();
      if (!roots.isEmpty()) {
        return roots.get(0);
      }
    }
    throw new WorkspaceRefusedException(
        "this job reaches no directory to run a command in;" + " call file_roots to see why");
  }

  /** What the environment forbids before any hook or approval is asked, or null. */
  public static String refusal(
      List<String> argv, String side, EnvironmentFile.Side allowed, String offBecause) {
    if (allowed.isOff()) {
      return offBecause != null
          ? offBecause
          : "commands do not run on the "
              + side
              + " side of this project: its"
              + " environment.yml has mode off there, which is also the default";
    }
    if (EnvironmentFile.isShell(argv.get(0)) && !allowed.shells()) {
      return "'"
          + argv.get(0)
          + "' is a shell, and the environment on the "
          + side
          + " side does not allow one (shells: false); run the program directly, with its"
          + " arguments as separate items";
    }
    return null;
  }

  /** The last {@code lines} lines of stdout, then of stderr, each under its name. */
  public static String tail(CommandRunner.Outcome outcome, int lines) {
    return "--- stdout ---\n"
        + last(outcome.stdout(), lines)
        + "\n--- stderr ---\n"
        + last(outcome.stderr(), lines);
  }

  private static String last(String text, int lines) {
    if (text == null || text.isEmpty()) {
      return "(nothing)";
    }
    List<String> all = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
  }
}
