package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRunTool;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.agents.ConductorActions;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.ProjectCaps;
import io.aeyer.plowshare.server.agents.RunLimits;
import io.aeyer.plowshare.server.agents.RunTool;
import io.aeyer.plowshare.server.agents.StructuredAnswers;
import io.aeyer.plowshare.server.agents.StructuredQuestions;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.orchestrations.OrchestrationMessage.Kind;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.StageSeeding;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The orchestration engine: a run is started, asks, finishes, is answered and is cancelled here,
 * and every ending of its conductor's turn is decided here — spec §5.
 *
 * <h2>The row decides, not the ending alone</h2>
 *
 * <p>{@link #ended} re-reads the run before it does anything, and routes on the ending <em>and</em>
 * the row's state together. A turn that ends {@code ANSWERED} means three different things
 * depending on the row: the conductor finished (deliver the result), the conductor stopped talking
 * without asking or finishing (nudge it), or somebody already ended the run (nothing). The ending
 * alone could not tell them apart, which is the whole reason the conductor's tools write to the
 * database before they trip the turn's end.
 *
 * <h2>Every change of state is the store's compare-and-set</h2>
 *
 * <p>A stop that lost re-reads the row, and delivers its ending only if it has ended and nobody has
 * delivered that ending yet. Whoever won usually delivered already, but not always: {@code finish}
 * commits inside the conductor's tool, and the turn can still end {@code CANCELLED}, {@code
 * UNAVAILABLE} or {@code SUB_AGENT_FAILED} rather than {@code ANSWERED}. Delivery re-reads the
 * delivered mark itself, so a cancel followed by the cancelled job's own {@code CANCELLED} ending
 * still tells the caller once.
 *
 * <h2>A cap is a question, not an ending</h2>
 *
 * <p>Decision 7. A running conductor that hits its turn cap or its model-call budget asks its
 * caller whether to go on; the answer, read by {@link #speakAnswerIfPending}, raises the cap or
 * caps the run. Only a caller with nowhere to be asked — no TURN conversation that exists, no
 * account, and no live conductor parent — has its run capped at once.
 *
 * <h2>So is "stuck", and it is the person's</h2>
 *
 * <p>Spec 2026-09-28. A conductor that ends a third turn in a row without progress used to fail the
 * run {@code stuck}; measured that day on {@code orc_3187D648AC346812}, the person got the word and
 * nothing else, and the bot that started the run invented a reason for it. Now {@link
 * #nudgeOrRestart} asks the <em>person</em> instead, on the cap question's own machinery ({@link
 * #STUCK}): {@link Delivery} puts it in their inbox and never in a model's conversation, a model's
 * {@link #answerAsModel} is refused, and the person's answer resumes the run with its nudges
 * forgotten. Only a run with no account behind it, which no person could answer, still fails.
 *
 * <p>So are the acceptance checker's (spec 2026-10-01): the concerns it could not resolve with the
 * conductor ({@link #CONCERNS}), and the product check at the acceptance stage ({@link
 * #PRODUCT_CHECK}) — the {@code check:} lines and what the checker could not check itself. Each
 * answer — accept, or the person's direction — is spoken to the conductor.
 *
 * <h2>A conductor may be a caller too</h2>
 *
 * <p>{@link #startNested} is the whole nesting rule: the depth cap, the states a child may be
 * started from, and the parent's own row as the child's caller. A parent either waits on its row —
 * {@code waiting}, entered inside the tool call and left only by {@link #wake} — or carries on and
 * is nudged as any running conductor is. {@link #speakToParent} is the door a child's report climbs
 * back through, and every ending cancels the live descendants below it, deepest first — each row
 * stopped and then its conductor's own live job cancelled.
 */
public final class Orchestrations implements ConductorActions, UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private static final Logger log = LoggerFactory.getLogger(Orchestrations.class);

  /** How many nudges, and separately how many restarts, a run is allowed before it fails. */
  static final int MAX_NUDGES_OR_RESTARTS = 2;

  static final String TURN_CAP = "turn_cap";
  static final String CALL_BUDGET = "call_budget";

  /**
   * The cap a run passing its project's {@code caps: time} asks about (V69): asked as a turn cap is
   * — auto-continue first, then the model and the person at once — and every "go on" grants another
   * span of that many minutes. Measured 2026-09-29/30, {@code orc_318DFD3782228160}: a root went
   * 663 minutes, every turn under its step cap and every run under its budget, and nothing ever
   * asked the person whether to go on.
   */
  static final String TIME_CAP = "time_cap";

  /**
   * The harness question a run asks the person instead of failing {@code stuck}: its {@code
   * pending_cap} and its answer's {@code cap_kind}, V58. The one kind only a person may answer —
   * {@link OrchestrationStore#answerUnlessPersonOnly} refuses a model's answer to it, and {@link
   * Delivery} sends it to the person's inbox rather than into any model's conversation.
   */
  static final String STUCK = "stuck";

  /**
   * The harness question the retired acceptance verifier asked the person (V65): whether spec.md's
   * acceptance section stood as written. Nothing asks it any more (spec 2026-10-01: the acceptance
   * checker takes the verifier's place); it is still person-only, so a row V65 wrote is answered by
   * the person alone, and its answer is passed to the conductor as their words.
   */
  static final String UNCOVERED = "uncovered";

  /**
   * The harness question a run with an acceptance checker asks the person when the checker could
   * not resolve concerns with the conductor (V77, spec 2026-10-01 §3): each concern with the
   * conductor's reason and why the checker does not accept it. The person accepts the reason or
   * directs a change, per concern. Only a person may answer it: two models disagreeing are not
   * settled by a third.
   */
  static final String CONCERNS = "concerns";

  /**
   * The harness question the acceptance stage asks once every {@code run:} line passed and the
   * section has {@code check:} lines, or concerns the checker could not check itself (V77, spec
   * 2026-10-01 §1): how the product starts and what to check. Accept, and the stage is done; any
   * other answer is the person's notes, and the run goes back. Only a person may answer it: a
   * requirement no command can observe is accepted by the person, never by a model.
   */
  static final String PRODUCT_CHECK = "product_check";

  /**
   * The harness question a run asks the person once its check — or its acceptance commands — has
   * failed its project's {@code caps: failed-checks} times since it started or since they last
   * answered (V69), in place of refusing the move again. Measured 2026-09-29/30, {@code
   * orc_318DFD3782228160}: phase 05-bullet's check failed 21 times over 183 minutes, and 07-sounds'
   * 13, whose output showed at once what no model in the run was fixing. Only a person may answer
   * it, as {@link #STUCK}: a repeating failure is exactly what they must see, and it is never
   * auto-continued.
   */
  static final String CHECK_FAILURES = "check_failures";

  /**
   * The harness question {@code orchestration_install} asks (V71, spec
   * 2026-09-29-orchestration-studio §3.4): whether to write a draft into the project. Only a person
   * may answer it: the model that drafted a definition does not also approve it.
   */
  static final String INSTALL = "install";

  /** Every harness question only the person may answer. */
  static final Set<String> PERSON_ONLY =
      Set.of(STUCK, UNCOVERED, CHECK_FAILURES, INSTALL, CONCERNS, PRODUCT_CHECK);

  /**
   * @param kind a run's {@code pending_cap}, or an answer's {@code cap_kind}; null for none
   * @return whether it is a question only the person may answer
   */
  static boolean personOnly(String kind) {
    return kind != null && PERSON_ONLY.contains(kind);
  }

  /** The failure of a run whose conductor ended CALL_FAILURES (spec 2026-09-28 §4). */
  static final String KEPT_WRITING_CALLS = "kept writing tool calls as text";

  /** Who a conductor's question is recorded as asked by. */
  static final String CONDUCTOR = "conductor";

  /** What {@link #lostRace} says a run in the wrong state cannot do, for a nested start. */
  private static final String START_A_CHILD = "start a child";

  /**
   * What starts a run.
   *
   * @param callerConversation the conversation that started it, or {@code null}
   * @param callerHandle the admin that started it, or {@code null}
   * @param callerSession the session the caller was attached to, or {@code null}; each conductor
   *     turn passes it only while it is live — Decision 2
   * @param parent the orchestration whose conductor started this one, or {@code null} for a root
   * @param depth how many parents this run has; a root is 0. {@link #startNested} is the only thing
   *     that passes anything else, and the database refuses a parent without a depth or a depth
   *     without a parent
   */
  public record Start(
      OrchestrationDefinition definition,
      Home home,
      String request,
      String context,
      String callerConversation,
      String callerAgent,
      String callerHandle,
      String callerSession,
      String parent,
      int depth) {

    public Start {
      Objects.requireNonNull(definition, "definition");
      Objects.requireNonNull(home, "home");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(callerAgent, "callerAgent");
    }
  }

  /**
   * What a nested start came to: a sentence for the conductor to read, or the child's row. Exactly
   * one of the two is non-null.
   *
   * <p>A lost {@code waitFor} is a refusal <em>with</em> a child already running, so its sentence
   * names that child rather than the row being returned as if the wait had taken — the conductor
   * must know there is something out there to follow.
   *
   * @param waiting whether the parent's row is actually waiting on this child now. {@code wait} was
   *     what the conductor asked for; this is what happened. A child that had already ended by the
   *     time the wait was taken leaves its parent running, and the tool over this must not tell the
   *     conductor to stop and wait for a report that has already been and gone
   */
  public record Started(String refusal, OrchestrationRecord run, boolean waiting) {

    public Started {
      if ((refusal == null) == (run == null)) {
        throw new IllegalArgumentException(
            "a Started is either a refusal or a run, never both and never neither");
      }
      if (waiting && run == null) {
        throw new IllegalArgumentException("a refused start has no child to be waiting on");
      }
    }
  }

  private final CallerAccess access;
  private final OrchestrationStore store;
  private final ConversationStore conversations;
  private final StageSeeding seeding;
  private final TodoLists todos;
  private final UnitOfWork work;
  private final ConductorVoice voice;
  private final DeliveryPort delivery;
  private final Set<String> knownTools;
  private final UnaryOperator<AgentDefinition> conductorChecks;
  private final Predicate<String> sessionLive;
  private final Supplier<Instant> clock;
  private final Consumer<OrchestrationRecord> changed;
  private final Consumer<String> cancelJobsIn;
  private final int maxDepth;

  /**
   * Answer message ids being spoken right now, so a drain that fires while this process is still
   * inside {@link ConductorVoice#speak} for one does not speak it a second time.
   */
  private final Set<String> speakingAnswers = ConcurrentHashMap.newKeySet();

  /**
   * {@link #INSTALL} answer ids the installer has settled, by the run that asked, held while a busy
   * conductor leaves the answer pending: an answer whose speak is refused and retried is not
   * installed a second time — the second write would leave the new text as its own {@code .prev}.
   * Kept by run too, so {@link #recordEnded} can let a run's entries go.
   */
  private final Map<String, String> installsSettled = new ConcurrentHashMap<>();

  /**
   * What each settled {@link #INSTALL} answer is to speak, by answer id, until it is spoken: a
   * retry speaks the first settle's outcome again rather than settling again.
   */
  private final Map<String, String> installOutcomes = new ConcurrentHashMap<>();

  /**
   * A resumed sub-agent's result that found its conductor busy, by run, waiting for that
   * conductor's next free — {@link #speakDelegateResultIfPending}. Held here, in memory, because
   * nothing persisted can carry an arbitrary utterance: pending answers are rebuilt from their
   * messages, and a child's report is the parent-delivery route's. A restart loses one; the run is
   * then restarted as any interrupted run is, and its conductor reads the sub-agent's answer from
   * its conversation. implementation rationale records it.
   */
  private final Map<String, String> pendingDelegateResults = new ConcurrentHashMap<>();

  /**
   * Runs whose last acceptance answer arrived while their conductor was speaking, waiting for its
   * next free — {@link #speakAcceptanceAnswersIfPending} (Task 10 review, finding 5). Only the run
   * is held: the words are rebuilt from the approvals when it is spoken, so they are never stale. A
   * restart loses the mark; the run is then restarted as any interrupted run is, and marking
   * acceptance done again reads the answers from the approvals themselves.
   */
  private final Set<String> pendingAcceptanceAnswers = ConcurrentHashMap.newKeySet();

  /**
   * @param conductorChecks the server's definition checks over one conductor: the checked
   *     definition (sampling resolved), or an {@link IllegalStateException} with the refusal
   *     sentence. Applied to every conductor rebuilt from a pinned source, so a nudge, an answer or
   *     a restart speaks with what the first turn spoke with
   * @param changed told of a run after every committed change to its state — {@code start}, {@code
   *     ask}, {@code askAboutCap}, {@code answer}, {@code finish} and a winning {@code stopAndTell}
   *     — with the row freshly re-read. Never let to throw into the engine: a listener that fails
   *     costs the run nothing, on {@link Delivery}'s own precedent
   * @param cancelJobsIn told a conductor conversation whose live job is to be cancelled, for every
   *     descendant an ending cascades over — {@code OrchestrationCancel}'s own {@code JobStore}
   *     filter, handed in as a seam so the engine keeps knowing nothing about jobs. A row is always
   *     stopped before its job is cancelled, so the job's own {@code CANCELLED} ending finds the
   *     run already ended and delivers nothing twice. The run this engine was asked to stop keeps
   *     its own job for {@code OrchestrationCancel}: a run that finished on the very turn still
   *     winding down needs that turn to deliver its result
   * @param maxDepth how deep a tree of runs may go — {@code plowshare.orchestrations.max-depth}. A
   *     root is depth 0, so this is the last depth a child may be started at
   */
  public Orchestrations(
      OrchestrationStore store,
      ConversationStore conversations,
      StageSeeding seeding,
      TodoLists todos,
      UnitOfWork work,
      ConductorVoice voice,
      DeliveryPort delivery,
      Set<String> knownTools,
      UnaryOperator<AgentDefinition> conductorChecks,
      Predicate<String> sessionLive,
      Supplier<Instant> clock,
      Consumer<OrchestrationRecord> changed,
      Consumer<String> cancelJobsIn,
      int maxDepth,
      CallerAccess access) {
    this.access = access;
    this.store = Objects.requireNonNull(store, "store");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
    this.seeding = Objects.requireNonNull(seeding, "seeding");
    this.todos = Objects.requireNonNull(todos, "todos");
    this.work = Objects.requireNonNull(work, "work");
    this.voice = Objects.requireNonNull(voice, "voice");
    this.delivery = Objects.requireNonNull(delivery, "delivery");
    this.knownTools = Set.copyOf(knownTools);
    this.conductorChecks = Objects.requireNonNull(conductorChecks, "conductorChecks");
    this.sessionLive = Objects.requireNonNull(sessionLive, "sessionLive");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.changed = Objects.requireNonNull(changed, "changed");
    this.cancelJobsIn = Objects.requireNonNull(cancelJobsIn, "cancelJobsIn");
    this.maxDepth = maxDepth;
  }

  /**
   * Where a conductor turn that leaves its run live sends the command approvals raised in it —
   * whatever it ended on, since an approval's AWAITING can lose to another ending: {@code
   * JobRuntime.deliverApprovalQuestions}, wired by the bean. Unset, nothing is drained, which is
   * every test that never raises one.
   */
  private volatile Consumer<String> approvalDrain = conversation -> {};

  public void useApprovalDrain(Consumer<String> drain) {
    this.approvalDrain = Objects.requireNonNull(drain, "drain");
  }

  /**
   * Where a run's check is stored, how a person is asked to allow it, and how that approval is read
   * back by id — unset on a server that does not wire checks, in which case {@link #setCheck}
   * refuses every call.
   */
  private volatile OrchestrationChecks checks;

  private volatile CheckConsent checkConsent;
  private volatile Function<String, Optional<RunApproval>> checkApprovals;

  public void useChecks(
      OrchestrationChecks checks,
      CheckConsent consent,
      Function<String, Optional<RunApproval>> approvals) {
    this.checks = Objects.requireNonNull(checks, "checks");
    this.checkConsent = Objects.requireNonNull(consent, "consent");
    this.checkApprovals = Objects.requireNonNull(approvals, "approvals");
  }

  /**
   * Shown a check before the person is asked about it (V67); {@link CommandJudge#NONE}, which asks
   * the person every time, until wired.
   */
  private volatile CommandJudge judge = CommandJudge.NONE;

  /**
   * The command judge a check is shown before the person is asked to allow it (V67).
   *
   * @param judge the judge
   */
  public void useJudge(CommandJudge judge) {
    this.judge = Objects.requireNonNull(judge, "judge");
  }

  /**
   * Withdraws a still-asked approval by id, as asked for a run that has ended ({@link
   * RunApproval#RUN_ENDED}). Unset, nothing is withdrawn, which is every test that never asks.
   */
  private volatile Consumer<String> withdrawal = id -> {};

  /**
   * How an ended run's still-asked approvals are withdrawn — its check's and its acceptance
   * commands' ({@link #recordEnded}, {@link #setCheck}).
   *
   * @param withdraw withdraws one approval by id, if it is still asked
   */
  public void useWithdrawal(Consumer<String> withdraw) {
    this.withdrawal = Objects.requireNonNull(withdraw, "withdraw");
  }

  /**
   * Where a run's acceptance commands are registered — unset on a server that does not wire
   * acceptance, in which case an approval is never taken for one of them.
   */
  private volatile OrchestrationAcceptance acceptance;

  /**
   * Where a run's acceptance commands are registered — spec 2026-09-29 §1b.
   *
   * @param acceptance the registered commands, read to tell an acceptance command's approval from
   *     any other
   */
  public void useAcceptance(OrchestrationAcceptance acceptance) {
    this.acceptance = Objects.requireNonNull(acceptance, "acceptance");
  }

  /**
   * Where what happens to a run is recorded for a person — spec 2026-09-28, the orchestration
   * record. {@link OrchestrationRecorder#NONE} until wired, which records nothing; an
   * implementation never throws, so every call below is made plainly.
   */
  private volatile OrchestrationRecorder recorder = OrchestrationRecorder.NONE;

  public void useRecorder(OrchestrationRecorder recorder) {
    this.recorder = Objects.requireNonNull(recorder, "recorder");
  }

  /**
   * The acceptance checker's harness side (spec 2026-10-01) — unset on a server that wires no
   * checker, in which case {@code checker_answer} records nothing and no concern exists.
   */
  private volatile Checking checking;

  public void useChecking(Checking checking) {
    this.checking = Objects.requireNonNull(checking, "checking");
  }

  /** What settles an {@link #INSTALL} answer: the Studio, which writes or declines. */
  public interface Installer {
    /**
     * @return what the conductor is spoken: installed where, declined, or refused and why
     */
    String settle(
        OrchestrationRecord run, OrchestrationMessage question, OrchestrationMessage answer);

    Installer NONE =
        (run, question, answer) ->
            "Nothing was installed: this server has no" + " Studio to install with.";
  }

  private volatile Installer installer = Installer.NONE;

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Set once at wiring, as {@link #useRecorder} is. */
  public void useInstaller(Installer installer) {
    this.installer = Objects.requireNonNull(installer, "installer");
  }

  /**
   * What settles an install answer here — the wiring's own test reads it, so a context that left
   * {@link Installer#NONE} in place cannot ship unnoticed.
   */
  public Installer installer() {
    return installer;
  }

  /**
   * The log stages (spec 2026-09-28-hooks-reach-the-log): the conductor's log opens after the start
   * commits and closes when the run is terminal. {@link LogStages#NONE} until wired; an
   * implementation never throws.
   */
  private volatile LogStages logStages = LogStages.NONE;

  public void useLogStages(LogStages logStages) {
    this.logStages = Objects.requireNonNull(logStages, "logStages");
  }

  /**
   * Where a project's caps are read, and a live conductor turn's limits found — spec 2026-09-29 §2.
   * Unset, no project has caps and no turn is found in flight, which is every test that never sets
   * one.
   */
  private volatile CapsSource caps = CapsSource.NONE;

  private volatile Function<String, Optional<RunLimits>> liveLimits =
      conversation -> Optional.empty();

  /**
   * A budget {@link #applyCaps} set for a run with no turn in flight, spoken with its next turn —
   * spec 2026-09-29 §2. Held here rather than written to the conversation row because the turn's
   * {@code maxModelCalls} is the one door a total reaches a turn through, and a row written now
   * would be overwritten by whatever the turn in flight elsewhere writes back.
   */
  private final Map<String, Integer> pendingBudgets = new ConcurrentHashMap<>();

  /**
   * The cap question each asking child's parent was last nudged about, by child — so a parent is
   * nudged once about a cap the person holds too, and then left to them ({@link #nudgeOrRestart}).
   * Keyed by child, and forgotten when the child ends, so it holds at most one entry per live
   * child. Lost on restart, which costs one more nudge.
   */
  private final Map<String, String> capNudged = new ConcurrentHashMap<>();

  /** Whether a {@code /cap} budget is held for {@code orchestration}'s next turn — for tests. */
  boolean holdsPendingBudget(String orchestration) {
    return pendingBudgets.containsKey(orchestration);
  }

  /**
   * @param caps a project's caps, read at every start and every rebuilt conductor
   * @param liveLimits a conductor conversation's turn in flight, if one is
   */
  public void useCaps(CapsSource caps, Function<String, Optional<RunLimits>> liveLimits) {
    this.caps = Objects.requireNonNull(caps, "caps");
    this.liveLimits = Objects.requireNonNull(liveLimits, "liveLimits");
  }

  /**
   * The conductor with its project's caps in place of its definition's numbers. Read every time, so
   * a changed file is a later turn's steps. Caps that cannot be read leave the definition's own: a
   * run is never stopped, or started without a ceiling, over a file.
   */
  private AgentDefinition capped(AgentDefinition conductor, String project) {
    try {
      return caps.capsFor(project).applyTo(conductor);
    } catch (RuntimeException unreadable) {
      log.warn(
          "the caps for project {} could not be read; the definition's own stand",
          project,
          unreadable);
      return conductor;
    }
  }

  /**
   * A project's caps, applied to this account's live runs in it — spec 2026-09-29 §2, "applied to
   * runs already going at their next step". A conductor turn in flight is moved through its {@link
   * RunLimits}, which its loop reads at every step boundary; an idle run gets its steps on its next
   * turn (every turn is rebuilt with them) and its budget with that turn. A budget below what a run
   * has spent is never set below the spending — nothing is refunded, and the conversation row
   * cannot hold a total below it: a turn in flight is set to what it spent and stops at its next
   * call; an idle run's next turn gets one call past it (see {@code speak}). Either way the run
   * then asks, as any spent budget does.
   *
   * <p>Only {@code steps} and {@code budget}: {@code auto-continue} is read when a cap is reached,
   * not held by a run. Only the caller's own runs, because {@code /cap} is a person acting on their
   * project, not on somebody else's work in it.
   *
   * @param project the project whose caps were read
   * @param handle the account whose runs they are applied to
   * @param applied the caps as read now
   * @return how many live runs they were applied to
   */
  public int applyCaps(String project, String handle, ProjectCaps applied) {
    Integer steps = applied.steps().value();
    Integer budget = applied.budget().value();
    int count = 0;
    // Every live run, not the newest 200 rows (Task 12's review): an account with many
    // finished runs in the project would otherwise leave an older live one uncapped.
    for (OrchestrationRecord run : store.liveByCaller(handle, project)) {
      Optional<RunLimits> limits = liveLimits.apply(run.conductorConversation());
      if (limits.isPresent()) {
        // As the budget below: a turn with no step cap has no ceiling to move, and one
        // is not invented for it (Task 12's review).
        if (steps != null && limits.get().cap().capped()) {
          limits.get().cap().changeTo(steps);
        }
        // An uncapped budget has no ceiling to move, and a cap is not invented for it.
        if (budget != null && limits.get().budget().capped()) {
          limits.get().budget().changeTo(Math.max(budget, limits.get().budget().spent()));
        }
      } else if (budget != null) {
        // Measured against the spending when it is spoken, in speak, not now.
        pendingBudgets.put(run.id(), budget);
      }
      count++;
    }
    return count;
  }

  // --- start --------------------------------------------------------------------------------

  /**
   * Start a run: the conductor's conversation, the row and the locked stage items in one
   * transaction, then — only once that has committed — the conductor's first turn.
   *
   * <p>A first turn that is refused fails the run with the refusal and tells the caller; the row is
   * returned either way, as it now stands.
   *
   * @throws RuntimeException whatever the transaction threw, in which case nothing was written
   */
  public OrchestrationRecord start(Start start) {
    return start(start, null, null);
  }

  private record CommittedStart(OrchestrationRecord run, boolean created) {}

  /** A caller-supplied durable key; repeated submissions never speak another first turn. */
  public OrchestrationRecord start(Start start, java.util.UUID requestId, String payload) {
    access.requireWork(start.home().project(), start.callerHandle());
    access.requireSession(start.callerSession(), start.callerHandle());
    OrchestrationDefinition definition = start.definition();
    // The person's caps (spec 2026-09-29 §2): the budget the conversation is logged with and
    // the first turn's steps. Every later turn is rebuilt, and capped again, in rebuild.
    AgentDefinition conductor = capped(definition.conductor(), start.home().project());
    List<OrchestrationDefinition.Stage> ownStages = stagesFor(definition, start.parent() != null);
    List<StageRules.Stage> stages =
        ownStages.stream()
            .map(
                stage ->
                    new StageRules.Stage(
                        stage.id(),
                        stage.mayReturnTo(),
                        stage.checked(),
                        stage.acceptance(),
                        stage.holdsPhases()))
            .toList();
    CommittedStart committed =
        work.inTransaction(
            () -> {
              Supplier<OrchestrationRecord> insert =
                  () -> {
                    ConversationRecord conversation =
                        conversations.log(
                            Origin.ORCHESTRATION,
                            start.home(),
                            conductor.name(),
                            null,
                            Budget.of(conductor.maxModelCalls()),
                            // The caller's account owns the conductor's log (amendment 2).
                            start.callerHandle());
                    OrchestrationRecord inserted =
                        store.insert(
                            new NewOrchestration(
                                definition.name(),
                                definition.tier(),
                                definition.hash(),
                                definition.source(),
                                definition.origin(),
                                stages,
                                definition.maxReturns(),
                                start.home().project(),
                                conversation.id(),
                                start.callerConversation(),
                                start.callerAgent(),
                                start.callerHandle(),
                                start.callerSession(),
                                start.parent(),
                                start.depth()));
                    seeding.seedStages(
                        conversation.id(),
                        ownStages.stream()
                            .map(stage -> new StageSeeding.Seed(stage.id(), stageText(stage)))
                            .toList());
                    return inserted;
                  };
              if (requestId == null) return new CommittedStart(insert.get(), true);
              var receipt = store.receiveStart(start.callerHandle(), requestId, payload, insert);
              return new CommittedStart(store.find(receipt.id()).orElseThrow(), receipt.created());
            });
    OrchestrationRecord run = committed.run();
    if (!committed.created()) return run;
    // log.open, after the transaction committed and before the first turn is submitted, so
    // no hook holds a transaction open (spec 2026-09-28-hooks-reach-the-log decision 9). And
    // before the run is announced: a person told of it could stop it, and a log.close that
    // looked the log's local hooks up before this pinned them would find none (spec
    // 2026-09-30-local-hooks-are-served, plan choice 13; the miss is only believed a minute).
    //
    // A root reads its caller's session's local hooks; a nested child inherits its parent
    // conductor's, which startNested passes as the caller conversation (spec
    // 2026-09-30-local-hooks-are-served decision 4).
    boolean nested = start.parent() != null;
    logStages.opened(
        new LogStages.LogOpened(
            run.conductorConversation(),
            Origin.ORCHESTRATION,
            start.home(),
            conductor.name(),
            conductor.bot(),
            null,
            nested ? null : start.callerSession(),
            nested ? start.callerConversation() : null));
    announce(run.id());
    TodoItem phaseItem = start.parent() == null ? null : phaseItemOf(start.parent()).orElse(null);
    String phase = start.parent() == null ? null : phaseOf(start.parent());
    String own = artifactsDir(definition.artifacts(), definition.name(), run.id(), clock.get());
    String parentDir = start.parent() == null ? null : parentArtifactsDir(start.parent());
    store.placed(
        run.id(),
        Utterances.artifactsPath(own, parentDir, phase),
        phaseItem == null ? null : phaseItem.id());
    // THE CHECKER (spec 2026-10-01 §2), pinned only on a run that keeps a stage it checks: a
    // phase run has its acceptance dropped, and its root's checker covers the product.
    if (definition.checker() != null
        && ownStages.stream()
            .anyMatch(
                stage -> OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(stage.acceptance()))) {
      store.pinChecker(run.id(), definition.checker());
    }
    // Before the first turn, so the story opens with the run and not with its first call.
    recorder.runStarted(run, start.request(), phase);
    String utterance =
        io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram.isScript(
                definition.source())
            ? JSON.createObjectNode()
                .put("request", start.request())
                .put("context", start.context())
                .toString()
            : Utterances.start(
                definition.name(), start.request(), start.context(), own, parentDir, phase);
    speakOrFail(run, conductor, utterance, null);
    return store.find(run.id()).orElse(run);
  }

  /**
   * A definition's stages as a run of it has them: all of them for a root; for a phase, none that
   * runs acceptance and none that writes it — spec 2026-09-29 §1b, "a phase (child) run does not
   * have an acceptance stage; the root's acceptance covers the product".
   */
  static List<OrchestrationDefinition.Stage> stagesFor(
      OrchestrationDefinition definition, boolean child) {
    if (!child) {
      return definition.stages();
    }
    return definition.stages().stream()
        .filter(stage -> !OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(stage.acceptance()))
        .map(
            stage ->
                stage.acceptance() == null
                    ? stage
                    : new OrchestrationDefinition.Stage(
                        stage.id(),
                        stage.doneWhen(),
                        stage.mayReturnTo(),
                        stage.checked(),
                        null,
                        stage.holdsPhases()))
        .toList();
  }

  /**
   * A stage as its todo item reads: the id, then its {@code done-when} if it has one. The list is
   * the one place the conductor is shown every turn it changes, so it is where spec §4.3's
   * "guidance shown to the conductor" lands. Nothing matches on this text: the harness keys a stage
   * on its {@code stage_id}.
   */
  static String stageText(OrchestrationDefinition.Stage stage) {
    String doneWhen = stage.doneWhen();
    return doneWhen == null || doneWhen.isBlank()
        ? stage.id()
        : stage.id() + " — done when " + doneWhen.strip();
  }

  // --- a conductor's own start --------------------------------------------------------------

  /**
   * One conductor starts another orchestration — the whole nesting rule in one place, so the tool
   * over it has nothing to decide.
   *
   * <p><b>The child's caller is the parent's row, not the parent's run context</b> — Decision 7. A
   * conductor's turn has no session and no account of its own: its own row is where the admin who
   * started the tree and the session they were attached to are still written down, and a child that
   * copied them from the run context would be a run nobody could be asked anything about. Its
   * caller conversation is the parent's <em>conductor</em> conversation, which is the door {@code
   * Delivery} speaks a report back through.
   *
   * <p><b>{@code wait} moves the parent's row here, inside the tool call</b>, not when the
   * conductor's turn ends: {@link #route} decides from the row, and could not invent the transition
   * from an ending alone. A parent that loses that compare-and-set — its own turn asked a question,
   * or somebody stopped it, between the child's insert and the wait — keeps a child it is not
   * waiting on rather than losing the work: the sentence names the child so the conductor can
   * follow it.
   *
   * <p><b>The parent can be over before the child is.</b> The same window runs the other way: a
   * stop that walked {@code liveChildren} before this child's insert never saw it, and the {@code
   * waitFor} below would then lose and leave a live run under a dead parent — with nobody to report
   * to and nothing that would ever come back to stop it. So the parent is re-read once {@link
   * #start} has returned, and a terminal one takes the child with it, naming it in the refusal.
   * That check is before {@code wait} is looked at: a child nobody is waiting for is just as
   * orphaned as one that is waited on.
   *
   * <p><b>A child can be over before the wait is taken.</b> Its first turn may be refused, or it
   * may run and report, in the window between its own insert and {@code waitFor} — and a report
   * that arrives in that window finds a parent that is still running, so it is spoken and gone. The
   * wait is taken anyway and then undone by {@link #wake} once the child's row is re-read as ended:
   * cheaper than a second compare-and-set, and it leaves no row waiting on something that will
   * never report again. {@link Started#waiting} is what actually happened, not what was asked for.
   *
   * @param parentId the conductor's own run
   * @param wait whether the parent's row goes {@code waiting} on this child
   * @return the child's row, or a sentence saying why there is none
   */
  public Started startNested(
      String parentId,
      OrchestrationDefinition definition,
      String request,
      String context,
      boolean wait) {
    OrchestrationRecord parent = store.find(parentId).orElse(null);
    if (parent == null) {
      return new Started(lostRace(parentId, START_A_CHILD), null, false);
    }
    if (parent.state() == OrchestrationState.WAITING) {
      // Named apart from the other states a child cannot be started from: what the conductor
      // needs to know is which child it is already waiting on, not the word "waiting".
      return new Started(
          "this orchestration is already waiting for "
              + parent.waitingFor()
              + ", so it cannot start another child. Wait for that one to report first.",
          null,
          false);
    }
    if (parent.state() != OrchestrationState.RUNNING) {
      return new Started(lostRace(parentId, START_A_CHILD), null, false);
    }
    if (parent.depth() + 1 > maxDepth) {
      return new Started(
          "this orchestration is already "
              + parent.depth()
              + " deep, and"
              + " nesting stops at "
              + maxDepth
              + ". Do this work yourself, or with"
              + " agent_run.",
          null,
          false);
    }
    OrchestrationRecord child =
        start(
            new Start(
                definition,
                homeOf(parent),
                request,
                context,
                parent.conductorConversation(),
                parent.definitionName(),
                parent.callerHandle(),
                parent.callerSession(),
                parent.id(),
                parent.depth() + 1));
    Optional<OrchestrationRecord> after = store.find(parent.id());
    if (after.isEmpty() || after.get().state().terminal()) {
      // The parent ended while this child was being inserted and first-turned, and a cascade
      // that walked liveChildren before the insert cannot have reached it: left alone this is
      // a live run under a dead parent, with nobody to report to and nothing that would ever
      // come back to stop it. Decision 6's cascade, one row late.
      cancelWithParent(child, parent.id());
      String state = after.map(moved -> moved.state().wire()).orElse("gone");
      return new Started(
          "this orchestration is "
              + state
              + ", so orchestration "
              + child.id()
              + ", which it had just started, was cancelled with it.",
          null,
          false);
    }
    // Starting a child is progress (measured 2026-09-27: a run failed stuck after its fourth
    // phase, on nudges counted since its goal stage). It is not a model's claim of progress
    // but work begun, spending budget, so a conductor cannot hold off "stuck" with it alone.
    store.progressed(parent.id());
    if (!wait) {
      return new Started(null, child, false);
    }
    if (!store.waitFor(parent.id(), child.id())) {
      String state = store.find(parent.id()).map(moved -> moved.state().wire()).orElse("gone");
      // The child is re-read rather than reused: it may have ended while the wait was being
      // lost, and telling the conductor a dead child is running is worse than not waiting.
      String fate =
          store
              .find(child.id())
              .filter(fresh -> fresh.endedAt() != null)
              .map(
                  fresh ->
                      "That child has already ended "
                          + fresh.state().wire()
                          + ": read it with orchestration_status.")
              .orElse("That child is running: follow it with orchestration_status.");
      return new Started(
          "orchestration "
              + child.id()
              + " was started, but this"
              + " orchestration is "
              + state
              + " and cannot wait for it. "
              + fate,
          null,
          false);
    }
    announce(parent.id());
    if (store.find(child.id()).filter(fresh -> fresh.endedAt() != null).isPresent()) {
      // The child was over before the wait landed, so its report — if it had one — has
      // already been spoken to a parent that was still running. Nothing will report again,
      // and a row left waiting here is a row nothing would ever wake.
      wake(parent.id());
      return new Started(null, child, false);
    }
    return new Started(null, child, true);
  }

  /** A child answers from the same tier its parent does; global is the absence of a project. */
  private static Home homeOf(OrchestrationRecord parent) {
    return parent.project() == null ? Home.global() : Home.of(parent.project());
  }

  /**
   * A waiting parent goes back to {@code running} with nothing left to wait for. The wake comes
   * before the parent is spoken to, never after, so the turn that hears the child's report runs on
   * a row that is already running.
   *
   * <p><b>A caller that wakes a parent owes it a {@link #speakAnswerIfPending}</b> once it has
   * spoken, because {@link #speakAnswerOnce} only ever looks at a {@code running} row: an answer
   * that landed before the row went waiting could not be drained while it waited, and nothing else
   * would come back for it. {@link #speakToParent} is that caller.
   *
   * @return whether this call is the one that woke it; false if it was not waiting
   */
  public boolean wake(String parentId) {
    if (!store.wake(parentId)) {
      return false;
    }
    announce(parentId);
    return true;
  }

  /**
   * Speak into a live parent's own conductor conversation — a child's question or its ending,
   * whichever {@code Delivery} is carrying up the tree. Task 4's {@code ParentVoice} is one line
   * over this.
   *
   * <p>The order is fixed: rebuild, re-check the row, wake, speak, and only then drain whatever
   * answer was left pending while the row waited. Draining first would take the conductor for the
   * answer and the child's report would be refused as in flight, which is the one thing a report
   * climbing the tree cannot afford.
   *
   * <p><b>A refusal is transient or permanent, and only a permanent one settles the row.</b> {@link
   * ConductorVoice#isSpeaking} is the discriminator {@code speakOrLeavePending} already uses: a
   * conductor whose own turn is still in flight refuses a second utterance, and that is the
   * <em>ordinary</em> case while a parent waits, because {@code waitFor} moves the row inside that
   * turn's own tool call — from there until the turn ends the row is waiting while its conversation
   * is still speaking. Such a refusal is rethrown without settling anything and the wait is put
   * back, so the caller's retry when the conversation frees finds the row it left.
   *
   * <p>A refusal from a conductor that is <em>not</em> speaking is one no retry gets past, and the
   * wake has already moved the row to running, so leaving it would be a live row with no turn and
   * nothing scheduled — the report is the only thing that was ever going to speak to it. A spent
   * model-call budget becomes the cap question its caller can answer; anything else — an archived
   * conversation, a closed queue — fails the run with the refusal as its failure. Either way the
   * refusal is rethrown, so {@code Delivery} still gets its fallback.
   *
   * <p><b>An {@link ArchiveException} is settled the same way, and not let past.</b> {@code
   * Turn.speakToConductor} throws it, rather than {@code Refused}, when the conversation is gone
   * altogether — which no conversation that is speaking can be, so it is always the permanent kind.
   * Left uncaught it escaped with the wake already applied: a running row with no turn on it, which
   * neither {@code recover} nor a nudge picks up, and {@code Delivery} catching it one level up
   * only kept the item, not the run.
   *
   * <p><b>An {@code asking} parent is not spoken into either.</b> It waits on its own caller's
   * answer — a question it may well have climbed on this very child's behalf — and the rest of the
   * engine leaves an asking row alone. The report stays undelivered and is drained when the turn
   * that answer starts frees, which is the caller conversation {@code Delivery.drainCaller} is
   * keyed on; speaking now would take the conductor for a report while a person's answer is still
   * in the post.
   *
   * @throws io.aeyer.plowshare.server.agents.Turn.Refused if the parent has ended, is asking its
   *     own caller, its pinned definition no longer builds a conductor, or the turn itself is
   *     refused — as {@code CallerVoice.speak} refuses, so {@code Delivery} can fall back to the
   *     person route rather than lose the item
   * @throws ArchiveException if the parent's conversation no longer exists; the row is settled
   *     first, exactly as a permanent refusal settles it
   */
  public void speakToParent(String parentId, String utterance) {
    OrchestrationRecord parent = store.find(parentId).orElse(null);
    if (parent == null) {
      throw new Turn.Refused(
          "no orchestration has the id "
              + parentId
              + ", so its conductor"
              + " cannot be spoken to");
    }
    if (parent.endedAt() != null) {
      throw new Turn.Refused(
          "orchestration "
              + parentId
              + " is "
              + parent.state().wire()
              + ", so its conductor cannot be spoken to");
    }
    if (parent.state() == OrchestrationState.ASKING) {
      throw new Turn.Refused(
          "orchestration "
              + parentId
              + " is asking its own caller, so its"
              + " conductor cannot be spoken to until that answer arrives");
    }
    Rebuilt rebuilt = rebuild(parent);
    if (rebuilt.conductor() == null) {
      stopAndTell(parent.id(), OrchestrationState.FAILED, rebuilt.failure());
      throw new Turn.Refused(rebuilt.failure());
    }
    String waitedOn = parent.waitingFor();
    boolean woken = wake(parentId);
    try {
      speak(parent, rebuilt.conductor(), utterance, null);
    } catch (Turn.Refused | ArchiveException refused) {
      if (voice.isSpeaking(parent.conductorConversation())) {
        // The parent's own turn is still in flight, which is where a wait is usually taken
        // from: the refusal says "later", not "never", and the busy decision is the
        // caller's — it leaves the report undelivered and retries when the conversation
        // frees. Putting the wait back is what that retry, and the parent's own ending,
        // then read.
        restoreWait(parentId, woken ? waitedOn : null);
        throw refused;
      }
      // Nothing will free this conversation, and the wake has already moved the row to
      // running, so leaving it would be a live row with no turn and nothing scheduled to
      // speak to it again. A spent budget is the one permanent refusal with a question
      // behind it, and the wake is what makes askAboutExhaustedBudget's running -> asking
      // possible.
      if (budgetExhausted(parent)) {
        askAboutExhaustedBudget(parent);
      } else {
        stopAndTell(parent.id(), OrchestrationState.FAILED, refused.getMessage());
      }
      throw refused;
    }
    speakAnswerIfPending(parentId);
  }

  /**
   * Undo a {@link #wake} whose speak was refused only for now, so the row the caller retries
   * against is the one it found. A row that was not woken has nothing to put back, and a
   * compare-and-set that loses means something else has moved the row on since.
   */
  private void restoreWait(String parentId, String child) {
    if (child == null) {
      return;
    }
    if (!store.waitFor(parentId, child)) {
      log.info(
          "orchestration {}: its report was refused for now, but the row moved on before"
              + " the wait for {} could be put back",
          parentId,
          child);
      return;
    }
    announce(parentId);
  }

  // --- the conductor's two actions ----------------------------------------------------------

  @Override
  public Optional<String> ask(String orchestration, String question) {
    return ask(orchestration, question, null);
  }

  @Override
  public Optional<String> ask(String orchestration, String question, String structure) {
    if (store.ask(orchestration, question, CONDUCTOR, structure).isPresent()) {
      announce(orchestration);
      store.find(orchestration).ifPresent(run -> recorder.questionAsked(run, question));
      return Optional.empty();
    }
    return Optional.of(lostRace(orchestration, "ask"));
  }

  /** Why an install question on a run with no account behind it is not asked. */
  static final String NOBODY_TO_ASK_ABOUT_INSTALL =
      "nobody can be asked to install this draft:"
          + " this run has no account behind it, so nothing was asked";

  /**
   * Ask the person whether to install a draft; empty when asked, else why not.
   *
   * <p><b>Nobody to ask is refused before anything is recorded</b> (spec
   * 2026-09-29-orchestration-studio §3.4). {@link #INSTALL} is person-only, and {@link Delivery}
   * takes a person-only question to the run's account and nowhere else — not a caller bot, not a
   * parent conductor — so on a run with no account it would sit on its row with nobody ever able to
   * answer it. {@link #askAboutStuck} and {@link #checkFailed} draw the same line.
   */
  public Optional<String> askInstall(String orchestration, String question, String structure) {
    if (store.find(orchestration).filter(run -> run.callerHandle() == null).isPresent()) {
      return Optional.of(NOBODY_TO_ASK_ABOUT_INSTALL);
    }
    if (store.askInstall(orchestration, question, structure).isPresent()) {
      announce(orchestration);
      store.find(orchestration).ifPresent(run -> recorder.questionAsked(run, question));
      return Optional.empty();
    }
    return Optional.of(lostRace(orchestration, "ask"));
  }

  @Override
  public Optional<String> finish(String orchestration, String result) {
    Optional<OrchestrationRecord> found = store.find(orchestration);
    if (found.isEmpty()) {
      return Optional.of(lostRace(orchestration, "finish"));
    }
    OrchestrationRecord run = found.get();
    Set<String> done =
        todos.list(run.conductorConversation()).stream()
            .filter(item -> item.stageId() != null && item.status() == TodoStatus.DONE)
            .map(TodoItem::stageId)
            .collect(Collectors.toSet());
    List<String> notDone =
        run.stages().stream().map(StageRules.Stage::id).filter(id -> !done.contains(id)).toList();
    if (!notDone.isEmpty()) {
      return Optional.of("stages not done: " + String.join(", ", notDone));
    }
    // Decision 6, the other half of the cascade: a parent that finished while a child ran would
    // have its caller told the tree was done and then have that child stopped out from under
    // it, so the conductor is made to choose instead.
    List<String> live =
        store.liveChildren(orchestration).stream().map(OrchestrationRecord::id).toList();
    if (!live.isEmpty()) {
      return Optional.of(
          "children still running: "
              + String.join(", ", live)
              + ". Wait for"
              + " them: their results come to you when they finish. A child that is asking"
              + " or waiting can be ended with orchestration_cancel; a running one only by"
              + " the person.");
    }
    if (store.finish(orchestration, result)) {
      announce(orchestration);
      recordEnded(orchestration);
      // The check above is not atomic with this commit — a conductor's parallel tool calls
      // can start a child in between — so finish cascades as every other ending does rather
      // than leaving a run below a parent that has already told its caller the tree is done.
      cancelDescendants(orchestration, new HashSet<>());
      return Optional.empty();
    }
    return Optional.of(lostRace(orchestration, "finish"));
  }

  /**
   * Set a run's check once, asking a person for it unless the side's mode is already {@code open}.
   * Refused before any of that when this run checks no stage, already has a check, or a checked
   * stage has already passed without one — spec 2026-09-26's "set before the first checked stage is
   * done".
   *
   * <p><b>A check a person refused may be replaced</b> (final review F2, spec §2 as amended). A
   * check whose approval is denied, revoked or gone can never run, and "set once" then locked every
   * checked done move and left the run to end {@code stuck} — the silent failure this feature
   * exists to remove. So such a check is cleared and set again as usual, with a new approval, as
   * long as no checked stage is done yet: the bar is still the one nothing has passed. A check that
   * is allowed, used, open or still asked does not change.
   */
  @Override
  public CheckSet setCheck(
      String orchestration, List<String> argv, String side, String cwd, String mode) {
    return setCheck(orchestration, argv, side, cwd, mode, false, BeforeAsking.NONE);
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>The person is asked only when nothing else can answer</b> (V67). Measured 2026-09-29,
   * orc_318E02E6946BB915: every phase is its own run and sets its own check, so the person was
   * asked about the same {@code pytest} once per phase. Not on an open side and with no hook asking
   * — a hook that asks is the person's, as for an acceptance set — a check is set, in this order:
   * under consent already given ({@link #covering}: the person allowed the same command, side and
   * directory for another run of this tree, as its check or one of its acceptance commands, or a
   * standing project approval covers it), recorded as covered; allowed by the command judge, when
   * it finds it clearly safe; else asked of the person as before, with the judge's words when it
   * gave any. A check set either way runs every time a checked stage is marked done: {@link
   * StageChecks} runs one whose approval is {@code allowed} or {@code used}, and a covering
   * approval is either.
   *
   * <p><b>{@code approval.pre} before the person is asked</b> (spec 2026-09-28-hooks-reach-the-log
   * §3): asked only when a person is about to be — not for an open side, not when consent already
   * given covers the check, not when the judge allows it, and not when nobody could be ({@link
   * CheckConsent#canAsk}) — and before a person-refused check is cleared, so a hook's denial leaves
   * the run as it was. A run that ended while the hooks ran asks nobody.
   */
  @Override
  public CheckSet setCheck(
      String orchestration,
      List<String> argv,
      String side,
      String cwd,
      String mode,
      boolean hookAsked) {
    return setCheck(orchestration, argv, side, cwd, mode, hookAsked, BeforeAsking.NONE);
  }

  /** {@inheritDoc} */
  @Override
  public CheckSet setCheck(
      String orchestration,
      List<String> argv,
      String side,
      String cwd,
      String mode,
      BeforeAsking before) {
    return setCheck(orchestration, argv, side, cwd, mode, false, before);
  }

  /**
   * {@inheritDoc}
   *
   * <p>In the order {@link #setCheck(String, List, String, String, String, boolean)} gives: consent
   * already given, then the command judge, then {@code approval.pre}, then the person.
   */
  @Override
  public CheckSet setCheck(
      String orchestration,
      List<String> argv,
      String side,
      String cwd,
      String mode,
      boolean hookAsked,
      BeforeAsking before) {
    if (checks == null || checkConsent == null) {
      return new CheckSet.Refused(
          "checks are not wired on this server, so no check can be" + " set");
    }
    OrchestrationRecord run = store.find(orchestration).orElse(null);
    if (run == null || run.state() != OrchestrationState.RUNNING) {
      return new CheckSet.Refused(lostRace(orchestration, "set its check"));
    }
    Set<String> checkedStages =
        run.stages().stream()
            .filter(StageRules.Stage::checked)
            .map(StageRules.Stage::id)
            .collect(Collectors.toSet());
    if (checkedStages.isEmpty()) {
      return new CheckSet.Refused(
          "this orchestration checks no stage, so it has no check to" + " set");
    }
    Optional<OrchestrationChecks.Check> existing = checks.find(orchestration);
    if (existing.isPresent() && !refusedByAPerson(existing.get())) {
      return new CheckSet.Refused(
          "the check is `"
              + String.join(" ", existing.get().argv())
              + "`; it was set once and does not change");
    }
    boolean passedAlready =
        todos.list(run.conductorConversation()).stream()
            .anyMatch(
                item ->
                    item.stageId() != null
                        && checkedStages.contains(item.stageId())
                        && item.status() == TodoStatus.DONE);
    if (passedAlready) {
      return new CheckSet.Refused(
          "a checked stage is already done, so the bar it passed is"
              + " the bar; a check is set before the first checked stage is done");
    }
    boolean open = EnvironmentFile.OPEN.equals(mode) && !hookAsked;
    if (open) {
      if (existing.isPresent() && !checks.clear(orchestration, existing.get().approval())) {
        return new CheckSet.Refused(
            "another check was set for this run just now; read" + " it with orchestration_status");
      }
      checks.set(
          new OrchestrationChecks.Check(
              orchestration, argv, side, cwd, OrchestrationChecks.OPEN, null, clock.get()));
      return new CheckSet.Set();
    }
    CommandJudge.Verdict verdict = CommandJudge.Verdict.NOT_JUDGED;
    if (!hookAsked) {
      // CONSENT ALREADY GIVEN (V67): nobody is asked, so approval.pre is not either.
      Optional<Covering> covering = covering(run, argv, side, cwd);
      if (covering.isPresent()) {
        if (existing.isPresent() && !checks.clear(orchestration, existing.get().approval())) {
          return new CheckSet.Refused(
              "another check was set for this run just now;"
                  + " read it with orchestration_status");
        }
        if (!checks.set(
            new OrchestrationChecks.Check(
                orchestration,
                argv,
                side,
                cwd,
                OrchestrationChecks.APPROVAL,
                covering.get().approval(),
                clock.get()))) {
          return new CheckSet.Refused(
              "another check was set for this run just now;"
                  + " read it with orchestration_status");
        }
        recorder.consentCovered(run, argv, covering.get().approval(), covering.get().how());
        return new CheckSet.Set(
            "the person already allowed it — "
                + covering.get().how()
                + " ("
                + covering.get().approval()
                + ")");
      }
      // THE COMMAND JUDGE (V67): clear means nobody is asked, so approval.pre is not either.
      verdict =
          CommandJudge.safely(
              judge,
              List.of(new CommandJudge.Command(argv, null, cwd, side)),
              usageOwners.conversation(
                  run.conductorConversation(), 0, UsageAttribution.Operation.REVIEW));
      if (verdict.clear()) {
        if (existing.isPresent() && !checks.clear(orchestration, existing.get().approval())) {
          return new CheckSet.Refused(
              "another check was set for this run just now;"
                  + " read it with orchestration_status");
        }
        Optional<String> allowed =
            checkConsent.allowedByJudge(
                run, argv, null, side, cwd, CheckConsent.CHECK_WHY, verdict.why());
        if (allowed.isEmpty()) {
          return nobodyToAsk();
        }
        if (!checks.set(
            new OrchestrationChecks.Check(
                orchestration,
                argv,
                side,
                cwd,
                OrchestrationChecks.APPROVAL,
                allowed.get(),
                clock.get()))) {
          return new CheckSet.Refused(
              "another check was set for this run just now;"
                  + " read it with orchestration_status");
        }
        // The judge's words are the model's, recorded for the person; the conductor is
        // told only that it was allowed.
        return new CheckSet.Set("the command judge found it clearly safe");
      }
    }
    // THE PERSON. Nobody to ask (ruling F5): refused as the empty ask below refuses it, before
    // approval.pre and before anything is cleared.
    if (!checkConsent.canAsk(run)) {
      return nobodyToAsk();
    }
    String why =
        verdict.why() == null
            ? CheckConsent.CHECK_WHY
            : CheckConsent.CHECK_WHY + "\n" + CHECK_JUDGED + verdict.why();
    // approval.pre (spec 2026-09-28-hooks-reach-the-log §3), shown the reason the person
    // would be, the judge's words with it; a denial leaves the run as it was.
    Gate asking = before.before(why);
    if (asking.isDenied()) {
      return new CheckSet.Refused(
          "the check `"
              + String.join(" ", argv)
              + "` was not"
              + " set: a hook on approval.pre refused it before anyone was asked: "
              + asking.denied());
    }
    // ENDED WHILE THE HOOKS (OR THE JUDGE) RAN (the orc_318D26A46144920B race): nobody is
    // asked about a run that is over, and nothing is cleared on it.
    if (store.find(orchestration).map(now -> now.state().terminal()).orElse(true)) {
      return new CheckSet.Refused(
          lostRace(orchestration, "set its check") + "; it has ended, so nobody was asked");
    }
    if (existing.isPresent() && !checks.clear(orchestration, existing.get().approval())) {
      return new CheckSet.Refused(
          "another check was set for this run just now; read it with" + " orchestration_status");
    }
    Optional<CheckConsent.Asked> asked =
        checkConsent.ask(run, argv, side, cwd, asking.applyTo(why));
    if (asked.isEmpty()) {
      // RunTool's own "this run has nowhere to put a question to a person": a project with
      // no id on this server is one nobody can answer for, so the check is refused rather
      // than crash on an unboxed null projectId — nothing is written.
      return nobodyToAsk();
    }
    if (!checks.set(
        new OrchestrationChecks.Check(
            orchestration,
            argv,
            side,
            cwd,
            OrchestrationChecks.APPROVAL,
            asked.get().approval(),
            clock.get()))) {
      return new CheckSet.Refused(
          "another check was set for this run just now; read it with" + " orchestration_status");
    }
    // ENDED WHILE IT WAS ASKED (measured 2026-09-29, the acceptance gate's race): an ending
    // between the read above and the ask withdrew what the run had then, and not this. So it
    // is withdrawn here, and no check is left on a run that has nothing left to check.
    if (store.find(orchestration).map(now -> now.state().terminal()).orElse(true)) {
      withdrawal.accept(asked.get().approval());
      checks.clear(orchestration, asked.get().approval());
      return new CheckSet.Refused(
          lostRace(orchestration, "set its check")
              + "; it has ended, and the person's question was withdrawn");
    }
    return new CheckSet.Asking(asked.get().question());
  }

  /** Before the command judge's words, in a check's question it put to the person. */
  static final String CHECK_JUDGED = "The command judge did not find it clearly safe: ";

  /**
   * Consent already given for a check, and where it came from.
   *
   * @param approval the approval's id
   * @param how where it came from, in a few words, for the record and the conductor
   */
  record Covering(String approval, String how) {}

  /**
   * The consent that already covers a check (V67): the person allowed the same command, on the same
   * side, in the same directory, for another run of this run's tree — as its check, or as one of
   * its acceptance commands — or a standing project approval covers it. Only a person's answer
   * covers: one the command judge gave is the judge's again ({@link #setCheck}).
   */
  private Optional<Covering> covering(
      OrchestrationRecord run, List<String> argv, String side, String cwd) {
    Function<String, Optional<RunApproval>> states = checkApprovals;
    for (OrchestrationRecord other : tree(run)) {
      OrchestrationChecks wired = checks;
      if (wired != null && !other.id().equals(run.id())) {
        Optional<String> checked =
            wired
                .find(other.id())
                .filter(
                    check ->
                        OrchestrationChecks.APPROVAL.equals(check.consent())
                            && check.argv().equals(argv)
                            && check.side().equals(side)
                            && check.cwd().equals(cwd)
                            && allowedByThePerson(states, check.approval()))
                .map(OrchestrationChecks.Check::approval);
        if (checked.isPresent()) {
          return Optional.of(
              new Covering(
                  checked.get(),
                  "allowed as the check of " + other.id() + " (" + other.definitionName() + ")"));
        }
      }
      OrchestrationAcceptance accepting = acceptance;
      if (accepting != null) {
        Optional<String> accepted =
            accepting.find(other.id()).stream()
                .filter(
                    each ->
                        OrchestrationChecks.APPROVAL.equals(each.consent())
                            && each.argv().equals(argv)
                            && each.side().equals(side)
                            && each.cwd().equals(cwd)
                            && allowedByThePerson(states, each.approval()))
                .map(OrchestrationAcceptance.Registered::approval)
                .findFirst();
        if (accepted.isPresent()) {
          return Optional.of(
              new Covering(
                  accepted.get(),
                  "allowed as one of the"
                      + " acceptance commands of "
                      + other.id()
                      + " ("
                      + other.definitionName()
                      + ")"));
        }
      }
    }
    return checkConsent
        .standing(run, argv, side)
        .map(id -> new Covering(id, "a standing project approval covers it"));
  }

  /**
   * Whether an approval is one the person allowed: {@code allowed} or {@code used}, and not the
   * command judge's.
   */
  private static boolean allowedByThePerson(
      Function<String, Optional<RunApproval>> states, String approval) {
    return approval != null
        && states != null
        && states
            .apply(approval)
            .filter(
                found ->
                    !RunApproval.JUDGE.equals(found.answeredBy())
                        && (RunApproval.ALLOWED.equals(found.state())
                            || RunApproval.USED.equals(found.state())))
            .isPresent();
  }

  /** Every run of {@code run}'s tree: its root, then each run below, parents before children. */
  private List<OrchestrationRecord> tree(OrchestrationRecord run) {
    OrchestrationRecord root = run;
    Set<String> seen = new HashSet<>();
    while (root.parent() != null && seen.add(root.id())) {
      OrchestrationRecord up = store.find(root.parent()).orElse(null);
      if (up == null) {
        break;
      }
      root = up;
    }
    List<OrchestrationRecord> found = new ArrayList<>();
    Set<String> listed = new HashSet<>();
    ArrayDeque<OrchestrationRecord> next = new ArrayDeque<>(List.of(root));
    while (!next.isEmpty()) {
      OrchestrationRecord each = next.poll();
      if (listed.add(each.id())) {
        found.add(each);
        next.addAll(store.children(each.id()));
      }
    }
    return found;
  }

  private static CheckSet nobodyToAsk() {
    return new CheckSet.Refused(
        "nobody can be asked to allow this check: the project has"
            + " no id on this server, so the check cannot be set here");
  }

  /** Whether a check's approval is one no answer can bring back: denied, revoked or gone. */
  private boolean refusedByAPerson(OrchestrationChecks.Check check) {
    if (!OrchestrationChecks.APPROVAL.equals(check.consent())) {
      return false;
    }
    String state = checkApprovals.apply(check.approval()).map(RunApproval::state).orElse(null);
    return state == null || RunApproval.DENIED.equals(state) || RunApproval.REVOKED.equals(state);
  }

  // --- a command approval's continuation --------------------------------------------------

  /**
   * Continue the conductor whose conversation a command approval was raised in, with the words the
   * approval's answer puts to it, through this engine — so its turn ends through {@link #ended} as
   * every conductor turn does. {@code approval.answer} otherwise continues a conversation as a
   * plain agent turn, which for a conductor names an orchestration as an agent and reports its
   * ending to nobody that routes it.
   *
   * <p><b>An approval raised inside the conductor's own sub-agent resumes the sub-agent</b> — spec
   * 2026-09-26 §4. Such an approval is written against the conductor's conversation but was asked
   * in the sub-agent's, and it is the sub-agent that asked for the command and holds the history of
   * why. Continuing the conductor instead would have it delegate again with a reworded task to a
   * sub-agent that starts from nothing. So the sub-agent carries on in its own conversation, on the
   * conductor's budget, and when it ends {@link #delegateEnded} tells the conductor what its {@code
   * agent_run} would have returned. Only a direct child of this run's conductor qualifies: a
   * conversation the approval names that is anything else is continued as the conductor, as it was
   * before. So is one whose resume is refused, unless the refusal is the conductor's spent budget,
   * which is asked about as any spent budget is.
   *
   * @param approval the approval as the answer left it — its state {@code allowed} or {@code
   *     denied} — which is how the run's own check is told which it was
   * @param answered the ordinary words for the answer, spoken unless the approval is the run's
   *     check's, which has words of its own
   * @return whether the approval's conversation is a live run's conductor conversation, and so this
   *     engine's to continue; {@code false} leaves it to the ordinary continuation
   * @throws io.aeyer.plowshare.server.agents.Turn.Refused if the conductor or the sub-agent is
   *     speaking, as the ordinary continuation refuses a busy conversation
   */
  public boolean continueApproved(RunApproval approval, String answered) {
    Optional<OrchestrationRecord> found =
        store
            .byConductorConversation(approval.conversation())
            .filter(run -> !run.state().terminal());
    if (found.isEmpty()) {
      return false;
    }
    OrchestrationRecord run = found.get();
    // The run's own check (final review F2). The ordinary words — "allowed `…`, this once.
    // Run it again." — are wrong three ways for it: the conductor has no run tool, the consent
    // is for the run and not once, and it is never told its check may now be relied on. A
    // denial's "carry on without it" is wronger still: a checked stage cannot be done without
    // one. So the answer is spoken in the check's own words, which say what happens next.
    OrchestrationChecks wired = checks;
    Optional<OrchestrationChecks.Check> check =
        wired == null
            ? Optional.empty()
            : wired.find(run.id()).filter(set -> approval.id().equals(set.approval()));
    // An acceptance command's approval (spec 2026-09-29 §1b) is asked at the spec stage, while
    // the conductor goes on: its answer is spoken only when the conductor waits at acceptance
    // and no command is still asked — never "Run it again", which it cannot: it has no run
    // tool, and the harness runs these commands itself on the acceptance done move.
    OrchestrationAcceptance registered = acceptance;
    Function<String, Optional<RunApproval>> states = checkApprovals;
    if (registered != null
        && states != null
        && registered
            .byApproval(approval.id())
            .filter(each -> each.orchestration().equals(run.id()))
            .isPresent()) {
      speakAcceptanceAnswersIfDone(run, registered, states, true);
      return true;
    }
    if (AcceptanceGate.isAcceptance(approval.reason())) {
      // One of a set a changed section replaced: the command it asked about is no longer
      // one that runs, and its new approval is asked separately. Nothing to say.
      log.info(
          "orchestration {}: approval {} was for an acceptance command since replaced;"
              + " its answer is not spoken",
          run.id(),
          approval.id());
      return true;
    }
    String utterance = answered;
    if (check.isPresent()) {
      utterance =
          RunApproval.DENIED.equals(approval.state())
              ? Utterances.checkDenied(check.get().argv())
              : Utterances.checkAllowed(check.get().argv(), check.get().side());
    }
    Optional<ConversationRecord> delegate =
        approval.askedIn().equals(approval.conversation())
            ? Optional.empty()
            : conversations
                .find(approval.askedIn())
                .filter(
                    child ->
                        child.origin() == Origin.DELEGATION
                            && run.conductorConversation().equals(child.parentId()));
    if (delegate.isPresent()) {
      String agent = delegate.get().agent();
      try {
        voice.resumeDelegate(
            delegate.get().id(),
            agent,
            run.conductorConversation(),
            utterance,
            run.callerHandle(),
            outcome -> delegateEnded(run.id(), agent, approval.id(), outcome, delegate.get().id()));
        return true;
      } catch (Turn.Refused refused) {
        // A refused resume must not strand the run: the conductor's own turn already
        // ended awaiting this approval, and nothing else would speak to it again. A spent
        // budget is the question every spent budget is; anything else — the agent no
        // longer resolves, the child is busy — continues the conductor with the same
        // words, as for an approval raised in the conductor itself, and it can delegate
        // again. A conductor that is itself busy refuses that speak in turn, which reaches
        // the person as the ordinary continuation's busy refusal does.
        log.info(
            "orchestration {}: its sub-agent '{}' could not be resumed after approval"
                + " {}, so the conductor is continued instead: {}",
            run.id(),
            agent,
            approval.id(),
            refused.getMessage());
        if (budgetExhausted(run)) {
          askAboutExhaustedBudget(run);
          return true;
        }
      }
    }
    Rebuilt rebuilt = rebuild(run);
    if (rebuilt.conductor() == null) {
      stopAndTell(run.id(), OrchestrationState.FAILED, rebuilt.failure());
      throw new Turn.Refused(rebuilt.failure());
    }
    speak(run, rebuilt.conductor(), utterance, null);
    return true;
  }

  /**
   * Speak the answers to a run's acceptance approvals, once there is nothing left to wait for and
   * someone waiting: every command answered, and the conductor at the acceptance stage — the one
   * place its turn was ended {@code AWAITING} for them ({@link AcceptanceGate}). Answers that
   * arrive while it is still at an earlier stage are left for the gate to read when it gets there;
   * interrupting its plan with them would only tell it what it cannot yet use.
   */
  private void speakAcceptanceAnswersIfDone(
      OrchestrationRecord run,
      OrchestrationAcceptance registered,
      Function<String, Optional<RunApproval>> states,
      boolean retryOnceFree) {
    List<OrchestrationAcceptance.Registered> set = registered.find(run.id());
    List<String> allowed = new ArrayList<>();
    List<String> refused = new ArrayList<>();
    for (OrchestrationAcceptance.Registered each : set) {
      if (each.approval() == null) {
        continue;
      }
      String state = states.apply(each.approval()).map(RunApproval::state).orElse("gone");
      if (RunApproval.ASKED.equals(state)) {
        return;
      }
      (RunApproval.ALLOWED.equals(state) || RunApproval.USED.equals(state) ? allowed : refused)
          .add(String.join(" ", each.argv()));
    }
    boolean atAcceptance =
        todos.list(run.conductorConversation()).stream()
            .anyMatch(
                item ->
                    item.status() == TodoStatus.IN_PROGRESS
                        && run.stages().stream()
                            .anyMatch(
                                stage ->
                                    stage.id().equals(item.stageId())
                                        && OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(
                                            stage.acceptance())));
    if (!atAcceptance) {
      return;
    }
    // A conductor still speaking — the turn the gate ended AWAITING winding down as the
    // person answers — is not left stranded: the run is marked and its free speaks it, on
    // deliverDelegateResult's pattern, retried once here in case that free has already run.
    if (voice.isSpeaking(run.conductorConversation())) {
      leaveAcceptanceAnswersPending(run, retryOnceFree);
      return;
    }
    Rebuilt rebuilt = rebuild(run);
    if (rebuilt.conductor() == null) {
      stopAndTell(run.id(), OrchestrationState.FAILED, rebuilt.failure());
      return;
    }
    try {
      speak(run, rebuilt.conductor(), Utterances.acceptanceAnswered(allowed, refused), null);
    } catch (Turn.Refused busy) {
      if (!voice.isSpeaking(run.conductorConversation())) {
        stopAndTell(run.id(), OrchestrationState.FAILED, busy.getMessage());
        return;
      }
      leaveAcceptanceAnswersPending(run, retryOnceFree);
    } catch (RuntimeException failed) {
      failOnException(run, failed);
    }
  }

  private void leaveAcceptanceAnswersPending(OrchestrationRecord run, boolean retryOnceFree) {
    log.info(
        "orchestration {}: its conductor is speaking, so the answers to its acceptance"
            + " commands wait for that turn's free",
        run.id());
    pendingAcceptanceAnswers.add(run.id());
    if (retryOnceFree && !voice.isSpeaking(run.conductorConversation())) {
      speakAcceptanceAnswersIfPending(run.id(), false);
    }
  }

  /**
   * Speak the answers to a run's acceptance commands that found its conductor speaking, if any
   * wait: the {@code whenFree} drain's duty beside {@link #speakDelegateResultIfPending}. Taken
   * before it is spoken, so two drains cannot speak it twice; the words are rebuilt from the
   * approvals as they stand now.
   *
   * @param orchestration the run
   */
  public void speakAcceptanceAnswersIfPending(String orchestration) {
    speakAcceptanceAnswersIfPending(orchestration, true);
  }

  private void speakAcceptanceAnswersIfPending(String orchestration, boolean retryOnceFree) {
    if (!pendingAcceptanceAnswers.remove(orchestration)) {
      return;
    }
    OrchestrationAcceptance registered = acceptance;
    Function<String, Optional<RunApproval>> states = checkApprovals;
    OrchestrationRecord run = store.find(orchestration).orElse(null);
    if (registered == null || states == null || run == null || run.state().terminal()) {
      return;
    }
    if (run.state() == OrchestrationState.ASKING) {
      pendingAcceptanceAnswers.add(orchestration);
      return;
    }
    speakAcceptanceAnswersIfDone(run, registered, states, retryOnceFree);
  }

  /**
   * A resumed delegate's turn ended: another approval is delivered as any is and the conductor is
   * left alone; a failure that would have ended the conductor's agent_run ends the run; a conductor
   * budget the delegate spent is asked about, as the conductor's next call would have been;
   * anything else is what the agent_run would have returned, spoken to the conductor once — now, or
   * on its next free if it is busy ({@link #deliverDelegateResult}).
   *
   * <p>The three branches are {@code AgentRunTool}'s own, read through its public wording so the
   * conductor cannot tell a resumed sub-agent's result from one its own call returned: {@link
   * AgentRunTool#propagates} is the set of endings that stop a caller's turn, {@link
   * AgentRunTool#stoppedSentence} what that turn is ended with, and {@link AgentRunTool#render}
   * what the tool result would have read — including a sub-agent that stopped on a cap without an
   * answer, which the conductor is told and decides about, as it would be from the call.
   *
   * @param calleeConversation the resumed delegate's own conversation — {@code delegate.get().id()}
   *     at the one call site — so its facts footer (§1a) reads back exactly this delegation's tool
   *     lines and never an overlapping one to the same agent
   */
  private void delegateEnded(
      String orchestration,
      String agent,
      String approval,
      Outcome outcome,
      String calleeConversation) {
    OrchestrationRecord run = store.find(orchestration).orElse(null);
    if (run == null || run.state().terminal()) {
      return;
    }
    // Once per delegation: the agent_run that started it told nothing when it came back
    // waiting, and a delegate that waits again has still not come back.
    if (outcome.ending() != Outcome.Ending.AWAITING) {
      recorder.delegateReturned(run.conductorConversation(), run.definitionName(), agent, outcome);
    }
    if (outcome.ending() == Outcome.Ending.AWAITING) {
      approvalDrain.accept(run.conductorConversation());
      return;
    }
    if (AgentRunTool.propagates(outcome.ending())) {
      stopAndTell(
          run.id(), OrchestrationState.FAILED, AgentRunTool.stoppedSentence(agent, outcome));
      return;
    }
    // The resumed sub-agent's answer is the conductor's delegated work landing, as an
    // agent_run's return is (JobRuntime.delegationReturned): progress, so its nudges reset.
    store.progressed(run.id());
    // The resumed delegate spent the conductor's allowance, so the conductor has none left
    // to be told with: speakToConductor would refuse it and speakOrFail would fail the run.
    // Through agent_run the conductor's next call would end CALL_BUDGET and the person would
    // be asked to raise it, so that is what happens here.
    if (budgetExhausted(run)) {
      askAboutExhaustedBudget(run);
      return;
    }
    String rendered = AgentRunTool.render(agent, List.of(), outcome);
    String facts = recorder.delegationFacts(calleeConversation, agent);
    deliverDelegateResult(
        run,
        Utterances.delegateResumed(
            agent, approval, facts == null ? rendered : rendered + "\n\n" + facts),
        true);
  }

  /**
   * Speak a resumed sub-agent's result to its conductor — or, when the conductor is busy, leave it
   * for that turn's free (final review F5).
   *
   * <p><b>Busy is the ordinary case, not a failure.</b> {@code Turn.speakToDelegate} frees the
   * conductor before it hands over the outcome, and the free runs the {@code whenFree} drains
   * synchronously: whatever waited while the sub-agent ran — a child's report to a parent conductor
   * such as {@code implement_specification}'s, an answer — is spoken on that free, and this speak
   * is then refused as in flight. {@code speakOrFail} failed a working run for it. So a refusal
   * while {@link ConductorVoice#isSpeaking} says the conductor is speaking leaves the result for
   * the next free, exactly as {@code speakOrLeavePending} leaves an answer; a refusal from a
   * conductor that is not speaking is one nothing will get past, and still fails the run.
   *
   * <p><b>And it is retried once at once</b> if the conductor turns out to be free after being
   * left: the busy turn may have freed between the refusal and the put, and its drain found nothing
   * — the race {@code speakAnswerIfPending}'s own single retry closes.
   *
   * @param retryOnceFree whether a result left pending is retried once if the conductor is already
   *     free again; false on the retry itself, so it is bounded
   */
  private void deliverDelegateResult(
      OrchestrationRecord run, String utterance, boolean retryOnceFree) {
    if (budgetExhausted(run)) {
      askAboutExhaustedBudget(run);
      return;
    }
    Rebuilt rebuilt = rebuild(run);
    if (rebuilt.conductor() == null) {
      stopAndTell(run.id(), OrchestrationState.FAILED, rebuilt.failure());
      return;
    }
    try {
      speak(run, rebuilt.conductor(), utterance, null);
    } catch (Turn.Refused refused) {
      if (!voice.isSpeaking(run.conductorConversation())) {
        stopAndTell(run.id(), OrchestrationState.FAILED, refused.getMessage());
        return;
      }
      log.info(
          "orchestration {}: its conductor is speaking, so its sub-agent's result waits"
              + " for that turn's free: {}",
          run.id(),
          refused.getMessage());
      pendingDelegateResults.put(run.id(), utterance);
      if (retryOnceFree && !voice.isSpeaking(run.conductorConversation())) {
        speakDelegateResultIfPending(run.id(), false);
      }
    } catch (RuntimeException failed) {
      failOnException(run, failed);
    }
  }

  /**
   * Speak a resumed sub-agent's result that was left for this conductor's free, if there is one:
   * the {@code whenFree} drain's second duty beside {@link #speakAnswerIfPending}. Taken before it
   * is spoken, so two drains cannot speak it twice; a run that has ended since has nobody left to
   * tell, and one asking its caller is left alone, as every asking row is, until the turn its
   * answer starts frees.
   */
  public void speakDelegateResultIfPending(String orchestration) {
    speakDelegateResultIfPending(orchestration, true);
  }

  private void speakDelegateResultIfPending(String orchestration, boolean retryOnceFree) {
    String utterance = pendingDelegateResults.remove(orchestration);
    if (utterance == null) {
      return;
    }
    OrchestrationRecord run = store.find(orchestration).orElse(null);
    if (run == null || run.state().terminal()) {
      return;
    }
    if (run.state() == OrchestrationState.ASKING) {
      pendingDelegateResults.putIfAbsent(orchestration, utterance);
      return;
    }
    deliverDelegateResult(run, utterance, retryOnceFree);
  }

  // --- answer and cancel --------------------------------------------------------------------

  /**
   * Record an answer if the run is still asking, then speak it to the conductor if the conductor
   * can take it now. A conductor that cannot leaves it pending for the drain.
   *
   * @return whether this answer is the one that won
   */
  public boolean answer(String orchestration, String text, String author) {
    if (!store.answer(orchestration, text, author)) {
      return false;
    }
    answered(orchestration, text, author);
    return true;
  }

  /**
   * {@link #answer}, for a model — the bot or agent that started the run, or a parent conductor
   * answering its child. Refused while the run asks a question only the person may answer ({@link
   * #STUCK}): whether a run that has stopped making progress goes on is not something a model that
   * did not see it stop can judge, and the one measured guessed.
   *
   * @return whether this answer is the one that won; false also for a person-only question, which
   *     {@link #personOnlyQuestion} tells apart
   */
  public boolean answerAsModel(String orchestration, String text, String author) {
    if (!store.answerUnlessPersonOnly(orchestration, text, author)) {
      return false;
    }
    // A parent answering its child is progress: rule 2's nudge (spec 2026-09-29 §3) counts,
    // and without this a parent nudged once per question and answering every one would reach
    // the stuck question on its child's third question, punished for questions it answered.
    store.find(orchestration).map(OrchestrationRecord::parent).ifPresent(store::progressed);
    answered(orchestration, text, author);
    return true;
  }

  /** What choosing among a question's options came to. */
  public sealed interface Chosen {
    /** It won; {@code text} is what the conductor is spoken. */
    record Answered(String text) implements Chosen {}

    /** The choices do not fit the open question; nothing was written. */
    record Refused(String why) implements Chosen {}

    /** Nothing was waiting for it — already answered, not asking, or person-only to a model. */
    record Lost() implements Chosen {}
  }

  /**
   * Answer the open question by its options (spec 2026-09-29-orchestration-studio §2.3): the
   * choices are checked against the question's own structure, rendered into the text the conductor
   * is spoken, and written with the same compare-and-set {@link #answer} and {@link #answerAsModel}
   * use — so a person's choice and a model's race exactly as their words do.
   *
   * @param note the free words beside the choices, or null
   * @param byPerson false for a model: a person-only question is then {@link Chosen.Lost}, and a
   *     parent's answer counts as its progress, as {@link #answerAsModel}'s does
   */
  public Chosen answerChosen(
      String orchestration, JsonNode choices, String note, String author, boolean byPerson) {
    Optional<OrchestrationMessage> open = store.openQuestion(orchestration);
    if (open.isEmpty()) {
      return new Chosen.Lost();
    }
    if (open.get().structure() == null) {
      return new Chosen.Refused(
          "Orchestration "
              + orchestration
              + "'s question has no"
              + " options to choose from; answer it in words.");
    }
    StructuredQuestions.Asked asked;
    try {
      asked = StructuredQuestions.parse(open.get().structure());
    } catch (IllegalStateException unreadable) {
      // A STORED STRUCTURE THAT NO LONGER READS — written by an older shape, or by hand — is
      // a sentence to whoever chose, not a server error: words still answer the question.
      return new Chosen.Refused(
          "Orchestration "
              + orchestration
              + "'s question could not"
              + " be read as options; answer it in words.");
    }
    List<StructuredAnswers.Choice> read;
    try {
      read = StructuredAnswers.read(choices, asked.questions());
    } catch (StructuredQuestions.Refused refused) {
      return new Chosen.Refused(refused.getMessage());
    }
    String text = StructuredAnswers.render(read, note);
    String structure = StructuredAnswers.structure(read);
    boolean won =
        byPerson
            ? store.answer(orchestration, text, author, structure)
            : store.answerUnlessPersonOnly(orchestration, text, author, structure);
    if (!won) {
      return new Chosen.Lost();
    }
    if (!byPerson) {
      store.find(orchestration).map(OrchestrationRecord::parent).ifPresent(store::progressed);
    }
    answered(orchestration, text, author);
    return new Chosen.Answered(text);
  }

  /** The open question of a run asking what only the person may answer, or empty. */
  public Optional<String> personOnlyQuestion(String orchestration) {
    return store
        .find(orchestration)
        .filter(run -> run.state() == OrchestrationState.ASKING && personOnly(run.pendingCap()))
        .flatMap(run -> store.openQuestion(run.id()))
        .map(OrchestrationMessage::text);
  }

  /**
   * {@link #answer} and {@link #answerAsModel}'s shared tail: told to the record before the
   * conductor is spoken to, so a cascade the speak sets off orders after its own answer.
   */
  private void answered(String orchestration, String text, String author) {
    announce(orchestration);
    store.find(orchestration).ifPresent(run -> recorder.questionAnswered(run, text, author));
    speakAnswerIfPending(orchestration);
  }

  /**
   * Stop a run as cancelled and tell its caller. The live job, if any, is not this method's: the
   * wiring cancels it after this has stopped the row — Decision 6.
   *
   * @return whether this call is the one that cancelled it
   */
  public boolean cancel(String orchestration, String by) {
    return cancel(orchestration, by, false);
  }

  /**
   * {@link #cancel}, only while the run is asking or waiting — a model caller's cancel, which rule
   * 2 (spec 2026-09-27 §3) never lets stop running work. The state is judged by the stop itself,
   * not by the caller's earlier read: a person's answer landing in between puts the run back to
   * running, and this then changes nothing. Nor a run asking the person about being stuck ({@link
   * #STUCK}): the store's stop refuses it. The person's own {@code /cancel} stays on {@link
   * #cancel}.
   *
   * @return whether this call is the one that cancelled it; false for a running or ended run
   */
  public boolean cancelUnlessRunning(String orchestration, String by) {
    if (stuckBelow(orchestration).isPresent()) {
      return false;
    }
    return cancel(orchestration, by, true);
  }

  /**
   * The note a live conductor's delegations carry (rule 6, spec 2026-09-29 §3): the directory V60
   * stored at start — the one its first message named — and, for a phase, its phase directory, so
   * no sub-agent assembles a path. Measured 2026-09-28: a code_reviewer built one from the root's
   * folder name and the phase's id, and it did not exist.
   *
   * @param conductorConversation the delegating conversation
   * @return the note, or empty for a conversation that conducts no live run, or a run with no
   *     directory
   */
  public Optional<String> handoffNote(String conductorConversation) {
    return store
        .byConductorConversation(conductorConversation)
        .filter(run -> !run.state().terminal())
        .flatMap(run -> store.artifactsDir(run.id()))
        .map(Utterances::handoff);
  }

  /**
   * What a live conductor's delegation carries above its task: the run's check as the harness last
   * ran it ({@link CheckFacts}), for a delegate that judges work it cannot run, handed while a
   * checked stage is in progress. Measured 2026-09-30, {@code orc_3190C667F18B8E57}: the check had
   * just passed 26 of 26, and {@code code_reviewer} — which runs nothing — reported with "High"
   * confidence that one of those tests fails; the conductor believed it over the check.
   *
   * <p><b>Recognised by what it holds, not by its name</b>: a callee without {@link RunTool#NAME}
   * cannot run the check, so the only result it can have is the one it is handed. One that can run
   * it — the coder — is told a failing check's output by the refusal the conductor passes on, and
   * is handed nothing here. Every fact is read from the harness's own store: the command from
   * {@link OrchestrationChecks}, the result from the record's latest {@code check_ran} row of this
   * run ({@link OrchestrationRecorder#latestCheck}).
   *
   * @param conductorConversation the delegating conversation
   * @param callee the definition being delegated to
   * @return the block, or empty for a conversation that conducts no live run, a stage in progress
   *     that is not checked, a callee that can run commands, or an engine not given the check store
   */
  public Optional<String> checkFacts(String conductorConversation, AgentDefinition callee) {
    OrchestrationChecks stored = checks;
    if (stored == null || callee.tools().contains(RunTool.NAME)) {
      return Optional.empty();
    }
    OrchestrationRecord run =
        store
            .byConductorConversation(conductorConversation)
            .filter(found -> !found.state().terminal())
            .orElse(null);
    if (run == null || !checkedStageInProgress(run)) {
      return Optional.empty();
    }
    List<String> argv = stored.find(run.id()).map(OrchestrationChecks.Check::argv).orElse(null);
    return Optional.of(
        CheckFacts.block(argv, argv == null ? Optional.empty() : recorder.latestCheck(run)));
  }

  /**
   * Whether the stage item {@code run}'s conductor has in progress is one its definition marks
   * {@code check: required}.
   */
  private boolean checkedStageInProgress(OrchestrationRecord run) {
    Set<String> checked =
        run.stages().stream()
            .filter(StageRules.Stage::checked)
            .map(StageRules.Stage::id)
            .collect(Collectors.toSet());
    return todos.list(run.conductorConversation()).stream()
        .anyMatch(
            item ->
                item.locked()
                    && item.status() == TodoStatus.IN_PROGRESS
                    && checked.contains(item.stageId()));
  }

  /**
   * The first live descendant of a run that is asking the person about being stuck ({@link
   * #STUCK}), or empty. A model's cancel is refused while there is one: every ending cascades with
   * the person's own stop ({@link #cancelDescendants}), so a bot cancelling a waiting root, or a
   * conductor its waiting child, would otherwise end the stuck run beneath it — settling indirectly
   * the question it may neither answer nor cancel (review of spec 2026-09-28). Not atomic with the
   * stop: a descendant that goes stuck between the two is cascaded over, a window as narrow as the
   * cascade's own.
   */
  public Optional<String> stuckBelow(String orchestration) {
    return stuckBelow(orchestration, new HashSet<>());
  }

  private Optional<String> stuckBelow(String id, Set<String> seen) {
    for (OrchestrationRecord child : store.liveChildren(id)) {
      if (!seen.add(child.id())) {
        continue;
      }
      if (child.state() == OrchestrationState.ASKING && STUCK.equals(child.pendingCap())) {
        return Optional.of(child.id());
      }
      Optional<String> deeper = stuckBelow(child.id(), seen);
      if (deeper.isPresent()) {
        return deeper;
      }
    }
    return Optional.empty();
  }

  private boolean cancel(String orchestration, String by, boolean unlessRunning) {
    String parent = store.find(orchestration).map(OrchestrationRecord::parent).orElse(null);
    if (!stopAndTell(
        orchestration, OrchestrationState.CANCELLED, "cancelled by " + by, unlessRunning)) {
      return false;
    }
    releaseWait(parent, orchestration);
    return true;
  }

  /**
   * A parent's conductor cancelling a child it started: stopped as {@link #cancel} stops it, and
   * its ending recorded as delivered rather than carried back up — the parent's own cancel call
   * already told it. Any child's report wakes a waiting parent, so an ending delivered after the
   * parent had started a replacement would have woken it off the replacement's wait.
   *
   * <p><b>Only a child still asking or waiting</b>, as {@link #cancelUnlessRunning}: a conductor is
   * a model caller, and rule 2 (spec 2026-09-27 §3) never lets one stop running work — judged by
   * the stop itself, so a person's answer to the child landing after the conductor's read leaves
   * the child running.
   *
   * <p>Nor a child asking the person about being stuck ({@link #STUCK}), or one with such a run
   * anywhere below it ({@link #stuckBelow}): a parent conductor cannot judge whether a phase should
   * go on, so it may not decide that it should not.
   *
   * @return whether this call is the one that cancelled it; false for a running, a stuck-asking or
   *     an ended child
   */
  public boolean cancelOwnChild(String child, String by) {
    if (stuckBelow(child).isPresent()) {
      return false;
    }
    String parent = store.find(child).map(OrchestrationRecord::parent).orElse(null);
    if (!store.stopUnlessRunning(child, OrchestrationState.CANCELLED, "cancelled by " + by)) {
      return false;
    }
    announce(child);
    recordEnded(child);
    cancelDescendants(child, new HashSet<>());
    store.resultDelivered(child);
    releaseWait(parent, child);
    return true;
  }

  private void releaseWait(String parent, String orchestration) {
    // A PARENT WAITING ON THIS CHILD STOPS WAITING NOW, not when the ending reaches it.
    // Measured 2026-09-25: a root cancelled the phase it waited on and was refused three
    // restarts in the same turn as "already waiting for" the run it had just cancelled — the
    // ending cannot be spoken into a turn still in flight, so delivery put the wait back until
    // that turn ended. Only a wait on this child: a parent waiting on another stays waiting.
    if (parent != null && store.wakeFrom(parent, orchestration)) {
      announce(parent);
    }
  }

  // --- endings ------------------------------------------------------------------------------

  /**
   * Every ending of a conductor's turn, decided from the row as it stands now. Never throws: this
   * runs as the turn's own ended callback, where an exception reaches nobody.
   */
  void ended(String orchestration, Outcome outcome) {
    try {
      route(orchestration, outcome);
    } catch (RuntimeException unrouted) {
      log.error(
          "orchestration {}: the conductor's turn ended {} and deciding what that means"
              + " failed",
          orchestration,
          outcome.ending(),
          unrouted);
    }
  }

  private void route(String orchestration, Outcome outcome) {
    Optional<OrchestrationRecord> found = store.find(orchestration);
    if (found.isEmpty()) {
      log.warn(
          "orchestration {}: the conductor's turn ended {}, but no such run exists",
          orchestration,
          outcome.ending());
      return;
    }
    routeFrom(found.get(), outcome);
    // AN APPROVAL RAISED IN THE TURN REACHES THE PERSON HOWEVER THE TURN ENDED, as a
    // submission's or an event's does (JobStore drains after every ending of those). It used to
    // be drained only on an AWAITING ending of a running row, but a turn's ending is first-wins
    // (TurnEnd): a batch of [orchestration_status on a child still going, a run needing
    // approval] ends ANSWERED on rule 1's sentence, the approval's AWAITING is the request
    // that lost, and the approval sat unseen (final review, 2026-09-27 in-flight work). Only a
    // run still live after routing: one this ending stopped has nothing left to approve for.
    // May run after a new conductor turn has started (the free's drain, or a nudge above), so
    // an answer to the approval can race that turn — the race JobRuntime.
    // deliverApprovalQuestions is placed to avoid. Rare, and accepted.
    store
        .find(orchestration)
        .filter(now -> !now.state().terminal())
        .ifPresent(now -> approvalDrain.accept(now.conductorConversation()));
  }

  private void routeFrom(OrchestrationRecord run, Outcome outcome) {
    String orchestration = run.id();
    Outcome.Ending ending = outcome.ending();
    OrchestrationState state = run.state();
    if (state == OrchestrationState.WAITING) {
      routeWhileWaiting(run, outcome);
      return;
    }
    switch (ending) {
      case AWAITING -> {
        // TWO KINDS OF AWAITING. orchestration_ask's leaves the row asking, with a question
        // for the caller. A command approval — raised by the conductor's own run tool or,
        // through agent_run, by a coder under it — leaves it running, with the approval
        // written against this conversation; it is a person's to answer and never a
        // parent's, since no conductor can grant a command. Measured 2026-09-25: the
        // second was read as the first, found no question, and the tree sat for an hour.
        // The approval is handed to the drain in route, with every ending that leaves the
        // run live; only the question is this branch's.
        if (state != OrchestrationState.RUNNING) {
          deliverOpenQuestion(run, ending);
        }
      }
      case ANSWERED -> {
        if (state == OrchestrationState.FINISHED) {
          delivery.runEnded(run);
        } else if (state == OrchestrationState.RUNNING) {
          if (outcome.requested()) {
            endedOnAChildStillOut(run);
          } else if (!finishedInPlainText(run, outcome)) {
            nudgeOrRestart(run, nudge(run), false, outcome.text());
          }
        } else if (state == OrchestrationState.ASKING) {
          // orchestration_ask wrote its question, but the turn was already ending for
          // another reason and ended ANSWERED: the question is still the one waiting.
          deliverOpenQuestion(run, ending);
        }
        // Any other terminal state: whoever stopped the run delivered its ending. A
        // waiting row never reaches here — routeWhileWaiting took it.
      }
      case CANCELLED -> {
        if (state != OrchestrationState.CANCELLED) {
          stopAndTell(orchestration, OrchestrationState.CANCELLED, "cancelled");
        }
      }
      case TURN_CAP, CALL_BUDGET -> {
        if (state == OrchestrationState.RUNNING) {
          askAboutCap(run, outcome.ending(), failure(outcome));
        } else {
          stopAndTell(orchestration, OrchestrationState.CAPPED, failure(outcome));
        }
      }
      // A call failure (spec 2026-09-28 §4): the conductor kept writing its calls as text and
      // its own turn loop stopped it after three warnings. Failed as that, not `stuck`, and
      // no nudge is spent: a nudge would only ask again for the call it was already asked for.
      case CALL_FAILURES ->
          stopAndTell(orchestration, OrchestrationState.FAILED, KEPT_WRITING_CALLS);
      default -> stopAndTell(orchestration, OrchestrationState.FAILED, failure(outcome));
    }
  }

  /**
   * Every ending of a turn whose row is {@code waiting}. The conductor started a child and stopped
   * talking, which is what waiting means: the row moved inside {@code orchestrate_<name>}, so the
   * ordinary ending is nothing to do at all — no nudge, no question to deliver, and no ending to
   * tell the caller about. What wakes this row is a child's report.
   *
   * <p><b>A cap is not asked about here, and does not cap the run either.</b> The turn that hit it
   * is over and the row is not going to spend anything until its child reports; the wake speaks a
   * fresh turn, with a fresh turn cap. A model-call budget that is genuinely spent is deferred
   * rather than settled: the wake's own speak is refused for it, and {@link #speakToParent} settles
   * it then — a spent budget becomes the cap question its caller can answer, and any other refusal
   * from a conductor that is not speaking fails the run with the refusal as its failure. A refusal
   * from one that <em>is</em> still speaking settles nothing and puts the wait back, since it only
   * means "later". Nothing is left live with no turn on it.
   *
   * <p>Only a turn that ended badly still ends the run — a cancelled job, and anything in {@code
   * route}'s own default — because those say the conductor is gone, not busy waiting.
   */
  private void routeWhileWaiting(OrchestrationRecord run, Outcome outcome) {
    switch (outcome.ending()) {
      case CANCELLED -> stopAndTell(run.id(), OrchestrationState.CANCELLED, "cancelled");
      case AWAITING, ANSWERED, TURN_CAP, CALL_BUDGET ->
          log.info(
              "orchestration {}: the conductor's turn ended {} and the run is waiting for {},"
                  + " so nothing is done until that child reports",
              run.id(),
              outcome.ending(),
              run.waitingFor());
      case CALL_FAILURES -> stopAndTell(run.id(), OrchestrationState.FAILED, KEPT_WRITING_CALLS);
      default -> stopAndTell(run.id(), OrchestrationState.FAILED, failure(outcome));
    }
  }

  private void deliverOpenQuestion(OrchestrationRecord run, Outcome.Ending ending) {
    if (run.state() != OrchestrationState.ASKING) {
      log.info(
          "orchestration {}: the conductor's turn ended {} but the run is {}, so there"
              + " is no question to deliver",
          run.id(),
          ending,
          run.state().wire());
      return;
    }
    Optional<OrchestrationMessage> question = store.openQuestion(run.id());
    if (question.isEmpty()) {
      log.warn("orchestration {} is asking but has no open question", run.id());
      return;
    }
    delivery.questionAsked(run, question.get());
  }

  /**
   * Decision 7: a running conductor that hit a cap asks its caller, if it has one to ask. A caller
   * can be asked only where {@link Delivery} can reach it — a caller conversation that exists with
   * {@link Origin#TURN} origin, or an account's inbox. Anywhere else the question would only be
   * marked delivered unheard, and the run would wait on it for ever.
   *
   * <p>Spec 2026-09-29 §2: first, a cap the person's {@code auto-continue} still covers is passed
   * without asking anyone ({@link #autoContinued}). Past that, the question goes to the model — the
   * caller, or a parent conductor — and to the person at the same moment, and the first answer
   * settles it. Measured 2026-09-28 23:51, {@code orc_31893856D8F462A1}: a phase's cap question
   * reached only its parent, which ended its turn in prose over it, and the two waited on each
   * other for five hours; the person, who would have said {@code yes} at once, was never asked.
   */
  private void askAboutCap(OrchestrationRecord run, Outcome.Ending ending, String failure) {
    // A run past its time cap (V69) is asked about that first: the turn that stopped may be
    // the one the time cap's sweep stopped, and a "go on" to it is a fresh turn too.
    Optional<PastTime> past = pastTimeCap(run);
    if (past.isPresent()) {
      askAboutTime(run, past.get());
      return;
    }
    String kind = ending == Outcome.Ending.CALL_BUDGET ? CALL_BUDGET : TURN_CAP;
    if (autoContinued(run, kind)) {
      return;
    }
    if (!callerCanBeAsked(run)) {
      stopAndTell(run.id(), OrchestrationState.CAPPED, failure);
      return;
    }
    Rebuilt rebuilt = rebuild(run);
    if (rebuilt.conductor() == null) {
      // A yes could never be honoured: there is no conductor left to speak to.
      stopAndTell(run.id(), OrchestrationState.FAILED, rebuilt.failure());
      return;
    }
    Integer limit = budgetOf(run).filter(Budget::capped).map(Budget::limit).orElse(null);
    askBoth(
        run, kind, Utterances.capQuestion(run, ending, limit, rebuilt.conductor().maxModelCalls()));
  }

  /**
   * A cap's question, asked of the model and the person at once (spec 2026-09-29 §2): recorded,
   * delivered to the caller or parent, and put in the person's inbox beside it.
   */
  private void askBoth(OrchestrationRecord run, String kind, String question) {
    Optional<OrchestrationMessage> asked = store.askCap(run.id(), kind, question);
    if (asked.isEmpty()) {
      log.info(
          "orchestration {}: its conductor hit its {} but the run moved on before the"
              + " cap could be asked about",
          run.id(),
          kind);
      return;
    }
    announce(run.id());
    recorder.questionAsked(store.find(run.id()).orElse(run), question);
    delivery.questionAsked(store.find(run.id()).orElse(run), asked.get());
    // §2: the person at the same moment as the model; the first answer settles it.
    delivery.toThePerson(store.find(run.id()).orElse(run), asked.get());
  }

  // --- the time cap (V69) -------------------------------------------------------------------

  /**
   * A run past its time cap: its clock, and the cap in minutes.
   *
   * @param clock the run's clock as it read
   * @param minutes the project's {@code caps: time}
   */
  private record PastTime(OrchestrationStore.RunClock clock, int minutes) {}

  /**
   * The project's time cap in minutes, or null for none — and for caps that cannot be read, since a
   * run is never stopped over a file ({@link #capped}'s rule).
   */
  private Integer timeCapOf(OrchestrationRecord run) {
    try {
      Integer minutes = caps.capsFor(run.project()).time().value();
      return minutes == null || minutes < 1 ? null : minutes;
    } catch (RuntimeException unreadable) {
      log.warn(
          "orchestration {}: its project's caps could not be read, so it has no time" + " cap",
          run.id(),
          unreadable);
      return null;
    }
  }

  /**
   * {@code run}'s clock when it has run its project's time cap or longer in its current span, or
   * empty. <b>The clock</b> is the engine's own ({@code clock}, the server's wall clock in
   * production) against the run's {@code created_at}, less every spell it spent asking — a
   * question, a cap, or stuck, answered by anyone ({@link OrchestrationStore#clockOf}). Time a run
   * spends waiting on a child is counted, since the child's work is its own; so is time waiting on
   * a command approval, which the run's row cannot tell from working. A root and each phase count
   * their own: each has its own {@code created_at}.
   */
  private Optional<PastTime> pastTimeCap(OrchestrationRecord run) {
    Integer minutes = timeCapOf(run);
    if (minutes == null) {
      return Optional.empty();
    }
    return store
        .clockOf(run.id(), clock.get())
        .filter(read -> read.inSpan() >= minutes * 60L)
        .map(read -> new PastTime(read, minutes));
  }

  /**
   * The time cap's sweep (V69): every {@code running} run past its time cap with a conductor turn
   * in flight has that turn's step cap lowered so it stops at its next step boundary — the same
   * door a {@code /cap steps} reaches a turn in flight through ({@link #applyCaps}). The turn then
   * ends {@code TURN_CAP}, and {@link #askAboutCap} asks about the time instead.
   *
   * <p><b>A run with no turn in flight is left alone</b> — one waiting on an approval, between
   * turns, or waiting on a child: speaking to it or asking it now would race whatever speaks to it
   * next, and whatever does starts a turn this sweep then stops within the minute. A step taken by
   * that turn is the price of never asking a run that is about to be spoken to.
   *
   * <p>Run every minute beside the stall sweep, on its switch. Never throws: one run's failure is
   * logged and the pass goes on.
   *
   * @return how many turns in flight it told to stop
   */
  public int sweepTimeCaps() {
    int stopping = 0;
    for (OrchestrationRecord run : store.running()) {
      try {
        if (pastTimeCap(run).isEmpty()) {
          continue;
        }
        Optional<RunLimits> limits = liveLimits.apply(run.conductorConversation());
        if (limits.isEmpty()) {
          continue;
        }
        limits.get().cap().changeTo(1);
        stopping++;
      } catch (RuntimeException failed) {
        log.warn(
            "the time cap sweep could not judge orchestration {}; the next sweep" + " tries again",
            run.id(),
            failed);
      }
    }
    return stopping;
  }

  /**
   * Ask about a run past its time cap, as a cap is asked (spec 2026-09-29 §2): auto-continue first
   * — each continue grants another span of the cap's minutes — then the model and the person at
   * once. Nowhere to ask, it is capped.
   */
  private void askAboutTime(OrchestrationRecord run, PastTime past) {
    if (autoContinued(run, TIME_CAP)) {
      return;
    }
    long ran = past.clock().counted() / 60;
    if (!callerCanBeAsked(run)) {
      stopAndTell(
          run.id(),
          OrchestrationState.CAPPED,
          "time cap: ran " + ran + " minutes, past its cap of " + past.minutes());
      return;
    }
    Rebuilt rebuilt = rebuild(run);
    if (rebuilt.conductor() == null) {
      stopAndTell(run.id(), OrchestrationState.FAILED, rebuilt.failure());
      return;
    }
    askBoth(
        run,
        TIME_CAP,
        Utterances.timeQuestion(
            run, ran, past.minutes(), recorder.latestMilestone(run).orElse(null)));
  }

  /**
   * A new time span for {@code run}, of {@code minutes}: its clock's span is moved so that the cap
   * is reached again after that many more minutes of running.
   *
   * @param minutes the minutes granted; the cap's own for a {@code yes} or an auto-continue
   */
  private void grantTime(OrchestrationRecord run, int minutes) {
    store
        .clockOf(run.id(), clock.get())
        .ifPresent(
            read -> {
              Integer cap = timeCapOf(run);
              long from =
                  cap == null ? read.counted() : read.counted() - (cap - (long) minutes) * 60;
              store.timeSpanFrom(run.id(), from);
            });
  }

  /**
   * The person or the model answered {@link #askAboutTime}: {@code yes} grants another span of the
   * cap's minutes, a positive number that many minutes, and anything else caps the run. A "go on"
   * is a fresh turn, as a turn cap's is: the turn the sweep stopped is over.
   */
  private boolean settleTime(
      OrchestrationRecord run,
      AgentDefinition conductor,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    String given = answer.text() == null ? "" : answer.text().strip();
    String decision = given.toLowerCase(Locale.ROOT);
    Integer cap = timeCapOf(run);
    Integer grant = null;
    if (decision.equals("yes") || decision.equals("y")) {
      grant = cap == null ? 0 : cap;
    } else {
      try {
        int number = Integer.parseInt(decision);
        if (number > 0) {
          grant = number;
        }
      } catch (NumberFormatException notANumber) {
        // Anything that is neither yes nor a positive number declines.
      }
    }
    if (grant == null) {
      store.messageDelivered(answer.id());
      stopAndTell(run.id(), OrchestrationState.CAPPED, "time cap not raised: " + given);
      return true;
    }
    // Moved before the budget is looked at, so the budget's own question is not taken for
    // the time cap again (askAboutCap asks about the time first).
    grantTime(run, grant);
    if (budgetExhausted(run)) {
      store.messageDelivered(answer.id());
      askAboutExhaustedBudget(run);
      return true;
    }
    String utterance = Utterances.capRaised(TIME_CAP, null);
    String folded = answers(run, earlier);
    if (!folded.isEmpty()) {
      utterance = utterance + "\n\n" + folded;
    }
    if (!speakOrLeavePending(run, conductor, utterance, null, attempt)) {
      return false;
    }
    delivered(earlier);
    store.messageDelivered(answer.id());
    return true;
  }

  // --- failed checks (V69) ------------------------------------------------------------------

  /** The answers to {@link #CHECK_FAILURES} that stop the run, lower-cased. */
  static final Set<String> STOPS = Set.of("stop", "no", "n");

  /** The answers to {@link #CHECK_FAILURES} that only say go on, lower-cased. */
  static final Set<String> GOES_ON = Set.of("go on", "yes", "y", "continue");

  /**
   * Where the stage checks and the acceptance gate count a run's failure (V69): {@link
   * #checkFailed}.
   *
   * @return the port
   */
  public CheckFailures checkFailures() {
    return this::checkFailed;
  }

  /**
   * One more failure of a run's check or its acceptance commands. At its project's {@code caps:
   * failed-checks} ({@value ProjectCaps#DEFAULT_FAILED_CHECKS} when unset) since it started or
   * since the person last answered, the person is asked whether it goes on ({@link
   * #CHECK_FAILURES}), with the failure's output — and only the person: the question rides {@link
   * #askAboutStuck}'s machinery, so {@link Delivery} puts it in their inbox and a model's answer is
   * refused. Never auto-continued: a repeating failure is exactly what the person must see. A run
   * with no account behind it is refused as before, since nobody could answer.
   *
   * @param id the run
   * @param what what failed, as the question names it — {@code check `pytest -q`}
   * @param output the end of its output
   * @return whether the person was asked; the caller then ends the conductor's turn
   */
  boolean checkFailed(String id, String what, String output) {
    OrchestrationRecord run = store.find(id).orElse(null);
    if (run == null || run.state().terminal()) {
      return false;
    }
    int n = store.checkFailed(id);
    int most;
    try {
      most = caps.capsFor(run.project()).failedChecksLimit();
    } catch (RuntimeException unreadable) {
      most = ProjectCaps.DEFAULT_FAILED_CHECKS;
    }
    if (n < most || run.callerHandle() == null) {
      return false;
    }
    String question = Utterances.checkFailuresQuestion(run, what, n, output);
    Optional<OrchestrationMessage> asked = store.askCap(id, CHECK_FAILURES, question);
    if (asked.isEmpty()) {
      log.info(
          "orchestration {}: its check failed {} times but the run moved on before the"
              + " person could be asked",
          id,
          n);
      return false;
    }
    announce(id);
    recorder.questionAsked(store.find(id).orElse(run), question);
    delivery.questionAsked(store.find(id).orElse(run), asked.get());
    return true;
  }

  /**
   * The person answered {@link #checkFailed}'s question: {@code stop} (or {@code no}) caps the run;
   * anything else goes on — the count starts again, and the conductor is told to fix what the
   * output shows, with the person's words when they said more than "go on".
   */
  private boolean settleCheckFailures(
      OrchestrationRecord run,
      AgentDefinition conductor,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    String given = answer.text() == null ? "" : answer.text().strip();
    String decision = given.toLowerCase(Locale.ROOT);
    if (STOPS.contains(decision)) {
      store.messageDelivered(answer.id());
      stopAndTell(
          run.id(), OrchestrationState.CAPPED, "its check kept failing, and the person stopped it");
      return true;
    }
    store.checkFailuresSettled(run.id());
    store.progressed(run.id());
    if (budgetExhausted(run)) {
      store.messageDelivered(answer.id());
      askAboutExhaustedBudget(run);
      return true;
    }
    String utterance = Utterances.checkFailuresGoOn(GOES_ON.contains(decision) ? null : given);
    String folded = answers(run, earlier);
    if (!folded.isEmpty()) {
      utterance = utterance + "\n\n" + folded;
    }
    if (!speakOrLeavePending(run, conductor, utterance, null, attempt)) {
      return false;
    }
    delivered(earlier);
    store.messageDelivered(answer.id());
    return true;
  }

  /**
   * Auto-continue (spec 2026-09-29 §2): a cap reached with {@code auto-continue} remaining
   * continues at once, as a {@code yes} would — a fresh turn, or a budget raised by the conductor's
   * own (the person's {@code budget}, when set) — counted per run and recorded {@code cap continued
   * (n/N)}; after N the question is asked. A time cap's continue grants another span of its minutes
   * (V69). Never for {@link #STUCK} or {@link #CHECK_FAILURES}, which do not come through here:
   * whether a run that stopped moving, or whose check keeps failing, goes on is not a number.
   *
   * <p>A conductor already speaking — a drain spoke a pending answer on the ended turn's free — is
   * already continuing: nothing is counted and nothing is spoken, since a second speak would be
   * refused as in flight and fail a working run.
   *
   * @return whether it continued (or was already going), and so nobody is asked
   */
  private boolean autoContinued(OrchestrationRecord run, String kind) {
    Integer most;
    boolean automatic;
    try {
      var policy = caps.capsFor(run.project());
      automatic =
          policy.increasesAutomatically() && (TURN_CAP.equals(kind) || CALL_BUDGET.equals(kind));
      most = policy.autoContinue().value();
    } catch (RuntimeException unreadable) {
      log.warn(
          "orchestration {}: its project's caps could not be read, so its cap is asked"
              + " about rather than continued",
          run.id(),
          unreadable);
      return false;
    }
    if (!automatic && (most == null || most < 1)) {
      return false;
    }
    Rebuilt rebuilt = rebuild(run);
    if (rebuilt.conductor() == null) {
      return false;
    }
    if (voice.isSpeaking(run.conductorConversation())) {
      log.info(
          "orchestration {}: its conductor is already speaking, so its {} is neither"
              + " continued nor asked about",
          run.id(),
          kind);
      return true;
    }
    if ((TURN_CAP.equals(kind) || TIME_CAP.equals(kind)) && budgetExhausted(run)) {
      // As settleCap: a fresh turn with no call left would only be refused, so the budget
      // is the next cap — continued too while any are left, asked about after. The turn cap
      // is not counted: nothing continued it. A time cap's span is granted first, so the
      // budget's question is not taken for the time again.
      if (TIME_CAP.equals(kind)) {
        Integer minutes = timeCapOf(run);
        grantTime(run, minutes == null ? 0 : minutes);
      }
      askAboutExhaustedBudget(run);
      return true;
    }
    if (automatic
        && CALL_BUDGET.equals(kind)
        && budgetOf(run).map(Budget::spent).orElse(0) == Integer.MAX_VALUE) return false;
    OptionalInt n = automatic ? store.capIncreased(run.id()) : store.capContinued(run.id(), most);
    if (n.isEmpty()) {
      return false;
    }
    if (TIME_CAP.equals(kind)) {
      // Each continue grants another span of the cap's minutes.
      Integer minutes = timeCapOf(run);
      grantTime(run, minutes == null ? 0 : minutes);
    }
    Integer newLimit = null;
    if (CALL_BUDGET.equals(kind)) {
      int spent = budgetOf(run).map(Budget::spent).orElse(0);
      try {
        newLimit = Math.addExact(spent, rebuilt.conductor().maxModelCalls());
      } catch (ArithmeticException tooLarge) {
        newLimit = Integer.MAX_VALUE;
      }
    }
    // Recorded only once the turn is going (Task 13's review): a speak that was refused
    // failed the run instead, and "cap continued" above that failure would say it went on.
    if (speakOrFail(run, rebuilt.conductor(), Utterances.capRaised(kind, newLimit), newLimit)) {
      if (automatic) recorder.capIncreased(run, kind, n.getAsInt());
      else recorder.capContinued(run, kind, n.getAsInt(), most);
    }
    return true;
  }

  /**
   * Who already answered a run that is no longer asking — for a model whose answer came second
   * (spec 2026-09-29 §2: a cap question goes to the person and the model at once, and the first
   * answer settles it). Told who and what, it has nothing to guess about.
   *
   * <p><b>Only when that answer settled the run's latest question</b> (Task 13's review): each
   * question is settled by exactly one answer, so a run with as many answers as questions had its
   * latest settled by its latest answer. A run stopped while asking has one question more than
   * answers, and the newest answer then belongs to an older question — naming it would tell the
   * model somebody settled a question nobody did.
   *
   * @param orchestration the run's id
   * @return the sentence, or empty for a run still asking, never answered, whose latest question is
   *     unanswered, or not found
   */
  public Optional<String> answeredAlready(String orchestration) {
    return store
        .find(orchestration)
        .filter(run -> run.state() != OrchestrationState.ASKING)
        .filter(run -> latestQuestionSettled(orchestration))
        .flatMap(run -> store.latestAnswer(orchestration))
        .map(
            answer ->
                "Orchestration "
                    + orchestration
                    + "'s question was already"
                    + " answered by "
                    + answer.author()
                    + " (`"
                    + answer.text()
                    + "`);"
                    + " nothing changed.");
  }

  private boolean latestQuestionSettled(String orchestration) {
    List<OrchestrationMessage> messages = store.messages(orchestration);
    long questions =
        messages.stream().filter(m -> m.kind() == OrchestrationMessage.Kind.QUESTION).count();
    long answers = messages.size() - questions;
    return questions > 0 && answers >= questions;
  }

  /**
   * A conductor that did the work and said so in prose, finished on the strength of the work.
   *
   * <h2>Measured, on the first orchestration tree ever to run against a live model</h2>
   *
   * <p>2026-09-25, {@code gpt-oss-120b} at {@code reasoning_effort=medium}, both levels of a
   * two-level tree. {@code code_implementation} worked through all seven of its stages — goal,
   * spec, plan, test_design, tests, code, review — wrote its artifacts, had {@code coder} report
   * the tests passing and {@code code_reviewer} find nothing, and then ended its turn with <i>"The
   * orchestration code_implementation has now completed all stages"</i> as text. Nudged, it called
   * {@code todo_write} and ended with <b>"Orchestration finished."</b> as text. Nudged again: the
   * same sentence again. The third nudge failed it {@code stuck} and the cascade took the phase
   * with it. Its root died the same way one turn later, and had already produced the purer form of
   * the same fault on an earlier turn: its whole message content was {@code &#123;"question":
   * "..."&#125;} — a tool call's arguments, emitted as prose, with no tool call.
   *
   * <p>The nudge is not vague and was not misread. It names the tool, and says that a turn ending
   * in plain text is not how an orchestration ends. The conductor read that twice.
   *
   * <h2>Why this is allowed to finish the run rather than press harder</h2>
   *
   * <p><b>The engine already trusts the same fact this leans on.</b> {@link #finish} refuses until
   * every stage is done, so "may this run end" was never the conductor's judgement to make — the
   * stages are. What calling the tool adds over ending in prose is the <em>result text</em>, and a
   * conductor that has just written a paragraph about what it produced has supplied one. So this
   * hands that paragraph to {@link #finish} unchanged and lets the same checks decide: a refusal —
   * stages outstanding, a child still live — falls through to the nudge exactly as before.
   *
   * <p><b>What it costs, said plainly.</b> Narration becomes protocol. A conductor that marked its
   * stages done without doing them and then talked would now finish a run it had not completed.
   * That is a real loss and it is smaller than it looks: such a conductor could already finish that
   * run by calling the tool, because the stages are the gate either way. What is no longer possible
   * is the outcome this method is named for — a tree whose work is complete, whose artifacts are on
   * disk and whose tests pass, thrown away over the shape of its last sentence.
   *
   * <p><b>A blank turn is not a result and still nudges.</b> The root's third turn above answered
   * with nothing at all; there is no paragraph to finish on, and a run finished on an empty result
   * would tell its caller the tree was done and hand them silence.
   *
   * <p>No bound on the text, on parity with {@code ConductorTools.Finish}, which puts whatever the
   * model passed into {@code result}. A cap here and not there would mean two answers to what a
   * result may be, differing only in how the run happened to end.
   *
   * @return whether the run was finished, and so whether the nudge is to be skipped
   */
  private boolean finishedInPlainText(OrchestrationRecord run, Outcome outcome) {
    String said = outcome.text() == null ? "" : outcome.text().strip();
    if (said.isEmpty()) {
      return false;
    }
    Optional<String> refused = finish(run.id(), said);
    if (refused.isPresent()) {
      // Discarded rather than spoken: the conductor did not call anything, so there is no
      // tool result to put this in, and the nudge that follows renders the stages anyway.
      // Logged because a refusal nobody is told about is how this path would go wrong
      // quietly.
      log.debug(
          "orchestration {}: its turn ended in plain text and could not be finished on"
              + " it, so it is nudged instead. Reason: {}",
          run.id(),
          refused.get());
      return false;
    }
    log.info(
        "orchestration {}: its conductor ended a turn in plain text with every stage done,"
            + " so the run is finished on that text as its result",
        run.id());
    store.find(run.id()).ifPresent(delivery::runEnded);
    return true;
  }

  /**
   * A running conductor whose turn a tool ended {@code ANSWERED} without finishing the run — rule 1
   * (spec 2026-09-27 §2): it checked on a child it started while that child was still going, and
   * was told its result would come to it. {@code orchestration_finish} is the only other tool that
   * ends a turn {@code ANSWERED}, and it leaves the row {@code finished}, not here.
   *
   * <p><b>Never the conductor's prose, so never finished on.</b> The text is the harness's own
   * sentence about the child. Measured by the final review: with every stage done and the child
   * ending between the status call and this route, {@link #finishedInPlainText} found no live child
   * and finished the tree with that sentence as its result, delivered to the person, while the
   * child's real report landed on a finished run.
   *
   * <p><b>What wakes it is the child's report.</b> A child that ended while this turn was going had
   * its report held, since a report is not spoken into a turn in flight ({@link Delivery}), and
   * {@code Turn}'s free normally speaks it before this runs. A report still held here is delivered
   * now, so a conductor with nothing left running is not left with nothing to wake it. Then the
   * ordinary nudge, whose own guards make it nothing while a child still runs or the report's turn
   * is already speaking, and a nudge only for a conductor with neither — which would otherwise be a
   * running row with no turn on it.
   */
  private void endedOnAChildStillOut(OrchestrationRecord run) {
    for (OrchestrationRecord child : store.children(run.id())) {
      if (child.endedAt() != null && child.resultDeliveredAt() == null) {
        delivery.runEnded(child);
      }
    }
    store
        .find(run.id())
        .filter(now -> now.state() == OrchestrationState.RUNNING)
        .ifPresent(now -> nudgeOrRestart(now, nudge(now), false, null));
  }

  /**
   * Speak to a running conductor again, after a turn that ended in plain text ({@code restart}
   * false) or after a server restart ({@code restart} true). Each is counted, and a run past {@link
   * #MAX_NUDGES_OR_RESTARTS} restarts fails {@code restarted}; one past it in nudges asks the
   * person whether it goes on ({@link #askAboutStuck}), and fails {@code stuck} only when there is
   * no person to ask. A conductor already speaking is neither, and nothing is counted.
   *
   * <p><b>A run with a child still running is neither either.</b> Such a run is not stalled: it is
   * waiting on work whose report is what drives its next turn, through {@link #speakToParent},
   * whether or not its row says {@code waiting} — and a row that is {@code running} while a child
   * of it runs is the ordinary case, not a rare one. A parent that answered its child's question
   * has one: {@code orchestration_answer} ends no turn and no tool puts the row back to {@code
   * waiting}, so that turn ends in plain text with the row {@code running}. Nudged, the third such
   * ending would fail the parent {@code stuck} and {@link #cancelDescendants} would cancel the
   * phase it was waiting on. A run with NO live children still stops on the third: that is the
   * deliberate limit for a conductor that has stopped moving, and the one a root with a goal it
   * cannot settle is told to leave its explanation in a question for — only now it stops to ask the
   * person rather than to fail.
   *
   * <p>…unless that child is asking this run a question it has already heard (rule 2, spec
   * 2026-09-29): then the nudge is that question. Measured 2026-09-28 23:51, {@code
   * orc_31893856D8F462A1}: a phase asked its parent about its turn cap, the parent ended its turn
   * in prose, and this guard left it alone because the phase was "live" — each waited on the other
   * for five hours. Counted like any nudge; the parent's answer to it ({@link #answerAsModel}) is
   * progress, so only a parent that keeps ignoring its child reaches the stuck question. A cap
   * question the person holds too is the exception: nudged once and not counted, since leaving it
   * to the person is not being stuck. A restart is not turned into one — it would be counted as a
   * restart, and a question already heard is not spoken again by the drain; such a parent is what
   * the stall sweep now reports ({@link StallSweep#waitsOnItsParent}, rule 3).
   *
   * @return whether a turn was started
   */
  boolean nudgeOrRestart(OrchestrationRecord run, String notice, boolean restart) {
    return nudgeOrRestart(run, notice, restart, null);
  }

  /**
   * @param said the text the conductor's last turn ended on, which the person's question quotes the
   *     first line of; {@code null} for a restart, or a turn whose text was the harness's own
   */
  boolean nudgeOrRestart(OrchestrationRecord run, String notice, boolean restart, String said) {
    List<OrchestrationRecord> live = store.liveChildren(run.id());
    boolean counted = true;
    OrchestrationRecord capChild = null;
    String capQuestion = null;
    if (!live.isEmpty()) {
      // RULE 2 (spec 2026-09-29 §3): a child waiting on this conductor's answer is not work
      // that will report; left alone, each waits on the other. Nudged with its question,
      // and counted, so a conductor that ignores it still reaches the stuck question —
      // which quotes what the conductor last said, as any stuck question does (Task 5's
      // review): the nudge replaces what it is told, not what it said.
      Optional<ChildAsking> asking = restart ? Optional.empty() : childAskingThisRun(live);
      if (asking.isEmpty()) {
        log.debug(
            "orchestration {}: a child of it is still running, so it is neither"
                + " nudged nor restarted; that child's report is what drives its next"
                + " turn",
            run.id());
        return false;
      }
      OrchestrationRecord child = asking.get().child();
      if (Utterances.personHolds(child)) {
        // A CAP QUESTION THE PERSON HOLDS TOO (spec 2026-09-29 §2) is not a deadlock: the
        // person may answer it, and a conductor that leaves it to them is doing as its
        // prompt says (final review). So it is nudged once per question, and not counted
        // — a conductor that leaves it to the person is not stuck, and must not be walked
        // into the person's stuck question over a question the person already has.
        String questionId = asking.get().question().id();
        if (questionId.equals(capNudged.get(child.id()))) {
          log.info(
              "orchestration {}: its child {} is asking about a cap the person"
                  + " holds too, and it was nudged once about it, so it is left to the"
                  + " person",
              run.id(),
              child.id());
          return false;
        }
        // Marked spent only once it is spoken, below (re-review): a nudge the early
        // returns never sent — a conductor already speaking, a spent budget — is still
        // owed.
        capChild = child;
        capQuestion = questionId;
        counted = false;
      }
      notice = Utterances.answerYourChild(child, asking.get().question());
    }
    if (voice.isSpeaking(run.conductorConversation())) {
      // Another turn is already driving this run — the drain on the ended turn's free spoke
      // a pending answer before this ending was routed. Speaking again would be refused as
      // in flight and fail a run that is working.
      log.info(
          "orchestration {}: its conductor is already speaking, so it is neither nudged"
              + " nor restarted",
          run.id());
      return false;
    }
    if (budgetExhausted(run)) {
      // The conductor spent its last call on the turn that just ended, which JobRuntime
      // ends ANSWERED or AWAITING rather than CALL_BUDGET; speaking again would only be
      // refused. It is the same question a CALL_BUDGET ending asks, and it costs no nudge.
      askAboutExhaustedBudget(run);
      return false;
    }
    OptionalInt count =
        restart ? store.restarted(run.id()) : counted ? store.nudged(run.id()) : OptionalInt.of(0);
    if (count.isEmpty()) {
      return false;
    }
    if (count.getAsInt() > MAX_NUDGES_OR_RESTARTS) {
      if (restart) {
        stopAndTell(run.id(), OrchestrationState.FAILED, "restarted");
      } else {
        askAboutStuck(run, said);
      }
      return false;
    }
    todos.forget(run.conductorConversation());
    Rebuilt rebuilt = rebuild(run);
    if (rebuilt.conductor() == null) {
      stopAndTell(run.id(), OrchestrationState.FAILED, rebuilt.failure());
      return false;
    }
    boolean spoken = speakOrFail(run, rebuilt.conductor(), notice, null);
    if (spoken && capChild != null) {
      capNudged.put(capChild.id(), capQuestion);
    }
    return spoken;
  }

  /** A live child asking this run, and the question it has already been told. */
  private record ChildAsking(OrchestrationRecord child, OrchestrationMessage question) {}

  /**
   * The first live child asking its parent a question the parent has heard — not {@link #STUCK},
   * which is the person's. An undelivered one is the delivery drain's: it speaks it on this
   * conductor's free, which is a turn of its own.
   */
  private Optional<ChildAsking> childAskingThisRun(List<OrchestrationRecord> live) {
    for (OrchestrationRecord child : live) {
      if (child.state() != OrchestrationState.ASKING || personOnly(child.pendingCap())) {
        continue;
      }
      Optional<OrchestrationMessage> question =
          store.openQuestion(child.id()).filter(open -> open.deliveredAt() != null);
      if (question.isPresent()) {
        return Optional.of(new ChildAsking(child, question.get()));
      }
    }
    return Optional.empty();
  }

  /**
   * A run whose conductor ended {@code MAX_NUDGES_OR_RESTARTS + 1} turns in a row without progress
   * asks the person whether it goes on, instead of failing {@code stuck}.
   *
   * <p><b>Measured 2026-09-28, {@code orc_3187D648AC346812}.</b> Each of its three prose endings
   * claimed the run complete while stages were pending, with an hour of real work between them;
   * each nudge worked. It failed {@code stuck} anyway, and the person got that word and no way on.
   * So the question says what it did, what it last said and what is still pending, and gives the
   * two commands that settle it.
   *
   * <p><b>The person's, never a model's</b> — a root's bot and a phase's parent conductor alike —
   * since neither saw it stop and the one that was told guessed. It rides {@link #askAboutCap}'s
   * machinery with {@link #STUCK} as its kind; {@link Delivery} sends it to the person's inbox,
   * which is why a run with no account fails as before: nobody could ever answer it.
   *
   * <p>Told to the record ({@link #recorder}) as a plain {@code question_asked}, on the same
   * footing as {@link #askAboutCap}'s and {@link #ask}'s: a person reading the story back has no
   * reason to be told this question came from a nudge counter rather than the conductor itself —
   * both are the run asking something only a person can answer.
   */
  private void askAboutStuck(OrchestrationRecord run, String said) {
    if (run.callerHandle() == null) {
      stopAndTell(run.id(), OrchestrationState.FAILED, "stuck");
      return;
    }
    List<String> pending = pendingStages(run).stream().map(Utterances.PendingStage::id).toList();
    String question = Utterances.stuckQuestion(run, MAX_NUDGES_OR_RESTARTS + 1, said, pending);
    Optional<OrchestrationMessage> asked = store.askCap(run.id(), STUCK, question);
    if (asked.isEmpty()) {
      log.info(
          "orchestration {}: its conductor stopped making progress but the run moved on"
              + " before the person could be asked",
          run.id());
      return;
    }
    announce(run.id());
    recorder.questionAsked(store.find(run.id()).orElse(run), question);
    delivery.questionAsked(store.find(run.id()).orElse(run), asked.get());
  }

  /**
   * Speak every undelivered answer of a running run to its conductor, in order, in one turn — or,
   * when the newest is an answer to a cap, act on it and fold the earlier ones into the
   * continuation. Left pending, and {@code false}, when the conductor refuses the turn while {@link
   * ConductorVoice#isSpeaking} says it is still speaking: that turn's free drains it again. After
   * releasing the in-flight guard, a refused attempt checks once whether the conductor became free
   * and retries once; this closes the race where that drain ran while the answer was still guarded.
   * A refusal from a conductor that is not speaking fails the run instead, since no drain would
   * ever get past it.
   *
   * <p><b>A waiting run is not one of them.</b> The filter below is {@code RUNNING} only, so an
   * answer that landed just before its row went {@code waiting} sits undelivered until something
   * wakes the row — which is why {@link #speakToParent} re-drives this after it has spoken.
   *
   * <p><b>An exhausted budget is asked about, not spoken into.</b> A conductor whose last allowed
   * call asked its question ends {@code AWAITING} with nothing left to spend, and speaking the
   * answer would only be refused for ever. The answer stays undelivered, the caller is asked to
   * raise the budget, and a raise speaks it.
   *
   * @return whether answers were spoken, or a cap answer settled
   */
  boolean speakAnswerIfPending(String orchestration) {
    return speakAnswerIfPending(orchestration, true);
  }

  private boolean speakAnswerIfPending(String orchestration, boolean retryOnceFree) {
    PendingAttempt attempt = new PendingAttempt();
    boolean spoken = speakAnswerOnce(orchestration, attempt);
    if (!spoken
        && retryOnceFree
        && attempt.deferredConversation != null
        && !voice.isSpeaking(attempt.deferredConversation)) {
      return speakAnswerIfPending(orchestration, false);
    }
    return spoken;
  }

  private boolean speakAnswerOnce(String orchestration, PendingAttempt attempt) {
    Optional<OrchestrationRecord> found =
        store.find(orchestration).filter(run -> run.state() == OrchestrationState.RUNNING);
    if (found.isEmpty()) {
      return false;
    }
    OrchestrationRecord run = found.get();
    List<Pending> pending = pendingAnswers(store.messages(orchestration));
    if (pending.isEmpty()) {
      return false;
    }
    OrchestrationMessage newest = pending.get(pending.size() - 1).answer();
    if (!speakingAnswers.add(newest.id())) {
      return false;
    }
    try {
      Rebuilt rebuilt = rebuild(run);
      if (rebuilt.conductor() == null) {
        stopAndTell(run.id(), OrchestrationState.FAILED, rebuilt.failure());
        return false;
      }
      if (INSTALL.equals(newest.capKind())) {
        return settleInstall(
            run, rebuilt.conductor(), newest, pending.subList(0, pending.size() - 1), attempt);
      }
      if (STUCK.equals(newest.capKind())) {
        return settleStuck(
            run, rebuilt.conductor(), newest, pending.subList(0, pending.size() - 1), attempt);
      }
      if (UNCOVERED.equals(newest.capKind())) {
        return settleUncovered(
            run, rebuilt.conductor(), newest, pending.subList(0, pending.size() - 1), attempt);
      }
      if (CONCERNS.equals(newest.capKind())) {
        return settleConcerns(
            run, rebuilt.conductor(), newest, pending.subList(0, pending.size() - 1), attempt);
      }
      if (PRODUCT_CHECK.equals(newest.capKind())) {
        return settleProduct(
            run, rebuilt.conductor(), newest, pending.subList(0, pending.size() - 1), attempt);
      }
      if (CHECK_FAILURES.equals(newest.capKind())) {
        return settleCheckFailures(
            run, rebuilt.conductor(), newest, pending.subList(0, pending.size() - 1), attempt);
      }
      if (TIME_CAP.equals(newest.capKind())) {
        return settleTime(
            run, rebuilt.conductor(), newest, pending.subList(0, pending.size() - 1), attempt);
      }
      if (newest.capKind() != null) {
        return settleCap(
            run, rebuilt.conductor(), newest, pending.subList(0, pending.size() - 1), attempt);
      }
      if (budgetExhausted(run)) {
        askAboutExhaustedBudget(run);
        return false;
      }
      if (!speakOrLeavePending(run, rebuilt.conductor(), answers(run, pending), null, attempt)) {
        return false;
      }
      delivered(pending);
      return true;
    } finally {
      speakingAnswers.remove(newest.id());
    }
  }

  /** One undelivered answer and the question it answered ({@code ""} if none is on record). */
  private record Pending(OrchestrationMessage answer, String question) {}

  /** Records the only failure that merits the bounded post-guard retry. */
  private static final class PendingAttempt {
    private String deferredConversation;
  }

  private static List<Pending> pendingAnswers(List<OrchestrationMessage> messages) {
    List<Pending> pending = new ArrayList<>();
    String question = "";
    for (OrchestrationMessage message : messages) {
      if (message.kind() == Kind.QUESTION) {
        question = message.text();
      } else if (message.deliveredAt() == null) {
        pending.add(new Pending(message, question));
      }
    }
    return pending;
  }

  /**
   * Plain answers, each in its own fences, and what the harness did with a pending {@link #INSTALL}
   * answer, in the order they were given. An install answer is pending beside a later one when it
   * was settled on a spent budget ({@link #settleInstall}): its outcome rides the raise's
   * continuation, and is settled here if it never was. Any other cap answer was acted on when it
   * came.
   */
  private String answers(OrchestrationRecord run, List<Pending> pending) {
    List<String> spoken = new ArrayList<>();
    for (Pending p : pending) {
      OrchestrationMessage answer = p.answer();
      if (answer.capKind() == null) {
        spoken.add(Utterances.answer(p.question(), answer.text(), answer.author()));
      } else if (INSTALL.equals(answer.capKind())) {
        spoken.add(installOutcome(run, answer));
      }
    }
    return String.join("\n\n", spoken);
  }

  /** {@code pending} delivered: each marked so, and an install answer's guard let go. */
  private void delivered(List<Pending> pending) {
    for (Pending p : pending) {
      store.messageDelivered(p.answer().id());
      forgetInstall(p.answer().id());
    }
  }

  /**
   * Decision 7's table: {@code yes}, a positive integer, or anything else. {@code earlier} are the
   * undelivered answers before this one, spoken in the same continuation on a raise.
   */
  private boolean settleCap(
      OrchestrationRecord run,
      AgentDefinition conductor,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    String kind = answer.capKind();
    String given = answer.text() == null ? "" : answer.text().strip();
    String decision = given.toLowerCase(Locale.ROOT);
    Integer raiseBy = null;
    if (decision.equals("yes")) {
      raiseBy = conductor.maxModelCalls();
    } else {
      try {
        int number = Integer.parseInt(decision);
        if (number > 0) {
          raiseBy = number;
        }
      } catch (NumberFormatException notANumber) {
        // Anything that is neither yes nor a positive number declines.
      }
    }
    if (raiseBy == null) {
      store.messageDelivered(answer.id());
      String what = CALL_BUDGET.equals(kind) ? "model-call budget" : "turn cap";
      stopAndTell(run.id(), OrchestrationState.CAPPED, what + " not raised: " + given);
      return true;
    }
    Integer newLimit = null;
    if (CALL_BUDGET.equals(kind)) {
      int spent = budgetOf(run).map(Budget::spent).orElse(0);
      try {
        newLimit = Math.addExact(spent, raiseBy);
      } catch (ArithmeticException tooLarge) {
        // A number past what a budget can hold asks for as much as there is.
        newLimit = Integer.MAX_VALUE;
      }
    } else if (budgetExhausted(run)) {
      // A fresh turn with no model call left to spend it on would only be refused: the
      // turn cap is settled, and the budget is the next question.
      store.messageDelivered(answer.id());
      askAboutExhaustedBudget(run);
      return true;
    }
    String utterance = Utterances.capRaised(kind, newLimit);
    String folded = answers(run, earlier);
    if (!folded.isEmpty()) {
      utterance = utterance + "\n\n" + folded;
    }
    if (!speakOrLeavePending(run, conductor, utterance, newLimit, attempt)) {
      return false;
    }
    delivered(earlier);
    store.messageDelivered(answer.id());
    return true;
  }

  /**
   * The person answered {@link #askAboutStuck}: any answer is "go on" — a cancel is {@code
   * /cancel}, which never reaches here. The run's nudges are forgotten, so it has as many tries
   * again as it had, and the conductor is spoken to with the nudge: it never saw the question, and
   * an answer to something it did not ask would read as an instruction from nowhere. The answer's
   * words are not passed on; the person's reason for going on is theirs.
   */
  private boolean settleStuck(
      OrchestrationRecord run,
      AgentDefinition conductor,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    store.progressed(run.id());
    if (budgetExhausted(run)) {
      // A turn with no model call left to spend would only be refused: going on is
      // settled, and the budget is the next question.
      store.messageDelivered(answer.id());
      askAboutExhaustedBudget(run);
      return true;
    }
    todos.forget(run.conductorConversation());
    String utterance = nudge(run);
    String folded = answers(run, earlier);
    if (!folded.isEmpty()) {
      utterance = utterance + "\n\n" + folded;
    }
    if (!speakOrLeavePending(run, conductor, utterance, null, attempt)) {
      return false;
    }
    delivered(earlier);
    store.messageDelivered(answer.id());
    return true;
  }

  /**
   * What the acceptance gate asks of this engine (V69, V77): a failure counted, and the person's
   * product check — what they accepted, and asking them.
   *
   * @return the gate's port onto this run's person
   */
  public AcceptanceGate.Person person() {
    return new AcceptanceGate.Person() {
      @Override
      public boolean checkFailed(String run, String what, String output) {
        return Orchestrations.this.checkFailed(run, what, output);
      }

      @Override
      public Optional<String> productAccepted(String run) {
        return store.productAccepted(run);
      }

      @Override
      public boolean askProduct(String run, String digest, String question) {
        return askAboutProduct(run, digest, question);
      }
    };
  }

  /**
   * Ask the person to check the product (V77, spec 2026-10-01 §1), once every {@code run:} line has
   * passed: called by {@link AcceptanceGate} inside the conductor's own {@code todo_write}, which
   * it then refuses and ends the turn on. It rides {@link #askAboutStuck}'s machinery with {@link
   * #PRODUCT_CHECK} as its kind, so {@link Delivery} sends it to the person and a model's answer is
   * refused.
   *
   * @param digest what the question shows, hashed ({@link AcceptanceGate#productDigest}); the
   *     answer {@code accept} accepts exactly that
   * @return whether the person was asked: false for a run with no account behind it, which no
   *     person could answer, or one no longer running
   */
  public boolean askAboutProduct(String orchestration, String digest, String question) {
    OrchestrationRecord run = store.find(orchestration).orElse(null);
    if (run == null || run.callerHandle() == null) {
      return false;
    }
    Optional<OrchestrationMessage> asked = store.askProduct(run.id(), digest, question);
    if (asked.isEmpty()) {
      return false;
    }
    announce(run.id());
    recorder.questionAsked(store.find(run.id()).orElse(run), question);
    delivery.questionAsked(store.find(run.id()).orElse(run), asked.get());
    return true;
  }

  /**
   * A row V65 wrote, answered after the verifier was retired (spec 2026-10-01): the person's words
   * are passed to the conductor as theirs, and the verifier's count let go. Nothing asks this
   * question any more.
   */
  private boolean settleUncovered(
      OrchestrationRecord run,
      AgentDefinition conductor,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    store.verifierSettled(run.id(), false);
    store.progressed(run.id());
    if (budgetExhausted(run)) {
      store.messageDelivered(answer.id());
      askAboutExhaustedBudget(run);
      return true;
    }
    String utterance = Utterances.answer("", answer.text(), answer.author());
    return speakSettled(run, conductor, utterance, answer, earlier, attempt);
  }

  /** The answers that accept a product check, lower-cased. */
  static final Set<String> ACCEPTS = Set.of("accept", "accepted", "y", "yes", "ok");

  /**
   * The person answered {@link #askAboutProduct}. {@code accept} accepts exactly what they were
   * shown, and the conductor is told to mark the stage done again, which then passes on their
   * acceptance; anything else is their notes, passed to the conductor whole as its direction, with
   * the way back — or, with no return left, the word to ask its caller, as a failed acceptance
   * does. The concerns they were shown are recorded as they answered.
   */
  private boolean settleProduct(
      OrchestrationRecord run,
      AgentDefinition conductor,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    String given = answer.text() == null ? "" : answer.text().strip();
    boolean accepted = ACCEPTS.contains(given.toLowerCase(Locale.ROOT).replaceAll("[.!]+$", ""));
    store.productSettled(run.id(), accepted);
    store.progressed(run.id());
    Checking checker = checking;
    if (checker != null) {
      for (Concerns.Concern concern : checker.forThePersonAtAcceptance(run.id())) {
        checker.concerns().personAnswered(run.id(), concern.id(), given, accepted);
        recorder.concern(
            run,
            Checking.PERSON,
            concern.id()
                + (accepted
                    ? " resolved: the person checked it and accepts the product"
                    : " not accepted by the person: " + given),
            null);
      }
    }
    if (budgetExhausted(run)) {
      store.messageDelivered(answer.id());
      askAboutExhaustedBudget(run);
      return true;
    }
    StageRules.Stage stage =
        run.stages().stream()
            .filter(each -> OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(each.acceptance()))
            .findFirst()
            .orElse(null);
    String id = stage == null ? "acceptance" : stage.id();
    String back =
        stage == null || stage.mayReturnTo().isEmpty() ? null : stage.mayReturnTo().get(0);
    String utterance =
        accepted
            ? Utterances.productAccepted(id)
            : Utterances.productRejected(
                id, given, back, back != null && run.returnsUsed() < run.maxReturns());
    return speakSettled(run, conductor, utterance, answer, earlier, attempt);
  }

  /**
   * The person answered the checker's concerns (V77): each concern recorded as they answered it —
   * their acceptance of the conductor's reason, or their direction — and the conductor told.
   */
  private boolean settleConcerns(
      OrchestrationRecord run,
      AgentDefinition conductor,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    Checking checker = checking;
    String utterance =
        checker == null
            ? Utterances.answer("", answer.text(), answer.author())
            : checker.personAnswered(run, answer.text());
    store.progressed(run.id());
    if (budgetExhausted(run)) {
      store.messageDelivered(answer.id());
      askAboutExhaustedBudget(run);
      return true;
    }
    return speakSettled(run, conductor, utterance, answer, earlier, attempt);
  }

  /** A settled answer spoken, with the earlier answers folded in, and marked delivered. */
  private boolean speakSettled(
      OrchestrationRecord run,
      AgentDefinition conductor,
      String utterance,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    String folded = answers(run, earlier);
    String spoken = folded.isEmpty() ? utterance : utterance + "\n\n" + folded;
    if (!speakOrLeavePending(run, conductor, spoken, null, attempt)) {
      return false;
    }
    delivered(earlier);
    store.messageDelivered(answer.id());
    return true;
  }

  /**
   * The conductor's {@code checker_answer} (spec 2026-10-01 §3): its answer recorded and shown to
   * the run's checker, which gives its verdict; and, once nothing is owed and some concern is left
   * unresolved, the person asked about them all in one question only they may answer.
   */
  @Override
  public CheckerAnswered checkerAnswer(
      String orchestration, String concern, String reason, Home home) {
    Checking checker = checking;
    OrchestrationRecord run =
        store.find(orchestration).filter(found -> !found.state().terminal()).orElse(null);
    String name = run == null ? null : store.checker(run.id()).orElse(null);
    if (checker == null || run == null || name == null) {
      return new CheckerAnswered(
          "Nothing was recorded: this run has no acceptance" + " checker.", false);
    }
    String session =
        run.callerSession() != null && sessionLive.test(run.callerSession())
            ? run.callerSession()
            : null;
    AcceptanceChecker.Brief brief =
        new AcceptanceChecker.Brief(
            run.id(), name, home, session, store.artifactsDir(run.id()).orElse(null), null, null);
    Checking.Answered answered = checker.answer(run, brief, concern, reason);
    store.progressed(run.id());
    if (!answered.personNeeded()) {
      return new CheckerAnswered(answered.text(), false);
    }
    List<Concerns.Concern> unresolved = checker.forThePerson(run.id());
    String question = Checking.concernsQuestion(run, unresolved);
    Optional<OrchestrationMessage> asked =
        run.callerHandle() == null ? Optional.empty() : store.askCap(run.id(), CONCERNS, question);
    if (asked.isEmpty()) {
      checker.nobodyToAsk(run, name);
      return new CheckerAnswered(
          answered.text()
              + " Nobody can be asked about the"
              + " concerns it could not resolve, so they stay open, and the checker checks"
              + " them against the finished project before acceptance.",
          false);
    }
    announce(run.id());
    recorder.questionAsked(store.find(run.id()).orElse(run), question);
    delivery.questionAsked(store.find(run.id()).orElse(run), asked.get());
    return new CheckerAnswered(
        answered.text()
            + " The person is asked about "
            + unresolved.stream().map(Concerns.Concern::id).collect(Collectors.joining(", "))
            + ".",
        true);
  }

  /**
   * The person answered {@link #INSTALL}: the installer writes or declines — it, and never the
   * conductor, which is spoken only what happened. Earlier undelivered answers ride along.
   *
   * <p><b>Settled once</b>: a speak refused by a busy conductor leaves the answer pending, and the
   * drain on that turn's free comes back here with it. The installer is called only the first time
   * ({@link #installsSettled}); a retry speaks the outcome it remembered.
   *
   * <p><b>A spent budget keeps the outcome, not drops it.</b> The install is settled and recorded,
   * and the budget is asked about; the answer stays undelivered, as a plain answer on a spent
   * budget does, and the raise's continuation speaks its outcome ({@link #answers}).
   */
  private boolean settleInstall(
      OrchestrationRecord run,
      AgentDefinition conductor,
      OrchestrationMessage answer,
      List<Pending> earlier,
      PendingAttempt attempt) {
    String utterance = installOutcome(run, answer);
    store.progressed(run.id());
    if (budgetExhausted(run)) {
      askAboutExhaustedBudget(run);
      return true;
    }
    String folded = answers(run, earlier);
    if (!folded.isEmpty()) {
      utterance = utterance + "\n\n" + folded;
    }
    if (!speakOrLeavePending(run, conductor, utterance, null, attempt)) {
      // A busy conductor leaves it pending for the retry; anything else ended the run.
      if (attempt.deferredConversation == null) {
        forgetInstall(answer.id());
      }
      return false;
    }
    delivered(earlier);
    store.messageDelivered(answer.id());
    forgetInstall(answer.id());
    return true;
  }

  /**
   * What the harness did with an install answer: settled by the installer the first time only
   * ({@link #installsSettled}) and recorded then, so it is on the record whether or not the
   * conductor is ever spoken to again; later, the outcome remembered.
   */
  private String installOutcome(OrchestrationRecord run, OrchestrationMessage answer) {
    if (installsSettled.putIfAbsent(answer.id(), run.id()) != null) {
      return installOutcomes.getOrDefault(
          answer.id(), "Nothing more was installed: this" + " answer was settled already.");
    }
    String outcome = install(run, answer);
    installOutcomes.put(answer.id(), outcome);
    recorder.installSettled(store.find(run.id()).orElse(run), outcome);
    return outcome;
  }

  /**
   * An install answer delivered, or its run ended: it can never be pending again, so neither its
   * guard nor its outcome is needed any more.
   */
  private void forgetInstall(String answer) {
    installOutcomes.remove(answer);
    installsSettled.remove(answer);
  }

  /** Whether an install answer's settle-once guard or outcome is still held — tests. */
  boolean holdsInstall(String answer) {
    return installsSettled.containsKey(answer) || installOutcomes.containsKey(answer);
  }

  /** The installer's sentence for {@code answer}; a failure is its sentence, never a throw. */
  private String install(OrchestrationRecord run, OrchestrationMessage answer) {
    // THE QUESTION THIS ANSWERS: the latest asked at or before it — messages are in order.
    // AND ONLY AN INSTALL QUESTION: the harness wrote it, and its structure holds the draft.
    OrchestrationMessage question =
        store.messages(run.id()).stream()
            .filter(m -> m.kind() == Kind.QUESTION && !m.createdAt().isAfter(answer.createdAt()))
            .reduce((first, second) -> second)
            .orElse(null);
    if (question == null
        || !"harness".equals(question.author())
        || !holdsDraft(question.structure())) {
      return "Nothing was installed: the install question could not be found.";
    }
    try {
      return installer.settle(run, question, answer);
    } catch (RuntimeException failed) {
      log.error("orchestration {}: settling its install failed", run.id(), failed);
      return "Nothing was installed: "
          + (failed.getMessage() == null ? failed.toString() : failed.getMessage());
    }
  }

  /** Whether a question's stored structure holds a draft's text, as an install question's does. */
  private static boolean holdsDraft(String structure) {
    if (structure == null) {
      return false;
    }
    try {
      return JSON.readTree(structure).path("text").isTextual();
    } catch (JsonProcessingException unreadable) {
      return false;
    }
  }

  // --- rebuilding the conductor -------------------------------------------------------------

  /**
   * The run's conductor, re-parsed from its pinned source — Decision 1 — or empty if that source no
   * longer parses.
   */
  Optional<AgentDefinition> conductorOf(OrchestrationRecord run) {
    return Optional.ofNullable(rebuild(run).conductor());
  }

  /** Exactly one of the two is non-null. */
  private record Rebuilt(AgentDefinition conductor, String failure) {}

  private Rebuilt rebuild(OrchestrationRecord run) {
    try {
      AgentDefinition parsed =
          OrchestrationRegistry.parsePinned(
                  run.definitionName(),
                  run.definitionOrigin(),
                  run.definitionSource(),
                  knownTools,
                  run.tier())
              .conductor();
      // Capped here, once, so every later turn's steps and a cap question's raise
      // (settleCap's raiseBy) are the person's numbers as the files read now.
      return new Rebuilt(
          capped(
              Objects.requireNonNull(
                  conductorChecks.apply(parsed), "conductorChecks returned no definition"),
              run.project()),
          null);
    } catch (RuntimeException unparseable) {
      String sentence =
          unparseable.getMessage() != null
              ? unparseable.getMessage()
              : "the orchestration definition '"
                  + run.definitionName()
                  + "' pinned on this"
                  + " run could not be read: "
                  + unparseable;
      log.warn(
          "orchestration {}: its pinned definition no longer parses or is refused: {}",
          run.id(),
          sentence);
      return new Rebuilt(null, sentence);
    }
  }

  // --- helpers ------------------------------------------------------------------------------

  /**
   * Speak, and on a refusal or any other exception fail the run with it and tell the caller: a run
   * left running with no turn is one nothing would ever speak to again.
   */
  private boolean speakOrFail(
      OrchestrationRecord run, AgentDefinition conductor, String utterance, Integer maxModelCalls) {
    try {
      return speak(run, conductor, utterance, maxModelCalls);
    } catch (Turn.Refused refused) {
      stopAndTell(run.id(), OrchestrationState.FAILED, refused.getMessage());
      return false;
    } catch (RuntimeException failed) {
      failOnException(run, failed);
      return false;
    }
  }

  /**
   * Speak, and on a refusal while the conductor is still speaking leave whatever was being spoken
   * for the drain its turn's free runs. A refusal from a conductor that is not speaking — an
   * archived conversation, a spent budget — is one no drain gets past, and nothing would ever free
   * that conversation to try again, so it fails the run.
   */
  private boolean speakOrLeavePending(
      OrchestrationRecord run,
      AgentDefinition conductor,
      String utterance,
      Integer maxModelCalls,
      PendingAttempt attempt) {
    try {
      return speak(run, conductor, utterance, maxModelCalls);
    } catch (Turn.Refused refused) {
      if (!voice.isSpeaking(run.conductorConversation())) {
        stopAndTell(run.id(), OrchestrationState.FAILED, refused.getMessage());
        return false;
      }
      log.info(
          "orchestration {}: its conductor is still speaking, so the answer stays"
              + " pending for that turn's free: {}",
          run.id(),
          refused.getMessage());
      attempt.deferredConversation = run.conductorConversation();
      return false;
    } catch (RuntimeException failed) {
      failOnException(run, failed);
      return false;
    }
  }

  private void failOnException(OrchestrationRecord run, RuntimeException failed) {
    log.error("orchestration {}: speaking to its conductor failed", run.id(), failed);
    stopAndTell(
        run.id(),
        OrchestrationState.FAILED,
        failed.getMessage() != null ? failed.getMessage() : failed.toString());
  }

  private boolean callerCanBeAsked(OrchestrationRecord run) {
    if (run.callerHandle() != null) {
      return true;
    }
    if (run.callerConversation() != null
        && conversations
            .find(run.callerConversation())
            .map(conversation -> conversation.origin() == Origin.TURN)
            .orElse(false)) {
      return true;
    }
    // A live conductor parent is a third place a question can land: Delivery speaks it into
    // that conductor's own conversation, and the conductor may answer it or pass it further up
    // with orchestration_ask — Decision 5. A conductor's own caller conversation has
    // ORCHESTRATION origin, so without this a nested run would be capped rather than asked.
    return run.parent() != null
        && store.find(run.parent()).filter(parent -> !parent.state().terminal()).isPresent();
  }

  private boolean budgetExhausted(OrchestrationRecord run) {
    return budgetOf(run).map(Budget::exhausted).orElse(false);
  }

  /** {@link #askAboutCap} for a conductor whose last turn spent its last allowed call. */
  private void askAboutExhaustedBudget(OrchestrationRecord run) {
    String spent =
        budgetOf(run).filter(Budget::capped).map(budget -> " all " + budget.limit()).orElse("");
    askAboutCap(
        run,
        Outcome.Ending.CALL_BUDGET,
        Outcome.Ending.CALL_BUDGET + ": the conductor has spent" + spent + " of its model calls");
  }

  /**
   * Speak, unless the run ended since it was read: a cancel racing a nudge, an answer or a cap's
   * continuation has already told the caller, and a turn spoken now would only spend calls on a run
   * nobody is waiting on. Nothing is left pending for a run that has ended.
   *
   * @return whether a turn was started
   */
  private boolean speak(
      OrchestrationRecord run, AgentDefinition conductor, String utterance, Integer maxModelCalls) {
    String id = run.id();
    if (store.find(id).filter(live -> live.endedAt() == null).isEmpty()) {
      log.info(
          "orchestration {} ended before its conductor could be spoken to, so it is" + " not", id);
      return false;
    }
    // A budget /cap left for this idle run goes with its next turn — unless this turn
    // carries its own total, a cap question's raise, which was decided after the /cap and
    // must not be lowered by it on the turn after (spec 2026-09-29 §2).
    Integer pending = pendingBudgets.remove(id);
    Integer total = maxModelCalls;
    if (total == null && pending != null) {
      // At least one call past what is spent by now. Turn refuses a turn into a spent
      // budget, and a refused speak fails the run — over a cap, where a spent budget only
      // asks. With one call left the turn makes it and ends CALL_BUDGET, which asks as any
      // spent budget does: a /cap below the spending costs one call, never the run. A turn
      // that started between applyCaps' look and its put may also have spent past it.
      //
      // …but only for a run that still HAD a call (Task 12's review). One whose budget was
      // already spent before the /cap has asked, or will, as any spent budget does; the
      // floor would have handed it a call past both its own ceiling and the person's, which
      // lowered it. A /cap at or below the spending then changes nothing for it.
      int spent = budgetOf(run).map(Budget::spent).orElse(0);
      total = pending > spent ? pending : budgetExhausted(run) ? null : spent + 1;
    }
    try {
      voice.speak(
          run.conductorConversation(),
          conductor,
          utterance,
          liveSession(run.callerSession()),
          total,
          outcome -> ended(id, outcome));
    } catch (RuntimeException refused) {
      // No turn took it, so the next one still should.
      if (pending != null && maxModelCalls == null) {
        pendingBudgets.putIfAbsent(id, pending);
      }
      throw refused;
    }
    return true;
  }

  /**
   * Stop the run and tell the caller. A stop that lost still tells the caller of an ending nobody
   * has delivered yet — {@code finish} commits inside the tool, and the turn can then end {@code
   * CANCELLED} or {@code UNAVAILABLE} rather than {@code ANSWERED} — and {@link Delivery} re-reads
   * the delivered mark, so an ending both calls deliver is told once.
   *
   * @return whether this call is the one that stopped it
   */
  private boolean stopAndTell(String orchestration, OrchestrationState terminal, String failure) {
    return stopAndTell(orchestration, terminal, failure, false);
  }

  /**
   * {@link #stopAndTell}, with {@code unlessRunning} choosing {@link
   * OrchestrationStore#stopUnlessRunning} — a running row lost to it has not ended, so the lost
   * branch below tells nobody anything.
   */
  private boolean stopAndTell(
      String orchestration, OrchestrationState terminal, String failure, boolean unlessRunning) {
    boolean stopped =
        unlessRunning
            ? store.stopUnlessRunning(orchestration, terminal, failure)
            : store.stop(orchestration, terminal, failure);
    if (!stopped) {
      store
          .find(orchestration)
          .filter(run -> run.endedAt() != null && run.resultDeliveredAt() == null)
          .ifPresent(delivery::runEnded);
      return false;
    }
    announce(orchestration);
    recordEnded(orchestration);
    // Before the caller is told, so a descendant's own CANCELLED ending finds its row already
    // stopped and routes to nothing — Decision 6.
    cancelDescendants(orchestration, new HashSet<>());
    store.find(orchestration).ifPresent(delivery::runEnded);
    return true;
  }

  /**
   * Stop every live descendant of a run that has just ended, deepest first: a child whose parent is
   * gone has nobody to report to, and its own conductor would go on spending model calls on work
   * nothing is waiting for.
   *
   * <p>Each child's own {@link #stopAndTell} walks its children in turn, so this method only has to
   * recurse to get the order right — the deepest row is stopped, and its ending delivered, before
   * the one above it.
   *
   * <p><b>Two different things bound this walk, and both are needed.</b> Across the nested {@code
   * stopAndTell}s it is {@code liveChildren}'s own {@code ended_at IS NULL} filter and a winning
   * {@code store.stop} being monotone: every row reached is stopped exactly once and never appears
   * in a later {@code liveChildren} again, and a nested {@code stopAndTell} only recurses when its
   * own stop won. Within one walk it is {@code seen}, because the recursion descends
   * <em>before</em> it stops anything — a live cycle of two or more rows would still be live all
   * the way down, and would not terminate without it. {@code seen} starts fresh inside every {@code
   * stopAndTell}, which is why it cannot catch the self-parent case; {@code
   * orchestrations_parent_is_not_itself} refuses that row outright, and {@code
   * OrchestrationRegistry}'s load-time grant walk is what should have made the longer cycles
   * unreachable, so a repeat here is logged as the bug it is.
   *
   * <p><b>A stopped row is not a stopped conductor</b>, so each descendant's live job is cancelled
   * too — spec §6's "then {@code JobStore} cancels live jobs". Without it a cancelled grandchild's
   * turn goes on spending model calls until it ends by itself, and a cascade that started anywhere
   * but {@code OrchestrationCancel} — a failure, a cap, a finish — cancelled no job at all. The row
   * is always stopped first, so the job's own {@code CANCELLED} ending finds it already ended and
   * delivers nothing twice.
   */
  private void cancelDescendants(String id, Set<String> seen) {
    for (OrchestrationRecord child : store.liveChildren(id)) {
      if (!seen.add(child.id())) {
        log.warn(
            "orchestration {}: its child {} is already on this cancellation walk, so"
                + " the rows form a cycle a grant cycle should have refused at load",
            id,
            child.id());
        continue;
      }
      cancelDescendants(child.id(), seen);
      cancelWithParent(child, id);
    }
  }

  /**
   * Stop one row as cancelled with the parent it hung from, and cancel whatever job is still
   * running in its conductor's conversation — {@code OrchestrationCancel}'s own two steps, in its
   * own order, for a row the engine is stopping rather than a caller. Only a stop that won cancels
   * a job, for that method's reason: a child that had already ended keeps the turn still winding
   * down, whose ending delivers what it came to say.
   */
  private void cancelWithParent(OrchestrationRecord child, String parentId) {
    if (stopAndTell(
        child.id(), OrchestrationState.CANCELLED, "cancelled with its parent " + parentId)) {
      cancelJobsIn.accept(child.conductorConversation());
    }
  }

  /**
   * Tells {@link #changed} of a run after a committed change to its state, from a fresh read of the
   * row — nothing is emitted if the row cannot be read. Never throws: on {@link Delivery}'s own
   * precedent, a listener that fails costs the run nothing.
   *
   * <p>Every change that leaves {@code ASKING} passes here — an answer, the person's or a model's,
   * and every stop — so it is also where the person's inbox notices about the run's questions are
   * settled ({@link DeliveryPort#questionsSettled}, V68): every question but the one it is asking
   * now.
   */
  private void announce(String orchestration) {
    store
        .find(orchestration)
        .ifPresent(
            run -> {
              try {
                changed.accept(run);
              } catch (RuntimeException failed) {
                log.warn(
                    "orchestration {}: telling its listener the run changed to {} failed",
                    orchestration,
                    run.state().wire(),
                    failed);
              }
              try {
                delivery.questionsSettled(run);
              } catch (RuntimeException failed) {
                log.warn(
                    "orchestration {}: settling its questions' inbox notices failed",
                    orchestration,
                    failed);
              }
            });
  }

  /**
   * A run that has just ended, told to the record from a fresh read of its row, and its conductor's
   * log closed: finish, stopAndTell and cancelOwnChild all end here, so this is the one seam for
   * log.close on an orchestration (spec 2026-09-28-hooks-reach-the-log §3).
   */
  private void recordEnded(String orchestration) {
    store
        .find(orchestration)
        .ifPresent(
            run -> {
              recorder.runEnded(run);
              logStages.closed(run.conductorConversation(), run.state().wire());
            });
    // Every ending passes here, so what is held per run is let go here: a /cap budget left
    // for a run that ended idle (Task 12's review) and a cap nudge's memory.
    pendingBudgets.remove(orchestration);
    capNudged.remove(orchestration);
    // AND ITS INSTALL GUARD: an install answer still pending on a busy conductor when the
    // run ends would otherwise hold its settle-once guard for the life of the process.
    installsSettled.entrySet().stream()
        .filter(settled -> settled.getValue().equals(orchestration))
        .map(Map.Entry::getKey)
        .collect(Collectors.toList())
        .forEach(this::forgetInstall);
    withdrawAsked(orchestration);
  }

  /**
   * Withdraw every approval an ended run still has asked of the person — its check's and its
   * acceptance commands'. Measured 2026-09-29, {@code orc_318D26A46144920B}: a run cancelled while
   * its acceptance gate was in the verifier call had eleven commands asked of the person after it
   * ended, and nothing ever withdrew them. An answered approval is left as it is; a phase's ending,
   * reached through the cascade, withdraws its own. Never throws: an ending is not undone for a
   * question left on a list.
   */
  private void withdrawAsked(String orchestration) {
    try {
      List<String> named = new ArrayList<>();
      OrchestrationChecks checked = checks;
      if (checked != null) {
        checked
            .find(orchestration)
            .map(OrchestrationChecks.Check::approval)
            .filter(Objects::nonNull)
            .ifPresent(named::add);
      }
      OrchestrationAcceptance accepting = acceptance;
      if (accepting != null) {
        accepting.find(orchestration).stream()
            .map(OrchestrationAcceptance.Registered::approval)
            .filter(Objects::nonNull)
            .forEach(named::add);
      }
      Function<String, Optional<RunApproval>> states = checkApprovals;
      // A set's one approval is named by each of its commands: withdrawn once (V67).
      for (String id : named.stream().distinct().toList()) {
        boolean asked =
            states == null
                || states
                    .apply(id)
                    .map(RunApproval::state)
                    .filter(RunApproval.ASKED::equals)
                    .isPresent();
        if (asked) {
          withdrawal.accept(id);
        }
      }
    } catch (RuntimeException failed) {
      log.warn(
          "orchestration {}: its asked approvals could not be withdrawn as it ended",
          orchestration,
          failed);
    }
  }

  private String lostRace(String orchestration, String verb) {
    return store
        .find(orchestration)
        .map(run -> "this orchestration is " + run.state().wire() + ", so it cannot " + verb)
        .orElse("no orchestration has the id " + orchestration + ", so it cannot " + verb);
  }

  private static String failure(Outcome outcome) {
    return outcome.ending() + ": " + outcome.text();
  }

  private String liveSession(String session) {
    return session != null && sessionLive.test(session) ? session : null;
  }

  private Optional<Budget> budgetOf(OrchestrationRecord run) {
    return conversations.find(run.conductorConversation()).map(ConversationRecord::budget);
  }

  /**
   * The nudge for a conductor whose turn ended in plain text: its pending stages, then its list —
   * {@link Utterances#nudge}.
   */
  private String nudge(OrchestrationRecord run) {
    String nudge = Utterances.nudge(pendingStages(run), renderStages(run));
    // The checker's open questions are owed before anything else moves (spec 2026-10-01 §3).
    Checking checker = checking;
    List<Concerns.Concern> waiting = checker == null ? List.of() : checker.waiting(run.id());
    return waiting.isEmpty() ? nudge : nudge + "\n\n" + Checking.whyQuestions(waiting);
  }

  /**
   * The run's stages not yet done, in the definition's order, each with its {@code done-when}. The
   * row pins the stage ids but not their guidance, so that is read back from the pinned source, as
   * {@link #parentArtifactsDir} reads its template; a source that no longer parses names the stages
   * without it rather than costing the nudge.
   */
  private List<Utterances.PendingStage> pendingStages(OrchestrationRecord run) {
    Set<String> done =
        todos.list(run.conductorConversation()).stream()
            .filter(item -> item.stageId() != null && item.status() == TodoStatus.DONE)
            .map(TodoItem::stageId)
            .collect(Collectors.toSet());
    Map<String, String> doneWhen = new HashMap<>();
    try {
      OrchestrationRegistry.parsePinned(
              run.definitionName(),
              run.definitionOrigin(),
              run.definitionSource(),
              knownTools,
              run.tier())
          .stages()
          .forEach(
              stage -> {
                if (stage.doneWhen() != null) {
                  doneWhen.put(stage.id(), stage.doneWhen());
                }
              });
    } catch (RuntimeException unparseable) {
      log.debug(
          "orchestration {}: its pinned definition no longer parses, so its nudge names"
              + " its stages without their done-when",
          run.id());
    }
    return run.stages().stream()
        .map(StageRules.Stage::id)
        .filter(id -> !done.contains(id))
        .map(id -> new Utterances.PendingStage(id, doneWhen.get(id)))
        .toList();
  }

  private String renderStages(OrchestrationRecord run) {
    Map<String, TodoStatus> statuses = new HashMap<>();
    for (TodoItem item : todos.list(run.conductorConversation())) {
      if (item.stageId() != null) {
        statuses.put(item.stageId(), item.status());
      }
    }
    return run.stages().stream()
        .map(
            stage ->
                "- "
                    + stage.id()
                    + ": "
                    + Optional.ofNullable(statuses.get(stage.id()))
                        .map(TodoStatus::wire)
                        .orElse("missing"))
        .collect(Collectors.joining("\n"));
  }

  /**
   * The todo item of the phase a parent is starting: the child todo of a stage item its conductor
   * marked in progress last — {@code implement_specification} marks it just before it starts the
   * phase, and a conductor that never closed the phase before it can have two (measured
   * 2026-09-27). Empty when there is none, or two were marked at the same instant.
   */
  private Optional<TodoItem> phaseItemOf(String parentId) {
    String conversation =
        store.find(parentId).map(OrchestrationRecord::conductorConversation).orElse(null);
    if (conversation == null) {
      return Optional.empty();
    }
    List<TodoItem> items = todos.list(conversation);
    java.util.Set<String> stageItems =
        items.stream()
            .filter(item -> item.stageId() != null)
            .map(TodoItem::id)
            .collect(java.util.stream.Collectors.toSet());
    List<TodoItem> going =
        items.stream()
            .filter(
                item ->
                    item.stageId() == null
                        && stageItems.contains(item.parent())
                        && item.status() == TodoStatus.IN_PROGRESS)
            .toList();
    TodoItem phase =
        going.stream().max(java.util.Comparator.comparing(TodoItem::updatedAt)).orElse(null);
    if (phase == null
        || going.stream()
            .filter(item -> item != phase)
            .anyMatch(item -> item.updatedAt().equals(phase.updatedAt()))) {
      return Optional.empty();
    }
    return Optional.of(phase);
  }

  /**
   * The directory of the phase a parent is starting, under its {@code phases/}: {@link
   * #phaseItemOf}'s todo item, numbered among its siblings and slugged. {@code null} when there is
   * none, or its name has no letter or digit, and the child is left to fill the segment in as
   * before.
   */
  private String phaseOf(String parentId) {
    return phaseItemOf(parentId)
        .map(
            phase -> {
              String slug = Utterances.slug(phase.text());
              if (slug.isEmpty()) {
                return null;
              }
              String conversation =
                  store.find(parentId).map(OrchestrationRecord::conductorConversation).orElse(null);
              List<TodoItem> items = conversation == null ? List.of() : todos.list(conversation);
              List<TodoItem> siblings =
                  items.stream()
                      .filter(item -> phase.parent().equals(item.parent()))
                      .sorted(java.util.Comparator.comparingInt(TodoItem::position))
                      .toList();
              return String.format("%02d-%s", siblings.indexOf(phase) + 1, slug);
            })
        .orElse(null);
  }

  /**
   * The directory the parent's own first message named, rebuilt from its row: its pinned
   * definition's template, its name, its id, and the day it started. {@code null} when the parent
   * is gone, names no directory, or its pinned definition no longer parses — the child is then told
   * its own directory, as a run with no parent is.
   */
  private String parentArtifactsDir(String parentId) {
    return store
        .find(parentId)
        .map(
            parent -> {
              try {
                OrchestrationDefinition pinned =
                    OrchestrationRegistry.parsePinned(
                        parent.definitionName(),
                        parent.definitionOrigin(),
                        parent.definitionSource(),
                        knownTools,
                        parent.tier());
                return artifactsDir(
                    pinned.artifacts(), parent.definitionName(), parent.id(), parent.createdAt());
              } catch (RuntimeException unparseable) {
                return null;
              }
            })
        .orElse(null);
  }

  private static String artifactsDir(String template, String name, String id, Instant on) {
    if (template == null) {
      return null;
    }
    return template
        .replace("{date}", LocalDate.ofInstant(on, ZoneOffset.UTC).toString())
        .replace("{name}", name)
        .replace("{id}", id);
  }
}
