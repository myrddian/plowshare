package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.client.files.ChannelClient;
import io.aeyer.plowshare.client.files.ClientEnforcer;
import io.aeyer.plowshare.client.files.Rooting;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.files.AmbiguousPathException;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionChannel;
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
import io.aeyer.plowshare.server.ws.FileChannelConfig;
import io.aeyer.plowshare.server.ws.FileChannelHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

/**
 * The append this whole slice exists for: a run reaching the machine that asked for it.
 *
 * <h2>Three things are measured here and they are three different questions</h2>
 *
 * <ul>
 *   <li><b>the append itself</b> — {@code AgentsConfig.runProviders} adds a {@code RemoteProvider}
 *       when, and only when, the run's session has {@link Role#FILE_PROVIDER} attached. A session
 *       <em>existing</em> is not a provider being attached, and the fixture holds one of each so
 *       the distinction is something the query has to make rather than something the fixture makes
 *       for it;
 *   <li><b>cross-provider ambiguity, now reachable</b> — {@code ProviderRouter} refuses a path two
 *       providers cover instead of tiebreaking it, and with two providers live for the first time
 *       that refusal stops being theoretical. The fixture is two providers that <em>genuinely</em>
 *       serve the path, and the test proves that of them before asking the router;
 *   <li><b>the role gets populated at all</b> — the wiring above is worth nothing if nothing ever
 *       attaches, so the last section drives a real {@link ChannelClient} over a real socket on an
 *       ephemeral port and reads a file off the client's own disk through the production seam.
 * </ul>
 *
 * <h2>Why the file channel's own map is not consulted anywhere here</h2>
 *
 * <p>Because it no longer exists. {@code FileChannelHandler} attaches its connection to the one
 * {@link SessionRegistry} rather than keeping a second map beside it. The cost a second map would
 * carry is that the question is asked from two places that never meet — the wiring asks the
 * registry to decide whether a run gets a provider at all, and {@code ask} asks the handler where
 * to send a request — so a disagreement is either a session with a disk no run can reach or a run
 * holding a provider for a socket that has gone, and both are silent.
 *
 * <p>{@link #the_handler_and_the_registry_never_disagree_about_the_role} compares those two readers
 * at every point of one connection's life, including the displacement where a loser's close
 * callback lands after its replacement is live. <b>Measured:</b> with the close's {@code
 * live.socket != socket} guard removed, so that closing detaches whatever holds the role, it fails
 * — <b>at the {@code remoteOf(...).roots()} assertions and not at {@code assertAgree}, which cannot
 * see it.</b> That helper compares the handler's {@code isConnected} against the registry's {@code
 * has(FILE_PROVIDER)}, and removing the guard empties both together, so the two readers go on
 * agreeing about a role that is now empty. What catches it is asking what the role actually points
 * at. Worth saying because the paragraph above is an argument for comparing the two readers, and
 * the comparison is not what did the work — it also fails along with {@code FileChannelTest}'s
 * {@code the_old_socket_closing_after_a_takeover_does_not_fail_the_new_sessions_requests} and
 * {@code a_reply_on_a_displaced_socket_is_not_applied_to_the_session_that_replaced_it}.
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
class RemoteWiringTest {

  private static final Home PAYMENTS = Home.of("payments");

  private static final List<Grant> READ = List.of(new Grant(Scope.WORKSPACE, Mode.READ));

  /**
   * The window the two reads here ask for. This file is about which provider answers, never about
   * how much of a file comes back.
   */
  private static final Window FIRST = Window.of(0, Window.MAX_WINDOW_LINES);

  /**
   * How long a poll waits before calling the registry's state wrong. Generous: every wait here is
   * for something that happens in milliseconds, and the number is only large enough that a loaded
   * machine does not fail the suite.
   */
  private static final long PATIENCE_MILLIS = 10_000;

  /**
   * How long a state is watched to see whether something takes it away. The other direction from
   * {@link #PATIENCE_MILLIS}, and spent in full by the one test that uses it, so it is the smallest
   * window still worth watching — the same number and the same reasoning as {@code
   * EventChannelTest}'s.
   */
  private static final long SETTLE_MILLIS = 500;

  // --- the append ----------------------------------------------------------

  /**
   * A project rooted by a session with a file provider gets a second filesystem, and the three that
   * root it without one do not.
   *
   * <p><b>The four questions used to be about the session a run was submitted under, and presence
   * moved them one place along</b>: they are now about the session that <em>roots the project</em>,
   * because that is what decides which machine a run reaches. The gate itself is unchanged and is
   * still the one that matters — the listener is the browser of slice 4, and a wiring keyed on a
   * claim being present rather than on a disk being attached would hand a page on the internet a
   * machine. <b>A claim is not a disk.</b>
   *
   * <p>The three "no"s are all ordinary and none of them refuses: what such a run gets is the
   * server's own disk and an {@code AbsentPresence} saying nothing is serving the project, which is
   * what turns a silent smaller capability into something a person can read.
   *
   * <p>Every question is asked from {@code somebody-else}, a session that holds a file provider and
   * roots nothing. That is deliberate: with the run submitted from the rooting session, "it reached
   * the machine that roots it" and "it reached the caller" would be the same observation.
   */
  @Test
  void only_a_session_that_has_a_file_provider_attached_reaches_a_second_machine() {
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("watching", Role.LISTENER, new Object());
    sessions.attach("working", Role.FILE_PROVIDER, new Object());
    sessions.attach("somebody-else", Role.FILE_PROVIDER, new Object());

    assertEquals(
        List.of("local", "remote"),
        names(
            wiring(new Recording(), sessions, rooting("working"))
                .forRun(PAYMENTS, READ, "somebody-else", null)),
        "the session that roots the project lends its disk to a run submitted from"
            + " another machine entirely");
    assertEquals(
        List.of("local"),
        names(
            wiring(new Recording(), sessions, rooting("watching"))
                .forRun(PAYMENTS, READ, "somebody-else", null)),
        "a session holding only a listener has no disk to offer, however loudly it"
            + " claims the project");
    assertEquals(
        List.of("local"),
        names(
            wiring(new Recording(), sessions, rooting("typed-by-hand"))
                .forRun(PAYMENTS, READ, "somebody-else", null)),
        "and a claim under an id nothing ever attached to is an ordinary run, not a" + " refusal");
    assertEquals(
        List.of("local"),
        names(
            wiring(new Recording(), sessions, new PresenceRegistry())
                .forRun(PAYMENTS, READ, "somebody-else", null)),
        "as is a run in a project nothing roots at all");
  }

  /**
   * The remote provider a run is given asks for the session that roots the project.
   *
   * <p>Two sessions hold a provider, so "it picked the only one there was" is not an available
   * explanation, and the run is submitted from the one that does <em>not</em> root it — so "it
   * picked the caller" is not one either. <b>This is what makes {@code SESSION_GONE}'s note true by
   * construction</b>: the socket that can end a run that way is the one attached to the session
   * that holds the project, and never some other client's.
   */
  @Test
  void the_remote_provider_is_bound_to_the_session_that_roots_the_project() {
    Recording channel = new Recording();
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("laptop-a", Role.FILE_PROVIDER, new Object());
    sessions.attach("laptop-b", Role.FILE_PROVIDER, new Object());

    remoteOf(
            wiring(channel, sessions, rooting("laptop-a")).forRun(PAYMENTS, READ, "laptop-b", null))
        .roots();

    assertEquals(
        List.of("laptop-a"),
        channel.asked(),
        "the request went to the session that holds the project and to no other");
  }

  /**
   * A client that goes away makes the next ask smaller, and raises nothing on the way.
   *
   * <p>The spec's explicit case, at the layer that decides it: {@code SESSION_GONE} is produced by
   * a file request that cannot be served, never by a socket closing. A detach is a change in what
   * the next routing call gets asked for, and {@link
   * #a_client_going_away_mid_run_does_not_end_a_run_that_reads_the_servers_own_disk} drives the
   * same event through a whole run.
   */
  @Test
  void a_file_provider_that_detaches_makes_the_next_ask_smaller_and_ends_nothing() {
    Object socket = new Object();
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("working", Role.FILE_PROVIDER, socket);
    RunProviders wiring = wiring(new Recording(), sessions, rooting("working"));
    assertEquals(List.of("local", "remote"), names(wiring.forRun(PAYMENTS, READ, "working", null)));

    assertTrue(sessions.detach("working", Role.FILE_PROVIDER, socket));

    assertEquals(
        List.of("local"),
        names(wiring.forRun(PAYMENTS, READ, "working", null)),
        "the set is re-asked per routing call, so a client that went away during a"
            + " run changes what the rest of that run can see");
  }

  // --- two machines, one path ----------------------------------------------

  /**
   * A path both machines really hold is refused rather than guessed at.
   *
   * <p><b>The fixture is two providers that can both serve it, and that is asserted before the
   * router is asked</b> — each one is made to read the file, and each returns its contents. Without
   * that, "ambiguous" would be a claim about two lists of strings rather than about two
   * filesystems, and a fixture that only looked as though both could serve is exactly the
   * accidentally-correct one.
   *
   * <p>Both sides are the production code: the local one is a {@code LocalProvider} over a real
   * project workspace, and the remote one is the real {@code ClientEnforcer} over a real client
   * {@code Workspace}, reached through a {@link SessionChannel} that hands the request straight to
   * it. What the fixture cannot have is two machines, so one tree stands in for two trees that
   * share a spelling — which is the spec's own worked example, an operator with the same checkout
   * at the same absolute path on the server and on their laptop.
   *
   * <p><b>And the query has something to filter out.</b> The client also holds a root the server
   * does not, so a path under that one is routed to the remote provider alone — the same call, the
   * same fixture, and the local provider filtered away by not covering it.
   */
  @Test
  void a_path_both_machines_hold_is_refused_and_names_them_both() throws Exception {
    Path shared = Files.createDirectory(tmp.resolve("checkout"));
    Path laptopOnly = Files.createDirectory(tmp.resolve("notes"));
    Files.writeString(shared.resolve("A.java"), "class A {}\n");
    Files.writeString(laptopOnly.resolve("B.md"), "# notes\n");

    ProviderRouter router = routerOver(shared, List.of(shared, laptopOnly));
    List<FileProvider> both = twoProviders(shared, List.of(shared, laptopOnly));

    // The premise, measured rather than assumed: each provider really does
    // serve the contested path, on its own, through its own code path.
    // Without the trailing newline the file has: the reply's lines are its
    // only carrier and a terminator is not part of the line it ended.
    assertEquals(
        List.of("class A {}"),
        both.get(0).read(shared.resolve("A.java"), FIRST).lines(),
        "the server's own disk holds it");
    assertEquals(
        List.of("class A {}"),
        both.get(1).read(shared.resolve("A.java"), FIRST).lines(),
        "and so does the machine that asked for the run");

    AmbiguousPathException refused =
        assertThrows(
            AmbiguousPathException.class,
            () -> router.providerFor(PAYMENTS, shared.resolve("A.java")));

    assertTrue(
        refused.getMessage().contains("local") && refused.getMessage().contains("remote"),
        "both are named, since a refusal that says 'ambiguous' without"
            + " saying between what names nothing an operator can go and change — "
            + refused.getMessage());
    assertEquals(
        "remote",
        router.providerFor(PAYMENTS, laptopOnly.resolve("B.md")).name(),
        "and a path only the client holds is routed rather than refused, which is"
            + " what makes the refusal above a decision instead of a shrug");
  }

  /** And a path neither holds is still the plain out-of-scope refusal, with both accounts in it. */
  @Test
  void a_path_neither_machine_holds_is_the_ordinary_refusal() throws Exception {
    Path shared = Files.createDirectory(tmp.resolve("checkout"));
    Path elsewhere = Files.createDirectory(tmp.resolve("elsewhere"));

    WorkspaceRefusedException refused =
        assertThrows(
            WorkspaceRefusedException.class,
            () ->
                routerOver(shared, List.of(shared))
                    .providerFor(PAYMENTS, elsewhere.resolve("C.txt")));

    assertTrue(
        refused.getMessage().contains(shared.toRealPath().toString()),
        "carrying what each one does reach — " + refused.getMessage());
  }

  /**
   * A run that only ever reads the server's own disk survives its client going away.
   *
   * <p>The spec asks for this explicitly, and it is the case a careless wiring would get wrong in
   * the expensive direction: with two providers live, a closing socket must not end a job that
   * never needed the second one. <b>The client goes away between two turns of a real run</b> — the
   * transport detaches it while answering — so the first file call is routed with two providers and
   * the second with one, and the run answers. <b>The "two" is asserted in the body before the run
   * starts</b>, because every file this run reads is on the server's own disk: without that line a
   * wiring that never appended a remote provider would produce the identical ending, and this test
   * would be describing a second provider it never had.
   *
   * <p>{@code ProviderRouter} is what makes this hold rather than luck: a provider that could not
   * be asked is dropped from candidacy as long as somebody who could be asked covers the path, and
   * it is only fatal when nothing that answered does.
   */
  @Test
  void a_client_going_away_mid_run_does_not_end_a_run_that_reads_the_servers_own_disk()
      throws Exception {
    Path shared = Files.createDirectory(tmp.resolve("checkout"));
    Path laptop = Files.createDirectory(tmp.resolve("laptop"));
    Files.writeString(shared.resolve("A.java"), "class A {}\n");
    Object socket = new Object();
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("laptop", Role.FILE_PROVIDER, socket);
    // The client holds a tree of its own, so the run really does have two
    // filesystems for its first call and the second provider is filtered out
    // by not covering the path rather than by not existing.
    RunProviders wiring =
        wiring(enforcing(workspaceOver(laptop)), sessions, rooting("laptop"), shared);
    // The premise, asserted rather than described. Both reads below target
    // the SERVER's tree and the client's only root is `laptop`, so the
    // remote provider never covers the path — which means a runProviders
    // that appended no remote provider at all produces exactly the ANSWERED
    // this test ends on. Without this line the paragraph above claims "two
    // providers, then one" and nothing checks the two.
    assertEquals(
        List.of("local", "remote"),
        names(wiring.forRun(PAYMENTS, READ, "laptop", null)),
        "before the client goes away this run really does have two filesystems");

    Scripted transport =
        new Scripted()
            .then(
                asking(
                    "1",
                    FileTools.READ_NAME,
                    "{\"path\":\"" + json(shared.resolve("A.java")) + "\"}"))
            // Between the two turns the laptop closes its channel.
            .onCall(1, () -> sessions.detach("laptop", Role.FILE_PROVIDER, socket))
            .then(
                asking(
                    "2",
                    FileTools.READ_NAME,
                    "{\"path\":\"" + json(shared.resolve("A.java")) + "\"}"))
            .then(done());
    JobRuntime runtime = new JobRuntime(dispatcherOver(transport), List.of(), null, wiring);
    Path definitions = Files.createDirectory(tmp.resolve("definitions"));
    writeReader(definitions);

    Outcome outcome =
        runtime.run(
            AgentRegistry.of(definitions, runtime.knownTools()).get("reader"),
            "read it twice",
            PAYMENTS,
            Budget.of(100),
            "laptop");

    assertEquals(
        Outcome.Ending.ANSWERED,
        outcome.ending(),
        "a socket closing is not an ending; only a file request that cannot be"
            + " served is — "
            + outcome.detail());
    assertFalse(
        sessions.find("laptop").orElseThrow().has(Role.FILE_PROVIDER),
        "and the client really had gone by the time the run finished");
  }

  // --- the socket, and the role it fills ------------------------------------

  /**
   * Opening the file channel fills the role, and a run under that id then reads a file that never
   * left the client's machine.
   *
   * <p>This is the sentence the commit is named for, driven end to end at this task's layer: a real
   * socket on an ephemeral port, the real {@link ChannelClient} with the real {@code
   * ClientEnforcer} behind it, and the production {@code AgentsConfig.runProviders} asked for the
   * providers. Nothing in slice 3b could reach this state, because nothing attached anything to the
   * {@link Role#FILE_PROVIDER} role.
   */
  @Test
  void opening_the_file_channel_is_what_lets_a_run_read_the_clients_own_disk() throws Exception {
    Path onTheLaptop = Files.createDirectory(tmp.resolve("laptop"));
    Files.writeString(onTheLaptop.resolve("A.java"), "class A {}\n");
    String id = "the-cli";
    assertEquals(
        List.of("local"),
        names(production().forRun(PAYMENTS, READ, id, null)),
        "before the client connects the defined server-owned workspace remains available");

    client(id, workspaceOver(onTheLaptop));

    List<FileProvider> providers = production().forRun(PAYMENTS, READ, id, null);
    assertEquals(List.of("local", "remote"), names(providers));
    assertEquals(
        List.of(onTheLaptop.toRealPath()),
        providers.get(1).roots(),
        "and the second one is the client's own tree, read over the socket");
    assertEquals(
        List.of("class A {}"),
        providers.get(1).read(onTheLaptop.resolve("A.java"), FIRST).lines(),
        "a file this server never had");
  }

  @Test
  void the_live_code_map_reconciles_remote_bytes_over_the_real_file_socket() throws Exception {
    Path laptop = Files.createDirectory(tmp.resolve("code-laptop")).toRealPath();
    Path source =
        Files.writeString(
            laptop.resolve("Remote.java"), "// 😀\r\nclass Remote { void first() {} }\r\n");
    String id = "code-map-cli";
    var socket = client(id, workspaceOver(laptop));
    var provider = new io.aeyer.plowshare.server.files.RemoteProvider(handler, id, READ);
    var map =
        new io.aeyer.plowshare.server.files.WorkspaceCodeMap(
            new ProviderRouter(home -> List.of(provider)), () -> false);
    var tool = new CodeMapTool(map, new FileTools.Reads(map));
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var outline =
        json.readTree(
            tool.run(
                json.writeValueAsString(Map.of("operation", "outline", "path", source.toString())),
                PAYMENTS));
    assertEquals("observed", outline.path("state").asText());
    var declaration = outline.path("results").get(1);
    assertEquals("first", declaration.path("name").asText());
    String request =
        json.writeValueAsString(
            Map.of(
                "operation",
                "read",
                "path",
                source.toString(),
                "source_hash",
                declaration.path("source_hash").asText(),
                "offset",
                declaration.path("start_offset").asInt(),
                "limit",
                declaration.path("end_offset").asInt() - declaration.path("start_offset").asInt()));
    assertEquals(
        "void first() {}", json.readTree(tool.run(request, PAYMENTS)).path("text").asText());
    Files.writeString(source, "class Remote { void changed() {} }");
    assertTrue(tool.run(request, PAYMENTS).contains("code changed"));
    var symbols =
        json.readTree(tool.run("{\"operation\":\"symbols\",\"query\":\"changed\"}", PAYMENTS));
    assertEquals(1, symbols.path("total").asInt());
    socket.close();
    awaitNoFileProvider(id);
    var gone = json.readTree(tool.run("{\"operation\":\"files\"}", PAYMENTS));
    assertEquals("partial", gone.path("state").asText());
    assertEquals(0, gone.path("total").asInt());
  }

  @Test
  void background_code_tracking_hashes_the_client_disk_over_the_existing_socket() throws Exception {
    Path laptop = Files.createDirectory(tmp.resolve("monitored-laptop")).toRealPath();
    Path source = Files.writeString(laptop.resolve("Remote.java"), "class Before {}");
    String id = "monitored-cli";
    var socket = client(id, workspaceOver(laptop));
    var provider = new io.aeyer.plowshare.server.files.RemoteProvider(handler, id, READ);
    var store = mock(io.aeyer.plowshare.server.files.CodeWorkspaceStore.class);
    var scope =
        new io.aeyer.plowshare.server.files.CodeWorkspaceStore.Scope(
            PAYMENTS, "alice", "coder", id);
    var ticket = new io.aeyer.plowshare.server.files.CodeMapObservations.Ticket(scope.id(), 1);
    when(store.claim())
        .thenReturn(
            Optional.of(
                new io.aeyer.plowshare.server.files.CodeWorkspaceStore.Scan(ticket, scope, "**")));
    var observed =
        new java.util.concurrent.atomic.AtomicReference<
            io.aeyer.plowshare.server.files.WorkspaceCodeMap.View>();
    org.mockito.Mockito.doAnswer(
            call -> {
              observed.set(call.getArgument(1));
              return true;
            })
        .when(store)
        .publish(any(), any());
    try (var monitor =
        new io.aeyer.plowshare.server.files.CodeWorkspaceMonitor(
            store, ignored -> new ProviderRouter(home -> List.of(provider)), true)) {
      assertTrue(monitor.pollOnce());
      assertEquals("observed", observed.get().state());
      String before = observed.get().files().getFirst().fingerprint().sha256();
      Files.writeString(source, "class BackgroundChange {}");
      assertTrue(monitor.pollOnce());
      assertFalse(before.equals(observed.get().files().getFirst().fingerprint().sha256()));
      assertEquals("not_requested", observed.get().files().getFirst().outline().status());
      socket.close();
      awaitNoFileProvider(id);
      assertTrue(monitor.pollOnce());
      assertEquals("partial", observed.get().state());
      assertTrue(observed.get().files().isEmpty());
    }
  }

  /** And closing it takes the second filesystem away again. */
  @Test
  void closing_the_file_channel_takes_the_second_machine_away() throws Exception {
    String id = "leaving";
    ChannelClient client = client(id, workspaceOver(tmp));
    assertEquals(List.of("local", "remote"), names(production().forRun(PAYMENTS, READ, id, null)));

    client.close();
    awaitNoFileProvider(id);

    assertEquals(
        List.of("local"),
        names(production().forRun(PAYMENTS, READ, id, null)),
        "the remote presence goes with the socket; the server-owned workspace stays available");
  }

  /**
   * The handler and the registry are one store, watched through a displacement.
   *
   * <p><b>The instrument for the decision this task made</b>: the handler attaches to the registry
   * instead of keeping a map of its own. Two stores would be two answers to "does this session have
   * a disk" — the wiring reads one and {@code ask} reads the other — and every way they could
   * disagree is silent. So the two are compared at every point of a connection's life, including
   * the one where a second socket arrives under a live id and the loser's close callback lands
   * afterwards.
   *
   * <p>Which client holds the role is asserted <em>behaviourally</em>, by whose tree comes back:
   * object identity would only say the role changed, and the failure worth catching is the role
   * ending up pointed at the socket that was displaced.
   */
  @Test
  void the_handler_and_the_registry_never_disagree_about_the_role() throws Exception {
    Path first = Files.createDirectory(tmp.resolve("first"));
    Path second = Files.createDirectory(tmp.resolve("second"));
    String id = "crowd";
    assertAgree(id);

    ChannelClient earlier = client(id, workspaceOver(first));
    assertAgree(id);
    assertEquals(
        List.of(first.toRealPath()),
        remoteOf(production().forRun(PAYMENTS, READ, id, null)).roots());

    client(id, workspaceOver(second));
    awaitRoots(id, second.toRealPath());
    assertAgree(id);

    // The displaced client's own close callback arrives after it has already
    // been replaced, and must take nothing away from its replacement. Watched
    // for a window rather than glanced at once: the callback is the
    // container's and arrives whenever it arrives, and a role emptied and
    // refilled between two glances is a bug this would otherwise report as
    // fine.
    earlier.close();
    RunProviders wiring = production();
    for (long waited = 0; waited < SETTLE_MILLIS; waited += 10) {
      assertAgree(id);
      assertEquals(
          List.of(second.toRealPath()),
          remoteOf(wiring.forRun(PAYMENTS, READ, id, null)).roots(),
          "the newer connection is still the session's file provider after " + waited + "ms");
      Thread.sleep(10);
    }
  }

  /** Both stores say the same thing about this id, whatever that thing is. */
  private static void assertAgree(String id) {
    boolean served = handler.isConnected(id);
    boolean routed =
        registry.find(id).map(session -> session.has(Role.FILE_PROVIDER)).orElse(false);
    assertEquals(
        routed,
        served,
        "the registry says "
            + routed
            + " and the handler says "
            + served
            + " about session '"
            + id
            + "'; the first is what a run is routed on"
            + " and the second is what a file request is served on, and a"
            + " disagreement is silent in both directions");
  }

  // --- fixtures -------------------------------------------------------------

  @TempDir Path tmp;

  private static ConfigurableApplicationContext context;

  /**
   * The one {@code FileChannelConfig} built, so this file drives the handler the production wiring
   * registers rather than a copy of it.
   */
  private static FileChannelHandler handler;

  /** The one {@code EventChannelConfig} built, for the same reason. */
  private static SessionRegistry registry;

  /**
   * The context's own presence registry, so {@link #production()} reads what the socket declared
   * rather than what this file arranged.
   */
  private static PresenceRegistry presences;

  private static int port;
  private static OkHttpClient http;

  private final List<ChannelClient> opened = new ArrayList<>();

  @BeforeAll
  static void startTheServer() {
    context =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            // A command-line argument and not properties(...): FileChannelTest
            // measured that the latter supplies default properties and loses
            // to application.yml, which names a fixed port.
            .run("--server.port=0");
    port = ((WebServerApplicationContext) context).getWebServer().getPort();
    handler = context.getBean(FileChannelHandler.class);
    registry = context.getBean(SessionRegistry.class);
    presences = context.getBean(PresenceRegistry.class);
    http = new OkHttpClient.Builder().build();
  }

  @AfterAll
  static void stopTheServer() {
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
    context.close();
  }

  @AfterEach
  void closeWhatThisTestOpened() throws InterruptedException {
    for (ChannelClient open : opened) {
      open.close();
    }
    opened.clear();
    // All socket fixtures root payments. Wait for their close callbacks before
    // another test claims that project under a fresh session id.
    for (long waited = 0; waited < PATIENCE_MILLIS; waited += 10) {
      if (presences.serving("payments").isEmpty()) {
        return;
      }
      Thread.sleep(10);
    }
    assertTrue(
        presences.serving("payments").isEmpty(),
        "closed fixture sockets must release their project before the next test");
  }

  /**
   * The production seam over the production socket wiring, reading the context's own presence
   * registry — so what the socket declared is what the seam sees, rather than what this file
   * arranged.
   */
  private RunProviders production() {
    return wiring(handler, registry, presences, tmp);
  }

  /**
   * The same, for the questions that are about the role gate and the presence rather than about
   * anything on the server's own disk.
   *
   * <p><b>{@code tmp} and not the {@code /nowhere-this-test-reads} this used to name.</b> That
   * literal was chosen because these tests never read through the local provider — and it stopped
   * being harmless when {@code AgentsConfig.serverCannotServe} arrived: a project whose workspace
   * is not a directory on this machine is one the server does not root, so the seam leaves the
   * local provider out of the set entirely and the names come back {@code [presence]} rather than
   * {@code [local, presence]}. The fixture was asserting a provider was present for a workspace
   * nobody could have served, which is exactly the state the new rule exists to notice.
   */
  private RunProviders wiring(
      SessionChannel channel, SessionRegistry sessions, PresenceRegistry presences) {
    return wiring(channel, sessions, presences, tmp);
  }

  /**
   * A registry in which each of {@code sessions} roots {@link #PAYMENTS}.
   *
   * <p>Every test in this file submits a run in {@code payments}, and under presence that is a run
   * for <b>the machine that roots payments</b> — so a fixture that attached a file provider and
   * stopped would be measuring a session that lends files and roots nothing, which is a different
   * question and one {@code PresenceRoutingTest} asks. Declaring here is what keeps these tests
   * pointed at what they were written for: the role gate, the ambiguity refusal, and the socket
   * that fills the role.
   *
   * <p>The machine name is the session id, which is enough: nothing in this file reads a canonical
   * name, and two sessions in one test are two machines because they are two claims.
   */
  private static PresenceRegistry rooting(String... sessions) {
    PresenceRegistry presences = new PresenceRegistry();
    for (String session : sessions) {
      presences.declare(new Presence(session, session, "/srv/payments", "payments"));
    }
    return presences;
  }

  /**
   * The production method, with a project store that answers for one workspace.
   *
   * <p>A mocked store rather than a database: what is under test is which providers the seam builds
   * and how a router chooses between them, and {@code LocalProviderTest} is where {@code
   * ProjectStore}'s own rules are driven against a real one. The workspace it names is a real
   * directory, so the {@code LocalProvider} built over it really lists and really reads.
   */
  private static RunProviders wiring(
      SessionChannel channel,
      SessionRegistry sessions,
      PresenceRegistry presences,
      Path workspace) {
    ProjectStore projects = mock(ProjectStore.class);
    ProjectRecord row = new ProjectRecord("payments", workspace, List.of(), List.of());
    when(projects.find("payments")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(any(ProjectRecord.class))).thenReturn(List.of());
    return new AgentsConfig()
        .runProviders(
            projects,
            channel,
            sessions,
            presences,
            ImageStore.NONE,
            UnionRouting.NONE,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ProjectMembers.class));
  }

  /** A router over the seam, exactly as {@code JobRuntime} builds one per run. */
  private static ProviderRouter routerOver(Path serverWorkspace, List<Path> onTheClient) {
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("laptop", Role.FILE_PROVIDER, new Object());
    RunProviders wiring =
        wiring(enforcing(workspaceOver(onTheClient)), sessions, rooting("laptop"), serverWorkspace);
    return new ProviderRouter(home -> wiring.forRun(home, READ, "laptop", null));
  }

  private static List<FileProvider> twoProviders(Path serverWorkspace, List<Path> onTheClient) {
    SessionRegistry sessions = new SessionRegistry();
    sessions.attach("laptop", Role.FILE_PROVIDER, new Object());
    return wiring(
            enforcing(workspaceOver(onTheClient)), sessions, rooting("laptop"), serverWorkspace)
        .forRun(PAYMENTS, READ, "laptop", null);
  }

  /**
   * The real client-side enforcer, reached without a socket.
   *
   * <p>{@code FileChannelTest} is where the wire itself is measured. What this file needs from the
   * client is that the answers are the ones the production client would give — its roots, its
   * containment, its refusals — so the enforcer is real and only the transport is short-circuited.
   */
  private static SessionChannel enforcing(Workspace workspace) {
    ClientEnforcer enforcer = new ClientEnforcer(workspace);
    return (session, request) -> enforcer.answer(request);
  }

  private static Workspace workspaceOver(Path... roots) {
    return workspaceOver(List.of(roots));
  }

  private static Workspace workspaceOver(List<Path> roots) {
    Workspace workspace = new Workspace();
    workspace.set(roots);
    return workspace;
  }

  /**
   * A real {@link ChannelClient} on the ephemeral port, closed after the test.
   *
   * <p><b>Waits for THIS client's attachment and not for the role being occupied</b>, and the
   * difference is the whole of a flake this file produced three times in fifty runs of the class on
   * its own. {@code ChannelClient.open} is fire-and-forget by design — its own javadoc says so — so
   * everything after it is asynchronous. Waiting on {@code Session.has(FILE_PROVIDER)} answers "is
   * anybody attached", which is already true when a SECOND client is opened under a live id: the
   * poll returned on its first pass, before the displacing socket had upgraded, and the caller's
   * next wire call went out over the socket on its way out. When {@code afterConnectionEstablished}
   * then ran, {@code Live.gone} failed that in-flight request with "the session 'crowd' was
   * replaced by a second connection under the same name while this was waiting" — a real production
   * sentence about a race this fixture created and then lost.
   *
   * <p>So the wait is on identity: the attachment this call is waiting for is the one that is
   * <em>not</em> the one that was there before it. The displacement is complete before any wire
   * call can be made, so no request can be outstanding across it, and the collision is impossible
   * rather than rare. It is {@code EventChannelTest.awaitListenerOtherThan}'s shape, ported: that
   * file solved this for its own role and neither this one nor {@code FileChannelTest} took the
   * correction.
   *
   * <p>{@code Object.class} rather than the attachment's real type because that type is {@code
   * FileChannelHandler.Live}, which is private. Identity is all this needs.
   */
  private ChannelClient client(String id, Workspace workspace) throws Exception {
    Object incumbent = fileProvider(id).orElse(null);
    ChannelClient client =
        new ChannelClient(
            "http://localhost:" + port,
            id,
            new ClientEnforcer(workspace),
            null,
            // THE PRODUCTION DECLARATION, over the production socket. Every
            // run in this file is a run in `payments`, so a client that lent
            // its files and rooted nothing would be reached by none of them —
            // which is the rule rather than a fixture problem. The root is
            // the first directory it lends, exactly as `cli.Plowshare`
            // composes one.
            Rooting.of(workspace.roots().get(0), "payments"));
    opened.add(client);
    client.open();
    awaitFileProviderOtherThan(id, incumbent);
    return client;
  }

  /** Whatever is in the file-provider role right now, by identity. */
  private static Optional<Object> fileProvider(String id) {
    return registry.find(id).flatMap(session -> session.attached(Role.FILE_PROVIDER, Object.class));
  }

  /**
   * The upgrade is asynchronous on both sides, and a second socket under a live id leaves the role
   * occupied throughout — so the thing to wait for is an attachment that is not {@code incumbent},
   * which is null for the first client under an id and the displaced one for any after it.
   */
  private static void awaitFileProviderOtherThan(String id, Object incumbent)
      throws InterruptedException {
    for (long waited = 0; waited < PATIENCE_MILLIS; waited += 10) {
      Optional<Object> now = fileProvider(id);
      if (now.isPresent() && now.get() != incumbent) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "session '"
            + id
            + "' never took "
            + (incumbent == null ? "a file provider" : "a SECOND file provider")
            + " — "
            + registry.find(id));
  }

  /**
   * The close is asynchronous too, and in the other direction. No identity here, and it is not the
   * omission above: this waits for the role to be EMPTY, which nothing else can satisfy on the
   * caller's behalf.
   */
  private static void awaitNoFileProvider(String id) throws InterruptedException {
    for (long waited = 0; waited < PATIENCE_MILLIS; waited += 10) {
      if (fileProvider(id).isEmpty()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "session '" + id + "' never lost its file provider — " + registry.find(id));
  }

  /**
   * The takeover observed rather than slept through: the role holds the second client when the tree
   * that comes back is the second client's.
   */
  private void awaitRoots(String id, Path expected) throws InterruptedException {
    RunProviders wiring = production();
    for (long waited = 0; waited < PATIENCE_MILLIS; waited += 10) {
      if (List.of(expected).equals(remoteOf(wiring.forRun(PAYMENTS, READ, id, null)).roots())) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("session '" + id + "' never advertised " + expected);
  }

  private static FileProvider remoteOf(List<FileProvider> providers) {
    return providers.stream()
        .filter(provider -> "remote".equals(provider.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no remote provider in " + names(providers)));
  }

  private static List<String> names(List<FileProvider> providers) {
    return providers.stream().map(FileProvider::name).toList();
  }

  private static String json(Path path) {
    return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * A channel that records which session each request named and answers with one root, so a
   * provider built over it can be made to speak.
   */
  private static final class Recording implements SessionChannel {

    private final List<String> sessions = Collections.synchronizedList(new ArrayList<>());

    @Override
    public FileReply ask(String session, FileRequest request) {
      sessions.add(session);
      return FileReply.listed(request.id(), List.of("/laptop/repo"));
    }

    List<String> asked() {
      synchronized (sessions) {
        return List.copyOf(sessions);
      }
    }
  }

  /**
   * A transport that answers with what the test queued, in order, and can run one side effect
   * between two of them.
   */
  private static final class Scripted implements LlmTransport {

    private final List<Completion> steps = new ArrayList<>();
    private final Map<Integer, Runnable> before = new HashMap<>();
    private final AtomicInteger at = new AtomicInteger();

    Scripted then(Completion step) {
      steps.add(step);
      return this;
    }

    /** Run {@code effect} just before the call at {@code index} is answered. */
    Scripted onCall(int index, Runnable effect) {
      before.put(index, effect);
      return this;
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      int index = at.getAndIncrement();
      Runnable effect = before.get(index);
      if (effect != null) {
        effect.run();
      }
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

  private static LlmDispatcher dispatcherOver(LlmTransport transport) {
    return new LlmDispatcher(
        List.of(
            new LlmPool(
                "scripted",
                List.of("model-fast"),
                Map.of("fast", "model-fast"),
                4,
                1,
                Duration.ofSeconds(5),
                transport)),
        new NoOpTokenLedger());
  }

  private static Completion asking(String id, String name, String arguments) {
    return new Completion(
        "", "tool_calls", TokenUsage.UNKNOWN, List.of(new ToolCall(id, name, arguments)));
  }

  private static Completion done() {
    return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
  }

  /**
   * A definition written the way an operator writes one, in a directory of its own — {@code
   * AgentRegistry.load} validates a whole directory at once.
   */
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
              + "max-turns: 4\n"
              + "max-model-calls: 8\n"
              + "---\n"
              + "You read files.\n");
    } catch (IOException e) {
      throw new UncheckedIOException("could not write the fixture", e);
    }
  }

  /**
   * The web layer and nothing else — no Postgres, no model, no agent registry.
   *
   * <p>Three autoconfigurations by name rather than {@code @SpringBootTest}, for the reason {@code
   * FileChannelTest} measured: a hand-built context with a Tomcat factory answers the upgrade with
   * a 500, because it is {@code WebSocketServletAutoConfiguration} that registers Tomcat's {@code
   * WsSci}.
   *
   * <p>Both channel configurations are imported and nothing else is defined here, so the handler,
   * the registry and the path under test are the ones production builds. {@code EventChannelConfig}
   * is what defines the registry, and {@code FileChannelConfig} is now what injects it.
   */
  @Configuration
  // `Watchers` travels with the channel: EventChannelConfig requires it
  // rather than inventing one, which is what keeps the frame handler and
  // the channel holding the same instance.
  @Import({FileChannelConfig.class, EventChannelConfig.class, Watchers.class})
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebSocketServletAutoConfiguration.class
  })
  static class Wiring {
    @Bean
    io.aeyer.plowshare.server.ws.SocketAuthorization socketAuthorization() {
      return new io.aeyer.plowshare.server.ws.SocketAuthorization(
          mock(io.aeyer.plowshare.server.auth.AdminStore.class));
    }

    @Bean
    io.aeyer.plowshare.server.access.ProjectAuthorization projectAuthorization() {
      var authorization = mock(io.aeyer.plowshare.server.access.ProjectAuthorization.class);
      when(authorization.allowed(anyString(), anyMap(), nullable(String.class))).thenReturn(true);
      org.mockito.Mockito.when(
              authorization.filter(
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.any(
                      io.aeyer.plowshare.protocol.frames.Outcome.class),
                  org.mockito.ArgumentMatchers.nullable(String.class)))
          .thenAnswer(call -> call.getArgument(1));
      return authorization;
    }

    /**
     * Where a declaration's durable half would go, and here it goes nowhere.
     *
     * <p>{@code FileChannelConfig} requires the seam, because a null one would silently stop every
     * presence being written down. This context has no archive at all — no Postgres, by design —
     * and nothing in this file declares a presence on the query string, so a recorder would record
     * nothing and a mock would assert nothing. What the bean is for is letting the production
     * socket wiring start.
     */
    @Bean
    io.aeyer.plowshare.server.archive.ProjectMembers projectMembers() {
      return io.aeyer.plowshare.server.ws.TestProjectMembers.allowed();
    }

    @Bean
    ProjectRoots projectRoots() {
      return (project, machine, root, handle) -> {};
    }
  }
}
