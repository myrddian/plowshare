package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The way in, and the two credentials it takes to have one.
 *
 * <h2>The design gap this file records</h2>
 *
 * <p>The slice design says the server mints a one-time bootstrap token, prints it in a URL for the
 * browser, <em>and</em> writes it to {@code ~/.config/plowshare/console-token} so a detached server
 * stays reachable. Those two sentences contradict each other and there are two clients: the browser
 * takes its credential from {@code Set-Cookie} because a page cannot set a header on a WebSocket
 * upgrade, the CLI presents {@code Authorization: Bearer} and is handed no cookie, and the token is
 * single-use — so whichever spent it, the other had none.
 *
 * <p>The resolution measured here is <b>two credentials at startup</b>: the bootstrap token stays
 * single-use and stays in the printed URL, and a long-lived operator token recorded by {@link
 * TokenStore#acceptOperator(String)} goes in the file. {@link
 * #the_startup_line_and_the_file_carry_two_different_credentials()} is what fails if they ever
 * become one token again, and {@link
 * #the_operator_token_a_real_boot_writes_authenticates_bearer_requests()} is what fails if the file
 * stops being a credential the CLI can use.
 *
 * <h2>Two instruments, and why both</h2>
 *
 * <p>The cookie and refusal assertions run through {@link MockMvc} over a standalone {@link
 * AuthController} — no container, no port, and a {@link TokenStore} that is sometimes a mock,
 * because {@code verify(store, never()).refresh(...)} is a claim about a call that does not happen
 * and no real store can report one.
 *
 * <p>The startup and end-to-end assertions run against a real Tomcat on a port the OS picks,
 * because the announcement's whole subject is a port that is not known until the server is
 * listening, and because "the CLI's credential path works" is a claim about a file crossing two
 * modules and a socket.
 *
 * <h2>Two standing rules this file is written around</h2>
 *
 * <p><b>Tokens are never operands of {@code assertEquals}.</b> {@code TokensTest} carries the rule:
 * {@code assertEquals} and its relatives interpolate their operands into the failure they print,
 * and they fail exactly when the printed value is the live one. Every assertion here that touches a
 * credential is a boolean, and no message interpolates one.
 *
 * <p><b>No test writes the real {@code ~/.config/plowshare/console-token}.</b> That is the
 * operator's live credential on this machine, and a suite that overwrote it would make whatever
 * server they are using unreachable. Every write here goes to a {@link TempDir}; {@link
 * #a_context_that_did_not_come_through_main_mints_nothing_and_writes_nothing()} is the assertion
 * that the default really is "write nothing", which is what keeps every <em>other</em>
 * full-application context in this repository off that file too.
 */
class AuthControllerTest {

  private static final MediaType JSON = MediaType.get("application/json");

  /**
   * A POST body with no {@code Content-Type} and no bytes, sent chunked so that {@code
   * getContentLength()} is -1 and {@code Transfer-Encoding} is present.
   *
   * <p>The one shape {@code RequestBody.create(byte[], MediaType)} cannot produce: a byte array has
   * a known length, so it goes out as {@code Content-Length: 0} and the container reports no body.
   * See {@link #no_content_type_is_a_401_without_a_body_and_a_415_with_one} for what the
   * distinction pins.
   */
  private static final RequestBody CHUNKED_AND_EMPTY =
      new RequestBody() {

        @Override
        public MediaType contentType() {
          return null;
        }

        @Override
        public long contentLength() {
          return -1;
        }

        @Override
        public void writeTo(BufferedSink sink) {}
      };

  @Test
  void logout_revokes_the_presented_chain_and_returns_no_tokens() throws Exception {
    TokenStore store = mock(TokenStore.class);
    MockHttpServletResponse response =
        standalone(store, new AuthProperties())
            .perform(post("/v1/auth/logout").header("Authorization", "Bearer logout-access"))
            .andReturn()
            .getResponse();
    assertEquals(204, response.getStatus());
    assertEquals("", response.getContentAsString());
    verify(store).revoke("logout-access");
  }

  @Test
  void identical_refresh_delivery_repairs_cookies_with_original_remaining_expiry()
      throws Exception {
    Ticking clock = new Ticking();
    var properties = new AuthProperties();
    var store =
        new TokenStore(
            clock,
            properties.getAccessLifetime(),
            properties.getRefreshLifetime(),
            properties.getTicketLifetime());
    var parent = store.issuePair();
    String intent = java.util.UUID.randomUUID().toString();
    var mvc = standalone(store, properties);
    var first =
        mvc.perform(
                post("/v1/auth/refresh")
                    .header(RefreshIntent.HEADER, intent)
                    .cookie(new jakarta.servlet.http.Cookie("ps_refresh", parent.refresh())))
            .andReturn()
            .getResponse();
    clock.advance(Duration.ofSeconds(5));
    var duplicate =
        mvc.perform(
                post("/v1/auth/refresh")
                    .header(RefreshIntent.HEADER, intent)
                    .cookie(new jakarta.servlet.http.Cookie("ps_refresh", parent.refresh())))
            .andReturn()
            .getResponse();
    assertEquals(204, first.getStatus());
    assertEquals(204, duplicate.getStatus());
    assertTrue(duplicate.getContentAsString().isEmpty());
    var firstCookies = setCookies(first);
    var repaired = setCookies(duplicate);
    assertTrue(value(firstCookies.get("ps_access")).equals(value(repaired.get("ps_access"))));
    assertTrue(value(firstCookies.get("ps_refresh")).equals(value(repaired.get("ps_refresh"))));
    assertEquals(
        Long.toString(properties.getAccessLifetime().minusSeconds(5).toSeconds()),
        attributes(repaired.get("ps_access")).get("max-age"));
    assertEquals(
        Long.toString(properties.getRefreshLifetime().minusSeconds(5).toSeconds()),
        attributes(repaired.get("ps_refresh")).get("max-age"));
    assertTrue(store.validAccess(value(repaired.get("ps_access"))));
  }

  @Test
  void invalid_or_repeated_intent_headers_are_rejected_before_spending_any_credential()
      throws Exception {
    var store = realStore();
    var parent = store.issuePair();
    var mvc = standalone(store, new AuthProperties());
    for (String malformed :
        java.util.List.of(
            "",
            "garbage",
            "F612B939-AA97-4938-B55D-B3C25F0FDC43",
            "00000000-0000-0000-0000-000000000000",
            java.util.UUID.randomUUID() + "," + java.util.UUID.randomUUID())) {
      var response =
          mvc.perform(
                  post("/v1/auth/refresh")
                      .header(RefreshIntent.HEADER, malformed)
                      .cookie(new jakarta.servlet.http.Cookie("ps_refresh", parent.refresh())))
              .andReturn()
              .getResponse();
      assertEquals(400, response.getStatus());
      assertTrue(setCookies(response).isEmpty());
    }
    var repeated =
        mvc.perform(
                post("/v1/auth/refresh")
                    .header(
                        RefreshIntent.HEADER,
                        java.util.UUID.randomUUID().toString(),
                        java.util.UUID.randomUUID().toString())
                    .cookie(new jakarta.servlet.http.Cookie("ps_refresh", parent.refresh())))
            .andReturn()
            .getResponse();
    assertEquals(400, repeated.getStatus());
    assertTrue(store.refresh(parent.refresh()).isPresent());
  }

  // --- the cookies, attribute by attribute ---------------------------------

  /**
   * Every attribute the design names, asserted one at a time.
   *
   * <p><b>Parsed into a map first, and that is the point.</b> A {@code
   * assertTrue(header.contains("HttpOnly"))} passes on a header that merely has the word in it — a
   * cookie <em>named</em> {@code HttpOnly}, an {@code Expires} value that happened to contain it —
   * and a whole-header {@code assertEquals} fails on a harmless reordering of attributes the RFC
   * does not order. So the header is split into name, value and attributes, and each claim is made
   * against the one thing it is about.
   */
  @Test
  void spending_the_bootstrap_token_sets_both_cookies_with_the_attributes_the_design_names()
      throws Exception {
    TokenStore store = mock(TokenStore.class);
    AuthProperties properties = new AuthProperties();
    when(store.spendBootstrap("boot")).thenReturn(true);
    when(store.issuePair()).thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));

    MockHttpServletResponse answer = exchange(store, properties, "{\"token\":\"boot\"}");

    assertEquals(204, answer.getStatus(), "the exchange did not answer 204");
    Map<String, String> cookies = setCookies(answer);
    assertEquals(
        List.of("ps_access", "ps_refresh"),
        new ArrayList<>(cookies.keySet()),
        "the exchange did not set exactly the two cookies the design names");

    Map<String, String> access = attributes(cookies.get("ps_access"));
    assertTrue(
        access.containsKey("httponly"),
        "ps_access is readable by JavaScript, which is the one thing HttpOnly was chosen"
            + " for: this console renders model output and file contents, and an XSS"
            + " takes anything a page can read");
    assertEquals(
        "Strict",
        access.get("samesite"),
        "ps_access is not SameSite=Strict, so a foreign page's request to this port"
            + " carries the operator's credential");
    assertEquals(
        "/v1",
        access.get("path"),
        "ps_access is not scoped to /v1, so it either misses the API or rides on"
            + " everything this server serves");
    assertEquals(
        String.valueOf(properties.getAccessLifetime().getSeconds()),
        access.get("max-age"),
        "ps_access does not carry the configured access lifetime — the refresh lifetime"
            + " here would be a fifteen-minute secret living seven days");
    assertFalse(
        access.containsKey("secure"),
        "ps_access is marked Secure on a plain-HTTP request, so a browser discards it and"
            + " the console on http://127.0.0.1 can never log in");

    Map<String, String> refresh = attributes(cookies.get("ps_refresh"));
    assertTrue(refresh.containsKey("httponly"), "ps_refresh is readable by JavaScript");
    assertEquals("Strict", refresh.get("samesite"), "ps_refresh is not SameSite=Strict");
    assertEquals(
        "/v1/auth",
        refresh.get("path"),
        "ps_refresh is not narrowed to the door, so the long-lived secret rides on every"
            + " ordinary API call — which is the one thing narrowing its Path buys");
    assertEquals(
        String.valueOf(properties.getRefreshLifetime().getSeconds()),
        refresh.get("max-age"),
        "ps_refresh does not carry the configured refresh lifetime");

    // And each cookie carries its own half of the pair, not the other's.
    // assertTrue and not assertEquals: see the class note on why a token is
    // never an operand of an assertion that prints its operands.
    assertTrue(
        "access-stand-in".equals(value(cookies.get("ps_access"))),
        "ps_access does not carry the access token");
    assertTrue(
        "refresh-stand-in".equals(value(cookies.get("ps_refresh"))),
        "ps_refresh does not carry the refresh token");
  }

  /**
   * {@code Secure} follows the scheme rather than being absent altogether.
   *
   * <p>The other half of the assertion above. Without this one, "not Secure on plain HTTP" is
   * satisfied by a controller that never sets it at all, and the design's "Secure-when-TLS" would
   * be a phrase with nothing behind it.
   */
  @Test
  void a_request_over_tls_gets_secure_cookies() throws Exception {
    TokenStore store = mock(TokenStore.class);
    when(store.spendBootstrap("boot")).thenReturn(true);
    when(store.issuePair()).thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth")
                    .secure(true)
                    .contentType("application/json")
                    .content("{\"token\":\"boot\"}"))
            .andReturn()
            .getResponse();

    Map<String, String> cookies = setCookies(answer);
    assertTrue(
        attributes(cookies.get("ps_access")).containsKey("secure"),
        "a cookie issued over TLS is not marked Secure, so a later plain-HTTP request to"
            + " the same host carries it in the clear");
    assertTrue(
        attributes(cookies.get("ps_refresh")).containsKey("secure"),
        "the refresh cookie issued over TLS is not marked Secure");
  }

  /**
   * Where each cookie is sent, and — the half that matters — where it is not.
   *
   * <p>{@link AuthController#REFRESH_PATH} used to carry a sentence claiming this was "measured
   * rather than reasoned, against a real server on a real port", by a {@code curl} session nothing
   * in this repository could repeat. The claim was right and the evidence was unrepeatable, so this
   * is the evidence.
   *
   * <p><b>Parsed by {@link okhttp3.Cookie}, not by this file.</b> RFC 6265's path match is
   * prefix-plus-a-boundary and the boundary is the whole subject here; an assertion written against
   * a hand-rolled {@code startsWith} would be measuring this test's idea of the rule against the
   * controller's, and both could be wrong together. okhttp is already a dependency of this module,
   * its cookie jar is what a real HTTP client would apply, and it did not learn the rule from
   * anything in this tree.
   *
   * <p>{@code /v1/authorize} is the case worth pinning: a plain prefix test would send the
   * long-lived refresh secret there, because {@code /v1/auth} is a prefix of it. No such route
   * exists today, which is exactly why nothing else would catch a {@code REFRESH_PATH} widened to
   * {@code /v1} by someone fixing a rotation bug.
   */
  @Test
  void the_cookie_paths_match_the_requests_they_are_for() throws Exception {
    TokenStore store = mock(TokenStore.class);
    when(store.spendBootstrap("boot")).thenReturn(true);
    when(store.issuePair()).thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    HttpUrl origin = HttpUrl.get("http://127.0.0.1:8091/");

    Map<String, String> headers =
        setCookies(exchange(store, new AuthProperties(), "{\"token\":\"boot\"}"));

    okhttp3.Cookie access = okhttp3.Cookie.parse(origin, headers.get("ps_access"));
    okhttp3.Cookie refresh = okhttp3.Cookie.parse(origin, headers.get("ps_refresh"));
    assertNotNull(access, "an RFC 6265 parser could not read the ps_access header at all");
    assertNotNull(refresh, "an RFC 6265 parser could not read the ps_refresh header at all");

    // The refresh cookie reaches the rotation and nothing else.
    assertTrue(
        refresh.matches(origin.resolve("/v1/auth/refresh")),
        "the refresh cookie is not sent to /v1/auth/refresh, so a console can never"
            + " rotate and every session dies at the access lifetime");
    assertTrue(
        refresh.matches(origin.resolve("/v1/auth")),
        "the refresh cookie is not sent to the exchange it is scoped to");
    assertFalse(
        refresh.matches(origin.resolve("/v1/jobs")),
        "the seven-day secret rides on ordinary API calls, which is the one thing"
            + " narrowing its Path buys");
    assertFalse(
        refresh.matches(origin.resolve("/v1/authorize")),
        "the path match is a plain prefix rather than prefix-with-a-boundary, so the"
            + " long-lived secret is sent to any route whose name merely starts with"
            + " auth");
    assertFalse(
        refresh.matches(origin.resolve("/")),
        "the refresh cookie is sent to the console page itself");

    // The access cookie reaches the whole API, both upgrades included.
    assertTrue(
        access.matches(origin.resolve("/v1/jobs")),
        "the access cookie is not sent to the API, so the console logs in and then"
            + " renders nothing");
    assertTrue(
        access.matches(origin.resolve("/v1/events")),
        "the access cookie is not sent to the events socket, and a page cannot set a"
            + " header on a WebSocket upgrade");
    assertTrue(
        access.matches(origin.resolve("/v1/files")),
        "the access cookie is not sent to the files socket");
    assertFalse(
        access.matches(origin.resolve("/")),
        "the access cookie rides on the console page and every static asset under it");
  }

  /**
   * A request with no {@code Content-Type} at all, which is the shape the 401 above is really
   * about.
   *
   * <p><b>Run, because {@link AuthController#exchange} declares {@code consumes = application/json}
   * and a reader can reasonably expect that to answer 415 before the handler ever sees the
   * request.</b> It does not, and the reason is worth having written down: Spring copies {@code
   * required = false} off the {@code @RequestBody} parameter onto the consumes condition, and a
   * consumes condition whose body is not required is skipped entirely for a request that
   * <em>has</em> no body. So the handler runs, {@code presented} is null, and the answer is the
   * ordinary 401.
   *
   * <p>The third case is what pins that mechanism rather than merely observing the first two: a
   * POST with no {@code Content-Type} and no bytes, sent chunked so that it has a body as far as
   * the container is concerned, is a 415 where the same request sent with {@code Content-Length: 0}
   * is a 401. The switch is "does this request have a body", not "did it name a type". That third
   * case runs over a real socket because it is a statement about framing, and {@link MockMvc} has
   * no framing to speak of.
   */
  @Test
  void no_content_type_is_a_401_without_a_body_and_a_415_with_one() throws Exception {
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc = standalone(store, new AuthProperties());

    assertEquals(
        401,
        mvc.perform(post("/v1/auth")).andReturn().getResponse().getStatus(),
        "a POST carrying neither a credential nor a Content-Type got something other"
            + " than the ordinary answer for a request carrying no credential");
    assertEquals(
        415,
        mvc.perform(post("/v1/auth").content(body("0".repeat(48))))
            .andReturn()
            .getResponse()
            .getStatus(),
        "a body sent without a Content-Type was read as JSON anyway");
    verify(store, never()).spendBootstrap(anyString());

    List<ILoggingEvent> said = new ArrayList<>();
    OkHttpClient http = new OkHttpClient.Builder().build();
    ConfigurableApplicationContext context = null;
    try {
      context = boot(said, "--server.port=0", "--server.address=127.0.0.1");
      String url =
          "http://localhost:"
              + ((WebServerApplicationContext) context).getWebServer().getPort()
              + "/v1/auth";
      try (Response empty =
          http.newCall(
                  new Request.Builder()
                      .url(url)
                      .post(RequestBody.create(new byte[0], null))
                      .build())
              .execute()) {
        assertEquals(
            401,
            empty.code(),
            "over a real socket, a POST with no Content-Type and no body answered"
                + " something other than the 401 measured through MockMvc");
      }
      try (Response chunked =
          http.newCall(new Request.Builder().url(url).post(CHUNKED_AND_EMPTY).build()).execute()) {
        assertEquals(
            415,
            chunked.code(),
            "a chunked empty POST answered the same as a Content-Length: 0 one, so"
                + " the consumes condition is not turning on whether the request"
                + " has a body and the paragraph on exchange() saying it does is"
                + " wrong");
      }
    } finally {
      http.dispatcher().executorService().shutdown();
      http.connectionPool().evictAll();
      if (context != null) {
        context.close();
      }
    }
  }

  // --- what the door refuses ----------------------------------------------

  /**
   * Single-use, measured against the real store rather than a stub.
   *
   * <p>A mock told to answer {@code false} the second time would assert what the test itself
   * arranged. This one mints, spends and spends again through {@link TokenStore}, so what is under
   * test is the property the whole secret-in-a-URL decision rests on.
   */
  @Test
  void a_bootstrap_token_spent_twice_is_refused_the_second_time() throws Exception {
    TokenStore store = realStore();
    AuthProperties properties = new AuthProperties();
    String bootstrap = store.mintBootstrap();

    MockHttpServletResponse first = exchange(store, properties, body(bootstrap));
    MockHttpServletResponse second = exchange(store, properties, body(bootstrap));

    assertEquals(204, first.getStatus(), "a freshly minted bootstrap token was refused");
    assertEquals(
        401,
        second.getStatus(),
        "the bootstrap token was accepted twice. It is single-use, and single-use is the"
            + " whole of what makes putting it in a URL defensible");
    assertTrue(setCookies(second).isEmpty(), "the refused exchange still set cookies");
  }

  @Test
  void a_value_that_is_not_the_bootstrap_token_is_refused() throws Exception {
    TokenStore store = realStore();
    store.mintBootstrap();

    MockHttpServletResponse answer = exchange(store, new AuthProperties(), body("0".repeat(48)));

    assertEquals(401, answer.getStatus());
    assertTrue(setCookies(answer).isEmpty());
  }

  @Test
  void an_exchange_with_no_body_at_all_is_refused() throws Exception {
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(post("/v1/auth").contentType("application/json")).andReturn().getResponse();

    assertEquals(
        401,
        answer.getStatus(),
        "a request carrying no credential got something other than the ordinary answer"
            + " for a request carrying no credential");
    verify(store, never()).issuePair();
  }

  /**
   * A body that is not JSON is a 400, and that is deliberate rather than overlooked.
   *
   * <p>{@link AuthController} says so, so it is measured here rather than assumed: this repository
   * has produced javadoc explaining framework behaviour its author never ran. The distinction it
   * draws is about syntax and not about credentials — it tells a caller their body was malformed
   * and nothing whatever about whether a token was ever real — so it is not the oracle the 401
   * refuses to be.
   */
  @Test
  void a_body_that_is_not_json_is_a_four_hundred_and_asks_the_store_nothing() throws Exception {
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(post("/v1/auth").contentType("application/json").content("this is not json"))
            .andReturn()
            .getResponse();

    assertEquals(
        400,
        answer.getStatus(),
        "a malformed body answered something other than the 400 AuthController's javadoc"
            + " says it does");
    verify(store, never()).spendBootstrap(anyString());
  }

  /**
   * No cookie means no question asked of the store.
   *
   * <p>This endpoint is unauthenticated by necessity — {@link AuthFilter#OPEN} names it, because a
   * gate that refused the way through it could never be passed — so anyone who can reach the port
   * can post to it. Hashing a value that is not there would be work this server does on their
   * behalf, and {@link TokenStore}'s sweep placement is argued around exactly that.
   */
  @Test
  void refreshing_without_a_refresh_cookie_is_refused_and_does_not_consult_the_store()
      throws Exception {
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(post("/v1/auth/refresh")).andReturn().getResponse();

    assertEquals(401, answer.getStatus());
    verify(store, never()).refresh(anyString());
  }

  /**
   * A rotation hands back a fresh pair, and the old refresh token is spent.
   *
   * <p>The real store, because single-use and chain retirement are its behaviour and a stub would
   * only echo this file's own expectations back.
   */
  @Test
  void refreshing_rotates_both_cookies_and_spends_the_token_presented() throws Exception {
    TokenStore store = realStore();
    AuthProperties properties = new AuthProperties();
    TokenStore.Pair first = store.issuePair();

    MockHttpServletResponse rotated = refresh(store, properties, first.refresh());

    assertEquals(204, rotated.getStatus(), "a live refresh token was not exchanged");
    Map<String, String> cookies = setCookies(rotated);
    assertEquals(
        List.of("ps_access", "ps_refresh"),
        new ArrayList<>(cookies.keySet()),
        "a rotation set something other than both cookies — the design rotates both, so"
            + " an answer carrying one of them leaves the console half-renewed");
    assertFalse(
        first.access().equals(value(cookies.get("ps_access"))),
        "the rotation handed back the access token that was already held");
    assertFalse(
        first.refresh().equals(value(cookies.get("ps_refresh"))),
        "the rotation handed back the refresh token that was just spent");
    assertTrue(
        store.validAccess(value(cookies.get("ps_access"))),
        "the access token a rotation issued does not authenticate anything");

    // Presenting it again is reuse, and reuse retires the chain. One status
    // for that and for every other way of declining: see TokenStore.refresh.
    MockHttpServletResponse replayed = refresh(store, properties, first.refresh());
    assertEquals(401, replayed.getStatus(), "a spent refresh token was accepted a second time");
  }

  @Test
  void a_refresh_cookie_that_names_nothing_is_refused() throws Exception {
    TokenStore store = realStore();

    MockHttpServletResponse answer = refresh(store, new AuthProperties(), "0".repeat(48));

    assertEquals(401, answer.getStatus());
    assertTrue(setCookies(answer).isEmpty());
  }

  /**
   * A planted duplicate does not lock the console out.
   *
   * <p>Cookies are not port-scoped, so a sibling application on another localhost port can set a
   * second {@code ps_refresh} in the operator's browser and this server does not choose the order
   * they arrive in. {@link AuthFilter} had exactly this defect for {@code ps_access} and it was
   * measured as a denial of service; both orderings are asserted here for the reason that file
   * gives — one of them passes under the defect.
   */
  @Test
  void a_duplicate_refresh_cookie_does_not_lock_a_live_session_out() throws Exception {
    AuthProperties properties = new AuthProperties();

    TokenStore strayFirst = realStore();
    TokenStore.Pair one = strayFirst.issuePair();
    assertEquals(
        204,
        refresh(strayFirst, properties, "0".repeat(48), one.refresh()).getStatus(),
        "a stray ps_refresh sorted ahead of the real one refused the rotation, so anyone"
            + " able to set a cookie on this host can stop the console renewing");

    TokenStore strayLast = realStore();
    TokenStore.Pair two = strayLast.issuePair();
    assertEquals(
        204,
        refresh(strayLast, properties, two.refresh(), "0".repeat(48)).getStatus(),
        "a stray ps_refresh sorted behind the real one refused the rotation");

    TokenStore neither = realStore();
    neither.issuePair();
    assertEquals(
        401,
        refresh(neither, properties, "0".repeat(48), "1".repeat(48)).getStatus(),
        "two values, neither of them a token, were accepted — collecting every cookie has"
            + " turned into accepting one");
  }

  // --- the password door: POST /v1/auth/login -------------------------------

  @Test
  void a_correct_password_answers_204_and_sets_both_cookies() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("correct horse battery staple".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    when(store.issuePair(anyString(), anyBoolean(), anyString()))
        .thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .content(login("root", "correct horse battery staple")))
            .andReturn()
            .getResponse();

    assertEquals(204, answer.getStatus(), "a correct password was not accepted");
    assertEquals(
        List.of("ps_access", "ps_refresh"),
        new ArrayList<>(setCookies(answer).keySet()),
        "a correct password did not set exactly the two cookies POST /v1/auth sets");
    assertEquals(
        "",
        answer.getContentAsString(),
        "a caller that did not ask for a JSON body got one anyway");
  }

  @Test
  void a_wrong_password_answers_401_and_sets_no_cookies() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("the-real-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .content(login("root", "not-the-password")))
            .andReturn()
            .getResponse();

    assertEquals(401, answer.getStatus(), "a wrong password was accepted");
    assertTrue(setCookies(answer).isEmpty(), "a refused login still set cookies");
    verify(store, never()).issuePair(anyString(), anyBoolean(), anyString());
  }

  /**
   * The security requirement this file exists to pin: a handle {@link AdminStore} has never heard
   * of answers exactly like a handle it knows with the wrong password, in status and in body — the
   * shape a caller cannot probe apart. {@link
   * #an_unknown_handle_is_hashed_against_before_any_answer_is_given()} is the other half, pinning
   * that the two also cost the same work rather than only looking the same on the wire.
   */
  @Test
  void an_unknown_handle_answers_the_same_shape_as_a_wrong_password() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("the-real-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    when(admins.byHandle("nobody")).thenReturn(Optional.empty());
    TokenStore store = mock(TokenStore.class);

    MockHttpServletResponse wrongPassword =
        standalone(store, new AuthProperties(), admins, hasher, freshAttempts())
            .perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .content(login("root", "not-it")))
            .andReturn()
            .getResponse();
    MockHttpServletResponse unknownHandle =
        standalone(store, new AuthProperties(), admins, hasher, freshAttempts())
            .perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .content(login("nobody", "not-it")))
            .andReturn()
            .getResponse();

    assertEquals(
        wrongPassword.getStatus(),
        unknownHandle.getStatus(),
        "an unknown handle and a wrong password answer with different statuses, so an"
            + " attacker can tell the two apart");
    assertEquals(
        wrongPassword.getContentAsString(),
        unknownHandle.getContentAsString(),
        "an unknown handle and a wrong password answer with different bodies");
    assertTrue(setCookies(wrongPassword).isEmpty());
    assertTrue(setCookies(unknownHandle).isEmpty());
  }

  /**
   * The half {@code assertEquals} on a status and a body cannot pin: that an unknown handle is not
   * answered <em>faster</em> because nothing was checked. A mock {@link PasswordHasher} stands in
   * for a real one so the assertion is "{@code matches} was called" rather than a wall-clock
   * measurement, which would be the flaky version of the same claim.
   */
  @Test
  void an_unknown_handle_is_hashed_against_before_any_answer_is_given() throws Exception {
    PasswordHasher hasher = mock(PasswordHasher.class);
    when(hasher.matches(any(char[].class), anyString())).thenReturn(false);
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("nobody")).thenReturn(Optional.empty());
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    mvc.perform(
            post("/v1/auth/login")
                .contentType("application/json")
                .content(login("nobody", "whatever")))
        .andReturn();

    verify(hasher).matches(any(char[].class), eq(AuthController.DUMMY_PASSWORD_HASH));
  }

  @Test
  void the_throttle_refuses_after_too_many_failures_even_with_the_right_password()
      throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("correct-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    for (int i = 0; i < new AuthProperties().getLoginMaxFailures(); i++) {
      MockHttpServletResponse failed =
          mvc.perform(
                  post("/v1/auth/login")
                      .contentType("application/json")
                      .content(login("root", "wrong")))
              .andReturn()
              .getResponse();
      assertEquals(401, failed.getStatus());
    }

    MockHttpServletResponse lockedOut =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .content(login("root", "correct-password")))
            .andReturn()
            .getResponse();

    assertEquals(
        401,
        lockedOut.getStatus(),
        "the throttle did not refuse a correct password once the failure limit was" + " reached");
    verify(store, never()).issuePair(anyString(), anyBoolean(), anyString());
  }

  @Test
  void a_caller_that_asks_for_the_body_gets_the_pair_and_the_cookies() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("correct-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    when(store.issuePair(anyString(), anyBoolean(), anyString()))
        .thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .header(
                        AuthController.TOKEN_DELIVERY_HEADER, AuthController.TOKEN_DELIVERY_BODY)
                    .content(login("root", "correct-password")))
            .andReturn()
            .getResponse();

    assertEquals(
        200,
        answer.getStatus(),
        "a caller that asked for the pair in the body did not get 200 — 204 cannot carry"
            + " a body");
    assertEquals(
        List.of("ps_access", "ps_refresh"),
        new ArrayList<>(setCookies(answer).keySet()),
        "asking for the body cost the caller the cookies a browser relies on");
    String body = answer.getContentAsString();
    assertTrue(body.contains("access-stand-in"), "the JSON body did not carry the access token");
    assertTrue(body.contains("refresh-stand-in"), "the JSON body did not carry the refresh token");
  }

  /**
   * The regression this file exists to catch: the endpoint's first cut used {@code Accept} as the
   * body-delivery signal, and axios's own documented default request header — sent by any page
   * built on it, with nobody deciding this endpoint should behave differently — named {@code
   * application/json} concretely enough to trip it. That would have handed both tokens to a
   * browser's own JavaScript in a body, defeating {@code HttpOnly} for exactly the class of
   * attacker it exists to survive. See {@link AuthController}'s class note "How a non-browser
   * caller asks for the pair in the body" for the finding in full.
   */
  @Test
  void axioss_default_accept_header_does_not_leak_the_pair_into_a_body() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("correct-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    when(store.issuePair(anyString(), anyBoolean(), anyString()))
        .thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    // axios's literal, documented default Accept header — no
    // X-Plowshare-Token-Delivery, because axios does not send one and
    // never has.
    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .header("Accept", "application/json, text/plain, */*")
                    .content(login("root", "correct-password")))
            .andReturn()
            .getResponse();

    assertEquals(
        204,
        answer.getStatus(),
        "axios's default Accept header was read as a request for the pair in the body,"
            + " which is exactly the leak this test exists to catch");
    assertEquals(
        "",
        answer.getContentAsString(),
        "the response carried a body despite no X-Plowshare-Token-Delivery header, so"
            + " axios's default Accept header put the tokens where a browser's own"
            + " JavaScript can read them");
    assertEquals(
        List.of("ps_access", "ps_refresh"),
        new ArrayList<>(setCookies(answer).keySet()),
        "cookies are still owed regardless of what Accept said");
  }

  /**
   * A near miss on the header's value is not the header. jQuery's own default {@code Accept} names
   * {@code application/json} too, at a low quality value, which is the same hazard axios's default
   * is — named here to state plainly that neither a library's default {@code Accept} nor a
   * near-miss value on the real signal is read as "yes".
   */
  @Test
  void a_near_miss_on_the_delivery_header_s_value_does_not_ask_for_the_body() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("correct-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    when(store.issuePair(anyString(), anyBoolean(), anyString()))
        .thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header(AuthController.TOKEN_DELIVERY_HEADER, "bodies-please")
                    .content(login("root", "correct-password")))
            .andReturn()
            .getResponse();

    assertEquals(
        204,
        answer.getStatus(),
        "a value other than the exact one this endpoint checks for was read as a request"
            + " for the pair in the body");
    assertEquals("", answer.getContentAsString());
  }

  /**
   * The decision this endpoint makes about {@code must_change_password}: login still succeeds, but
   * the session it grants cannot be rotated forward — an expired {@code ps_refresh} rather than a
   * live one — and the response says why, rather than either refusing the login outright or staying
   * silent about the flag while handing out a session as good as any other's.
   *
   * <p>The cookie is expired rather than simply absent: a browser that already held a live {@code
   * ps_refresh} from an earlier, unflagged exchange must not keep rotating it just because this
   * response never mentioned the name. See {@code AuthController}'s class note on {@code
   * must_change_password} for the finding this shape closes.
   */
  @Test
  void a_login_by_an_admin_who_must_still_change_their_password_gets_no_refresh_cookie()
      throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("correct-password".toCharArray()),
                    true,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    when(store.issuePair(anyString(), anyBoolean(), anyString()))
        .thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .content(login("root", "correct-password")))
            .andReturn()
            .getResponse();

    assertEquals(
        204,
        answer.getStatus(),
        "an admin who still must change their password was refused login outright, which"
            + " is more than this slice enforces — see AuthController's javadoc on"
            + " must_change_password");
    Map<String, String> cookies = setCookies(answer);
    assertEquals(
        List.of("ps_access", "ps_refresh"),
        new ArrayList<>(cookies.keySet()),
        "a login for an admin who must still change their password did not answer with"
            + " both cookie names — ps_refresh must still be present, expired, so a"
            + " browser holding a live one from an earlier exchange is told to drop it");
    assertEquals(
        "0",
        attributes(cookies.get("ps_refresh")).get("max-age"),
        "the ps_refresh cookie on a flagged login was not expired (Max-Age=0), so a"
            + " browser already holding a live one from an earlier exchange keeps"
            + " rotating it");
    assertEquals(
        "true",
        answer.getHeader(AuthController.MUST_CHANGE_PASSWORD_HEADER),
        "the response did not say this admin must still change their password, so"
            + " nothing on the other end has any way to know to prompt for one");
    assertEquals(
        "",
        answer.getContentAsString(),
        "a cookie-only login for a flagged admin still wrote a body");
  }

  /**
   * The JSON-body half of the test above: {@code refresh} is {@code null} rather than the token
   * {@link TokenStore#issuePair(boolean)} actually minted, because nothing that calls this endpoint
   * may ever hold it.
   */
  @Test
  void a_flagged_admin_asking_for_the_body_gets_a_null_refresh_field() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("correct-password".toCharArray()),
                    true,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    when(store.issuePair(anyString(), anyBoolean(), anyString()))
        .thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .header(
                        AuthController.TOKEN_DELIVERY_HEADER, AuthController.TOKEN_DELIVERY_BODY)
                    .content(login("root", "correct-password")))
            .andReturn()
            .getResponse();

    String body = answer.getContentAsString();
    assertTrue(
        body.contains("\"refresh\":null"),
        "a flagged admin's JSON body did not carry a null refresh field — got: " + body);
    assertFalse(
        body.contains("refresh-stand-in"),
        "a flagged admin's JSON body carried the refresh token TokenStore actually minted,"
            + " so a session that must not be rotated forward can be");
  }

  @Test
  void a_login_by_an_admin_with_a_real_password_does_not_say_it_must_change() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("correct-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = mock(TokenStore.class);
    when(store.issuePair(anyString(), anyBoolean(), anyString()))
        .thenReturn(new TokenStore.Pair("access-stand-in", "refresh-stand-in"));
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/login")
                    .contentType("application/json")
                    .content(login("root", "correct-password")))
            .andReturn()
            .getResponse();

    assertEquals("false", answer.getHeader(AuthController.MUST_CHANGE_PASSWORD_HEADER));
    assertEquals(
        List.of("ps_access", "ps_refresh"),
        new ArrayList<>(setCookies(answer).keySet()),
        "an admin with a real password did not get the ordinary two cookies");
  }

  @Test
  void a_login_with_no_body_at_all_is_refused_and_asks_nothing_of_the_store() throws Exception {
    AdminStore admins = mock(AdminStore.class);
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc =
        standalone(store, new AuthProperties(), admins, new PasswordHasher(), freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(post("/v1/auth/login").contentType("application/json"))
            .andReturn()
            .getResponse();

    assertEquals(401, answer.getStatus());
    verify(admins, never()).byHandle(anyString());
  }

  /**
   * The body of {@code POST /v1/auth/login}. Assembled rather than written as a literal for {@link
   * #body}'s reason: no test in this file should hold a live secret as a string constant, even a
   * made-up one meant to be wrong.
   */
  private static String login(String handle, String password) {
    return "{\"handle\":\"" + handle + "\",\"password\":\"" + password + "\"}";
  }

  /**
   * A {@link LoginAttempts} at the shipping defaults — {@link AuthProperties#getLoginMaxFailures()}
   * and {@link AuthProperties#getLoginLockout()} — fresh for whichever test asks, rather than a
   * copy of the numbers held as a constant in this file.
   */
  private static LoginAttempts freshAttempts() {
    AuthProperties defaults = new AuthProperties();
    return new LoginAttempts(
        Clock.systemUTC(), defaults.getLoginMaxFailures(), defaults.getLoginLockout());
  }

  // --- the ticket door, and the one restriction on it: FIX 6 ---------------

  @Test
  void an_unrestricted_session_can_mint_a_ticket() throws Exception {
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair();
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(post("/v1/auth/ticket").header("Authorization", "Bearer " + pair.access()))
            .andReturn()
            .getResponse();

    assertEquals(
        200,
        answer.getStatus(),
        "an ordinary session was refused a ticket, which is more than this fix closes");
    assertTrue(
        answer.getContentAsString().contains("ticket"),
        "the ticket response did not carry a ticket: " + answer.getContentAsString());
  }

  /**
   * The finding this pins: a ticket minted from a flagged admin's access token has no lifetime tied
   * to that access token at all, so it could hold the events socket open well past the fifteen
   * minutes that is supposed to bound such a session. {@link TokenStore#issuePair(boolean)} with
   * {@code true} is exactly what {@link AuthController#login} calls for such an admin — see that
   * method's own test on {@code must_change_password} — so this drives the ticket endpoint the same
   * way a real flagged login would.
   */
  @Test
  void a_restricted_session_is_refused_a_ticket() throws Exception {
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair(true);
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(post("/v1/auth/ticket").header("Authorization", "Bearer " + pair.access()))
            .andReturn()
            .getResponse();

    assertEquals(
        403,
        answer.getStatus(),
        "a session for an admin who must still change their password was allowed to mint"
            + " a ticket, which could hold the events socket open well past the"
            + " access lifetime that is supposed to bound such a session");
  }

  /**
   * The restriction is per-chain, not global: a second, ordinary session is unaffected by a first
   * one being restricted.
   */
  @Test
  void a_restricted_session_does_not_refuse_a_ticket_for_a_different_session() throws Exception {
    TokenStore store = realStore();
    store.issuePair(true);
    TokenStore.Pair ordinary = store.issuePair(false);
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(post("/v1/auth/ticket").header("Authorization", "Bearer " + ordinary.access()))
            .andReturn()
            .getResponse();

    assertEquals(200, answer.getStatus());
  }

  /**
   * The cookie transport is asked too, not only the header — {@code AuthFilter}'s own precedence
   * rule, mirrored here since {@link AuthController#ticket} has to ask the same question of the
   * same request the filter already let through.
   */
  @Test
  void a_restricted_session_presented_as_a_cookie_is_also_refused_a_ticket() throws Exception {
    TokenStore store = realStore();
    AuthProperties properties = new AuthProperties();
    TokenStore.Pair pair = store.issuePair(true);
    MockMvc mvc = standalone(store, properties);

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/ticket")
                    .cookie(
                        new jakarta.servlet.http.Cookie(
                            properties.getAccessCookie(), pair.access())))
            .andReturn()
            .getResponse();

    assertEquals(403, answer.getStatus());
  }

  // --- the session probe: GET /v1/auth/session -----------------------------

  /**
   * The reason this endpoint exists at all, half of it: a console on page load has no login
   * response left to read {@link AuthController#MUST_CHANGE_PASSWORD_HEADER} from, so it asks this
   * instead. The unauthenticated case — no credential at all — belongs to {@code AuthFilterTest},
   * not here: {@link AuthFilter} refuses before this controller is ever entered, so there is no 401
   * branch in {@link AuthController#session} for this file to drive.
   */
  @Test
  void tells_an_authenticated_caller_it_has_a_session() throws Exception {
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair();
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(get("/v1/auth/session").header("Authorization", "Bearer " + pair.access()))
            .andReturn()
            .getResponse();

    assertEquals(
        204,
        answer.getStatus(),
        "an authenticated caller with an ordinary session was not told it has one");
    assertEquals(
        "false",
        answer.getHeader(AuthController.MUST_CHANGE_PASSWORD_HEADER),
        "an ordinary session was reported as needing a password change");
  }

  /**
   * The reason this endpoint exists at all, the other half: a flagged admin's session has no
   * refresh cookie, so it dies at the fifteen-minute access lifetime with nothing to rotate it —
   * and on a reload the console has no login response to read the flag from. This is the one place
   * that tells it, from the same chain-restriction bit {@link AuthController#ticket} already reads.
   */
  @Test
  void tells_a_flagged_admin_that_it_must_still_change_its_password() throws Exception {
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair(true);
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(get("/v1/auth/session").header("Authorization", "Bearer " + pair.access()))
            .andReturn()
            .getResponse();

    assertEquals(204, answer.getStatus());
    assertEquals(
        "true",
        answer.getHeader(AuthController.MUST_CHANGE_PASSWORD_HEADER),
        "a flagged admin's session was not reported as needing a password change, so a"
            + " console reloading against this endpoint would drop it at the shell"
            + " with a session that dies in fifteen minutes");
  }

  /**
   * Probing is not a way to stay signed in. Asserted two ways: no {@code Set-Cookie} comes back at
   * all — nothing is issued, rotated or expired — and the presented access token still validates
   * against a real {@link TokenStore} afterwards, which a rotation or a revocation would both have
   * broken.
   */
  @Test
  void changes_nothing_about_the_session_it_reports_on() throws Exception {
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair();
    MockMvc mvc = standalone(store, new AuthProperties());

    MockHttpServletResponse answer =
        mvc.perform(get("/v1/auth/session").header("Authorization", "Bearer " + pair.access()))
            .andReturn()
            .getResponse();

    assertEquals(204, answer.getStatus());
    assertTrue(
        answer.getHeaders(HttpHeaders.SET_COOKIE).isEmpty(),
        "the session probe wrote a Set-Cookie header, so a caller polling it would be"
            + " issued, rotated or expired a credential rather than merely reported on");
    assertTrue(
        store.validAccess(pair.access()),
        "the access token presented to the session probe no longer validates against the"
            + " store afterwards, so the probe changed the very session it was asked"
            + " to report on rather than only reading it");
  }

  // --- the password-change door: POST /v1/auth/password, FIX 1 ------------

  /**
   * The gap this endpoint closes: before it existed, nothing could clear {@code
   * must_change_password} once set, so every seeded admin logged in forever on a fifteen-minute
   * access token with no refresh. This is the endpoint's whole success path in one test: the hash
   * is written, the flag-clearing write is the one {@link AdminStore} method asked for, the
   * caller's own session is revoked against a real {@link TokenStore} rather than a mock — a mock
   * could not tell this test whether {@link TokenStore#revoke(String)} was wired to anything real —
   * and both cookies come back expired.
   *
   * <p>The session is issued with a handle — {@link TokenStore#issuePair( String, boolean)} — and
   * the body carries no handle at all, which is the point of this whole task: {@link
   * AdminStore#changePassword} is still asked for {@code "root"}, and the only place that string
   * comes from now is the session, not the request.
   */
  @Test
  void a_correct_current_password_changes_it_and_ends_the_caller_s_session() throws Exception {
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair("root", false);
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("the-old-password".toCharArray()),
                    true,
                    OffsetDateTime.now())));
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    assertTrue(
        store.validAccess(pair.access()),
        "the fixture's own access token was not valid before the request, so this test"
            + " cannot tell a real revocation from a token that never worked");

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/password")
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + pair.access())
                    .content(changePassword("the-old-password", "a genuinely new one")))
            .andReturn()
            .getResponse();

    assertEquals(204, answer.getStatus(), answer.getContentAsString());
    verify(admins)
        .changePassword(
            eq("root"),
            argThat(
                hash ->
                    hash != null
                        && !hash.isBlank()
                        && hasher.matches("a genuinely new one".toCharArray(), hash)),
            anyString());
    Map<String, String> cookies = setCookies(answer);
    assertEquals(
        List.of("ps_access", "ps_refresh"),
        new ArrayList<>(cookies.keySet()),
        "a successful password change did not expire both cookies this class ever" + " writes");
    assertEquals("0", attributes(cookies.get("ps_access")).get("max-age"));
    assertEquals("0", attributes(cookies.get("ps_refresh")).get("max-age"));
    assertFalse(
        store.validAccess(pair.access()),
        "the access token used to change the password was still valid afterwards, so a"
            + " changed password did not end the session that changed it");
  }

  /**
   * Two admins in the fixture, a session issued for one of them, and its own current password in
   * the body: {@code alice}'s row changes and {@code bob}'s is asked for never.
   *
   * <p><b>This does not attempt the attack the finding was about, and should not be read as though
   * it did.</b> It asserts {@code alice}-changes-{@code alice} and {@code never()} on {@code bob} —
   * it cannot ask for {@code bob}'s row in the request at all, because {@link ChangePassword} no
   * longer has a field to name one with, and a body naming {@code "alice"} would pass this exact
   * assertion identically whether or not that field still existed. The security claim — that no
   * session can name a different admin's handle — rests on {@link ChangePassword} having no {@code
   * handle} field for a caller to fill in, which is a fact about the type this test cannot exercise
   * by calling the endpoint; it is verified by reading the record's declaration, not by this method
   * failing to compile against a body that once would have. What this test is worth having for is
   * the ordinary path with two rows in the fixture instead of one: that {@link
   * TokenStore#handleFor(String)} names {@code alice} and not {@code bob} for {@code alice}'s own
   * session, and that {@code bob}'s row is consequently never touched or even read.
   */
  @Test
  void a_session_for_one_admin_cannot_change_another_admin_s_password() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("alice"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "alice",
                    hasher.hash("alices-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    when(admins.byHandle("bob"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "bob",
                    hasher.hash("bobs-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = realStore();
    TokenStore.Pair aliceSession = store.issuePair("alice", false);
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/password")
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + aliceSession.access())
                    .content(changePassword("alices-password", "a genuinely new one")))
            .andReturn()
            .getResponse();

    assertEquals(204, answer.getStatus(), answer.getContentAsString());
    verify(admins).changePassword(eq("alice"), anyString(), anyString());
    verify(admins, never()).changePassword(eq("bob"), anyString(), anyString());
    verify(admins, never()).byHandle("bob");
  }

  /**
   * A session belonging to no account — the bootstrap and operator paths — is not an admin and has
   * no password to change, so it is refused 401 before {@link AdminStore} is asked anything at all.
   * {@link TokenStore#issuePair()} is exactly such a session: no handle was ever recorded for its
   * chain.
   */
  @Test
  void a_session_with_no_account_cannot_change_a_password() throws Exception {
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair();
    AdminStore admins = mock(AdminStore.class);
    MockMvc mvc =
        standalone(store, new AuthProperties(), admins, new PasswordHasher(), freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/password")
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + pair.access())
                    .content(changePassword("whatever-the-caller-guesses", "a new one")))
            .andReturn()
            .getResponse();

    assertEquals(401, answer.getStatus());
    verify(admins, never()).byHandle(anyString());
    assertTrue(
        store.validAccess(pair.access()),
        "a session with no account to change the password of was revoked anyway");
  }

  @Test
  void a_wrong_current_password_answers_401_and_changes_nothing() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("the-real-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair("root", false);
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/password")
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + pair.access())
                    .content(changePassword("not-the-password", "a new one entirely")))
            .andReturn()
            .getResponse();

    assertEquals(401, answer.getStatus());
    verify(admins, never()).changePassword(anyString(), anyString(), anyString());
    assertTrue(
        store.validAccess(pair.access()),
        "a wrong current password ended the session that presented it, which only a"
            + " correct one is supposed to do");
  }

  /**
   * The same indistinguishable-failure shape {@link #login} carries, applied to this endpoint: a
   * session whose handle {@link AdminStore} no longer recognises — its row deleted after the
   * session was issued, say — must cost the same Argon2id verification and answer the same way a
   * wrong password does.
   */
  @Test
  void a_session_for_an_unknown_handle_answers_the_same_shape_as_a_wrong_current_password_here_too()
      throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("the-real-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    when(admins.byHandle("nobody")).thenReturn(Optional.empty());
    TokenStore store = realStore();
    TokenStore.Pair rootSession = store.issuePair("root", false);
    TokenStore.Pair nobodySession = store.issuePair("nobody", false);

    MockHttpServletResponse wrongPassword =
        standalone(store, new AuthProperties(), admins, hasher, freshAttempts())
            .perform(
                post("/v1/auth/password")
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + rootSession.access())
                    .content(changePassword("not-it", "a new one entirely")))
            .andReturn()
            .getResponse();
    MockHttpServletResponse unknownHandle =
        standalone(store, new AuthProperties(), admins, hasher, freshAttempts())
            .perform(
                post("/v1/auth/password")
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + nobodySession.access())
                    .content(changePassword("not-it", "a new one entirely")))
            .andReturn()
            .getResponse();

    assertEquals(wrongPassword.getStatus(), unknownHandle.getStatus());
    assertEquals(wrongPassword.getContentAsString(), unknownHandle.getContentAsString());
  }

  @Test
  void a_blank_new_password_is_refused_with_400_and_changes_nothing() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("the-real-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair("root", false);
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/password")
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + pair.access())
                    .content(changePassword("the-real-password", "   ")))
            .andReturn()
            .getResponse();

    assertEquals(400, answer.getStatus());
    verify(admins, never()).changePassword(anyString(), anyString(), anyString());
  }

  /**
   * {@link PasswordPolicy}, shared with {@link AdminSeed}, is what this refuses against — not a
   * second list this endpoint keeps of its own.
   */
  @Test
  void an_obvious_placeholder_new_password_is_refused_with_400() throws Exception {
    PasswordHasher hasher = new PasswordHasher();
    AdminStore admins = mock(AdminStore.class);
    when(admins.byHandle("root"))
        .thenReturn(
            Optional.of(
                new AdminRecord(
                    "root",
                    hasher.hash("the-real-password".toCharArray()),
                    false,
                    OffsetDateTime.now())));
    TokenStore store = realStore();
    TokenStore.Pair pair = store.issuePair("root", false);
    MockMvc mvc = standalone(store, new AuthProperties(), admins, hasher, freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(
                post("/v1/auth/password")
                    .contentType("application/json")
                    .header("Authorization", "Bearer " + pair.access())
                    .content(changePassword("the-real-password", "changeme")))
            .andReturn()
            .getResponse();

    assertEquals(400, answer.getStatus());
    verify(admins, never()).changePassword(anyString(), anyString(), anyString());
  }

  @Test
  void a_password_change_with_no_body_at_all_is_refused_and_asks_nothing_of_the_store()
      throws Exception {
    AdminStore admins = mock(AdminStore.class);
    TokenStore store = mock(TokenStore.class);
    MockMvc mvc =
        standalone(store, new AuthProperties(), admins, new PasswordHasher(), freshAttempts());

    MockHttpServletResponse answer =
        mvc.perform(post("/v1/auth/password").contentType("application/json"))
            .andReturn()
            .getResponse();

    assertEquals(401, answer.getStatus());
    verify(admins, never()).byHandle(anyString());
    verify(store, never()).handleFor(anyString());
  }

  /**
   * The body of {@code POST /v1/auth/password} — no {@code handle} field, deliberately: see {@link
   * AuthController.ChangePassword}'s own class note for why it is gone rather than merely unread.
   * Assembled rather than written as a literal for {@link #body}'s reason.
   */
  private static String changePassword(String currentPassword, String newPassword) {
    return "{\"currentPassword\":\""
        + currentPassword
        + "\",\"newPassword\":\""
        + newPassword
        + "\"}";
  }

  // --- what the answers may not carry --------------------------------------

  /**
   * No body {@link AuthController#exchange} or {@link AuthController#refresh} writes carries a
   * token, in any shape.
   *
   * <p><b>Scoped to those two methods in its name, and not to the whole controller</b> — {@link
   * AuthController#login} is a deliberate exception to this since it grew a body-delivery path, and
   * a test named as though it covered the controller would have overclaimed while still passing.
   * {@code login}'s own cookie-only path is asserted empty separately, in {@link
   * #a_correct_password_answers_204_and_sets_both_cookies()} and {@link
   * #a_login_by_an_admin_who_must_still_change_their_password_gets_no_refresh_cookie()} — this
   * method is {@code exchange}/{@code refresh} alone.
   *
   * <p>Asserted as "there is no body", not as "the body does not contain the word token". A body
   * that said {@code {"access":"a1b2..."}} passes the second and is precisely the failure: a token
   * in a JSON body is a token in JavaScript's hands, which is the single thing {@code HttpOnly} was
   * chosen to prevent.
   */
  @Test
  void no_exchange_or_refresh_response_body_carries_a_token() throws Exception {
    TokenStore store = realStore();
    AuthProperties properties = new AuthProperties();
    String bootstrap = store.mintBootstrap();

    MockHttpServletResponse spent = exchange(store, properties, body(bootstrap));
    MockHttpServletResponse refused = exchange(store, properties, body(bootstrap));
    MockHttpServletResponse rotated = refresh(store, properties, refreshCookieOf(spent));
    MockHttpServletResponse declined = refresh(store, properties, "0".repeat(48));

    assertEquals(
        "",
        spent.getContentAsString(),
        "the successful exchange wrote a body. 204 and nothing is the contract, and the"
            + " cookie header is the only channel a credential travels on");
    assertEquals("", refused.getContentAsString(), "the refused exchange wrote a body");
    assertEquals("", rotated.getContentAsString(), "the successful rotation wrote a body");
    assertEquals("", declined.getContentAsString(), "the refused rotation wrote a body");
  }

  /**
   * The request body's record does not print what it carries.
   *
   * <p>The hazard {@code TokenStore.Pair} documents, one layer up: a record's generated {@code
   * toString} interpolates its components, and a handler argument is rendered by a debug line, an
   * exception, or Spring's own error page without anyone deciding to.
   */
  @Test
  void the_request_body_record_prints_no_token() {
    String secret = "0123456789abcdef";

    String printed = new AuthController.Exchange(secret).toString();

    assertFalse(
        printed.contains(secret),
        "the exchange body prints the bootstrap token it carries, so anything that"
            + " renders a handler argument writes a live credential");
  }

  // --- the file ------------------------------------------------------------

  @Test
  void the_operator_token_file_is_written_at_mode_600(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("console-token");

    AuthConfig.writeOperatorToken(file, "0123456789abcdef");

    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        "the console token does not get the handling ~/.config/plowshare/lm-key gets,"
            + " which is the whole of what protects it");
  }

  /**
   * A file already there is replaced, mode and contents together.
   *
   * <p>A restart writing a fresh token over a world-readable file left by anything else must not
   * leave it world-readable. The staged write gets this without a second code path — the mode
   * belongs to the file being renamed into place, so there is no existing file to narrow — and the
   * assertion stays because the property is about the result and not about how it is reached.
   */
  @Test
  void an_existing_token_file_has_its_mode_narrowed_before_anything_is_written(@TempDir Path tmp)
      throws Exception {
    Path file = tmp.resolve("console-token");
    Files.writeString(file, "stale");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    AuthConfig.writeOperatorToken(file, "0123456789abcdef");

    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        "a token was written into a file every process on this box can read");
    assertFalse(
        Files.readString(file).contains("stale"),
        "the previous contents survived, so the file holds two tokens and one of them is"
            + " dead");
  }

  /**
   * A symlink at the token path is replaced, not followed.
   *
   * <p><b>The hole the staged write closes.</b> The previous shape reached the existing-file case,
   * called {@link Files#setPosixFilePermissions} and then {@link Files#writeString}, and both of
   * those resolve links: with a symlink sitting at the path, the token went into the link's
   * <em>target</em> and the target was chmod'ed to 600. The mode is the whole protection on this
   * file, and under a symlink it landed on a path the operator never chose.
   *
   * <p>Not reachable at the default path — {@code ~/.config/plowshare/} is the operator's own
   * directory and is not writable by others — and reachable the moment {@code
   * --plowshare.auth.token-file} names a shared one. This runs entirely inside a {@link TempDir}
   * for the reason the class note gives.
   *
   * <p>The decoy is left world-readable so that a chmod reaching it is visible as a change of mode
   * and not only as a change of content.
   */
  @Test
  void a_symlink_at_the_token_path_is_replaced_rather_than_followed(@TempDir Path tmp)
      throws Exception {
    Path decoy = tmp.resolve("somebody-elses-file");
    Files.writeString(decoy, "not the token file");
    Files.setPosixFilePermissions(decoy, PosixFilePermissions.fromString("rw-r--r--"));
    Path file = tmp.resolve("console-token");
    Files.createSymbolicLink(file, decoy);

    AuthConfig.writeOperatorToken(file, "0123456789abcdef");

    assertEquals(
        "not the token file",
        Files.readString(decoy),
        "the write followed the symlink, so the operator token is sitting in a file"
            + " somebody else named");
    assertEquals(
        "rw-r--r--",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(decoy)),
        "the chmod followed the symlink, so mode 600 landed on a path the operator did"
            + " not choose and the token's only protection is on the wrong file");
    assertFalse(
        Files.isSymbolicLink(file),
        "the symlink is still there, so the next start writes through it again");
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        "the replacement is not at mode 600");
    assertTrue(
        "0123456789abcdef".equals(Files.readString(file)),
        "the token did not land at the path the operator configured");
  }

  /**
   * No half-written token and no leftover staging file: the directory holds the one file it is
   * supposed to. The staged name is a sibling, so a directory listing is where a leftover would
   * show.
   */
  @Test
  void the_staged_write_leaves_nothing_beside_the_token(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("console-token");

    AuthConfig.writeOperatorToken(file, "0123456789abcdef");
    AuthConfig.writeOperatorToken(file, "fedcba9876543210");

    try (var entries = Files.list(tmp)) {
      List<String> held = entries.map(each -> each.getFileName().toString()).sorted().toList();
      assertEquals(
          List.of("console-token"),
          held,
          "the staged write left a temporary file behind, so this directory accumulates"
              + " one dead token per restart");
    }
    assertTrue(
        "fedcba9876543210".equals(Files.readString(file)),
        "the second write did not replace the first");
  }

  // --- the announcement ----------------------------------------------------

  /**
   * One line, and the two credentials behind it are not the same credential.
   *
   * <p><b>The assertion that records the design gap.</b> If a later change goes back to putting the
   * bootstrap token in the file, the browser and the CLI race for one single-use secret and
   * whichever loses has none — and this fails.
   *
   * <p>The file now carries both: the operator token on the first line, and the console URL —
   * bootstrap token and all — on the second, because that URL had lived only in the log and so had
   * to be scraped out of it after every restart. That is a convenience and not a second credential:
   * the two lines are still the two different secrets this test exists to keep apart, and the order
   * is load-bearing because a reader taking the head of this file gets what it always got.
   */
  @Test
  void the_startup_line_and_the_file_carry_two_different_credentials(@TempDir Path tmp)
      throws Exception {
    TokenStore store = realStore();
    Path file = tmp.resolve("console-token");
    List<ILoggingEvent> said = new ArrayList<>();

    capturing(
        said,
        () -> {
          AuthConfig.announce(store, announcementProperties(), file);
          return null;
        });

    assertEquals(
        1,
        said.size(),
        "the startup announcement said something other than exactly one line, and this is"
            + " the only line in this server allowed to carry a secret");
    assertEquals(Level.INFO, said.get(0).getLevel());
    String line = said.get(0).getFormattedMessage();
    List<String> lines = Files.readAllLines(file);
    assertEquals(2, lines.size());
    String printed = lines.get(1).substring(lines.get(1).indexOf("?token=") + "?token=".length());
    assertTrue(line.contains("http://127.0.0.1:8091/"));
    assertFalse(line.contains(printed));
    assertFalse(line.contains(lines.getFirst()));
    String written = lines.get(0).strip();
    assertFalse(
        printed.equals(written),
        "the printed token and the file's token are the same secret. It is single-use, so"
            + " the browser and the CLI race for it and whichever loses cannot"
            + " authenticate at all — which is the contradiction in the design this"
            + " commit exists to resolve");
    assertTrue(
        store.validAccess(written),
        "the file does not hold a credential this server accepts, so the CLI has nothing"
            + " to present");
    assertTrue(
        store.spendBootstrap(printed),
        "the printed token is not the bootstrap token this store is holding, so the URL"
            + " in the console line cannot be exchanged");
  }

  /**
   * The operator token outlives every lifetime this server configures.
   *
   * <p>It has no expiry on purpose, and the reason is the case the file exists for: a detached
   * server left up past the refresh lifetime would answer the CLI 401 with a file on disk that
   * reads exactly like a good credential. {@link TokenStore#acceptOperator(String)} states what
   * that costs.
   */
  @Test
  void the_operator_token_does_not_age_out() {
    Ticking clock = new Ticking();
    AuthProperties properties = new AuthProperties();
    TokenStore store =
        new TokenStore(
            clock,
            properties.getAccessLifetime(),
            properties.getRefreshLifetime(),
            properties.getTicketLifetime());
    String operator = Tokens.mint();
    store.acceptOperator(operator);

    clock.advance(properties.getRefreshLifetime().multipliedBy(52));

    assertTrue(
        store.validAccess(operator),
        "the operator token expired, so a server left running for a year answers the CLI"
            + " 401 while the file on disk still looks like a credential");
  }

  /**
   * A write that fails records no grant, and that is a change from what this did.
   *
   * <p>{@link AuthConfig#announce} minted the operator token before writing it, so a failed write
   * left a <b>never-expiring, never-swept access grant</b> in the store — measured on the old shape
   * at {@link TokenStore#trackedRecords()} 0 before and 2 after — while the warning it logged in
   * the same breath told the operator the CLI had no credential. Nothing could present that grant,
   * because the only copy of its token was a local that went out of scope, but a store and a
   * warning disagreeing about whether a credential exists is not a state to leave lying around in
   * the class that mints them.
   *
   * <p>The failure is arranged with a parent that is a regular file rather than with a mode, so it
   * is a failure for the same reason on every machine including one running the build as root. The
   * successful half runs in the same test because {@code 0} on its own is also what a method that
   * did nothing at all would leave.
   */
  @Test
  void a_write_that_fails_leaves_no_operator_grant_behind(@TempDir Path tmp) throws Exception {
    TokenStore store = realStore();
    Path notADirectory = tmp.resolve("not-a-directory");
    Files.writeString(notADirectory, "");
    List<ILoggingEvent> said = new ArrayList<>();

    capturing(
        said,
        () -> {
          AuthConfig.announce(
              store, announcementProperties(), notADirectory.resolve("console-token"));
          return null;
        });

    assertEquals(
        0,
        store.trackedRecords(),
        "a failed write left an access grant in the store that no file holds, never"
            + " expires and no sweep drops — while the warning beside it says the CLI"
            + " has no credential");
    assertEquals(Level.WARN, said.get(0).getLevel(), "the failed write was not warned about");
    assertTrue(
        said.get(0).getFormattedMessage().contains(notADirectory.toString()),
        "the warning does not name the path that could not be written");

    // The control: the same call over a path that works does record one.
    TokenStore worked = realStore();
    AuthConfig.announce(worked, announcementProperties(), tmp.resolve("console-token"));
    assertEquals(
        2,
        worked.trackedRecords(),
        "a successful write recorded no operator grant either, so the assertion above is"
            + " passing on a method that does nothing");
  }

  // --- the real boot, and the CLI's own resolution -------------------------

  /** A real boot writes protected credentials that authenticate both bearer and cookie requests. */
  @Test
  void the_operator_token_a_real_boot_writes_authenticates_bearer_requests(@TempDir Path tmp)
      throws Exception {
    Path file = tmp.resolve("console-token");
    List<ILoggingEvent> said = new ArrayList<>();
    OkHttpClient http = new OkHttpClient.Builder().build();

    ConfigurableApplicationContext context = null;
    try {
      context =
          boot(
              said,
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--plowshare.auth.token-file=" + file);
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();

      assertEquals(
          "rw-------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
          "a real boot wrote the operator token at a mode other than 600");

      // THE CLI. Its own resolution, not this test reading the file.
      String operator = Files.readAllLines(file).getFirst().strip();
      assertNotNull(
          operator,
          "the client resolved no credential from the file the server just wrote, so"
              + " `plowshare` against a server on this machine is a 401");
      try (Response asOperator =
          httpGet(http, port, "/v1/jobs", "Authorization", "Bearer " + operator)) {
        assertEquals(
            200,
            asOperator.code(),
            "the token the server wrote for the CLI does not authenticate the CLI");
      }

      // THE BROWSER. The printed token, exchanged for cookies, and the
      // access cookie carried on a gated request the way a page carries it.
      String printed = Files.readAllLines(file).get(1).split("token=", 2)[1];
      try (Response exchanged =
          httpPost(http, port, "/v1/auth", "{\"token\":\"" + printed + "\"}")) {
        assertEquals(
            204,
            exchanged.code(),
            "the token in the startup URL could not be exchanged, so the line the"
                + " design tells an operator to click does nothing");
        assertEquals(
            "",
            exchanged.body() == null ? "" : exchanged.body().string(),
            "the exchange wrote a body over a real socket");
        String cookie =
            value(
                exchanged.headers(HttpHeaders.SET_COOKIE).stream()
                    .filter(header -> header.startsWith("ps_access="))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no ps_access cookie was set")));
        try (Response asBrowser =
            httpGet(http, port, "/v1/jobs", "Cookie", "ps_access=" + cookie)) {
          assertEquals(
              200,
              asBrowser.code(),
              "the cookie the exchange set does not authenticate a gated request,"
                  + " so the console logs in and then renders nothing");
        }
      }
    } finally {
      http.dispatcher().executorService().shutdown();
      http.connectionPool().evictAll();
      if (context != null) {
        context.close();
      }
    }
  }

  /**
   * The default is to write nothing and print nothing, and that is what keeps this suite off the
   * operator's own credential.
   *
   * <p>{@code plowshare.auth.token-file} is set by {@code PlowshareServerApplication.main} as a
   * default property, and a {@code @SpringBootTest} never calls {@code main}, and neither does a
   * {@code SpringApplicationBuilder} boot naming its own configuration class — so no test context
   * announces. <b>This is the assertion behind that sentence.</b> Without it, every
   * full-application test context in this repository would overwrite {@code
   * ~/.config/plowshare/console-token} and make whatever server the operator is running
   * unreachable, with nothing anywhere saying so.
   *
   * <p>It cannot assert on that file directly without reading it, so it asserts the two observable
   * consequences of the announcement never running: no line, and no bootstrap token in the store
   * for the door to spend.
   *
   * <p><b>The "no line" half is only worth anything because {@link #boot} is capturing at all</b>,
   * and the first version of this file proved it is not free: an appender attached before {@code
   * run()} is silently detached by Spring Boot's own logging initialisation, and this assertion
   * passed over an empty list for that reason rather than for its own. The test above, which needs
   * the line, is what caught it.
   */
  @Test
  void a_context_that_did_not_come_through_main_mints_nothing_and_writes_nothing()
      throws Exception {
    List<ILoggingEvent> said = new ArrayList<>();
    OkHttpClient http = new OkHttpClient.Builder().build();

    ConfigurableApplicationContext context = null;
    try {
      context = boot(said, "--server.port=0", "--server.address=127.0.0.1");
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();

      assertEquals(
          List.of(),
          said.stream().map(ILoggingEvent::getFormattedMessage).toList(),
          "a context that never went through main announced a console anyway. The"
              + " announcement writes a real path on this machine, so every"
              + " full-application context in this repository would overwrite the"
              + " operator's live credential");
      try (Response nothingToSpend =
          httpPost(http, port, "/v1/auth", "{\"token\":\"" + "0".repeat(48) + "\"}")) {
        assertEquals(401, nothingToSpend.code());
      }
    } finally {
      http.dispatcher().executorService().shutdown();
      http.connectionPool().evictAll();
      if (context != null) {
        context.close();
      }
    }
  }

  /**
   * The two halves agree where the file is.
   *
   * <p>The path is spelled twice — once in the server, once in the client — because {@code
   * plowshare-protocol} is the wire vocabulary the two exchange and where one machine keeps its own
   * credential is not part of it. This is what makes the duplication safe: it is a path and not a
   * secret, so {@code assertEquals} is allowed to print it, and the server module has the client on
   * its test classpath already.
   */
  @Test
  void operator_token_file_has_no_deployment_fallback() {
    assertEquals("", new AuthProperties().getTokenFile());
    assertEquals("", new AuthProperties().getConsoleOrigin());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> AuthConfig.announce(realStore(), new AuthProperties(), Path.of("unused")));
  }

  // --- fixtures ------------------------------------------------------------

  private static AuthProperties announcementProperties() {
    var properties = new AuthProperties();
    properties.setConsoleOrigin("http://127.0.0.1:8091");
    return properties;
  }

  private static TokenStore realStore() {
    AuthProperties properties = new AuthProperties();
    return new TokenStore(
        Clock.systemUTC(),
        properties.getAccessLifetime(),
        properties.getRefreshLifetime(),
        properties.getTicketLifetime());
  }

  /**
   * The two-argument shape every pre-existing test in this file uses, which knows nothing about a
   * login and does not need to: a mock {@link AdminStore} nothing calls, and a real,
   * cheap-to-construct {@link PasswordHasher} and {@link LoginAttempts} that never see a request
   * unless a test actually posts to {@code /v1/auth/login}.
   */
  private static MockMvc standalone(TokenStore store, AuthProperties properties) {
    return standalone(
        store, properties, mock(AdminStore.class), new PasswordHasher(), freshAttempts());
  }

  private static MockMvc standalone(
      TokenStore store,
      AuthProperties properties,
      AdminStore admins,
      PasswordHasher hasher,
      LoginAttempts attempts) {
    when(admins.changePassword(anyString(), anyString(), anyString())).thenReturn(true);
    return MockMvcBuilders.standaloneSetup(
            new AuthController(store, properties, admins, hasher, attempts))
        .build();
  }

  private static MockHttpServletResponse exchange(
      TokenStore store, AuthProperties properties, String json) throws Exception {
    return standalone(store, properties)
        .perform(post("/v1/auth").contentType("application/json").content(json))
        .andReturn()
        .getResponse();
  }

  private static MockHttpServletResponse refresh(
      TokenStore store, AuthProperties properties, String... presented) throws Exception {
    var request = post("/v1/auth/refresh");
    for (String each : presented) {
      request = request.cookie(new jakarta.servlet.http.Cookie("ps_refresh", each));
    }
    return standalone(store, properties).perform(request).andReturn().getResponse();
  }

  /**
   * The body {@code POST /v1/auth} takes. Assembled rather than written out so that no literal in
   * this file is ever a live token.
   */
  private static String body(String token) {
    return "{\"token\":\"" + token + "\"}";
  }

  private static String refreshCookieOf(MockHttpServletResponse answer) {
    return value(setCookies(answer).get("ps_refresh"));
  }

  /**
   * Every {@code Set-Cookie} header, by cookie name, in the order they were written.
   *
   * <p>Read off the header rather than off {@link MockHttpServletResponse#getCookie(String)}, so
   * that what is asserted is the bytes a browser receives and not a container's re-reading of them.
   */
  private static Map<String, String> setCookies(MockHttpServletResponse answer) {
    Map<String, String> byName = new LinkedHashMap<>();
    for (String header : answer.getHeaders(HttpHeaders.SET_COOKIE)) {
      byName.put(name(header), header);
    }
    return byName;
  }

  private static String name(String setCookie) {
    String pair = setCookie.split(";", 2)[0];
    return pair.substring(0, pair.indexOf('='));
  }

  private static String value(String setCookie) {
    String pair = setCookie.split(";", 2)[0];
    return pair.substring(pair.indexOf('=') + 1);
  }

  /**
   * The attributes of one {@code Set-Cookie}, keyed by lowercased name.
   *
   * <p>Lowercased because attribute names are case-insensitive and this file should not fail on a
   * framework that writes {@code HTTPOnly}. A valueless attribute — {@code HttpOnly}, {@code
   * Secure} — maps to the empty string, so "is it there" is {@code containsKey} and "what is it" is
   * {@code get}, and the two questions do not get confused.
   */
  private static Map<String, String> attributes(String setCookie) {
    Map<String, String> parsed = new LinkedHashMap<>();
    String[] parts = setCookie.split(";");
    for (int at = 1; at < parts.length; at++) {
      String part = parts[at].trim();
      int equals = part.indexOf('=');
      if (equals < 0) {
        parsed.put(part.toLowerCase(Locale.ROOT), "");
      } else {
        parsed.put(
            part.substring(0, equals).trim().toLowerCase(Locale.ROOT),
            part.substring(equals + 1).trim());
      }
    }
    return parsed;
  }

  /**
   * Starts {@link Wiring} on a port the OS picks, with {@link AuthConfig}'s logger captured into
   * {@code said}.
   *
   * <p><b>The appender is attached on {@code ApplicationPreparedEvent} and not before {@code
   * run()}, and that is measured rather than stylistic.</b> Attached beforehand it captured nothing
   * at all: Spring Boot's {@code LoggingApplicationListener} initialises the logging system while
   * the environment is being prepared, which resets the logback context and detaches every appender
   * added up to then. The console line was written, the file was written at mode 600, and the
   * captured list was empty. {@code ApplicationPreparedEvent} fires after that reset and before
   * {@code ApplicationReadyEvent}, which is when the announcement runs.
   */
  private static ConfigurableApplicationContext boot(List<ILoggingEvent> said, String... args) {
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    Logger configLog = (Logger) LoggerFactory.getLogger(AuthConfig.class);
    try {
      return new SpringApplicationBuilder(Wiring.class)
          .web(WebApplicationType.SERVLET)
          .listeners(
              (ApplicationListener<
                      org.springframework.boot.web.servlet.context
                          .ServletWebServerInitializedEvent>)
                  event ->
                      event
                          .getApplicationContext()
                          .getBean(AuthProperties.class)
                          .setConsoleOrigin("http://127.0.0.1:" + event.getWebServer().getPort()))
          .listeners(
              (ApplicationListener<ApplicationPreparedEvent>)
                  prepared -> {
                    captured.start();
                    configLog.addAppender(captured);
                  })
          .run(
              java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("--server.address=127.0.0.1"),
                      java.util.Arrays.stream(args)
                          .filter(arg -> !arg.startsWith("--server.address=")))
                  .toArray(String[]::new));
    } finally {
      configLog.detachAppender(captured);
      said.addAll(captured.list);
    }
  }

  /**
   * Runs {@code work} with {@link AuthConfig}'s logger captured into {@code said}, and hands back
   * whatever it produced. For work that is not a boot: see {@link #boot} for why a boot needs the
   * appender attached from inside its own lifecycle.
   */
  private static <T> T capturing(List<ILoggingEvent> said, Work<T> work) throws Exception {
    Logger configLog = (Logger) LoggerFactory.getLogger(AuthConfig.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    configLog.addAppender(captured);
    try {
      return work.run();
    } finally {
      configLog.detachAppender(captured);
      said.addAll(captured.list);
    }
  }

  @FunctionalInterface
  private interface Work<T> {
    T run() throws Exception;
  }

  private static Response httpGet(
      OkHttpClient http, int port, String path, String header, String value) throws IOException {
    return http.newCall(
            new Request.Builder()
                .url("http://localhost:" + port + path)
                .header(header, value)
                .build())
        .execute();
  }

  private static Response httpPost(OkHttpClient http, int port, String path, String json)
      throws IOException {
    return http.newCall(
            new Request.Builder()
                .url("http://localhost:" + port + path)
                .post(RequestBody.create(json, JSON))
                .build())
        .execute();
  }

  /**
   * A clock that moves only when a test moves it, for the reason {@code TokenStoreTest}'s does: the
   * shortest lifetime here is fifteen minutes.
   */
  private static final class Ticking extends Clock {

    private volatile java.time.Instant now = java.time.Instant.parse("2026-09-01T09:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public java.time.Instant instant() {
      return now;
    }

    void advance(java.time.Duration by) {
      now = now.plus(by);
    }
  }

  /**
   * A gated route, at a path the real application also publishes, so the end-to-end assertions are
   * about a route shaped like the ones being protected.
   */
  @RestController
  static class Probe {

    @GetMapping("/v1/jobs")
    String gated() {
      return "reached";
    }
  }

  /**
   * The web layer, the production auth wiring and one gated route.
   *
   * <p>{@link AuthConfig} and {@link AuthController} are the production classes, so what runs here
   * is the registration, the order and the announcement a real start uses. No WebSocket
   * autoconfiguration: this file's subject is the door and the file, and {@code AuthFilterTest} is
   * where the upgrade is measured.
   *
   * <p>{@link AdminStore} and {@link PasswordHasher} are supplied here as bare beans rather than
   * found by scanning for their own {@code @Repository}/{@code @Component} annotations, {@code
   * AuthFilterTest.Wiring}'s reason: this class is named explicitly rather than discovered by a
   * component scan, and a real {@link AdminStore} needs a {@link
   * org.springframework.jdbc.core.JdbcTemplate} this file has no Postgres to back. None of the
   * tests that boot this {@code Wiring} post to {@code /v1/auth/login}, so the mock is never asked
   * a question. {@link LoginAttempts} needs no bean here: {@link AuthConfig#loginAttempts()}
   * already supplies one.
   */
  @Configuration
  @Import({AuthConfig.class, AuthController.class, Probe.class})
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class
  })
  static class Wiring {

    @Bean
    AdminStore adminStore() {
      return mock(AdminStore.class);
    }

    @Bean
    PasswordHasher passwordHasher() {
      return new PasswordHasher();
    }
  }
}
