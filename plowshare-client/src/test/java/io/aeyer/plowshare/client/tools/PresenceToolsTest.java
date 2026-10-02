package io.aeyer.plowshare.client.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.ClientPresence;
import io.aeyer.plowshare.client.HttpServerClient;
import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.files.Rooting;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one tool that says where this client is, and the thing it exists for.
 *
 * <h2>Why {@code agent_run} is asserted here too</h2>
 *
 * <p>Rooting a project is not an end in itself: the whole of it is that a run
 * started through this process then reaches <em>this</em> machine's files. Split
 * across two classes that claim would be two half-facts — a tool that dials, and
 * a tool that passes whatever it is handed — with nothing asserting that the id
 * on the run is the id the channel attached under. So the last section drives
 * both tools over one {@link MockWebServer} and reads the submission off the
 * wire.
 *
 * <h2>Loopback and an ephemeral port</h2>
 *
 * <p>{@code SessionClientTest}'s arrangement. Nothing here reaches a real machine
 * and no port is fixed; the directories are JUnit's own temporary ones, so the
 * only disk this touches is one the suite made.
 */
class PresenceToolsTest {

    /** Set rather than read: a hostname is a property of whatever box the suite
     *  runs on, and an assertion against one would pass here and nowhere else. */
    private static final String MACHINE = "bench.local";

    private MockWebServer server;
    private ServerClient http;
    private ClientPresence presence;
    private PresenceTools tools;

    private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    /** Set by the test that wants the file channel refused rather than upgraded. */
    private volatile int refuseFilesWith;

    private String machineWas;

    @TempDir
    Path ledger;

    @TempDir
    Path payments;

    @BeforeEach
    void start() throws IOException {
        machineWas = System.setProperty(Rooting.MACHINE_PROPERTY, MACHINE);
        refuseFilesWith = 0;
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/v1/files")) {
                    if (refuseFilesWith != 0) {
                        return new MockResponse().setResponseCode(refuseFilesWith);
                    }
                    return upgrade();
                }
                if (path.startsWith("/v1/events")) {
                    return upgrade();
                }
                if (path.endsWith("/runs")) {
                    // Read here, while the buffer still has it: a RecordedRequest
                    // hands its body over once.
                    bodies.add(request.getBody().readUtf8());
                    return new MockResponse()
                            .setResponseCode(202)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"job_000001\",\"agent\":\"scribe\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        http = new HttpServerClient(server.url("/").toString());
        presence = new ClientPresence(http, null);
        tools = new PresenceTools(presence);
    }

    @AfterEach
    void stop() throws IOException {
        presence.close();
        server.shutdown();
        if (machineWas == null) {
            System.clearProperty(Rooting.MACHINE_PROPERTY);
        } else {
            System.setProperty(Rooting.MACHINE_PROPERTY, machineWas);
        }
    }

    // --- what the surface offers ----------------------------------------------------

    @Test
    void the_rooting_tool_is_registered_under_its_own_name() {
        ToolRegistry registry = new ToolRegistry();

        tools.registerOn(registry);

        assertEquals(List.of("client_root_project_here"),
                registry.tools().stream().map(ToolRegistry.Tool::name).toList());
    }

    /**
     * It is not a fifth project verb, and it says so about each of the four.
     *
     * <p>The hazard {@code ProjectTools} already records: a model reads nothing
     * but the descriptions, so two tools whose texts could both answer "point
     * this project at where its files are" get chosen between by coin flip, and
     * the wrong pick is silent because either succeeds at what it does. The four
     * already name each other where they overlap; this one arrived after them and
     * has to name all four, because it is the only tool in the list that can talk
     * about a machine other than the server.
     */
    @Test
    void the_rooting_tool_names_every_project_verb_it_is_not() {
        for (String other : List.of("project_define", "project_workspace_set", "project_move",
                "project_forget")) {
            assertTrue(PresenceTools.ROOT_DESCRIPTION.contains(other),
                    "the description does not distinguish itself from " + other + ": "
                            + PresenceTools.ROOT_DESCRIPTION);
        }
    }

    // --- rooting --------------------------------------------------------------------

    @Test
    void rooting_names_the_project_the_place_and_the_machine() throws Exception {
        String out = (String) tools.root(args("project", "ledger", "path", ledger.toString()));

        assertTrue(out.contains("ledger"), out);
        assertTrue(out.contains(ledger.toRealPath().toString()), out);
        assertTrue(out.contains(MACHINE),
                "the machine is the half of a presence the server cannot know, so a person"
                        + " reading this must be told which name was asserted: " + out);
    }

    /**
     * Re-rooting says what it stopped, because that is the half nobody asked for.
     *
     * <p>This client roots one project at a time, so a second call is also a
     * withdrawal — and a caller told only about the new claim would believe the
     * old one is still being served from here.
     */
    @Test
    void rooting_something_else_says_what_it_stopped_rooting() {
        tools.root(args("project", "ledger", "path", ledger.toString()));

        String out = (String) tools.root(args("project", "payments", "path", payments.toString()));

        assertTrue(out.contains("payments"), out);
        assertTrue(out.contains("ledger"),
                "the answer did not say which project this machine stopped serving: " + out);
    }

    // --- what it refuses --------------------------------------------------------------

    /**
     * The refusal this whole tool exists to make possible.
     *
     * <p>A relative path would be resolved against whatever directory this
     * process happened to be launched in — which is precisely the directory a
     * harness must not lend on somebody's behalf. Refusing it is what keeps
     * rooting explicit rather than an implicit working directory wearing an
     * argument's clothes.
     */
    @Test
    void a_relative_path_is_refused_and_nothing_is_dialled() {
        String message = assertThrows(IllegalArgumentException.class,
                () -> tools.root(args("project", "ledger", "path", "src"))).getMessage();

        assertTrue(message.contains("absolute"), message);
        assertTrue(seen.isEmpty(), "the client dialled on a path it was going to refuse: " + seen);
    }

    @Test
    void a_path_that_is_not_a_directory_is_refused_before_anything_is_lent() {
        Path missing = ledger.resolve("not-there");

        String message = assertThrows(IllegalArgumentException.class,
                () -> tools.root(args("project", "ledger", "path", missing.toString())))
                .getMessage();

        assertTrue(message.contains(missing.toString()), message);
        assertTrue(seen.isEmpty(), "the client dialled on a path it was going to refuse: " + seen);
    }

    @Test
    void both_the_project_and_the_path_are_required() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> tools.root(args("path", ledger.toString()))).getMessage()
                .contains("project"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> tools.root(args("project", "ledger"))).getMessage().contains("path"));
    }

    /**
     * A channel that will not open is reported, and the sentence says what this
     * process is now — because it is not what it was.
     *
     * <p>The old presence was given back before the new claim went out, so a
     * failure here leaves this client serving nothing at all. Told only that
     * something failed, a caller would go on believing the previous project is
     * still rooted here.
     */
    @Test
    void a_channel_that_will_not_open_says_this_client_now_serves_nothing() {
        tools.root(args("project", "ledger", "path", ledger.toString()));
        refuseFilesWith = 404;

        String message = assertThrows(IllegalStateException.class,
                () -> tools.root(args("project", "payments", "path", payments.toString())))
                .getMessage();

        assertTrue(message.contains("payments"), message);
        assertTrue(message.contains("no files"),
                "the refusal did not say that this machine now serves nothing: " + message);
        assertTrue(presence.rooted() == null, "a rooting that failed was recorded anyway");
    }

    // --- and then a run reaches this machine -------------------------------------------

    /**
     * The point of all of it, read off the wire.
     *
     * <p>{@code AgentTools.run} passed {@code null} for the session because there
     * was no session to pass. What makes this assertion worth its sockets is the
     * <em>equality</em>: the id on the submission is the id the file channel
     * declared its presence under, so the run the server starts reaches this
     * machine rather than its own disks.
     */
    @Test
    void a_run_started_here_carries_the_session_the_file_channel_attached_under() {
        tools.root(args("project", "ledger", "path", ledger.toString()));

        new AgentTools(http, presence).run(args("agent", "scribe", "task", "read the ledger",
                "project", "ledger"));

        assertEquals(1, bodies.size(), "expected one submission, saw " + bodies);
        String declared = filesUpgrade().queryParameter("session");
        assertTrue(bodies.get(0).contains("\"session\":\"" + declared + "\""),
                "the run carried a session other than the one the channel attached under ("
                        + declared + "): " + bodies.get(0));
    }

    /**
     * The global tier carries it too, and that is the tier the id actually
     * decides.
     *
     * <p>Server-side, {@code AgentsConfig.runProviders} routes a named project to
     * whichever presence roots it and never looks at who asked. The <em>global</em>
     * tier has no place to root, so the only machine such a run can mean is the
     * one it came from — which makes this the one path where the id on the
     * submission is the whole of what reaches this machine, and the one that
     * silently reached the server's own disks before.
     */
    @Test
    void a_global_run_carries_it_too_which_is_the_tier_the_id_decides() {
        tools.root(args("project", "ledger", "path", ledger.toString()));

        new AgentTools(http, presence).run(args("agent", "scribe", "task", "go"));

        assertEquals(1, bodies.size(), "expected one submission, saw " + bodies);
        assertTrue(bodies.get(0).contains("\"project\":null"), bodies.get(0));
        assertTrue(bodies.get(0).contains(
                        "\"session\":\"" + filesUpgrade().queryParameter("session") + "\""),
                bodies.get(0));
    }

    /**
     * And before anything is rooted it carries none, which is the old behaviour
     * kept rather than an accident preserved.
     *
     * <p>A run with no session is a smaller capability and not an empty one: it
     * reaches the filesystems the server itself can see. What must not happen is
     * an id going out that names a machine nothing is serving.
     */
    @Test
    void a_run_started_before_anything_is_rooted_carries_no_session() {
        new AgentTools(http, presence).run(args("agent", "scribe", "task", "go"));

        assertEquals(1, bodies.size(), "expected one submission, saw " + bodies);
        assertTrue(bodies.get(0).contains("\"session\":null"), bodies.get(0));
        assertFalse(seen.stream().anyMatch(request ->
                        request.getPath() != null && request.getPath().startsWith("/v1/files")),
                "a client that roots nothing opened a file channel anyway: " + seen);
    }

    // --- plumbing ----------------------------------------------------------------------

    private HttpUrl filesUpgrade() {
        List<String> upgrades = seen.stream()
                .map(request -> request.getPath() == null ? "" : request.getPath())
                .filter(path -> path.startsWith("/v1/files"))
                .toList();
        assertEquals(1, upgrades.size(), "expected one file-channel upgrade, saw " + upgrades);
        return HttpUrl.parse("http://localhost" + upgrades.get(0));
    }

    private static Map<String, Object> args(String... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            map.put(pairs[at], pairs[at + 1]);
        }
        return map;
    }

    /** {@code onClosing} answers the close; {@code SessionClientTest} measured
     *  what happens without it, and {@code MockWebServer.shutdown} is what
     *  fails. */
    private static MockResponse upgrade() {
        return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, null);
            }

            @Override
            public void onOpen(WebSocket socket, Response response) {
                // Held by nobody: this class never sends a frame down one.
            }
        });
    }
}
