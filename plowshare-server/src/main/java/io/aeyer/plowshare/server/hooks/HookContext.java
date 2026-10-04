package io.aeyer.plowshare.server.hooks;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * What every stage knows about the run or the log it is intercepting.
 *
 * <p>Strings and a set rather than the definition itself, so this package depends on nothing in
 * {@code agents} and {@code agents} may depend on it.
 *
 * @param agent the definition's name. Required on a run stage; on a log stage it is null for a
 *     {@code turn} log, which names no agent (spec 2026-09-28-hooks-reach-the-log §3)
 * @param bot whether it is a character somebody returns to
 * @param tools the tool names it holds: the ones the run is offered, once it has been offered them
 *     ({@link #holding}), and its definition's before; empty on a log stage
 * @param project the project tier's name, or {@code null} for the global tier
 * @param conversation the conversation id, or {@code null} for a run with none. On a log stage it
 *     is the log's id
 * @param side always {@link #SERVER}: every hook runs on this server, a person's local ones
 *     included (spec 2026-09-30-local-hooks-are-served decision 1). Where a command would run is
 *     {@link RunEnvironment#side}
 * @param environment for a {@code run} call only: what the environment allows on the side the
 *     command would run on; {@code null} for every other call
 * @param origin the log's origin wire name ({@code turn}, {@code delegation}, …) on a log stage, an
 *     in-turn one ({@code stage.*}, {@code approval.pre}) included; {@code null} on a run stage
 * @param orchestration the orchestration a stage is about, when one is involved (spec
 *     2026-09-28-hooks-reach-the-log §3); otherwise {@code null}
 */
public record HookContext(
    String agent,
    boolean bot,
    Set<String> tools,
    String project,
    String conversation,
    String side,
    RunEnvironment environment,
    String origin,
    Orchestration orchestration,
    Document document,
    @com.fasterxml.jackson.annotation.JsonIgnore UsageAttribution usage) {

  public record Document(
      String operation,
      String resource,
      String revision,
      long generation,
      String stage,
      int attempt,
      String sourceUri) {
    public Document(
        String operation,
        String resource,
        String revision,
        long generation,
        String stage,
        int attempt) {
      this(operation, resource, revision, generation, stage, attempt, null);
    }
  }

  public HookContext(
      String agent,
      boolean bot,
      Set<String> tools,
      String project,
      String conversation,
      String side,
      RunEnvironment environment,
      String origin,
      Orchestration orchestration) {
    this(
        agent,
        bot,
        tools,
        project,
        conversation,
        side,
        environment,
        origin,
        orchestration,
        null,
        UsageAttribution.LEGACY);
  }

  public HookContext about(Document involved) {
    return new HookContext(
        agent,
        bot,
        tools,
        project,
        conversation,
        side,
        environment,
        origin,
        orchestration,
        involved,
        usage);
  }

  public HookContext(
      String agent,
      boolean bot,
      Set<String> tools,
      String project,
      String conversation,
      String side,
      RunEnvironment environment,
      String origin,
      Orchestration orchestration,
      Document document) {
    this(
        agent,
        bot,
        tools,
        project,
        conversation,
        side,
        environment,
        origin,
        orchestration,
        document,
        UsageAttribution.LEGACY);
  }

  public HookContext withUsage(UsageAttribution owner) {
    return new HookContext(
        agent,
        bot,
        tools,
        project,
        conversation,
        side,
        environment,
        origin,
        orchestration,
        document,
        owner);
  }

  public static final String SERVER = "server";

  /**
   * What a {@code run} call's environment allows, for an allowlist hook to read, and how the
   * command would be isolated — so a later decision can depend on it (spec decision 5).
   *
   * @param side {@code "server"} or {@code "local"} — where the command would run
   * @param mode {@code off}, {@code gated}, {@code ask} or {@code open}
   * @param isolation {@code environment.yml}'s {@code isolation:} for that side; {@code none} until
   *     the isolation slice lets the parser accept another value
   * @param session on the local side, the session whose machine would run the command; {@code null}
   *     on the server side. Never shown to a hook: a local {@code allow} counts only on the log
   *     owner's own machine, and this is how the harness tells (spec
   *     2026-09-30-local-hooks-are-served decision 6)
   */
  public record RunEnvironment(
      String side, String mode, boolean shells, String isolation, String session) {

    public RunEnvironment {
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(isolation, "isolation");
    }

    /** An environment no session serves: the server side, or a fixture's. */
    public RunEnvironment(String side, String mode, boolean shells, String isolation) {
      this(side, mode, shells, isolation, null);
    }
  }

  /**
   * The orchestration a stage is about (spec 2026-09-28-hooks-reach-the-log §3).
   *
   * @param id the run's id
   * @param definition the orchestration definition's name
   * @param stage the stage moving, or {@code null} when the transition is the run's, not a stage's
   */
  public record Orchestration(String id, String definition, String stage) {

    public Orchestration {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(definition, "definition");
    }
  }

  public HookContext {
    if (origin == null) {
      Objects.requireNonNull(agent, "agent");
    }
    Objects.requireNonNull(usage, "usage");
    tools = Set.copyOf(tools);
    Objects.requireNonNull(side, "side");
  }

  /** A run stage's context: no environment yet, and no origin. */
  public HookContext(
      String agent,
      boolean bot,
      Set<String> tools,
      String project,
      String conversation,
      String side) {
    this(agent, bot, tools, project, conversation, side, null, null, null);
  }

  /** A log stage's context (spec 2026-09-28-hooks-reach-the-log §3). */
  public static HookContext forLog(
      String origin, String agent, boolean bot, String project, String log) {
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(log, "log");
    return new HookContext(agent, bot, Set.of(), project, log, SERVER, null, origin, null);
  }

  /** The same context, for one {@code run} call. */
  public HookContext with(RunEnvironment environment) {
    return new HookContext(
        agent,
        bot,
        tools,
        project,
        conversation,
        side,
        environment,
        origin,
        orchestration,
        document,
        usage);
  }

  /**
   * The run's context at a log stage inside its turn ({@code stage.*}, {@code approval.pre}): the
   * run's own fields, and its log's origin so {@code origins:} filters it (spec §2.6).
   *
   * @param logOrigin the run's log's origin wire name, or {@code null} for a run in no log
   */
  public HookContext inLog(String logOrigin) {
    return new HookContext(
        agent,
        bot,
        tools,
        project,
        conversation,
        side,
        environment,
        logOrigin,
        orchestration,
        document,
        usage);
  }

  /**
   * The same context, holding the tools the run was actually offered: its definition's that a tool
   * answers to, and whatever it was handed beyond them. A hook that judges a run by what it can do
   * -- the stuck trap's read-only stretch, its advisor's tool list -- reads these.
   */
  public HookContext holding(Collection<String> offered) {
    return new HookContext(
        agent,
        bot,
        Set.copyOf(offered),
        project,
        conversation,
        side,
        environment,
        origin,
        orchestration,
        document,
        usage);
  }

  /** The same context, about one orchestration. */
  public HookContext about(Orchestration involved) {
    return new HookContext(
        agent,
        bot,
        tools,
        project,
        conversation,
        side,
        environment,
        origin,
        involved,
        document,
        usage);
  }
}
