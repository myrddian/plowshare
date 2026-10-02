package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.client.SessionClient;
import io.aeyer.plowshare.client.files.ChannelClient;
import io.aeyer.plowshare.protocol.JobDelta;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.Session;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The listener socket, over a real socket on loopback.
 *
 * <h2>What only a real port can measure</h2>
 *
 * <p>{@code SessionRegistryTest} settles what attaching and displacing mean,
 * against a two-field fake attachment and no Spring at all. What is left over is
 * everything between a TCP connection and that registry: that the upgrade
 * reaches this handler on the path a client would dial, that the id comes off
 * the URI, that a refused connection is refused rather than accepted and
 * ignored, that the container's close callback is what detaches, and that a
 * foreign origin never gets that far. None of those can be faked without faking
 * the thing being measured.
 *
 * <h2>Loopback and nothing else</h2>
 *
 * <p>{@code --server.port=0} as a command-line argument rather than as
 * properties: {@code FileChannelTest} measured that {@code
 * SpringApplicationBuilder.properties(...)} supplies <em>default</em> properties
 * and loses to {@code application.yml}, which names a fixed port. This file
 * inherits that finding rather than re-deriving it, and asserts nothing about
 * it — {@link #port} being whatever was free is the whole of what it needs.
 *
 * <h2>No events are asserted on here, and events now exist</h2>
 *
 * <p>This file is the socket, the attachment and the lifecycle. <b>The reason it
 * asserts nothing about a frame going server-to-client has changed, and the
 * heading is rewritten rather than left to be read as still true.</b> It used to
 * be that no producer wrote one, so a payload asserted here would have been a
 * fixture that was accidentally correct on the day it was written. Task 5 built
 * the producer; the division of labour is now deliberate. {@code JobEventTest}
 * owns what a job says and what reaches a listener, including the frame's JSON
 * and the delivery that cannot be measured over a real socket at all — a
 * listener that stops reading. What is left here is everything between a TCP
 * connection and the registry, which is what only a real port can measure.
 *
 * <h2>Responses are asserted on here, and that division is different again</h2>
 *
 * <p>The heading above is about <em>pushes</em>, and it still holds. What this
 * channel gained since is the other direction: a client may now send a request
 * frame and be answered on the same socket. The answer belongs to this file
 * because the thing being measured is this channel — that a frame reaching the
 * socket reaches {@link FrameRouter}, that the {@code Outcome} it hands back
 * becomes an envelope carrying the request's own {@code id}, and that the write
 * discipline the class was built around survives having a second kind of frame
 * pushed through it. <em>Which</em> {@code Outcome} a given frame type produces
 * stays {@code FrameRouterTest}'s question.
 *
 * <p>Three of those tests drive the handler over a fake socket rather than over
 * the port, and the comment above them says why: a client that will not drain
 * cannot be asked for over a real connection. That is the same reason {@code
 * JobEventTest} has a fake, applied to this file's own subject.
 *
 * <h2>Nothing here is authenticated, and that is not evidence about the gate</h2>
 *
 * <p>This file's {@code Wiring} does not import {@code AuthConfig}, so {@code
 * AuthFilter} is registered in no context it builds and every request and every
 * upgrade below is anonymous. <b>Its green therefore says nothing about whether
 * the server has a gate at all</b> — measured, by making {@code
 * AuthFilter.doFilter} refuse every request unconditionally and running this
 * class: still green. The property that {@code /v1} is closed is held by {@code
 * AuthFilterTest}, whose {@code Wiring} imports the production {@code
 * AuthConfig} and whose {@code
 * every_route_this_application_publishes_is_gated_or_deliberately_open}
 * enumerates the tree; a route added here is covered by that scan and not by
 * anything in this file.
 */
class EventChannelTest {

    /** How long a poll waits before calling a registry state wrong. Generous:
     *  every wait here is for something that happens in milliseconds, and the
     *  number is only large enough that a loaded machine does not fail the
     *  suite. */
    private static final long PATIENCE_MILLIS = 10_000;

    /**
     * How long a state is watched to see whether something takes it away.
     *
     * <p>The other direction from {@link #PATIENCE_MILLIS}, and it cannot be
     * generous for free: it is spent in full by every test that uses it, so it
     * is the smallest window that is still worth watching.
     *
     * <p><b>It is not what catches the detach mutant, and saying so is the
     * point.</b> With the handler's close detaching whatever the role holds
     * rather than its own socket, the role is emptied within ten milliseconds of
     * the displaced socket closing — before this window opens, which is why all
     * three displacement tests fail at {@link #awaitListenerOtherThan} instead.
     * What this window holds is the weaker claim it can: that nothing takes the
     * attachment away in the half-second after the test stopped looking, which
     * is what makes {@code
     * a_frame_that_is_not_an_envelope_is_refused_and_the_socket_stays_attached}
     * an assertion rather than a coincidence of timing.
     */
    private static final long SETTLE_MILLIS = 500;

    private static ConfigurableApplicationContext context;

    /** The one {@link EventChannelConfig} built, so this file reads the registry
     *  the production wiring attaches to rather than a copy of it. */
    private static SessionRegistry registry;

    private static int port;
    private static OkHttpClient http;

    private final List<Watching> opened = new ArrayList<>();

    @BeforeAll
    static void startTheServer() {
        context = new SpringApplicationBuilder(Wiring.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0");
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
        registry = context.getBean(SessionRegistry.class);
        http = new OkHttpClient.Builder().build();
    }

    @AfterAll
    static void stopTheServer() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        context.close();
    }

    @AfterEach
    void closeWhatThisTestOpened() {
        for (Watching open : opened) {
            open.close();
        }
        opened.clear();
    }

    // --- attaching -----------------------------------------------------------

    @Test
    void connecting_attaches_a_listener_and_leaves_every_other_role_empty() throws Exception {
        String id = "watching";

        connect(id);

        Session session = awaitListener(id);
        WebSocketSession attached = session.attached(Role.LISTENER, WebSocketSession.class)
                .orElseThrow();
        assertTrue(attached.isOpen(),
                "what is attached is the live socket, which is what task 5 will write events"
                        + " to — a closed one in the role would be a listener nothing reaches");
        // A SESSION EXISTING IS NOT A ROLE BEING ATTACHED, which is Role's own
        // rule and the one the browser depends on: a client that can only watch
        // must not look like a client that can serve files, or slice 4 hands
        // every page a file provider it cannot implement.
        assertFalse(session.has(Role.FILE_PROVIDER),
                "opening a listener attaches a listener and nothing else");
        assertEquals(id, session.id());
    }

    @Test
    void a_listener_attaching_leaves_a_file_provider_on_the_same_session_alone() throws Exception {
        // The independence of the two roles, from the socket end. The file
        // channel does attach to this registry now, and the provider here is
        // STILL a stand-in placed directly in the role: what is under test is
        // that a listener arriving over a real socket does not disturb the other
        // slot, and opening a second real socket to establish the other slot
        // would make this file depend on the file channel's whole lifecycle to
        // measure one line of this one. RemoteWiringTest is where the real
        // attachment is driven.
        String id = "both-roles";
        String provider = "a stand-in for the file channel's socket";
        registry.attach(id, Role.FILE_PROVIDER, provider);

        connect(id);

        Session session = awaitListener(id);
        assertEquals(Optional.of(provider), session.attached(Role.FILE_PROVIDER, String.class),
                "a listener arriving displaces a listener, never the other role");
    }

    @Test
    void closing_detaches_the_listener_and_the_session_outlives_it() throws Exception {
        String id = "closing";
        Watching watching = connect(id);
        awaitListener(id);

        watching.socket.close(1000, "going away");

        Session session = awaitNoListener(id);
        // THE SESSION IS STILL THERE. That is the whole reason it is an object:
        // a client that reconnects under the id it already had gets the session
        // it already had, and a job submitted under it in between still finds an
        // answer to "is anything attached" rather than an absence.
        assertEquals(id, session.id());
        assertFalse(session.has(Role.LISTENER));
    }

    // --- displacement --------------------------------------------------------

    @Test
    void a_second_connection_under_one_name_takes_over_and_the_first_is_closed()
            throws Exception {
        // FileChannelHandler's story and not a second one: the newer connection
        // wins because it is the one a human is looking at, and the displaced one
        // is closed rather than forgotten — left open, a Tomcat session survives
        // until its peer goes, which that handler's javadoc says can be minutes
        // on a half-open connection, so a client in a reconnect loop accumulates
        // them. SessionRegistry hands the loser back and closes nothing itself;
        // this is the caller that closes it.
        String id = "reconnecting";
        Watching first = connect(id);
        WebSocketSession before = awaitListener(id)
                .attached(Role.LISTENER, WebSocketSession.class).orElseThrow();

        connect(id);

        WebSocketSession after = awaitListenerOtherThan(id, before);
        assertNotSame(before, after, "the newer socket is the session's listener now");
        assertTrue(first.awaitClosed(PATIENCE_MILLIS),
                "and the server closed the socket it displaced rather than leaving it open");
        assertTrue(first.reason.get().contains("replaced"),
                "the displaced client is told what happened to it, since a socket that simply"
                        + " closed and one that was taken over are the same event from the"
                        + " outside — " + first.reason.get());
    }

    @Test
    void a_session_id_too_long_for_a_close_reason_still_displaces() throws Exception {
        // The fixture behind the displacement sentence naming no session id. A
        // close reason is a bounded field and a session id is not: the registry
        // requires only that an id be non-blank, so how long one gets is a
        // decision this server does not make.
        //
        // MEASURED, AND THE MEASUREMENT SAID SOMETHING ELSE. Building the reason
        // as "replaced by a newer connection for session " + id was expected to
        // make the container refuse an over-long reason; it does not. Nothing
        // throws — the reason is silently truncated, and this client receives 121
        // characters ending in an ellipsis. THIS TEST PASSES ON THAT VARIANT, so
        // it is not what keeps the id out of the sentence; the reason for that is
        // recorded on EventChannelHandler.close, and it is a half-id that still
        // reads like an id rather than a failure.
        //
        // What this test does hold is the surrounding claim, which is worth
        // holding either way: an id far longer than a close frame can carry
        // displaces cleanly, and a later hand that decides to validate the
        // reason and throw finds out here rather than in somebody's session.
        String id = "w".repeat(200);
        Watching first = connect(id);
        WebSocketSession before = awaitListener(id)
                .attached(Role.LISTENER, WebSocketSession.class).orElseThrow();

        connect(id);
        WebSocketSession after = awaitListenerOtherThan(id, before);

        assertTrue(first.awaitClosed(PATIENCE_MILLIS), "the displaced socket closed");
        assertSame(after, staysAttached(id),
                "and the connection that displaced it is still the session's listener");
    }

    @Test
    void the_displaced_socket_closing_afterwards_leaves_its_replacement() throws Exception {
        // The dangerous half. The first socket is replaced and then closes, a
        // moment later and on its own schedule, and its close callback must not
        // empty the role the second one is now in.
        //
        // Measured by making the mistake: with afterConnectionClosed detaching
        // whatever the role holds instead of its own socket — `attached(LISTENER,
        // ...)` and then detaching that — the session is left with NO listener at
        // all, and this test fails at awaitListenerOtherThan rather than at the
        // settle below, because the emptying happens within ten milliseconds.
        //
        // It is not alone in failing: all three displacement tests here do, which
        // is what one shared registry guard looks like from three angles. What
        // this one adds over the two above is the sequencing — the loser's close
        // is observed to have completed before the replacement is looked at, so a
        // detach arriving late has already arrived by the time the assertion
        // runs, and passing cannot mean the callback simply had not happened
        // yet.
        String id = "handover";
        Watching first = connect(id);
        WebSocketSession before = awaitListener(id)
                .attached(Role.LISTENER, WebSocketSession.class).orElseThrow();
        connect(id);
        WebSocketSession after = awaitListenerOtherThan(id, before);

        assertTrue(first.awaitClosed(PATIENCE_MILLIS), "the displaced socket really did close");

        assertSame(after, staysAttached(id),
                "the session's listener is still the socket that took over");
    }

    // --- refusals ------------------------------------------------------------

    @Test
    void a_client_that_opens_without_naming_a_session_is_closed_rather_than_left_hanging()
            throws Exception {
        // A session nothing can name is one no job can be routed to, so this
        // socket would sit there receiving events for nobody, and whoever opened
        // it would never learn that it is watching nothing. The registry refuses
        // a blank id one layer down; this is the refusal happening where the
        // wiring bug is, which is the only place the client can be told.
        Watching bare = connectTo(EventChannelHandler.PATH);

        assertTrue(bare.awaitClosed(PATIENCE_MILLIS), "the server closed it");
        assertTrue(bare.reason.get().contains(FileChannelHandler.SESSION_PARAM),
                "and said what to open instead — " + bare.reason.get());
        assertTrue(bare.reason.get().contains(EventChannelHandler.PATH),
                "naming this path rather than the file channel's, since a client told to"
                        + " reopen the wrong one is worse off than one told nothing — "
                        + bare.reason.get());
    }

    @Test
    void a_session_name_that_is_there_but_empty_is_refused_like_one_that_is_missing()
            throws Exception {
        // A different branch from no query string at all: `?session=` parses,
        // and a listener registered under "" would be a session no job could
        // name either — and a shared one, since every mis-wired client lands in
        // it together.
        Watching empty = connectTo(EventChannelHandler.PATH + "?"
                + FileChannelHandler.SESSION_PARAM + "=");

        assertTrue(empty.awaitClosed(PATIENCE_MILLIS));
        assertTrue(registry.find("").isEmpty(), "and nothing registered under the empty name");
    }

    @Test
    void an_upgrade_from_a_foreign_origin_is_refused() throws Exception {
        // EventChannelConfig says the ABSENCE of setAllowedOrigins is
        // load-bearing, and an absence with no instrument is a comment. This is
        // the one that matters most on this socket: it is the role a browser can
        // hold, so a config that allowed all origins would let any page the
        // operator visits open a listener on their session and watch their jobs
        // go by.
        //
        // "on a server bound to their own loopback" is the tail this comment
        // used to carry, and it was FALSE when it was written: application.yml
        // set a port and no server.address, so the container bound every
        // interface. The commit immediately after this one corrected the design
        // spec for exactly that, and EventChannelConfig — added in the same
        // commit as this line — already said the corrected version one file
        // over. The branch held both statements at once and this was the only
        // surviving copy.
        //
        // It is true now, and by a change rather than by a correction:
        // application.yml sets server.address: ${PLOWSHARE_BIND:127.0.0.1}. That
        // makes the origin check MORE load-bearing here, not less — with the
        // port reachable only from this machine, a page in the operator's own
        // browser is the party that can still reach it, and this check is what
        // stands between that page and their session.
        //
        // Measured: a no-origin upgrade is accepted (the tests above are all
        // no-origin), a foreign one is refused with 403 before this handler runs.
        String id = "origin-check";
        okhttp3.Request foreign = new okhttp3.Request.Builder()
                .url("http://localhost:" + port + EventChannelHandler.PATH + "?"
                        + FileChannelHandler.SESSION_PARAM + "=" + id)
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .header("Sec-WebSocket-Version", "13")
                .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .header("Origin", "http://evil.example.com")
                .build();

        try (okhttp3.Response refused = http.newCall(foreign).execute()) {
            assertEquals(403, refused.code(),
                    "a page on another origin does not get a listener");
        }
        assertTrue(registry.find(id).isEmpty(), "and nothing attached");
    }

    // --- the socket itself ---------------------------------------------------

    @Test
    void a_frame_that_is_not_an_envelope_is_refused_and_the_socket_stays_attached()
            throws Exception {
        // THIS TEST USED TO BE a_frame_from_a_listener_is_ignored_..., and what
        // it held was that the handler does not take the connection down over a
        // frame it has no protocol for. That half is unchanged and still
        // asserted; what changed under it is that there IS a protocol now, so
        // "ignored" became "refused, and told so". The listener's whole job is
        // still to be there when a job starts producing, which is why a frame
        // this server cannot read as an envelope costs the sender an answer and
        // not its socket — FrameRouter's own "a malformed frame does not take the
        // session down", reached from the channel that now calls it.
        String id = "talkative";
        Watching watching = connect(id);
        WebSocketSession attached = awaitListener(id)
                .attached(Role.LISTENER, WebSocketSession.class).orElseThrow();

        assertTrue(watching.socket.send("{\"hello\":\"is this thing on\"}"),
                "the client could send it");

        Map<String, Object> refusal = frame(watching.awaitFrame(PATIENCE_MILLIS));
        assertNull(refusal.get("id"),
                "there was no id in that frame, so there is none to echo — and an absent key"
                        + " rather than a null one, since a client correlating by id must not"
                        + " find one that matches nothing it sent");
        assertEquals(FrameTypes.REFUSED, refusal.get("type"),
                "a frame whose own type could not be read is answered under the one type that"
                        + " says so, since Envelope refuses to exist without a type at all");
        assertEquals(Code.BAD_REQUEST.name(), outcome(refusal).get("code"));

        assertSame(attached, staysAttached(id), "and the session kept its listener");
        assertTrue(attached.isOpen(), "on a socket that is still open");
    }

    @Test
    void a_request_frame_is_answered_over_the_socket_under_the_id_it_carried() throws Exception {
        // The whole of what this task connected, over a real port: a client
        // frame reaches the router, the router's Outcome becomes a response
        // frame, and the frame carries the request's own id — which is the only
        // thing a client has to match an answer to the question it asked, since
        // §3.5 says it may not assume the answer is the next thing to arrive.
        //
        // NOTHING IS REGISTERED IN THE PRODUCTION ROUTING TABLE YET, so the
        // answer is a NOT_FOUND naming the type. That is not a weakness of this
        // test: which Outcome a registered handler produces is FrameRouterTest's
        // question, and what is under test here is everything between the socket
        // and that Outcome. It also means this test keeps measuring the same
        // thing after the pilots land, rather than becoming an assertion about
        // one of them.
        String id = "asking";
        Watching watching = connect(id);
        awaitListener(id);

        assertTrue(watching.socket.send(request("req-1", "nothing.registered")));

        Map<String, Object> answer = frame(watching.awaitFrame(PATIENCE_MILLIS));
        assertEquals("req-1", answer.get("id"), "the answer names the request it answers");
        assertEquals("nothing.registered", answer.get("type"),
                "and echoes the type it was asked about, so a frame read out of a log says"
                        + " what it was an answer to");
        assertEquals(Envelope.CURRENT_VERSION, answer.get("protocol_version"),
                "the response is an envelope, under the one version this build speaks");
        Map<String, Object> outcome = outcome(answer);
        assertEquals(Code.NOT_FOUND.name(), outcome.get("code"));
        assertTrue(String.valueOf(outcome.get("said")).contains("nothing.registered"),
                "and said which type nobody answers — " + outcome.get("said"));
    }

    // --- the write discipline, which no real socket can be asked about --------

    /**
     * The three tests below drive {@link EventChannelHandler} directly over a
     * fake {@link WebSocketSession} rather than over the port this file
     * otherwise uses, and the reason is the same one {@code JobEventTest}
     * records for its own fake: <b>what has to be measured is a client that does
     * not complete a read, and no real client can be asked to stop reading.</b>
     * A blocked {@code sendMessage} is what a peer with a full receive window
     * looks like from this side, and it is the only way to hold the bounded
     * queue open long enough to ask what happens when it fills.
     *
     * <p>They live here rather than in {@code JobEventTest} because what they
     * measure is this channel's answer to a client frame — the thing this file
     * is about — and {@code JobEventTest}'s subject is what a running job says.
     * The fake is a second one for the same reason: that one is private to a
     * suite in another package, and this file must not start depending on it.
     */
    @Test
    void a_response_is_queued_rather_than_written_by_the_thread_that_routed_it()
            throws Exception {
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling socket = new Stalling(true);
        attach(handler, own, "wedged", socket);
        // One push takes the drain thread into a write that will not return, so
        // everything after it is stuck in the queue where this test can see it.
        handler.publish("wedged", JobEvent.started("job_warmup", "echo"));
        assertTrue(socket.awaitWedged(PATIENCE_MILLIS), "the fake socket is inside a write");

        long before = System.nanoTime();
        handler.handleTextMessage(socket, new TextMessage(request("req-1", "test.ping")));
        long millis = (System.nanoTime() - before) / 1_000_000;

        assertTrue(millis < 2_000,
                "answering a frame took " + millis + "ms over a socket that is not draining;"
                        + " the routing thread must not be the one that waits on the write,"
                        + " which this process bounds at twenty seconds");
        assertTrue(socket.frames().isEmpty(),
                "and nothing completed a write while the socket was wedged — so the answer"
                        + " is in the queue, which is the whole claim");

        socket.release();
        assertEquals("req-1", frame(socket.await(2, PATIENCE_MILLIS).get(1)).get("id"),
                "and it came out of the queue once the socket drained");
    }

    @Test
    void a_client_that_will_not_drain_is_disconnected_rather_than_quietly_unanswered()
            throws Exception {
        // THE ONE DECISION THIS TASK COULD NOT INHERIT. A full queue drops an
        // EVENT, and EventChannelHandler's javadoc argues that at length: the
        // stream is droppable because what a run came to is in JobStore, behind
        // GET /v1/jobs/{id}. None of that argument survives being pointed at a
        // RESPONSE. There is no endpoint a caller can ask "what did my frame
        // req-17 come to"; the id is the only handle, and a dropped response
        // leaves it pending forever — so the failure is not a lost frame but a
        // client that never fails.
        //
        // Blocking instead is the other thing this class may not do (§3.4). What
        // is left is §3.4's own sentence, which this test is the instrument for:
        // a client that will not drain is disconnected rather than tolerated. A
        // close is something the client actually observes, and it fails every
        // outstanding id at once instead of one silently.
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling socket = new Stalling(true);
        attach(handler, own, "flooded", socket);
        handler.publish("flooded", JobEvent.started("job_warmup", "echo"));
        assertTrue(socket.awaitWedged(PATIENCE_MILLIS));

        try {
            for (int nth = 0; nth < EventChannelHandler.PENDING + 2; nth++) {
                handler.handleTextMessage(socket,
                        new TextMessage(request("req-" + nth, "test.ping")));
            }

            assertTrue(socket.awaitClosed(PATIENCE_MILLIS),
                    "a listener given " + (EventChannelHandler.PENDING + 2) + " requests it"
                            + " never read was closed rather than answered into a full queue");
            assertFalse(socket.isOpen());
            assertFalse(socket.closeReason().isBlank(),
                    "and told why, since a close with no reason is indistinguishable from the"
                            + " network going away");
        } finally {
            socket.release();
        }
    }

    @Test
    void a_listener_that_did_not_ask_is_sent_no_deltas_at_all() throws Exception {
        // THE OTHER HALF OF OPT-IN, AND THE ONE THAT PROTECTS EVERYBODY ELSE.
        // The MCP adapter and the web console both attach listeners and neither
        // wants a typing effect; without this the firehose reaches them by
        // default, which is the outcome the streaming spec warned about in so
        // many words.
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler =
                new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling socket = new Stalling(false);
        attach(handler, own, "incurious", socket);

        for (int nth = 0; nth < 50; nth++) {
            handler.stream("incurious", JobDelta.answering("job_1", "tok" + nth));
        }
        // An event AFTER them, so this waits on something that must arrive
        // rather than on the absence of something, which cannot be waited on.
        handler.publish("incurious", JobEvent.ended("job_1", "echo", "ANSWERED", 1, 1));
        assertTrue(socket.awaitFrameContaining("ANSWERED", PATIENCE_MILLIS));

        assertTrue(socket.frames().stream().noneMatch(frame -> frame.contains("tok")),
                "a listener that never sent job.stream was sent deltas anyway: "
                        + socket.frames());
    }

    @Test
    void asking_and_then_asking_to_stop_leaves_a_listener_quiet_again() throws Exception {
        // A field on the run frame could not express this: it is fixed for the
        // life of the run, so somebody who opened the thinking, read enough and
        // wanted it to stop would have no way to say so. It is the sharpest of
        // the reasons the subscription belongs to the listener.
        SessionRegistry own = new SessionRegistry();
        Watchers watchers = new Watchers();
        EventChannelHandler handler =
                new EventChannelHandler(own, answering("test.ping"), watchers);
        Stalling socket = new Stalling(false);
        attach(handler, own, "fickle", socket);

        watchers.wants("fickle", true);
        handler.stream("fickle", JobDelta.answering("job_1", "WANTED"));
        assertTrue(socket.awaitFrameContaining("WANTED", PATIENCE_MILLIS));

        watchers.wants("fickle", false);
        handler.stream("fickle", JobDelta.answering("job_1", "UNWANTED"));
        handler.publish("fickle", JobEvent.ended("job_1", "echo", "ANSWERED", 1, 1));
        assertTrue(socket.awaitFrameContaining("ANSWERED", PATIENCE_MILLIS));

        assertTrue(socket.frames().stream().noneMatch(frame -> frame.contains("UNWANTED")),
                "a listener that asked to stop kept being sent deltas");
    }

    @Test
    void a_listener_closing_forgets_that_it_ever_asked() throws Exception {
        // Otherwise the set grows by one per session that ever asked, for the
        // life of the process -- and a client that reconnected would be
        // streaming again without having said so, which is the one thing opt-in
        // is for.
        SessionRegistry own = new SessionRegistry();
        Watchers watchers = new Watchers();
        EventChannelHandler handler =
                new EventChannelHandler(own, answering("test.ping"), watchers);
        Stalling socket = new Stalling(false);
        attach(handler, own, "transient", socket);
        watchers.wants("transient", true);
        assertTrue(watchers.watching("transient"));

        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);

        assertFalse(watchers.watching("transient"),
                "the subscription outlived the socket that asked for it");
    }

    @Test
    void a_flood_of_tokens_cannot_evict_an_ending() throws Exception {
        // THE WHOLE REASON THERE ARE TWO QUEUES, AND WITHOUT THIS TEST THEY ARE
        // TWO QUEUES BY ACCIDENT.
        //
        // The lifecycle queue drops when full, which is right for an event a
        // client can recover from GET /v1/jobs/{id}. Deltas arrive in their
        // hundreds per model call -- measured, 6 571 characters of reasoning
        // against 1 965 of answer on one call -- and through that same queue
        // they would push an ENDED out of it. An ending that never arrives is a
        // turn that never finishes, which is the shape of failure that cost
        // eighteen minutes of diagnosis on 2026-09-12 from a different cause.
        //
        // So: flood the tokens far past their own bound, then publish an
        // ending, and require the ending to arrive.
        SessionRegistry own = new SessionRegistry();
        Watchers watchers = new Watchers();
        EventChannelHandler handler =
                new EventChannelHandler(own, answering("test.ping"), watchers);
        Stalling socket = new Stalling(true);
        attach(handler, own, "flooded", socket);
        // OPT IN, BECAUSE NOTHING ARRIVES OTHERWISE. `job.stream` is the frame a
        // client sends to get here; this is what that frame does.
        watchers.wants("flooded", true);

        // Wedge the drain thread on its first write, so nothing leaves and the
        // queues genuinely fill rather than draining as fast as they are fed.
        handler.publish("flooded", JobEvent.started("job_warmup", "echo"));
        assertTrue(socket.awaitWedged(PATIENCE_MILLIS));

        try {
            // Ten times the token bound. A single queue of 256 would have lost
            // everything behind the first 256 frames long before this stopped.
            for (int nth = 0; nth < EventChannelHandler.STREAMING * 10; nth++) {
                handler.stream("flooded", JobDelta.answering("job_1", "tok" + nth));
            }
            handler.publish("flooded", JobEvent.ended("job_1", "echo", "ANSWERED", 1, 1));

            // Let it write: the wedged frame goes, then whatever is queued.
            socket.release();

            assertTrue(socket.awaitFrameContaining("ANSWERED", PATIENCE_MILLIS),
                    "a flood of " + (EventChannelHandler.STREAMING * 10) + " token deltas"
                            + " evicted the ending behind them, which is the one thing two"
                            + " queues exist to prevent");
        } finally {
            socket.release();
        }
    }

    @Test
    void an_ending_goes_out_ahead_of_tokens_already_queued() throws Exception {
        // ISOLATION IS NOT THE WHOLE OF IT. Separate queues stop a flood
        // EVICTING an ending; they do not on their own stop one sitting behind
        // sixty-four deltas, which is a turn that finished and a terminal that
        // does not say so yet. The drain prefers lifecycle for that reason, and
        // this is the test that makes the preference real -- reversing the two
        // polls passes every other case in this file.
        SessionRegistry own = new SessionRegistry();
        Watchers watchers = new Watchers();
        EventChannelHandler handler =
                new EventChannelHandler(own, answering("test.ping"), watchers);
        Stalling socket = new Stalling(true);
        attach(handler, own, "ordered", socket);
        watchers.wants("ordered", true);
        handler.publish("ordered", JobEvent.started("job_warmup", "echo"));
        assertTrue(socket.awaitWedged(PATIENCE_MILLIS));

        try {
            // Fill the token queue, THEN end the run. In arrival order the
            // ending is last; in delivery order it must be first.
            for (int nth = 0; nth < EventChannelHandler.STREAMING; nth++) {
                handler.stream("ordered", JobDelta.answering("job_1", "tok" + nth));
            }
            handler.publish("ordered", JobEvent.ended("job_1", "echo", "ANSWERED", 1, 1));
            socket.release();

            List<String> written = socket.await(2, PATIENCE_MILLIS);
            // [0] is the warmup the socket was wedged on. [1] is the next thing
            // the drain chose, with sixty-four tokens and one ending waiting.
            assertTrue(written.get(1).contains("ANSWERED"),
                    "the drain wrote " + written.get(1) + " before the ending, so a finished"
                            + " turn waits behind a queue of tokens that no longer matter");
        } finally {
            socket.release();
        }
    }

    @Test
    void a_token_queue_that_fills_drops_rather_than_disconnecting() throws Exception {
        // The other half of the contract, and the difference from a RESPONSE. A
        // response that cannot be queued closes the socket, because its id would
        // otherwise be pending forever. A token that cannot be queued is simply
        // gone: nobody is waiting on it and the answer arrives whole regardless.
        SessionRegistry own = new SessionRegistry();
        Watchers watchers = new Watchers();
        EventChannelHandler handler =
                new EventChannelHandler(own, answering("test.ping"), watchers);
        Stalling socket = new Stalling(true);
        attach(handler, own, "flooded", socket);
        watchers.wants("flooded", true);
        handler.publish("flooded", JobEvent.started("job_warmup", "echo"));
        assertTrue(socket.awaitWedged(PATIENCE_MILLIS));

        try {
            for (int nth = 0; nth < EventChannelHandler.STREAMING * 10; nth++) {
                handler.stream("flooded", JobDelta.thinking("job_1", "t" + nth));
            }
            assertTrue(socket.isOpen(),
                    "a listener that fell behind on TOKENS was disconnected, which is the"
                            + " treatment a dropped response gets and a dropped token must not");
        } finally {
            socket.release();
        }
    }

    @Test
    void a_response_and_an_event_its_request_caused_arrive_in_either_order() throws Exception {
        // Spec §3.5, pinned rather than left to prose: a client may not assume a
        // response arrives before an event its request caused, OR after. A test
        // can only pin that by PRODUCING BOTH ORDERS, which is what the two
        // halves below do — assert one order and the test would be asserting
        // more than the contract allows a client to rely on.
        //
        // It also holds the other shape this task promised not to change: the
        // event frames here are bare JobEvent JSON with no envelope around them,
        // which is what /v1/events has always pushed and what the web console
        // reads today. Only the responses are enveloped.
        SessionRegistry own = new SessionRegistry();
        AtomicReference<EventChannelHandler> channel = new AtomicReference<>();
        FrameHandler publishesFirst = (payload, caller) -> {
            channel.get().publish(caller.sessionId(), JobEvent.started("job_1", "echo"));
            return Outcome.ok();
        };
        EventChannelHandler handler = new EventChannelHandler(own, new FrameRouter(Map.of(
                "test.noisy", publishesFirst,
                "test.quiet", (payload, caller) ->
                        Outcome.ok())), new Watchers());
        channel.set(handler);
        Stalling socket = new Stalling(false);
        attach(handler, own, "interleaving", socket);

        // The event first, because the handler published before it answered.
        handler.handleTextMessage(socket, new TextMessage(request("req-1", "test.noisy")));
        List<String> two = socket.await(2, PATIENCE_MILLIS);
        assertTrue(isBareEvent(frame(two.get(0))),
                "the event its own request caused came first — " + two.get(0));
        assertEquals("req-1", frame(two.get(1)).get("id"));

        // And the response first, because this one had nothing to publish and
        // the job that did published afterwards. Waited for rather than slept
        // through: the response is observed to have arrived before the event is
        // produced, so the order is made rather than raced for.
        handler.handleTextMessage(socket, new TextMessage(request("req-2", "test.quiet")));
        assertEquals("req-2", frame(socket.await(3, PATIENCE_MILLIS).get(2)).get("id"));
        handler.publish("interleaving", JobEvent.modelCall("job_1", "echo", 1, 1));
        assertTrue(isBareEvent(frame(socket.await(4, PATIENCE_MILLIS).get(3))),
                "the event followed the answer this time, and the client was told nothing"
                        + " different about either");
    }

    /** A server push, which carries no envelope at all — no {@code
     *  protocol_version}, no {@code id} — and is exactly the shape the console
     *  consumes today. */
    private static boolean isBareEvent(Map<String, Object> pushed) {
        return pushed.containsKey("kind") && !pushed.containsKey("protocol_version");
    }

    /** A router that answers {@code type} with an empty success and knows
     *  nothing else. */
    private static FrameRouter answering(String type) {
        return new FrameRouter(Map.of(type, (payload, caller) ->
                Outcome.ok()));
    }

    /** Opens {@code socket} as {@code session}'s listener, the way the container
     *  would, and checks the attach took — a fake whose URI was wrong would
     *  otherwise make every assertion after it vacuous. */
    private static void attach(EventChannelHandler handler, SessionRegistry registry,
            String session, Stalling socket) throws Exception {
        socket.uri = URI.create(EventChannelHandler.PATH + "?"
                + FileChannelHandler.SESSION_PARAM + "=" + session);
        handler.afterConnectionEstablished(socket);
        assertTrue(registry.find(session).filter(live -> live.has(Role.LISTENER)).isPresent(),
                "the fake socket attached as a listener");
    }

    @Test
    void the_two_channels_are_two_paths_that_read_the_session_id_the_same_way() {
        // One convention, stated where a second one would be introduced. Every
        // other test in this file would pass with both literals wrong in the
        // same way, because they all reach the server through this pair.
        assertEquals("/v1/events", EventChannelHandler.PATH);
        assertNotEquals(FileChannelHandler.PATH, EventChannelHandler.PATH,
                "two roles, two sockets, so that a listener cannot break the file channel");
        assertEquals("session", FileChannelHandler.SESSION_PARAM,
                "and the id is on the query string under one name for both");
    }

    /**
     * The listener is published on the path the client dials, and reads the id
     * under the name the client sends it.
     *
     * <p>{@code FileChannelTest} has this pair for the file channel — {@code
     * the_production_wiring_publishes_the_channel_on_the_path_the_client_dials}
     * — and there was no equivalent for the listener, because the class that
     * dials it is {@link SessionClient}, in a module whose own tests cannot see
     * a server to compare against. So the claim had nowhere to live except here.
     *
     * <p>Today a mismatch is not silent: the upgrade fails and {@code
     * SessionClient} reports the 404 with a sentence about a server that has no
     * such channel. But that is a mismatch found by running the pair, on a
     * machine where both halves happen to be deployed together, and the two
     * literals are compiled from two modules that ship independently. This is
     * the same claim at compile time.
     *
     * <p>{@code assertNotEquals} against the file channel's path is not repeated
     * here — the test above owns it. What is added is the second half of the
     * agreement: the query parameter. {@link EventChannelHandler} reads the id
     * with {@code FileChannelHandler}'s parser and under its constant, and
     * {@code SessionClient} dials through {@link ChannelClient#dial}, which
     * appends {@code ChannelClient}'s. A listener that attached under a
     * different key would be a session no job could publish to.
     */
    @Test
    void the_production_wiring_publishes_the_listener_on_the_path_the_client_dials() {
        assertEquals("/" + SessionClient.EVENTS_PATH, EventChannelHandler.PATH);
        assertEquals(ChannelClient.SESSION_PARAM, FileChannelHandler.SESSION_PARAM);
    }

    // --- the account a socket is signed in as ---------------------------------

    /** Attaches {@code socket} as {@code session}'s listener, with the handle
     *  attribute already on it — the same attribute {@code HandleInterceptor}
     *  copies over before the upgrade completes. */
    private static void attachAs(EventChannelHandler handler, SessionRegistry registry,
            String session, String handle, Stalling socket) throws Exception {
        socket.getAttributes().put(EventChannelHandler.HANDLE, handle);
        attach(handler, registry, session, socket);
    }

    @Test
    void a_push_to_an_account_reaches_every_socket_signed_in_as_it_and_no_other() throws Exception {
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling console = new Stalling(false);
        Stalling terminal = new Stalling(false);
        Stalling someoneElse = new Stalling(false);
        attachAs(handler, own, "s-console", "enzo", console);
        attachAs(handler, own, "s-terminal", "enzo", terminal);
        attachAs(handler, own, "s-sam", "sam", someoneElse);

        handler.push("enzo", Map.of("kind", "inbox.changed", "unread", 2));

        assertTrue(console.awaitFrameContaining("inbox.changed", 2_000));
        assertTrue(terminal.awaitFrameContaining("inbox.changed", 2_000));
        assertFalse(someoneElse.awaitFrameContaining("inbox.changed", 200));
    }

    @Test
    void concurrent_jobs_keep_their_ids_and_session_routing_across_accounts() throws Exception {
        SessionRegistry own = new SessionRegistry();
        Watchers watchers = new Watchers();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), watchers);
        Stalling desktop = new Stalling(false);
        Stalling otherUser = new Stalling(false);
        Stalling otherClient = new Stalling(false);
        attachAs(handler, own, "s-desktop", "enzo", desktop);
        attachAs(handler, own, "s-other", "sam", otherUser);
        attachAs(handler, own, "s-terminal", "enzo", otherClient);
        watchers.wants("s-desktop", true);
        watchers.wants("s-other", true);

        handler.publish("s-desktop", JobEvent.started("job_1", "echo"));
        handler.publish("s-other", JobEvent.started("job_3", "echo"));
        handler.publish("s-desktop", JobEvent.started("job_2", "echo"));
        handler.stream("s-desktop", JobDelta.answering("job_2", "second"));
        handler.stream("s-other", JobDelta.answering("job_3", "other user"));
        handler.stream("s-desktop", JobDelta.answering("job_1", "first"));

        List<String> desktopFrames = desktop.await(4, PATIENCE_MILLIS);
        List<String> otherFrames = otherUser.await(2, PATIENCE_MILLIS);
        assertEquals(4, desktopFrames.size());
        List<Object> desktopJobs = new ArrayList<>();
        for (String body : desktopFrames) {
            desktopJobs.add(frame(body).get("job"));
        }
        assertEquals(List.of("job_1", "job_2", "job_2", "job_1"), desktopJobs);
        assertEquals(2, otherFrames.size());
        for (String body : otherFrames) {
            assertEquals("job_3", frame(body).get("job"));
        }
        assertFalse(otherClient.awaitFrameContaining("job_", 200),
                "job streams are session-addressed even for another client of the same account");
    }

    /**
     * Final review M10: a listener refused because another account holds its session id is
     * refused before it has a delivery, a place in {@code byHandle} or the session id its close
     * would act on, so it hears nothing of the holder's and nothing pushed to its own account.
     * Driven directly, with no close callback after the refusal: over a real socket that callback
     * would tidy both up and hide a refusal that came too late.
     */
    @Test
    void a_listener_refused_for_another_account_s_session_is_given_nothing_to_hear_through()
            throws Exception {
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling holder = new Stalling(false);
        attachAs(handler, own, "s-held", "enzo", holder);
        Stalling intruder = new Stalling(false);
        intruder.getAttributes().put(EventChannelHandler.HANDLE, "mallory");
        intruder.uri = URI.create(EventChannelHandler.PATH + "?"
                + FileChannelHandler.SESSION_PARAM + "=s-held");

        handler.afterConnectionEstablished(intruder);

        assertTrue(intruder.awaitClosed(PATIENCE_MILLIS), "refused");
        assertEquals(Set.of(EventChannelHandler.HANDLE), intruder.getAttributes().keySet(),
                "no delivery and no session id were hung on the refused socket");
        @SuppressWarnings("unchecked")
        Map<String, Set<WebSocketSession>> byHandle = (Map<String, Set<WebSocketSession>>)
                readField(handler, "byHandle");
        assertFalse(byHandle.containsKey("mallory"), "and it was never listed for its account");
        assertTrue(own.find("s-held").flatMap(live -> live.attached(Role.LISTENER,
                WebSocketSession.class)).filter(holder::equals).isPresent(),
                "the holder's listener was not displaced");
        handler.publish("s-held", JobEvent.started("job_1", "echo"));
        assertTrue(holder.awaitFrameContaining("job_1", PATIENCE_MILLIS));
        assertTrue(intruder.frames().isEmpty(), "the intruder heard none of the holder's events");
    }

    private static Object readField(Object owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test
    void a_closed_socket_no_longer_receives_its_accounts_pushes() throws Exception {
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling console = new Stalling(false);
        attachAs(handler, own, "s-console", "enzo", console);
        handler.afterConnectionClosed(console, CloseStatus.NORMAL);
        handler.push("enzo", Map.of("kind", "inbox.changed", "unread", 1));
        assertFalse(console.awaitFrameContaining("inbox.changed", 200));
    }

    // --- SessionPushes: a bare push addressed by session, not by account ------

    @Test
    void a_push_to_a_session_reaches_that_session_s_socket_and_no_other() throws Exception {
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler =
                new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling following = new Stalling(false);
        Stalling elsewhere = new Stalling(false);
        attach(handler, own, "s-following", following);
        attach(handler, own, "s-elsewhere", elsewhere);

        handler.tell("s-following",
                Map.of("kind", "conversation.appended", "conversation", "cnv_1", "through", 7));

        assertTrue(following.awaitFrameContaining("conversation.appended", 2_000));
        assertFalse(elsewhere.awaitFrameContaining("conversation.appended", 200));
    }

    @Test
    void a_listener_closing_follows_nothing() throws Exception {
        SessionRegistry own = new SessionRegistry();
        Watchers watchers = new Watchers();
        EventChannelHandler handler =
                new EventChannelHandler(own, answering("test.ping"), watchers);
        Stalling socket = new Stalling(false);
        attach(handler, own, "transient", socket);
        watchers.follows("transient", Set.of("cnv_1", "cnv_2"));
        assertEquals(Set.of("transient"), watchers.followersOf("cnv_1"));
        assertEquals(Set.of("transient"), watchers.followersOf("cnv_2"));

        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);

        assertEquals(Set.of(), watchers.followersOf("cnv_1"),
                "a closed socket follows no conversation");
        assertEquals(Set.of(), watchers.followersOf("cnv_2"));
    }

    @Test
    void a_session_names_the_account_its_socket_is_signed_in_as() throws Exception {
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), new Watchers());
        attachAs(handler, own, "s-console", "enzo", new Stalling(false));
        assertEquals(Optional.of("enzo"), handler.handleOf("s-console"));
        assertEquals(Optional.empty(), handler.handleOf("s-unknown"));
    }

    @Test
    void a_frame_is_routed_with_the_sockets_handle() throws Exception {
        SessionRegistry own = new SessionRegistry();
        AtomicReference<Asking> asked = new AtomicReference<>();
        FrameRouter router = new FrameRouter(Map.of("test.who", (payload, asking) -> {
            asked.set(asking);
            return Outcome.ok();
        }));
        EventChannelHandler handler = new EventChannelHandler(own, router, new Watchers());
        Stalling socket = new Stalling(false);
        attachAs(handler, own, "s-console", "enzo", socket);
        handler.handleMessage(socket, new TextMessage(request("r1", "test.who")));
        assertTrue(socket.awaitFrameContaining("\"r1\"", 2_000));
        assertEquals(new Asking("s-console", "enzo", socket.getId()), asked.get());
    }

    @Test
    void usage_snapshots_coalesce_without_displacing_the_correlated_initial_reply() throws Exception {
        var queries = org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.accounting.UsageQueryService.class);
        var filter = new io.aeyer.plowshare.server.llm.accounting.UsageQueryService.Filter(null,null,null,null,null,null,null,null,
                "direct", java.time.Instant.now().minusSeconds(60),java.time.Instant.now(),List.of(),null,200);
        var query = new io.aeyer.plowshare.server.llm.accounting.UsageQueryService.Resolved("usage.models",filter);
        var value = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(queries.resolve(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenReturn(query);
        org.mockito.Mockito.when(queries.report(org.mockito.ArgumentMatchers.eq("enzo"),org.mockito.ArgumentMatchers.eq(query)))
                .thenAnswer(invocation -> new io.aeyer.plowshare.server.llm.accounting.UsageQueryService.Report(query,
                        Map.of("calls",Integer.toString(value.get())),List.of(),null,Map.of("watermark",Integer.toString(value.get()))));
        var subscriptions = new UsageSubscriptions(queries);
        var registry = new SessionRegistry();
        var channel = new EventChannelHandler(registry,new FrameRouter(new UsageFrames(queries,subscriptions).frames()),new Watchers());
        channel.useUsageSubscriptions(subscriptions);
        var socket = new Stalling(true);
        attachAs(channel,registry,"usage-session","enzo",socket);
        channel.push("enzo",Map.of("holding",true));
        assertTrue(socket.awaitWedged(2_000));
        channel.handleMessage(socket,new TextMessage("{\"id\":\"initial\",\"type\":\"usage.subscribe\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"report_type\":\"usage.models\"}}"));
        for(int i=1;i<=1000;i++) {value.set(i);subscriptions.publish();}
        socket.release();
        var frames=socket.await(3,2_000);
        assertEquals(3,frames.size());
        assertTrue(frames.get(1).contains("\"id\":\"initial\""));
        assertTrue(frames.get(2).contains("\"type\":\"usage.updated\""));
        assertTrue(frames.get(2).contains("\"revision\":1000"));
        assertFalse(frames.get(2).contains("\"id\":"));
        assertTrue(socket.isOpen());
        channel.afterConnectionClosed(socket,CloseStatus.NORMAL);
        assertThrows(io.aeyer.plowshare.server.faults.CallerFault.class,
                () -> subscriptions.subscribe(new Asking("usage-session","enzo",socket.getId()),query));
        subscriptions.close();
    }

    @Test
    void a_push_body_that_cannot_be_written_is_dropped_and_the_next_one_still_arrives()
            throws Exception {
        // pushed() (JobEvent) and streamed() (deltas) both catch their own
        // JsonProcessingException and return null so drain()'s loop moves on;
        // an account push must behave the same way rather than take the whole
        // drain thread down with it, which would silently cost this
        // connection every future job event, delta, and reply too.
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling console = new Stalling(false);
        attachAs(handler, own, "s-console", "enzo", console);

        handler.push("enzo", new Unwritable());
        handler.push("enzo", Map.of("kind", "inbox.changed", "unread", 1));

        assertTrue(console.awaitFrameContaining("inbox.changed", 2_000),
                "a push body that fails to serialise must be dropped, not let it kill the"
                        + " drain thread that every later push on this connection needs");
    }

    /** A bean whose getter throws, so Jackson wraps the failure as a {@code
     *  JsonProcessingException} rather than serialising anything — the shape
     *  {@code Delivery.bare} has to catch and drop instead of propagating. */
    private static final class Unwritable {
        public String getBoom() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void a_displaced_socket_no_longer_receives_pushes_to_the_handle_it_carried() throws Exception {
        SessionRegistry own = new SessionRegistry();
        EventChannelHandler handler = new EventChannelHandler(own, answering("test.ping"), new Watchers());
        Stalling first = new Stalling(false);
        Stalling second = new Stalling(false);
        attachAs(handler, own, "s-console", "enzo", first);
        // A second connection under the SAME session displaces the first:
        // EventChannelHandler.close() stops its delivery and closes the
        // socket, but does not touch byHandle — that happens in
        // afterConnectionClosed, which only a container calls once it
        // notices the close. Stalling has no container behind it, so this
        // test calls that callback itself, exactly as Tomcat would.
        attachAs(handler, own, "s-console", "enzo", second);
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);

        handler.push("enzo", Map.of("kind", "inbox.changed", "unread", 3));

        assertTrue(second.awaitFrameContaining("inbox.changed", 2_000));
        assertFalse(first.awaitFrameContaining("inbox.changed", 200));
    }

    // --- fixtures ------------------------------------------------------------

    /** Reads a frame back off the wire as the untyped map a client would see,
     *  rather than binding it to {@link Envelope} — a test that bound the
     *  server's own record would pass on a frame that omitted a key entirely,
     *  and an absent key is exactly what two assertions here are about. */
    private static final ObjectMapper FRAMES = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> AS_MAP = new TypeReference<>() {};

    /** One request frame, hand-written rather than serialised from {@link
     *  Envelope}, so that a change to that record's wire names fails a test here
     *  instead of being applied to both sides of the assertion at once. */
    private static String request(String id, String type) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"protocol_version\":\""
                + Envelope.CURRENT_VERSION + "\",\"payload\":{}}";
    }

    private static Map<String, Object> frame(String text) throws IOException {
        return FRAMES.readValue(text, AS_MAP);
    }

    /** The {@code { code, said?, payload? }} a response envelope carries, per
     *  spec §3.3. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> outcome(Map<String, Object> response) {
        Object payload = response.get("payload");
        assertTrue(payload instanceof Map, "a response carries an outcome — " + payload);
        return (Map<String, Object>) payload;
    }

    /** A listener socket under {@code id}, closed after the test. */
    private Watching connect(String id) {
        return connectTo(EventChannelHandler.PATH + "?"
                + FileChannelHandler.SESSION_PARAM + "=" + id);
    }

    /** Opens whatever URI a refusal test needs, including one with no id at all.
     *  Named apart from {@link #connect(String)} so that a test asking for a
     *  session cannot silently be asking for a path. */
    private Watching connectTo(String pathAndQuery) {
        Watching watching = new Watching();
        watching.socket = http.newWebSocket(new Request.Builder()
                .url("ws://localhost:" + port + pathAndQuery).build(), watching);
        opened.add(watching);
        return watching;
    }

    /** The upgrade is asynchronous on both sides, so a test that read the
     *  registry the moment {@code newWebSocket} returned would race the
     *  attachment. */
    private Session awaitListener(String id) throws InterruptedException {
        return await(id, session -> session.has(Role.LISTENER),
                "session '" + id + "' never got a listener");
    }

    private Session awaitNoListener(String id) throws InterruptedException {
        return await(id, session -> !session.has(Role.LISTENER),
                "session '" + id + "' still has a listener");
    }

    /** The takeover observed rather than slept through: the second socket is
     *  registered when the role holds something that is not what the first one
     *  put there. */
    private WebSocketSession awaitListenerOtherThan(String id, WebSocketSession first)
            throws InterruptedException {
        return await(id, session -> session.attached(Role.LISTENER, WebSocketSession.class)
                        .filter(live -> live != first).isPresent(),
                "session '" + id + "' never took a second listener")
                .attached(Role.LISTENER, WebSocketSession.class).orElseThrow();
    }

    private Session await(String id, Predicate<Session> until, String said)
            throws InterruptedException {
        for (long waited = 0; waited < PATIENCE_MILLIS; waited += 10) {
            Optional<Session> session = registry.find(id);
            if (session.filter(until).isPresent()) {
                return session.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError(said + " — " + registry.find(id));
    }

    /**
     * What is attached after {@link #SETTLE_MILLIS} of nothing taking it away.
     *
     * <p>Polls throughout rather than sleeping once and looking: a role that is
     * emptied and refilled between two glances is a bug this would otherwise
     * report as fine.
     *
     * @return the attachment, which was the same one for the whole window
     */
    private WebSocketSession staysAttached(String id) throws InterruptedException {
        WebSocketSession first = null;
        for (long waited = 0; waited < SETTLE_MILLIS; waited += 10) {
            long far = waited;
            WebSocketSession now = registry.find(id)
                    .flatMap(session -> session.attached(Role.LISTENER, WebSocketSession.class))
                    .orElseThrow(() -> new AssertionError(
                            "session '" + id + "' lost its listener after " + far + "ms"));
            if (first == null) {
                first = now;
            } else if (first != now) {
                throw new AssertionError("session '" + id + "' swapped its listener after "
                        + waited + "ms");
            }
            Thread.sleep(10);
        }
        return first;
    }

    /** A client that records how the server closed it and answers a close so the
     *  handshake completes — measured on okhttp 4.12 by {@code FileChannelTest},
     *  {@code onClosed} never arrives otherwise. */
    private static final class Watching extends WebSocketListener {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicReference<String> reason = new AtomicReference<>("");
        private final List<String> received = Collections.synchronizedList(new ArrayList<>());
        private volatile WebSocket socket;

        @Override
        public void onMessage(WebSocket from, String text) {
            received.add(text);
        }

        /** The next frame this client has not yet been shown. Indexed rather
         *  than drained, so a test that asks twice gets two frames and a test
         *  that asks once cannot silently be reading the second one. */
        String awaitFrame(long millis) throws InterruptedException {
            for (long waited = 0; waited < millis; waited += 10) {
                synchronized (received) {
                    if (!received.isEmpty()) {
                        return received.remove(0);
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("no frame arrived in " + millis + "ms");
        }

        @Override
        public void onClosing(WebSocket from, int code, String said) {
            reason.set(said);
            from.close(1000, null);
            closed.countDown();
        }

        @Override
        public void onClosed(WebSocket from, int code, String said) {
            closed.countDown();
        }

        boolean awaitClosed(long millis) throws InterruptedException {
            return closed.await(millis, TimeUnit.MILLISECONDS);
        }

        void close() {
            socket.close(1000, null);
        }
    }

    /**
     * A listener whose socket records what it was written and, when asked, never
     * finishes its first write.
     *
     * <p>See the comment above {@code
     * a_response_is_queued_rather_than_written_by_the_thread_that_routed_it} for
     * why this file has a fake at all. What it adds over {@code JobEventTest}'s
     * equivalent is the close: this task's answer to a full queue is to
     * disconnect, so a fake that only recorded writes could not be pointed at
     * the decision.
     */
    private static final class Stalling implements WebSocketSession {

        private final Map<String, Object> attributes = new HashMap<>();
        private final List<String> frames = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch wedged = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicReference<String> closeReason = new AtomicReference<>("");
        private final boolean stalls;
        private volatile URI uri;
        private volatile boolean open = true;

        /** @param stalls whether the first write blocks until {@link #release} */
        Stalling(boolean stalls) {
            this.stalls = stalls;
        }

        List<String> frames() {
            synchronized (frames) {
                return List.copyOf(frames);
            }
        }

        /** Waits for at least {@code count} frames, then returns all of them. */
        List<String> await(int count, long millis) throws InterruptedException {
            for (long waited = 0; waited < millis; waited += 10) {
                if (frames().size() >= count) {
                    return frames();
                }
                Thread.sleep(10);
            }
            throw new AssertionError(
                    "only " + frames().size() + " frames arrived, wanted " + count);
        }

        /**
         * Waits for any frame containing {@code text}, rather than for a count.
         *
         * <p>A count is the wrong instrument for the isolation cases: how many
         * of a flood of deltas survive their own bounded queue is deliberately
         * unspecified, so the question those tests ask is whether one PARTICULAR
         * frame got through — not how many did.
         */
        boolean awaitFrameContaining(String text, long millis) throws InterruptedException {
            for (long waited = 0; waited < millis; waited += 10) {
                if (frames().stream().anyMatch(frame -> frame.contains(text))) {
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
        }

        boolean awaitWedged(long millis) throws InterruptedException {
            return wedged.await(millis, TimeUnit.MILLISECONDS);
        }

        boolean awaitClosed(long millis) throws InterruptedException {
            return closed.await(millis, TimeUnit.MILLISECONDS);
        }

        String closeReason() {
            return closeReason.get();
        }

        void release() {
            release.countDown();
        }

        /**
         * Blocks the first write until released, and records every write.
         *
         * <p>The recording is deliberately outside the stalling branch — {@code
         * JobEventTest} records having made the opposite mistake, and a fake
         * that never recorded the frame it was held open on could not afterwards
         * be asked what had been delivered.
         */
        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (stalls && wedged.getCount() > 0) {
                wedged.countDown();
                try {
                    if (!release.await(60, TimeUnit.SECONDS)) {
                        throw new IOException("the wedged socket was never released");
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted in the wedged socket", stopped);
                }
            }
            if (!open) {
                throw new IllegalStateException("this socket is closed");
            }
            frames.add(String.valueOf(message.getPayload()));
        }

        @Override
        public void close() {
            close(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
            closeReason.set(status.getReason() == null ? "" : status.getReason());
            closed.countDown();
        }

        @Override
        public String getId() {
            return "stalling";
        }

        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int limit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int limit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    /**
     * The web layer and nothing else — no Postgres, no model, no agent registry.
     *
     * <p>Three autoconfigurations by name rather than {@code @SpringBootTest},
     * for the reason {@code FileChannelTest} measured: a hand-built context with
     * a Tomcat factory answers the upgrade with a 500, because it is {@code
     * WebSocketServletAutoConfiguration} that registers Tomcat's {@code WsSci}.
     *
     * <p>{@link EventChannelConfig} is imported and nothing else is defined here,
     * so the handler, the path and the registry under test are the ones
     * production builds.
     */
    @Configuration
    // `Watchers` travels with the channel now: EventChannelConfig requires
    // it rather than inventing one, which is what stops the frame handler
    // and the channel holding different instances.
    @Import({EventChannelConfig.class, Watchers.class})
    @ImportAutoConfiguration({
            ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebSocketServletAutoConfiguration.class})
    static class Wiring {
    }
}
