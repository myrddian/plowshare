package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.ModelJson;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads one sentence into a {@link ScheduleProposal}: {@code schedule.read}'s whole work.
 *
 * <h2>The scribe's shape, not a job</h2>
 *
 * <p>One synchronous call through {@link JobRuntime#requestFor} and {@link LlmDispatcher#complete},
 * the answer read with {@link ModelJson}, the definition looked up by name in the boot registry —
 * exactly {@code agents.scribe.Scribe} and {@code agents.learner.Learner}. Not a {@code JobRuntime}
 * run: there is no loop, no tool, and a person waiting on the frame, and a job would add a job row
 * and a stream for an answer that is one JSON object. The agent's {@code schema:} rides on its
 * resolved sampling, so the request constrains the answer's shape without this class naming a
 * schema of its own.
 *
 * <p><b>Unlike the scribe, a failure here is the caller's to see.</b> The scribe files a write flat
 * rather than lose it; there is nothing to fall back to when a person asked what a sentence means,
 * so every reading this class cannot use is a {@link CallerFault} saying what was wrong, and
 * nothing is proposed. A model or transport failure ({@code LlmException}) is not the caller's
 * fault either: it becomes {@link ModelUnavailableException}, {@code MODEL_UNAVAILABLE} on the
 * wire, with a sentence that names no endpoint.
 *
 * <h2>What the model decides, and what it never does</h2>
 *
 * <p>The model reads four things: the cron, the words for it, the agent and the task, plus whether
 * the sentence asked for the result "here" (it is told today's date in the request's zone, from the
 * same clock the fire times count from). Every one of them is checked before it is proposed — the
 * cron by {@link CronSchedule#parse}, the agent against the list the model was shown <em>and</em>
 * by {@link Callers#requireAgent} for the tier the trigger will run in — so a proposal a person
 * confirms is one {@code schedule.define} and {@code trigger.define} will accept.
 *
 * <p>Three things are never the model's. <b>The fire times</b> are {@link CronSchedule#nextAfter}
 * chained from an injected clock, because they are what the person checks the reading against, and
 * a model computing them would be checking itself. <b>The names</b> are generated from the task and
 * a random suffix, because a name is an identity in two stores and a model inventing one could
 * collide with a schedule somebody else defined. <b>The destination</b> is decided from the
 * request: a conversation the request did not carry cannot be written into, whatever the sentence
 * said.
 */
public final class ScheduleReader implements UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  public boolean accountingEnabled() {
    return usageOwners != UsageOwners.NONE;
  }

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private static final Logger log = LoggerFactory.getLogger(ScheduleReader.class);

  /** The agent this looks for in the registry, and the stem of its file. */
  public static final String AGENT = "schedule_reader";

  /**
   * How long a reading is willing to wait to <em>start</em>.
   *
   * <p>The scribe's budget doubled, and the difference is what waits: a scribe that does not start
   * files a memory flat and nobody notices, where a reading that does not start is a refusal in
   * front of the person who asked for it. Bounds queueing only, as the scribe's does; generation is
   * bounded by the pool's own {@code chat-timeout}.
   */
  static final Duration BUDGET = Duration.ofSeconds(10);

  /** How many fire times a proposal shows: enough to see a weekday rule skip a weekend. */
  static final int FIRES = 3;

  /** How many of the task's words a name is made of, before its suffix. */
  static final int NAME_WORDS = 3;

  /**
   * How much of a refused answer {@link #refused} logs, roughly — enough for whoever debugs a
   * refusal to see the shape of what the model actually said, and short enough that one wordy field
   * cannot turn a single refusal into a wall in the log.
   */
  static final int LOGGED_ANSWER_LIMIT = 500;

  /**
   * What {@code schedule.read} asked for.
   *
   * @param text the sentence; required
   * @param zone an IANA zone the sentence's times are in, or null for UTC
   * @param project the tier the client is in, or null for global; where an inbox result's trigger
   *     runs
   * @param conversation the conversation the client is in, or null; used only when the sentence
   *     asks for the result there. May come with a project.
   */
  public record Request(String text, String zone, String project, String conversation) {}

  private final LlmDispatcher dispatcher;
  private final Supplier<AgentRegistry> agents;
  private final DefinitionResolver resolver;
  private final Callers callers;
  private final Supplier<Instant> clock;
  private final Supplier<String> suffix;

  /**
   * The production one: the wall clock and a random suffix.
   *
   * @param agents the boot registry, resolved late, for the scribe's reason: the registry and the
   *     tool layer are built in an order a constructor-time lookup would freeze. The reader's own
   *     definition comes from here and never from a project's tier, so a project cannot redefine
   *     what reads its sentences.
   */
  public ScheduleReader(
      LlmDispatcher dispatcher,
      Supplier<AgentRegistry> agents,
      DefinitionResolver resolver,
      Callers callers) {
    this(dispatcher, agents, resolver, callers, Instant::now, ScheduleReader::randomSuffix);
  }

  /**
   * @param clock where "the next three fires" count from — a parameter so a test asserts the exact
   *     instants
   * @param suffix four hex characters for a name — a parameter so a test asserts the name
   */
  ScheduleReader(
      LlmDispatcher dispatcher,
      Supplier<AgentRegistry> agents,
      DefinitionResolver resolver,
      Callers callers,
      Supplier<Instant> clock,
      Supplier<String> suffix) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.agents = Objects.requireNonNull(agents, "agents");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.suffix = Objects.requireNonNull(suffix, "suffix");
  }

  /**
   * The proposal {@code request}'s sentence reads as, checked, and saved nowhere.
   *
   * @throws CallerFault for a sentence that cannot be read, or a reading this server will not
   *     propose — the message says which, and nothing was proposed
   */
  public ScheduleProposal read(Request request, String account) {
    return read(
        request,
        usageOwners.in(
            request.project() == null
                ? io.aeyer.plowshare.protocol.Home.global()
                : io.aeyer.plowshare.protocol.Home.of(request.project()),
            account,
            UsageAttribution.Operation.SCHEDULE_READ));
  }

  public ScheduleProposal read(Request request) {
    return read(
        request,
        usageOwners.in(
            request.project() == null
                ? io.aeyer.plowshare.protocol.Home.global()
                : io.aeyer.plowshare.protocol.Home.of(request.project()),
            null,
            UsageAttribution.Operation.SCHEDULE_READ));
  }

  public ScheduleProposal read(Request request, UsageAttribution owner) {
    Objects.requireNonNull(request, "request");
    if (request.text() == null || request.text().isBlank()) {
      throw new CallerFault(
          "there is no sentence to read: 'text' is blank. Nothing was" + " proposed.");
    }
    // The zone's refusal is CronSchedule's own, asked before the model so that a misspelt
    // zone costs no call. The expression is a placeholder that always parses.
    ZoneId zone = CronSchedule.parse("0 0 0 * * *", request.zone()).zone();
    Instant now = clock.get();

    // Two homes, because where the result goes is not known until the sentence is read:
    // the inbox under the request's project (global when it names none), or the
    // conversation, whose home is its own project. The model is offered everything either
    // could run, and the choice is checked below against the one the reading actually used.
    DefinitionResolver.Caller inbox =
        owner.accountHandle() == null
            ? callers.callerFor(request.project(), null)
            : callers.callerFor(request.project(), null, owner.accountHandle());
    DefinitionResolver.Caller here =
        request.conversation() == null
            ? null
            : callers.callerForConversation(request.conversation(), null);
    List<AgentDefinition> inboxAgents = runnable(resolver.forCaller(inbox));
    List<AgentDefinition> hereAgents =
        here == null ? List.of() : runnable(resolver.forCaller(here));
    List<String> forInbox = names(inboxAgents);
    List<String> forHere = names(hereAgents);
    // By name, the project's description winning when both tiers define one: one line per
    // name the model may answer with.
    Map<String, AgentDefinition> offered = new TreeMap<>();
    inboxAgents.forEach(each -> offered.putIfAbsent(each.name(), each));
    hereAgents.forEach(each -> offered.putIfAbsent(each.name(), each));
    if (offered.isEmpty()) {
      throw new CallerFault(
          "this tier can run no agents, so there is nothing a schedule"
              + " could run. Nothing was proposed.");
    }
    // Offered only when it can be chosen: a default bot nothing here can run would be a choice
    // the check below refuses, and the prompt says to take the default. The project's tier
    // speaks first, since that is the tier the client is in.
    Optional<String> defaultBot =
        resolver
            .defaultBot(inbox)
            .or(() -> here == null ? Optional.empty() : resolver.defaultBot(here))
            .map(DefinitionResolver.DefaultBot::name)
            .filter(offered::containsKey);

    AgentDefinition definition = definition();
    String said;
    try {
      said =
          dispatcher
              .complete(
                  JobRuntime.requestFor(
                          definition,
                          List.of(
                              ChatMessage.system(definition.prompt()),
                              ChatMessage.user(
                                  opening(
                                      request.text(),
                                      zone,
                                      now,
                                      List.copyOf(offered.values()),
                                      defaultBot))))
                      .withBudget(BUDGET)
                      .withAttribution(
                          owner.forOperation(
                              UsageAttribution.Operation.SCHEDULE_READ, definition.name())))
              .content();
    } catch (LlmException away) {
      // LlmSaturatedException included: a reading that did not start within its budget is,
      // to the person waiting, a model that is not there. Only LlmException, as the scribe
      // draws it: an IllegalArgumentException out of ChatRequest for a misconfigured
      // definition is this server being wrong, and stays the 500 that says so. The cause is
      // logged here and never reaches the wire, where its message could name an endpoint.
      log.warn("the model that reads schedules could not be reached", away);
      throw new ModelUnavailableException(
          "the model that reads schedules could not be"
              + " reached; nothing was proposed and nothing was saved",
          away);
    }

    io.aeyer.plowshare.server.agents.ModelAnswers.Schedule answer;
    try {
      answer = io.aeyer.plowshare.server.agents.ModelAnswers.schedule(said);
    } catch (ModelJson.Unreadable why) {
      throw refused(
          said,
          "the reading of that sentence came back in a shape this server"
              + " could not use ("
              + why.getMessage()
              + "). Nothing was proposed; try"
              + " rephrasing it.");
    }
    String unreadable = answer.unreadable();
    if (!unreadable.isEmpty()) {
      throw refused(
          said,
          "that sentence could not be read into a schedule: "
              + unreadable
              + ". Nothing was proposed.");
    }

    String cron = answer.cron();
    if (cron.isEmpty()) {
      // Not CronSchedule's own refusal, which quotes "0 0 9 * * *" as an example: that
      // reads as though the person's own sentence had been echoed back wrong, when what
      // actually happened is the model gave no cron at all and said nothing was unreadable
      // either. See the class javadoc and schedule_reader.md's `required:` list.
      throw refused(
          said,
          "the reading gave no schedule for that sentence; try saying the"
              + " time another way. Nothing was proposed.");
    }
    cron = sixFields(cron);
    CronSchedule schedule;
    try {
      schedule = CronSchedule.parse(cron, zone.getId());
    } catch (CallerFault unusable) {
      throw refused(
          said,
          "the reading chose a schedule this server cannot use: "
              + unusable.getMessage()
              + ". Nothing was proposed.");
    }
    // Where the result goes is the model's to say (and ours to bound) before the agent is,
    // because a blank agent is chosen here against that home.
    boolean into = answer.intoConversation() && here != null;
    String agent = answer.agent();
    if (agent.isEmpty()) {
      agent = serverBot(said, into ? here : inbox, into ? hereAgents : inboxAgents);
    }
    if (!offered.containsKey(agent)) {
      throw refused(
          said,
          "the reading chose an agent this tier can't run: '"
              + agent
              + "'. It can run "
              + offered.keySet()
              + ". Nothing was proposed.");
    }
    String task = answer.task();
    if (task.isEmpty()) {
      throw refused(
          said,
          "the reading gave the agent no task. Nothing was proposed; say"
              + " what '"
              + agent
              + "' is to do.");
    }
    String when = answer.when();

    // Into the conversation only when the sentence asked AND the request carried one; any
    // other reading goes to the inbox, under the request's own project (decided above).
    if (!(into ? forHere : forInbox).contains(agent)) {
      throw refused(
          said,
          "the reading chose '"
              + agent
              + "', which can't run where its"
              + " result goes: "
              + (into
                  ? "this conversation"
                  : "the inbox, "
                      + (request.project() == null
                          ? "globally"
                          : "under '" + request.project() + "'"))
              + ", which can run "
              + (into ? forHere : forInbox)
              + ". Nothing was proposed;"
              + " name one of those, or say where the result should go.");
    }
    // The same check the saved trigger will meet, against the same home.
    try {
      callers.requireAgent(agent, into ? here : inbox);
    } catch (CallerFault notRunnable) {
      throw refused(said, notRunnable.getMessage());
    }

    List<Instant> fires = new ArrayList<>(FIRES);
    Instant after = now;
    for (int i = 0; i < FIRES; i++) {
      after = schedule.nextAfter(after);
      fires.add(after);
    }
    String name = slug(task, suffix.get());
    return new ScheduleProposal(
        cron,
        zone.getId(),
        when.isEmpty() ? cron : when,
        agent,
        task,
        into,
        into ? null : request.project(),
        into ? request.conversation() : null,
        fires,
        new ScheduleProposal.Names(name, name, name));
  }

  /**
   * A name for a proposal: the task's first words, lower-cased, joined by {@code -}, then the
   * suffix. Anything that is not an ASCII letter or digit separates words, so a name is always
   * something a person can type back into {@code schedule.forget}; a task with no such words at all
   * is named {@code schedule}.
   */
  static String slug(String task, String suffix) {
    String words =
        Arrays.stream(task.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(word -> !word.isEmpty())
            .limit(NAME_WORDS)
            .collect(Collectors.joining("-"));
    return (words.isEmpty() ? "schedule" : words) + "-" + suffix;
  }

  /**
   * The model's cron, normalised to Spring's six fields when it looks like classic Unix cron
   * instead.
   *
   * <p>Small models are trained on the five-field Unix form far more often than Spring's six, and
   * answer with it even when the prompt asks for six fields and gives examples — a stable failure
   * of the small {@code fast} model this reader uses, not a one-off. A five-field Unix expression
   * maps onto Spring's form by prepending a seconds field of {@code 0}: minute, hour, day-of-month,
   * month and day-of-week keep their meaning and their order, only shifted one place right.
   * Anything else — six fields already, or a field count neither form uses — is returned unchanged
   * (whitespace aside) and left for {@link CronSchedule#parse} to accept or refuse exactly as
   * before this method existed.
   *
   * <p>One semantic difference is worth knowing before trusting this: classic cron ORs day-of-month
   * and day-of-week when both are restricted (a match on either fires it), where Spring's parser
   * ANDs them (both must match). A sentence that restricted both would therefore read differently
   * than a person familiar with classic cron might expect. This method does not detect or warn
   * about that case; the guard against it is the confirmation's computed {@link
   * ScheduleProposal#nextFires}, which is what the person actually checks the reading against
   * before confirming it.
   *
   * @param cron the model's answer for the {@code cron} field, already known non-empty
   * @return {@code cron}, trimmed and with internal whitespace collapsed to single spaces, prefixed
   *     with {@code "0 "} when that leaves exactly five fields
   */
  static String sixFields(String cron) {
    if (cron == null) {
      return cron;
    }
    String collapsed = cron.strip().replaceAll("\\s+", " ");
    if (collapsed.isEmpty()) {
      return collapsed;
    }
    int fields = collapsed.split(" ").length;
    return fields == 5 ? "0 " + collapsed : collapsed;
  }

  /**
   * Four hex characters: enough that two readings of one task rarely share a name, and a collision
   * is refused by the store rather than overwriting, since a name belongs to its defining account.
   */
  private static String randomSuffix() {
    return String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
  }

  /**
   * The bot a sentence that names nobody goes to, chosen in code rather than guessed by the model:
   * the default bot of the home the result goes to, when that home can run it; else the only server
   * bot that home can run; else a refusal naming the bots there are.
   *
   * <p>"Server bot" means a {@code bot: true} definition resolved <em>without a session</em> — the
   * shipped {@code bots/}, {@code global/bots} and the project's own {@code bots/}. Every caller
   * this class resolves has a null session, so a connected client's {@code .plowshare/bots} never
   * appears here, and neither its default nor its bots can be chosen for a schedule that runs long
   * after that client has gone.
   *
   * @param home the caller the saved trigger will run as
   * @param runnable the exported definitions that home resolves, sorted by name
   */
  private String serverBot(
      String said, DefinitionResolver.Caller home, List<AgentDefinition> runnable) {
    Optional<String> preferred =
        resolver
            .defaultBot(home)
            .map(DefinitionResolver.DefaultBot::name)
            .filter(name -> names(runnable).contains(name));
    if (preferred.isPresent()) {
      return preferred.get();
    }
    List<String> bots =
        runnable.stream().filter(AgentDefinition::bot).map(AgentDefinition::name).toList();
    if (bots.size() == 1) {
      return bots.get(0);
    }
    throw refused(
        said,
        "say which bot should do it: "
            + (bots.isEmpty() ? "no bots are defined on this server" : String.join(", ", bots))
            + ". Nothing was proposed.");
  }

  private AgentDefinition definition() {
    AgentRegistry registry = agents.get();
    if (registry == null || !registry.names().contains(AGENT)) {
      throw new CallerFault(
          "this server defines no agent named '"
              + AGENT
              + "', so a"
              + " sentence cannot be read here. Define the schedule and the trigger"
              + " directly with schedule.define and trigger.define.");
    }
    return registry.get(AGENT);
  }

  private static List<String> names(List<AgentDefinition> agents) {
    return agents.stream().map(AgentDefinition::name).toList();
  }

  /** The exported ones, by name: what {@link Callers#requireAgent} would let a trigger run. */
  private static List<AgentDefinition> runnable(AgentRegistry registry) {
    return registry.byName().values().stream()
        .filter(AgentDefinition::exported)
        .sorted(Comparator.comparing(AgentDefinition::name))
        .toList();
  }

  private static String opening(
      String sentence,
      ZoneId zone,
      Instant now,
      List<AgentDefinition> runnable,
      Optional<String> defaultBot) {
    LocalDate today = now.atZone(zone).toLocalDate();
    StringBuilder message = new StringBuilder();
    message.append("The sentence:\n").append(sentence.strip()).append("\n\n");
    message.append("The zone its times are in: ").append(zone.getId()).append("\n");
    // The day in the zone and not in UTC: late evening in one is already tomorrow in another,
    // and "tomorrow" or "on weekdays" is read from where the person is.
    message
        .append("Today in ")
        .append(zone.getId())
        .append(" is ")
        .append(today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
        .append(", ")
        .append(today)
        .append(".\n\n");
    message.append("The agents you may choose from:\n");
    for (AgentDefinition agent : runnable) {
      message
          .append("- ")
          .append(agent.name())
          .append(agent.bot() ? " (bot): " : " (agent): ")
          .append(
              agent.description() == null
                  ? ""
                  : agent.description().strip().replaceAll("\\s+", " "))
          .append('\n');
    }
    message
        .append('\n')
        .append(
            defaultBot
                .map(name -> "This tier's default bot: " + name)
                .orElse("This tier has no default bot."));
    return message.toString();
  }

  /**
   * A refusal, logged at WARN with the raw answer it was refused for — the thing production could
   * never see before: a reading came back unusable and nobody could tell whether the model had left
   * a field out, invented one, or answered something this class does not even check for. Every
   * refusal reached once an answer exists takes its {@code CallerFault} from here rather than
   * constructing one directly, so none of them is missed.
   *
   * <p>Never the prompt and never the agent list: both are large, neither is what went wrong, and a
   * prompt in a log is a prompt a operator has to scroll past every time this fires.
   *
   * @param rawAnswer the model's answer, verbatim off the transport, truncated to {@link
   *     #LOGGED_ANSWER_LIMIT} characters before it is written
   * @param message the caller-facing refusal, unwrapped into the fault this returns
   */
  private CallerFault refused(String rawAnswer, String message) {
    log.warn(
        "a schedule reading was refused ({}); the model answered: {}",
        message,
        truncated(rawAnswer));
    return new CallerFault(message);
  }

  private static String truncated(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= LOGGED_ANSWER_LIMIT
        ? text
        : text.substring(0, LOGGED_ANSWER_LIMIT) + "...";
  }
}
