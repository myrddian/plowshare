package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.client.files.Rooting;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
 * What this process roots, and what a run started from it therefore carries.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@code PlowshareClient} built only an {@code HttpServerClient} and never a {@link
 * SessionClient}, so {@code AgentTools.run} passed no session — not as a decision but because there
 * was none. A foreign harness could start a Plowshare agent and that agent could not read a file on
 * the harness's own machine.
 *
 * <h2>Against a {@link MockWebServer} on an ephemeral port</h2>
 *
 * <p>{@code SessionClientTest}'s arrangement and for its reason: the interesting answers are ones a
 * working server never gives — an upgrade refused, a socket that closes underneath a live presence
 * — and a client-module test may not depend on the server module at all. Nothing here reaches a
 * real machine and nothing binds a fixed port.
 */
class ClientPresenceTest {

  /**
   * Long enough that a loopback socket on a loaded machine is not the thing being measured, and far
   * inside the suite's 120-second bound so a failure is an assertion rather than a timeout with no
   * message.
   */
  private static final Duration PATIENCE = Duration.ofSeconds(5);

  /**
   * What this box calls itself for the length of one test. Set rather than read, because a hostname
   * is a property of whatever machine the suite runs on and an assertion against it would pass here
   * and nowhere else.
   */
  private static final String MACHINE = "bench.local";

  private MockWebServer server;
  private ServerClient http;
  private ClientPresence presence;

  /**
   * Every request the server was given, in dispatch order. The question most of this class asks is
   * "what did the client declare when it dialled", which is a question about the query string of
   * one of these.
   */
  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();

  /**
   * The server's end of each file channel, so a test can take one away underneath a live presence.
   */
  private final List<WebSocket> serverFiles = new CopyOnWriteArrayList<>();

  /** Set by the test that wants the file channel refused rather than upgraded. */
  private volatile int refuseFilesWith;

  /**
   * The multipart body of the one image upload, or null if none was made. Kept as a string rather
   * than counted, because the question this class asks about it is which tier it named.
   */
  private volatile String uploaded;

  /** Every frame the client sent back down a file channel, in order. */
  private final List<String> answered = new CopyOnWriteArrayList<>();

  private String machineWas;

  @TempDir Path ledger;

  @TempDir Path payments;

  @BeforeEach
  void start() throws IOException {
    machineWas = System.setProperty(Rooting.MACHINE_PROPERTY, MACHINE);
    refuseFilesWith = 0;
    uploaded = null;
    answered.clear();
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            seen.add(request);
            String path = request.getPath() == null ? "" : request.getPath();
            if (path.startsWith("/v1/files")) {
              if (refuseFilesWith != 0) {
                return new MockResponse().setResponseCode(refuseFilesWith);
              }
              return upgrade(serverFiles::add);
            }
            if (path.startsWith("/v1/events")) {
              return upgrade(socket -> {});
            }
            if (path.startsWith("/v1/images")) {
              // ISO-8859-1, so the PNG signature survives as the bytes it
              // is: read as UTF-8 the 0x89 would come back as a
              // replacement character and the assertion would be about
              // this test's own decoding.
              uploaded = request.getBody().readString(StandardCharsets.ISO_8859_1);
              return json(
                  201,
                  """
                            {"id":"img_00000000000000000009","project":"ledger",\
                             "format":"png"}""");
            }
            return new MockResponse().setResponseCode(404);
          }
        });
    server.start();
    http = new HttpServerClient(server.url("/").toString());
    presence = new ClientPresence(http, null, PATIENCE);
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

  // --- before anything is rooted --------------------------------------------

  /**
   * The state this process starts in, and it is a decision rather than a gap.
   *
   * <p>Rooting is explicit and user-initiated: a harness that spawned this must not have whatever
   * directory it happened to be launched in lent on its behalf. So until somebody says what to
   * serve there is nothing to serve, and — the half this asserts — <b>nothing is dialled
   * either</b>. That is what keeps {@code PlowshareClient}'s "no reachability check at startup"
   * true: a client whose server is not up is one that has not been asked to root anything yet, and
   * it still shows a harness every tool it has.
   */
  @Test
  void before_anything_is_rooted_this_client_serves_nothing_and_has_dialled_nothing() {
    assertNull(presence.rooted(), "a client that was never asked to root anything roots one");
    assertNull(
        presence.sessionForRun("scribe"),
        "a run started before anything is rooted has no session to carry: this process"
            + " holds no channel, so an id would name a machine nothing is serving");
    assertEquals(
        0,
        seen.size(),
        "the client dialled the server before it was asked to root anything: " + paths());
  }

  // --- rooting ---------------------------------------------------------------

  /**
   * All four parameters on the upgrade, which is the presence design's "all three or none" plus the
   * session id that was always there.
   *
   * <p>On the upgrade and not in a later frame, because a window in which a session holds a disk
   * and roots nothing is a window in which a run silently gets the smaller set.
   */
  @Test
  void rooting_declares_the_session_the_machine_the_root_and_the_project_on_the_upgrade()
      throws Exception {
    Rooting rooted = presence.root(ledger, "ledger");

    HttpUrl dialled = filesUpgrade();
    assertEquals(presence.sessionForRun("scribe"), dialled.queryParameter("session"));
    assertEquals(MACHINE, dialled.queryParameter("machine"));
    assertEquals(
        ledger.toRealPath().toString(),
        dialled.queryParameter("root"),
        "the root is one component of an identity, so it travels resolved: two spellings"
            + " of one place must not be two projects");
    assertEquals("ledger", dialled.queryParameter("project"));
    assertEquals(rooted, presence.rooted());
  }

  /**
   * The whole point of the exercise: the id a run carries is the id the file channel attached
   * under, so the run reaches this machine.
   */
  @Test
  void the_session_a_run_carries_is_the_one_the_file_channel_attached_under() throws Exception {
    presence.root(ledger, "ledger");

    String forRun = presence.sessionForRun("scribe");

    assertEquals(
        4,
        UUID.fromString(forRun).version(),
        "the id a run carries is minted by SessionClient or it is not a session id");
    assertEquals(filesUpgrade().queryParameter("session"), forRun);
  }

  /**
   * A presence is declared on an upgrade, so changing one is a reconnect.
   *
   * <p>It cannot be a frame: the declaration <em>is</em> the query string. So a client asked to
   * root something else closes what it held and dials again, under a new id.
   */
  @Test
  void rooting_something_else_reconnects_with_the_new_declaration() throws Exception {
    presence.root(ledger, "ledger");
    String first = presence.sessionForRun("scribe");

    presence.root(payments, "payments");

    List<HttpUrl> upgrades = filesUpgrades();
    assertEquals(2, upgrades.size(), "re-rooting did not reconnect: " + paths());
    assertEquals("ledger", upgrades.get(0).queryParameter("project"));
    assertEquals("payments", upgrades.get(1).queryParameter("project"));
    assertEquals(payments.toRealPath().toString(), upgrades.get(1).queryParameter("root"));
    assertNotEquals(
        first,
        presence.sessionForRun("scribe"),
        "the second declaration went out under the id the first one used, which is a"
            + " session mutating what it roots rather than a reconnect");
  }

  /**
   * The old socket goes before the new one is dialled, and the order is the decision.
   *
   * <p>A second live session claiming a project another session already roots is refused by the
   * server, naming the holder. So a client that opened the new socket first would be refused <b>by
   * itself</b> the moment somebody re-rooted the same project at a corrected path — which is the
   * commonest reason to call this twice.
   */
  @Test
  void the_channel_it_held_is_closed_before_the_next_one_is_dialled() throws Exception {
    presence.root(ledger, "ledger");
    await(() -> serverFiles.size() == 1, "the first file channel never opened");
    WebSocket first = serverFiles.get(0);

    presence.root(ledger, "ledger");

    assertEquals(2, filesUpgrades().size(), "expected two upgrades, saw " + paths());
    assertNotEquals(
        first,
        serverFiles.get(serverFiles.size() - 1),
        "the client dialled again on the socket it already held");
  }

  // --- when it cannot be done --------------------------------------------------

  /**
   * A degraded client and not a dead one.
   *
   * <p>The failure is reported to whoever asked and this process goes on serving every other tool.
   * What it must not do is claim a presence it does not hold: a run carrying the id of a channel
   * that never opened would reach the server's own filesystems and report nothing amiss.
   */
  @Test
  void a_channel_that_will_not_open_leaves_this_client_rooting_nothing() {
    refuseFilesWith = 404;

    IOException refused = assertThrows(IOException.class, () -> presence.root(ledger, "ledger"));

    assertTrue(
        refused.getMessage().contains("v1/files"),
        "the refusal did not name the channel that would not open: " + refused.getMessage());
    assertNull(presence.rooted(), "a rooting that failed was recorded anyway");
    assertNull(
        presence.sessionForRun("scribe"),
        "a run started now would carry the id of a channel that never opened");
  }

  /**
   * A presence that has gone is not a session id worth handing out.
   *
   * <p>{@code SessionClient.submit} refuses for this reason and the sentence is its: the server
   * accepts a session id it has never seen attached, so a run submitted over a dead channel
   * executes against the server's own filesystems and nothing says so. This is that same guard
   * reached from the MCP side rather than a second copy of it.
   */
  @Test
  void a_channel_that_has_since_closed_refuses_to_lend_its_id_to_a_run() throws Exception {
    presence.root(ledger, "ledger");
    await(() -> !serverFiles.isEmpty(), "the file channel never opened");

    serverFiles.get(0).close(1000, "the machine that roots this went away");
    await(() -> !presence.serving(), "the client never noticed its channel close");

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> presence.sessionForRun("scribe"));
    assertTrue(
        refused.getMessage().contains("has since closed"),
        "the refusal did not distinguish a socket that went away from one that never"
            + " opened: "
            + refused.getMessage());
  }

  /**
   * Closing the process gives the presence back rather than leaving a client that reports rooting a
   * project no socket is serving.
   */
  @Test
  void closing_this_client_stops_it_claiming_anything() throws Exception {
    presence.root(ledger, "ledger");

    presence.close();

    assertNull(presence.rooted());
    assertNull(presence.sessionForRun("scribe"));
  }

  // --- a picture in the rooted project's workspace ---------------------------

  /**
   * <b>A picture read out of the rooted project's workspace is uploaded, and it is filed under that
   * project.</b>
   *
   * <p>The remote half of §6a end to end, over a real socket: a read arrives on the file channel,
   * the client opens the file under its own leash, the bytes go up over HTTP because {@code
   * FileRequest} has no operation that could carry them back, and what returns down the channel is
   * a name.
   *
   * <p><b>The tier is the assertion and it is the part nothing else could check.</b> {@code
   * AgentRunTool} resolves an image id against the home its own run was given and no other, so a
   * picture filed in the wrong tier produces an id that is real, unguessable and useless — and
   * useless one hop away from anything that could explain it. {@code SessionClient.uploads} argues
   * at length that this session's {@link Rooting} is the right tier <em>because</em> of how a file
   * request reaches this machine at all: a run in a named project reaches the session that roots
   * it. Nothing measured that. Replacing {@code rooted.project()} with {@code null} — the global
   * tier — left the entire suite green, which is how this test came to exist.
   *
   * <p>It also holds the wiring itself: a {@code SessionClient} built with {@code
   * ImageUploads.NONE} would answer "not UTF-8 text" here and every other test in this tree would
   * go on passing.
   */
  @Test
  void a_picture_in_the_rooted_workspace_is_uploaded_under_that_project() throws Exception {
    Files.write(
        ledger.resolve("logo.png"),
        new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 42});
    presence.root(ledger, "ledger");
    await(() -> !serverFiles.isEmpty(), "the file channel never opened");

    serverFiles
        .get(0)
        .send(
            new ObjectMapper()
                .writeValueAsString(
                    FileRequest.read(
                        "r1",
                        ledger.toRealPath().resolve("logo.png").toString(),
                        Window.of(0, Window.MAX_WINDOW_LINES))));
    await(() -> !answered.isEmpty(), "the client never answered the read");

    assertTrue(
        answered.get(0).contains("img_00000000000000000009"),
        "the read did not come back as a name: " + answered.get(0));
    assertTrue(
        uploaded != null && uploaded.contains("\u0089PNG"),
        "the picture's bytes never reached the server: " + uploaded);
    assertTrue(uploaded.contains("name=\"project\""), uploaded);
    assertTrue(
        uploaded.contains("ledger"),
        "the picture was filed in a tier this session does not root, so the id it"
            + " answered with resolves nowhere the run can see: "
            + uploaded);
  }

  // --- plumbing ----------------------------------------------------------------

  private HttpUrl filesUpgrade() {
    List<HttpUrl> upgrades = filesUpgrades();
    assertEquals(1, upgrades.size(), "expected one file-channel upgrade, saw " + paths());
    return upgrades.get(0);
  }

  private List<HttpUrl> filesUpgrades() {
    return seen.stream()
        .map(request -> request.getPath() == null ? "" : request.getPath())
        .filter(path -> path.startsWith("/v1/files"))
        .map(path -> HttpUrl.parse("http://localhost" + path))
        .toList();
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private String paths() {
    return seen.stream().map(RecordedRequest::getPath).toList().toString();
  }

  /**
   * Poll rather than sleep: what is being waited for is a socket callback on okhttp's reader
   * thread, and a fixed pause would be either flaky or slow.
   */
  private static void await(BooleanSupplier condition, String complaint)
      throws InterruptedException {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(1);
    }
    fail(complaint);
  }

  /**
   * The server's end of one socket.
   *
   * <p>{@code onClosing} answers the close, and it is not decoration: {@code SessionClientTest}
   * measured that without it the handshake never completes, the connection stays open, and {@link
   * MockWebServer#shutdown()} fails every test in the class with "Gave up waiting for queue to shut
   * down" <b>after the assertions have passed</b>. This class re-measured it, by omitting the
   * override first.
   */
  private MockResponse upgrade(Consumer<WebSocket> keep) {
    Consumer<String> frames = answered::add;
    return new MockResponse()
        .withWebSocketUpgrade(
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket socket, Response response) {
                keep.accept(socket);
              }

              @Override
              public void onMessage(WebSocket socket, String frame) {
                frames.accept(frame);
              }

              @Override
              public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, null);
              }
            });
  }
}
