package io.aeyer.plowshare.client.cli;

import io.aeyer.plowshare.client.HttpServerClient;
import io.aeyer.plowshare.client.PlowshareClient;
import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.SessionClient;
import io.aeyer.plowshare.client.files.Rooting;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.JobEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A terminal that opens a session, lends this machine's files to it, and either
 * starts one run and watches it work or holds a conversation of them.
 *
 * <h2>What this is for</h2>
 *
 * <p>It is the slice's acceptance test and not a product. Everything under it is
 * server-side and has only ever been exercised by harnesses that hold both halves;
 * this is the first thing in the project that drives the remote path the way a
 * person does — dial out, offer a directory, submit, watch, read the outcome.
 *
 * <pre>
 *   plowshare &lt;agent&gt; &lt;task&gt; [--workspace DIR[,DIR]] [--project NAME]
 *                              [--server URL]
 *   plowshare run &lt;agent&gt; &lt;task&gt; [the same options]
 *   plowshare --talk [agent] [--max-model-calls N] [the same options]
 *   plowshare &lt;group&gt; &lt;verb&gt; [arguments]
 * </pre>
 *
 * <p>The second form is the first said out loud, and it exists because the fourth
 * reserves five words at the front of a command line: after {@code run}, the next
 * word is an agent whatever it spells.
 *
 * <p>The third is {@link Repl}, and everything below is still its: it opens the
 * same session, renders each turn through {@link #follow} and reports each one
 * through {@link #say}. What that class adds is a conversation to put the runs in
 * and a person to hear from between them.
 *
 * <p>The fourth is {@link Commands}, and it is a different shape: no session, no
 * watching, one call and its answer. It is where the rest of what a Plowshare
 * client can do lives — the archive, the promotion queue, conversations read
 * back, the corpus, projects, and a run that is already going. Those are the same
 * capabilities the MCP surface offers, and {@link
 * io.aeyer.plowshare.client.Capabilities} is what holds the two to each other.
 *
 * <p>Run as a plain class on a classpath, the way {@link PlowshareClient} is and
 * for its reason: {@code jar} here packages this module alone, and a {@code java
 * -jar} that failed on a missing Jackson would be a worse first experience than a
 * classpath written out.
 *
 * <h2>What a person sees, and how they tell working from stuck</h2>
 *
 * <p>There is no token stream, and that is now a decision rather than a
 * deferral. Slice 3d put it off on the grounds that building it before the REPL
 * that needs it would mean designing it blind; the REPL exists, slice 3e's task
 * 7 took the decision, and <b>the answer was no</b> — for two reasons that turn
 * out to be one. Every model call a turn makes carries the agent's tools, and
 * {@code LlmDispatcher.stream} refuses a request carrying tools; and only the
 * <em>last</em> call's prose becomes the answer {@code GET
 * /v1/jobs/&#123;id&#125;} returns, so streaming the rest would disclose a
 * model's working rather than re-time a disclosure — while which call is the last
 * one is knowable only once it has come back without tool calls. The containment
 * rule this terminal does not get to relax is intact and untouched: {@code
 * JobEvent} carries no payload by signature, and model output is payload. So the
 * honest signal is
 * still the lifecycle events themselves <b>and their timing</b>, and the timing is
 * not decoration: four events across a three-minute run is a nearly still
 * terminal, which reads exactly like a wedged one.
 *
 * <p>Every line carries two clocks — how long the run has been going, and how
 * long since anything last happened — and {@link #waiting} prints the second one
 * on its own while nothing is arriving:
 *
 * <pre>
 *    0.0s  +0.0s  started      promotion_judge
 *    0.4s  +0.4s  model call   step 1, 1 model call
 *    3.2s  +2.8s  tool called  file_read
 *   13.2s +10.0s  waiting      still nothing since tool called file_read
 *   23.2s +20.0s  waiting      still nothing since tool called file_read
 *   31.0s +27.8s  model call   step 2, 2 model calls
 *   33.0s  +2.0s  ended        ANSWERED after 2 steps, 2 model calls
 * </pre>
 *
 * <p>The two waiting lines are what a stuck run looks like, and the number that
 * grows between them is the whole signal. Their gap column measures the last
 * <em>event</em> and not the last line, so it goes on counting up rather than
 * restarting each time the terminal says something.
 *
 * <p>A person reading that can say which of the two states the run is in: a
 * growing gap under {@code model call} is a model thinking, and a growing gap
 * under {@code tool called} is a tool that has not come back. Neither is
 * distinguishable from the other, or from a hang, without the gap.
 *
 * <h2>What it does when things go wrong, which is most of the design</h2>
 *
 * <ul>
 *   <li><b>the server has no session support.</b> Its {@code /v1/events} answers
 *       404, {@link SessionClient#attach} refuses, and this exits saying so.
 *       Watching nothing forever is the alternative, and it looks identical to a
 *       run that is stuck;
 *   <li><b>no credential, or the wrong one.</b> The server's {@code AuthFilter}
 *       gates every {@code /v1} path, so both upgrades answer <b>401</b> and
 *       {@link SessionClient#attach} refuses naming the status. It is loud
 *       rather than subtle for that reason. What this reads is {@link
 *       PlowshareClient#accessToken()}: {@code PLOWSHARE_TOKEN} if it is
 *       exported, and otherwise {@code ~/.config/plowshare/console-token}, which
 *       a server started on this machine wrote at mode 600 when it came up. So
 *       the ordinary case needs no flag and no export, and a 401 here means
 *       either that no server on this machine has started since the file was
 *       last removed, or that the one this is pointed at is a different one;
 *   <li><b>no workspace.</b> Legal, and printed rather than assumed: a session
 *       that lends nothing is a supported row of the spec's table, and a person
 *       who meant to pass {@code --workspace} would otherwise watch a run fail to
 *       find their files and blame the agent;
 *   <li><b>the event socket drops mid-run.</b> The stream is droppable by design
 *       and the outcome is behind {@code GET /v1/jobs/&#123;id&#125;} either way,
 *       so this says the view is gone and goes on polling. <b>A client that lost
 *       its socket and then reported the run as failed would be lying about a job
 *       that is still going</b>;
 *   <li><b>events were dropped.</b> Said out loud at the end. The server's own
 *       drop is silent by design — no sequence numbers on a droppable stream — so
 *       the only incompleteness this can report is its own, and it says which.
 * </ul>
 *
 * <h2>Exit status</h2>
 *
 * <p>{@code 0} only when the run <b>answered</b>. Not "finished": a truncated run
 * is never dressed as an answer, which is {@code RunOutcome.answered}'s whole
 * reason for being a separate bit from the ending. {@code 1} for a run that ended
 * without answering, {@code 2} for anything that stopped this from watching a run
 * at all.
 *
 * <p><b>A conversation reads the same three numbers about a different thing</b>,
 * and {@link Repl} states them: a turn that did not answer is not a failed
 * conversation, so its {@code 1} is kept for a conversation that ended without the
 * person ending it.
 */
public final class Plowshare {

    /** How often to look at the job endpoint while a run is going. Slow, because
     *  the live view is the socket and this is only the backstop that holds the
     *  outcome — and because it keeps polling on a lost socket without turning
     *  the fallback into a load test. */
    private static final Duration POLL = Duration.ofSeconds(2);

    /** How long the terminal stays quiet before saying that it is quiet. Chosen
     *  against the thing it has to beat: {@link #POLL} is 2s, and a waiting line
     *  every 2s would be noise rather than news. Five of them is long enough that
     *  an ordinary gap between a model call and its answer passes unremarked, and
     *  short enough that a person deciding whether to reach for ctrl-C has an
     *  answer before they do. */
    private static final Duration QUIET = Duration.ofSeconds(10);

    /** How much of a session id reaches stdout. Eight hex characters of a
     *  version-four UUID are 32 bits: plenty to tie two lines of one transcript
     *  together, and not a capability anybody can attach with. */
    private static final int HANDLE_CHARS = 8;

    /**
     * The usage block, whose second half is generated.
     *
     * <p>The commands under {@link Commands} print themselves from the table that
     * dispatches them, so a verb cannot be added, renamed or removed without this
     * block following it. What is written by hand is the part that describes this
     * class: the two forms that need a session, and the options they take.
     */
    private static final String USAGE = """
            usage: plowshare <agent> <task> [options]              one run, and its outcome
                   plowshare run <agent> <task> [options]          the same, said out loud
                   plowshare --talk [agent] [options]              a conversation, until you leave
                   plowshare <command> [arguments]                 everything below

              --talk                 hold a conversation instead of making one run. The
                                     agent is optional and defaults to %s.
              --max-model-calls N    a conversation's whole allowance, across every turn
                                     in it. Only with --talk, and optional there: leave
                                     it out and the server uses the number it is
                                     configured with, which it tells you when it opens.
              --workspace DIR[,DIR]  what this machine lends the run. Omitted means
                                     the run sees only what the server can.
              --project NAME         the tier the run answers from. Omitted means global.
                                     With --workspace it also roots the project here: runs
                                     in it reach THIS machine, from any terminal.
              --server URL           where the server is. Defaults to $%s, then %s.
                                     Every command below takes it too.

            The first word is an agent unless it is one of the command groups below, in
            which case it is a command. 'plowshare run <agent> <task>' is how to reach an
            agent whose name is one of them.
            %s"""
            .formatted(Repl.INTERLOCUTOR, PlowshareClient.SERVER_URL_ENV,
                    PlowshareClient.DEFAULT_SERVER_URL, Commands.usage());

    private Plowshare() {}

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /**
     * The whole of it, with its two streams passed in.
     *
     * <p>Separate from {@link #main} so that it returns a status instead of
     * calling {@code System.exit}, which is the difference between something a
     * test can run and something that takes the JVM with it. Public for the same
     * reason: {@code SessionClientTest} drives the whole terminal against a real
     * socket and reads what it printed, which is the only way to check that the
     * acceptance path prints an outcome rather than that its pieces could.
     */
    public static int run(String[] args, PrintStream out, PrintStream problems) {
        return run(args, System.in, out, problems);
    }

    /**
     * The same, with the terminal's own input as well.
     *
     * <p>Only {@code --talk} reads it, and a one-shot run does not touch it at
     * all — which is why the three-argument form above is still the whole of what
     * this class was and still delegates here with {@link System#in}. A test that
     * drives a conversation supplies its lines the way a pipe does.
     */
    public static int run(String[] args, InputStream in, PrintStream out, PrintStream problems) {
        // The command surface, before anything that needs a session. None of
        // those verbs lends a file or watches a run, so opening the two sockets
        // this class opens below would make every read wait on a channel it has
        // no use for — and would make 'memory index' fail against a server whose
        // /v1/events is not there.
        //
        // A reserved word is always a command, never a guess from the shape of
        // the rest of the line: a guess is silent when it is wrong. `run` is the
        // way past it for an agent whose name collides, and it is stripped here
        // rather than parsed as one, so that after it every word means what it
        // means in the bare form.
        if (args.length > 0 && Commands.group(args[0])) {
            return Commands.run(args, out, problems);
        }
        if (args.length > 0 && "run".equals(args[0])) {
            args = Arrays.copyOfRange(args, 1, args.length);
        }

        Options options;
        try {
            options = Options.of(args);
        } catch (IllegalArgumentException notUsable) {
            problems.println(notUsable.getMessage());
            problems.print(USAGE);
            return 2;
        }

        ServerClient server;
        try {
            // Inside a guard rather than beside one. HttpServerClient refuses an
            // unusable base URL at construction, deliberately — and this line
            // used to sit outside the try below, so a mistyped --server left
            // that IllegalArgumentException to the JVM: a stack trace and exit
            // 1, against the 2 this class promises for anything that stopped it
            // watching a run.
            // The credential comes from the environment and never from a flag:
            // a --token would put it on a command line, which every process on
            // the box can read through ps. PlowshareClient.accessToken() is the
            // one resolution, shared so that a person who exports
            // PLOWSHARE_TOKEN does not find half of it taking effect.
            server = new HttpServerClient(
                    options.serverUrl(), PlowshareClient.accessToken());
        } catch (IllegalArgumentException notAUrl) {
            problems.println(notAUrl.getMessage());
            return 2;
        }
        Workspace workspace = new Workspace();
        workspace.set(options.workspace());
        Rooting rooted = rooting(options);

        // Cannot throw for a bad URL: it builds its socket over
        // ServerClient.baseUrl(), which is a parsed HttpUrl printed back out,
        // and the parse that would have refused it happened above.
        try (SessionClient session =
                new SessionClient(server, workspace, PlowshareClient.accessToken(), rooted)) {
            // A prefix and not the whole id. SessionClient's own javadoc says an
            // id that has been used is as good as a password to whoever sees it,
            // and stdout is a CI log, a paste and a screen share; ChannelClient
            // redacts its URL for the same reason. Eight hex characters are
            // enough to tie two lines of this transcript together and are not
            // the capability.
            out.println("session " + handle(session.id()) + " → " + session.serverUrl());
            if (options.workspace().isEmpty()) {
                // Loud rather than silent. A run with no workspace is a supported
                // shape and a wrong one to arrive at by forgetting a flag.
                out.println("no workspace: this session lends no files. Pass --workspace <dir>"
                        + " to let the run read this machine.");
            } else {
                out.println("lending " + options.workspace());
            }
            if (rooted != null) {
                // Loud, for the reason the workspace line above is: this is the
                // sentence that says a run in this project reaches THIS machine
                // and no other, which is a different and larger claim than
                // lending a directory.
                // The three parts and not the canonical name they compose.
                // Server-side `Presence` owns that formula and is the only place
                // that spells it; a second spelling here would be two
                // conventions that agree until one of them changes.
                out.println("rooting " + rooted.project() + " at " + rooted.root()
                        + " on " + rooted.machine());
            } else if (!options.workspace().isEmpty()) {
                // Lending and rooting are not the same thing, and the difference
                // is invisible until a run in a named project comes back saying
                // no presence serves it. Said here, once, where the flag that
                // fixes it is still on screen.
                out.println("rooting nothing: pass --project <name> as well to make this"
                        + " machine the one that serves that project's files.");
            }

            String job;
            ServerClient.Conversation conversation = null;
            try {
                session.attach(SessionClient.ATTACH_PATIENCE);
                if (options.talk()) {
                    // The session first and the conversation second, and the
                    // order is an argument. A conversation is a durable row and
                    // a session is two sockets: a row opened for a terminal that
                    // then failed to attach is a permanent artefact of a failed
                    // start, while sockets opened for a conversation that then
                    // failed to open are closed by the block around this and
                    // leave nothing behind.
                    conversation = server.openConversation(
                            options.project(), options.maxModelCalls());
                    job = null;
                } else {
                    job = session.submit(
                            options.agent(), options.task(), options.project(), null);
                }
            } catch (IllegalStateException refused) {
                // The submission guard: a state this client is in, described in
                // the words of that state. Nothing was submitted.
                problems.println(refused.getMessage());
                return 2;
            } catch (ServerClient.ServerError said) {
                problems.println(said.getMessage());
                return 2;
            } catch (IOException unreachable) {
                problems.println("could not " + (options.talk()
                                ? "open a conversation on " : "start a run against ")
                        + options.serverUrl() + ": " + unreachable.getMessage());
                return 2;
            }
            if (conversation != null) {
                return Repl.hold(session, server, conversation, options.agent(),
                        reader(in), out, problems);
            }
            out.println("job " + job + " — " + options.agent());

            // A second try, and the separation is the point. These four clauses
            // used to be one set covering both phases, with the watch inside
            // them — so a network blip on a mid-run poll printed "could not run
            // against <url>", a sentence about a run that had already started,
            // and dropped the job id it had printed one line earlier. A failure
            // after submission is never a failure to submit.
            try {
                return watch(session, job, out, problems);
            } catch (ServerClient.ServerError said) {
                return stillRunning(job, options.serverUrl(), problems, said.getMessage());
            } catch (IOException lost) {
                return stillRunning(job, options.serverUrl(), problems, lost.getMessage());
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return stillRunning(
                        job, options.serverUrl(), problems, "this terminal was interrupted");
            }
        }
    }

    /**
     * What this invocation declares it roots, or null.
     *
     * <p><b>Both flags, or neither.</b> A presence is a project <em>at a
     * place</em>, so {@code --project} with no {@code --workspace} has no place
     * to name and {@code --workspace} with no {@code --project} has no name for
     * the place — and each alone is already a supported way to run: the first is
     * a memory tier with no files, the second is a machine lending a directory to
     * runs in the global tier.
     *
     * <p><b>The first root and not all of them</b>, when several are lent. A
     * project is one place — that is the whole of the owner's "a project is where
     * the files are" — and a canonical name composed from a list would be an
     * identity with no single location in it. The rest are still lent and still
     * reachable; what the first one fixes is where the project <em>is</em>, and
     * it is the one a person names when they name one.
     */
    private static Rooting rooting(Options options) {
        if (options.project() == null || options.workspace().isEmpty()) {
            return null;
        }
        return Rooting.of(options.workspace().get(0), options.project());
    }

    /**
     * The terminal lost the server while a run was going, and says so about the
     * run rather than about the submission.
     *
     * <p>Names the job in every sentence it can, because that id is the only
     * thing that gets the outcome back, and this is the moment a person needs
     * it. It also says what is true of the run: closing these sockets does not
     * stop a job — the server ends a run when a file request cannot be served,
     * never when a socket closes.
     */
    static int stillRunning(
            String job, String serverUrl, PrintStream problems, String why) {
        problems.println("lost contact with " + serverUrl + " while watching " + job
                + ": " + why + ". The run was NOT cancelled by this — a job ends when a file"
                + " request cannot be served, never when a socket closes. GET /v1/jobs/" + job
                + " for its outcome, or POST /v1/jobs/" + job + "/cancel to stop it.");
        return 2;
    }

    /** Enough of a session id to tie two lines together, and not enough to
     *  attach with. */
    private static String handle(String id) {
        return id.length() <= HANDLE_CHARS ? id : id.substring(0, HANDLE_CHARS) + "…";
    }

    /**
     * Render until the job endpoint says the run is over.
     *
     * <p><b>The endpoint and not the {@code ended} event decides when to stop</b>,
     * and the two are not interchangeable. The stream is droppable, so an {@code
     * ended} that never arrives is an ordinary outcome of a busy or broken socket;
     * the job endpoint is the only contractual record of a run. Reading the event
     * would make a dropped frame look like a run that never finished.
     */
    private static int watch(SessionClient session, String job, PrintStream out,
            PrintStream problems) throws IOException, InterruptedException {
        ServerClient.JobStatus status = follow(session, job, out, WhileWatching.NOTHING);
        return say(status.outcome(), session.dropped(), out, problems) ? 0 : 1;
    }

    /**
     * Render until the job endpoint says this run is over, and answer with what
     * it said.
     *
     * <p>Shared with {@link Repl}, and sharing it is the point rather than a
     * convenience: the two clocks, the quiet line and the rule that the endpoint
     * and not the {@code ended} event decides when to stop are one argument, and
     * a REPL with its own copy of that loop would be a second place for it to
     * drift. What the two callers do differ about is what happens <em>around</em>
     * a run, which is what {@code attending} and the return type leave to them.
     *
     * @param attending called once per poll of the event stream, so that a caller
     *     with somebody to listen to can listen. {@link WhileWatching#NOTHING}
     *     for a terminal running one job, which has nobody to hear from
     */
    static ServerClient.JobStatus follow(SessionClient session, String job, PrintStream out,
            WhileWatching attending) throws IOException, InterruptedException {
        Heartbeat beat = new Heartbeat(System.nanoTime());
        while (true) {
            attending.check();
            JobEvent event = session.nextEvent(POLL);
            if (event != null) {
                out.println(beat.onEvent(event, System.nanoTime()));
                continue;
            }
            String quiet = beat.onQuiet(session.listening(), System.nanoTime());
            if (quiet != null) {
                out.println(quiet);
            }
            ServerClient.JobStatus status = session.job(job);
            if (status.outcome() != null) {
                return status;
            }
        }
    }

    /**
     * Something to do between polls while a run is going.
     *
     * <p>Declared with a name for the thing rather than as a {@code Runnable},
     * because the one implementation makes a network call and a checked {@link
     * IOException} is what stops a caller quietly swallowing a cancel that never
     * left the machine.
     */
    @FunctionalInterface
    interface WhileWatching {

        /** A terminal watching one run, with nobody to hear from. */
        WhileWatching NOTHING = () -> {};

        void check() throws IOException;
    }

    /**
     * A finished run, as a person reads it: how it ended, and its answer if it
     * reached one.
     *
     * <p><b>{@code answered} and not the ending decides whether the text is
     * shown</b>, which is the rule this whole terminal turns on and the reason
     * that bit exists separately from the ending at all. Shared with {@link Repl}
     * so that a conversation cannot grow a second reading of it.
     *
     * @param dropped how many events were lost <em>for this run</em>. A whole
     *     terminal passes {@link SessionClient#dropped()}, which is its whole
     *     life; a REPL passes the difference since its last turn, because a
     *     running total reprinted after every turn would report one turn's hole
     *     in every turn after it
     * @return whether the run answered
     */
    static boolean say(ServerClient.RunOutcome outcome, int dropped, PrintStream out,
            PrintStream problems) {
        if (dropped > 0) {
            out.println("(" + dropped + " events were dropped by this terminal; the"
                    + " view above is incomplete and the outcome below is not)");
        }
        out.println(outcome.ending() + " after " + count(outcome.steps(), "step") + ", "
                + count(outcome.modelCalls(), "model call"));
        if (outcome.answered()) {
            out.println();
            out.println(outcome.text());
            return true;
        }
        // Never dressed as an answer: `answered` is a separate bit from the
        // ending for exactly this, and text() means nothing until it is read.
        //
        // Blank and not only null, and the number was counted rather than
        // guessed. Of the six endings that are not an answer, JobRuntime passes
        // the empty string for three — TURN_CAP, CALL_BUDGET and CANCELLED,
        // whose own sentences already say the whole of it — and a describe() for
        // the rest. So a null check alone printed "...answering: " with nothing
        // after the colon on exactly the endings a person meets most, which
        // reads as a message that was itself cut off.
        problems.println(outcome.detail() == null || outcome.detail().isBlank()
                ? "the run stopped without answering"
                : "the run stopped without answering: " + outcome.detail());
        return false;
    }

    /**
     * The terminal's own input, decoded.
     *
     * <p>{@link Charset#defaultCharset()} and not UTF-8: this is a person typing
     * at whatever terminal they have, and what they type arrives in that
     * terminal's encoding. Everything this client <em>sends</em> is UTF-8 by the
     * time okhttp writes it.
     */
    private static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, Charset.defaultCharset()));
    }

    /**
     * The two clocks and the one flag the watch loop keeps.
     *
     * <h2>Why this is an object</h2>
     *
     * <p>It was three local variables and a pair of branches, and the branches
     * were wrong in a way no test could reach without waiting {@link #QUIET}
     * seconds. Once the stream had gone, the first branch was spent — it is
     * guarded on not having said so yet — and the second was guarded on {@code
     * session.listening()}, which is false from then on. So the terminal printed
     * one line and then <b>nothing at all until the run ended</b>, which is the
     * still terminal this class exists to avoid, arriving in the one case the
     * heartbeat matters most: a person who has lost the live view has no other
     * way to tell a run that is working from one that is stuck.
     *
     * <p>Pulled out so that failure is a unit test with synthetic clock readings
     * rather than a ten-second wait, and public for the reason {@link #line} is.
     */
    public static final class Heartbeat {

        private final long started;
        private long lastEvent;
        private long lastSaid;
        private JobEvent latest;
        private boolean saidTheViewIsGone;

        /** @param startedNanos a {@link System#nanoTime()} reading, or any
         *      origin a test wants to count from */
        public Heartbeat(long startedNanos) {
            this.started = startedNanos;
            this.lastEvent = startedNanos;
            this.lastSaid = startedNanos;
        }

        /** Render one event, and remember that the run did something. */
        public String onEvent(JobEvent event, long now) {
            String rendered = line(event, since(started, now), since(lastEvent, now));
            lastEvent = now;
            lastSaid = now;
            latest = event;
            return rendered;
        }

        /**
         * What to print when a poll came back with no event, or null for
         * silence.
         *
         * <p>Two clocks and not one, because they answer different questions.
         * {@code lastEvent} is what the gap column means — how long since the
         * run last did anything — and a waiting line is not the run doing
         * something, so printing one must not reset it. {@code lastSaid} is only
         * how often to repeat. Sharing a clock would either restart the gap at
         * every waiting line, hiding exactly the growing number a person is
         * watching for, or repeat that line every {@link #POLL} for as long as
         * the quiet lasted.
         */
        public String onQuiet(boolean listening, long now) {
            if (!listening && !saidTheViewIsGone) {
                saidTheViewIsGone = true;
                lastSaid = now;
                return "the event stream is gone; the run is not. Still reading the outcome"
                        + " from the job endpoint.";
            }
            if (since(lastSaid, now).compareTo(QUIET) < 0) {
                return null;
            }
            lastSaid = now;
            return waiting(since(started, now), since(lastEvent, now), latest, listening);
        }
    }

    // --- rendering ---------------------------------------------------------------

    /**
     * One event, as a person reads it.
     *
     * <p>Public, and called by {@code SessionClientTest} against whole strings: a
     * claim about what somebody sees is only checked by checking what they see,
     * and a containment assertion would pass on a line whose clocks were missing.
     *
     * @param sinceStart how long the run has been going
     * @param sinceLast how long since the previous event — the column that
     *     separates a run that is working from one that is stuck
     */
    public static String line(JobEvent event, Duration sinceStart, Duration sinceLast) {
        return column(sinceStart, sinceLast) + pad(label(event.kind())) + detail(event);
    }

    /**
     * The line printed while nothing is arriving.
     *
     * @param last the last event that did arrive, or null if the run has not
     *     produced one yet — which is itself worth saying, because a run that has
     *     not started is a different thing to worry about than one that has
     * @param streaming whether the event socket is still up. False says the
     *     quiet is this terminal's and not the run's
     */
    public static String waiting(Duration sinceStart, Duration sinceLast, JobEvent last,
            boolean streaming) {
        return column(sinceStart, sinceLast) + pad("waiting") + quiet(last, streaming);
    }

    /**
     * What the quiet means, which is not the same thing in the two states.
     *
     * <p>With the socket up, nothing arriving is the run thinking. With it gone,
     * nothing is going to arrive whatever the run does, and a line saying "still
     * nothing since tool called file_read" would invite a reader to conclude
     * something about the run from a fact about this terminal.
     */
    private static String quiet(JobEvent last, boolean streaming) {
        if (!streaming) {
            return "no event stream; still asking the job endpoint";
        }
        return last == null
                ? "still nothing at all since the run was submitted"
                : "still nothing since " + label(last.kind()) + describeSuffix(last);
    }

    /**
     * The kind, in words.
     *
     * <p>An unknown kind renders as itself rather than throwing. {@code
     * JobEvent.kind} is a String and not an enum precisely so that a client built
     * against an older server binds a constant it has never heard of; a switch
     * over four literals with a {@code default -> throw} would take that promise
     * back on the rendering side.
     */
    private static String label(String kind) {
        return switch (kind == null ? "" : kind) {
            case JobEvent.STARTED -> "started";
            case JobEvent.MODEL_CALL -> "model call";
            case JobEvent.TOOL_CALLED -> "tool called";
            case JobEvent.ENDED -> "ended";
            default -> kind;
        };
    }

    private static String detail(JobEvent event) {
        return switch (event.kind() == null ? "" : event.kind()) {
            case JobEvent.STARTED -> event.agent();
            // steps + 1, and read from JobRuntime rather than guessed: the
            // event is published where the budget is claimed, before the call is
            // made, and carries the number of steps that have *completed* — so a
            // call made with none completed is the first step's.
            case JobEvent.MODEL_CALL -> "step " + (event.steps() + 1) + ", "
                    + count(event.modelCalls(), "model call");
            case JobEvent.TOOL_CALLED -> event.tool();
            case JobEvent.ENDED -> event.ending() + " after " + count(event.steps(), "step")
                    + ", " + count(event.modelCalls(), "model call");
            default -> event.agent();
        };
    }

    /** What a {@code waiting} line adds after the kind, so "still nothing since
     *  tool called file_read" names the tool that has not come back. */
    private static String describeSuffix(JobEvent event) {
        return JobEvent.TOOL_CALLED.equals(event.kind()) ? " " + event.tool() : "";
    }

    /** The two clocks, right-aligned so a growing gap is visible as a shape and
     *  not only as a number. */
    private static String column(Duration sinceStart, Duration sinceLast) {
        return String.format("%7s %6s  ", seconds(sinceStart), "+" + seconds(sinceLast));
    }

    private static String pad(String label) {
        return String.format("%-12s ", label);
    }

    private static String seconds(Duration elapsed) {
        return String.format("%.1fs", elapsed.toMillis() / 1000.0);
    }

    static String count(int howMany, String noun) {
        return howMany + " " + noun + (howMany == 1 ? "" : "s");
    }

    private static Duration since(long from, long now) {
        return Duration.ofNanos(now - from);
    }

    // --- arguments ---------------------------------------------------------------

    /**
     * What the command line said.
     *
     * <p>Hand-parsed and not a library, because this module's dependencies are
     * okhttp, Jackson and slf4j, and a CLI that is the acceptance test for a
     * protocol is a poor reason to make that four.
     */
    private record Options(
            String agent, String task, String project, List<Path> workspace, String serverUrl,
            boolean talk, Integer maxModelCalls) {

        static Options of(String[] args) {
            String agent = null;
            String task = null;
            String project = null;
            List<Path> workspace = List.of();
            String serverUrl = PlowshareClient.serverUrl();
            boolean talk = false;
            Integer maxModelCalls = null;

            for (int at = 0; at < args.length; at++) {
                String argument = args[at];
                switch (argument) {
                    case "--talk" -> talk = true;
                    case "--max-model-calls" ->
                            maxModelCalls = whole(value(args, ++at, argument), argument);
                    case "--workspace" -> workspace = directories(value(args, ++at, argument));
                    case "--project" -> project = value(args, ++at, argument);
                    case "--server" -> serverUrl = value(args, ++at, argument);
                    default -> {
                        if (argument.startsWith("--")) {
                            throw new IllegalArgumentException("unknown option: " + argument);
                        }
                        if (agent == null) {
                            agent = argument;
                        } else if (task == null) {
                            task = argument;
                        } else {
                            throw new IllegalArgumentException(
                                    "one agent and one task, and this is a third: " + argument);
                        }
                    }
                }
            }
            return talk
                    ? conversation(agent, task, project, workspace, serverUrl, maxModelCalls)
                    : oneRun(agent, task, project, workspace, serverUrl, maxModelCalls);
        }

        /**
         * A command line for one run, which is what this terminal has always
         * been.
         *
         * <p>{@code --max-model-calls} is refused here rather than ignored. A run
         * spends a budget built from its agent's own {@code max-model-calls} and
         * there is nothing on this path for the number to change, so accepting it
         * would let a person cap a run at four and watch it make twelve calls.
         */
        private static Options oneRun(String agent, String task, String project,
                List<Path> workspace, String serverUrl, Integer maxModelCalls) {
            if (agent == null || task == null) {
                throw new IllegalArgumentException("an agent and a task are both required");
            }
            if (maxModelCalls != null) {
                throw new IllegalArgumentException(
                        "--max-model-calls is a conversation's whole allowance and there is no"
                                + " conversation here. One run spends a budget built from the"
                                + " agent's own max-model-calls, which this cannot change. Add"
                                + " --talk, or leave it out.");
            }
            return new Options(agent, task, project, workspace, serverUrl, false, null);
        }

        /**
         * A command line for a conversation.
         *
         * <p><b>Both the agent and the allowance are optional, and neither is
         * defaulted here.</b> There is an agent this server ships for being
         * talked to, so a person who names none gets it and the first thing they
         * type is a sentence rather than a lookup. And the allowance is left
         * <em>absent</em> rather than filled in: a null goes out as no key at
         * all, so the number is the one {@code plowshare.conversations} holds on
         * the box the conversation lives on, and {@link Repl} reports what came
         * back rather than what was asked for.
         *
         * <p><b>It used to be required, and the refusal was right when it was
         * written.</b> The server had no default then, so a terminal that did not
         * ask would have had its conversation refused a hop later; the sentence
         * this class printed said as much. The server takes {@code
         * plowshare.conversations.default-budget} now, and the owner's judgement
         * that removed the same field from the console names this shape
         * specifically — "esp in REPL mode having a Budget makes no sense its
         * only when the agent takes turns doing multi-steps". {@code --talk} is a
         * REPL: what a person is about to say has no arithmetic behind it, so
         * making them state a number before they can say anything is asking for a
         * figure they can only invent. An operator's number is a different thing
         * from an invented one — {@code ConversationsProperties} carries that
         * argument — and it is still the flag's when the flag is given.
         *
         * <p><b>And a task is refused rather than taken as the first
         * utterance.</b> A conversation's utterances are typed one at a time,
         * each answered from everything before it; a sentence on the command line
         * would be the only one that never appeared after a prompt, and a person
         * who wanted one sentence answered wants the other form of this command.
         */
        private static Options conversation(String agent, String task, String project,
                List<Path> workspace, String serverUrl, Integer maxModelCalls) {
            if (task != null) {
                throw new IllegalArgumentException(
                        "--talk takes at most an agent, and this line also gives it something to"
                                + " do: '" + task + "'. In a conversation you type what you want"
                                + " said, one line at a time. For one sentence answered once, run"
                                + " it without --talk.");
            }
            return new Options(agent == null ? Repl.INTERLOCUTOR : agent, null, project,
                    workspace, serverUrl, true, maxModelCalls);
        }

        /** A count, or the refusal that says what was typed instead of one. */
        private static int whole(String value, String option) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException(
                        option + " wants a whole number of model calls and was given '" + value
                                + "'.");
            }
        }

        private static String value(String[] args, int at, String option) {
            if (at >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            String value = args[at];
            if (value.startsWith("--")) {
                // A missing argument, not a value. Without this, `--workspace
                // --project pay` lends a directory literally named "--project"
                // and silently drops the project, which is two wrong things a
                // person would have to notice from the output.
                throw new IllegalArgumentException(option + " needs a value and was given the"
                        + " option " + value + "; a value starting with -- is a missing argument");
            }
            return value;
        }

        /**
         * The directories this machine lends, <b>as the person typed them</b>.
         *
         * <p>Not resolved here: {@code Workspace} keeps what was typed and
         * {@code FileAccess} canonicalises both sides where the comparison
         * happens, which is the only correct place for it and is argued at length
         * over there.
         */
        private static List<Path> directories(String commaSeparated) {
            List<Path> roots = new ArrayList<>();
            for (String each : commaSeparated.split(",")) {
                if (each.isBlank()) {
                    continue;
                }
                Path root = Path.of(each.trim());
                if (!Files.isDirectory(root)) {
                    // Checked here and nowhere else in the file path: this is a
                    // usability check on a command line and not a containment
                    // decision, which belongs to FileAccess. Worth making
                    // because Workspace.set validates nothing, so `--workspace
                    // /typo` used to announce "lending [/typo]" and then run
                    // against a session that lends nothing — precisely the
                    // silent smaller capability the branch above exists to
                    // prevent, arriving through the branch that was supposed to
                    // prevent it.
                    throw new IllegalArgumentException(
                            "--workspace names " + root + ", which is not a directory on this"
                                    + " machine. A run cannot be lent a path that is not there.");
                }
                roots.add(root);
            }
            return List.copyOf(roots);
        }
    }
}
