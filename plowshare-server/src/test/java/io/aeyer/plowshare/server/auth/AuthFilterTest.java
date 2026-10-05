package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.ws.EventChannelConfig;
import io.aeyer.plowshare.server.ws.EventChannelHandler;
import io.aeyer.plowshare.server.ws.FileChannelConfig;
import io.aeyer.plowshare.server.ws.FileChannelHandler;
import jakarta.servlet.FilterChain;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The commit where this server refuses something, measured over a real socket.
 *
 * <h2>The claim this file exists to prove</h2>
 *
 * <p>The slice design says a WebSocket upgrade is an ordinary HTTP request before it is a socket,
 * so one servlet {@link jakarta.servlet.Filter} covers {@code /v1/events}, {@code /v1/files} and
 * the REST API and there is no second place a path could be left ungated. <b>That was an unverified
 * claim about Tomcat and Spring, and the recurring defect in this slice is exactly that shape</b> —
 * a javadoc explaining a mechanism its author never ran, and {@link AuthFilter} itself has since
 * supplied two more of them, both now measured and rewritten and both now held by a test in this
 * file. So {@link #an_unauthenticated_websocket_upgrade_is_refused_before_the_handler()} dials a
 * real socket at the real path against a real Tomcat and asserts the 401; {@link
 * #an_authenticated_websocket_upgrade_reaches_the_handler()} is its other half, because a socket
 * nobody can ever open is the same green.
 *
 * <p>What was observed, and it is the whole of why the design stands: the upgrade is answered
 * {@code HTTP/1.1 401} with the JSON body, the socket never opens, and {@link SessionRegistry}
 * never sees the session id — the handler is not entered at all.
 *
 * <h2>Why the context is hand-built</h2>
 *
 * <p>{@code EventChannelTest}'s three autoconfigurations by name, plus {@code
 * WebMvcAutoConfiguration} because this file needs handler mappings and a static resource where
 * that one needs neither. The subject is a filter's interaction with the dispatcher and the
 * WebSocket upgrade, and a {@code @SpringBootTest} would drag Postgres and the agent registry in to
 * measure it.
 *
 * <p>{@code AuthConfig} is imported rather than reproduced, so what is under test is the
 * registration production uses — the order, the {@code /*} pattern and the single instance — and
 * not a filter this file wired up its own way.
 *
 * <h2>The standing rule about tokens in assertions</h2>
 *
 * <p>{@code TokensTest}'s class javadoc carries it: {@code assertTrue}, {@code assertFalse} and
 * {@code assertThrows} may take a token as an operand; {@code assertEquals} and its relatives may
 * never, because each interpolates its operands into the failure it prints and each fails exactly
 * when the printed value is the live one. Every assertion here that touches a credential is
 * therefore a boolean, and no message in this file interpolates one.
 */
class AuthFilterTest {

  /**
   * How long a socket assertion waits before calling the server's answer wrong. Generous for the
   * same reason {@code EventChannelTest}'s is: every wait here is for something that happens in
   * milliseconds.
   */
  private static final long PATIENCE_MILLIS = 10_000;

  private static ConfigurableApplicationContext context;
  private static SessionRegistry registry;
  private static TokenStore tokens;
  private static Probes probes;
  private static int port;
  private static OkHttpClient http;

  private final List<WebSocket> opened = new ArrayList<>();

  @BeforeAll
  static void startTheServer() {
    context =
        new SpringApplicationBuilder(Wiring.class)
            .web(WebApplicationType.SERVLET)
            .run("--server.port=0", "--server.address=127.0.0.1");
    port = ((WebServerApplicationContext) context).getWebServer().getPort();
    registry = context.getBean(SessionRegistry.class);
    tokens = context.getBean(TokenStore.class);
    probes = context.getBean(Probes.class);
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
    for (WebSocket socket : opened) {
      socket.close(1000, null);
    }
    opened.clear();
    probes.reset();
  }

  // --- the REST surface ----------------------------------------------------

  @Test
  void an_unauthenticated_request_is_refused_and_the_controller_is_never_entered()
      throws IOException {
    try (Response refused = get("/v1/jobs", null)) {
      assertEquals(401, refused.code(), "an anonymous caller does not read this server");
      assertEquals("application/json", contentType(refused));
      assertEquals(
          "{\"error\":\"unauthenticated\"}",
          body(refused),
          "one sentence for every way of failing: a 401 that distinguished expired"
              + " from unknown would tell a caller whether a guess was ever real");
    }
    // THE POINT OF THE FILTER, and the thing a controller-level check could
    // not give: the handler did not run, so nothing behind it had to be
    // trusted to refuse.
    assertEquals(
        0,
        probes.gated(),
        "the request reached the controller, so the filter is not what refused it");
  }

  @Test
  void a_valid_bearer_header_is_accepted() throws IOException {
    String access = tokens.issuePair().access();

    try (Response allowed =
        get("/v1/jobs", request -> request.header("Authorization", "Bearer " + access))) {
      assertEquals(200, allowed.code(), "the CLI's transport is a header and it works");
    }
    assertEquals(1, probes.gated(), "and the request reached the controller");
  }

  @Test
  void a_valid_access_cookie_is_accepted() throws IOException {
    String access = tokens.issuePair().access();

    // The browser's transport, and the only one it has: new WebSocket(url)
    // takes a URL and a subprotocol list, so a page cannot present a header
    // on the upgrade. Same store, same call, different envelope.
    try (Response allowed =
        get("/v1/jobs", request -> request.header("Cookie", "ps_access=" + access))) {
      assertEquals(200, allowed.code());
    }
    assertEquals(1, probes.gated());
  }

  /**
   * A duplicate {@code ps_access} does not lock the console out.
   *
   * <p><b>Cookies are not port-scoped.</b> Anything else served from this host — a sibling
   * application on another localhost port — can set a second {@code ps_access} in the operator's
   * browser, and the browser then sends both on every request to this server in an order this
   * server does not choose. Returning on the first match made that a denial of service: measured
   * before the fix, the stray-first spelling was refused 401 and the stray-last spelling was
   * accepted 200, so a console holding a valid session would be refused with it attached and
   * nothing would say why.
   *
   * <p>Both orderings are asserted, because one of them passed under the defect. The third case is
   * the other half: collecting rather than returning must not turn a request carrying nothing valid
   * into an accepted one.
   */
  @Test
  void a_duplicate_access_cookie_does_not_lock_a_valid_session_out() throws IOException {
    String access = tokens.issuePair().access();
    String stray = "0".repeat(48);

    try (Response strayFirst =
        get(
            "/v1/jobs",
            request -> request.header("Cookie", "ps_access=" + stray + "; ps_access=" + access))) {
      assertEquals(
          200,
          strayFirst.code(),
          "a stray ps_access sorted ahead of the real one refused the request, so"
              + " anyone able to set a cookie on this host can lock the console"
              + " out of a session it still holds");
    }
    try (Response strayLast =
        get(
            "/v1/jobs",
            request -> request.header("Cookie", "ps_access=" + access + "; ps_access=" + stray))) {
      assertEquals(
          200,
          strayLast.code(),
          "a stray ps_access sorted behind the real one refused the request");
    }
    try (Response neither =
        get(
            "/v1/jobs",
            request -> request.header("Cookie", "ps_access=" + stray + "; ps_access=" + stray))) {
      assertEquals(
          401,
          neither.code(),
          "two values, neither of them a token, were accepted — collecting every"
              + " cookie has turned into accepting one");
    }
    assertEquals(
        2,
        probes.gated(),
        "the controller was entered a number of times that is not the two requests"
            + " that carried a valid credential");
  }

  /**
   * Only a {@code Bearer} header suppresses the cookie.
   *
   * <p>The class javadoc claimed for one commit that a request carrying any {@code Authorization}
   * header does not fall back to the cookie. Measured false, and the behaviour is the one worth
   * keeping: a proxy that inserts {@code Authorization: Basic} must not lock a browser out of its
   * own console, because a header in a scheme this server does not understand is not a credential
   * it was offered. A {@code Bearer} header is different — that is a caller naming the credential
   * it means, and falling back from it would let a stale cookie decide a request the CLI thought it
   * had authenticated.
   */
  @Test
  void only_a_bearer_authorization_header_suppresses_the_cookie() throws IOException {
    String access = tokens.issuePair().access();

    try (Response allowed =
        get(
            "/v1/jobs",
            request ->
                request
                    .header("Authorization", "Basic YWJjOmRlZg==")
                    .header("Cookie", "ps_access=" + access))) {
      assertEquals(
          200,
          allowed.code(),
          "an Authorization header in a scheme this server does not read was treated"
              + " as a credential, so a proxy inserting one locks the console out");
    }
    try (Response refused =
        get(
            "/v1/jobs",
            request ->
                request
                    .header("Authorization", "Bearer " + "0".repeat(48))
                    .header("Cookie", "ps_access=" + access))) {
      assertEquals(
          401,
          refused.code(),
          "a Bearer header that does not authenticate fell back to the cookie, so the"
              + " request the CLI believed it authorised was decided by a"
              + " credential it did not present");
    }
    assertEquals(1, probes.gated());
  }

  @Test
  void a_credential_that_is_not_one_is_refused() throws IOException {
    try (Response refused =
        get("/v1/jobs", request -> request.header("Authorization", "Bearer " + "0".repeat(48)))) {
      assertEquals(401, refused.code());
    }
    try (Response refused =
        get("/v1/jobs", request -> request.header("Cookie", "ps_access=" + "0".repeat(48)))) {
      assertEquals(401, refused.code());
    }
    assertEquals(0, probes.gated());
  }

  // --- the open paths ------------------------------------------------------

  /**
   * A gate that refused the way through it could never be passed.
   *
   * <p>This test asserted a <b>404</b> until task 8, because nothing mapped those two paths and a
   * 404 is the dispatcher's answer — so reaching one was proof the filter had let the request
   * through. {@link AuthController} now answers both, and the proof moves to the <b>body</b>: the
   * filter's refusal carries {@code {"error":"unauthenticated"}} and the controller's carries
   * nothing at all, so an empty-bodied 401 is a 401 the filter did not write. The claim the test
   * holds is unchanged.
   */
  @Test
  void the_door_is_reachable_without_a_credential() throws IOException {
    try (Response auth = postJson("/v1/auth", "{\"token\":\"" + "0".repeat(48) + "\"}")) {
      assertEquals(
          401,
          auth.code(),
          "the door answered something other than a refusal to a" + " credential that is not one");
      assertEquals(
          "",
          body(auth),
          "/v1/auth was refused by the FILTER rather than by the controller — its body"
              + " is the filter's sentence — so the filter is refusing the only path"
              + " that could ever hand out a credential and nothing can"
              + " authenticate");
    }
    try (Response refresh = postJson("/v1/auth/refresh", "")) {
      assertEquals(401, refresh.code());
      assertEquals(
          "",
          body(refresh),
          "/v1/auth/refresh was refused by the filter, so a console can be logged in and"
              + " never renewed");
    }
  }

  /**
   * {@code GET /v1/auth/session} is gated — no entry in {@link AuthFilter#OPEN} — so an anonymous
   * caller is refused by this filter before {@code AuthController#session} is ever entered. The
   * authenticated cases, and the assertion that the probe changes nothing, live in {@code
   * AuthControllerTest} against the same real {@link AuthController}; this is the one case that
   * belongs here instead, because it is this filter's refusal being measured, not the controller's.
   */
  @Test
  void an_unauthenticated_probe_of_the_session_endpoint_is_refused_by_the_filter()
      throws IOException {
    try (Response refused = get("/v1/auth/session", null)) {
      assertEquals(
          401,
          refused.code(),
          "GET /v1/auth/session answered an anonymous caller with something other than"
              + " the filter's refusal, so either it is open when it must be gated or"
              + " the controller ran with no credential to read");
      assertEquals(
          "{\"error\":\"unauthenticated\"}",
          body(refused),
          "the body was not the filter's own sentence, so this request reached the"
              + " controller rather than being refused before it");
    }
  }

  @Test
  void the_console_s_own_assets_are_reachable_without_a_credential() throws IOException {
    // The same bytes for every install, and no data among them. Every byte
    // of data the console displays comes from a /v1 path, which is behind
    // the filter — so serving the shell to an anonymous caller costs a page
    // that can render nothing.
    try (Response asset = get("/console-probe.txt", null)) {
      assertEquals(
          200,
          asset.code(),
          "a static asset was refused, so the console cannot load at all and the"
              + " login page is behind the login");
    }
  }

  // --- the WebSocket upgrade, which is what the design rests on ------------

  /**
   * The claim, measured: the filter runs on the upgrade.
   *
   * <p>If this fails, the design's "one filter covers both channels" is false and the fallback is a
   * different design — a handshake interceptor, or auth in {@code afterConnectionEstablished} —
   * which needs approval rather than a patch.
   */
  @Test
  void an_unauthenticated_websocket_upgrade_is_refused_before_the_handler()
      throws InterruptedException {
    String id = "unauthenticated-listener";

    Refused refused = dial(id, null);

    assertTrue(refused.await(PATIENCE_MILLIS), "the upgrade neither opened nor failed");
    assertFalse(
        refused.opened(),
        "the socket opened without a credential, so the filter does not run on a"
            + " WebSocket upgrade and the whole of the slice design's auth section"
            + " rests on something untrue");
    assertEquals(
        401,
        refused.code(),
        "the upgrade was refused, but not by the filter — the design says an upgrade is"
            + " an ordinary HTTP request before it is a socket, and 401 is what"
            + " proves it");
    // And the handler was never entered: attaching is the first thing
    // afterConnectionEstablished does, so an empty registry is the handler
    // not having run rather than a socket that opened and did nothing.
    assertTrue(
        registry.find(id).isEmpty(),
        "the session registry saw the id, so the handler ran and the filter refused"
            + " something downstream of it");
  }

  @Test
  void an_authenticated_websocket_upgrade_reaches_the_handler() throws InterruptedException {
    String id = "authenticated-listener";
    String access = tokens.issuePair().access();

    // A Cookie header, because that is what a browser sends on an upgrade
    // and it is the transport this test most needs to hold: okhttp could
    // send an Authorization header here and a page never can.
    Refused dialled = dial(id, "ps_access=" + access);

    assertTrue(dialled.await(PATIENCE_MILLIS), "the upgrade neither opened nor failed");
    assertTrue(
        dialled.opened(),
        "the console cannot open a listener at all, which is a gate nobody can pass");
    assertTrue(awaitListener(id), "and the handler attached the listener it was given");
  }

  // --- a restricted chain, residual review of task 3 -----------------------

  /**
   * The browser half of the finding {@code AuthController#ticket()} already closes for a
   * non-browser caller.
   *
   * <p>Refusing a restricted chain's ticket trade only ever covered a caller with no cookie jar and
   * no way to set a header on a WebSocket upgrade. A browser is not that caller: {@link
   * #an_authenticated_websocket_upgrade_reaches_the_handler()} above is the measurement that an
   * ordinary session's {@code ps_access} cookie is sent on this exact upgrade and admitted, and
   * nothing about a flagged admin's chain changes which cookies a browser attaches — so before this
   * filter asked {@link TokenStore#chainIsRestricted(String)} itself, that same cookie opened the
   * socket regardless of the ticket refusal. See {@link AuthFilter}'s class note "A restricted
   * chain may not hold the socket, cookie or ticket".
   */
  @Test
  void a_restricted_chain_s_cookie_is_refused_at_the_events_socket() throws InterruptedException {
    String id = "restricted-cookie-listener";
    String access = tokens.issuePair(true).access();

    Refused refused = dial(id, "ps_access=" + access);

    assertTrue(refused.await(PATIENCE_MILLIS), "the upgrade neither opened nor failed");
    assertFalse(
        refused.opened(),
        "a flagged admin's own cookie opened the events socket, so refusing the ticket"
            + " trade closed only the caller that never needed a cookie in the first"
            + " place");
    assertEquals(401, refused.code());
    assertTrue(
        registry.find(id).isEmpty(),
        "the session registry saw the id, so the handler ran before the restricted"
            + " chain was refused");
  }

  /** Setup and password-rotation sessions may use only the account repair routes. */
  @Test
  void a_restricted_chain_cannot_use_an_ordinary_v1_route() throws IOException {
    String access = tokens.issuePair(true).access();

    try (Response allowed =
        get("/v1/jobs", request -> request.header("Cookie", "ps_access=" + access))) {
      assertEquals(
          401, allowed.code(), "Setup/password-change sessions must not use ordinary routes");
    }
    assertEquals(0, probes.gated());
  }

  // --- the ticket, task 3 of the auth slice --------------------------------

  /**
   * The claim task 3 exists to prove: a caller with no cookie jar and no way to set a header on an
   * upgrade can still open the events socket, by trading one authenticated HTTP call for a ticket
   * first.
   */
  @Test
  void a_ticket_admits_the_events_upgrade() throws InterruptedException {
    String id = "ticket-admits-listener";
    String ticket = tokens.mintTicket();

    Refused dialled = dial(id, null, ticket);

    assertTrue(dialled.await(PATIENCE_MILLIS), "the upgrade neither opened nor failed");
    assertTrue(
        dialled.opened(),
        "a valid ticket did not open the events socket, so the one credential a"
            + " non-browser client can present on an upgrade does not work");
    assertTrue(awaitListener(id), "and the handler attached the listener it was given");
  }

  /**
   * A ticket is single-use, exactly like the bootstrap token — presenting it again is not a retry.
   */
  @Test
  void a_spent_ticket_does_not_admit_a_second_upgrade() throws InterruptedException {
    String ticket = tokens.mintTicket();
    Refused first = dial("ticket-reuse-first", null, ticket);
    assertTrue(first.await(PATIENCE_MILLIS), "the first upgrade neither opened nor failed");
    assertTrue(
        first.opened(),
        "the ticket did not open the first upgrade, so this proves nothing about reuse");

    Refused second = dial("ticket-reuse-second", null, ticket);

    assertTrue(second.await(PATIENCE_MILLIS), "the second upgrade neither opened nor failed");
    assertFalse(
        second.opened(),
        "the same ticket opened a socket twice, so a ticket presented once is not" + " single-use");
    assertEquals(401, second.code());
  }

  /**
   * A ticket is a credential for the socket and nothing else — presenting it on any other gated
   * route is refused, and refused without spending it, because that route never looks at it at all.
   */
  @Test
  void a_ticket_does_not_admit_a_different_gated_route() throws IOException, InterruptedException {
    String ticket = tokens.mintTicket();

    try (Response refused = get("/v1/jobs?" + AuthFilter.TICKET_PARAM + "=" + ticket, null)) {
      assertEquals(
          401,
          refused.code(),
          "a ticket authenticated a route other than the events socket, so a"
              + " credential meant for one socket authenticates the whole API");
    }
    assertEquals(0, probes.gated(), "and the controller was never entered");

    // AND THE TICKET STILL WORKS ON THE EVENTS PATH, which is what says the
    // refusal above came from the path being wrong and not from the ticket
    // having been spent by being looked at on the wrong route.
    Refused dialled = dial("ticket-scoped-listener", null, ticket);
    assertTrue(dialled.await(PATIENCE_MILLIS), "the upgrade neither opened nor failed");
    assertTrue(
        dialled.opened(),
        "presenting the ticket at another route spent it, so a ticket is not scoped to"
            + " the events path by the filter simply never asking elsewhere — it is"
            + " being spent somewhere it should only ever be read");
  }

  /**
   * The floor this whole feature sits on: neither transport is weakened by the ticket existing at
   * all.
   */
  @Test
  void an_events_upgrade_with_neither_a_cookie_nor_a_ticket_is_still_refused()
      throws InterruptedException {
    Refused refused = dial("no-credential-listener", null, null);

    assertTrue(refused.await(PATIENCE_MILLIS), "the upgrade neither opened nor failed");
    assertFalse(
        refused.opened(),
        "the events socket opened with neither a cookie nor a ticket, so the ticket"
            + " code path is admitting a request it should refuse");
    assertEquals(401, refused.code());
  }

  // --- the file channel's door, task 1 of this slice -----------------------

  /**
   * The file channel is a socket a browser-shaped client has to open too, and such a client cannot
   * set a header on an upgrade — which is the whole reason the ticket exists. Before this, a ticket
   * opened {@code /v1/events} and was ignored on {@code /v1/files}, so the terminal client could
   * listen and could never serve a file.
   */
  @Test
  void a_ticket_admits_the_files_upgrade() throws InterruptedException {
    String id = "ticket-admits-provider";
    String ticket = tokens.mintTicket();

    Refused dialled = dialOn(FileChannelHandler.PATH, id, null, ticket);

    assertTrue(dialled.await(PATIENCE_MILLIS), "the upgrade neither opened nor failed");
    assertTrue(
        dialled.opened(),
        "a valid ticket did not open the file channel, so a client that cannot set a"
            + " header can listen and can never lend a file");
    assertTrue(awaitProvider(id), "and the handler attached the provider it was given");
  }

  /** Single-use on this path as on the other: one ticket, one socket, whichever. */
  @Test
  void a_ticket_spent_on_the_events_socket_does_not_open_the_file_channel()
      throws InterruptedException {
    String ticket = tokens.mintTicket();
    Refused listener = dial("ticket-spent-listener", null, ticket);
    assertTrue(listener.await(PATIENCE_MILLIS), "the first upgrade neither opened nor failed");
    assertTrue(listener.opened(), "the ticket did not open the first upgrade");

    Refused provider = dialOn(FileChannelHandler.PATH, "ticket-spent-provider", null, ticket);

    assertTrue(provider.await(PATIENCE_MILLIS), "the second upgrade neither opened nor failed");
    assertFalse(provider.opened(), "one ticket opened two sockets on two paths");
    assertEquals(401, provider.code());
  }

  /**
   * The browser half of the restricted-chain finding, on the file channel as on the listener: a
   * flagged admin's own {@code ps_access} cookie is sent on this upgrade exactly like any other,
   * and the ticket refusal alone would cover only the caller that never needed a cookie in the
   * first place. See {@link #a_restricted_chain_s_cookie_is_refused_at_the_events_socket()}.
   */
  @Test
  void a_restricted_chain_may_not_hold_the_file_channel() throws InterruptedException {
    String id = "restricted-cookie-provider";
    String access = tokens.issuePair(true).access();

    Refused refused = dialOn(FileChannelHandler.PATH, id, "ps_access=" + access, null);

    assertTrue(refused.await(PATIENCE_MILLIS), "the upgrade neither opened nor failed");
    assertFalse(
        refused.opened(),
        "a flagged admin's own cookie opened the file channel, so refusing the ticket"
            + " trade closed only the caller that never needed a cookie in the first"
            + " place");
    assertEquals(401, refused.code());
    assertTrue(
        registry.find(id).isEmpty(),
        "the session registry saw the id, so the handler ran before the restricted"
            + " chain was refused");
  }

  /**
   * One registration, under the name {@link AuthConfig} gives it.
   *
   * <p>Spring Boot registers a bare {@code Filter} bean at {@code /*} of its own accord, so {@link
   * AuthConfig} deliberately exposes only the {@link
   * org.springframework.boot.web.servlet.FilterRegistrationBean} and not the filter — otherwise
   * both mechanisms would find it and a request would be checked twice. <b>That is asserted here
   * rather than argued in a comment</b>, over the container's own registry: this reads what Tomcat
   * was actually told, not what a bean definition intended.
   */
  @Test
  void the_filter_is_registered_once_and_over_everything() {
    jakarta.servlet.ServletContext servlets =
        ((org.springframework.web.context.WebApplicationContext) context).getServletContext();

    List<String> mine = new ArrayList<>();
    servlets
        .getFilterRegistrations()
        .forEach(
            (name, registration) -> {
              if (registration.getClassName().equals(AuthFilter.class.getName())) {
                mine.add(name + " " + registration.getUrlPatternMappings());
              }
            });

    assertEquals(
        List.of("plowshareAuthFilter [/*]"),
        mine,
        "the gate is registered a number of times that is not one, or over something"
            + " narrower than everything — and a pattern narrower than /* would put"
            + " the list of gated paths in a second place");
  }

  /**
   * The dispatcher must be at {@code /}, and this is what says so.
   *
   * <p><b>The latent open door this test exists for.</b> {@link AuthFilter} reads {@code
   * getServletPath() + getPathInfo()}, which is the path relative to the servlet mapping, and
   * {@link AuthFilter#gates(String)} is written against the path relative to the context. They are
   * the same string only while {@code DispatcherServlet} is mapped at {@code /}, and until {@link
   * AuthConfig} refused it nothing in this tree said so.
   *
   * <p>What was measured on this exact {@link Wiring}, with the guard removed and one property
   * added: {@code --server.port=0 --spring.mvc.servlet.path=/api}, then an anonymous {@code GET
   * /api/v1/jobs} — <b>200, body {@code reached}</b>. The controller ran. The same wiring under
   * {@code --server.servlet.context-path=/ps} refuses {@code GET /ps/v1/jobs} with the 401, because
   * the specification keeps a context path out of {@code getServletPath()}; a prefix-mapped
   * dispatcher is the case that is not covered.
   *
   * <p>So the assertion is that the context does not start at all. A 401 would be the weaker
   * guarantee — it would hold for this repository's tests and for nothing an operator does on their
   * own machine, and {@code spring.mvc.servlet.path} is exactly the kind of key an operator sets.
   */
  @Test
  void a_dispatcher_mapped_under_a_prefix_is_refused_at_boot() {
    SpringApplicationBuilder booting =
        new SpringApplicationBuilder(Wiring.class).web(WebApplicationType.SERVLET);

    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                booting
                    .run(
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--spring.mvc.servlet.path=/api")
                    .close(),
            "the context started with the dispatcher mapped under /api. AuthFilter is then"
                + " handed /api/v1/jobs where its rule expects /v1/jobs, and an"
                + " anonymous GET /api/v1/jobs was measured reaching the controller with"
                + " a 200 — every /v1 route and both sockets open, silently");

    String said = causes(refused);
    assertTrue(
        said.contains("spring.mvc.servlet.path"),
        "the boot was refused without naming the property that caused it, so an"
            + " operator reading the stack trace cannot act on it — "
            + said);
    assertTrue(said.contains("/api"), "the refusal does not quote the value it refused — " + said);
    assertTrue(
        said.contains("server.servlet.context-path"),
        "the refusal names no way to serve this application under a prefix, so it reads"
            + " as a capability withdrawn rather than a key chosen wrong — "
            + said);
  }

  @Test
  void the_file_channel_is_gated_by_the_same_decision() {
    // /v1/files is the other socket, and it is under /v1/ — so it is gated
    // by the prefix rather than by a second implementation, which is the
    // property the design asked a filter for. Asserted against the same pure
    // rule the running filter uses.
    assertTrue(AuthFilter.gates(FileChannelHandler.PATH));
    assertTrue(AuthFilter.gates(EventChannelHandler.PATH));
  }

  // --- what a refusal may not say -----------------------------------------

  /**
   * An expired token is refused, and the refusal names no token.
   *
   * <p>Driven through {@link MockHttpServletRequest} rather than over the socket, because fifteen
   * minutes is the shortest lifetime this server has and a test that waited it out would be the
   * slowest thing in the suite. The filter is the real one and the store is the real one; only the
   * clock and the request are stand-ins.
   */
  @Test
  void an_expired_token_is_refused_and_the_refusal_names_nothing() throws Exception {
    Ticking clock = new Ticking();
    AuthProperties properties = new AuthProperties();
    TokenStore store =
        new TokenStore(
            clock,
            properties.getAccessLifetime(),
            properties.getRefreshLifetime(),
            properties.getTicketLifetime());
    String access = store.issuePair().access();
    AuthFilter filter = new AuthFilter(store, properties);

    clock.advance(properties.getAccessLifetime().plusSeconds(1));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/jobs");
    request.setServletPath("/v1/jobs");
    request.addHeader("Authorization", "Bearer " + access);
    MockHttpServletResponse response = new MockHttpServletResponse();
    Reached reached = new Reached();

    filter.doFilter(request, response, reached);

    assertFalse(reached.happened(), "an expired token reached the controller");
    assertEquals(401, response.getStatus());
    // No assertEquals against the token, ever: it prints its operands, and it
    // prints them exactly when the value is the live one. See the class note.
    assertFalse(
        everything(response).contains(access),
        "the refusal echoed the value that was presented, which hands it to whatever"
            + " reads the response — a log, a proxy, a browser console");
    assertFalse(
        everything(response).toLowerCase().contains("expire"),
        "the refusal says which check failed, which tells a caller whether a guessed"
            + " token was ever real");
  }

  // --- the switch ----------------------------------------------------------

  @Test
  void disabling_auth_logs_a_warning_that_names_what_is_open() throws Exception {
    AuthProperties off = new AuthProperties();
    off.setEnabled(false);
    Logger filterLog = (Logger) LoggerFactory.getLogger(AuthFilter.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    filterLog.addAppender(captured);

    AuthFilter filter;
    try {
      filter =
          new AuthFilter(
              new TokenStore(
                  Clock.systemUTC(),
                  Duration.ofMinutes(15),
                  Duration.ofDays(7),
                  Duration.ofSeconds(10)),
              off);
    } finally {
      filterLog.detachAppender(captured);
    }

    assertEquals(1, captured.list.size(), "a server that authenticates nothing said nothing");
    ILoggingEvent said = captured.list.get(0);
    assertEquals(
        Level.WARN,
        said.getLevel(),
        "at INFO this line is one of a hundred at startup and nobody sees it");
    String line = said.getFormattedMessage();
    assertTrue(
        line.contains("plowshare.auth.enabled"),
        "the warning does not name the key that caused it — " + line);
    assertTrue(
        line.contains(EventChannelHandler.PATH) && line.contains(FileChannelHandler.PATH),
        "the warning names no sockets, and the two sockets are the part an operator"
            + " least expects to be included — "
            + line);

    // And it really is off: the gated path goes through.
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/jobs");
    request.setServletPath("/v1/jobs");
    Reached reached = new Reached();
    filter.doFilter(request, new MockHttpServletResponse(), reached);
    assertTrue(
        reached.happened(), "the warning is printed by something that gates anyway, so it lies");
  }

  // --- the handle attribute, task 5 of "a session knows whose it is" -------

  /**
   * {@link AuthFilter#HANDLE_ATTRIBUTE} is what task 6 copies onto the socket, so every credential
   * this filter can accept on the events path has to be asked, in the order the interface names: an
   * access token or cookie whose chain has a handle, then a ticket carrying one, then any other
   * accepted credential falling back to {@code properties.getAdminHandle()}.
   *
   * <p>Driven through {@link MockHttpServletRequest} rather than over the socket, for {@link
   * #an_expired_token_is_refused_and_the_refusal_names_nothing()}'s own reason: only the request
   * and the store need to be real to exercise {@link AuthFilter#doFilter}, and a real Tomcat
   * upgrade would answer the same question far more slowly. A fresh {@link TokenStore} per test —
   * built the same way {@link #an_expired_token_is_refused_and_the_refusal_names_nothing()} builds
   * one, from a real {@link AuthProperties} rather than the shared one the outer class's static
   * {@code tokens} field is wired against — because this block needs {@code
   * properties.setAdminHandle("root")}, which the running server's own {@link AuthProperties} bean
   * was not configured with.
   */
  @Nested
  class TheHandleAttribute {

    private TokenStore tokens;
    private AuthProperties properties;

    @BeforeEach
    void freshStoreWithASeededAdmin() {
      properties = new AuthProperties();
      properties.setAdminHandle("root");
      tokens =
          new TokenStore(
              Clock.systemUTC(),
              properties.getAccessLifetime(),
              properties.getRefreshLifetime(),
              properties.getTicketLifetime());
    }

    /**
     * What {@link AuthFilter#HANDLE_ATTRIBUTE} was set to on a request that ran the whole filter
     * and reached the chain — {@code null} if the filter never set it, including if it refused the
     * request outright.
     */
    private String handleSeenBy(MockHttpServletRequest request) throws Exception {
      AtomicReference<Object> seen = new AtomicReference<>();
      new AuthFilter(tokens, properties)
          .doFilter(
              request,
              new MockHttpServletResponse(),
              (req, res) -> seen.set(req.getAttribute(AuthFilter.HANDLE_ATTRIBUTE)));
      return (String) seen.get();
    }

    /**
     * A bare {@code /v1/events} upgrade, with a session query parameter the way a real one carries
     * one — the servlet path is set explicitly because {@link MockHttpServletRequest}'s constructor
     * leaves it empty and fills only {@code requestURI}, which {@link AuthFilter}'s own path
     * computation never reads.
     */
    private static MockHttpServletRequest upgrade() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/events");
      request.setServletPath("/v1/events");
      request.setParameter("session", "s1");
      return request;
    }

    @Test
    void a_logged_in_access_token_names_its_own_handle_on_the_upgrade() throws Exception {
      MockHttpServletRequest request = upgrade();
      request.addHeader("Authorization", "Bearer " + tokens.issuePair("enzo", false).access());

      assertEquals("enzo", handleSeenBy(request));
    }

    @Test
    void a_ticket_names_the_handle_it_was_minted_for() throws Exception {
      MockHttpServletRequest request = upgrade();
      request.setParameter("ticket", tokens.mintTicket("enzo"));

      assertEquals("enzo", handleSeenBy(request));
    }

    @Test
    void the_operator_token_is_the_seeded_admin() throws Exception {
      String operator = Tokens.mint();
      tokens.acceptOperator(operator);
      MockHttpServletRequest request = upgrade();
      request.addHeader("Authorization", "Bearer " + operator);

      assertEquals("root", handleSeenBy(request));
    }

    @Test
    void with_no_admin_handle_configured_the_operator_token_names_nobody() throws Exception {
      properties.setAdminHandle("");
      String operator = Tokens.mint();
      tokens.acceptOperator(operator);
      MockHttpServletRequest request = upgrade();
      request.addHeader("Authorization", "Bearer " + operator);

      assertNull(handleSeenBy(request));
    }

    /** A bare {@code /v1/files} upgrade, as {@link #upgrade()} builds one for events. */
    private static MockHttpServletRequest filesUpgrade() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/files");
      request.setServletPath("/v1/files");
      request.setParameter("session", "s1");
      return request;
    }

    /**
     * Enzo's decision of 2026-09-30 (spec 2026-09-30-local-hooks-are-served): the file channel's
     * upgrade names its account exactly as the listener's does, so the session's two sockets can be
     * held to one account.
     */
    @Test
    void a_files_upgrade_names_its_handle_by_ticket_by_token_and_by_the_admin_fallback()
        throws Exception {
      MockHttpServletRequest byTicket = filesUpgrade();
      byTicket.setParameter("ticket", tokens.mintTicket("enzo"));
      MockHttpServletRequest byToken = filesUpgrade();
      byToken.addHeader("Authorization", "Bearer " + tokens.issuePair("enzo", false).access());
      String operator = Tokens.mint();
      tokens.acceptOperator(operator);
      MockHttpServletRequest byOperator = filesUpgrade();
      byOperator.addHeader("Authorization", "Bearer " + operator);

      assertEquals("enzo", handleSeenBy(byTicket));
      assertEquals("enzo", handleSeenBy(byToken));
      assertEquals("root", handleSeenBy(byOperator));
    }

    @Test
    void with_auth_off_a_files_upgrade_is_the_seeded_admin() throws Exception {
      properties.setEnabled(false);

      assertEquals("root", handleSeenBy(filesUpgrade()));
    }

    /** Every gated request carries its authenticated account. */
    @Test
    void a_rest_request_carries_its_token_handle_or_the_admin_with_auth_off() throws Exception {
      MockHttpServletRequest rest = new MockHttpServletRequest("GET", "/v1/jobs");
      rest.setServletPath("/v1/jobs");
      rest.addHeader("Authorization", "Bearer " + tokens.issuePair("enzo", false).access());
      MockHttpServletRequest restOff = new MockHttpServletRequest("GET", "/v1/jobs");
      restOff.setServletPath("/v1/jobs");

      assertEquals("enzo", handleSeenBy(rest));
      properties.setEnabled(false);
      assertEquals("root", handleSeenBy(restOff));
    }

    /** Other gated paths carry the same handle as the upgrades. */
    @Test
    void other_gated_paths_also_carry_the_handle() throws Exception {
      for (String near : List.of("/v1/files/", "/v1/filesx", "/v1/events/x")) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", near);
        request.setServletPath(near);
        request.addHeader("Authorization", "Bearer " + tokens.issuePair("enzo", false).access());

        assertEquals("enzo", handleSeenBy(request), near);
      }
    }
  }

  // --- the rule itself -----------------------------------------------------

  @Test
  void a_path_that_reaches_a_gated_route_only_when_decoded_is_still_refused() throws Exception {
    // getRequestURI() is not decoded, and the servlet specification says so.
    // A filter reading it would see a string that does not start with /v1/
    // where the dispatcher sees GET /v1/jobs — which is a bypass and not an
    // inconvenience. Sent down a raw socket because okhttp's HttpUrl
    // canonicalises a path before it is ever written to the wire.
    String answer = raw("/%76%31/jobs");

    assertTrue(
        answer.startsWith("HTTP/1.1 401"),
        "an encoded spelling of /v1/jobs was not refused — " + statusLine(answer));
    assertEquals(0, probes.gated(), "and it did not reach the controller either");

    // AND THE SPELLING REALLY DOES ROUTE THERE, which is the half that makes
    // the 401 above mean something. Without this the test would pass just as
    // well against a container that 404s the encoded form, and the filter's
    // javadoc would be claiming a bypass that was never available.
    String allowed = raw("/%76%31/jobs", "Authorization: Bearer " + tokens.issuePair().access());
    assertTrue(
        allowed.startsWith("HTTP/1.1 200"),
        "the encoded spelling does not reach the controller even with a credential, so"
            + " the refusal above is measuring the container and not the filter — "
            + statusLine(allowed));
    assertEquals(1, probes.gated());
  }

  /**
   * A trailing slash is a different route, and the filter and the dispatcher agree about that.
   *
   * <p>{@link AuthFilter#OPEN} is a set of exact strings, so {@code /v1/auth/} is gated. That is
   * only harmless if the dispatcher also declines to route a trailing slash onto {@code /v1/auth} —
   * otherwise the door would have a spelling this filter refuses. Measured on the gated probe
   * instead of on the door, because the door has no route behind it until task 8: with a valid
   * credential, {@code /v1/jobs/} must not reach the controller.
   */
  @Test
  void a_trailing_slash_is_not_the_same_route() throws Exception {
    String answer = raw("/v1/jobs/", "Authorization: Bearer " + tokens.issuePair().access());

    assertFalse(
        statusLine(answer).startsWith("HTTP/1.1 200"),
        "this container routes a trailing slash onto the mapping without one, so"
            + " AuthFilter.OPEN's exact-string exemption has a spelling the"
            + " dispatcher would honour and the filter would not — "
            + statusLine(answer));
    assertEquals(0, probes.gated());
  }

  @Test
  void a_path_that_walks_out_of_the_open_door_is_refused() throws Exception {
    // /v1/auth/../jobs normalises to /v1/jobs. Whatever the container does
    // with the dot segments, the two halves must agree: if it routes to the
    // controller the filter must have gated it, and if it does not route
    // there is nothing to gate.
    String answer = raw("/v1/auth/../jobs");

    assertFalse(
        statusLine(answer).startsWith("HTTP/1.1 200"),
        "a request that walked out of the open door reached the controller — "
            + statusLine(answer));
    assertEquals(0, probes.gated());
  }

  @Test
  void the_rule_is_a_prefix_and_the_door_is_three_exact_strings() {
    assertTrue(AuthFilter.gates("/v1/jobs"));
    assertTrue(AuthFilter.gates("/v1/memories/index"));
    assertTrue(AuthFilter.gates("/v1"));
    assertFalse(AuthFilter.gates("/v1/auth"));
    assertFalse(AuthFilter.gates("/v1/auth/refresh"));
    assertFalse(AuthFilter.gates("/v1/auth/login"));
    // Not a prefix: a route that merely starts with the door's spelling is
    // not the door. This is the mistake the exemption is written as a Set to
    // avoid.
    assertTrue(
        AuthFilter.gates("/v1/authorised"),
        "the exemption is matching as a prefix, so any future /v1/auth* route is open");
    assertTrue(
        AuthFilter.gates("/v1/auth/"),
        "a trailing slash is a different string, and Spring Boot 3 does not route it"
            + " onto the door either");
    assertFalse(AuthFilter.gates("/index.html"));
    assertFalse(AuthFilter.gates("/assets/console.js"));
    // Absence is refused rather than waved through: a path this filter
    // cannot read is one it cannot vouch for.
    assertTrue(AuthFilter.gates(null));
    assertTrue(AuthFilter.gates(""));
  }

  /**
   * Every route this application publishes is gated or deliberately open.
   *
   * <p><b>The one that catches the endpoint somebody adds in six months without thinking about
   * auth.</b> Every other test here names a path; this one names none, and enumerates instead — the
   * controllers by classpath scan, so a new {@code @RestController} anywhere under {@code
   * io.aeyer.plowshare.server} is in the enumeration by existing, and the two sockets by their
   * published constants.
   *
   * <p>It lives in the {@code test} task rather than in {@code invariants} deliberately. That task
   * exists for guards whose subject is text the compiler never reads — a comment, a plan, a
   * resource — and whose inputs therefore have to be declared as the tree. This guard's subject is
   * compiled classes on the test runtime classpath, which {@code test} already declares, so adding
   * a controller re-runs it without anything being wired up.
   *
   * <p>The scan's soundness rests on its own assertions: that it found more than nothing and found
   * a route that certainly exists, and that {@code addHandler} is called nowhere but the two
   * channel configurations — a third socket registered somewhere else would otherwise be a path
   * this enumeration never sees. <b>That second guard is a weaker instrument than this one and does
   * not share the paragraph above</b>: it greps source text rather than reading classes. See {@link
   * #nothing_registers_a_socket_outside_the_two_channel_configurations()} for what that costs.
   *
   * <p><b>It reads test controllers too</b>, because the scanner reads a classpath and this file's
   * own {@link Probes} is on it. That is left in rather than filtered out: a controller is a route
   * whatever source set it came from, and a test that publishes an ungated one is a test that has
   * quietly built the thing this guard is about.
   */
  @Test
  void every_route_this_application_publishes_is_gated_or_deliberately_open() throws Exception {
    Set<String> published = new TreeSet<>(mappedPaths());
    published.add(EventChannelHandler.PATH);
    published.add(FileChannelHandler.PATH);

    // The scan passing over an empty set would be a green that means
    // nothing, so it has to be shown to have found the tree it claims to
    // read — by a count and by a route nobody would delete quietly.
    assertTrue(
        published.size() > 10,
        "the scan found almost nothing, so it is passing over an empty set rather than"
            + " asserting anything — "
            + published);
    assertTrue(
        published.contains("/v1/memories/index"),
        "the scan did not find a route this application certainly publishes, so it is"
            + " reading something other than the controllers — "
            + published);

    // AND THE DOOR IS NOW AMONG THEM. Until task 8 this was a decision with
    // no route behind it, so the exemption below could only be a subset. It
    // is a published pair of routes now, and asserting so is what says the
    // controller is annotated and mapped where AuthFilter.OPEN says it is —
    // the scan reads the same package root @SpringBootApplication scans.
    assertTrue(
        published.containsAll(AuthFilter.OPEN),
        "AuthFilter exempts paths this application does not publish, so the exemption is"
            + " open on nothing and there is no way to obtain a credential — "
            + published);

    Set<String> ungated = new TreeSet<>();
    for (String path : published) {
      if (!AuthFilter.gates(path)) {
        ungated.add(path);
      }
    }
    ungated.removeAll(AuthFilter.OPEN);
    assertEquals(
        Set.of(),
        ungated,
        "these routes are reachable without a credential. Every one of them must be a"
            + " deliberate decision recorded in AuthFilter.OPEN: a route that is"
            + " open by accident is an open door, not a stale comment. If a new"
            + " endpoint appears here, it is because it was not put under /v1/.");
  }

  /**
   * No third socket, and this one reads <b>file text</b>.
   *
   * <p>Unlike the enumeration above, whose subject is compiled classes on the test runtime
   * classpath, this walks {@code src/main/java} and asks each {@code .java} file whether it
   * contains the literal substring {@code addHandler(}. That is worth saying plainly, because a
   * reader arriving from the paragraph above will otherwise carry the wrong model across and trust
   * this further than it deserves.
   *
   * <p>What a substring scan misses, and each of these would be a socket this file swears does not
   * exist:
   *
   * <ul>
   *   <li>{@code addHandler (} — a space before the parenthesis, which the compiler accepts and
   *       this string does not match;
   *   <li>a registration behind a helper — {@code register(registry, handler)} — where the call
   *       site naming {@code addHandler} is one indirection away and lives in a file this assertion
   *       would then also list;
   *   <li>a handler registered from anywhere that is not {@code src/main/java}: another source set,
   *       another module, a generated source directory.
   * </ul>
   *
   * <p>It is kept as text rather than raised to a bean-level assertion because the property wanted
   * is "no file in this tree registers one", and a running context can only report the
   * registrations its own wiring produced. The honest description is the one above: this catches
   * the ordinary spelling of a third socket, and it is not a proof that none exists.
   */
  @Test
  void nothing_registers_a_socket_outside_the_two_channel_configurations() throws IOException {
    Set<String> registrars = new TreeSet<>();
    Path main = Path.of("src/main/java");
    try (Stream<Path> sources = Files.walk(main)) {
      for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (Files.readString(source).contains("addHandler(")) {
          registrars.add(source.getFileName().toString());
        }
      }
    }

    assertEquals(
        Set.of("EventChannelConfig.java", "FileChannelConfig.java"),
        registrars,
        "a WebSocket handler is registered somewhere the auth enumeration above does"
            + " not read, so its path is gated by nobody having noticed");
  }

  // --- fixtures ------------------------------------------------------------

  private static Response get(String path, RequestTweak tweak) throws IOException {
    Request.Builder request = new Request.Builder().url("http://localhost:" + port + path);
    if (tweak != null) {
      tweak.apply(request);
    }
    return http.newCall(request.build()).execute();
  }

  /**
   * A POST with a JSON body, for the door — which is the one thing in this file that is not a GET.
   */
  private static Response postJson(String path, String json) throws IOException {
    return http.newCall(
            new Request.Builder()
                .url("http://localhost:" + port + path)
                .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.get("application/json")))
                .build())
        .execute();
  }

  private static String body(Response response) throws IOException {
    return response.body() == null ? "" : response.body().string();
  }

  private static String contentType(Response response) {
    String header = response.header("Content-Type");
    // Tomcat appends the charset; the assertion is about the type, and the
    // charset is asserted by the body parsing at all.
    return header == null ? null : header.split(";")[0].trim();
  }

  /**
   * One request, written to the socket exactly as spelled.
   *
   * <p>okhttp is the wrong instrument for the two adversarial paths above: {@code HttpUrl} resolves
   * dot segments and re-canonicalises percent escapes when it parses, so the string this file wrote
   * would not be the string the server read. This writes the request line itself.
   *
   * @return the whole response, status line first
   */
  private static String raw(String target, String... headers) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", port), 5_000);
      OutputStream out = socket.getOutputStream();
      StringBuilder extra = new StringBuilder();
      for (String header : headers) {
        extra.append(header).append("\r\n");
      }
      out.write(
          ("GET "
                  + target
                  + " HTTP/1.1\r\n"
                  + "Host: localhost\r\n"
                  + extra
                  + "Connection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      StringBuilder answer = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          answer.append(line).append('\n');
        }
      }
      return answer.toString();
    }
  }

  /**
   * Every message in a failure's cause chain, joined — a bean whose factory throws reaches the
   * caller wrapped, and the sentence that matters is the innermost one.
   */
  private static String causes(Throwable failure) {
    StringBuilder all = new StringBuilder();
    for (Throwable at = failure; at != null; at = at.getCause()) {
      all.append(at.getMessage()).append(" | ");
    }
    return all.toString();
  }

  private static String statusLine(String answer) {
    int end = answer.indexOf('\n');
    return end < 0 ? answer : answer.substring(0, end);
  }

  /**
   * Everything the filter wrote — body, status and every header value — as one string, so that
   * "names nothing" is asserted over the whole response rather than over the part a reader thought
   * to check.
   */
  private static String everything(MockHttpServletResponse response)
      throws java.io.UnsupportedEncodingException {
    StringBuilder all =
        new StringBuilder()
            .append(response.getStatus())
            .append(' ')
            .append(response.getContentAsString());
    for (String name : response.getHeaderNames()) {
      all.append(' ').append(name).append('=').append(response.getHeader(name));
    }
    return all.toString();
  }

  /**
   * Dials the listener socket, optionally with a cookie — the browser's only way to present
   * anything on an upgrade.
   */
  private Refused dial(String id, String cookie) {
    return dial(id, cookie, null);
  }

  /**
   * Dials the listener socket, optionally with a cookie and a ticket — the two ways anything at all
   * can be presented on an upgrade, since neither a browser nor a non-browser client can set a
   * header on one.
   */
  private Refused dial(String id, String cookie, String ticket) {
    return dialOn(EventChannelHandler.PATH, id, cookie, ticket);
  }

  /**
   * {@link #dial(String, String, String)}, on whichever socket path the caller names — the events
   * listener and the file channel are gated by the same filter, and this is what lets one dialler
   * measure both.
   */
  private Refused dialOn(String path, String id, String cookie, String ticket) {
    String url =
        "ws://localhost:"
            + port
            + path
            + "?"
            + FileChannelHandler.SESSION_PARAM
            + "="
            + id
            + (ticket == null ? "" : "&" + AuthFilter.TICKET_PARAM + "=" + ticket);
    Request.Builder request = new Request.Builder().url(url);
    if (cookie != null) {
      request.header("Cookie", cookie);
    }
    Refused refused = new Refused();
    opened.add(http.newWebSocket(request.build(), refused));
    return refused;
  }

  private static boolean awaitListener(String id) throws InterruptedException {
    for (long waited = 0; waited < PATIENCE_MILLIS; waited += 10) {
      if (registry.find(id).filter(session -> session.has(Role.LISTENER)).isPresent()) {
        return true;
      }
      Thread.sleep(10);
    }
    return false;
  }

  /**
   * {@link #awaitListener}, for the other role: the file channel attaches {@link
   * Role#FILE_PROVIDER} rather than {@link Role#LISTENER}, and the upgrade is asynchronous on both
   * sides so a test that asserted the role the instant {@code newWebSocket} returned would race the
   * registration.
   */
  private static boolean awaitProvider(String id) throws InterruptedException {
    for (long waited = 0; waited < PATIENCE_MILLIS; waited += 10) {
      if (registry.find(id).filter(session -> session.has(Role.FILE_PROVIDER)).isPresent()) {
        return true;
      }
      Thread.sleep(10);
    }
    return false;
  }

  /** Every path the controllers in this application publish, class-level prefixes included. */
  private static Set<String> mappedPaths() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    // @RestController is meta-annotated @Controller, and AnnotationTypeFilter
    // considers meta-annotations, so one filter finds both spellings.
    scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

    Set<String> paths = new LinkedHashSet<>();
    for (BeanDefinition found : scanner.findCandidateComponents("io.aeyer.plowshare.server")) {
      Class<?> controller = Class.forName(found.getBeanClassName());
      RequestMapping onClass =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      String[] prefixes =
          onClass == null || onClass.path().length == 0 ? new String[] {""} : onClass.path();
      // MethodIntrospector, and not getDeclaredMethods(): the dispatcher
      // maps a handler declared on a SUPERCLASS, which getDeclaredMethods
      // never returns, and one that is NOT PUBLIC, which getMethods() never
      // returns — Probes.gatedRoute below is package-private and really is
      // routed. This is the same call AbstractHandlerMethodMapping makes,
      // so the enumeration sees a method exactly when Spring maps it.
      Map<Method, RequestMapping> mappings =
          MethodIntrospector.selectMethods(
              controller,
              (MethodIntrospector.MetadataLookup<RequestMapping>)
                  method ->
                      AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
      for (RequestMapping mapping : mappings.values()) {
        for (String prefix : prefixes) {
          for (String path : mapping.path()) {
            paths.add(prefix + path);
          }
        }
      }
    }
    return paths;
  }

  @FunctionalInterface
  private interface RequestTweak {
    void apply(Request.Builder request);
  }

  /** A chain that records whether anything behind the filter ran. */
  private static final class Reached implements FilterChain {

    private boolean happened;

    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
      happened = true;
    }

    boolean happened() {
      return happened;
    }
  }

  /**
   * A dialled socket that records which of the two things happened to it.
   *
   * <p>Both outcomes count the latch down, so a test never distinguishes "was refused" from "has
   * not answered yet" by waiting longer.
   */
  private static final class Refused extends WebSocketListener {

    private final CountDownLatch settled = new CountDownLatch(1);
    private final AtomicReference<Integer> code = new AtomicReference<>();
    private volatile boolean opened;

    @Override
    public void onOpen(WebSocket socket, Response response) {
      opened = true;
      code.set(response.code());
      settled.countDown();
    }

    @Override
    public void onFailure(WebSocket socket, Throwable failure, Response response) {
      if (response != null) {
        code.set(response.code());
      }
      settled.countDown();
    }

    boolean await(long millis) throws InterruptedException {
      return settled.await(millis, TimeUnit.MILLISECONDS);
    }

    boolean opened() {
      return opened;
    }

    int code() {
      Integer answered = code.get();
      return answered == null ? -1 : answered;
    }
  }

  /**
   * A clock that moves only when a test moves it.
   *
   * <p>The same shape as {@code TokenStoreTest}'s, and a copy rather than a shared fixture because
   * that one is private to the file that needs it and this is two fields. The reason is the same:
   * the shortest lifetime under test is fifteen minutes, so an instant this file asserts on has to
   * be one this file set.
   */
  private static final class Ticking extends Clock {

    private volatile Instant now = Instant.parse("2026-09-01T09:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }
  }

  /**
   * Two routes that count what reaches them.
   *
   * <p>A gated one at a path the real application also publishes, so this file asserts about a
   * route shaped like the ones being protected rather than about {@code /test}. The real {@code
   * AgentController} is not in this context — it would bring Postgres, the agent registry and a
   * model pool to measure a filter.
   */
  @RestController
  static class Probes {

    private final AtomicInteger gated = new AtomicInteger();

    @GetMapping("/v1/jobs")
    String gatedRoute() {
      gated.incrementAndGet();
      return "reached";
    }

    int gated() {
      return gated.get();
    }

    void reset() {
      gated.set(0);
    }
  }

  /**
   * The web layer, both socket paths and the production auth wiring.
   *
   * <p>{@code EventChannelTest}'s three autoconfigurations plus {@code WebMvcAutoConfiguration},
   * which that file does not need because it publishes no controller and serves no asset. {@link
   * AuthConfig} is the production one.
   *
   * <p><b>{@link FileChannelConfig} joined {@link EventChannelConfig} here for task 1 of this
   * slice</b>, because a ticket admitting {@code /v1/files} is a claim about a socket this context
   * did not use to open at all — before this, {@code dialOn(FileChannelHandler.PATH, ...)} would
   * 404 rather than measure the filter, since nothing was mapped there. {@link
   * #a_ticket_admits_the_files_upgrade()} is what needs the real handler rather than a fake: it
   * asserts the {@link Role#FILE_PROVIDER} role was actually attached, which only a socket that
   * really upgraded can produce. {@link #projectRoots()} below is the one constructor argument
   * {@code FileChannelConfig} needs that {@code EventChannelConfig} does not already supply — a
   * lambda rather than a recording fake, because no test in this file asserts what a socket
   * declared about a project, only whether the upgrade itself was admitted.
   *
   * <p><b>{@link AdminStore} and {@link PasswordHasher} are supplied here as bare beans rather than
   * by scanning for their own {@code @Repository}/ {@code @Component} annotations</b>, because this
   * class is imported by name and not discovered by a component scan — the same reason {@code
   * EventChannelConfig} is named explicitly instead of relying on one. A real {@link AdminStore}
   * needs a {@link org.springframework.jdbc.core.JdbcTemplate}, and this file's whole point is a
   * filter and a socket measured without Postgres in the room; a mock costs nothing and is never
   * asked a question, since no test here calls {@code /v1/auth/login}. {@link PasswordHasher} is
   * genuinely stateless, so the real one costs nothing to construct and there is no reason to fake
   * it. {@link LoginAttempts} needs no bean of its own here: {@link AuthConfig#loginAttempts()}
   * already supplies one.
   */
  @Configuration
  // `Watchers` travels with the channel; EventChannelConfig requires it.
  @Import({
    EventChannelConfig.class,
    FileChannelConfig.class,
    Watchers.class,
    AuthConfig.class,
    AuthController.class,
    io.aeyer.plowshare.server.ws.SocketAuthorization.class,
    Probes.class
  })
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
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
                  org.mockito.ArgumentMatchers.any(
                      io.aeyer.plowshare.server.access.AccessRequest.class),
                  org.mockito.ArgumentMatchers.nullable(String.class)))
          .thenReturn(true);
      return authorization;
    }

    @Bean
    AdminStore adminStore() {
      return mock(AdminStore.class);
    }

    @Bean
    PasswordHasher passwordHasher() {
      return new PasswordHasher();
    }

    /**
     * {@link FileChannelConfig}'s one constructor argument this class does not already supply. A
     * lambda, because this file never asserts what a socket declared about a project — only whether
     * the upgrade was admitted — so the recording fake {@code FileChannelTest.Wiring.Rooted} builds
     * for that question would be answering one nothing here asks.
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
