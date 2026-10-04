package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.client.HttpServerClient;
import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.SessionClient;
import io.aeyer.plowshare.client.files.ChannelClient;
import io.aeyer.plowshare.client.files.ClientEnforcer;
import io.aeyer.plowshare.client.files.Rooting;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.AgentsConfig;
import io.aeyer.plowshare.server.agents.AgentsProperties;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionChecks;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.DefinitionWriter;
import io.aeyer.plowshare.server.agents.Definitions;
import io.aeyer.plowshare.server.agents.FileTools;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Limits;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.api.AgentController;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.union.UnionRouting;
import io.aeyer.plowshare.server.ws.EventChannelConfig;
import io.aeyer.plowshare.server.ws.EventChannelHandler;
import io.aeyer.plowshare.server.ws.FileChannelConfig;
import io.aeyer.plowshare.server.ws.FileChannelHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The whole path, in one process: an operator's terminal opens a session, submits a run over HTTP
 * under it, and the run reads a file that only the operator's machine has.
 *
 * <h2>The two proofs this class exists for</h2>
 *
 * <p>The design spec names two things the slice must establish and nothing before this task could.
 * Both are here, and both are about a <em>single</em> file request rather than about a layer:
 *
 * <ul>
 *   <li><b>A job submitted with a session reaches the operator's disk</b> — {@link
 *       #a_job_submitted_with_a_session_reads_a_file_only_the_operator_has} goes over a real HTTP
 *       POST, through {@code AgentController}, {@code JobStore}, {@code JobRuntime}, {@code
 *       FileTools}, {@code ProviderRouter}, {@code RemoteProvider}, a real WebSocket, the real
 *       {@code ChannelClient} and the real {@code ClientEnforcer}, and the bytes it hands the model
 *       are bytes this server's own filesystem never held;
 *   <li><b>{@code SESSION_GONE} comes from a real disconnect mid-request</b> — {@link
 *       #a_socket_that_dies_with_a_request_on_it_is_the_run_ending} closes a live socket from the
 *       client side <em>while the server is waiting on a frame that socket owes it</em>, and the
 *       run ends {@code SESSION_GONE}. No test double throws it.
 * </ul>
 *
 * <h2>How one request shows both enforcements executing</h2>
 *
 * <p>{@code RemoteProvider}'s javadoc states the design: the doubling for a remote path is <b>the
 * router and the client</b>, both running the {@code FileAccess} that lives in {@code
 * plowshare-protocol} for exactly this reason. Every test before this one could see one side or the
 * other. {@link #one_request_is_canonicalised_by_the_router_and_refused_again_by_the_client} sees
 * both, and it does it by asking for a path spelled so that each execution is separately
 * load-bearing:
 *
 * <pre>    &lt;the client's workspace&gt;/../outside/Ledger.java</pre>
 *
 * <p>That string <b>starts with the root the client advertised</b>. A router that compared the path
 * as written would route it to the operator's machine; it is {@code FileAccess.canonical} inside
 * {@code ProviderRouter.providerFor} that resolves the {@code ..} and refuses. So the server's
 * execution is what produces the tool result — and the assertion on that result is what fails if it
 * stops running. <b>Measured, by replacing that call with the path as written:</b> the read is then
 * routed to the client, the client refuses it, and the tool result becomes the client's sentence
 * instead of the router's, which this test catches.
 *
 * <p>The client's execution is the other half of the same request, and the test reaches it by
 * asking the same live channel for the same path with the router stepped over — {@code
 * RemoteProvider.read} directly, on the provider the production seam built. The answer is the
 * client's own containment sentence, produced by {@code FileAccess.permits} in the process that
 * would have opened the file. That is the spec's clause in full: <b>the client refuses what the
 * server would have refused, and it refuses it for having run the same code.</b>
 *
 * <p>The fixture is a file that <b>exists and is readable by this process</b>, so neither refusal
 * can be an accident of absence — the standing check about a filtering query needing something to
 * filter out, applied to a containment check.
 *
 * <h2>A session going while a job runs that never needed it: deferred, not duplicated</h2>
 *
 * <p>The spec also calls out that losing a session must not end a run that was never reaching
 * through it, and it is already covered — by two tests that compose, so a third here would be a
 * longer way of asking the same question. The two halves are {@code
 * RemoteWiringTest.closing_the_file_channel_takes_the_second_machine_away}, where a <b>real</b>
 * {@code ChannelClient} closing a <b>real</b> socket is what empties the role, and {@code
 * RemoteWiringTest.a_client_going_away_mid_run_does_not_end_a_run_that_reads_the_servers_own_disk},
 * where the role emptying between two turns leaves the run answering off the server's own disk.
 * <b>Measured</b> rather than argued, by deleting {@code sessions.detach} from {@code
 * FileChannelHandler.afterConnectionClosed}: the first of the two fails and the second stays green,
 * which is the decomposition itself — the first holds the real close's only effect on a run, the
 * second holds what a run does once that effect has landed, and an end-to-end version would only be
 * the two of them in one method. The close callback does nothing else to a run: it fails the
 * requests outstanding <em>on that socket</em>, and a run that never asked has none.
 *
 * <h2>The context, and why it is hand-built</h2>
 *
 * <p>Same reasoning as {@code RemoteWiringTest} and {@code FileChannelTest}: the two channel
 * configurations plus the servlet, MVC and WebSocket autoconfigurations by name, so the handler,
 * the registry and the paths are the ones production builds, with no Postgres and no model behind
 * them. The model is a scripted transport; {@code ProjectStore} is a mock naming one real
 * directory, because what is measured here is which machine a file came off rather than how a
 * project row is stored.
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
class SessionEndToEndTest {

  /** The project every run here is submitted under. */
  private static final String PROJECT = "payments";

  private static final Home PAYMENTS = Home.of(PROJECT);

  /**
   * What the fixture agent's definition declares, restated for the direct provider call that steps
   * over the router.
   */
  private static final List<Grant> READ = List.of(new Grant(Scope.WORKSPACE, Mode.READ));

  /**
   * Text that exists on exactly one of the two machines in this test, and is distinctive enough
   * that finding it anywhere else is an answer.
   *
   * <p>No trailing newline in the constant, and the writes below add one: a read's lines are now
   * the reply's only carrier and a terminator is not part of the line it ended, so a tool result
   * never contains one at the end of the file. The constant is what has to survive the socket, and
   * that is the line and not its terminator.
   */
  private static final String ONLY_ON_THE_LAPTOP =
      "class Ledger { /* this file is on the operator's disk and nowhere else */ }";

  /**
   * How long a poll waits before calling a state wrong. Generous: everything waited for here
   * happens in milliseconds, and the number only has to be larger than a loaded machine's hiccup.
   */
  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @TempDir static Path tmp;

  /** The operator's machine. */
  private static Path onTheLaptop;

  /**
   * The project's workspace as this server sees it — a different directory, so "the file came off
   * the client" is a fact about which tree holds it rather than about which provider answered
   * first.
   */
  private static Path onTheServer;

  /** Outside both, and reachable by neither. */
  private static Path outside;

  private static Path definitions;

  private static final Scripted MODEL = new Scripted();

  private static ConfigurableApplicationContext context;
  private static FileChannelHandler handler;
  private static SessionRegistry registry;
  private static RunProviders providers;
  private static int port;

  private final List<AutoCloseable> opened = new ArrayList<>();

  @BeforeAll
  static void startTheServer() throws IOException {
    onTheLaptop = Files.createDirectory(tmp.resolve("laptop"));
    onTheServer = Files.createDirectory(tmp.resolve("server"));
    outside = Files.createDirectory(tmp.resolve("outside"));
    definitions = Files.createDirectory(tmp.resolve("definitions"));
    Files.writeString(onTheLaptop.resolve("Ledger.java"), ONLY_ON_THE_LAPTOP + "\n");
    Files.writeString(outside.resolve("Ledger.java"), ONLY_ON_THE_LAPTOP + "\n");
    writeReader(definitions);

    context =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            // A command-line argument and not properties(...): FileChannelTest
            // measured that the latter loses to application.yml, which names a
            // fixed port.
            .run("--server.port=0");
    port = ((WebServerApplicationContext) context).getWebServer().getPort();
    handler = context.getBean(FileChannelHandler.class);
    registry = context.getBean(SessionRegistry.class);
    providers = context.getBean(RunProviders.class);
  }

  @AfterAll
  static void stopTheServer() {
    context.close();
  }

  @BeforeEach
  void emptyTheScript() {
    MODEL.reset();
  }

  @AfterEach
  void closeWhatThisTestOpened() throws InterruptedException {
    for (AutoCloseable open : opened) {
      try {
        open.close();
      } catch (Exception ignored) {
        // Closing a fixture is not what any test here is about.
      }
    }
    opened.clear();
    // Socket close callbacks withdraw presence asynchronously. The next test
    // roots this same project, so cleanup must finish before it can attach.
    PresenceRegistry presences = context.getBean(PresenceRegistry.class);
    for (long waited = 0; waited < PATIENCE.toMillis(); waited += 10) {
      if (presences.serving(PROJECT).isEmpty()) {
        return;
      }
      Thread.sleep(10);
    }
    assertTrue(
        presences.serving(PROJECT).isEmpty(),
        "the closed test sessions must release the project before the next test");
  }

  // --- proof one: the operator's disk ---------------------------------------

  /**
   * A terminal opens a session, submits a run under it, and the run reads a file this server has
   * never had.
   *
   * <p>Everything on the path is the production article: {@link SessionClient} attaches both roles
   * over two real sockets on an ephemeral port, {@code submit} is an HTTP POST carrying the session
   * id in the body, and the server's answer to {@code file_read} is what the operator's own {@code
   * ClientEnforcer} read off their disk.
   *
   * <p>Three separate claims, because "it worked" is not one of them: the run answered; the file's
   * text reached the model as a tool result; and the server's own workspace never held that file,
   * so the text cannot have come from the local provider.
   */
  @Test
  void a_job_submitted_with_a_session_reads_a_file_only_the_operator_has() throws Exception {
    SessionClient session = attach(workspaceOver(onTheLaptop));
    MODEL
        .then(
            asking(
                "call-1",
                FileTools.READ_NAME,
                "{\"path\":\"" + json(onTheLaptop.resolve("Ledger.java")) + "\"}"))
        .then(done("read it"));

    String job = session.submit("reader", "read the ledger", PROJECT, null);
    ServerClient.JobStatus finished = awaitOutcome(session, job);

    assertEquals(
        Outcome.Ending.ANSWERED.name(),
        finished.outcome().ending(),
        "the run answered — " + finished.outcome().detail());
    assertTrue(
        toolResults().stream().anyMatch(result -> result.contains(ONLY_ON_THE_LAPTOP)),
        "the file's own text reached the model as a tool result, having crossed the"
            + " socket from the operator's machine — "
            + toolResults());
    assertFalse(
        Files.exists(onTheServer.resolve("Ledger.java")),
        "and this server's own workspace never held that file, so the text cannot have"
            + " come off the local provider");
  }

  /**
   * The lifecycle of that same run is watched as it happens, and the events carry nothing that was
   * read.
   *
   * <p>The listener is the second role the same {@link SessionClient} attached, on the second
   * socket. What arrives is four kinds of event and no payload — {@code JobEvent} has nowhere to
   * put one, which is containment by signature, and this is that claim asserted against a real
   * stream rather than against the record's component list.
   */
  @Test
  void the_terminal_watches_that_run_go_by_without_ever_being_told_what_it_read() throws Exception {
    SessionClient session = attach(workspaceOver(onTheLaptop));
    MODEL
        .then(
            asking(
                "call-1",
                FileTools.READ_NAME,
                "{\"path\":\"" + json(onTheLaptop.resolve("Ledger.java")) + "\"}"))
        .then(done("read it"));

    String job = session.submit("reader", "read the ledger", PROJECT, null);
    List<JobEvent> events = awaitEnded(session, job);

    assertEquals(
        List.of(
            JobEvent.STARTED,
            JobEvent.MODEL_CALL,
            JobEvent.TOOL_CALLED,
            JobEvent.MODEL_CALL,
            JobEvent.ENDED),
        events.stream().map(JobEvent::kind).toList(),
        "the run's whole life, in order, on the operator's terminal");
    assertEquals(
        FileTools.READ_NAME,
        events.stream()
            .filter(event -> JobEvent.TOOL_CALLED.equals(event.kind()))
            .findFirst()
            .orElseThrow()
            .tool(),
        "and the tool is named");
    for (JobEvent event : events) {
      // Arrays.asList and not List.of: every String field a JobEvent has
      // is checked here, and TWO of the five are nullable -- `tool`, set
      // on tool_called alone, and `ending`, set on ended alone, so each is
      // non-null on one of the four kinds. (This said "three of the five
      // are null on most kinds", which was wrong in both halves and in a
      // way nothing fails over: List.of would refuse a null whichever
      // count were true.) `job`, `agent` and `kind` are never null.
      for (String said :
          Arrays.asList(event.job(), event.agent(), event.tool(), event.ending(), event.kind())) {
        if (said == null) {
          continue;
        }
        assertFalse(
            said.contains(ONLY_ON_THE_LAPTOP) || said.contains("Ledger.java"),
            "no event says what was read or which file it was, and this one said '" + said + "'");
      }
    }
  }

  // --- proof two: one request, two enforcements ----------------------------

  /**
   * One {@code file_read}, refused twice over, by the two halves of the same rule.
   *
   * <p>The class javadoc argues this at length; the shape is that the path is spelled {@code <the
   * client's root>/../outside/Ledger.java}, which <em>textually</em> lies under the root the client
   * advertised and canonically does not.
   *
   * <ul>
   *   <li>the <b>server's</b> execution is the tool result: {@code ProviderRouter} canonicalised
   *       the path, found no provider covering it, and said so — naming the client's root, which
   *       only the client's own {@code FileAccess} could have produced;
   *   <li>the <b>client's</b> execution is what the same live channel answers when the router is
   *       stepped over: its own containment sentence, from the process that would have opened the
   *       file.
   * </ul>
   *
   * <p>The file at that path exists and is readable here, asserted before anything else, so neither
   * refusal is a missing file wearing a containment message.
   */
  @Test
  void one_request_is_canonicalised_by_the_router_and_refused_again_by_the_client()
      throws Exception {
    SessionClient session = attach(workspaceOver(onTheLaptop));
    // The fixture has something to refuse. Both refusals below are about
    // containment, and a test whose file simply was not there would read
    // exactly the same from outside.
    // Built from the root as the client will advertise it -- real-pathed --
    // so that "starts with the root" below is true of the string the router
    // is handed, on a host whose temp directory is itself behind a symlink.
    Path escaping =
        onTheLaptop.toRealPath().resolve("..").resolve("outside").resolve("Ledger.java");
    assertEquals(
        ONLY_ON_THE_LAPTOP + "\n",
        Files.readString(escaping),
        "the file is there and this process can read it, so a refusal below is a"
            + " refusal and not an absence");
    assertTrue(
        escaping.toString().startsWith(onTheLaptop.toRealPath().toString()),
        "and as written the path starts with the root the client advertises, which is"
            + " what makes canonicalisation load-bearing rather than decorative");

    MODEL
        .then(asking("call-1", FileTools.READ_NAME, "{\"path\":\"" + json(escaping) + "\"}"))
        .then(done("could not read it"));
    String job = session.submit("reader", "read the ledger", PROJECT, null);
    ServerClient.JobStatus finished = awaitOutcome(session, job);

    assertEquals(
        Outcome.Ending.ANSWERED.name(),
        finished.outcome().ending(),
        "a refused path is a tool result and not an ending — " + finished.outcome());
    String routed =
        toolResults().stream()
            .filter(result -> result.contains("outside every root"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the router's refusal is what the model was handed: " + toolResults()));
    assertTrue(
        routed.contains(onTheLaptop.toRealPath().toString()),
        "and it accounts for the client's own root, which only the client's FileAccess"
            + " could have produced — "
            + routed);

    // The same path, the same live channel, the router stepped over: what the
    // client says when it is the one asked.
    FileProvider remote = remoteOf(providers.forRun(PAYMENTS, READ, session.id(), null));
    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () -> remote.read(escaping, Window.of(0, Window.MAX_WINDOW_LINES)));
    assertTrue(
        refused.getMessage().contains("outside this session's workspace"),
        "the client refuses what the server would have refused, having run the same"
            + " FileAccess in the process that would have opened the file — "
            + refused.getMessage());
    assertFalse(
        refused.getMessage().contains(ONLY_ON_THE_LAPTOP),
        "and it refuses without quoting what it declined to send");
  }

  // --- proof three: a real disconnect, mid-request --------------------------

  /**
   * A socket dies with a request outstanding on it, and that is the run's ending.
   *
   * <p>The client here answers {@code roots} and then, on the {@code read}, closes instead of
   * replying — so the close happens at the one moment that makes this a mid-request disconnect
   * rather than a disconnect that happens to precede a request. Nothing throws {@code
   * SessionGoneException}: the server produces it from {@code afterConnectionClosed}, which fails
   * the futures the dead socket owed.
   *
   * <p><b>Which of the two disconnects it was is asserted, not assumed.</b> {@code
   * FileChannelHandler} has two sentences for a gone session and only one of them can be reached by
   * a socket dying under a live request: "closed while this was waiting". The other — "no client
   * session ... is connected" — is what a request sent after the socket had already gone would
   * produce, and a test that only checked the ending would pass on either.
   *
   * <p>The client is a raw listener on {@link ChannelClient#dial} rather than a {@code
   * ClientEnforcer}, because its whole job in this proof is to stop answering and disappear, and
   * the enforcer is what the two tests above drive. It is still {@code ChannelClient}'s own socket,
   * on the connection pool the module's HTTP client owns, so no fourth HTTP client is built to
   * write this.
   */
  @Test
  void a_socket_that_dies_with_a_request_on_it_is_the_run_ending() throws Exception {
    String id = "the-laptop-that-closes-" + UUID.randomUUID();
    Vanishing laptop = new Vanishing(onTheLaptop.toRealPath());
    dial(id, laptop);
    awaitRole(id, Role.FILE_PROVIDER, true);

    MODEL.then(
        asking(
            "call-1",
            FileTools.READ_NAME,
            "{\"path\":\"" + json(onTheLaptop.resolve("Ledger.java")) + "\"}"));
    JobStore jobs = context.getBean(JobStore.class);
    String job =
        jobs.submit(
            context.getBean(AgentRegistry.class).get("reader"), "read the ledger", PAYMENTS, id);

    Outcome outcome = awaitOutcome(jobs, job);

    assertTrue(laptop.answeredRoots(), "the session was live enough to answer for its roots");
    assertTrue(
        laptop.closedOnARequest(),
        "and it closed while holding a request rather than before one arrived");
    assertEquals(
        Outcome.Ending.SESSION_GONE,
        outcome.ending(),
        "a socket dying under a request is this ending — " + outcome.detail());
    assertTrue(
        outcome.detail().contains("closed while this was waiting"),
        "and it is the mid-request sentence rather than the one for a request sent"
            + " after the socket had already gone — "
            + outcome.detail());
    assertTrue(
        outcome.detail().startsWith(FileTools.READ_NAME + ":"),
        "named against the tool that was asking — " + outcome.detail());
    assertFalse(
        registry.find(id).map(session -> session.has(Role.FILE_PROVIDER)).orElse(false),
        "and the close really reached the registry");
  }

  // --- fixtures -------------------------------------------------------------

  /**
   * A real {@link SessionClient} attached in both roles, closed after the test. Its id is minted by
   * the client, as it is on a laptop.
   *
   * <p><b>And it roots {@link #PROJECT}</b>, which is what makes every run in this file reach it.
   * Under presence the session a run was submitted under decides nothing about a named project; the
   * session that <em>declared</em> it does, and this is where that declaration is made — over the
   * production upgrade, by the production client, exactly as {@code cli.Plowshare} composes one
   * from {@code --workspace} and {@code --project}.
   */
  private SessionClient attach(Workspace workspace) throws Exception {
    SessionClient session =
        new SessionClient(
            new HttpServerClient("http://localhost:" + port),
            workspace,
            null,
            Rooting.of(workspace.roots().get(0), PROJECT));
    opened.add(session);
    session.attach(PATIENCE);
    assertTrue(session.providing() && session.listening(), "the session attached in both roles");
    // Both roles, and the listener is the one that matters here: a run
    // submitted while only the provider had reached the registry would miss
    // its own STARTED, because JobStore.start publishes that as soon as the
    // executor picks the job up.
    awaitRole(session.id(), Role.FILE_PROVIDER, true);
    awaitRole(session.id(), Role.LISTENER, true);
    return session;
  }

  /**
   * A bare socket in the file-provider role, on {@code ChannelClient}'s own HTTP client, closed
   * after the test.
   *
   * <p>The dialled socket is held and closed <b>before</b> the client, which is that method's
   * stated contract and was not being kept: {@code open()} is never called here, so {@code
   * dialling.close()} found a null socket of its own, closed nothing, and then shut down the
   * dispatcher and evicted the pool that this live socket was running on. The socket the test
   * actually opened was the one thing cleanup did not close.
   */
  private void dial(String id, WebSocketListener frames) {
    ChannelClient dialling =
        new ChannelClient("http://localhost:" + port, id, new ClientEnforcer(new Workspace()));
    WebSocket socket = dialling.dial(ChannelClient.PATH, frames);
    // The presence, declared into the registry rather than sent up the
    // socket. `dial` is the raw-listener door — production uses it for the
    // EVENTS path, which carries a session id and nothing else — so it does
    // not put the presence parameters on the query string, and giving it an
    // overload that could would be widening a client API for one fixture.
    // What this test is about is a socket dying under a live request, and
    // what it needs from presence is only that the run reaches this id. The
    // declaration over the wire is `attach`'s, two helpers up, and
    // FileChannelTest is where the query string itself is measured.
    context
        .getBean(PresenceRegistry.class)
        .declare(new Presence(id, "the-laptop", onTheLaptop.toString(), PROJECT));
    opened.add(
        () -> {
          socket.close(1000, "the test is done");
          dialling.close();
        });
  }

  private static Workspace workspaceOver(Path root) {
    Workspace workspace = new Workspace();
    workspace.set(List.of(root));
    return workspace;
  }

  /** The upgrade is asynchronous on both sides. */
  /**
   * Wait until the registry agrees about one role.
   *
   * <p>Takes the role rather than assuming the file provider. It used to poll {@code FILE_PROVIDER}
   * only, and the caller that attaches <em>both</em> then went on to submit a run knowing nothing
   * about the listener — while {@code JobStore.start} publishes {@code STARTED} as soon as the
   * executor picks the job up, and the listener attaches after its own 101. A small window, and a
   * real one: what it loses is the first event of a run, which is the assertion the test using it
   * makes.
   */
  private static void awaitRole(String id, Role role, boolean attached)
      throws InterruptedException {
    for (long waited = 0; waited < PATIENCE.toMillis(); waited += 10) {
      if (registry.find(id).map(session -> session.has(role)).orElse(false) == attached) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "session '"
            + id
            + "' never "
            + (attached ? "got" : "lost")
            + " a "
            + role
            + " — "
            + registry.find(id));
  }

  /** The job polled the way the terminal polls it: over HTTP, until it has an outcome. */
  private static ServerClient.JobStatus awaitOutcome(SessionClient session, String job)
      throws Exception {
    for (long waited = 0; waited < PATIENCE.toMillis(); waited += 10) {
      ServerClient.JobStatus status = session.job(job);
      if (status.outcome() != null) {
        return status;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("job " + job + " never finished");
  }

  /**
   * The same wait, off the store, for the one test that needs the {@code Ending} as an enum rather
   * than as a name on the wire.
   */
  private static Outcome awaitOutcome(JobStore jobs, String job) throws InterruptedException {
    for (long waited = 0; waited < PATIENCE.toMillis(); waited += 10) {
      Optional<Outcome> outcome = jobs.get(job).outcome();
      if (outcome.isPresent()) {
        return outcome.get();
      }
      Thread.sleep(10);
    }
    throw new AssertionError("job " + job + " never finished");
  }

  /**
   * Every event the terminal was handed for {@code job}, up to and including the one that says it
   * ended.
   */
  private static List<JobEvent> awaitEnded(SessionClient session, String job) throws Exception {
    List<JobEvent> seen = new ArrayList<>();
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline) {
      JobEvent event = session.nextEvent(Duration.ofMillis(200));
      if (event == null) {
        continue;
      }
      if (!job.equals(event.job())) {
        continue;
      }
      seen.add(event);
      if (JobEvent.ENDED.equals(event.kind())) {
        assertEquals(0, session.dropped(), "and nothing was dropped on the way");
        return seen;
      }
    }
    throw new AssertionError("job " + job + " never said it ended; heard " + seen);
  }

  /** What the model was handed back for the calls it made. */
  private static List<String> toolResults() {
    return MODEL.lastSeen().stream()
        .filter(message -> message.role() == ChatMessage.Role.TOOL)
        .map(ChatMessage::content)
        .toList();
  }

  private static FileProvider remoteOf(List<FileProvider> all) {
    return all.stream()
        .filter(provider -> "remote".equals(provider.name()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no remote provider in " + all.stream().map(FileProvider::name).toList()));
  }

  private static String json(Path path) {
    return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * A client that answers for its roots and then dies holding a read.
   *
   * <p>It replies to {@code roots} so the router has something to route on, and on the first
   * request that is not {@code roots} it closes the socket without answering — which is the only
   * way to make the server hold an outstanding request on a socket that is going away.
   */
  private static final class Vanishing extends WebSocketListener {

    private final Path root;
    private final ObjectMapper json =
        new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final CountDownLatch roots = new CountDownLatch(1);
    private final CountDownLatch closing = new CountDownLatch(1);

    Vanishing(Path root) {
      this.root = root;
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      FileRequest request;
      try {
        request = json.readValue(text, FileRequest.class);
      } catch (IOException notARequest) {
        throw new UncheckedIOException("the server sent something unreadable", notARequest);
      }
      if (FileRequest.ROOTS.equals(request.op())) {
        try {
          socket.send(
              json.writeValueAsString(FileReply.listed(request.id(), List.of(root.toString()))));
        } catch (IOException notSerialisable) {
          throw new UncheckedIOException("could not answer", notSerialisable);
        }
        roots.countDown();
        return;
      }
      // The whole point of this fixture: the request is received, never
      // answered, and the socket goes while the server is still holding it.
      closing.countDown();
      socket.close(1000, "the laptop closed its lid");
    }

    boolean answeredRoots() throws InterruptedException {
      return roots.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean closedOnARequest() throws InterruptedException {
      return closing.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /**
   * A transport that answers with what the test queued, in order, and keeps what it was last sent
   * so a tool result can be read back.
   */
  private static final class Scripted implements LlmTransport {

    private final List<Completion> steps = new CopyOnWriteArrayList<>();
    private final List<ChatMessage> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger at = new AtomicInteger();

    Scripted then(Completion step) {
      steps.add(step);
      return this;
    }

    void reset() {
      steps.clear();
      seen.clear();
      at.set(0);
    }

    List<ChatMessage> lastSeen() {
      return List.copyOf(seen);
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      seen.clear();
      seen.addAll(messages);
      int index = at.getAndIncrement();
      return index < steps.size()
          ? steps.get(index)
          : new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      // The job runtime streams now. This double answers the same
      // thing either way, on purpose: reconciling two wire formats is the
      // transport's problem and OpenAiTransportTest is where it is
      // proved, so a fake that answered differently down this path would
      // only be testing itself. Delegating to complete(...) keeps every
      // assertion in this class — what a turn was offered, what it sent,
      // what came back — meaning exactly what it meant.
      Completion streamed = complete(wireModel, messages, sampling, tools);
      // Asked after the call, which is where a fake can honestly ask it:
      // the real transport asks once per chunk, and this one has exactly
      // one chunk. See LlmTransport.stream and CallerAbandonedException.
      if (abandoned.getAsBoolean()) {
        throw new CallerAbandonedException(poolName());
      }
      String content = streamed.content();
      if (content != null && !content.isEmpty()) {
        sink.answered(content);
      }
      return streamed;
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException("the job runtime does not embed");
    }

    @Override
    public void close() {}
  }

  private static Completion asking(String id, String name, String arguments) {
    return new Completion(
        "", "tool_calls", TokenUsage.UNKNOWN, List.of(new ToolCall(id, name, arguments)));
  }

  private static Completion done(String text) {
    return new Completion(text, "stop", TokenUsage.UNKNOWN, List.of());
  }

  private static void writeReader(Path dir) {
    try {
      Files.writeString(
          dir.resolve("reader.md"),
          "---\n"
              + "name: reader\n"
              + "description: a fixture agent\n"
              + "model: fast\n"
              + "tools: ["
              + FileTools.READ_NAME
              + "]\n"
              + "scopes: [workspace:read]\n"
              // Exported: this harness submits the agent by name over
              // POST /v1/agents/reader/runs, which that key gates.
              + "exported: true\n"
              + "max-turns: 4\n"
              + "max-model-calls: 8\n"
              + "---\n"
              + "You read files.\n");
    } catch (IOException e) {
      throw new UncheckedIOException("could not write the fixture", e);
    }
  }

  /**
   * The web layer, the two channels and the job machinery — no Postgres, no model endpoint, no
   * agent directory on the classpath.
   *
   * <p>The autoconfigurations are named rather than discovered for the reason {@code
   * FileChannelTest} measured: a hand-built context with a Tomcat factory answers a WebSocket
   * upgrade with a 500, because it is {@code WebSocketServletAutoConfiguration} that registers
   * Tomcat's {@code WsSci}. The MVC and message-converter ones are here because this file, unlike
   * its two neighbours, submits over HTTP rather than calling the store.
   */
  @Configuration
  // `Watchers` travels with the channel: EventChannelConfig requires it
  // rather than inventing one, which is what keeps the frame handler and
  // the channel holding the same instance.
  @Import({FileChannelConfig.class, EventChannelConfig.class, Watchers.class})
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    WebSocketServletAutoConfiguration.class
  })
  static class Wiring {
    @Bean
    io.aeyer.plowshare.server.access.ProjectAuthorization projectAuthorization() {
      var authorization =
          org.mockito.Mockito.mock(io.aeyer.plowshare.server.access.ProjectAuthorization.class);
      org.mockito.Mockito.when(
              authorization.allowed(
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyMap(),
                  org.mockito.ArgumentMatchers.nullable(String.class)))
          .thenReturn(true);
      org.mockito.Mockito.when(
              authorization.filter(
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.any(
                      io.aeyer.plowshare.protocol.frames.Outcome.class),
                  org.mockito.ArgumentMatchers.nullable(String.class)))
          .thenAnswer(call -> call.getArgument(1));
      return authorization;
    }

    @Bean
    io.aeyer.plowshare.server.ws.SocketAuthorization socketAuthorization() {
      return new io.aeyer.plowshare.server.ws.SocketAuthorization(
          org.mockito.Mockito.mock(io.aeyer.plowshare.server.auth.AdminStore.class));
    }

    @Bean
    ProjectStore projects() {
      ProjectStore projects = mock(ProjectStore.class);
      when(projects.find(PROJECT))
          .thenReturn(Optional.of(new ProjectRecord(PROJECT, onTheServer, List.of(), List.of())));
      when(projects.effectiveExclusions(any(ProjectRecord.class)))
          .thenReturn(Collections.emptyList());
      return projects;
    }

    /**
     * Where a declaration's durable half goes, bound the production way — {@code
     * AgentsConfig.projectRoots} is this one line — over the mocked store above, so nothing here
     * writes a row and the socket wiring is still the one production builds.
     */
    @Bean
    io.aeyer.plowshare.server.archive.ProjectMembers projectMembers() {
      return io.aeyer.plowshare.server.ws.TestProjectMembers.allowed();
    }

    @Bean
    ProjectRoots projectRoots(ProjectStore projects) {
      return projects::rootOn;
    }

    /** The production seam, over the production socket wiring. */
    @Bean
    RunProviders runProviders(
        ProjectStore projects,
        FileChannelHandler channel,
        SessionRegistry sessions,
        PresenceRegistry presences) {
      return new AgentsConfig()
          .runProviders(
              projects,
              channel,
              sessions,
              presences,
              ImageStore.NONE,
              UnionRouting.NONE,
              io.aeyer.plowshare.server.ws.TestProjectMembers.allowed());
    }

    @Bean
    JobRuntime jobRuntime(RunProviders files) {
      return new JobRuntime(
          new LlmDispatcher(
              List.of(
                  new LlmPool(
                      "scripted",
                      List.of("model-fast"),
                      Map.of("fast", "model-fast"),
                      4,
                      1,
                      Duration.ofSeconds(5),
                      MODEL)),
              new NoOpTokenLedger()),
          List.of(),
          null,
          files);
    }

    @Bean
    AgentRegistry agentRegistry(JobRuntime runtime) {
      return AgentRegistry.of(definitions, runtime.knownTools());
    }

    /**
     * The production seam over the fixture registry above: no project named in this file has a row
     * {@code projects.exists} would answer true for, so {@code forCaller} always falls back to the
     * boot set -- matching what a raw {@code AgentRegistry} gave this controller before task 10.
     */
    @Bean
    DefinitionResolver definitionResolver(
        AgentRegistry agents,
        ProjectStore projects,
        FileChannelHandler channel,
        SessionRegistry sessions) {
      return new DefinitionResolver(
          agents,
          DataLayout.NONE,
          projects::exists,
          Set.of(),
          Set.of(),
          channel,
          id -> sessions.find(id).filter(live -> live.has(Role.FILE_PROVIDER)).isPresent(),
          DefinitionChecks.NONE);
    }

    @Bean(destroyMethod = "close")
    JobStore jobStore(JobRuntime runtime, EventChannelHandler events) {
      return new JobStore(runtime, events);
    }

    /**
     * No {@code Turn}: this context holds no conversations and every run here is submitted without
     * one. A mock rather than a null so that a body naming a conversation would fail loudly here
     * rather than with a NullPointerException from the controller.
     */
    @Bean
    AgentController agentController(
        JobStore jobs, DefinitionResolver resolver, ProjectStore projects) {
      // The six services this controller now takes, built here rather
      // than scanned because this context wires its collaborators by
      // hand -- one Callers, shared, exactly as the scanned singletons
      // are.
      Turn turns = mock(Turn.class);
      Callers callers =
          new Callers(
              resolver,
              projects,
              turns,
              org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
      return new AgentController(
          jobs,
          resolver,
          projects,
          callers,
          new Runs(callers, jobs, turns),
          new Pictures(ImageStore.NONE),
          new Passes(jobs, mock(Curator.class), new AgentsProperties()),
          new Limits(jobs),
          new Definitions(mock(DefinitionWriter.class), resolver, projects, callers));
    }
  }
}
