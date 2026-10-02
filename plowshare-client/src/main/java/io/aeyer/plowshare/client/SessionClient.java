package io.aeyer.plowshare.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.client.files.ChannelClient;
import io.aeyer.plowshare.client.files.ClientEnforcer;
import io.aeyer.plowshare.client.files.ImageUploads;
import io.aeyer.plowshare.client.files.Rooting;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.JobEvent;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One session, held from the terminal end: an id, both roles attached to it, and
 * a view of what the jobs submitted under it are doing.
 *
 * <h2>This is where a session id starts existing</h2>
 *
 * <p>The design spec lists "a session id is a bearer capability" among the things
 * that are true today, and notes that one bullet under it was weaker than it
 * looked: an earlier draft said "ids are UUIDs, so collision is not the concern"
 * and <b>nothing established that</b>. It still does not, server-side, and
 * deliberately — {@code SessionRegistry} requires only that an id be non-blank,
 * {@code ChannelClient} takes whatever id it is handed, and {@code
 * FileChannelTest} attaches under the plain words {@code closing}, {@code wedged}
 * and {@code crowd}, all of which stay valid. <b>{@link #id} is minted with {@link
 * UUID#randomUUID()}, and that is what turns the bullet from an aspiration into a
 * property of every session this client opens.</b> It is a
 * property of the client and not of the protocol, which is still the honest
 * place for it: a server cannot tell a well-minted id from a guessed one, so the
 * only party that can make guessing pointless is the party that mints.
 *
 * <p><b>This paragraph used to end "while the server has no authentication", and
 * slice 4 built it.</b> The server's {@code AuthFilter} gates every {@code /v1}
 * path, both of this class's sockets included, so the id is no longer the only
 * thing between a stranger and a session — an access token is, and this class
 * presents one when it is given one (see the three-argument constructor).
 *
 * <p>What that does <em>not</em> buy is stated so nobody reads more into it.
 * <b>Authentication here is operator-level and not per-session</b>: holding the
 * token means being the operator, and the operator may attach to any session by
 * name. An id that has been used is still as good as a password to whoever sees
 * it — that is why {@code cli.Plowshare} prints eight characters of one and not
 * the whole — and the barriers in front of the listener role are now two
 * independent ones rather than one: {@code EventChannelConfig}'s deliberate
 * absence of {@code setAllowedOrigins} for a page, and the filter for everyone.
 * A deployment that exposes the port to a network needs the second of those left
 * switched on.
 *
 * <h2>Both roles, and then a run — in that order, loudly</h2>
 *
 * <p>The two roles are independent sockets and the server treats a session with
 * neither, either or both as ordinary. That is right for the server: {@code
 * AgentController} says in as many words that a body naming a session nothing has
 * attached to submits and runs, because refusing an unattached id would make a
 * client race its own upgrade. <b>The consequence is that a run submitted a
 * moment too early gets the local provider alone, executes, and reports nothing
 * amiss</b> — the smaller-capability-by-accident that this slice's nullable
 * session id exists to keep deliberate. Nowhere on the server can see it happen.
 * So:
 *
 * <ul>
 *   <li>{@link #attach} does not return until <b>both</b> roles are up, and
 *       throws if either is not. A failed attach closes what it opened rather
 *       than leaving a half session behind;
 *   <li>{@link #submit} refuses while the <b>file-provider</b> role is not
 *       attached <em>right now</em>, and its message distinguishes a socket that
 *       never opened from one that has since gone.
 * </ul>
 *
 * <p><b>The two roles are guarded asymmetrically, and that is the decision rather
 * than an inconsistency.</b> Losing the provider changes what a run can reach and
 * nothing says so, which is a wrong answer; losing the listener costs the view
 * and nothing else, and the outcome is still behind {@code GET
 * /v1/jobs/&#123;id&#125;}. Refusing to submit because nobody is watching would
 * be this client inventing a rule the protocol does not have — the spec's table
 * lists "provider only" as a supported row. So a lost listener is reported
 * through {@link #listening} and does not stop anything.
 *
 * <h2>No fourth HTTP client</h2>
 *
 * <p>The listener socket is dialled through {@link ChannelClient#dial}, on the
 * {@code OkHttpClient} that class already holds. {@code
 * InvariantsTest.an_http_client_is_held_by_exactly_three_files_in_main} names the
 * three holders by name, and its own message says what a fourth would cost: "a
 * fourth place configuring its own timeouts, retries and connection pool for the
 * same endpoints". Two sockets to one host on one pool is what that guard is
 * asking for, so reusing it is the design and not a way around a test.
 */
public final class SessionClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SessionClient.class);

    /** Matches {@code EventChannelHandler.PATH} on the server, spelled without a
     *  leading slash the way {@link ChannelClient#PATH} is. A separate literal in
     *  a separate module for that class's reason — this is a URL this process
     *  dials and the server's is a route it publishes — and a mismatch fails at
     *  the upgrade with a 404, which {@link #attach} reports rather than
     *  swallows. */
    public static final String EVENTS_PATH = "v1/events";

    /**
     * How many events this client will hold for a renderer that has fallen
     * behind.
     *
     * <p><b>The same number as {@code EventChannelHandler.PENDING}, and equality
     * is the argument.</b> That queue is one per attached listener and bounds the
     * server's memory; this one only hands frames from okhttp's reader thread to
     * whatever is drawing them. A smaller number here would make this client the
     * drop point for a stream the server was willing to carry, and a larger one
     * would buffer past the point where the server had already given up. A
     * separate literal rather than a shared constant because the two live in
     * modules that ship separately, and a divergence costs a different drop point
     * rather than a wrong answer.
     *
     * <p>The difference between the two drops: the server's is silent by design —
     * a droppable stream with no sequence numbers, so a listener is not told. This
     * one is counted, and {@link #dropped} is what lets a renderer say the view
     * is incomplete rather than quietly showing less than happened.
     */
    public static final int PENDING = 256;

    /**
     * How long {@link #attach} waits for the two upgrades to settle.
     *
     * <p><b>{@link HttpServerClient#CONNECT_TIMEOUT}'s number, read from it
     * rather than restated</b>, and for the reason written there: "nothing is
     * listening" should be a fast, legible answer rather than something a person
     * times out on. It is the same question about the same host over the same
     * pool, and a second five-second constant with a paraphrase beside it is how
     * a number stops having a reason. It is also rarely the bound that matters —
     * a refused upgrade settles in milliseconds, because {@link
     * ChannelClient#awaitOpen} waits for an answer and not for a duration.
     */
    public static final Duration ATTACH_PATIENCE = HttpServerClient.CONNECT_TIMEOUT;

    private final String id = UUID.randomUUID().toString();

    private final ServerClient server;
    private final ChannelClient files;

    private final ObjectMapper json = new ObjectMapper()
            // A server that grows a field must not break a client that has not
            // been rebuilt, which is ChannelClient's and HttpServerClient's
            // reason and is sharper here: JobEvent.kind is a String and not an
            // enum precisely so a later server's fifth kind binds and renders.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final BlockingQueue<JobEvent> events = new ArrayBlockingQueue<>(PENDING);
    private final AtomicInteger dropped = new AtomicInteger();
    private final CountDownLatch listenerSettled = new CountDownLatch(1);

    private volatile WebSocket listener;
    private volatile ChannelClient.State listenerState = ChannelClient.State.UNOPENED;
    private volatile String listenerRefusal;
    private volatile int listenerStatus;
    private volatile boolean attachAttempted;

    /**
     * @param server where the runs go. <b>The socket URL is taken from {@link
     *     ServerClient#baseUrl()} rather than passed separately</b>, which is
     *     what that method is for: two settings naming one server is how a client
     *     submits a run to one machine and offers its files to another
     * @param workspace what this machine offers, which a human sets and no model
     *     may widen. An empty one is an ordinary state — a session that watches
     *     runs and lends no files
     */
    public SessionClient(ServerClient server, Workspace workspace) {
        this(server, workspace, null);
    }

    /**
     * @param server where the runs go; see the two-argument constructor
     * @param workspace what this machine offers
     * @param token an access token for the two upgrades, or null for none.
     *     <b>Passed separately rather than read off {@code server}</b>, and that
     *     is the decision: a {@code token()} on {@link ServerClient} would put a
     *     live credential on an interface every implementation has to hand back,
     *     which is a getter somebody eventually logs. The cost is that a caller
     *     holding one token names it twice, and the failure of naming it once is
     *     loud — {@link #attach} refuses with the 401 in its message.
     */
    public SessionClient(ServerClient server, Workspace workspace, String token) {
        this(server, workspace, token, null);
    }

    /**
     * @param server where the runs go; see the two-argument constructor
     * @param workspace what this machine offers
     * @param token an access token, or null — see the three-argument constructor
     * @param rooted the project this session declares it roots, or null. <b>The
     *     one thing this client asserts about itself before it asserts anything
     *     about files</b>: a run in that project reaches this machine whichever
     *     terminal it was submitted from, and a run in a project this session
     *     does not root does not reach it at all, however much it is lending.
     *
     *     <p>Refused at the upgrade if another live session already roots it,
     *     which {@link #attach} reports the way it reports a 401 — a conflict is
     *     two clients running and the person has to close one of them
     */
    public SessionClient(
            ServerClient server, Workspace workspace, String token, Rooting rooted) {
        this.server = Objects.requireNonNull(server, "server");
        Objects.requireNonNull(workspace, "workspace");
        this.files = new ChannelClient(
                server.baseUrl(), id, new ClientEnforcer(workspace, uploads(server, rooted)),
                token, rooted);
    }

    /**
     * How a picture in this session's workspace gets a name, and which tier it
     * gets it in.
     *
     * <h2>The tier is this session's rooting, and there is nothing else it could
     * be</h2>
     *
     * <p>An {@code agent_run} resolves an image id against <em>its own run's</em>
     * home and no other — {@code AgentRunTool} says so and makes it a parameter
     * rather than an argument a model can name — so a picture filed in the wrong
     * tier yields an id that is real, unguessable and useless. This session
     * therefore files under the project it declared it roots.
     *
     * <p><b>That is the right answer because of how a file request gets here at
     * all.</b> {@code AgentsConfig.runProviders} picks the machine two ways: a
     * run in a named project reaches the session that {@code PresenceRegistry}
     * says roots it, and a run in the global tier reaches the session it was
     * submitted under. So a session with a {@link Rooting} is only ever asked
     * for files by runs in that project, and a session without one is only ever
     * asked by runs in the global tier — which is what a null project means to
     * {@code POST /v1/images}.
     *
     * <p><b>{@code a_picture_in_the_rooted_workspace_is_uploaded_under_that_project}
     * is what holds this, and it exists because nothing did.</b> Replacing the
     * line below with a plain {@code null} left all 3718 tests green: every
     * assertion about a picture was against a stubbed uploader, which cannot see
     * which tier it was called for. An argument this long with no instrument
     * under it is the shape this project deletes rather than keeps.
     *
     * <p><b>The seam it leaves, stated rather than discovered:</b> a run in the
     * <em>global</em> tier submitted under a session that also roots a project
     * reaches this same client, and its picture is filed under that project
     * while the run resolves against global. The id is then real and resolves
     * nowhere the run can see. Nothing here can fix it: {@code FileRequest}
     * carries an op, a path and a window, and never the home of the run that
     * asked — so the client cannot know, and the two candidate answers are wrong
     * for opposite halves of the traffic. §6a does not raise this case; the
     * fix, if it is ever worth one, is the home travelling on the frame.
     */
    private static ImageUploads uploads(ServerClient server, Rooting rooted) {
        String project = rooted == null ? null : rooted.project();
        return (filename, bytes) -> {
            try {
                return server.uploadImage(project, filename, bytes).id();
            } catch (ServerClient.ServerError refused) {
                // Translated here rather than caught in the enforcer, so that
                // `client.files` goes on knowing nothing about the archive API —
                // ChannelClient in that package already declines it, taking a
                // base URL rather than a client. The status is what crosses,
                // because three refusals have three remedies and the sentences
                // are ClientEnforcer's to write.
                throw new ImageUploads.Unnameable(refused.status(), refused.getMessage());
            }
        };
    }

    /** The id both roles attached under and every run submitted here names. */
    public String id() {
        return id;
    }

    /** Where this session's server is, for messages that have to say so. */
    public String serverUrl() {
        return server.baseUrl();
    }

    /**
     * Open both sockets and wait for both, or fail saying which did not.
     *
     * <p>The two are dialled together and then waited on against one deadline, so
     * {@code patience} is what a caller waits in the worst case and not twice it.
     *
     * @param patience how long to wait for the upgrades to settle. {@link
     *     HttpServerClient#CONNECT_TIMEOUT} by default at the call sites, for the
     *     reason written there: "nothing is listening" should be a fast, legible
     *     answer rather than something a person times out on
     * @throws IOException if either role failed to attach. Nothing is left
     *     attached: a client holding one role and not the other would submit runs
     *     it could not watch, or watch runs it could not lend files to, through no
     *     decision anybody made
     */
    public void attach(Duration patience) throws IOException {
        Objects.requireNonNull(patience, "patience");
        if (attachAttempted) {
            throw new IllegalStateException(
                    "session '" + id + "' has already tried to attach. A session client mints one"
                            + " id and attaches once; open another one rather than reusing this,"
                            + " because a second attach under the same id would displace the"
                            + " sockets this one is holding.");
        }
        attachAttempted = true;

        files.open();
        listener = files.dial(EVENTS_PATH, new Frames());

        long deadline = System.nanoTime() + patience.toNanos();
        boolean providing;
        boolean listening;
        try {
            providing = files.awaitOpen(remaining(deadline));
            listening = listenerSettled.await(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS)
                    && listenerState == ChannelClient.State.OPEN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("interrupted while attaching session '" + id + "'");
        }

        if (providing && listening) {
            log.info("Session '{}' attached to {} in both roles.", id, server.baseUrl());
            return;
        }
        String why =
                role("the file-provider", ChannelClient.PATH, files.state(), files.refusal(), 0)
                        + "; " + role("the listener", EVENTS_PATH, listenerState, listenerRefusal,
                                listenerStatus);
        close();
        throw new IOException("session '" + id + "' could not attach to " + server.baseUrl()
                + " within " + patience + ": " + why);
    }

    /** Whether this machine is answering file requests for the session right
     *  now. The role whose absence changes what a run can reach. */
    public boolean providing() {
        return files.isOpen();
    }

    /** Whether anything is arriving on the event socket right now. The role whose
     *  absence costs the view and not the run. */
    public boolean listening() {
        return listenerState == ChannelClient.State.OPEN;
    }

    /** How many events this client had nowhere to put. Nonzero means the view is
     *  incomplete and a renderer should say so; the run is unaffected and its
     *  record is the job endpoint's. */
    public int dropped() {
        return dropped.get();
    }

    /**
     * Start a run under this session.
     *
     * <p>Refused while the file-provider role is down, because the server will
     * not refuse it and nothing downstream will notice: the run would execute
     * against the filesystems the server itself can see and answer as if that
     * were what was asked for.
     *
     * @param conversation the conversation this run is a turn in, or {@code null}
     *     for a run that is not one. <b>The guard above applies unchanged to a
     *     turn</b>, and it matters more there than it does to a one-shot run: an
     *     utterance that reached only the server's own filesystems would answer
     *     about a tree the person cannot see, and the next utterance would be
     *     answered from a history that already holds that answer
     * @return the job id, to be polled through {@link #job}
     * @throws IllegalStateException if the provider role is not attached. The
     *     message says which of the two ways that is true, because a socket that
     *     never opened and one that has gone away send a reader to different
     *     places
     */
    public String submit(String agent, String task, String project, String conversation)
            throws IOException {
        requireProviding(agent);
        String job = server.run(agent, task, project, id, conversation).id();
        log.info("Session '{}' started {} as job {}.", id, agent, job);
        return job;
    }

    /**
     * Refuse unless this machine is answering file requests right now.
     *
     * <p>{@link #submit}'s own guard, extracted rather than copied because it is
     * needed by a caller that does not submit through this class: the MCP
     * client's {@code agent_run} starts a run over HTTP and only wants the
     * session id to put on it, and a second spelling of this refusal is how one
     * of the two would eventually stop saying which way the socket was down.
     *
     * @param agent what the run would have been, so the sentence names the thing
     *     that did not start rather than only the session that could not carry it
     * @throws IllegalStateException if the provider role is not attached. The
     *     message says which of the two ways that is true, because a socket that
     *     never opened and one that has gone away send a reader to different
     *     places
     */
    public void requireProviding(String agent) {
        // One read, decided on and explained from. Asking files.state() twice
        // would let a socket that opened in between produce a refusal whose
        // sentence says the socket is fine — a guard describing a situation it
        // did not fire on.
        ChannelClient.State provider = files.state();
        if (provider != ChannelClient.State.OPEN) {
            throw new IllegalStateException(
                    "refusing to start '" + agent + "' under session '" + id + "': "
                            + providerFault(provider) + ". A run submitted now would reach only"
                            + " the filesystems the server itself can see, and nothing would say"
                            + " so — the server accepts a session id it has never seen attached,"
                            + " because refusing one would make every client race its own"
                            + " upgrade.");
        }
    }

    /** How a run is going, and how it ended once it has. <b>The only contractual
     *  record of a run</b>: the event stream is droppable and this is not, so a
     *  client that has lost its listener asks here rather than concluding
     *  anything. */
    public ServerClient.JobStatus job(String jobId) throws IOException {
        return server.job(jobId);
    }

    /**
     * The next lifecycle event, or null if none arrived in time.
     *
     * <p>Null and not an exception, on {@link BlockingQueue#poll}'s reading:
     * nothing arriving is the ordinary case on a stream that carries a handful of
     * events across a run lasting minutes, and it is exactly the case a renderer
     * turns into "still working, nothing for 31s".
     */
    public JobEvent nextEvent(Duration wait) throws InterruptedException {
        Objects.requireNonNull(wait, "wait");
        return events.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        WebSocket watching = listener;
        if (watching != null) {
            // Before the channel, not after: both sockets are on that client's
            // dispatcher and connection pool, and closing it first would take
            // this one's close frame with it.
            watching.close(1000, "the terminal is shutting down");
        }
        files.close();
    }

    // --- plumbing --------------------------------------------------------------

    private Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    /** Why the provider role is unusable, in the words of the state it is in.
     *
     *  <p>Takes the state rather than reading it, so the sentence describes the
     *  same observation the refusal was decided on. */
    private String providerFault(ChannelClient.State state) {
        return switch (state) {
            case UNOPENED -> "the file-provider role has never been attached — attach() has not"
                    + " been called, or has not finished";
            case CLOSED -> "the file-provider socket was open and has since closed";
            case REFUSED -> "the file-provider socket was refused (" + files.refusal() + ")";
            // Unreachable: the only caller asks about a state it has already
            // found not to be OPEN. Present because the switch is exhaustive
            // over the enum, and worded so it could not read as a refusal even
            // if something ever did reach it.
            case OPEN -> "the file-provider socket is open, and this sentence is a bug";
        };
    }

    /** One role's standing, for the sentence {@link #attach} throws. */
    private static String role(
            String name, String path, ChannelClient.State state, String refusal, int status) {
        return switch (state) {
            case OPEN -> name + " role attached at " + path;
            case UNOPENED -> name + " role never answered at " + path;
            case CLOSED -> name + " role attached at " + path + " and closed again";
            case REFUSED -> name + " role was refused at " + path
                    + (status == 0 ? " (" + refusal + ")" : " with HTTP " + status)
                    + (status == 404
                            ? " — a 404 on that path is a server with no such channel at all,"
                                    + " which is every server built before sessions existed"
                            : "");
        };
    }

    /** okhttp's callbacks for the listener socket. The file channel's own are
     *  {@code ChannelClient.Frames}; this one only reads. */
    private final class Frames extends WebSocketListener {

        @Override
        public void onOpen(WebSocket socket, Response response) {
            listenerState = ChannelClient.State.OPEN;
            listenerSettled.countDown();
            log.info("Watching session '{}'.", id);
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            JobEvent event;
            try {
                event = json.readValue(text, JobEvent.class);
            } catch (IOException notAnEvent) {
                // Dropped, and the watch goes on. ChannelClient settled this
                // shape for the other socket and it is milder here: there is
                // nothing to answer, nobody waiting on an answer, and the run's
                // record is the job endpoint's. A watch that ended on one
                // unreadable frame would report a healthy run as a lost one. The
                // exception's type and never its message, for refusal()'s reason.
                log.warn("The server sent a frame this client could not read as an event: {}",
                        notAnEvent.getClass().getName());
                return;
            }
            if (!events.offer(event)) {
                // Never blocks: this is okhttp's reader thread, and parking it
                // would stop the socket rather than slow the renderer.
                dropped.incrementAndGet();
                log.debug("Dropped a '{}' for job {}: {} events are already waiting.",
                        event.kind(), event.job(), PENDING);
            }
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            listenerState = ChannelClient.State.CLOSED;
            listenerSettled.countDown();
            // MEASURED on okhttp 4.12 and recorded on ChannelClient: without
            // completing the handshake here, onClosed never arrives and the
            // half-closed socket is held for the life of the process.
            log.info("The server closed the event channel ({} {}).", code, reason);
            socket.close(1000, null);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable failure, Response response) {
            listenerRefusal = failure.getClass().getName();
            listenerStatus = response == null ? 0 : response.code();
            listenerState = listenerState == ChannelClient.State.OPEN
                    ? ChannelClient.State.CLOSED
                    : ChannelClient.State.REFUSED;
            listenerSettled.countDown();
            log.warn("The event channel for session '{}' failed{}: {}", id,
                    listenerStatus == 0 ? "" : " (" + listenerStatus + ")", listenerRefusal);
        }
    }
}
