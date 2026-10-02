package io.aeyer.plowshare.client;

import io.aeyer.plowshare.client.files.Rooting;
import io.aeyer.plowshare.client.files.Workspace;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This process's half of a presence: the one session it holds, and the project
 * that session declares it roots.
 *
 * <h2>What it is for</h2>
 *
 * <p>{@code cli.Plowshare} builds a {@link SessionClient} from a command line and
 * holds it for one run or one conversation. {@code PlowshareClient} is a
 * long-lived stdio process that is spawned before anybody has said what it should
 * serve, so the same construction has to happen <em>later</em> and has to be able
 * to happen again. This class is that: the same three pieces {@code cli.Plowshare}
 * assembles — a {@link Workspace}, a {@link Rooting}, a {@link SessionClient} —
 * behind something a tool call can move.
 *
 * <h2>Nothing is dialled until somebody says what to serve</h2>
 *
 * <p><b>Rooting is explicit and user-initiated, never an implicit working
 * directory.</b> A harness that spawns this process must not have whatever
 * directory it happened to be launched in lent on its behalf — that would be the
 * leash granting itself. So a fresh {@code ClientPresence} holds no channel, and
 * a run started through it carries no session and reaches only what the server
 * itself can see, which is exactly what the MCP client did before this existed.
 *
 * <p>That also keeps {@code PlowshareClient}'s "no reachability check at startup"
 * true rather than merely unbroken: the first thing that touches the network is a
 * tool call somebody made, and a server that is not up costs that one call rather
 * than every tool in the list.
 *
 * <h2>Changing what is rooted is a reconnect, and the order is the decision</h2>
 *
 * <p>A presence is declared on the file channel's upgrade —
 * {@code ?session=&machine=&root=&project=}, all three or none — because a
 * window in which a session holds a disk and roots nothing is a window in which a
 * run silently gets the smaller set. There is therefore no frame that changes it,
 * and {@link #root} closes what this process held and dials again.
 *
 * <p><b>Closed first, then dialled</b>, which is not the safer-looking order. The
 * server refuses a second live session claiming a project another session already
 * roots; a client that opened the new socket before dropping the old would be
 * refused <em>by itself</em> the moment somebody re-rooted the same project at a
 * corrected path, which is the commonest reason to call this twice. The cost is
 * paid explicitly and it is the right way round: a re-rooting that then fails to
 * attach leaves this process rooting nothing, which is a state it says out loud,
 * rather than rooting the old place under a claim the server has just refused.
 *
 * <h2>A failure here is a degraded client, never a dead one</h2>
 *
 * <p>Every failure is reported to whoever asked and nothing else changes. This
 * process goes on answering every other tool, because a client that stopped
 * showing a model its tools would read as an archive that does not exist.
 */
public final class ClientPresence implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ClientPresence.class);

    /** What this process holds, or null while it roots nothing.
     *
     *  <p>One immutable value behind one field rather than three fields, for
     *  {@link Workspace}'s reason: a reader is a tool call on another thread, and
     *  fields written separately are a window in which the session is the new one
     *  and the rooting is still the old one — a run reaching a machine under a
     *  name nobody claimed. */
    private record Held(SessionClient session, Rooting rooted) {}

    private final ServerClient server;
    private final String token;
    private final Duration patience;

    private volatile Held held;

    /**
     * @param server where the runs go, and — through {@link ServerClient#baseUrl}
     *     — where the file channel dials. One setting for one server, which is
     *     {@link SessionClient}'s rule and its reason
     * @param token the access token both upgrades present, or null for none. The
     *     same one {@code PlowshareClient.accessToken()} resolves, passed rather
     *     than read off {@code server} so no live credential sits on an interface
     */
    public ClientPresence(ServerClient server, String token) {
        this(server, token, SessionClient.ATTACH_PATIENCE);
    }

    /**
     * @param patience how long {@link #root} waits for the two upgrades to
     *     settle. {@link SessionClient#ATTACH_PATIENCE} everywhere in main; a
     *     parameter so a test can bound its own wait rather than inherit one
     */
    ClientPresence(ServerClient server, String token, Duration patience) {
        this.server = Objects.requireNonNull(server, "server");
        this.token = token;
        this.patience = Objects.requireNonNull(patience, "patience");
    }

    /** What this process declares it roots right now, or null. */
    public Rooting rooted() {
        Held now = held;
        return now == null ? null : now.rooted();
    }

    /** Whether this machine is answering file requests for its session right
     *  now. False both for a process that roots nothing and for one whose
     *  channel has gone. */
    public boolean serving() {
        Held now = held;
        return now != null && now.session().providing();
    }

    /**
     * The session id a run started this instant should carry, or null if this
     * process roots nothing.
     *
     * <p>Null is an ordinary answer and not a failure: a client that has not been
     * asked to serve anything starts runs that reach the server's own filesystems,
     * which is what every MCP client did before presence.
     *
     * @throws IllegalStateException if this process roots something and its
     *     channel is down. {@link SessionClient#requireProviding} owns that
     *     sentence — a run carrying the id of a socket that has gone would
     *     execute against the server's own filesystems and report nothing amiss,
     *     because the server accepts a session id it has never seen attached
     */
    public String sessionForRun(String agent) {
        Held now = held;
        if (now == null) {
            return null;
        }
        now.session().requireProviding(agent);
        return now.session().id();
    }

    /**
     * Declare that this machine serves {@code project}, whose files are at
     * {@code root}.
     *
     * <p>Synchronised because two tool calls arriving together would otherwise
     * each close the other's session and leave whichever finished second holding
     * a channel the first had already given back.
     *
     * @param root the directory on <b>this</b> machine. Resolved to a real,
     *     absolute path by {@link Rooting#of}, because it is one component of an
     *     identity and two spellings of one place must not be two projects
     * @param project the friendly name, the same one a run names
     * @return what is now declared
     * @throws IOException if the channel could not be opened. This process then
     *     roots nothing — see the class note on the order — and every other tool
     *     goes on working
     */
    public synchronized Rooting root(Path root, String project) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(project, "project");

        Held previous = held;
        // Given back before the new claim goes out, and the field is cleared
        // before the socket is: a reader between the two would otherwise be told
        // this process roots a project whose channel is closing.
        held = null;
        if (previous != null) {
            log.info("No longer rooting '{}'; reconnecting.", previous.rooted().project());
            previous.session().close();
        }

        Rooting rooting = Rooting.of(root, project);
        Workspace workspace = new Workspace();
        /*
         * THE ONE DIRECTORY IT ROOTS, AND THE WHOLE OF WHAT IT LENDS -- AND THE
         * SECOND HALF IS A CHOICE ABOUT THIS TOOL, NOT A FACT ABOUT WHAT IS
         * NAMEABLE.
         *
         * This used to say that "a client that lent more than it rooted would be
         * offering files no run could name". THAT CLAIM IS FALSE and was false
         * when it was written. `FileRequest.ROOTS` is answered with
         * `FileReply.listed(id, List<String>)` -- a list -- `FileAccess` has been
         * plural since it was written, and `Plowshare.main` already lends every
         * root `--workspace a,b` parses while rooting only the first. A run names
         * a lent root by asking `file_roots` and using what comes back; nothing
         * about naming requires a root to be THE root.
         *
         * `cli/Plowshare.rooting` had the right version of this all along --
         * "the rest are still lent and still reachable; what the first one fixes
         * is where the project IS" -- and the two comments were contradicting
         * each other in the same tree, with this one doing the work of a rule.
         * The rule is Plowshare's, and it is the general one.
         *
         * WHAT IS LEFT IS ABOUT `client_root_project_here` AND NOTHING ELSE.
         * That tool takes one `path` because its whole proposition is "here" --
         * the directory the person is standing in -- and because DECLARING and
         * LENDING are different acts with different failure modes: a wrong root
         * breaks the presence and the project's canonical name, while a wrong
         * lent root merely reaches nothing. The server side of this slice split
         * exactly those two apart, into `project_define` and `project_lend`, so
         * that lending could not disturb identity; giving this one method a list
         * would collapse them back together on the client in the same breath.
         *
         * THE COST, SAID OUT LOUD BECAUSE IT IS REAL. A project rooted through
         * this tool cannot be lent a second directory at all -- so a person whose
         * agent cannot read `.github/` on a client-rooted project has no verb for
         * it, which is precisely the gap `project_lend` closes for a project
         * rooted on the server. `plowshare --workspace a,b --project p` expresses
         * it at process start and is the only thing that does. That is a
         * narrower version of this slice, not a decision that it is unnecessary,
         * and it is left open rather than half-built here.
         */
        workspace.set(List.of(root));

        SessionClient session = new SessionClient(server, workspace, token, rooting);
        try {
            session.attach(patience);
        } catch (IOException | RuntimeException notAttached) {
            // Closed rather than left for the caller: a half-open session nobody
            // holds a reference to is two sockets and a thread pool that live as
            // long as the process.
            session.close();
            throw notAttached;
        }
        held = new Held(session, rooting);
        log.info("Rooting '{}' at {} on {} as session '{}'.",
                rooting.project(), rooting.root(), rooting.machine(), session.id());
        return rooting;
    }

    /** Give the presence back and close the channel. Idempotent, so the
     *  try-with-resources in {@code PlowshareClient.main} and an explicit close
     *  do not fight. */
    @Override
    public synchronized void close() {
        Held now = held;
        held = null;
        if (now != null) {
            now.session().close();
        }
    }
}
