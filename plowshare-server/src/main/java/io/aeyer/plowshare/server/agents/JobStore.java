package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.archive.JobLog;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The jobs this process is running, and the threads they run on.
 *
 * <h2>One virtual thread per job</h2>
 *
 * <p>Blocking on the dispatcher is what virtual threads are for, and it lets the turn loop read top
 * to bottom rather than as a state machine — which is also how it debugs. <b>But the reason it is
 * not a style preference is that it removes a deadlock.</b> If a job were a platform thread from a
 * bounded orchestration pool, then once agents can call agents a parent blocking on a child
 * deadlocks the moment every thread in the pool is a parent waiting for a child that cannot get
 * one. Virtual threads dissolve it by making threads not the scarce resource: <b>the scarce
 * resource is the lane</b>, which {@code LlmPool} already bounds, and which a job blocked on a
 * child does not hold.
 *
 * <p>Measured on Java 21.0.8 rather than assumed, because this claim is the whole design: a task
 * submitted to {@code Executors.newVirtualThreadPerTaskExecutor()} sees {@code
 * Thread.currentThread().isVirtual()} true, and 64 such tasks blocked simultaneously on 64 distinct
 * threads with 18 processors available — so the concurrency is not the carrier count in disguise.
 * {@code a_job_runs_on_a_virtual_thread} and {@code
 * nothing_bounds_how_many_jobs_are_blocked_at_once} are what would notice a future refactor putting
 * these back on a pool.
 *
 * <h2>The handle is in memory; the record is a row</h2>
 *
 * <p>A <em>handle</em> does not survive a restart and still does not: a run in progress does not
 * survive one either, so nothing here can honestly say {@code RUNNING} about a thread that no
 * longer exists. This class is the authority on what is running <b>now</b>, and {@link #get}
 * refuses an id it has never registered rather than reaching for a database, which is the property
 * {@code DurableJobsTest} exists to keep.
 *
 * <p><b>What changed is the identifier and the fact that a run happened.</b> This class used to
 * mint {@code "job_" + String.format("%06d", ids.incrementAndGet())} off an in-process {@code
 * AtomicLong}, so ids restarted at {@code job_000001} on every boot and two unrelated runs on two
 * different days collided — {@code implementation rationale} §2, observed when a probe script broke
 * on it. Ids are now minted the way this repository mints every id it shows, and every job is
 * written to {@code jobs} at both ends. {@code V24__jobs.sql} carries the argument; the shape of it
 * is that a job id means one run for ever, and something durable can therefore reference it.
 *
 * <p>The old paragraph's other half is untouched and still right: what a run <em>produced</em> is
 * durable in its own right — memories in the archive, proposals in the queue, entries and summaries
 * in Postgres — and a job is a handle on a run rather than a container for its output. The row
 * holds no output either. It holds that the run existed.
 *
 * <h2>The ends of a job are published here, the middle is not, and so is the fact that there is a
 * middle</h2>
 *
 * <p>This class mints the id and files the outcome, so {@code JobEvent.STARTED} and {@code
 * JobEvent.ENDED} are its to send and {@link JobRuntime} sends neither. That is not tidiness: the
 * last-resort {@code catch} below finishes a job whose run threw before it could return an {@link
 * Outcome}, and an ending published from inside the loop would be missing from exactly the run a
 * listener most needs telling about. The two events bracket {@code Job.finish}, so a listener told
 * a job has ended and asking {@code GET /v1/jobs/&#123;id&#125;} finds one that is done.
 *
 * <p><b>{@code JobEvent.ALIVE} is the third, and the sentence above used to stop one kind
 * short.</b> A heartbeat is not an end and it is not the middle either: it says that a job exists
 * and has not finished, which is precisely what this class knows and what {@link JobRuntime} —
 * blocked inside a model call, which is where the silence was measured — is in no position to say.
 * So the beats are this class's too, on the same terms as the ends: {@link #beat} arms one when the
 * run's thread starts and the {@code finally} below disarms it before the thread lets go, and the
 * task consults {@link Job#state} as well, so a timer that somehow survived its job would publish
 * nothing. What the beat <em>says</em> about the run's progress is still not this class's to know:
 * the two counts are read off {@link JobWatch}, which has seen every one that was ever published.
 */
public final class JobStore implements AutoCloseable, EventRuns {

  private static final Logger log = LoggerFactory.getLogger(JobStore.class);

  /**
   * How often a running job says it is still there, unless an operator says otherwise.
   *
   * <h2>Twenty seconds, and the number is bounded from both ends</h2>
   *
   * <p><b>Under sixty, because sixty is where an idle connection gets cut.</b> That is the common
   * default for a proxy or a load balancer with no traffic on a socket, and the client design's
   * premise is that this server is remote — so the ceiling here is not a preference, it is the
   * thing that decides whether a twelve-minute turn survives the network between the two ends. A
   * third of it is chosen rather than a half so that <b>one dropped beat is still not a cut
   * connection</b>: the stream is droppable by design, and at twenty seconds a listener would have
   * to miss two consecutive beats before the socket had been quiet for the sixty that matters.
   *
   * <p><b>Not lower, because a beat is traffic and a run is long.</b> Twelve minutes at this
   * interval is thirty-six frames, which is nothing; the same turn at one second would be seven
   * hundred, every one of them identical to the last, on every listener attached. There is nothing
   * a person or a client does with a second-by-second beat that they cannot do with this one — the
   * question it answers is "is this connection alive", and the answer does not change faster than
   * the connection does.
   *
   * <p><b>It also fixes what "dead" costs a client.</b> A client's rule is some number of missed
   * intervals, so this number times that one is how long a person stares at a stalled terminal
   * before being told; three of these is a minute, which is long enough not to accuse a slow
   * network and short enough that nobody has gone to make tea.
   *
   * <p>Zero or negative turns beats off entirely, which is a deployment whose clients are all local
   * and an operator who would rather have the silence.
   */
  public static final Duration HEARTBEAT = Duration.ofSeconds(20);

  private final JobRuntime runtime;
  private final JobEvents events;

  /**
   * How often each running job here publishes {@code JobEvent.ALIVE}.
   *
   * <p>A field and not a read of {@link #HEARTBEAT}, because the interval is an operator's — {@code
   * plowshare.jobs.heartbeat} — and because a test that had to wait twenty seconds to observe a
   * beat is a test nobody runs.
   */
  private final Duration heartbeat;

  /**
   * The one thread every job's heartbeat is scheduled on.
   *
   * <h2>A platform thread, and one of it</h2>
   *
   * <p>Not a virtual thread: {@link ScheduledThreadPoolExecutor} keeps its workers for the life of
   * the pool, which is the one thing virtual threads are not for, and this one is never blocked — a
   * beat is a bounded queue's {@code offer} and a return, on {@link JobEvents}'s first rule. One
   * thread therefore serves every job in the process, and it is a daemon so that a store nobody
   * closed does not hold the JVM open.
   *
   * <p><b>{@code setRemoveOnCancelPolicy} is not tidiness either.</b> Without it a cancelled
   * repeating task stays in the queue until its next due time, so a server that had run ten
   * thousand jobs would be holding ten thousand dead tasks — and {@link #beating} could not be the
   * instrument it is, since a cancelled beat and a live one would be indistinguishable by counting.
   */
  private final ScheduledThreadPoolExecutor beats = beatingThread();

  /**
   * Where a submission's conversation is opened, or null for a store that opens none.
   *
   * <p><b>Nullable, and the null is a fixture rather than a deployment.</b> {@code AgentsConfig}
   * always supplies it; what the null is for is the three dozen tests that build a store over a
   * stub runtime to assert on job lifecycle and have no database at all. Such a store's submissions
   * run on {@code Transcript.NONE}, which is what every submission did before V17.
   */
  private final Compaction logs;

  /**
   * Where a job's durable record goes, or null for a store that keeps none.
   *
   * <p><b>Nullable on {@link #logs}'s terms exactly</b>, and for the same population: {@code
   * AgentsConfig} always supplies it, and what the null is for is the three dozen fixtures that
   * build a store over a stub runtime with no database at all. Such a store still mints the same
   * ids — see {@link #start} — because an id whose shape depended on whether a database was wired
   * would be two schemes in one server.
   */
  private final JobLog rows;

  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, Job> byId = new ConcurrentHashMap<>();

  /**
   * The order this process registered its jobs in, and not an identifier.
   *
   * <p>See {@link Job#sequence}. An {@code AtomicLong} is exactly what {@code implementation
   * rationale} §2 removed from the line above, and the distinction is the point: that one was the
   * <b>id</b>, so it restarted at {@code job_000001} every boot and two days' runs collided. This
   * one restarts too, and that is harmless, because it never leaves this object — no wire, no row,
   * no message — and orders a list that does not survive a restart either.
   */
  private final AtomicLong order = new AtomicLong();

  private volatile RunEnds runEnds = RunEnds.NONE;

  /** Who hears that a run ended. One listener; the production wiring sets it once at boot. */
  public void onRunEnded(RunEnds ends) {
    this.runEnds = Objects.requireNonNull(ends, "ends");
  }

  /**
   * A store nobody is watching. Every event is dropped, which is what {@link JobEvents#NONE} means
   * and what a context with no event channel wired gets.
   */
  public JobStore(JobRuntime runtime) {
    this(runtime, JobEvents.NONE);
  }

  /**
   * @param events where a job's lifecycle goes. The seam and not a socket: this package must not
   *     know what a listener is attached over, which is {@code files.SessionChannel}'s argument for
   *     the other role
   */
  public JobStore(JobRuntime runtime, JobEvents events) {
    this(runtime, events, null);
  }

  /**
   * The same, keeping a log of every run submitted on its own behalf.
   *
   * <p>The production wiring. The constructor above states <b>this store's submissions keep no
   * log</b>, which is true of a fixture over a stub runtime and false of every boot.
   *
   * @param logs where a submission's conversation is opened
   */
  public JobStore(JobRuntime runtime, JobEvents events, Compaction logs) {
    this(runtime, events, logs, null);
  }

  /**
   * The same, writing every job down as a row that outlives this process.
   *
   * @param rows where a job's durable record goes, or {@code null} for a store that keeps none
   */
  public JobStore(JobRuntime runtime, JobEvents events, Compaction logs, JobLog rows) {
    this(runtime, events, logs, rows, HEARTBEAT);
  }

  /**
   * The same, saying how often a run in flight should say that it is.
   *
   * <p>The overload above answers {@link #HEARTBEAT}, which is what every fixture in this
   * repository wants: twenty seconds is longer than any test's job lives, so a suite asserting on
   * an exact sequence of kinds sees the same sequence it always saw. A caller that names an
   * interval is either the production wiring, reading an operator's key, or a test that means to
   * observe a beat.
   *
   * @param heartbeat how often a running job publishes {@code JobEvent.ALIVE}. Zero or negative for
   *     a store whose jobs publish none — see {@link #HEARTBEAT} for what turning them off is for
   */
  public JobStore(
      JobRuntime runtime, JobEvents events, Compaction logs, JobLog rows, Duration heartbeat) {
    this.logs = logs;
    this.rows = rows;
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.events = Objects.requireNonNull(events, "events");
    this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
  }

  /**
   * Start a run and return its id at once.
   *
   * <p>The budget is built here from the definition's own {@code max-model-calls}, because this is
   * a job started on its own behalf and there is no parent to inherit from. Task 5's {@code
   * agent_run} calls {@link JobRuntime#run} directly with the <em>parent's</em> budget instead of
   * coming through here, which is what makes the budget a property of the tree; a child submitted
   * through this method would get a fresh allowance and the sharing would be lost.
   *
   * <p>A conversation is the other caller that must not get a fresh allowance, and it comes through
   * {@link #submit(AgentDefinition, String, Home, String, Budget, Transcript, Origin, Consumer)}
   * rather than through here for exactly that reason. This method is the shape for a run nobody is
   * going to speak to again.
   *
   * @param sessionId the session the submitter is attached to, or {@code null} for a submission
   *     that has none. <b>Null is an ordinary submission and not a degraded one</b>: the curator's
   *     pass, a scheduled tick and a plain HTTP caller holding no socket all arrive this way, and
   *     the design spec's rule is that such a run has a smaller set of filesystems rather than a
   *     broken one. Carried to {@link JobRuntime#run} and no further by this class, which never
   *     looks a session up
   * @return the job id, for {@link #get} and {@link #cancel}
   */
  public String submit(AgentDefinition definition, String userPrompt, Home home, String sessionId) {
    return submit(definition, userPrompt, home, sessionId, null);
  }

  /**
   * The same submission, under a turn cap somebody chose for this run.
   *
   * <p>The overload above states {@code this run is capped at what its definition asks for}, which
   * is what every caller wanted before a run could be given its own — and is still what a caller
   * that names no cap gets, by the same route: {@link TurnCap#chosen} answers the definition's
   * number when nothing narrower has one.
   *
   * <p>There is deliberately <b>no run-level override of the budget on this door</b>. A run
   * submitted here is one nobody is going to speak to again, and its allowance is the definition's;
   * a caller that wants a different one has a conversation, which is where an allowance is a
   * decision somebody takes and a row records. Adding one here would be a second place to answer a
   * question {@code POST /v1/conversations} already answers, with nothing durable behind it.
   *
   * @param cap the ceiling for this run, or {@code null} for the definition's own. Held on the
   *     {@link Job} so an operator can move it while the run is going
   */
  public String submit(
      AgentDefinition definition, String userPrompt, Home home, String sessionId, TurnCap cap) {
    return submit(definition, userPrompt, home, sessionId, cap, List.of());
  }

  /**
   * The same submission, showing the agent pictures the submitter named.
   *
   * <p>An overload rather than a parameter on the one above, for {@link TurnCap}'s reason: what the
   * shorter form states is <b>this run is shown nothing</b>, which is true of every caller it has
   * and of every run this server made before images existed.
   *
   * <p><b>Resolved already, and that is where the containment is.</b> The images arrive as bytes
   * because {@code Pictures} read them out of the store on the request thread, with the run's home
   * in hand — so a UID naming nothing is a refusal the submitter reads rather than a job they poll
   * to discover, and nothing below this line can name an image at all. {@code JobRuntime.run}
   * carries the rest of that argument, including what a resumed run does not get.
   *
   * @param images what the model is shown on the opening turn. Never null
   */
  public String submit(
      AgentDefinition definition,
      String userPrompt,
      Home home,
      String sessionId,
      TurnCap cap,
      List<Content.Image> images) {
    return submit(definition, userPrompt, home, sessionId, cap, images, false, null);
  }

  /**
   * The same submission, stating whether it is an utterance arriving fresh — spec §6, and {@link
   * JobRuntime#run}'s own twelfth parameter. {@link Runs#start} is the one caller that needs {@code
   * true} here: a plain submission with no conversation is a person's own words exactly as an
   * utterance into one is, and {@link TriggerNoticing} is asked about it on the identical terms.
   *
   * @param incoming whether this is one of the doors spec §6 names. The overload above states
   *     {@code false}, which was every caller's meaning before this parameter existed
   * @param owner the account that asked, which owns the log a hook's notice reaches (spec
   *     2026-09-28-hooks-reach-the-log decision 8), or {@code null} for nobody known. It names the
   *     log's owner only: the run still acts for nobody, as it did before
   */
  public String submit(
      AgentDefinition definition,
      String userPrompt,
      Home home,
      String sessionId,
      TurnCap cap,
      List<Content.Image> images,
      boolean incoming,
      String owner) {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(images, "images");
    Objects.requireNonNull(home, "home");
    Budget budget = Budget.of(definition.maxModelCalls());
    // A conversation of its own, and this door is the one origin that OWNS
    // the allowance on its row: there is no parent to inherit from and no
    // pass sharing one, so the budget built two lines up is this
    // conversation's and nothing else spends it. A delegated child and a
    // curator's ruling are the opposite case and their rows hold no budget
    // at all -- see Origin.ownsItsAllowance.
    //
    // IT IS 'submission' AND NOT 'mcp'. By the time a request reaches this
    // method there is nothing on it that says whether the caller was the MCP
    // client, a script or curl: client.tools.AgentTools.run makes the same
    // POST /v1/agents/{name}/runs with no conversation and no session that
    // any of them make. V17 spends a paragraph on why a value that would
    // have to be guessed later is not what this column is for.
    Transcript log =
        logs == null
            ? Transcript.NONE
            : logs.logFor(
                Origin.SUBMISSION,
                home,
                definition,
                null,
                budget,
                // A person's own words arriving fresh, recorded with no handle (spec
                // 2026-09-28-the-log-is-the-source §2); the handle, when the surface
                // knows it, owns the log instead.
                Speaker.person(null),
                owner,
                null,
                // The session this submission names, whose .plowshare/hooks/ the log is
                // snapshotted with (spec 2026-09-30-local-hooks-are-served decision 4).
                sessionId);
    return submit(
        definition,
        userPrompt,
        home,
        sessionId,
        budget,
        log,
        // Origin.SUBMISSION, the same constant handed to logFor three
        // lines up: this is the conversation logFor just opened, and
        // Job#conversationOrigin is this run's own copy of what it was
        // opened as. It reaches JobView.OutcomeView.resumable, which is
        // what stops a submission's stop from being offered a continue
        // button that POST /v1/conversations/{id}/resume then refuses —
        // Turn.requireResumable accepts only Origin.TURN, and a
        // submission is "a run nobody is going to speak to again" by
        // this very method's own words two paragraphs up.
        Origin.SUBMISSION,
        // One call, and it closes the log AND writes back what this run
        // spent. The second half is not visible here on purpose: the
        // transcript was opened with the budget two lines up, so it is
        // the thing that knows this conversation's row owns the
        // allowance, and Compaction.TurnTranscript.writeBackWhatWasSpent
        // is where that is answered for every door that opens one.
        //
        // This comment used to say "there is no budget to write back --
        // nothing else will read it", and both halves were wrong.
        // Something does read it: the row is the only durable record of
        // what a submission cost, and implementation rationale §11 records what it
        // said instead -- nought, for ever, however long the run was.
        outcome -> {
          log.closed(userPrompt, outcome);
          runtime.deliverApprovalQuestions(log.conversationId());
        },
        cap,
        images,
        incoming);
  }

  /** An event-started run: its conversation, if one was opened, and its job. */
  public record EventRun(String id, String conversation) {}

  /**
   * A run a trigger started. The same door as {@link #submit(AgentDefinition, String, Home, String,
   * TurnCap, List)} with three differences: the origin is {@link Origin#EVENT}, there is never a
   * session (so only the local file provider, slice 3d's rule for scheduled work), and the trigger
   * may set the allowance, where a submission always takes the definition's.
   *
   * <p><b>The speaker is named, and no shorter door names it for a caller</b>: the task is the
   * harness's words on a trigger's behalf — {@code event <trigger>} — and a door that defaulted it
   * would be one more place a reader of the log could be told the wrong speaker.
   *
   * @param callerHandle the account that defined the trigger, or null. It owns the run's log (spec
   *     2026-09-28-hooks-reach-the-log decision 8)
   * @param speaker who speaks the task: {@code event <trigger>} for a trigger
   * @param ended called once, after the transcript is closed, with the conversation the run was
   *     logged in (null when no log is wired) and how it ended
   */
  public EventRun submitEvent(
      AgentDefinition definition,
      String utterance,
      Home home,
      Integer maxModelCalls,
      TurnCap cap,
      String callerHandle,
      Speaker speaker,
      BiConsumer<String, Outcome> ended) {
    return submitEvent(
        definition, utterance, home, null, maxModelCalls, cap, callerHandle, speaker, ended);
  }

  /** Event submission with a live, already authorized workspace definition session. */
  public EventRun submitEvent(
      AgentDefinition definition,
      String utterance,
      Home home,
      String session,
      Integer maxModelCalls,
      TurnCap cap,
      String callerHandle,
      Speaker speaker,
      BiConsumer<String, Outcome> ended) {
    return submitEvent(
        definition,
        utterance,
        home,
        session,
        maxModelCalls,
        cap,
        callerHandle,
        speaker,
        ended,
        null);
  }

  /** Relay causation is persisted with the job, before any executor or lifecycle effect. */
  public EventRun submitEvent(
      AgentDefinition definition,
      String utterance,
      Home home,
      String session,
      Integer maxModelCalls,
      TurnCap cap,
      String callerHandle,
      Speaker speaker,
      BiConsumer<String, Outcome> ended,
      RelayCausation causation) {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(ended, "ended");
    Budget budget = Budget.of(maxModelCalls == null ? definition.maxModelCalls() : maxModelCalls);
    Transcript log =
        logs == null
            ? Transcript.NONE
            : logs.logFor(Origin.EVENT, home, definition, session, budget, speaker, callerHandle);
    String conversation = log.conversationId();
    String id =
        submit(
            definition,
            utterance,
            home,
            session,
            budget,
            log,
            Origin.EVENT,
            outcome -> {
              // finally, on Turn.speak's own terms: ended must run even when closing the
              // log throws, or the caller — Dispatcher, for a trigger's run — never learns
              // this run finished, and its target stays busy until a restart abandons it.
              try {
                log.closed(utterance, outcome);
                runtime.deliverApprovalQuestions(conversation);
              } finally {
                ended.accept(conversation, outcome);
              }
            },
            // true: an event task is one of the doors spec §6 names, and submitEvent always
            // says so -- it has no caller that arrives any other way.
            cap,
            List.of(),
            callerHandle,
            true,
            causation);
    return new EventRun(id, conversation);
  }

  /**
   * Start a run on an allowance somebody else owns, and say when it ended.
   *
   * <p>The door a turn comes through. {@link Turn} holds the two things this one does not decide:
   * which budget — the conversation's, read off its row and shared by reference the way {@code
   * Curator.pass} shares one across a pass — and what to do when the run finishes, which is to
   * write back what that budget came to.
   *
   * <p><b>{@code ended} is called before the job is published as ended, and after the outcome is
   * filed.</b> That is the same rule {@link #finish} already held, extended by one step and for the
   * same reason: a listener told a job has ended goes straight to {@code GET
   * /v1/jobs/&#123;id&#125;}, and a REPL then speaks again. Everything a listener will act on has
   * to be true before it is told — so a conversation whose next utterance is submitted the instant
   * the event lands finds a row that already records what the last turn spent, rather than racing
   * it.
   *
   * <p><b>A callback that throws does not stop the job ending.</b> The exception is logged and the
   * ENDED event still goes out; a listener left waiting for ever because a database write failed
   * would be a second fault on top of the first, and a worse one — the run really did finish, and
   * {@code Turn}'s own {@code finally} is what keeps the conversation speakable when this happens.
   *
   * @param budget the allowance this run and everything it delegates to spends. Never rebuilt here:
   *     a caller reaching this method has one precisely because {@link Budget#of} would be the
   *     wrong object
   * @param transcript what the run opens with, and where its prompt measurements go. {@link
   *     Transcript#NONE} for a caller keeping no history. <b>Required rather than a second
   *     overload</b>: this door is the turn's, {@link Turn} is its only caller in {@code main}, and
   *     a five-argument version left beside it would decide silently that this turn is in no
   *     conversation — the same mistake the {@code sessionId} overload was collapsed to avoid, one
   *     class over
   * @param origin the conversation's origin, or {@code null} for a caller keeping no history. Not
   *     read off {@code transcript}: {@link Compaction.TurnTranscript} carries no origin of its own
   *     — {@link Turn#speak} and a plain submission both build one through it — so the door that
   *     already knows which one this is says so explicitly rather than this method guessing from
   *     the object it was handed. Held on the {@link Job} exactly when {@code
   *     transcript.conversationId()} is non-null, and nulled otherwise even if a caller names one:
   *     a caller with no real conversation — {@link Transcript#NONE}, every fixture — has no origin
   *     for it either
   * @param ended handed the outcome once it is filed. {@code JobRuntime} already reports what a run
   *     spent, so this seam carries no counts of its own — and the prompt measurement does not come
   *     back through here either, because {@code transcript} has already been given it, call by
   *     call, while the run was still going
   * @return the job id, for {@link #get} and {@link #cancel}
   */
  public String submit(
      AgentDefinition definition,
      String userPrompt,
      Home home,
      String sessionId,
      Budget budget,
      Transcript transcript,
      Origin origin,
      Consumer<Outcome> ended) {
    return submit(definition, userPrompt, home, sessionId, budget, transcript, origin, ended, null);
  }

  /**
   * The turn's door, under a ceiling the conversation or the utterance chose.
   *
   * <p>{@link Turn} resolves the three levels — the run's, the conversation's, the definition's —
   * before it gets here, because it is the only caller that can see all three; what arrives is the
   * answer, or {@code null} for the definition's own.
   *
   * @param cap the ceiling for this turn, or {@code null} for the agent's own
   */
  public String submit(
      AgentDefinition definition,
      String userPrompt,
      Home home,
      String sessionId,
      Budget budget,
      Transcript transcript,
      Origin origin,
      Consumer<Outcome> ended,
      TurnCap cap) {
    return submit(
        definition, userPrompt, home, sessionId, budget, transcript, origin, ended, cap, List.of());
  }

  /**
   * The same door, showing the model pictures on the opening turn.
   *
   * <p>One step from the bottom, handing off to the overload below on every caller's behalf with
   * {@code incoming = false} — and the only place images travel any further than this class: they
   * go straight to {@code JobRuntime.run}, which is where the argument about what a resumed run
   * does not get is written down. Nothing on the {@link Job} holds them and nothing durable records
   * them — an entry carries what was said, and a base64 payload is not something anybody said.
   *
   * @param images what the model is shown, already resolved out of the store. Never null; empty
   *     produces the run this method produced before images existed
   */
  public String submit(
      AgentDefinition definition,
      String userPrompt,
      Home home,
      String sessionId,
      Budget budget,
      Transcript transcript,
      Origin origin,
      Consumer<Outcome> ended,
      TurnCap cap,
      List<Content.Image> images) {
    return submit(
        definition,
        userPrompt,
        home,
        sessionId,
        budget,
        transcript,
        origin,
        ended,
        cap,
        images,
        false);
  }

  /**
   * The turn's door, stating whether this turn is an utterance arriving fresh — spec §6. {@link
   * Turn} is the one caller: {@code true} for {@link Turn#speak}, {@code false} for {@link
   * Turn#deliver}, {@link Turn#speakToConductor} and {@link Turn#speakToApprovedRun}, each of which
   * reaches this exact overload. {@link Turn#resume} reaches the nine-argument overload above
   * instead, which chains down to this one already stating {@code false}.
   *
   * @param incoming whether this is one of the doors spec §6 names. The overload above states
   *     {@code false}, which was every caller's meaning before this parameter existed
   */
  public String submit(
      AgentDefinition definition,
      String userPrompt,
      Home home,
      String sessionId,
      Budget budget,
      Transcript transcript,
      Origin origin,
      Consumer<Outcome> ended,
      TurnCap cap,
      List<Content.Image> images,
      boolean incoming) {
    return submit(
        definition,
        userPrompt,
        home,
        sessionId,
        budget,
        transcript,
        origin,
        ended,
        cap,
        images,
        null,
        incoming);
  }

  /**
   * The same door, naming the account the run acts for.
   *
   * @param callerHandle the account the run acts for, which {@code run}'s gate names on a question
   *     it raises; the harness's delegate resume passes the orchestration's. {@code null}, which
   *     the overload above passes, is a run with nobody to ask
   */
  public String submit(
      AgentDefinition definition,
      String userPrompt,
      Home home,
      String sessionId,
      Budget budget,
      Transcript transcript,
      Origin origin,
      Consumer<Outcome> ended,
      TurnCap cap,
      List<Content.Image> images,
      String callerHandle,
      boolean incoming) {
    return submit(
        definition,
        userPrompt,
        home,
        sessionId,
        budget,
        transcript,
        origin,
        ended,
        cap,
        images,
        callerHandle,
        incoming,
        null);
  }

  private String submit(
      AgentDefinition definition,
      String userPrompt,
      Home home,
      String sessionId,
      Budget budget,
      Transcript transcript,
      Origin origin,
      Consumer<Outcome> ended,
      TurnCap cap,
      List<Content.Image> images,
      String callerHandle,
      boolean incoming,
      RelayCausation causation) {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(images, "images");
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(transcript, "transcript");
    Objects.requireNonNull(ended, "ended");
    Objects.requireNonNull(userPrompt, "userPrompt");
    Objects.requireNonNull(home, "home");
    // Blank as well as null, and refused here rather than several seconds
    // later on a virtual thread. JobRuntime.run refuses it too, but by then
    // a job id has been handed back and a caller is polling something that
    // was never going to run; the failure has to reach whoever submitted it.
    if (userPrompt.isBlank()) {
      throw new IllegalArgumentException(
          "the agent '" + definition.name() + "' was given no task to do");
    }
    // Null and blank are two different statements and only one of them is
    // ordinary. Null is a submitter with no session. "" is a submitter that
    // has one and sent it wrong, and accepting it would start a run that
    // silently reaches no client machine and reports nothing amiss —
    // exactly the quiet downgrade the nullable exists to make deliberate.
    // SessionRegistry.attach refuses the same string from the other end.
    if (sessionId != null && sessionId.isBlank()) {
      throw new IllegalArgumentException(
          "the agent '"
              + definition.name()
              + "' was submitted with a session id that"
              + " is blank; leave it out for a run with no session, because an id"
              + " that cannot be named is one no job can be routed to");
    }

    // Resolved once, here, and the same object is both what the run reads
    // and what the Job hands an operator to move. Building a second one for
    // the handle would give an operator a ceiling nothing is under.
    TurnCap under = TurnCap.chosen(cap, null, definition);
    // transcript.conversationId() and not a caller-supplied id: this door is
    // reached both by a plain submission's own Transcript, opened up the
    // call stack through logFor, and by Turn's, opened through
    // transcriptFor or resumedTranscriptFor. Both already know their
    // conversation before they ever get here, and Transcript.conversationId
    // reads it off the field that was set when the transcript itself was
    // built rather than asking either caller to say it twice.
    String conversation = transcript.conversationId();
    // Nulled whenever there is no real conversation, whatever origin a
    // caller named -- a run over Transcript.NONE has no conversation to
    // have an origin of, and this is the one place both facts are settled
    // together so nothing downstream of here can hold one without the
    // other. Job's own constructor refuses the pair otherwise, the same
    // way Transcript.Spoken refuses a conversation id with no turn ordinal.
    Origin conversationOrigin = conversation == null ? null : origin;
    return start(
        definition.name(),
        home,
        sessionId,
        new RunLimits(budget, under),
        conversation,
        conversationOrigin,
        ended,
        (watch, cancelled) ->
            runtime.run(
                definition,
                userPrompt,
                home,
                budget,
                cancelled,
                sessionId,
                watch,
                transcript,
                under,
                images,
                callerHandle,
                incoming),
        id -> {},
        causation);
  }

  /**
   * Start a run that is not one agent's, and return its id at once.
   *
   * <p>The caller {@code Curator.pass} needs, and the reason it is here rather than a second
   * executor of its own. A pass is <em>many</em> runs — a job per candidate, through {@link
   * JobRuntime} — so it is not an {@link AgentDefinition} and has no {@code max-model-calls} of its
   * own; there is deliberately no {@code curator.md}, because with triage in Java there would be
   * nothing for a model in it to do.
   *
   * <p>It is still a job in every way that matters to a caller: it runs on a virtual thread, it is
   * polled by id, it stops at a boundary when cancelled, and what it produced is in the archive and
   * the queue rather than in the handle. Registering it here is what lets one set of MCP tools
   * serve both.
   *
   * <p><b>No session, and not because one was forgotten.</b> This door takes the work itself rather
   * than a definition to run, so there is nothing here for a session id to reach: the caller builds
   * its own runs and decides what they are given. {@code Curator.pass} is the one caller, its
   * rulings go through {@code JobRuntime} with no session, and that is the smaller set the design
   * spec says a run nobody's client asked for should have.
   *
   * <p><b>It is still bracketed by a start and an ending</b>, published to no session, because
   * those two are facts about a job rather than about a run — this class minted the id and will
   * file the outcome either way. What the work does inside is unwatched: the runs a pass makes take
   * {@link JobRuntime}'s six-argument overload and say nothing, which is the same shape a delegated
   * child takes and for the same reason.
   *
   * @param name what {@link Job#agent} reports; the caller's own name for the run, not necessarily
   *     an agent this server defines
   * @param work the run, handed the cancellation flag to consult at its own boundaries
   * @return the job id, for {@link #get} and {@link #cancel}
   */
  public String submit(String name, Function<BooleanSupplier, Outcome> work) {
    return submit(name, Home.global(), work);
  }

  /**
   * The same door, saying which project the work answers in.
   *
   * <p><b>Added when a job became a row</b>, and it is the only thing the row needs that this door
   * did not already carry: {@code V24__jobs.sql} records the project as a reference, because a run
   * nobody can attribute to a project is a record with half its question missing. The overload
   * above answers {@link Home#global()}, which is not a fallback — it is what a caller that names
   * no project means, on {@code V6}'s rule that global is the absence of a project rather than a
   * project called one.
   *
   * @param home the tier the work answers in
   */
  public String submit(String name, Home home, Function<BooleanSupplier, Outcome> work) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(work, "work");
    if (name.isBlank()) {
      throw new IllegalArgumentException("a job needs a name to be polled under");
    }
    // No limits on the handle, and that is not an omission: this door takes
    // the work itself rather than a definition to run, so the bounds it goes
    // under are the caller's own -- Curator.pass builds a Budget and shares
    // it across a whole pass -- and a pair invented here would be one an
    // operator could move to no effect. Job.limits says the same from the
    // other side.
    //
    // No conversation on THIS handle either, and this door has four
    // callers and not one: curator.Passes, behind AgentController's
    // curator endpoint, and DocumentController's two document-agent
    // endpoints reaching Deliberation.ask and Summariser through it. One
    // of those, Deliberation.ask, DOES open a conversation of its own -- but on the job's own
    // thread, inside `work`, after this method has already registered the
    // Job and handed its id back. There is no Transcript here for this
    // method to ask, because the work describing what a conversation would
    // even be has not run yet. Job.conversation stays null for the run's
    // whole life, honestly: whatever conversation the work goes on to open
    // is never this handle's to report, only its own children's -- the same
    // reason a curator's per-candidate rulings are not on this handle
    // either.
    return start(
        name,
        home,
        null,
        null,
        null,
        null,
        outcome -> {},
        (watch, cancelled) -> work.apply(cancelled));
  }

  /**
   * @param limits the two bounds the run is going under, or {@code null} for a job whose work is
   *     not this class's to bound. Put on the {@link Job} so that an operator can read and move
   *     them while it runs, and reachable from nowhere the run itself can see
   * @param conversation the conversation this run speaks into, or {@code null} for a run that has
   *     none. Put on the {@link Job} for the same reason as {@code limits} — see {@link
   *     Job#conversation} for what it is for — and the caller's to say: this method never opens one
   *     and never looks one up, it only carries what a caller already had in hand
   * @param conversationOrigin the conversation's origin, or {@code null} to match a null {@code
   *     conversation}. {@link Job}'s constructor refuses the two disagreeing, so this method does
   *     not have to
   * @param work handed the watch as well as the cancellation flag, because the watch is stamped
   *     with an id that does not exist until this method has minted one — so it cannot be built by
   *     the caller that describes the work
   * @param ended what to tell before the ending is published; see {@link #finish}
   */
  private io.aeyer.plowshare.server.information.InformationJobs informationJobs;

  public void useInformationJobs(io.aeyer.plowshare.server.information.InformationJobs policies) {
    informationJobs = policies;
  }

  private io.aeyer.plowshare.server.access.ProjectAuthorization projectAccess;

  public void useProjectAccess(io.aeyer.plowshare.server.access.ProjectAuthorization access) {
    projectAccess = access;
  }

  public Job getFor(String id, String account) {
    if (projectAccess != null)
      projectAccess.require(
          "job.status",
          io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
              "job.status", Map.of("job", id)),
          account);
    Job job = get(id);
    if (informationJobs != null) {
      informationJobs.require(id, account);
      informationJobs.requireLog(job.conversation(), account);
    }
    return job;
  }

  public List<Job> jobsFor(String account) {
    return jobs().stream()
        .filter(
            job ->
                projectAccess == null
                    || projectAccess.allowed(
                        "job.status",
                        io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                            "job.status", Map.of("job", job.id())),
                        account))
        .filter(
            job ->
                informationJobs == null
                    || (informationJobs.allowed(job.id(), account)
                        && (job.conversation() == null
                            || informationJobs.logAllowed(job.conversation(), account))))
        .toList();
  }

  public String submitInformation(
      String name,
      Home home,
      io.aeyer.plowshare.server.information.InformationContext context,
      List<java.util.UUID> inputs,
      Function<BooleanSupplier, Outcome> work) {
    if (informationJobs == null)
      throw new IllegalStateException("information job policies are not bound");
    return start(
        name,
        home,
        null,
        null,
        null,
        null,
        outcome -> {},
        (watch, cancelled) -> work.apply(cancelled),
        id -> informationJobs.bind(id, context, inputs));
  }

  private String start(
      String name,
      Home home,
      String sessionId,
      RunLimits limits,
      String conversation,
      Origin conversationOrigin,
      Consumer<Outcome> ended,
      BiFunction<JobWatch, BooleanSupplier, Outcome> work) {
    return start(
        name, home, sessionId, limits, conversation, conversationOrigin, ended, work, id -> {});
  }

  private String start(
      String name,
      Home home,
      String sessionId,
      RunLimits limits,
      String conversation,
      Origin conversationOrigin,
      Consumer<Outcome> ended,
      BiFunction<JobWatch, BooleanSupplier, Outcome> work,
      Consumer<String> beforeStart) {
    return start(
        name,
        home,
        sessionId,
        limits,
        conversation,
        conversationOrigin,
        ended,
        work,
        beforeStart,
        null);
  }

  private String start(
      String name,
      Home home,
      String sessionId,
      RunLimits limits,
      String conversation,
      Origin conversationOrigin,
      Consumer<Outcome> ended,
      BiFunction<JobWatch, BooleanSupplier, Outcome> work,
      Consumer<String> beforeStart,
      RelayCausation causation) {
    // ONE INSTANT, READ ONCE, and both the id and the row's `started_at`
    // come from it. MemoryIds' javadoc names what two readings would cost:
    // an id whose embedded timestamp disagrees with the timestamp beside it
    // sorts into a position its own history contradicts.
    //
    // It used to be `"job_" + String.format("%06d", ids.incrementAndGet())`
    // off an in-process AtomicLong, and implementation rationale §2 is the record of what
    // that cost: the counter starts at zero every boot, so the first run
    // after a restart is job_000001 again -- which is what the first run of
    // last Tuesday was called. A probe script broke on it. The scheme here is
    // the one this repository already mints shown ids with, and it is the
    // fourth caller after mem_, prp_ and cnv_: ten hex digits of millis from
    // a 2020 epoch, zero-padded, so string order is minting order.
    //
    // MINTED WHETHER OR NOT THERE IS A `rows` TO WRITE IT TO. An id whose
    // shape depended on whether a database happened to be wired would be two
    // schemes in one server, and the fixtures' one would be the one nothing
    // ever tested.
    Instant at = Instant.now();
    String id = JobLog.newId(at);
    Job job = new Job(id, name, limits, order.incrementAndGet(), conversation, conversationOrigin);
    JobWatch watch = new JobWatch(id, sessionId, events);
    beforeStart.accept(id);

    // BEFORE THE THREAD, so anything handed this id has a record to resolve
    // it against. Writing it when the run ended would be no record at all of
    // the case that motivated the table -- a twenty-six minute ingest whose
    // process died -- and a row that appeared halfway through would be a
    // window in which a job id resolved to nothing.
    wroteDown(id, name, home, at, conversation, causation);
    byId.put(id, job);
    try {
      // execute and not submit. Measured on Java 21.0.8: an exception
      // thrown by a task passed to submit() is held in the returned
      // Future and printed nowhere, and nothing here reads that Future —
      // a job is polled through this store. The catch below is what
      // actually keeps a runtime bug from leaving a RUNNING job forever;
      // execute is chosen so there is no Future to imply otherwise.
      threads.execute(
          () -> {
            // Inside the task and not before it: a submission the executor
            // refuses below never ran, and a STARTED with no ENDED after it
            // is a listener waiting forever on a job that never began.
            watch.started(name);
            // AFTER the STARTED and inside the same task, so the first thing
            // a listener hears about this job is still that it began. Armed
            // on the job's own thread and disarmed in the finally below by
            // the same thread, which is what makes "a beat exists exactly
            // while the run does" one object's lifetime rather than two
            // callbacks that have to agree.
            ScheduledFuture<?> beating = beat(job, watch, name);
            try {
              finish(job, watch, name, work.apply(watch, job::cancelRequested), ended);
            } catch (Throwable bug) {
              // The genuine last resort, and narrower than it used to be:
              // JobRuntime.run now catches RuntimeException around the
              // dispatcher call itself, so everything that happens during
              // a turn comes back as an ending carrying the run's real
              // turn and model-call counts. What is left for this clause
              // is a bug outside that — in building the tool list, say —
              // where there is genuinely nothing to count.
              //
              // Reported as UNAVAILABLE because that is honest from where
              // the caller stands: no answer was produced and the reason
              // was not the model's decision. The zeroes are the honest
              // numbers here and misleading anywhere else, which is
              // exactly why the loop keeps its own.
              //
              // Throwable and not RuntimeException: LlmPool.submit
              // rethrows an Error as itself, and an Error escaping here
              // would leave the job RUNNING for the life of the process
              // with a poller waiting on it — the one outcome this catch
              // exists to prevent, arriving through the one type it used
              // not to cover. The job is finished first and the Error is
              // then rethrown untouched, because an Error means the JVM
              // is in a state where swallowing it would be worse than the
              // hung poller.
              log.error("the job runtime failed running '{}' as job {}", name, id, bug);
              finish(
                  job,
                  watch,
                  name,
                  new Outcome(
                      Ending.UNAVAILABLE,
                      "This run stopped because the job runtime itself failed. Nothing"
                          + " can be concluded from it.",
                      0,
                      0,
                      "the job runtime failed: "
                          + bug.getClass().getSimpleName()
                          + ": "
                          + bug.getMessage()),
                  ended);
              if (bug instanceof Error error) {
                throw error;
              }
            } finally {
              // A finally and not a line after finish(...), because the
              // two paths out of this task are the ordinary one and the
              // one through the clause above -- which rethrows an Error --
              // and a beat that outlived a job by either route would be a
              // job telling a listener it is alive for the rest of the
              // process's life. false and not true: the argument is
              // whether to interrupt a beat that is running, and a beat
              // that is running is inside a queue's offer, which is over
              // in microseconds and has nothing worth interrupting.
              if (beating != null) {
                beating.cancel(false);
              }
            }
            try {
              runEnds.ended(id, name, home);
            } catch (RuntimeException failed) {
              log.warn("a run ended and its listener failed: {} ({})", id, name, failed);
            }
          });
    } catch (RejectedExecutionException shuttingDown) {
      // The store is closing. The job was registered a moment ago, so it
      // has to be finished here or it sits RUNNING with no thread behind
      // it — the exact state the catch above exists to prevent, arriving
      // through the other door.
      byId.remove(id);
      throw new IllegalStateException(
          "the job store is shut down and cannot start '" + name + "'", shuttingDown);
    }
    return id;
  }

  /**
   * Say every {@link #heartbeat} that this job is still running.
   *
   * <h2>Two things stop it, and the second is there because the first is a habit</h2>
   *
   * <p>The cancel in {@code start}'s {@code finally} is what actually stops a beat, and it is
   * exact: the run's own thread disarms the timer before it lets go. <b>The state check inside the
   * task is the structural half</b> — it costs one volatile read per beat and it means that a timer
   * which outlived its job for any reason at all, a lost {@code finally} in some later refactor
   * included, publishes nothing and then cancels itself. A heartbeat for a job that has ended is
   * worse than no heartbeat: a client's whole liveness rule is that these stop.
   *
   * <p>It is also what settles the one ordering a cancel cannot. {@link #finish} calls {@code
   * Job.finish} <em>before</em> {@code watch.ended}, deliberately, so a beat that fires in that gap
   * already sees a job that is {@code DONE} and says nothing — and {@code ENDED} stays the last
   * thing a listener hears.
   *
   * <p><b>Fixed delay and not fixed rate.</b> A publish that took longer than an interval would, at
   * a fixed rate, be followed by a burst catching up — which is the opposite of what this is for.
   * The interval a client reasons about is the gap between beats, and that is the one fixed delay
   * keeps.
   *
   * <p><b>A beat that cannot be scheduled is a beat that was dropped.</b> This one is refused for
   * exactly one reason — the store is closing, and {@link #close} stops this thread while a run
   * that got its task in first is still going — and the answer is {@link JobWatch}'s for the same
   * case one layer up: the stream is droppable and the run is not. A run whose <em>ending</em> were
   * lost because its heartbeat could not start would be a job lost to a timer, which is the
   * opposite of what a timer is here for.
   *
   * @return the scheduled beat, to be cancelled when the run ends, or {@code null} for a store
   *     whose heartbeat is off and for one that is closing
   */
  private ScheduledFuture<?> beat(Job job, JobWatch watch, String agent) {
    if (heartbeat.isZero() || heartbeat.isNegative()) {
      return null;
    }
    // At least one millisecond: a sub-millisecond Duration rounds to zero,
    // and a zero delay here is a thread spinning rather than a heartbeat.
    long millis = Math.max(1, heartbeat.toMillis());
    AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
    try {
      ScheduledFuture<?> scheduled =
          beats.scheduleWithFixedDelay(
              () -> {
                if (job.state() == Job.State.RUNNING) {
                  watch.alive(agent);
                  return;
                }
                ScheduledFuture<?> mine = self.get();
                if (mine != null) {
                  mine.cancel(false);
                }
              },
              millis,
              millis,
              TimeUnit.MILLISECONDS);
      self.set(scheduled);
      return scheduled;
    } catch (RejectedExecutionException shuttingDown) {
      log.debug("job {} runs without a heartbeat: the store is closing", job.id());
      return null;
    }
  }

  /**
   * How many heartbeats this store still has scheduled.
   *
   * <p>Package-private and written for one test: that a timer does not outlive the job it was
   * started for. <b>Silence is not enough to assert that.</b> A beat whose publishing the state
   * check above suppresses is quiet and is still a leaked timer, firing every interval for the life
   * of the process — so the test that would catch the natural bug has to be able to count what is
   * scheduled rather than what was published. {@code setRemoveOnCancelPolicy} is what makes this
   * number mean "live" rather than "ever created".
   */
  int beating() {
    return beats.getQueue().size();
  }

  /** One daemon platform thread, purging what it cancels. See {@link #beats}. */
  private static ScheduledThreadPoolExecutor beatingThread() {
    ScheduledThreadPoolExecutor scheduled =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("job-heartbeat").factory());
    scheduled.setRemoveOnCancelPolicy(true);
    return scheduled;
  }

  /**
   * Tell whoever submitted it, file the outcome, then say so.
   *
   * <p><b>Three steps in that order, and the rule behind all three is one sentence: everything a
   * party will act on is true before that party is told.</b>
   *
   * <p>The last two were here first. A listener reading an ending goes straight to {@code GET
   * /v1/jobs/&#123;id&#125;} for the answer the stream deliberately does not carry, and a job that
   * were still {@code RUNNING} when that request arrived would make the endpoint disagree with the
   * event that sent the client to it. The detail that <em>is</em> in the outcome — an exception's
   * type and its message — stays there and is not a parameter of {@link JobWatch#ended}.
   *
   * <p>{@code ended} goes <em>first</em>, before {@link Job#finish}, and not between the two.
   * <b>Putting it in the middle looks like the same rule and is not.</b> {@code Job.finish} is what
   * makes {@code GET /v1/jobs/&#123;id&#125;} report {@code DONE}, so a REPL that polls the
   * endpoint rather than waiting for the event — which is exactly what a client with no listener
   * attached does — would see a finished turn and speak again while the row saying what that turn
   * cost was still being written. The next utterance would then be handed a budget one turn out of
   * date. Recording first closes both routes with one ordering instead of closing the event and
   * leaving the poll open.
   *
   * <p><b>A callback that throws does not stop the job ending.</b> The run finished; a poller or a
   * listener left waiting for ever because a row could not be written would be a second and worse
   * fault. The throwable is logged whole, as the last-resort clause above logs its own: this seam
   * is not the transport, and its one production caller writes a database row, so no key and no
   * request body can reach it.
   */
  private void finish(
      Job job, JobWatch watch, String agent, Outcome outcome, Consumer<Outcome> ended) {
    try {
      ended.accept(outcome);
    } catch (RuntimeException notRecorded) {
      log.error(
          "a job ended and what it spent could not be recorded: {} ended {}",
          job.id(),
          outcome.ending(),
          notRecorded);
    }
    // Between the callback and Job.finish, which is the same rule one step
    // further out: everything a party will act on is true before that party
    // is told. A poller that saw DONE and went to the job row for how the
    // run ended would otherwise find a row that had not been updated yet.
    filedOutcome(job.id(), outcome);
    job.finish(outcome);
    watch.ended(agent, outcome.ending(), outcome.steps(), outcome.modelCalls());
  }

  /**
   * Write down that this job started, if this store keeps a record.
   *
   * <p><b>Relay work requires this write to succeed before execution</b>, because losing its
   * causation would reset the lifecycle effect budget. For independent work, a failure here does
   * not stop the run, which is {@code Compaction.logFor}'s position and its argument: every caller
   * is on a path where the alternative is losing a submission that has already been accepted, over
   * a database that blinked. What is lost is the record, and the run then behaves exactly as every
   * run behaved before this table existed. It is logged at {@code warn} naming the id, so an
   * operator looking for a job that is not in the table has a line saying why.
   */
  private void wroteDown(
      String id,
      String name,
      Home home,
      Instant at,
      String conversation,
      RelayCausation causation) {
    if (rows == null) {
      if (causation != null)
        throw new IllegalStateException("Relay work requires a durable job log");
      return;
    }
    try {
      rows.started(id, name, home, at, conversation, causation);
    } catch (RuntimeException notWritten) {
      // A causal job cannot fall back to unrecorded execution: its notices would lose the bound.
      if (causation != null) throw notWritten;
      // First line only; see JobRuntime.describe. A constraint violation's
      // second line quotes the failing row.
      log.warn(
          "job {} of '{}' could not be written down, so it runs with no durable"
              + " record and nothing afterwards can tell that it ran. Reason: {}",
          id,
          name,
          JobRuntime.describe(notWritten));
    }
  }

  /**
   * And how it ended.
   *
   * <p><b>A row that is gone is not a fault.</b> {@link JobLog#ended} answers false for it rather
   * than raising — a sweep pruned the record, or the write above failed — and there is nothing for
   * this store to do about either. Swallowed on {@link #finish}'s terms: the run really did finish,
   * and a poller left waiting because a row could not be written would be a second and worse fault.
   */
  private void filedOutcome(String id, Outcome outcome) {
    if (rows == null) {
      return;
    }
    try {
      rows.ended(id, outcome.ending(), outcome.steps(), outcome.modelCalls(), Instant.now());
    } catch (RuntimeException notWritten) {
      log.warn(
          "job {} ended {} and that could not be written onto its row, which"
              + " therefore still reads as a run nothing filed an outcome for."
              + " Reason: {}",
          id,
          outcome.ending(),
          JobRuntime.describe(notWritten));
    }
  }

  /**
   * The job under {@code id}.
   *
   * <p>An exception and not a null or an empty Optional: a caller asking about a job id has one
   * from somewhere, so a miss is a spelling or a restart rather than an ordinary outcome, and it is
   * worth a message that says which ids exist. The same choice {@code AgentRegistry.get} makes for
   * the same reason.
   *
   * <p><b>{@link NotFoundFault} and not the {@code IllegalArgumentException} this used to
   * throw.</b> The two say different things: "bad argument" is about the shape of what was passed,
   * and this is about what is behind it. The old type made that distinction somebody else's job —
   * {@code AgentController.find} caught it by name and restated it as {@code
   * api.NotFoundException}, which is the only reason {@code GET /v1/jobs/&#123;id&#125;} answered
   * 404 rather than 500. A frame handler calling this method would have reached no such catch. The
   * status is the same as it always was; what changed is that this method now decides it, in a type
   * {@code Faults} maps for whichever surface asked.
   *
   * <p><b>A null {@code id} is a caller fault and not a miss</b>, and the distinction only became
   * reachable when this method stopped being something only a controller called. A path variable
   * cannot be absent, so over HTTP {@code id} is never null; a frame naming no id at all is a body
   * that left a required field out, which is the same refusal every other missing field gets.
   * Without this it is {@code ConcurrentHashMap.get(null)} — an NPE, and therefore a 500 for a
   * caller's typo.
   *
   * @throws CallerFault if {@code id} is null
   * @throws NotFoundFault if this process holds no job under {@code id}
   */
  public Job get(String id) {
    if (id == null) {
      throw new CallerFault(
          "'id' is required: it is the job to read, as this server named it when the"
              + " run was submitted. Nothing was read.");
    }
    Job job = byId.get(id);
    if (job == null) {
      throw new NotFoundFault("no job called '" + id + "'; this process knows " + ids());
    }
    return job;
  }

  /**
   * Ask a run to stop at its next turn boundary.
   *
   * <p>Returns immediately and does not wait for the run to notice. <b>Cancellation is a flag and
   * not an interrupt</b>, because an in-flight model call cannot be interrupted out of a
   * synchronous HTTP execute: {@code LlmPool.submit}'s javadoc sets out why at length, and
   * interrupting the lane thread would abandon a request that still lands on the box. Measured on
   * Java 21.0.8: a virtual thread blocked on a latch <em>does</em> throw {@code
   * InterruptedException} when interrupted, so the flag is not chosen because interruption fails to
   * arrive — it is chosen because arriving mid-turn would leave tool results half-appended for no
   * gain, when the boundary is a few seconds away and is where the run can stop cleanly.
   *
   * @return true if this call is the one that asked. False for an unknown id, for a second caller,
   *     and for a run that has already finished — a store that returned true for the last of those
   *     would tell a caller it had stopped a run that had already answered.
   * @throws CallerFault if {@code id} is null, on {@link #get}'s reasoning — false here means
   *     "there was a job and this call did not stop it", which is a fact about a run, and a caller
   *     that named no run at all has not asked a question this method can answer false to
   */
  public boolean cancel(String id) {
    if (id == null) {
      throw new CallerFault(
          "'id' is required: it is the run to stop, as this server named it when the"
              + " run was submitted. Nothing was stopped.");
    }
    Job job = byId.get(id);
    return job != null && job.requestCancel();
  }

  /**
   * Every job this process knows, newest last.
   *
   * <p>The {@code GET /v1/jobs} listing this was written for was dropped in 0eadb54 as an endpoint
   * nothing asked for, leaving tests as the only callers; it was kept rather than deleted because a
   * listing was the obvious next need and the ordering was the part worth not re-deriving. <b>Slice
   * 4 is that need</b>, and {@code AgentController.jobs} is a production caller again — so the
   * order below is now the console's job-list order, and changing it changes a screen.
   */
  public List<Job> jobs() {
    // BY THE ORDER THIS PROCESS REGISTERED THEM, and not by id. It was a
    // TreeMap over the ids, which was exactly right while an id was a
    // zero-padded counter; MemoryIds' scheme is sortable to the MILLISECOND,
    // so two jobs submitted in one loop differ only in three random bytes and
    // a sort on the id would put them in an order that means nothing. See
    // Job.sequence, which is the second key V6 gets from `created_at`.
    return byId.values().stream().sorted(Comparator.comparingLong(Job::sequence)).toList();
  }

  private List<String> ids() {
    return List.copyOf(new TreeMap<>(byId).keySet());
  }

  /**
   * Asks every running job to stop, then stops taking new ones.
   *
   * <p><b>Deliberately does not wait.</b> Measured on Java 21.0.8: {@code ExecutorService.close()}
   * blocks until every running task finishes, and {@code shutdownNow()} does interrupt a running
   * virtual thread at its next blocking point. Neither is what we want. A job inside a model call
   * cannot be cut short — that call is bounded by the transport's read timeout, which is the only
   * deadline this system has for it — so {@code close()} would make shutdown wait on an endpoint's
   * timeout, and {@code shutdownNow()} would interrupt a run that still has tool results to append
   * in order to save nothing. The flag reaches every job at its next boundary, which is the same
   * place cancellation always lands.
   *
   * <p>The threads are daemon by construction — every virtual thread is — so a job still finishing
   * does not hold the JVM open.
   */
  @Override
  public void close() {
    for (Job job : byId.values()) {
      job.requestCancel();
    }
    threads.shutdown();
    // shutdownNow and not shutdown, for the one reason the paragraph above
    // rejects it for the jobs: a repeating task is never "finished", so
    // shutdown() would leave this thread beating until the last run noticed
    // its cancellation flag. There is nothing here to lose by stopping at
    // once -- a beat is droppable, and what is being closed is the thing
    // that sends them.
    beats.shutdownNow();
  }
}
