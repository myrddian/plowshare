package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.approvals.ApprovalDelivery;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.hooks.ToolPre;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code run} — start one command in a directory this job can reach, and read how it ended. Spec:
 * {@code implementation rationale}.
 *
 * <h2>The gate is here, and it is not a hook anyone can leave out</h2>
 *
 * <p>Harness hooks come from a model's profile, and a model with no profile has none; project hooks
 * come from a directory a project may not have. So the one check that decides whether a command may
 * start at all — {@link #gate} — is this tool's, and {@code JobRuntime} calls it around the hook
 * chain for every {@code run} call: before the chain it refuses what the environment forbids; after
 * it, an approval a person gave lets the call run, a hook's {@code ask} or mode {@code ask} puts it
 * to a person by ending the turn, and a {@code gated} call no hook explicitly allowed is refused
 * (spec 2026-09-15, asking a person). {@link #run} checks the environment again against whatever
 * arguments actually run, since a hook may have rewritten them.
 *
 * <h2>What it does not do</h2>
 *
 * <p>Isolate. The gate judges a command line and the fence judges a working directory; a command
 * that is let through can read anything its OS user can.
 */
public final class RunTool implements AgentTool {

  public static final String NAME = "run";

  /** What a gate's records are named in the log. */
  static final String GATE = "harness:run-gate";

  private io.aeyer.plowshare.server.files.WorkspaceCodeMap codeMap;

  public RunTool withCodeMap(io.aeyer.plowshare.server.files.WorkspaceCodeMap codeMap) {
    this.codeMap = codeMap;
    return this;
  }

  private final ProviderRouter router;
  private final Supplier<Environments> environments;
  private final BooleanSupplier cancelled;
  private final Supplier<RunApprovalStore> approvals;
  private final String conversation;
  private final String session;
  private final String agent;
  private final TurnEnd end;
  private final Function<String, Optional<ConversationRecord>> roots;
  private final String callerHandle;
  private final RunHooks hooks;
  private final ToolSchema schema;

  /** A run no person can be asked about: every question it would ask is refused. */
  public RunTool(
      ProviderRouter router, Supplier<Environments> environments, BooleanSupplier cancelled) {
    this(router, environments, cancelled, () -> null, null, null, "unknown", null);
  }

  /**
   * @param approvals the store questions and approvals live in; a supplier of null asks nobody
   * @param conversation the run's conversation, or null
   * @param session the session that started the run, or null for one no person started
   * @param end the run's turn ending, which a question trips; null asks nobody
   */
  public RunTool(
      ProviderRouter router,
      Supplier<Environments> environments,
      BooleanSupplier cancelled,
      Supplier<RunApprovalStore> approvals,
      String conversation,
      String session,
      String agent,
      TurnEnd end) {
    this(
        router,
        environments,
        cancelled,
        approvals,
        conversation,
        session,
        agent,
        end,
        ignored -> Optional.empty(),
        null);
  }

  public RunTool(
      ProviderRouter router,
      Supplier<Environments> environments,
      BooleanSupplier cancelled,
      Supplier<RunApprovalStore> approvals,
      String conversation,
      String session,
      String agent,
      TurnEnd end,
      Function<String, Optional<ConversationRecord>> roots) {
    this(
        router, environments, cancelled, approvals, conversation, session, agent, end, roots, null);
  }

  public RunTool(
      ProviderRouter router,
      Supplier<Environments> environments,
      BooleanSupplier cancelled,
      Supplier<RunApprovalStore> approvals,
      String conversation,
      String session,
      String agent,
      TurnEnd end,
      Function<String, Optional<ConversationRecord>> roots,
      String callerHandle) {
    this(
        router,
        environments,
        cancelled,
        approvals,
        conversation,
        session,
        agent,
        end,
        roots,
        callerHandle,
        RunHooks.NONE);
  }

  /**
   * @param hooks the run's in-turn log stages: {@code approval.pre} is asked before a person is
   *     (spec 2026-09-28-hooks-reach-the-log §3)
   */
  public RunTool(
      ProviderRouter router,
      Supplier<Environments> environments,
      BooleanSupplier cancelled,
      Supplier<RunApprovalStore> approvals,
      String conversation,
      String session,
      String agent,
      TurnEnd end,
      Function<String, Optional<ConversationRecord>> roots,
      String callerHandle,
      RunHooks hooks) {
    this.router = Objects.requireNonNull(router, "router");
    this.environments = Objects.requireNonNull(environments, "environments");
    this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.conversation = conversation;
    this.session = session;
    this.agent = Objects.requireNonNull(agent, "agent");
    this.end = end;
    this.roots = Objects.requireNonNull(roots, "roots");
    this.callerHandle = callerHandle;
    this.hooks = Objects.requireNonNull(hooks, "hooks");
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "command",
        ToolArguments.strings(
            "The program and its arguments, one item each, e.g. [\"<the program>\","
                + " \"<its first argument>\"]."
                + " There is no shell: no pipes, redirects, heredocs, globs or &&; give the"
                + " program its input with stdin."));
    properties.put(
        "cwd",
        ToolArguments.string(
            "The absolute directory to run it in, as file_roots spells it. Omit for the"
                + " first root."));
    properties.put(
        "stdin",
        ToolArguments.string(
            "What the program is given as its standard input, whole, as if typed and then"
                + " ended; at most "
                + CommandRunner.MAX_STDIN_BYTES
                + " bytes, and more is"
                + " refused. Omit for none: the program then reads the end of its input at"
                + " once."));
    properties.put(
        "timeout_seconds",
        ToolArguments.integer(
            "The most it may take before it is killed. Omit for the environment's limit;"
                + " a larger number is brought down to it."));
    this.schema =
        ToolSchema.from(NAME, DESCRIPTION, ToolArguments.object(properties, List.of("command")));
  }

  @Override
  public ToolSchema schema() {
    return schema;
  }

  /** One call, parsed and placed: where it would run and what that side allows. */
  record Plan(
      List<String> argv,
      Path cwd,
      FileProvider provider,
      String side,
      EnvironmentFile.Side allowed,
      String offBecause,
      Duration timeout,
      String stdin) {

    /** A plan with no input. */
    Plan(
        List<String> argv,
        Path cwd,
        FileProvider provider,
        String side,
        EnvironmentFile.Side allowed,
        String offBecause,
        Duration timeout) {
      this(argv, cwd, provider, side, allowed, offBecause, timeout, null);
    }

    HookContext.RunEnvironment environment() {
      return new HookContext.RunEnvironment(
          side, allowed.mode(), allowed.shells(), allowed.isolation(), Commands.servedBy(provider));
    }
  }

  /**
   * The gate, around the hook chain: what the model is refused, or the chain's own answer. Never
   * throws for anything a model sent.
   *
   * <p>An allow counts only for the arguments it judged ({@link ToolPre#explicitlyAllowed}) and for
   * the place it judged ({@link #moved}); a call the gate could not place is judged, if a hook
   * rewrites it, with no allow at all. What it lets through is remembered with where it would run,
   * and {@link #run} refuses a command that would now run anywhere else (spec
   * 2026-09-30-local-hooks-are-served decision 6, Task 7 fix round 2).
   *
   * @param chain the hook chain, given the context this call's environment adds
   */
  ToolPre gate(
      String argumentsJson,
      Home home,
      HookContext context,
      java.util.function.BiFunction<HookContext, String, ToolPre> chain) {
    Plan plan;
    try {
      plan = plan(argumentsJson, home);
    } catch (BadArguments | WorkspaceRefusedException refused) {
      return unplaced(argumentsJson, home, context, chain);
    }
    // 1. What the environment itself forbids. No approval reaches past it.
    String before = refusal(plan);
    if (before != null) {
      return new ToolPre(argumentsJson, before, List.of(record(HookRecord.DENY, before)));
    }
    HookContext.RunEnvironment judgedFor = plan.environment();
    ToolPre judged = chain.apply(context.with(judgedFor), argumentsJson);
    if (judged.isDenied()) {
      return judged;
    }
    // A hook may have rewritten the call; what is approved or asked about is what would run.
    if (!judged.arguments().equals(argumentsJson)) {
      try {
        plan = plan(judged.arguments(), home);
      } catch (BadArguments | WorkspaceRefusedException refused) {
        judgedPlaces.put(judged.arguments(), Place.NOWHERE);
        return judged;
      }
      String after = refusal(plan);
      if (after != null) {
        List<HookRecord> records = new ArrayList<>(judged.records());
        records.add(record(HookRecord.DENY, after));
        return new ToolPre(judged.arguments(), after, records);
      }
    }
    List<HookRecord> records = new ArrayList<>(judged.records());
    // An allow is judged for the place the chain was shown. A rewrite that moved the command to
    // another side or machine leaves it with no allow, whichever tier gave it: the mode
    // decides, as if no hook had allowed (spec 2026-09-30-local-hooks-are-served decision 6).
    // explicitlyAllowed already holds only for the exact arguments the allow was given.
    String moved = judged.explicitlyAllowed() ? moved(judgedFor, plan.environment()) : null;
    if (moved != null) {
      records.add(record(HookRecord.NOTE, moved));
    }
    return decide(plan, judged, records, home, judged.explicitlyAllowed() && moved == null, moved);
  }

  /** Why a hook's allow does not count for a call the gate could not place before the chain. */
  static final String UNPLACED_ALLOW =
      "an allow given before this command could be placed was"
          + " judged for no environment, so it does not count for the command a rewrite made";

  /**
   * A call the gate could not place. Unchanged by the chain, it runs and says why, as every other
   * tool's bad arguments do. Rewritten into one that places, it passes the whole gate — an
   * approval, an ask, the mode — with no allow counted: none was judged for an environment (Task 7
   * fix round 2).
   */
  private ToolPre unplaced(
      String argumentsJson,
      Home home,
      HookContext context,
      java.util.function.BiFunction<HookContext, String, ToolPre> chain) {
    ToolPre judged = chain.apply(context, argumentsJson);
    if (judged.isDenied()) {
      return judged;
    }
    Plan plan;
    try {
      plan = plan(judged.arguments(), home);
    } catch (BadArguments | WorkspaceRefusedException refused) {
      judgedPlaces.put(judged.arguments(), Place.NOWHERE);
      return judged;
    }
    if (judged.arguments().equals(argumentsJson)) {
      // It did not place a moment ago and does now: judged for no place, so run refuses it.
      judgedPlaces.put(judged.arguments(), Place.NOWHERE);
      return judged;
    }
    String refused = refusal(plan);
    if (refused != null) {
      List<HookRecord> records = new ArrayList<>(judged.records());
      records.add(record(HookRecord.DENY, refused));
      return new ToolPre(judged.arguments(), refused, records);
    }
    List<HookRecord> records = new ArrayList<>(judged.records());
    String unjudged = judged.explicitlyAllowed() ? UNPLACED_ALLOW : null;
    if (unjudged != null) {
      records.add(record(HookRecord.NOTE, unjudged));
    }
    return decide(plan, judged, records, home, false, unjudged);
  }

  /**
   * Steps 2 and 3 for a placed plan: an approval a person already gave, then the chain's verdict —
   * ask, then an allow that counts, then the mode. What it lets through is remembered with where it
   * would run.
   *
   * @param allowed whether an allow counts for this plan
   * @param lost why an allow the chain gave does not count, said in a gated refusal; or null
   */
  private ToolPre decide(
      Plan plan,
      ToolPre judged,
      List<HookRecord> records,
      Home home,
      boolean allowed,
      String lost) {
    // 2. An approval a person already gave.
    Long projectId = home.isGlobal() ? null : environments.get().projectId(home.project());
    RunApprovalStore store = approvals.get();
    ApprovalTarget target = approvalTarget();
    if (store != null && projectId != null && target != null) {
      Optional<RunApproval> approved =
          store.consume(
              projectId,
              target.conversation(),
              plan.side(),
              plan.argv(),
              plan.cwd().toString(),
              plan.stdin());
      if (approved.isPresent()) {
        records.add(
            record(
                HookRecord.ALLOW,
                "approved by a person ("
                    + approved.get().id()
                    + ", "
                    + approved.get().scope()
                    + ")"));
        return placed(plan, new ToolPre(judged.arguments(), null, records, true));
      }
    }
    // 3. The chain's verdict: ask > explicit allow > silence, which the mode decides.
    String mode = plan.allowed().mode();
    if (judged.asked() != null) {
      return ask(plan, judged, records, projectId, store, target, judged.asked());
    }
    if (allowed || EnvironmentFile.OPEN.equals(mode)) {
      records.add(record(HookRecord.ALLOW, null));
      return placed(plan, new ToolPre(judged.arguments(), null, records, allowed));
    }
    if (EnvironmentFile.ASK.equals(mode)) {
      return ask(plan, judged, records, projectId, store, target, null);
    }
    String why =
        (lost == null ? "" : lost + "; ")
            + "the environment on the "
            + plan.side()
            + " side is gated, and no hook in this project allowed this command; a project"
            + " hook on tool.pre for run has to return { allow: true } for it";
    records.add(record(HookRecord.DENY, why));
    return new ToolPre(judged.arguments(), why, records, false);
  }

  /** What {@link #run} refuses: the place a let-through command was judged for is gone. */
  static final String PLACE_CHANGED =
      "where this command would run changed after it was judged,"
          + " so it did not run; call run again to have it judged where it would run now";

  /**
   * Where a command the gate let through was judged to run: its side, the session serving it (null
   * on the server) and its directory. {@link #NOWHERE} is a call the gate could not place.
   */
  record Place(String side, String session, Path cwd) {
    static final Place NOWHERE = new Place(null, null, null);

    static Place of(Plan plan) {
      return new Place(plan.side(), Commands.servedBy(plan.provider()), plan.cwd());
    }
  }

  /**
   * The places the gate judged, by the final arguments it let through; {@link #run} takes each
   * once. One {@code RunTool} is one run's, so this holds that run's calls between gate and run.
   */
  private final Map<String, Place> judgedPlaces = new ConcurrentHashMap<>();

  private ToolPre placed(Plan plan, ToolPre through) {
    judgedPlaces.put(through.arguments(), Place.of(plan));
    return through;
  }

  /**
   * Why an allow judged for {@code judged} does not carry to {@code now}, or null when both are the
   * same place: the same side, and on the local side the same session's machine.
   */
  static String moved(HookContext.RunEnvironment judged, HookContext.RunEnvironment now) {
    if (!judged.side().equals(now.side())) {
      return "an allow judged for the "
          + judged.side()
          + " side does not carry to a command"
          + " a rewrite moved to the "
          + now.side()
          + " side";
    }
    if (!Objects.equals(judged.session(), now.session())) {
      return "an allow judged for one machine on the "
          + judged.side()
          + " side does not"
          + " carry to a command a rewrite moved to another";
    }
    return null;
  }

  /** What the model is shown for a call a person was asked about. {@link #isWaiting} reads it. */
  static final String WAITING =
      "Waiting for a person to approve this command, so it did not run"
          + " and your turn ends here. They will answer, and you will be told what they decided.";

  /** Whether a gate's refusal is a question put to a person rather than a refusal. */
  static boolean isWaiting(ToolPre pre) {
    return pre.isDenied() && pre.denied().startsWith(WAITING);
  }

  /**
   * 4. Put the call to a person: a stored question, and the turn ended to wait for it — or a
   * refusal saying why nobody can be asked.
   *
   * <p>{@code approval.pre} is asked between the two: only once somebody can be asked, and before
   * the question exists (spec 2026-09-28-hooks-reach-the-log §3).
   */
  private ToolPre ask(
      Plan plan,
      ToolPre judged,
      List<HookRecord> records,
      Long projectId,
      RunApprovalStore store,
      ApprovalTarget target,
      String reason) {
    String nobody = null;
    if (target == null
        || (!target.attended() && target.handle() == null)
        || (target.attended() && session == null)) {
      nobody = "no person started this run, so nobody can be asked";
    } else if (end == null || store == null || projectId == null) {
      nobody = "this run has nowhere to put a question to a person";
    }
    if (nobody != null) {
      String why =
          "this command needs a person's approval"
              + (reason == null
                  ? " (the environment on the " + plan.side() + " side is ask)"
                  : " (" + reason + ")")
              + ", and "
              + nobody
              + "; it did not run";
      records.add(record(HookRecord.DENY, why));
      return new ToolPre(judged.arguments(), why, records, false);
    }
    // APPROVAL.PRE (spec 2026-09-28-hooks-reach-the-log §3), now that a person really is about
    // to be asked, and before any row is written: a denial refuses the command and asks
    // nobody; a note joins the question the person sees. No decision here answers it
    // (decision 5). The run's own chain — its profile, then the project — fails closed.
    // A command given input is asked about with it: the person sees what the program will
    // read, and the approval covers that input only (RunApproval.givenSameInput).
    String shown = RunApproval.withInput(reason, plan.stdin());
    Gate before =
        hooks.approvalPre(
            plan.environment(),
            null,
            new Approving(
                plan.argv(), plan.cwd().toString(), shown, target.attended(), RunApproval.SCOPES));
    records.addAll(before.records());
    if (before.isDenied()) {
      return new ToolPre(judged.arguments(), before.denied(), records, false);
    }
    String asked = before.applyTo(shown);
    RunApproval question =
        target.handle() == null
            ? store.ask(
                projectId,
                target.conversation(),
                conversation,
                target.agent(),
                plan.side(),
                plan.argv(),
                plan.cwd().toString(),
                asked)
            : store.ask(
                projectId,
                target.conversation(),
                conversation,
                target.handle(),
                target.agent(),
                plan.side(),
                plan.argv(),
                plan.cwd().toString(),
                asked);
    end.request(Outcome.Ending.AWAITING, ApprovalDelivery.question(question));
    records.add(
        record(
            HookRecord.ASK,
            reason == null ? "asked: " + question.id() : reason + " — asked: " + question.id()));
    return new ToolPre(judged.arguments(), WAITING + " [" + question.id() + "]", records, false);
  }

  private ApprovalTarget approvalTarget() {
    if (conversation == null) {
      return null;
    }
    Optional<ConversationRecord> found = roots.apply(conversation);
    if (found.isEmpty()) {
      return new ApprovalTarget(conversation, agent, true, null);
    }
    ConversationRecord root = found.get();
    boolean attended = root.origin() == Origin.TURN;
    return new ApprovalTarget(
        root.id(),
        root.agent() == null ? agent : root.agent(),
        attended,
        attended ? null : callerHandle);
  }

  private record ApprovalTarget(
      String conversation, String agent, boolean attended, String handle) {}

  private static HookRecord record(String decision, String reason) {
    return new HookRecord(
        GATE, null, Tier.HARNESS, Stage.TOOL_PRE, NAME, decision, reason, null, null, 0);
  }

  /** What the environment forbids for this plan before any hook is asked, or null. */
  private static String refusal(Plan plan) {
    return Commands.refusal(plan.argv(), plan.side(), plan.allowed(), plan.offBecause());
  }

  Plan plan(String argumentsJson, Home home) {
    JsonNode args =
        ToolArguments.parse(
            argumentsJson,
            NAME,
            "{\"command\": [\"<the program>\", \"<its first argument>\"],"
                + " \"cwd\": \"/srv/repo\"}");
    JsonNode command = args.path("command");
    if (!command.isArray() || command.isEmpty()) {
      throw new BadArguments(
          NAME
              + " needs 'command': the program and its arguments as a list"
              + " of strings, like [\"<the program>\", \"<its first argument>\"].");
    }
    List<String> argv = new ArrayList<>();
    for (JsonNode item : command) {
      if (!item.isTextual()) {
        throw new BadArguments(
            NAME
                + " was given a 'command' item that is not a string: "
                + item
                + ". Every item is one argument, written as a string.");
      }
      argv.add(item.asText());
    }
    if (home.isGlobal()) {
      // Repeated here, ahead of explicitCwd: precedence — the tier refusal comes before
      // any argument error, so a global call with a bad cwd still hears about the tier.
      // Commands.place carries the identical check for the harness's caller, which has
      // no cwd to parse first.
      throw new WorkspaceRefusedException(
          "commands do not run in the global tier, which names"
              + " no filesystem; a job that runs commands runs in a project");
    }
    String shell = shellSyntax(argv);
    if (shell != null) {
      throw new BadArguments(shell);
    }
    String stdin = stdin(args);
    Path requested = explicitCwd(args);
    Commands.Placed placed = Commands.place(router, environments.get(), home, requested, argv);
    EnvironmentFile.Side allowed = placed.allowed();
    long limit = allowed.timeout().toSeconds();
    int asked =
        ToolArguments.optionalInt(
            args,
            "timeout_seconds",
            (int) limit,
            sent ->
                new BadArguments(
                    NAME
                        + " could not read 'timeout_seconds': it is a whole"
                        + " number of seconds, not "
                        + sent
                        + "."));
    if (asked < 1) {
      throw new BadArguments(
          NAME
              + " was given a 'timeout_seconds' of "
              + asked
              + "; it must be"
              + " at least 1. Leave it out for the environment's limit of "
              + limit
              + ".");
    }
    return new Plan(
        placed.argv(),
        placed.cwd(),
        placed.provider(),
        placed.side(),
        allowed,
        placed.offBecause(),
        Duration.ofSeconds(Math.min(asked, limit)),
        stdin);
  }

  /** What stands for a shell operator when it is a whole argument: nothing but a shell reads it. */
  static final Set<String> SHELL_OPERATORS =
      Set.of("|", "||", "&&", ";", ">", ">>", "<", "2>", "2>&1", "&");

  /** A heredoc marker, {@code <<WORD}, {@code <<-WORD} or quoted; group 1 is the word. */
  private static final Pattern HEREDOC =
      Pattern.compile("<<-?\\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\\1");

  /** How much of an offending item a refusal quotes. */
  static final int ITEM_SHOWN = 40;

  /**
   * The call the shell refusal shows, naming no program. Measured 2026-09-30: a coder sent six
   * different heredocs, each refused with "give a program its input with `stdin`", and never found
   * the shape; a call it can copy is shown instead of only a word for it.
   */
  static final String STDIN_SHAPE =
      "{\"command\": [\"<the program>\", \"-\"], \"stdin\": \"<the text>\"}";

  /**
   * Why {@code argv} is shell syntax sent to a program that has no shell to read it, or null.
   * Measured 2026-09-30, orc_318DFD3782228160: a heredoc sent as one argument reached the program
   * as an argument, which ended with a usage error and nothing said why.
   *
   * <p>Refused, each as a whole item: a shell operator ({@link #SHELL_OPERATORS}); an item that
   * starts with {@code <<} or {@code - <<}, a heredoc marker; and an item holding a marker {@code
   * <<WORD} followed, on a later line, by a line that is only {@code WORD} — a heredoc with its
   * body and terminator written into one argument. The same characters anywhere else in an argument
   * are the program's: {@code a; b} after {@code -c} is its text to read.
   *
   * @return the refusal, quoting the offending item and showing {@link #STDIN_SHAPE}, naming no
   *     program; or null
   */
  static String shellSyntax(List<String> argv) {
    for (String item : argv) {
      String offending = offending(item);
      if (offending != null) {
        String shown = offending.replace("\n", "\\n");
        if (shown.length() > ITEM_SHOWN) {
          shown = shown.substring(0, ITEM_SHOWN) + "…";
        }
        return NAME
            + " has no shell: `"
            + shown
            + "` would reach the program as an"
            + " argument, not as its input. Give a program its input with `stdin`, as in "
            + STDIN_SHAPE
            + ", and run one program per call.";
      }
    }
    return null;
  }

  /** The shell syntax one item is, or holds, or null. */
  private static String offending(String item) {
    if (SHELL_OPERATORS.contains(item)) {
      return item;
    }
    if (item.startsWith("<<")) {
      return marker(item, 0);
    }
    if (item.startsWith("- <<")) {
      return marker(item, 2);
    }
    Matcher found = HEREDOC.matcher(item);
    while (found.find()) {
      String word = found.group(2);
      int newline = item.indexOf('\n', found.end());
      if (newline >= 0
          && item.substring(newline + 1).lines().anyMatch(line -> line.strip().equals(word))) {
        return found.group();
      }
    }
    return null;
  }

  /** The heredoc marker starting at {@code from}: the {@code <<WORD} a person would recognise. */
  private static String marker(String item, int from) {
    Matcher found = HEREDOC.matcher(item);
    if (found.find(from) && found.start() == from) {
      return found.group();
    }
    int end = from;
    while (end < item.length() && !Character.isWhitespace(item.charAt(end))) {
      end++;
    }
    return item.substring(from, end);
  }

  /** The call's {@code stdin}, or null when it gives none. */
  private static String stdin(JsonNode args) {
    JsonNode sent = args.path("stdin");
    if (sent.isMissingNode() || sent.isNull()) {
      return null;
    }
    if (!sent.isTextual()) {
      throw new BadArguments(
          NAME
              + " was given a 'stdin' that is not a string: "
              + sent
              + ". It is the program's input, written as one string.");
    }
    String stdin = sent.asText();
    String tooMuch = CommandRunner.stdinRefusal(stdin);
    if (tooMuch != null) {
      throw new BadArguments(
          NAME
              + " was refused its 'stdin': "
              + tooMuch
              + ". Put a larger"
              + " input in a file and give the program the file's path.");
    }
    return stdin;
  }

  private Path explicitCwd(JsonNode args) {
    JsonNode sent = args.path("cwd");
    if (sent.isMissingNode() || sent.isNull()) {
      return null;
    }
    String raw =
        ToolArguments.requireText(
            args, "cwd", NAME, "the absolute directory to run the command in");
    Path path;
    try {
      path = Path.of(raw);
    } catch (InvalidPathException unusable) {
      throw new BadArguments(
          NAME
              + " could not read '"
              + raw
              + "' as a path: "
              + ToolArguments.firstLine(unusable)
              + ". Send a plain absolute path.");
    }
    if (!path.isAbsolute()) {
      throw new BadArguments(
          NAME
              + " needs an absolute 'cwd', and '"
              + raw
              + "' is relative."
              + " Call file_roots and write the whole path out.");
    }
    return path;
  }

  @Override
  public String run(String argumentsJson, Home home) {
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    Objects.requireNonNull(home, "home");
    // What the gate judged for exactly these arguments, if it judged them: the command runs
    // only where it was judged to (Task 7 fix round 2). A call no gate saw — a fixture's —
    // is judged by refusal alone, as before.
    Place judged = judgedPlaces.remove(argumentsJson);
    try {
      Plan plan = plan(argumentsJson, home);
      if (judged != null && !judged.equals(Place.of(plan))) {
        return PLACE_CHANGED;
      }
      // Again, on what actually runs: a hook's rewrite is judged here exactly
      // as the fence judges a rewritten path.
      String refused = refusal(plan);
      if (refused != null) {
        return refused;
      }
      if (codeMap != null) codeMap.beforeMutation(home);
      try {
        CommandRunner.Outcome outcome =
            plan.provider()
                .run(
                    plan.cwd(),
                    plan.argv(),
                    plan.allowed(),
                    plan.timeout(),
                    plan.stdin(),
                    cancelled);
        return render(plan, outcome);
      } finally {
        if (codeMap != null) codeMap.afterMutation(home, List.of());
      }
    } catch (BadArguments | WorkspaceRefusedException correctable) {
      return correctable.getMessage();
    }
  }

  static String render(Plan plan, CommandRunner.Outcome outcome) {
    StringBuilder out = new StringBuilder();
    String took = String.format("%.1fs", outcome.millis() / 1000.0);
    if (outcome.timedOut()) {
      out.append("timed out after ").append(plan.timeout().toSeconds()).append("s and was killed");
    } else if (outcome.exitCode() == null) {
      out.append("cancelled after ").append(took).append(" and was killed");
    } else {
      out.append("exit ").append(outcome.exitCode()).append(" after ").append(took);
    }
    out.append(" in ").append(plan.cwd()).append(" on the ").append(plan.side()).append(" side");
    String status = out.toString();
    section(out, "stdout", outcome.stdout(), outcome.stdoutCut());
    section(out, "stderr", outcome.stderr(), outcome.stderrCut());
    String text = out.toString();
    if (text.length() <= FileTools.MAX_DISPLAY_CHARS) {
      return text;
    }
    // The tail and not the head: a build says what failed at the end. The
    // status line stays first whatever is cut — it is how the command ended,
    // and ToolLines reads the record's outcome off it; a cut answer that
    // opened with the note was recorded as "ran", its exit code gone.
    return status
        + "\n[Cut: the last "
        + FileTools.MAX_DISPLAY_CHARS
        + " of "
        + text.length()
        + " characters are shown. This is a limit on one answer, not the end of the"
        + " output.]\n"
        + text.substring(text.length() - FileTools.MAX_DISPLAY_CHARS);
  }

  private static void section(StringBuilder out, String name, String text, long cut) {
    out.append("\n--- ").append(name);
    if (cut > 0) {
      out.append(" (the first ").append(cut).append(" bytes were dropped to keep the end)");
    }
    out.append(" ---\n");
    out.append(text.isEmpty() ? "(nothing)" : text);
  }

  static final String DESCRIPTION =
      """
            Run one command in a directory this job can reach, and read its exit \
            status and output. The command is a list — the program, then each \
            argument as its own item — and it runs with no shell, so pipes, \
            redirects, globs, && and $VARIABLES mean nothing; run two commands as \
            two calls.

            Its input is `stdin` when you give one — the whole text, written to \
            it as if typed and then ended — and otherwise nothing, so anything \
            that waits for you to type reads the end of its input at once. A \
            heredoc or a redirect written as an argument is refused, since it \
            would reach the program as an argument. It is killed at its timeout. Output that is too long \
            keeps its end, because that is where a build says what failed.

            Whether commands may run at all, whether a shell such as bash may be \
            the program, the timeout, and which variables the command sees are \
            set by the project's environment, separately for the server and for \
            the machine the files are on. A refusal says which setting stopped \
            it; no turn of yours can change one.""";
}
