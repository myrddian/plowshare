package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.agents.FileTools;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.testpeer.NodeFiles;
import io.aeyer.plowshare.testpeer.SocketPeer;
import io.aeyer.plowshare.testpeer.TestWorkspace;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * The channel itself, over a real socket on loopback.
 *
 * <h2>What only a real port can measure</h2>
 *
 * <p>{@code RemoteProviderTest} covers what the provider does with an answer, against a two-line
 * fake. Everything here needs the socket: that a close is an <em>event</em> rather than a deadline,
 * that a wedged client is bounded by one, that two answers in flight do not cross, and that a
 * workspace moved on the client is refused by the client. None of those can be faked without faking
 * the thing being measured.
 *
 * <p>Both halves of Plowshare are on this module's test classpath — the build file says so and says
 * it is test-only and one-directional — so the client that connects here is the real {@link
 * SocketPeer}, okhttp and all, and not a stand-in for it.
 *
 * <h2>Loopback and nothing else</h2>
 *
 * <p>{@code server.port=0} on the command line, so the port is whatever was free. <b>Measured:</b>
 * {@code SpringApplicationBuilder.properties(...)} is <em>default</em> properties and loses to
 * {@code application.yml}, which names 8091 — the first version of this class bound the real
 * server's port and would have collided with anything running on the box. A command-line argument
 * wins.
 *
 * <h2>Two handlers, because one constant cannot be measured from both sides</h2>
 *
 * <p>The production handler is on {@link FileChannelHandler#PATH} with its real deadline, and is
 * what {@link SocketPeer} connects to. A second one, on a path only this file knows, carries a
 * deadline of {@link #FAST} so that the refused side can be measured without adding thirty seconds
 * to every run of the suite.
 *
 * <h2>What is measured here rather than assumed</h2>
 *
 * <ul>
 *   <li><b>a clean close reaches the server in single-digit milliseconds</b>, and an abrupt {@code
 *       cancel} — which is what a killed client process looks like — in about one. Both are orders
 *       of magnitude under any usable deadline, which is the whole of what makes "gone"
 *       distinguishable from "slow" in the cases where it is distinguishable at all;
 *   <li><b>a wedged-but-connected client fires nothing</b>: no callback, and the session still
 *       reports itself open. The deadline is the only instrument that case has;
 *   <li><b>okhttp's {@code WebSocket.send} takes concurrent callers</b> — the client answers each
 *       request on its own virtual thread, so this is the ordinary case rather than a corner.
 *       {@code many_requests_in_flight_at_once_never_get_each_others_answers} is what measures it,
 *       and it is the same test that measures correlation;
 *   <li><b>Spring's {@code WebSocketSession.sendMessage} does not</b>: eight threads sending at
 *       once produced seven {@code IllegalStateException}s naming {@code TEXT_PARTIAL_WRITING},
 *       which is why the handler holds a lock. The same test covers this half, from the server's
 *       side;
 *   <li><b>a payload larger than the container's own text buffer crosses and leaves the session
 *       open</b>, read off a real disk by a real client rather than assembled here. That is the
 *       case this class did not have — the 1 MiB fixture lived in {@code LocalProviderTest}, on the
 *       half of the doubled enforcement with no wire in it — and it is the whole of why a green
 *       build watched a live run die of it. {@code
 *       the_file_that_killed_a_run_crosses_this_wire_whole} is the shape it happened in, and the
 *       test in the same group whose name says a line ends the session it arrives on pins the one
 *       shape that still dies.
 * </ul>
 *
 * <h2>Nothing here is authenticated, and that is not evidence about the gate</h2>
 *
 * <p>This file's {@code Wiring} does not import {@code AuthConfig}, so {@code AuthFilter} is
 * registered in no context it builds and every request and every upgrade below is anonymous. <b>Its
 * green therefore says nothing about whether the server has a gate at all</b> — measured, by making
 * {@code AuthFilter.doFilter} refuse every request unconditionally and running this class: still
 * green. The property that {@code /v1} is closed is held by {@code AuthFilterTest}, whose {@code
 * Wiring} imports the production {@code AuthConfig} and whose {@code
 * every_route_this_application_publishes_is_gated_or_deliberately_open} enumerates the tree; a
 * route added here is covered by that scan and not by anything in this file.
 */
class FileChannelTest {

  @Test
  @org.junit.jupiter.api.Tag("node-source")
  void the_node_client_streams_pdf_bytes_to_the_real_java_server(@TempDir Path root)
      throws Exception {
    Path pdf = root.toRealPath().resolve("node.pdf");
    Files.write(pdf, io.aeyer.plowshare.server.documents.Pdfs.of("Node bytes Java conversion"));
    String session = "node-source-" + java.util.UUID.randomUUID();
    String script =
        "import { enforcing } from 'plowshare-client-node/enforcer';"
            + "import { serve } from 'plowshare-client-ts/binding/channel';"
            + "const socket=new WebSocket(process.argv[1]);"
            + "await new Promise((ok,no)=>{socket.addEventListener('open',ok,{once:true});socket.addEventListener('error',()=>no(new Error('open failed')),{once:true})});"
            + "serve({socket,answer:enforcing(process.argv[2],false)});"
            + "socket.addEventListener('close',()=>process.exit(0));";
    Process node =
        new ProcessBuilder(
                "node",
                "--input-type=module",
                "-e",
                script,
                "ws://localhost:" + port + "/v1/files?session=" + session + "&source=1",
                root.toRealPath().toString())
            .directory(new java.io.File(System.getProperty("plowshare.node.project")))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    try {
      awaitAttachmentOtherThan(productionSessions, session, null);
      assertTrue(production.sources(session));
      RemoteProvider provider = new RemoteProvider(production, session, WRITE);
      assertEquals(
          List.of("Node bytes Java conversion"), provider.read(pdf, new Window(0, 1)).lines());
      assertEquals(1, provider.stat(pdf).totalLines());
      assertEquals(1, provider.grep(new Needle("Java", false), pdf).matches().size());
      Files.write(pdf, io.aeyer.plowshare.server.documents.Pdfs.of("Fresh changed text"));
      assertEquals(List.of("Fresh changed text"), provider.read(pdf, new Window(0, 1)).lines());
      Path search = Files.createDirectory(root.toRealPath().resolve("search"));
      Files.writeString(search.resolve("notes.txt"), "Directory text search");
      Files.write(
          search.resolve("ignored.pdf"),
          io.aeyer.plowshare.server.documents.Pdfs.of("Directory text search"));
      var found = provider.grep(new Needle("Directory text", false), search).matches();
      assertEquals(1, found.size());
      assertEquals(search.resolve("notes.txt").toString(), found.getFirst().path());
      byte[] png =
          java.util.Base64.getDecoder()
              .decode(
                  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfJkAAAAASUVORK5CYII=");
      Path picture = root.toRealPath().resolve("pixel.png");
      Files.write(picture, png);
      var images =
          new io.aeyer.plowshare.server.images.ImageStore(
              home -> root.resolve("stored").resolve(home.isGlobal() ? "global" : home.project()),
              4096);
      var home = Home.of("node-project");
      RemoteProvider pictures = new RemoteProvider(production, session, WRITE, home, images);
      assertTrue(
          pictures
              .read(picture, new Window(0, 1))
              .lines()
              .getFirst()
              .contains(io.aeyer.plowshare.server.images.ImageStore.idFor(png)));
      assertTrue(
          images.find(home, io.aeyer.plowshare.server.images.ImageStore.idFor(png)).isPresent());
      assertTrue(
          images
              .find(Home.global(), io.aeyer.plowshare.server.images.ImageStore.idFor(png))
              .isEmpty());
      assertThrows(
          WorkspaceRefusedException.class,
          () -> provider.read(root.resolve("../outside.pdf"), new Window(0, 1)));
    } finally {
      node.destroy();
      if (!node.waitFor(3, TimeUnit.SECONDS)) {
        node.destroyForcibly();
        node.waitFor();
      }
    }
  }

  @Test
  void a_pdf_crosses_as_source_bytes_and_the_server_serves_cached_text_windows(@TempDir Path tmp)
      throws Exception {
    Path pdf = tmp.resolve("streamed.pdf");
    Files.write(pdf, io.aeyer.plowshare.server.documents.Pdfs.of("Converted on server"));
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(tmp));
    String session = "server-conversion-" + java.util.UUID.randomUUID();
    client(session, workspace);
    assertTrue(production.sources(session));
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);
    assertEquals(List.of("Converted on server"), provider.read(pdf, new Window(0, 1)).lines());
    assertEquals(1, provider.stat(pdf).totalLines());
    assertEquals(1, provider.grep(new Needle("server", false), pdf).matches().size());
    Path search = Files.createDirectory(tmp.resolve("search"));
    Files.writeString(search.resolve("notes.txt"), "Directory text search");
    Files.write(
        search.resolve("ignored.pdf"),
        io.aeyer.plowshare.server.documents.Pdfs.of("Directory text search"));
    assertEquals(1, provider.grep(new Needle("Directory text", false), search).matches().size());
    Files.write(pdf, io.aeyer.plowshare.server.documents.Pdfs.of("A different document"));
    assertEquals(List.of("A different document"), provider.read(pdf, new Window(0, 1)).lines());
    Files.delete(pdf);
    assertThrows(WorkspaceRefusedException.class, () -> provider.read(pdf, new Window(0, 1)));
  }

  /**
   * The deadline the second handler carries.
   *
   * <p>Long enough that nothing here times out by accident on a loaded machine, short enough that
   * the two tests which wait it out cost under a second between them.
   */
  private static final Duration FAST = Duration.ofMillis(400);

  /** Where the short-deadline handler lives. Only this file knows it. */
  private static final String FAST_PATH = "/v1/files-with-a-short-deadline";

  private static final List<Grant> WRITE = List.of(new Grant(Scope.WORKSPACE, Mode.WRITE));

  /** The query parameter {@code Wiring.namesTheAccount} reads the socket's account from. */
  private static final String AS_PARAM = "as";

  private static ConfigurableApplicationContext context;
  private static FileChannelHandler production;
  private static FileChannelHandler fast;

  /**
   * The registry the production handler attaches to — the one {@code EventChannelConfig} defines.
   * Read rather than {@code isConnected} so that {@link #connect} can wait on WHICH socket is
   * attached and not merely on whether one is; {@link #awaitAttachmentOtherThan} says why that
   * matters.
   */
  private static SessionRegistry productionSessions;

  /**
   * The presence registry the production handler declares into — the one {@code FileChannelConfig}
   * defines. Read here for the reason {@link #productionSessions} is: what the socket did is
   * asserted against the registry the rest of the server reads, and never against a copy.
   */
  private static PresenceRegistry productionPresences;

  private static int port;
  private static OkHttpClient http;

  private final List<AutoCloseable> opened = new ArrayList<>();

  @TempDir Path tmp;

  @BeforeAll
  static void startTheServer() {
    context =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            // A command-line argument, not properties(...): measured, the
            // latter is default properties and application.yml wins.
            .run("--server.port=0", "--server.address=127.0.0.1");
    port = ((WebServerApplicationContext) context).getWebServer().getPort();
    // The one FileChannelConfig built, so this file drives the production
    // wiring rather than a copy of it.
    production = context.getBean(FileChannelHandler.class);
    productionSessions = context.getBean(SessionRegistry.class);
    productionPresences = context.getBean(PresenceRegistry.class);
    fast = Wiring.FAST_HANDLER;
    http = new OkHttpClient.Builder().build();
  }

  @AfterAll
  static void stopTheServer() {
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
    context.close();
  }

  @AfterEach
  void closeWhatThisTestOpened() throws Exception {
    for (AutoCloseable open : opened) {
      open.close();
    }
    opened.clear();
    // AND THE RECORDER, which is static because the context is. Measured
    // rather than reasoned about: without this, the test that makes it
    // refuse a claim leaves it refusing, and the next test whose socket
    // declares a project has that socket closed on arrival — failing three
    // tests later with "session never registered a socket" and nothing in
    // the sentence pointing back here.
    Wiring.ROOTED.clear();
  }

  // --- the two bounds ------------------------------------------------------

  @Test
  void a_closed_socket_fails_every_outstanding_request_at_once() throws Exception {
    // NOT "eventually" and NOT "after the deadline": the close is the event,
    // and that is the whole reason this is a WebSocket rather than polling.
    String session = "closing";
    Silent silent = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    List<CompletableFuture<Throwable>> waiting = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      waiting.add(
          CompletableFuture.supplyAsync(
              () -> caught(() -> provider.read(Path.of("/laptop/repo/A.java"), FIRST))));
    }
    silent.awaitRequests(5);

    long before = System.nanoTime();
    silent.socket.close(1000, "going away");
    for (CompletableFuture<Throwable> one : waiting) {
      assertTrue(
          one.get(10, TimeUnit.SECONDS) instanceof SessionGoneException,
          "the request fails on the close, not on the deadline — " + one.get());
    }
    long elapsed = (System.nanoTime() - before) / 1_000_000;

    // THE SECOND ASSERTION IS THE WHOLE TEST. Without it a build with no
    // close detection at all — one that only ever times out — passes.
    assertTrue(
        elapsed < FAST.toMillis() / 2,
        "and all five failed well before the deadline would have fired: "
            + elapsed
            + "ms against a deadline of "
            + FAST.toMillis()
            + "ms");
    String said = waiting.get(0).get().getMessage();
    assertTrue(
        said.contains("closed"),
        "the sentence says the session closed, which is a different thing for an"
            + " operator to read from a session that went quiet — "
            + said);
    assertFalse(
        said.contains("Exception"),
        "and it is the close's OWN sentence rather than a wrapper around it. The"
            + " sweep found this: unwrapping the cause changes nothing a caller"
            + " can catch, so only the words say whether it happened —"
            + " JobRuntime.describe puts this string into the run's detail, and a"
            + " nested Java type name there is noise between an operator and the"
            + " reason — "
            + said);
  }

  @Test
  // A bound on the test as well as on the code, and the sweep is why. The
  // mutant that replaces the deadline with an unbounded `get` does not fail
  // this test — it makes it HANG, which reads to a harness exactly like a slow
  // machine and left a mutant in the tree when the run was killed. Thirty
  // seconds is far above FAST and far below anybody's patience.
  @Timeout(30)
  void a_wedged_but_connected_client_is_bounded_by_the_deadline() throws Exception {
    // Measured: a client that is connected and not answering fires nothing
    // at all — no callback, and the session still reports itself open. There
    // is no event to hang anything on, so the deadline is the only
    // instrument this case has.
    String session = "wedged";
    Silent silent = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    long before = System.nanoTime();
    WorkspaceUnavailableException gone =
        assertThrows(
            WorkspaceUnavailableException.class,
            () -> provider.read(Path.of("/laptop/repo/A.java"), FIRST));
    long elapsed = (System.nanoTime() - before) / 1_000_000;

    assertTrue(silent.socket.send("still here"), "the socket really was still open");
    assertTrue(
        elapsed >= FAST.toMillis(),
        "it waited the deadline out rather than giving up early: " + elapsed + "ms");
    assertTrue(gone.getMessage().contains("did not answer"), gone.getMessage());
    assertFalse(
        gone.getMessage().contains("closed"),
        "and it does not claim the session closed, which it plainly has not — "
            + gone.getMessage());
    // AND IT IS NOT SessionGoneException, which is the choice task 8 made
    // rather than a gap it left. The socket is open — the send above proves
    // it — and nothing here can tell a wedged client from a slow one, so an
    // ending saying "the session went away" would be naming a situation that
    // does not hold about a laptop that is merely busy. The run stops either
    // way; only the sentence differs, and the sentence is why there are two
    // endings at all.
    assertFalse(
        gone instanceof SessionGoneException,
        "a connected client that has not answered yet has not gone anywhere — "
            + gone.getMessage());
  }

  @Test
  void a_client_that_takes_its_time_is_not_a_client_that_is_gone() throws Exception {
    // The accepted side of the deadline, and it is the PRODUCTION handler
    // rather than the fast one: a fixture built from the constant it is
    // meant to pin holds nothing. 250 is written as a literal here, so
    // lowering FileChannelHandler.DEADLINE below a quarter of a second fails
    // this test.
    //
    // What it does NOT pin is thirty seconds. Between this literal and that
    // number is a judgement about how slow somebody's laptop is allowed to
    // be, with no instrument — the same admission LocalProvider.MAX_FILE_BYTES
    // makes about its ceiling, and saying so is better than a test that
    // appears to make it.
    String session = "slow";
    answering(
        FileChannelHandler.PATH,
        session,
        request -> {
          sleep(250);
          return FileReply.answered(request.id(), one("worth waiting for"));
        });

    assertEquals(
        "worth waiting for",
        text(new RemoteProvider(production, session, WRITE), Path.of("/laptop/A.java")));
  }

  @Test
  void the_production_deadline_is_the_one_the_default_constructor_uses() {
    // The number itself, pinned so that a change to it is a change to a
    // test. It is a judgement rather than a measurement, and this is what
    // makes it a deliberate one.
    assertEquals(Duration.ofSeconds(30), FileChannelHandler.DEADLINE);
  }

  // --- correlation ---------------------------------------------------------

  @Test
  void many_requests_in_flight_at_once_never_get_each_others_answers() throws Exception {
    // Correlation is the id and nothing else, because a job is a virtual
    // thread and several are blocked on this one socket at any moment. A
    // channel that matched by arrival order would hand one job another job's
    // file, which is the failure this whole slice is written against.
    //
    // It is also a measurement of both send paths, and neither half is forty.
    // The answers really do go out from forty virtual threads, but they are
    // the SILENT FIXTURE'S — it starts one per request in its own onMessage,
    // exactly as SocketPeer does, which is why the fixture is built that
    // way. This test does not exercise SocketPeer at all, and crediting it
    // here was a claim about a class this method never constructs.
    //
    // The server side is smaller still: supplyAsync uses the common pool,
    // which is core-count wide, so a handful of concurrent callers reach
    // Spring's sendMessage rather than forty. Enough to exercise the lock,
    // not enough to be the measurement of it — the eight-thread figure
    // recorded in FileChannelHandler is that, where unsynchronised sends
    // failed 283 times in 320.
    String session = "crowd";
    answering(
        FileChannelHandler.PATH,
        session,
        request -> {
          // A different delay per request, so an implementation that matched
          // answers to requests in order gets them in a different order from
          // the one they were asked in.
          sleep(Integer.parseInt(request.path().substring("/laptop/".length())) % 7 * 3L);
          return FileReply.answered(request.id(), one("file " + request.path()));
        });
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    int many = 40;
    CountDownLatch go = new CountDownLatch(1);
    List<CompletableFuture<String>> answers = new ArrayList<>();
    for (int i = 0; i < many; i++) {
      String path = "/laptop/" + i;
      answers.add(
          CompletableFuture.supplyAsync(
              () -> {
                await(go);
                return text(provider, Path.of(path));
              }));
    }
    go.countDown();

    Set<String> got = new HashSet<>();
    for (int i = 0; i < many; i++) {
      String answer = answers.get(i).get(30, TimeUnit.SECONDS);
      assertEquals("file /laptop/" + i, answer, "each caller got the answer to its own question");
      got.add(answer);
    }
    assertEquals(many, got.size(), "forty questions, forty distinct answers");
  }

  @Test
  void an_answer_that_arrives_after_its_own_deadline_is_dropped() throws Exception {
    // A late answer must not be handed to whatever asked next. It cannot be:
    // the ids are unique, so the map has nothing under it. This is the
    // instrument for that, because the failure it prevents — one job reading
    // another job's file — leaves no other trace.
    String session = "late";
    Silent silent = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    assertThrows(
        WorkspaceUnavailableException.class, () -> provider.read(Path.of("/laptop/first"), FIRST));
    FileRequest abandoned = silent.requests.poll();
    silent.socket.send(
        json()
            .writeValueAsString(
                FileReply.answered(abandoned.id(), one("the answer nobody is waiting for"))));

    // The next request gets its own answer or none, and never that one.
    CompletableFuture<Throwable> second =
        CompletableFuture.supplyAsync(
            () -> caught(() -> provider.read(Path.of("/laptop/second"), FIRST)));
    assertTrue(
        second.get(10, TimeUnit.SECONDS) instanceof WorkspaceUnavailableException,
        "the late answer did not satisfy a question it was not asked");
  }

  @Test
  void the_map_does_not_grow_by_one_every_time_a_client_does_not_answer() throws Exception {
    // A leak is invisible to every assertion that can be written about an
    // answer: the request has already thrown, and a late answer to an entry
    // nobody removed just completes a future nothing is holding. But a
    // session is meant to live as long as somebody's editor, and one entry
    // per timeout for that long is a slow death. The sweep is what asked for
    // this test — without it the mutant deleting that `finally` survives.
    String session = "leaking";
    connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    for (int i = 0; i < 3; i++) {
      assertThrows(
          WorkspaceUnavailableException.class,
          () -> provider.read(Path.of("/laptop/A.java"), FIRST));
    }

    assertEquals(
        0, fast.outstanding(session), "three requests went unanswered and nothing was left behind");
  }

  // --- the client is the one who decides -----------------------------------

  @Test
  void the_client_enforces_against_its_own_current_workspace_and_not_the_servers()
      throws Exception {
    // The human moved the workspace after the server routed. This is the
    // race the spec says is the doubled enforcement working rather than
    // something to fix — and it is built deliberately here rather than
    // waited for: the server reads the roots, the workspace moves, and only
    // then does the read go out.
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Path elsewhere = Files.createDirectory(tmp.resolve("elsewhere"));
    Files.writeString(repo.resolve("A.java"), "class A {}\n");

    String session = "moving";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    // What the server would route on.
    assertEquals(List.of(repo.toRealPath()), provider.roots());
    workspace.set(List.of(elsewhere));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class, () -> provider.read(repo.resolve("A.java"), FIRST));

    assertTrue(
        refused.getMessage().contains("outside")
            && refused.getMessage().contains(elsewhere.toRealPath().toString()),
        "the model is told the current root and cannot read the withdrawn workspace — "
            + refused.getMessage());
    // Correctable rather than an ending — every other path on this run still
    // works — and the types carry that: WorkspaceRefusedException and
    // WorkspaceUnavailableException share no supertype, which is what lets
    // JobRuntime.dependencyFailure name one without the other. assertThrows
    // above is the whole of the assertion; an instanceof against the other
    // type does not even compile, which is the guarantee working.
  }

  @Test
  void the_roots_a_client_advertises_are_whatever_it_holds_at_that_moment() throws Exception {
    // "Re-advertised on workspace_set" with no advertisement frame and no
    // server-side copy: FileProvider.roots() forbids caching, so every ask
    // is answered from the workspace as it stands. A client that had pushed
    // its roots once would need something to invalidate.
    Path first = Files.createDirectory(tmp.resolve("first"));
    Path second = Files.createDirectory(tmp.resolve("second"));

    String session = "readvertising";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(first));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    assertEquals(List.of(first.toRealPath()), provider.roots());
    workspace.set(List.of(second));
    assertEquals(
        List.of(second.toRealPath()),
        provider.roots(),
        "no invalidation, no advertisement frame, no stale copy to go wrong");
  }

  @Test
  void a_real_file_goes_over_the_wire_in_both_directions() throws Exception {
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Files.writeString(repo.resolve("Read.java"), "class Read {}\n");

    String session = "endtoend";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    // Without the file's own trailing newline, which the lines carrier does
    // not deliver: a terminator is not part of the line it ended, and the
    // reply has nowhere else to put it. FileReply owns that argument.
    assertEquals("class Read {}", text(provider, repo.resolve("Read.java")));
    provider.write(repo.resolve("sub/Written.java"), "class Written {}\n");
    assertEquals("class Written {}\n", Files.readString(repo.resolve("sub/Written.java")));
    assertEquals(
        Set.of(
            repo.toRealPath().resolve("Read.java"), repo.toRealPath().resolve("sub/Written.java")),
        new HashSet<>(provider.glob("**/*.java")));
  }

  @Test
  void a_refusal_assembled_on_the_client_arrives_as_one_the_model_can_correct() throws Exception {
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Path secret = Files.createDirectory(tmp.resolve("secret"));
    Files.writeString(secret.resolve("keys.txt"), "not yours");

    String session = "refusing";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> provider.read(secret.resolve("keys.txt"), FIRST));
    assertTrue(
        refused.getMessage().contains("outside this session's workspace"), refused.getMessage());
    assertFalse(
        refused.getMessage().contains("not yours"),
        "and the file it would not read is not in the refusal");
  }

  @Test
  void a_workspace_deleted_under_a_running_client_ends_the_run_rather_than_refusing()
      throws Exception {
    Path repo = Files.createDirectory(tmp.resolve("repo"));

    String session = "deleted";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);
    Files.delete(repo);

    WorkspaceUnavailableException gone =
        assertThrows(WorkspaceUnavailableException.class, () -> provider.glob("**/*.java"));
    assertTrue(gone.getMessage().contains("no longer there"), gone.getMessage());
    // The client's DISK went away and the client did not: it answered, which
    // is a thing an absent client cannot do. So this ends the run as
    // UNAVAILABLE and not as SESSION_GONE, and the whole difference is that
    // there is a directory here for somebody to put back.
    assertFalse(
        gone instanceof SessionGoneException,
        "a client well enough to say its workspace is gone is not itself gone — "
            + gone.getMessage());
  }

  /**
   * <b>What a run wrote before its session died is still there, and this is the instrument for
   * it.</b>
   *
   * <p>v1 says a client disappearing keeps "partial output rather than discarding it", and 3a made
   * {@code JobRuntime.stopped} take no content parameter precisely so a truncated run can never be
   * dressed as an answer. Those look opposed and are not: what survives is <em>what the run
   * produced</em>, not the prose it was in the middle of. 3a's own words for it — a pass's output
   * is in Postgres rather than in the handle.
   *
   * <p>So the claim is about durable side effects, and over this channel the durable side effect is
   * a file on somebody else's disk. The write lands, the client goes away, the next request ends
   * the run — and the file is still on that disk with the content the run put in it. <b>The
   * read-back is deliberately not over the wire</b>: the wire is gone by then, which is the whole
   * point, so the assertion is made against the filesystem directly and cannot be satisfied by
   * anything the channel remembers.
   *
   * <p>The delete of the earlier file is what stops this being a fixture that would pass without a
   * write happening at all: {@code Files.readString} on a path nothing wrote raises rather than
   * returning the old content.
   */
  @Test
  void what_a_run_wrote_before_the_session_died_is_still_on_the_clients_disk() throws Exception {
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    String session = "half-done";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    SocketPeer client = client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    provider.write(repo.resolve("Half.java"), "class Half {}\n");
    client.close();
    awaitDisconnected(session);

    assertThrows(
        SessionGoneException.class,
        () -> provider.read(repo.resolve("Half.java"), FIRST),
        "the run ends here, and it ends saying the session went away");
    assertEquals(
        "class Half {}\n",
        Files.readString(repo.resolve("Half.java")),
        "and what the run had already written is on the disk that owns it, read"
            + " without the channel that is now gone");
  }

  /**
   * The type survives every layer between the socket and the turn loop, and nothing else in this
   * repository holds that.
   *
   * <p>{@code SESSION_GONE} is picked by an {@code instanceof} in {@code JobRuntime}, so any layer
   * in between that caught the exception and threw a fresh {@code WorkspaceUnavailableException}
   * would downgrade the ending in silence — every existing test still green, because they all
   * assert the supertype. There are two such layers and each is a place it could have happened:
   * {@code ProviderRouter} <em>catches</em> this type by name and rethrows the remembered instance
   * rather than a sentence of its own, and {@code FileTools} catches {@code
   * WorkspaceRefusedException} beside {@code BadArguments} and deliberately not this one.
   *
   * <p>So the assertion is made at the top of the stack a job actually calls — the tool — over a
   * real socket that really closed.
   */
  @Test
  void the_type_survives_the_router_and_the_tool_that_a_job_calls() throws Exception {
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Files.writeString(repo.resolve("A.java"), "class A {}\n");
    String session = "through-the-stack";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    SocketPeer client = client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);
    ProviderRouter router = new ProviderRouter(home -> List.of(provider));
    FileTools.Read read = new FileTools.Read(router);
    String arguments = "{\"path\": \"" + repo.toRealPath().resolve("A.java") + "\"}";

    // The same call, answered, so the fixture is known to reach the file
    // before the client goes: a test whose read never worked would pass on
    // any refusal at all.
    assertTrue(read.run(arguments, Home.global()).contains("class A {}"));
    client.close();
    awaitDisconnected(session);

    assertThrows(
        SessionGoneException.class,
        () -> read.run(arguments, Home.global()),
        "a layer that rebuilt the exception would leave this a plain"
            + " WorkspaceUnavailableException and the run would end UNAVAILABLE");
  }

  @Test
  void the_absence_probe_reaches_a_remote_providers_sentence_over_the_wire() throws Exception {
    // Task 4 derived this trick from FileProvider's published contract rather
    // than from any implementation's ordering, and said it would cost this
    // task no extra message on the wire. MEASURED HERE RATHER THAN REPEATED:
    // ProviderRouter.absence asks a provider with no roots to search, and the
    // sentence it gets back is the client's own account of which state it is
    // in — a real round trip, which is the case task 4's own note corrected
    // itself about when it said the probe "costs nothing".
    String session = "empty";
    TestWorkspace nothingSet = new TestWorkspace();
    client(session, nothingSet);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    assertEquals(List.of(), provider.roots(), "no roots, and that is an ordinary answer");
    assertTrue(
        ProviderRouter.absence(provider).contains("no workspace is set"),
        "and asking it to search is what says WHICH kind of nothing — "
            + ProviderRouter.absence(provider));
  }

  @Test
  void an_agent_granted_nothing_reaches_the_same_probe_without_a_round_trip() throws Exception {
    // The second route into the same sentence, and the one that never leaves
    // the server: RemoteProvider refuses before the wire, so the probe finds
    // a state the client was never asked about. Both routes end in a
    // WorkspaceRefusedException, which is the whole of what absence needs.
    String session = "ungranted";
    TestWorkspace anywhere = new TestWorkspace();
    anywhere.set(List.of(tmp));
    client(session, anywhere);

    RemoteProvider provider = new RemoteProvider(production, session, List.of());

    assertEquals(Grant.noneDeclared(), ProviderRouter.absence(provider));
  }

  @Test
  void a_frame_that_parses_to_nothing_does_not_take_the_whole_session_down() throws Exception {
    // THE CRITICAL. The catch for unparseable frames covers only frames that
    // FAIL TO PARSE, and its comment described precisely the case it did not
    // handle. Measured on Jackson 2.17: readValue("null", FileReply.class)
    // returns NULL and "{}" binds to a record with every component null, so
    // the first dereferenced a null reply and the second reached
    // ConcurrentHashMap.remove(null) — both NullPointerException.
    //
    // AND THE BLAST RADIUS IS NOT ONE FRAME. Spring decorates this handler
    // with ExceptionWebSocketHandlerDecorator, so an exception escaping
    // handleTextMessage closes the session with SERVER_ERROR and every
    // outstanding request on it fails. One malformed frame from one client
    // would end every job holding that session.
    //
    // a_frame_the_server_cannot_read_is_dropped_and_the_socket_keeps_working
    // sends "{this is not a reply", which is a parse failure, so it never
    // reached this branch.
    String session = "empty-frames";
    Silent silent = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    for (String frame :
        List.of("null", "{}", "{\"outcome\":\"ok\"}", "{\"id\":null,\"outcome\":\"ok\"}")) {
      silent.socket.send(frame);
    }
    // A real assertion that doubles as the wait, rather than a sleep: before
    // the guard, the first of those frames closed the session with
    // SERVER_ERROR, and the client sees that as onClosing.
    assertFalse(
        silent.awaitClosed(500),
        "no frame above closed the session — before the guard, the first one did");
    assertTrue(fast.isConnected(session), "and it is still registered");

    CompletableFuture<String> waiting =
        CompletableFuture.supplyAsync(() -> text(provider, Path.of("/laptop/A.java")));
    silent.awaitRequests(1);
    silent.reply(FileReply.answered(silent.requests.peek().id(), one("still working")));

    assertEquals(
        "still working",
        waiting.get(10, TimeUnit.SECONDS),
        "and it still answers, which is what 'dropped' has to mean");
  }

  @Test
  void a_reply_on_a_displaced_socket_is_not_applied_to_the_session_that_replaced_it()
      throws Exception {
    // handleTextMessage looked up by name without checking socket identity
    // where afterConnectionClosed checks it, so a frame from a socket that
    // had been replaced was applied to the new session's outstanding map.
    // Harmless today because the ids are UUIDs and match nothing — which is
    // worth saying, because it makes this a correctness repair rather than a
    // bug fix, and because only one of those two methods could be right
    // about whether identity mattered.
    String session = "displaced";
    Silent first = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    CompletableFuture<Throwable> evicted =
        CompletableFuture.supplyAsync(
            () -> caught(() -> provider.read(Path.of("/laptop/first"), FIRST)));
    first.awaitRequests(1);
    String stolen = first.requests.peek().id();
    Silent second = connect(FAST_PATH, session);
    assertTrue(evicted.get(10, TimeUnit.SECONDS).getMessage().contains("replaced"));

    CompletableFuture<String> live =
        CompletableFuture.supplyAsync(() -> text(provider, Path.of("/laptop/second")));
    second.awaitRequests(1);
    String real = second.requests.peek().id();

    // The displaced socket answers the LIVE session's id. It must be ignored
    // for being on the wrong socket, not merely for having the wrong id.
    first.reply(FileReply.answered(real, one("from the socket that was replaced")));
    Thread.sleep(100);
    second.reply(FileReply.answered(real, one("from the socket that took over")));

    assertEquals("from the socket that took over", live.get(10, TimeUnit.SECONDS));
    assertEquals(false, stolen.equals(real), "and the ids really were different");
  }

  @Test
  void the_displaced_socket_is_closed_rather_than_left_open() throws Exception {
    // Leaving it open leaves a Tomcat session alive until the peer closes,
    // which this handler's own javadoc says can be minutes on a half-open
    // connection — so a client in a reconnect loop accumulates them without
    // bound.
    String session = "closed-on-takeover";
    Silent first = connect(FAST_PATH, session);
    connect(FAST_PATH, session);

    assertTrue(
        first.awaitClosed(10_000),
        "the server closed the socket it displaced rather than forgetting it");
  }

  // --- one account per session id (Enzo's decision of 2026-09-30) ---------------

  /** The production registration carries AuthFilter's account onto the file channel. */
  @Test
  void the_file_channel_knows_the_account_it_was_opened_as() throws Exception {
    String session = "known-account";
    connect(FileChannelHandler.PATH, session, "&" + AS_PARAM + "=enzo");

    assertEquals(Optional.of("enzo"), production.handleOf(session));
    assertEquals(Optional.empty(), production.handleOf("nobody-connected"));
  }

  @Test
  void the_same_account_reconnecting_displaces_its_earlier_socket() throws Exception {
    String session = "same-account";
    Silent first = connect(FAST_PATH, session, "&" + AS_PARAM + "=enzo");
    Silent second = connect(FAST_PATH, session, "&" + AS_PARAM + "=enzo");

    assertTrue(first.awaitClosed(10_000), "the earlier socket was displaced and closed");
    assertFalse(second.awaitClosed(200), "and the reconnect holds the session");
    assertEquals(Optional.of("enzo"), fast.handleOf(session));
  }

  /**
   * A socket opened as another account under a live session's id is refused with a policy
   * violation, and the socket already there keeps the session: an id is never taken over by an
   * account that does not hold it.
   */
  @Test
  void another_account_under_a_held_session_id_is_refused_and_takes_nothing() throws Exception {
    String session = "held-by-enzo";
    Silent held = connect(FAST_PATH, session, "&" + AS_PARAM + "=enzo");
    Object holding = attachment(Wiring.FAST_SESSIONS, session).orElseThrow();

    Silent intruder =
        dial(
            FAST_PATH,
            session,
            "&" + AS_PARAM + "=mallory" + rooting("mallory.local", "/srv/theirs", "ledger"));

    assertTrue(intruder.awaitClosed(10_000), "the second account's socket was closed");
    assertEquals(1008, intruder.closeCode, "as a policy violation");
    assertTrue(intruder.closeReason.contains("another account"), intruder.closeReason);
    assertFalse(held.awaitClosed(200), "the socket that held the session is still open");
    assertEquals(
        holding,
        attachment(Wiring.FAST_SESSIONS, session).orElseThrow(),
        "and still the session's file channel");
    assertEquals(Optional.of("enzo"), fast.handleOf(session));
    assertTrue(
        Wiring.FAST_PRESENCES.rootedBy(session).isEmpty(),
        "and the refused socket claimed nothing");
  }

  /**
   * The first account to claim a session id holds it for the registry's life, on both sockets:
   * another account is refused 1008 even after the holder's socket has closed, and on the listener
   * as on the file channel. The holder's own account reconnects on either.
   */
  @Test
  void a_session_id_stays_its_first_account_s_on_both_sockets_after_the_holder_closes()
      throws Exception {
    String session = "held-for-life";
    Silent held = connect(FileChannelHandler.PATH, session, "&" + AS_PARAM + "=enzo");
    held.close();
    awaitNoAttachment(productionSessions, session);

    Silent files = dial(FileChannelHandler.PATH, session, "&" + AS_PARAM + "=mallory");
    Silent listener = dial(EventChannelHandler.PATH, session, "&" + AS_PARAM + "=mallory");

    assertTrue(files.awaitClosed(10_000), "another account's file channel was refused");
    assertEquals(1008, files.closeCode);
    assertTrue(listener.awaitClosed(10_000), "and so was its listener");
    assertEquals(1008, listener.closeCode);
    assertEquals(Optional.of("enzo"), productionSessions.accountOf(session));
    assertTrue(attachment(productionSessions, session).isEmpty(), "nothing of theirs attached");

    connect(FileChannelHandler.PATH, session, "&" + AS_PARAM + "=enzo");
    Silent mine = dial(EventChannelHandler.PATH, session, "&" + AS_PARAM + "=enzo");
    for (int i = 0;
        i < 1_000 && !productionSessions.find(session).orElseThrow().has(Role.LISTENER);
        i++) {
      Thread.sleep(5);
    }
    assertTrue(
        productionSessions.find(session).orElseThrow().has(Role.LISTENER),
        "the holder's own listener is let in");
    assertFalse(mine.awaitClosed(100), "and stays");
    assertEquals(
        Optional.of("enzo"), production.handleOf(session), "and its file channel reconnects");
  }

  /**
   * The claim is taken before the declaration, so a second account arriving while the first is
   * still mid-connect (its archive write held on a latch) is refused at once, and the first then
   * attaches as the session's file channel.
   */
  @Test
  void a_second_account_arriving_while_the_first_is_mid_connect_is_refused() throws Exception {
    String session = "claimed-mid-connect";
    CountDownLatch gate = new CountDownLatch(1);
    Wiring.ROOTED.gate = gate;
    Silent first =
        dial(
            FAST_PATH,
            session,
            "&" + AS_PARAM + "=enzo" + rooting("bench.local", "/srv/claimed", "claimed"));
    assertTrue(
        Wiring.ROOTED.waiting.await(10, TimeUnit.SECONDS),
        "the first connect is held in its declaration");

    Silent second = dial(FAST_PATH, session, "&" + AS_PARAM + "=mallory");

    assertTrue(second.awaitClosed(10_000), "refused while the first was still connecting");
    assertEquals(1008, second.closeCode);
    gate.countDown();
    awaitAttachmentOtherThan(Wiring.FAST_SESSIONS, session, null);
    assertFalse(first.awaitClosed(200), "the first connect finished and holds the session");
    assertEquals(Optional.of("enzo"), fast.handleOf(session));
  }

  private static void awaitNoAttachment(SessionRegistry sessions, String session)
      throws InterruptedException {
    for (int i = 0; i < 1_000; i++) {
      if (attachment(sessions, session).isEmpty()) {
        return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("session " + session + " never let its file channel go");
  }

  @Test
  void two_requests_on_a_silent_session_are_each_bounded_rather_than_queued() throws Exception {
    // NEITHER OF THE TWO DECLARED BOUNDS BOUNDED THE SEND. `answer.get
    // (deadline)` starts only after `send` returns, and `send` took an untimed
    // lock and then made a blocking write — so a client whose reader has
    // stalled held every other job's send behind one lock. Measured on this
    // classpath (tomcat-embed-websocket 10.1.31): the only real bound was
    // Tomcat's DEFAULT_BLOCKING_SEND_TIMEOUT of 20000 ms, which nothing here
    // named, making N queued jobs a worst case of N x 20 seconds.
    //
    // WHAT THIS DOES NOT MEASURE, said plainly because the sweep asked. The
    // mutant that replaces the deadline on tryLock with Long.MAX_VALUE
    // SURVIVES this test, and the test was named
    // ..._queued_behind_a_wedged_one_ until it did. The lock here is only
    // held for the duration of a write that completes immediately, so
    // neither caller ever contends for it; both simply reach their own
    // answer deadline. What this holds is that two callers on a silent
    // session are each bounded and neither inherits the other's wait — real,
    // and not the acquisition bound.
    //
    // The acquisition bound has no instrument. Contending for it needs a
    // socket whose write blocks, which needs a peer that has stopped reading
    // while its TCP buffers fill — there is no deterministic fixture for
    // that, and a package-private hook to hold the lock would be a test-only
    // API for a race. It is kept on the measurement instead: Tomcat's
    // DEFAULT_BLOCKING_SEND_TIMEOUT is 20000 ms on this classpath, so without
    // the bound N queued jobs was a worst case of N x 20 seconds.
    String session = "queueing";
    Silent silent = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    // Hold the send lock the way a stalled peer would, by never letting the
    // holder finish: the first caller blocks in `get`, and the lock is only
    // held for the write — so this measures that a second caller is bounded
    // at all rather than that it queues forever.
    CompletableFuture<Throwable> first =
        CompletableFuture.supplyAsync(
            () -> caught(() -> provider.read(Path.of("/laptop/first"), FIRST)));
    CompletableFuture<Throwable> second =
        CompletableFuture.supplyAsync(
            () -> caught(() -> provider.read(Path.of("/laptop/second"), FIRST)));

    assertTrue(first.get(10, TimeUnit.SECONDS) instanceof WorkspaceUnavailableException);
    assertTrue(second.get(10, TimeUnit.SECONDS) instanceof WorkspaceUnavailableException);
    assertTrue(silent.socket.send("still here"), "the socket was open throughout");
  }

  // --- the third bound, which nobody had chosen -----------------------------

  /**
   * The worst window this protocol can cut, as the wire would carry it.
   *
   * <p>Lines of C0 control characters, because Jackson writes each one as a six-char {@code
   * &#92;uXXXX} escape and that is the largest expansion it has — a quote or a backslash costs two
   * chars, an accented or CJK character costs none at all and is <em>cheaper</em> here than its
   * UTF-8 length. Nothing on either side of this wire rejects a file for holding them: {@code
   * NodeFiles} refuses a file only for not decoding as UTF-8, and a C0 byte decodes. A captured
   * terminal log is the ordinary spelling of it, one {@code ESC} per colour change.
   *
   * <p>Built through {@code Window.cut} rather than assembled by hand, so it is the real ceiling
   * and moves if {@code MAX_WINDOW_BYTES} does.
   */
  private static Span worstCaseWindow() {
    String line =
        String.valueOf((char) 1).repeat(Window.MAX_WINDOW_BYTES / Window.MAX_WINDOW_LINES);
    List<String> all = new ArrayList<>();
    for (int i = 0; i < Window.MAX_WINDOW_LINES * 2; i++) {
      all.add(line);
    }
    Span window = FIRST.cut(all);
    assertEquals(
        Span.BYTES,
        window.stoppedBy(),
        "the fixture has to be a window the ceiling stopped, or it is not the worst case");
    return window;
  }

  /**
   * What the container was actually configured to, read back from the container and not from the
   * constant that set it.
   */
  private static int configuredBufferChars() {
    return context
        .getBean(ServletServerContainerFactoryBean.class)
        .getObject()
        .getDefaultMaxTextMessageBufferSize();
  }

  @Test
  void the_transport_carries_more_than_a_full_window_encodes_to() throws Exception {
    // THE TWO NUMBERS LIVE IN DIFFERENT MODULES AND THAT IS HOW THIS
    // HAPPENED. Window.MAX_WINDOW_BYTES is in plowshare-protocol and counts
    // TEXT; the buffer is a Spring bean in FileChannelConfig and bounds the
    // FRAME. Neither file can see the other, so the relation between them is
    // held here or it is held nowhere — which is exactly the state that let
    // an 18 957-byte file close a channel with 1009.
    //
    // The configured side is read back off the container rather than
    // compared against the constant that set it: an unconfigurable value is
    // an unassertable one, and asserting a constant against itself would
    // pass with the bean deleted. Measured, with the setter removed from
    // FileChannelConfig, this call answers 8192.
    String frame =
        json()
            .writeValueAsString(
                new FileReply(
                    "some-request-id", FileReply.OK, null, null, worstCaseWindow(), null));

    // Chars and not bytes, MEASURED on this classpath: Tomcat decodes into a
    // CharBuffer of this capacity, so a frame of 700 043 chars weighing
    // 1 400 043 bytes of UTF-8 crosses a 1 MiB setting intact. Comparing
    // byte lengths here would be a stricter test of the wrong quantity.
    assertTrue(
        configuredBufferChars() > frame.length(),
        "the transport has to carry the largest window this protocol can cut, and the"
            + " frame is not the window: this one encodes to "
            + frame.length()
            + " chars from "
            + Window.MAX_WINDOW_BYTES
            + " bytes of text, against a"
            + " configured buffer of "
            + configuredBufferChars()
            + " chars."
            + " Sizing the buffer from MAX_WINDOW_BYTES reproduces CloseStatus"
            + "[code=1009] at a larger number");
  }

  @Test
  void a_window_the_ceiling_stopped_crosses_the_socket_whole() throws Exception {
    // The arithmetic above is the pin; this is the instrument. It sends the
    // worst-case frame over a real socket to the production handler and
    // asserts the channel is still there afterwards — which is the thing
    // that was not true, and the thing a close code cannot be argued into.
    String session = "a-window-at-the-ceiling";
    Silent silent = connect(FileChannelHandler.PATH, session);

    silent.socket.send(
        json()
            .writeValueAsString(
                new FileReply(
                    "no-request-is-waiting-for-this",
                    FileReply.OK,
                    null,
                    null,
                    worstCaseWindow(),
                    null)));

    // The handler drops a reply whose id nothing is waiting on, so nothing
    // comes back and the absence of a close IS the measurement. A frame the
    // container refuses closes within milliseconds; a second is four orders
    // of magnitude more than this file's own measurement of how long a close
    // takes to arrive.
    assertFalse(
        silent.awaitClosed(1_000),
        "the socket carried a full window rather than closing with"
            + " CloseStatus[code=1009, reason=The decoded text message was too big"
            + " for the output buffer and the endpoint does not support partial"
            + " messages]");
    assertTrue(production.isConnected(session), "and the session survived with it");
  }

  @Test
  void the_event_socket_inherits_the_bound_this_file_chose() throws Exception {
    // A CLAIM ABOUT THE BEAN IN FileChannelConfig, which is why it is
    // measured here: ServletServerContainerFactoryBean configures the one
    // ServerContainer the servlet context holds, so it is container-wide
    // rather than per-handler. That is asserted rather than assumed, because
    // the alternative reading — a bound that applies only to the handler
    // registered beside it — is equally plausible from the API and would
    // leave the other socket on 8192 while this file's tests all passed.
    //
    // Measured both ways: with the setter present a frame far above the
    // container default crosses BOTH paths, and with it deleted both refuse
    // it with the 1009 reason. JobEvents are small and did not size this
    // number; the event channel is a passenger on it.
    CountDownLatch closed = new CountDownLatch(1);
    WebSocket listener =
        http.newWebSocket(
            new Request.Builder()
                .url(
                    "ws://localhost:"
                        + port
                        + EventChannelHandler.PATH
                        + "?"
                        + FileChannelHandler.SESSION_PARAM
                        + "=an-oversized-frame")
                .build(),
            new WebSocketListener() {
              @Override
              public void onClosing(WebSocket from, int code, String reason) {
                closed.countDown();
                from.close(1000, null);
              }
            });
    try {
      // Well-formed JSON, so that what is being measured is the container
      // decoding the frame and not the handler choking on it. This channel
      // is one-way and drops whatever arrives.
      String oversized =
          json()
              .writeValueAsString(
                  new FileReply(
                      "ignored",
                      FileReply.OK,
                      "x".repeat(configuredBufferChars() / 2),
                      null,
                      null,
                      null));
      assertTrue(
          oversized.length() > 8192,
          "a frame that fits the container default would measure nothing — " + oversized.length());
      listener.send(oversized);

      assertFalse(
          closed.await(1_000, TimeUnit.MILLISECONDS),
          "one container bean covers both sockets, so the event channel carries the"
              + " same "
              + configuredBufferChars()
              + " chars the file channel does");
    } finally {
      listener.close(1000, null);
    }
  }

  // --- the same bound, driven by a client rather than sent by hand ---------

  /**
   * The size of the file the live run died on.
   *
   * <p>{@code implementation rationale} as it stood that day: an ordinary document, nothing near
   * any window bound — and the read of it closed the channel with {@code CloseStatus[code=1009]}
   * and ended the job {@code SESSION_GONE}. The number is written here rather than read off the
   * file, because the file has grown since (it is over 21 000 bytes as this is written) and the
   * fixture is meant to stay the shape that found the fault — a test that read the repository's own
   * tree would also be a test that fails when a document is edited.
   *
   * <p>The tests above approach the buffer from the top, with the worst window this protocol can
   * cut. This is the other end of the same interval: the ordinary middle, above the container's own
   * 8 192 and far below anything anybody chose, which is exactly the band the bug lived in.
   */
  private static final int THE_LENGTH_THAT_FOUND_IT = 18_957;

  /**
   * ASCII of a stated length, laid out as short lines. Ordinary on purpose: the fixtures above are
   * built to be expensive, and the whole point of this one is that nothing about it is.
   */
  private static String ordinaryProse(int chars) {
    StringBuilder text = new StringBuilder(chars);
    for (int line = 0; text.length() < chars; line++) {
      String next = "a line of an ordinary document, numbered " + line;
      if (text.length() + next.length() + 1 > chars) {
        next = next.substring(0, chars - text.length() - 1);
      }
      text.append(next).append('\n');
    }
    return text.toString();
  }

  /**
   * Lines wide enough that a window of them reaches {@code MAX_WINDOW_BYTES} well before it reaches
   * {@code MAX_WINDOW_LINES}, so a read of this file is stopped by the byte cap and says so.
   */
  private static String longLines(int howMany) {
    StringBuilder text = new StringBuilder();
    for (int line = 0; line < howMany; line++) {
      text.append("line ")
          .append(line)
          .append(' ')
          .append("of a file whose lines are wide. ".repeat(6))
          .append('\n');
    }
    return text.toString();
  }

  @Test
  void the_file_that_killed_a_run_crosses_this_wire_whole() throws Exception {
    // THE REGRESSION IN ITS ORIGINAL SHAPE, and the one this class did not
    // have: a real SocketPeer with a real NodeFiles reads a real
    // file off a real disk and answers over a real socket. The other
    // real-client reads in this file carry one line each; the large payloads
    // in it are frames built by hand and sent from a raw socket to an id
    // nothing is waiting on. This is the first place both are true at once,
    // which is the gap that let a 1 MiB fixture exist in LocalProviderTest —
    // driven through the local provider, which never crosses a socket —
    // while the class that owns the transport had no large payload at all.
    //
    // MEASURED, by setting FileChannelConfig.MAX_TEXT_MESSAGE_CHARS to
    // 8 * 1024 and running this: it fails with SessionGoneException from
    // FileChannelHandler.afterConnectionClosed — "the session
    // 'the-file-that-found-it' closed while this was waiting" — and the
    // container logs "closed the file channel (1009 The decoded text message
    // was too big for the output buffer and the endpoint does not support
    // partial messages)". Not an assertion that came out false: the actual
    // close, from the actual read, which is what the live run saw.
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Path document = repo.resolve("notes.md");
    String written = ordinaryProse(THE_LENGTH_THAT_FOUND_IT);
    Files.writeString(document, written);
    assertEquals(
        THE_LENGTH_THAT_FOUND_IT,
        Files.size(document),
        "the fixture is the size of the file that found this, or it is a different test");

    String session = "the-file-that-found-it";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    Span answered = provider.read(document, FIRST);

    assertEquals(
        written.lines().toList(),
        answered.lines(),
        "every line the client read is a line the server got");
    assertFalse(answered.more(), "an ordinary file, carried whole in one window");
    assertEquals(
        Span.END,
        answered.stoppedBy(),
        "no window bound stopped this read — the transport is the only thing that" + " ever did");
    assertTrue(
        production.isConnected(session),
        "and the channel that carried it is still open, which is the whole assertion:"
            + " the session is what a job holds, and 1009 took it with the socket");

    // What the frame weighed, so the fixture cannot quietly shrink under the
    // bound it exists to be above. 8192 is the container's own default and
    // the number that was in force when this file killed a session; it is
    // read off no bean because it is what the bean replaced.
    String frame =
        json()
            .writeValueAsString(
                FileReply.answered("a-request-id-of-the-shape-this-wire-carries", answered));
    assertTrue(
        frame.length() > 8192,
        "a payload the old default could have carried would measure nothing — "
            + frame.length()
            + " chars");
  }

  @Test
  void a_read_the_byte_cap_stopped_crosses_with_the_next_call_it_names() throws Exception {
    // The window's own ceiling and the transport in one test, because the
    // reply that a caller has to act on is the one carrying BOTH a full
    // window and the metadata for the next call. Above, a ceiling-stopped
    // window is sent from a raw socket to an id nothing is waiting on: the
    // container decodes it and the handler drops it, so nothing on this side
    // ever reads a Span back. Here a client cuts it, the wire carries it,
    // and RemoteProvider hands back something a caller pages with.
    //
    // MEASURED at 8 * 1024 alongside the test above: the same
    // SessionGoneException and the same 1009 close reason.
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Path wide = repo.resolve("wide.txt");
    String written = longLines(800);
    Files.writeString(wide, written);
    List<String> all = written.lines().toList();

    String session = "a-window-the-cap-stopped";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    Span first = provider.read(wide, FIRST);

    assertEquals(
        Span.BYTES,
        first.stoppedBy(),
        "the fixture has to be cut by the byte cap rather than the line limit, or this"
            + " measures the ordinary case twice");
    assertTrue(first.more(), "and there is more of the file after it");
    assertEquals(0, first.offset());
    assertEquals(
        all.size(),
        first.totalLines(),
        "the total is about the file and not about the window, and it crossed intact");
    assertEquals(
        all.subList(0, first.lines().size()),
        first.lines(),
        "and so did every line of a window at the byte ceiling");

    // The continuation is only metadata until something continues with it.
    Span second =
        provider.read(
            wide, Window.of(first.offset() + first.lines().size(), Window.MAX_WINDOW_LINES));
    assertEquals(
        first.lines().size(), second.offset(), "the second window starts where the first stopped");
    assertEquals(
        all.subList(second.offset(), second.offset() + second.lines().size()), second.lines());
    assertEquals(
        all.size(),
        second.offset() + second.lines().size(),
        "and the two of them are the whole file, which is what paging is for");
    assertTrue(production.isConnected(session), "over one socket that stayed open");
  }

  @Test
  void a_stat_answers_over_this_wire_about_a_file_no_one_window_carries() throws Exception {
    // file_stat is new on this channel and nothing had driven it end to end:
    // NodeFiles.stat is measured on the client's side and RemoteProvider
    // .stat on the server's, and neither of those tests has a socket between
    // them. Its reply is tiny, which is the point of it — it is what lets a
    // model decide whether to read at all — so this measures the small frame
    // as well as the answer. Measured at 8 * 1024, where the two tests above
    // die of the close: this one still passes, which is what says it is
    // about the operation and not about the buffer.
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Path wide = repo.resolve("wide.txt");
    String written = longLines(800);
    Files.writeString(wide, written);

    String session = "asking-before-reading";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    Span about = provider.stat(wide);

    assertEquals(
        written.lines().toList().size(),
        about.totalLines(),
        "the count is of the file, taken on the machine that holds it");
    assertTrue(
        about.lines().isEmpty(),
        "and none of the file came with it — an ask that moved the file would be a read");
    assertTrue(production.isConnected(session));

    String frame =
        json()
            .writeValueAsString(
                FileReply.answered("a-request-id-of-the-shape-this-wire-carries", about));
    assertTrue(
        frame.length() < 8192,
        "a stat of a file no single window carries fits the buffer nobody had chosen — "
            + frame.length()
            + " chars");
  }

  /**
   * The residual this class used to assert, closed and asserted the other way round.
   *
   * <p><b>This test was {@code
   * a_line_wider_than_the_buffer_still_ends_the_session_it_arrives_on}</b>, and it pinned the
   * failure rather than endorsing it: a minified bundle is one line, {@code Window.cut} took its
   * first line whatever it cost, and the frame for such a file was bounded by {@code
   * NodeFiles.MAX_FILE_BYTES} rather than by {@code MAX_WINDOW_BYTES}. Its javadoc said it would go
   * red the day somebody fixed that, and this is that day; the fixture is kept exactly, because the
   * fixture was never the thing in doubt.
   *
   * <p><b>The two assertions that matter are that the session is still there and that the caller
   * was told something it can act on.</b> A refusal is an ordinary tool error a model reads and
   * answers; a dead session ends the job and tells a person only that a socket closed. The file is
   * unreadable either way — what changed is what that costs.
   *
   * <p><b>Measured:</b> a green run of this class used to log exactly one {@code
   * CloseStatus[code=1009]} and it was this test's. It now logs none.
   */
  @Test
  void a_line_wider_than_the_buffer_is_refused_and_the_session_survives() throws Exception {
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Path bundle = repo.resolve("bundle.min.js");
    // Derived from the container, so the fixture stays over the bound if the
    // bound moves, and the day it is sized for a line like this instead the
    // failure is a fixture that no longer reproduces rather than a test that
    // silently measures something else.
    String oneLine = "x".repeat(configuredBufferChars() + 1024);
    Files.writeString(bundle, oneLine + "\n");

    String session = "one-enormous-line";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    String frame =
        json()
            .writeValueAsString(
                FileReply.answered(
                    "a-request-id", new Span(List.of(oneLine), 0, 1, false, Span.END)));
    assertTrue(
        frame.length() > configuredBufferChars(),
        "the mechanism, stated where it can go wrong: the reply this read WOULD have"
            + " produced is a frame of "
            + frame.length()
            + " chars against a buffer"
            + " of "
            + configuredBufferChars()
            + ", and the point of the refusal is"
            + " that it is never built");

    WorkspaceRefusedException refused =
        assertThrows(WorkspaceRefusedException.class, () -> provider.read(bundle, FIRST));
    assertTrue(
        refused.getMessage().contains("line 0"),
        "the caller is told which line, which is a fact it can act on — " + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("file_grep"),
        "and what still works on this file — " + refused.getMessage());
    assertTrue(
        production.isConnected(session),
        "and the session it arrived on is still here, which is the whole difference"
            + " between an unreadable file and a fatal one");

    // Still usable afterwards, and not merely still registered. A session
    // that survived the refusal and could not answer again would be the same
    // outage arriving one call later.
    Path ordinary = Files.writeString(repo.resolve("ordinary.txt"), "one\ntwo\n");
    assertEquals(List.of("one", "two"), provider.read(ordinary, FIRST).lines());
  }

  /**
   * What makes that refusal a redirection rather than a wall, over the wire that used to die of it.
   *
   * <p>The refusal names {@code file_grep}, and a sentence naming a remedy is worth exactly what
   * the remedy is worth. {@code Needle.MAX_LINE_CHARS} cuts a matching line, so the search never
   * has to carry what the window would not — and this asserts that on the real socket, because
   * "grep truncates" is a fact about a class and "the answer crosses" is a fact about this wire.
   */
  @Test
  void the_file_a_read_refuses_can_still_be_searched_over_the_same_socket() throws Exception {
    Path repo = Files.createDirectory(tmp.resolve("repo"));
    Path bundle = repo.resolve("bundle.min.js");
    String oneLine = "var a=1;" + "x".repeat(configuredBufferChars() + 1024) + "needle";
    Files.writeString(bundle, oneLine + "\n");

    String session = "searchable-after-all";
    TestWorkspace workspace = new TestWorkspace();
    workspace.set(List.of(repo));
    client(session, workspace);
    RemoteProvider provider = new RemoteProvider(production, session, WRITE);

    assertThrows(WorkspaceRefusedException.class, () -> provider.read(bundle, FIRST));

    Found found = provider.grep(new Needle("needle", false), bundle);

    assertEquals(1, found.matches().size(), "the search reached inside the file");
    assertEquals(0, found.matches().get(0).offset());
    assertTrue(
        found.matches().get(0).truncated(),
        "cut to what a match carries, which is why this frame crosses a wire the read's"
            + " would not");
    assertTrue(production.isConnected(session));
  }

  @Test
  void an_upgrade_from_a_foreign_origin_is_refused() throws Exception {
    // FileChannelConfig says the ABSENCE of setAllowedOrigins is
    // load-bearing, and in a slice whose whole ethos is instrumenting
    // absences that one was the security-relevant absence left uninstrumented.
    // Nothing in the suite would have caught somebody adding
    // setAllowedOrigins("*"), which is the line that lets any page a
    // developer visits open a channel to a server on their own machine and be
    // handed a session's files.
    //
    // Measured: same-origin and no-origin upgrade (101), a foreign origin is
    // refused (403).
    okhttp3.Request foreign =
        new okhttp3.Request.Builder()
            .url(
                "http://localhost:"
                    + port
                    + FileChannelHandler.PATH
                    + "?"
                    + FileChannelHandler.SESSION_PARAM
                    + "=origin-check")
            .header("Upgrade", "websocket")
            .header("Connection", "Upgrade")
            .header("Sec-WebSocket-Version", "13")
            .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .header("Origin", "http://evil.example.com")
            .build();

    try (okhttp3.Response refused = http.newCall(foreign).execute()) {
      assertEquals(403, refused.code(), "a page on another origin does not get a file channel");
    }
    assertEquals(false, production.isConnected("origin-check"), "and nothing registered");
  }

  // --- registration --------------------------------------------------------

  @Test
  void a_session_that_never_connected_is_an_outage_naming_it() {
    SessionGoneException gone =
        assertThrows(
            SessionGoneException.class,
            () ->
                new RemoteProvider(production, "nobody-is-here", WRITE)
                    .read(Path.of("/laptop/A.java"), FIRST));

    assertTrue(
        gone.getMessage().contains("nobody-is-here"),
        "an operator reading a job's ending needs to know which session — " + gone.getMessage());
    // AND THE SENTENCE SAYS THE SESSION IS NOT CONNECTED, NEVER THAT IT
    // LEFT. This branch also covers a session that never connected at all —
    // a run pointed at an id no client ever opened, which is what a wiring
    // fault produces — and the ending it reaches is named for a client going
    // away. The ending's verb generalises across five sites; this sentence
    // is the one that lands in the run's detail, so it is the one that has
    // to be exact about a transition nothing here verified.
    assertTrue(
        gone.getMessage().contains("is connected"),
        "phrased about whether a session IS connected, which is the fact this branch"
            + " observed, rather than about one having left, which it did not — "
            + gone.getMessage());
  }

  @Test
  void a_client_that_opens_without_naming_a_session_is_closed_rather_than_left_hanging()
      throws Exception {
    // A session nothing can name is one no job can be routed to, so it would
    // sit holding a socket and answering nothing, and whoever opened it would
    // never learn that its file tools do not work.
    CountDownLatch closed = new CountDownLatch(1);
    AtomicReference<String> reason = new AtomicReference<>();
    http.newWebSocket(
        new Request.Builder().url("ws://localhost:" + port + FileChannelHandler.PATH).build(),
        new WebSocketListener() {
          @Override
          public void onClosing(WebSocket socket, int code, String said) {
            reason.set(said);
            socket.close(1000, null);
            closed.countDown();
          }
        });

    assertTrue(closed.await(10, TimeUnit.SECONDS), "the server closed it");
    assertTrue(
        reason.get().contains(FileChannelHandler.SESSION_PARAM),
        "and said what to open instead — " + reason.get());
  }

  @Test
  void a_second_connection_under_one_name_takes_over_and_fails_what_the_first_was_holding()
      throws Exception {
    // A client that reconnected before its old socket finished closing. The
    // new one wins, because it is the one whose workspace a human is looking
    // at; the old one's outstanding requests fail now rather than waiting out
    // a deadline against a session nothing will route to again.
    String session = "reconnecting";
    Silent first = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    CompletableFuture<Throwable> waiting =
        CompletableFuture.supplyAsync(
            () -> caught(() -> provider.read(Path.of("/laptop/A.java"), FIRST)));
    first.awaitRequests(1);

    long before = System.nanoTime();
    connect(FAST_PATH, session);
    Throwable failed = waiting.get(10, TimeUnit.SECONDS);
    long elapsed = (System.nanoTime() - before) / 1_000_000;

    assertTrue(
        failed instanceof SessionGoneException,
        "a takeover IS the session going away, however little it looks like one from"
            + " a three-sentence list of ways a socket dies — "
            + failed);
    assertTrue(failed.getMessage().contains("replaced"), failed.getMessage());
    assertTrue(
        elapsed < FAST.toMillis() / 2,
        "and it failed on the reconnection rather than on the deadline: " + elapsed + "ms");
  }

  @Test
  void the_production_wiring_publishes_the_channel_on_the_path_the_client_dials() {
    // FileChannelConfig is what puts the handler on a URL, and this is a
    // claim about the two literals agreeing across two modules. Every other
    // test in this file would pass with both of them wrong in the same way,
    // because they all reach the server through the same pair.
    assertEquals("/" + SocketPeer.PATH, FileChannelHandler.PATH);
    assertNotEquals(FAST_PATH, FileChannelHandler.PATH);
  }

  @Test
  void a_session_name_that_is_there_but_empty_is_refused_like_one_that_is_missing()
      throws Exception {
    // The other half of the guard. `?session=` is present and blank, which
    // is a different branch from no query string at all, and a client that
    // registered under "" would be a session no job could name either.
    CountDownLatch closed = new CountDownLatch(1);
    http.newWebSocket(
        new Request.Builder()
            .url(
                "ws://localhost:"
                    + port
                    + FileChannelHandler.PATH
                    + "?"
                    + FileChannelHandler.SESSION_PARAM
                    + "=")
            .build(),
        new WebSocketListener() {
          @Override
          public void onClosing(WebSocket socket, int code, String said) {
            socket.close(1000, null);
            closed.countDown();
          }
        });

    assertTrue(closed.await(10, TimeUnit.SECONDS));
    assertFalse(production.isConnected(""), "and nothing registered under the empty name");
  }

  @Test
  void the_old_socket_closing_after_a_takeover_does_not_fail_the_new_sessions_requests()
      throws Exception {
    // The dangerous half of the reconnection rule. The first socket is
    // replaced and then closes — a moment later, on its own schedule — and
    // afterConnectionClosed must not drain the map belonging to the session
    // that took over. Without that check, a client that reconnects kills its
    // own new requests the instant the old socket finishes going away.
    String session = "handover";
    Silent first = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    // The takeover, observed rather than waited out: this request is
    // outstanding on the first socket, so its "replaced" failure is proof
    // that the second socket is the registered one before anything below
    // runs. A sleep here would be a race dressed as a fixture — the first
    // draft of this test connected and asked, and the request went to the
    // socket that was on its way out.
    CompletableFuture<Throwable> displaced =
        CompletableFuture.supplyAsync(
            () -> caught(() -> provider.read(Path.of("/laptop/displaced"), FIRST)));
    first.awaitRequests(1);
    Silent second = connect(FAST_PATH, session);
    assertTrue(
        displaced.get(10, TimeUnit.SECONDS).getMessage().contains("replaced"),
        "the takeover has happened — " + displaced.get());

    CompletableFuture<String> waiting =
        CompletableFuture.supplyAsync(() -> text(provider, Path.of("/laptop/A.java")));
    second.awaitRequests(1);

    // Now the loser finally closes, on its own schedule — and this test waits
    // for it to have actually closed rather than sleeping. A sleep here was
    // eight lines below a comment saying a sleep would be a race dressed as a
    // fixture, and it degraded SILENTLY: too short and the close had not
    // landed, so the test passed without exercising the thing it names.
    first.socket.close(1000, "the old one, catching up");
    assertTrue(first.awaitClosed(10_000), "the displaced socket really did close");
    second.reply(FileReply.answered(second.requests.peek().id(), one("answered by the new one")));

    assertEquals(
        "answered by the new one",
        waiting.get(10, TimeUnit.SECONDS),
        "the new session's request survived the old socket going away");
  }

  // --- the presence a socket declares --------------------------------------

  /**
   * Opening the channel with a project on the query string is what makes this session the one that
   * roots it.
   *
   * <p><b>This is the answer to the design spec's §8.2</b>, which suspected declaring a presence
   * was already on the wire as {@code --workspace}. It was half there: the workspace travels, but
   * only as an answer to {@code ROOTS} on a socket that has already opened, and nothing anywhere
   * associated it with a project. So the association is what is new, and this is the smallest place
   * it can ride — the same upgrade, beside the session id that is already there.
   */
  @Test
  void opening_the_channel_with_a_project_declares_a_presence_for_it() throws Exception {
    connect(FileChannelHandler.PATH, "rooting", rooting("bench.local", "/srv/ledger", "ledger"));

    Presence rooted =
        productionPresences
            .serving("ledger")
            .orElseThrow(() -> new AssertionError("the socket declared nothing"));
    assertEquals("rooting", rooted.session());
    assertEquals(
        "bench.local/srv/ledger/ledger",
        rooted.canonicalName(),
        "and the canonical name is composed from what the client asserted about"
            + " itself, which is the only party that could know either half");
  }

  /**
   * A root is whatever the client's filesystem allows, and {@code &} is allowed on all of them.
   * Read through {@code URI.getQuery()}, which decodes before the split, an encoded {@code %26}
   * became a separator and the presence was declared for half a path.
   */
  @Test
  void a_root_with_an_ampersand_in_it_is_declared_whole() throws Exception {
    String root = "/srv/research & notes";
    connect(
        FileChannelHandler.PATH,
        "ampersand",
        rooting(
            "bench.local",
            URLEncoder.encode(root, StandardCharsets.UTF_8).replace("+", "%20"),
            "notes"));

    Presence rooted =
        productionPresences
            .serving("notes")
            .orElseThrow(() -> new AssertionError("the socket declared nothing"));
    assertEquals(root, rooted.root());
  }

  /**
   * A socket that names no project lends its files and roots nothing.
   *
   * <p>The ordinary shape of every client that existed before presence, and it still works: the
   * role is filled, so {@code ask} reaches it. What such a session does not get is a project of its
   * own, which is the distinction the whole design turns on — <b>holding a disk is not rooting a
   * place.</b>
   */
  @Test
  void a_channel_opened_without_a_project_lends_its_files_and_roots_nothing() throws Exception {
    connect(FileChannelHandler.PATH, "lending-only");

    assertTrue(
        production.isConnected("lending-only"), "the socket serves files exactly as it always did");
    assertEquals(Optional.empty(), productionPresences.serving("lending-only"));
    assertTrue(productionSessions.find("lending-only").orElseThrow().has(Role.FILE_PROVIDER));
  }

  /**
   * A second machine claiming a rooted project is refused at the upgrade, and the refusal names
   * both claims.
   *
   * <p><b>Refused and not displaced</b>, which is where this parts company with every other "second
   * connection" in this file. A second socket under one session id is one client reconnecting and
   * the newer one wins; a second <em>machine</em> claiming one project is two places asserting they
   * are one, and the owner ruled that out. So the socket is closed rather than attached, and the
   * sentence is the whole product — an operator with two terminals open needs to know which of them
   * is the wrong one.
   */
  @Test
  void a_second_machine_claiming_a_rooted_project_is_closed_and_told_who_holds_it()
      throws Exception {
    connect(
        FileChannelHandler.PATH,
        "bench",
        rooting("bench.local", "/srv/ledger", "ledger") + "&" + AS_PARAM + "=enzo");

    Silent refused =
        dial(
            FileChannelHandler.PATH,
            "desk",
            rooting("desk.local", "/home/example/ledger", "ledger") + "&" + AS_PARAM + "=enzo");

    assertTrue(refused.awaitClosed(10_000), "the server closed the second claim's socket");
    assertTrue(
        refused.closeReason.contains("bench.local/srv/ledger/ledger"),
        "and said which presence holds the project: " + refused.closeReason);
    assertEquals(
        "bench",
        productionPresences.serving("ledger").orElseThrow().session(),
        "the incumbent is untouched");
    assertTrue(
        production.isConnected("bench"),
        "and it is still serving files, which is the point of refusing the other one"
            + " rather than displacing it");
    assertFalse(
        productionSessions
            .find("desk")
            .map(session -> session.has(Role.FILE_PROVIDER))
            .orElse(false),
        "the refused claim never held the role, so no run could have been routed to"
            + " it in the window between attaching and closing");
  }

  /**
   * The same machine reconnecting under its own id is not a conflict.
   *
   * <p>The design spec's §8.3 — does a presence survive a reconnect — answered where the reconnect
   * happens: a session id is the identity, so a socket arriving under an id that already roots the
   * project is the same machine coming back and displaces its own socket in the ordinary way.
   */
  @Test
  void the_same_session_reconnecting_keeps_the_project_it_roots() throws Exception {
    String query = rooting("bench.local", "/srv/ledger", "ledger");
    Silent first = connect(FileChannelHandler.PATH, "reconnecting", query);

    connect(FileChannelHandler.PATH, "reconnecting", query);

    assertTrue(first.awaitClosed(10_000), "the earlier socket was displaced and closed");
    assertEquals(
        "reconnecting",
        productionPresences.serving("ledger").orElseThrow().session(),
        "and the project is still rooted, by the socket a human is looking at");
  }

  @Test
  void two_client_sessions_at_one_location_are_ready_and_a_primary_close_preserves_the_survivor()
      throws Exception {
    String query = rooting("bench.local", "/srv/shared", "shared") + "&ready=1";
    Silent first = connect(FAST_PATH, "shared-first", query);
    Silent second = connect(FAST_PATH, "shared-second", query);
    assertTrue(fast.isConnected("shared-first"));
    assertTrue(fast.isConnected("shared-second"));
    for (int i = 0; i < 500 && (first.frames.isEmpty() || second.frames.isEmpty()); i++) {
      Thread.sleep(5);
    }
    assertEquals(List.of("{\"ready\":true,\"project\":\"shared\"}"), List.copyOf(first.frames));
    assertEquals(List.of("{\"ready\":true,\"project\":\"shared\"}"), List.copyOf(second.frames));
    assertEquals("shared-first", Wiring.FAST_PRESENCES.serving("shared").orElseThrow().session());
    RemoteProvider pinned = new RemoteProvider(fast, "shared-first", WRITE);
    CompletableFuture<Throwable> waiting =
        CompletableFuture.supplyAsync(
            () -> caught(() -> pinned.read(Path.of("/srv/shared/pending"), FIRST)));
    first.awaitRequests(1);
    first.socket.close(1000, "first client exits");
    assertTrue(waiting.get(10, TimeUnit.SECONDS) instanceof SessionGoneException);
    for (int i = 0; i < 500 && Wiring.FAST_PRESENCES.rootedBy("shared-first").isPresent(); i++) {
      Thread.sleep(5);
    }
    assertEquals("shared-second", Wiring.FAST_PRESENCES.serving("shared").orElseThrow().session());
    assertEquals(0, second.requests.size(), "the interrupted request was not replayed");
    second.answer = request -> FileReply.answered(request.id(), one("surviving client"));
    RemoteProvider survivor = new RemoteProvider(fast, "shared-second", WRITE);
    assertEquals("surviving client", text(survivor, Path.of("/srv/shared/new")));
    assertTrue(fast.isConnected("shared-second"));
  }

  @Test
  void a_refused_standby_never_withdraws_the_incumbent_presence() throws Exception {
    String query = rooting("bench.local", "/srv/shared", "shared");
    connect(FAST_PATH, "shared-first", query);
    Wiring.ROOTED.refuseWith("membership or archive ownership changed");
    Silent denied = dial(FAST_PATH, "shared-denied", query + "&ready=1");
    assertTrue(denied.awaitClosed(10_000));
    assertEquals(List.of(), List.copyOf(denied.frames));
    assertTrue(Wiring.FAST_PRESENCES.rootedBy("shared-denied").isEmpty());
    assertEquals("shared-first", Wiring.FAST_PRESENCES.serving("shared").orElseThrow().session());
    assertTrue(fast.isConnected("shared-first"));
  }

  /** Closing the channel stops the session rooting the project. */
  @Test
  void closing_the_channel_leaves_the_project_rooted_nowhere() throws Exception {
    Silent client =
        connect(
            FileChannelHandler.PATH, "leaving", rooting("bench.local", "/srv/ledger", "ledger"));

    client.socket.close(1000, "going away");

    for (int i = 0; i < 500 && productionPresences.serving("ledger").isPresent(); i++) {
      Thread.sleep(5);
    }
    assertEquals(
        Optional.empty(),
        productionPresences.serving("ledger"),
        "a presence is a running process, so it goes when the process does");
  }

  /**
   * A claim this server cannot make sense of costs the socket its presence and not its files.
   *
   * <p>A relative root is the reachable spelling of it: a client that sends {@code code/ledger} has
   * named a place only it could find. Refusing the whole upgrade would take a working file channel
   * away over an identity component nothing has used yet, and accepting it would put a name in the
   * registry that no move operation could ever resolve. So the socket serves and roots nothing,
   * which is the state a client without the parameters is already in.
   */
  @Test
  void a_root_this_server_cannot_read_as_a_place_costs_the_presence_and_not_the_socket()
      throws Exception {
    connect(FileChannelHandler.PATH, "muddled", rooting("bench.local", "code/ledger", "ledger"));

    assertTrue(production.isConnected("muddled"), "the file channel is up");
    assertEquals(Optional.empty(), productionPresences.serving("ledger"));
  }

  // --- and saying the claim has landed ----------------------------------------

  /**
   * A client that asks to hear it is told, on the socket, once the claim is declared, written down
   * and the channel attached.
   *
   * <p><b>Why a frame at all.</b> A WebSocket's {@code open} event reaches the client when the 101
   * does, and {@code afterConnectionEstablished} — the declaration, the row and the attach — runs
   * after that. A client that asks {@code agent.list} on {@code open} can be answered before its
   * session roots anything, and so without its own definitions. This frame is the moment the claim
   * is true, which {@code open} never was.
   */
  @Test
  void a_client_that_asks_is_told_once_its_claim_has_landed() throws Exception {
    Silent client =
        connect(
            FileChannelHandler.PATH,
            "ready-bench",
            rooting("bench.local", "/srv/readied", "readied")
                + "&"
                + FileChannelHandler.READY_PARAM
                + "=1");

    for (int i = 0; i < 500 && client.frames.isEmpty(); i++) {
      Thread.sleep(5);
    }
    assertEquals(List.of("{\"ready\":true,\"project\":\"readied\"}"), List.copyOf(client.frames));
    assertEquals(
        "ready-bench",
        productionPresences.serving("readied").orElseThrow().session(),
        "and it was true when it was said");
  }

  /** A refused claim is a close and never a ready: the two cannot both arrive. */
  @Test
  void a_refused_claim_is_never_told_it_landed() throws Exception {
    connect(
        FileChannelHandler.PATH,
        "ready-holder",
        rooting("bench.local", "/srv/contested", "contested"));

    Silent refused =
        dial(
            FileChannelHandler.PATH,
            "ready-loser",
            rooting("desk.local", "/home/example/contested", "contested")
                + "&"
                + FileChannelHandler.READY_PARAM
                + "=1");

    assertTrue(refused.awaitClosed(10_000), "the server closed the second claim's socket");
    assertEquals(List.of(), List.copyOf(refused.frames));
  }

  /**
   * A client that did not ask — every Java {@code SocketPeer} — is sent nothing it would try to
   * read as a request.
   */
  @Test
  void a_client_that_did_not_ask_is_sent_nothing() throws Exception {
    Silent client =
        connect(
            FileChannelHandler.PATH, "unasked", rooting("bench.local", "/srv/unasked", "unasked"));

    Thread.sleep(100);
    assertEquals(List.of(), List.copyOf(client.frames));
  }

  // --- and the row the same declaration writes -------------------------------

  /**
   * <b>Declaring a presence is the registration, and this is the assertion of it.</b>
   *
   * <p>The design spec's §13.4 found that there is no verb for "this project exists and its files
   * are elsewhere": {@code ProjectStore.define} validates the workspace against the
   * <em>server's</em> disk, so a path that only exists on a laptop is refused at definition time
   * and the thing presence exists for cannot be written down at all. It turns out no new verb was
   * needed — the client already asserts the machine, the root and the project on this very upgrade,
   * which is exactly what a row needs — so the declaration writes both halves: the ephemeral one
   * into {@code PresenceRegistry} and the durable one here.
   *
   * <p><b>What is asserted is that the three components arrive unaltered.</b> The server does not
   * check the path and must not: it does not have that disk, and the only check available to it
   * would be whether this machine happens to hold a path of the same name — the one answer worse
   * than none.
   */
  @Test
  void declaring_a_presence_writes_down_where_the_projects_files_are() throws Exception {
    connect(
        FileChannelHandler.PATH,
        "registering",
        rooting("bench.local", "/home/example/ledger", "ledger"));

    assertEquals(
        List.of("ledger|bench.local|/home/example/ledger"),
        Wiring.ROOTED.written(),
        "the project, the machine and the place on it, exactly as the client spelled" + " them");
  }

  /**
   * A client that declares nothing writes nothing down, which is the same asymmetry the registry
   * has: lending a disk is not rooting a place.
   */
  @Test
  void a_channel_that_roots_nothing_writes_no_row() throws Exception {
    connect(FileChannelHandler.PATH, "lending-only-too");

    assertEquals(List.of(), Wiring.ROOTED.written());
  }

  /**
   * A place the archive says is already held costs the socket and the presence together.
   *
   * <p><b>The durable half of the conflict, and it catches what the registry cannot see.</b> {@code
   * PresenceRegistry} refuses two <em>live</em> claims; a laptop claiming a project the desk rooted
   * last week and has since closed competes with nothing there. So the refusal has to be able to
   * arrive from the row as well, and when it does the presence this socket just claimed is given
   * back — a session left rooting a project whose row says otherwise is exactly the split state
   * §13.2 refuses a move to avoid.
   */
  @Test
  void a_place_the_archive_says_is_held_elsewhere_costs_the_socket_and_the_presence()
      throws Exception {
    Wiring.ROOTED.refuseWith("'held' is rooted on 'desk.local' at /home/example/held");

    Silent refused =
        dial(
            FileChannelHandler.PATH,
            "claiming",
            rooting("bench.local", "/home/example/held", "held"));

    assertTrue(refused.awaitClosed(10_000), "the server closed the refused claim's socket");
    assertTrue(
        refused.closeReason.contains("desk.local"),
        "and said which place holds it: " + refused.closeReason);
    assertEquals(
        Optional.empty(),
        productionPresences.serving("held"),
        "the registry does not keep a claim the archive refused");
  }

  /**
   * An archive that cannot be reached costs a client nothing.
   *
   * <p><b>Deliberate, and the ranking is the point.</b> The registry is what routes a run, and it
   * is in memory; the row is a durable record of where the files are. A database that is down must
   * not take away a live capability that does not depend on it — the presence still serves every
   * run, exactly as it would have. What is lost is the writing-down, and the next reconnect makes
   * another attempt at it.
   */
  @Test
  void an_archive_that_cannot_be_reached_does_not_cost_the_presence() throws Exception {
    Wiring.ROOTED.breakWith("the archive is not reachable");

    connect(
        FileChannelHandler.PATH,
        "undeterred",
        rooting("bench.local", "/home/example/undeterred", "undeterred"));

    assertTrue(production.isConnected("undeterred"), "the file channel is up");
    assertEquals(
        "undeterred",
        productionPresences.serving("undeterred").orElseThrow().session(),
        "and the run routing that does not need the database still works");
  }

  @Test
  void a_frame_the_server_cannot_read_is_dropped_and_the_socket_keeps_working() throws Exception {
    // There is no id in it, so there is nothing to fail and nobody to tell.
    // What must not happen is the handler taking the reader thread down with
    // it and every later answer on that socket going unread.
    String session = "garbage";
    Silent silent = connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    // No sleep: frames on one socket are delivered in order, so the request
    // below cannot be answered before this one has been read and dropped.
    // Ordering is the fixture, and it is free.
    silent.socket.send("{this is not a reply");

    CompletableFuture<String> waiting =
        CompletableFuture.supplyAsync(() -> text(provider, Path.of("/laptop/A.java")));
    silent.awaitRequests(1);
    silent.reply(FileReply.answered(silent.requests.peek().id(), one("still working")));

    assertEquals(
        "still working",
        waiting.get(10, TimeUnit.SECONDS),
        "the socket survived a frame it could not parse");
  }

  @Test
  void a_run_asked_to_stop_while_waiting_for_a_client_stops() throws Exception {
    // JobStore.close asks every run to stop, and a thread whose interrupt was
    // swallowed here goes back round its loop. The flag has to survive the
    // wait, or a shutdown hangs on a client that is not answering.
    String session = "interrupted";
    connect(FAST_PATH, session);
    RemoteProvider provider = new RemoteProvider(fast, session, WRITE);

    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> flag = new AtomicReference<>();
    Thread waiting =
        new Thread(
            () -> {
              try {
                provider.read(Path.of("/laptop/A.java"), FIRST);
              } catch (RuntimeException stopped) {
                thrown.set(stopped);
                flag.set(Thread.currentThread().isInterrupted());
              }
            });
    waiting.start();
    Thread.sleep(60);
    waiting.interrupt();
    waiting.join(10_000);

    assertTrue(thrown.get() instanceof WorkspaceUnavailableException, String.valueOf(thrown.get()));
    assertTrue(thrown.get().getMessage().contains("interrupted"), thrown.get().getMessage());
    // And not SessionGoneException: this wait was ended by THIS process
    // asking the run to stop, and the client on the other end is exactly
    // where it was. An ending blaming it would name the wrong party.
    assertFalse(thrown.get() instanceof SessionGoneException, String.valueOf(thrown.get()));
    assertEquals(
        Boolean.TRUE,
        flag.get(),
        "and the interrupt flag is still set, or the run that was asked to stop"
            + " goes back round its loop");
  }

  // --- fixtures ------------------------------------------------------------

  /**
   * A real {@link SocketPeer} over a real workspace, closed after the test. Returned so a test can
   * close it early — a client going away mid-run is what the session-gone tests are about, and the
   * only way to produce one here is to be holding it.
   */
  private SocketPeer client(String session, TestWorkspace workspace) throws Exception {
    Object incumbent = attachment(productionSessions, session).orElse(null);
    SocketPeer client =
        new SocketPeer("http://localhost:" + port, session, new NodeFiles(workspace));
    opened.add(client);
    client.open();
    awaitAttachmentOtherThan(productionSessions, session, incumbent);
    return client;
  }

  /**
   * A raw socket that answers however the test says, for the cases a real enforcer cannot produce.
   */
  private void answering(String path, String session, Function<FileRequest, FileReply> answer)
      throws Exception {
    connect(path, session).answer = answer;
  }

  private Silent connect(String path, String session) throws Exception {
    return connect(path, session, "");
  }

  /**
   * A socket that opens with more on the query string than its session id, and that is waited for
   * the same way.
   *
   * @param extra query pairs, each with a leading {@code &}, or empty
   */
  private Silent connect(String path, String session, String extra) throws Exception {
    SessionRegistry sessions = sessionsBehind(path);
    Object incumbent = attachment(sessions, session).orElse(null);
    Silent silent = dial(path, session, extra);
    awaitAttachmentOtherThan(sessions, session, incumbent);
    return silent;
  }

  /**
   * The socket without the wait, for the one case where the server refuses it.
   *
   * <p>{@link #connect} waits for an attachment, and a socket the server closes on arrival never
   * produces one — so waiting for it would be waiting out the poll's whole patience and then
   * failing with the wrong sentence.
   */
  private Silent dial(String path, String session, String extra) {
    Silent silent = new Silent();
    silent.socket =
        http.newWebSocket(
            new Request.Builder()
                .url(
                    "ws://localhost:"
                        + port
                        + path
                        + "?"
                        + FileChannelHandler.SESSION_PARAM
                        + "="
                        + session
                        + extra)
                .build(),
            silent);
    opened.add(silent);
    return silent;
  }

  /** What a client puts on the query string to say it roots a project here. */
  private static String rooting(String machine, String root, String project) {
    return "&"
        + FileChannelHandler.MACHINE_PARAM
        + "="
        + machine
        + "&"
        + FileChannelHandler.ROOT_PARAM
        + "="
        + root
        + "&"
        + FileChannelHandler.PROJECT_PARAM
        + "="
        + project;
  }

  /**
   * Which registry a path's handler attaches to. The two are deliberately separate — {@code
   * Wiring.FAST_SESSIONS} says why — so a wait against the wrong one would be a wait against
   * nothing.
   */
  private static SessionRegistry sessionsBehind(String path) {
    if (FAST_PATH.equals(path)) {
      return Wiring.FAST_SESSIONS;
    }
    if (FileChannelHandler.PATH.equals(path)) {
      return productionSessions;
    }
    throw new AssertionError("no handler in this fixture is published on " + path);
  }

  /**
   * The close is asynchronous too, and in the other direction: {@code SocketPeer.close} returns as
   * soon as it has asked, so a request sent straight afterwards can still find the session
   * registered and wait out a deadline instead of failing on the close.
   */
  private void awaitDisconnected(String session) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      if (!production.isConnected(session) && !fast.isConnected(session)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("session '" + session + "' never went away");
  }

  /**
   * Whatever is in the file-provider role of {@code session} right now, by identity. {@code
   * Object.class} because the real type is {@code FileChannelHandler.Live}, which is private;
   * identity is all this needs.
   */
  private static Optional<Object> attachment(SessionRegistry sessions, String session) {
    return sessions.find(session).flatMap(live -> live.attached(Role.FILE_PROVIDER, Object.class));
  }

  /**
   * Wait until the socket THIS call opened is the attached one.
   *
   * <p>The upgrade is asynchronous on both sides, so a test that sent a request the moment {@code
   * newWebSocket} returned would race the registration. What this used to wait on was {@code
   * isConnected}, which answers "is anybody attached" — already true for every connection that
   * DISPLACES one, so the poll returned on its first pass and the wait was against nothing. Every
   * displacing call site in this file happens to compensate, by holding a request open until it
   * fails with "replaced" or by waiting on its own socket's {@code awaitRequests}; {@code
   * RemoteWiringTest} did not, and produced exactly the flake that shape predicts. The helper is
   * fixed here rather than only there, because a correction applied to one copy leaves the other
   * waiting on nothing.
   *
   * <p>{@code EventChannelTest.awaitListenerOtherThan} is where this shape already existed for the
   * listener role.
   *
   * @param incumbent what held the role before this connection was opened, or null if the role was
   *     empty
   */
  private static void awaitAttachmentOtherThan(
      SessionRegistry sessions, String session, Object incumbent) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      Optional<Object> now = attachment(sessions, session);
      if (now.isPresent() && now.get() != incumbent) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "session '"
            + session
            + "' never registered "
            + (incumbent == null ? "a socket" : "a SECOND socket")
            + " — "
            + sessions.find(session));
  }

  /** A client that records what it was asked and answers only when told to. */
  private static final class Silent extends WebSocketListener implements AutoCloseable {

    private final ConcurrentLinkedQueue<FileRequest> requests = new ConcurrentLinkedQueue<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile WebSocket socket;
    private volatile Function<FileRequest, FileReply> answer;

    /**
     * Why the far end said it was closing. Kept because a refusal that does not travel is a refusal
     * a person never sees: the whole content of a presence conflict is the sentence naming the two
     * claims.
     */
    private volatile String closeReason = "";

    /** The status the far end closed with; 0 until it has. */
    private volatile int closeCode;

    /**
     * Every text frame as it arrived, before anything reads it as a request — where the one frame
     * that is not a request, the claim landing, is looked for.
     */
    private final ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();

    @Override
    public void onMessage(WebSocket from, String text) {
      frames.add(text);
      if (text.contains("\"ready\"")) {
        return;
      }
      try {
        FileRequest request = json().readValue(text, FileRequest.class);
        requests.add(request);
        Function<FileRequest, FileReply> answering = answer;
        if (answering != null) {
          // On a thread of its own, so a test whose answer sleeps does
          // not stop this socket reading the next request — which is
          // exactly what the real client does and why it does it.
          Thread.ofVirtual().start(() -> reply(answering.apply(request)));
        }
      } catch (IOException notARequest) {
        throw new AssertionError(notARequest);
      }
    }

    void reply(FileReply answering) {
      try {
        socket.send(json().writeValueAsString(answering));
      } catch (IOException notWritable) {
        throw new AssertionError(notWritable);
      }
    }

    void awaitRequests(int many) throws InterruptedException {
      for (int i = 0; i < 500; i++) {
        if (requests.size() >= many) {
          return;
        }
        Thread.sleep(5);
      }
      throw new AssertionError("expected " + many + " requests, saw " + requests.size());
    }

    @Override
    public void onClosing(WebSocket from, int code, String reason) {
      // Answering the close is what completes the handshake — measured on
      // okhttp 4.12, onClosed never arrives otherwise — and the latch is
      // what lets a test assert the server closed this socket rather than
      // sleeping and hoping.
      closeReason = reason == null ? "" : reason;
      closeCode = code;
      from.close(1000, null);
      closed.countDown();
    }

    @Override
    public void onClosed(WebSocket from, int code, String reason) {
      if (reason != null && !reason.isEmpty()) {
        closeReason = reason;
      }
      closed.countDown();
    }

    boolean awaitClosed(long millis) throws InterruptedException {
      return closed.await(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
      socket.close(1000, null);
    }
  }

  private static ObjectMapper json() {
    return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  /**
   * The window every read in this file asks for.
   *
   * <p>Which window is never this file's subject — the socket is, and its two bounds and its
   * correlation. That the window travels in the frame at all is {@code RemoteProviderTest}'s to
   * measure, and how it is cut is {@code Window}'s.
   */
  private static final Window FIRST = Window.of(0, Window.MAX_WINDOW_LINES);

  /** A one-line answer, which is the whole of what a socket test needs a read to come back with. */
  private static Span one(String line) {
    return new Span(List.of(line), 0, 1, false, Span.END);
  }

  /**
   * One read as a string, so a test about a socket can go on comparing the text it sent with the
   * text it got.
   *
   * <p>Joined with {@code "\n"} and the range dropped. A file's own trailing newline does not
   * survive that and is not meant to: the lines are the reply's only carrier now, and a terminator
   * is not part of the line it ended.
   */
  private static String text(RemoteProvider provider, java.nio.file.Path path) {
    return String.join("\n", provider.read(path, FIRST).lines());
  }

  private static Throwable caught(Runnable body) {
    try {
      body.run();
      return null;
    } catch (RuntimeException thrown) {
      return thrown;
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new AssertionError(stopped);
    }
  }

  /**
   * The web layer and nothing else — no Postgres, no model, no agent registry.
   *
   * <p>Three autoconfigurations by name rather than {@code @SpringBootTest}: this class needs a
   * servlet container and Spring Boot's own WebSocket support, and nothing else in the application.
   * <b>Measured:</b> a hand-built context with a {@code TomcatServletWebServerFactory} answers the
   * upgrade with a 500, because it is {@code WebSocketServletAutoConfiguration} that registers
   * Tomcat's {@code WsSci} — so the production autoconfiguration is what is under test rather than
   * a stand-in for it.
   */
  @Configuration
  @EnableWebSocket
  // `Watchers` travels with the channel: EventChannelConfig requires it
  // rather than inventing one, which is what keeps the frame handler and
  // the channel holding the same instance.
  @Import({FileChannelConfig.class, EventChannelConfig.class, Watchers.class})
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebSocketServletAutoConfiguration.class
  })
  static class Wiring implements WebSocketConfigurer {
    @Bean
    io.aeyer.plowshare.server.access.ProjectAuthorization projectAuthorization() {
      var authorization =
          org.mockito.Mockito.mock(io.aeyer.plowshare.server.access.ProjectAuthorization.class);
      org.mockito.Mockito.when(
              authorization.allowed(
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.any(
                      io.aeyer.plowshare.server.access.AccessRequest.class),
                  org.mockito.ArgumentMatchers.nullable(String.class)))
          .thenReturn(true);
      return authorization;
    }

    @Bean
    SocketAuthorization socketAuthorization() {
      // Transport-only fixture; account authorization is covered by ServerAdministrationTest.
      return new SocketAuthorization(
          org.mockito.Mockito.mock(io.aeyer.plowshare.server.auth.AdminStore.class));
    }

    /**
     * A registry of its own for the short-deadline handler, so its sessions and the production
     * handler's cannot displace each other under the plain words this file attaches with. The
     * production handler's is the one {@code EventChannelConfig} defines and {@code
     * FileChannelConfig} injects; keeping them apart is what makes the two handlers as independent
     * as they were when each kept its own map.
     *
     * <p>Declared before the handler that takes it: static initialisers run in textual order, so
     * the other way round hands it a null.
     */
    static final SessionRegistry FAST_SESSIONS = new SessionRegistry();

    /** The short-deadline handler, on a path only this file knows. */
    // NOT a bean, and the reason outlived the thing that caused it. A
    // second FileChannelHandler in the context made getBean(...class)
    // ambiguous and broke this file's own access to the handler
    // FileChannelConfig builds; the fix over there was to delete a
    // redundant second bean, which that class records. Keeping this one out
    // of the context is what stops the test putting the ambiguity back — a
    // fixture must not be able to break the wiring it is measuring.
    /**
     * And a presence registry of its own, for {@link #FAST_SESSIONS}' reason: the two handlers must
     * stay as independent as they were when each kept its own map, and a shared one would let a
     * claim made on one path refuse a socket on the other.
     */
    static final PresenceRegistry FAST_PRESENCES = new PresenceRegistry();

    /**
     * Where the durable half of a declaration goes in this fixture.
     *
     * <p><b>A recorder and not a {@code ProjectStore}</b>, which is what the seam is for: this
     * context is "the web layer and nothing else — no Postgres", and the question here is what the
     * <em>socket</em> asserts. That the assertion becomes the right row is {@code
     * ProjectStoreTest}'s question and it is asked against a real database there.
     *
     * <p>Static because the context is, so {@link #closeWhatThisTestOpened} resets it between
     * tests: a switch left set here closes the next socket that declares a project, and the failure
     * surfaces in a test that has nothing to do with any of this.
     */
    static final Rooted ROOTED = new Rooted();

    static final FileChannelHandler FAST_HANDLER =
        new FileChannelHandler(
            FAST_SESSIONS, FAST_PRESENCES, ROOTED, TestProjectMembers.allowed(), FAST);

    @Bean
    io.aeyer.plowshare.server.archive.ProjectMembers projectMembers() {
      return io.aeyer.plowshare.server.ws.TestProjectMembers.allowed();
    }

    @Bean
    ProjectRoots projectRoots() {
      return ROOTED;
    }

    /** What was written down, and a switch for each of the two ways writing it down can fail. */
    static final class Rooted implements ProjectRoots {

      private final List<String> written = Collections.synchronizedList(new ArrayList<>());
      private volatile String refusal;
      private volatile String outage;

      /** When set, a declaration waits here: a connect held mid-claim. */
      private volatile CountDownLatch gate;

      /** Counted down once a declaration is waiting at {@link #gate}. */
      private final CountDownLatch waiting = new CountDownLatch(1);

      @Override
      public void rootOn(String project, String machine, String root, String handle) {
        CountDownLatch held = gate;
        if (held != null) {
          waiting.countDown();
          try {
            held.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          }
        }
        written.add(project + "|" + machine + "|" + root);
        if (refusal != null) {
          throw new ArchiveRefusedException(refusal);
        }
        if (outage != null) {
          throw new ArchiveUnavailableException(outage, new IllegalStateException(outage));
        }
      }

      List<String> written() {
        return List.copyOf(written);
      }

      void clear() {
        written.clear();
        refusal = null;
        outage = null;
        CountDownLatch held = gate;
        gate = null;
        if (held != null) {
          held.countDown();
        }
      }

      void refuseWith(String reason) {
        refusal = reason;
      }

      void breakWith(String reason) {
        outage = reason;
      }
    }

    /**
     * AuthFilter's part, and only it: the account a test names as {@code ?as=} is set as {@link
     * AuthFilter#HANDLE_ATTRIBUTE}, which {@code HandleInterceptor} copies onto the socket.
     * AuthFilter itself is {@code AuthFilterTest}'s; what is asked here is what the file channel
     * does with the account once it has one.
     */
    @Bean
    Filter namesTheAccount() {
      return (request, response, chain) -> {
        String as = request.getParameter(AS_PARAM);
        if (as != null) {
          request.setAttribute(AuthFilter.HANDLE_ATTRIBUTE, as);
        }
        chain.doFilter(request, response);
      };
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
      registry.addHandler(FAST_HANDLER, FAST_PATH).addInterceptors(new HandleInterceptor());
    }
  }
}
