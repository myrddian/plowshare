package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.ws.EventChannelHandler;
import io.aeyer.plowshare.server.ws.FileChannelHandler;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place this server refuses a request.
 *
 * <p>Every auth task before this one shipped something nothing called: {@link Tokens} mints and
 * hashes, {@link TokenStore} decides what is still good, and until this class existed no code path
 * asked either of them anything. This is the commit where the answer is acted on, so every comment
 * in this repository that says the server has no authentication stopped being true here.
 *
 * <h2>A plain {@link Filter}, and what that buys</h2>
 *
 * <p>Not Spring Security, which is not a dependency of this project and would arrive with a
 * configuration surface that then has to be argued with for a single-user check. What a filter buys
 * beyond that is the property the design rests on: <b>a WebSocket upgrade is an ordinary HTTP
 * request before it is a socket</b>, so this one implementation covers {@code /v1/events}, {@code
 * /v1/files} and the whole REST API, and there is no second place where a path could be left
 * ungated.
 *
 * <p><b>That sentence was a claim about Tomcat and Spring, and it is now a measurement.</b> {@code
 * AuthFilterTest} dials a real WebSocket at {@code /v1/events} against a real Tomcat, wired from
 * {@code EventChannelConfig} and this filter, and asserts the upgrade is refused with a 401 and
 * that the handler is never entered. It fails if the ordering it describes ever stops holding. The
 * recurring defect in this slice has been a javadoc explaining a mechanism its author did not run,
 * and this file has since contributed two of its own — the paragraph below that claimed the header
 * never falls back to the cookie, and the one that named an accessor pair without naming the
 * mapping that makes it correct. Both were measured and both are now rewritten to what ran. This
 * paragraph is not one of them: it is the WebSocket ordering, and the test named above dials it.
 *
 * <h2>Two transports, one decision</h2>
 *
 * <p>{@code new WebSocket(url)} accepts a URL and a subprotocol list and nothing else, so <b>a
 * browser cannot set a header on an upgrade</b> — which is why there is a cookie at all. The CLI is
 * the other way round: it dials with okhttp and never receives a {@code Set-Cookie}, so it presents
 * {@code Authorization}. Both land here, and {@link TokenStore#validAccess} is the only question
 * this filter asks of anything.
 *
 * <p>The header is read first, and <b>only a {@code Bearer} one suppresses the cookie.</b> An
 * {@code Authorization} header in any other scheme is not a credential this server understands, so
 * it is treated as absent and the cookie is read instead. Measured against this filter on a real
 * Tomcat: {@code Authorization: Basic abc} carried alongside a valid {@code ps_access} is a 200,
 * and {@code Authorization: Bearer } followed by a value that is not a token is a 401 with the same
 * cookie present. The asymmetry is the intended one — a stray {@code Basic} header inserted by a
 * proxy must not lock a browser out of its own console, whereas a {@code Bearer} header is a caller
 * naming the credential it means, and falling back from it would let a stale cookie decide a
 * request the CLI thought it had authenticated.
 *
 * <p><b>Every {@code ps_access} cookie the request carries is tried, not only the first.</b>
 * Cookies are not port-scoped, so anything else served from this host — a sibling application on
 * another localhost port — can plant a second {@code ps_access} in the operator's browser.
 * Returning on the first match made that a lockout: measured, {@code ps_access=<not a token>;
 * ps_access=<valid>} was refused 401 while the same two in the other order were accepted, so a
 * console holding a good session would 401 with it attached and nothing would say why. It is not a
 * bypass — none of the planted values can be made to validate — but it is a denial of service
 * available to anyone who can set a cookie on this host, so the loop collects and the request is
 * accepted if any of them validates. How many there can be is bounded by whatever the container
 * accepts in one header rather than by anything here, and each extra one costs a digest and a map
 * read against a value that cannot match.
 *
 * <h2>A third credential, task 3 of the auth slice: the ticket, and — since task 1 of the
 * local-files slice — on both socket paths</h2>
 *
 * <p>Neither of the two transports above is available to a caller that is not a browser and has no
 * way to attach {@code Authorization} to a WebSocket upgrade — no WHATWG {@code WebSocket}
 * constructor takes a header. {@link TokenStore#mintTicket()} is what {@link
 * AuthController#ticket(HttpServletRequest) AuthController#ticket()} hands such a caller after one
 * ordinary, already-authenticated HTTP call, and this filter honours it as {@code ?ticket=...} on
 * exactly two paths: {@link EventChannelHandler#PATH} and {@link FileChannelHandler#PATH}. Which of
 * the two a caller opens is decided the same way it always was — by which path it dials, and by
 * nothing this filter reads — so admitting the ticket on the second path is not a second decision
 * about a role, only the first decision applied to the socket it had always excluded. Presented
 * anywhere else it is not even looked at — a ticket is a credential for a socket and nothing else,
 * so a gated route that is neither socket path answers the way it always does to a request carrying
 * no {@code Authorization} header and no {@code ps_access} cookie, which is exactly what such a
 * request is.
 *
 * <p>{@link TokenStore#spendTicket(String)} is asked only when the header and the cookie have both
 * already failed, so a caller that is genuinely authenticated by either of those never spends a
 * ticket it happened to also carry. Spending is atomic and single-use, which together with the
 * ticket's short lifetime is what the design spec calls out as the whole of why it is acceptable
 * for this one credential, and no other, to appear in a URL — and therefore in this server's own
 * access logs.
 *
 * <h2>A restricted chain may not hold the socket, cookie or ticket</h2>
 *
 * <p>{@link TokenStore#issuePair(boolean)} can start a chain restricted — an admin who still must
 * change their password — and {@code AuthController#ticket()} already refuses to mint such a chain
 * a ticket, so a non-browser caller, which has no cookie and no way to put {@code Authorization} on
 * a WebSocket upgrade, cannot reach the events socket at all. <b>That closed only the caller which
 * needed a ticket to get there.</b> A browser needs no ticket: a {@code ps_access} cookie is sent
 * on this upgrade the same as on any other {@code /v1} request — that is the whole point of the
 * cookie transport, and exactly what a session's ordinary cookie is measured to do against this
 * same path elsewhere in this file's test suite — so a restricted chain's cookie reached {@link
 * EventChannelHandler} exactly as an unrestricted one does, for as long as the access token stayed
 * live. <b>The finding, and this section: refusing the ticket trade closed the non-browser half
 * only, and said nothing about the browser one, which is the client the cookie design exists
 * for.</b>
 *
 * <p>So this filter asks {@link TokenStore#chainIsRestricted(String)} itself, a second and separate
 * call from the one {@code AuthController#ticket()} makes, on {@link EventChannelHandler#PATH} and,
 * since task 1 of the local-files slice, {@link FileChannelHandler#PATH} — the same reasoning
 * applies verbatim to the second socket, since a browser's cookie is sent on that upgrade exactly
 * as it is on this one — and only once a presented credential has already been accepted: a
 * restricted chain's access token is limited to setup, password change, session status and logout.
 * Ordinary application routes and both long-lived sockets refuse it.
 *
 * <h2>What is open, and why refusing it would be a gate nobody could pass</h2>
 *
 * <ul>
 *   <li>{@code /v1/auth}, {@code /v1/auth/refresh} and, since task 2 of the admin-login slice,
 *       {@code /v1/auth/login} — the door itself, now with a second lock a caller can open with a
 *       password instead of a token. A gate that refused the way through any of them could never be
 *       passed. ({@link AuthController} answers all three. Before {@code /v1/auth} and {@code
 *       /v1/auth/refresh} existed those two paths reached a 404, and {@code AuthFilterTest}
 *       asserted the 404 because reaching the dispatcher at all is what proves the filter let the
 *       request through; it now asserts the controller's own empty-bodied 401 for the same reason,
 *       since the filter's refusal carries a body and the controller's does not.)
 *   <li>everything <em>not</em> under {@code /v1/} — the console's own static assets. They are the
 *       same bytes for every install and none of them is data; every byte of data they display sits
 *       behind this filter.
 * </ul>
 *
 * <p>Everything else under {@code /v1/} is gated, prefix-wise rather than endpoint-wise, so <b>an
 * endpoint added in six months is gated by having been added</b> rather than by somebody
 * remembering. {@code
 * AuthFilterTest.every_route_this_application_publishes_is_gated_or_deliberately_open} enumerates
 * the tree's mappings and asserts each one against {@link #gates(String)}, which is the assertion
 * that fails when that stops being true.
 *
 * <h2>The path this reads is the decoded one, and that is a security property</h2>
 *
 * <p>{@link HttpServletRequest#getServletPath()} and {@link HttpServletRequest#getPathInfo()},
 * concatenated — <b>not {@link HttpServletRequest#getRequestURI()}</b>, which the servlet
 * specification says is <em>not</em> decoded. Reading the undecoded form would mean this filter and
 * the dispatcher disagree about what a request is asking for, and the direction of that
 * disagreement is a bypass rather than an inconvenience: {@code /%76%31/jobs} does not begin with
 * {@code /v1/} as a string and <b>does reach {@code GET /v1/jobs} as a route</b> — measured, by
 * presenting a credential with it and getting the controller's answer. {@code
 * AuthFilterTest.a_path_that_reaches_a_gated_route_only_when_decoded_is_still_refused} holds both
 * halves: the 401 without a credential and the 200 with one.
 *
 * <p><b>That pair is the path relative to the servlet mapping and not to the context, and it is the
 * right pair here precisely because {@code DispatcherServlet} is mapped at {@code /}.</b> {@link
 * #gates(String)} is written against a context-relative path — {@code startsWith("/v1/")} — and the
 * two coincide only under that mapping. A context path is harmless, because the servlet
 * specification excludes it from {@link HttpServletRequest#getServletPath()}: measured, {@code
 * --server.servlet.context-path=/ps} still refuses {@code GET /ps/v1/jobs}. A prefix-mapped
 * dispatcher is not harmless. Under {@code --spring.mvc.servlet.path=/api} the accessors read
 * {@code servletPath=[/api]} and {@code pathInfo=[/v1/jobs]}, this method hands {@code
 * /api/v1/jobs} to a rule that wants {@code /v1/jobs}, and {@code GET /api/v1/jobs} was answered
 * <b>200, by the controller, to an anonymous caller</b> — every {@code /v1} route open, with no
 * warning and nothing failing. Nothing in a single request distinguishes that from the ordinary
 * case, so it is <b>refused at boot</b> instead: {@link AuthConfig} reads {@code
 * spring.mvc.servlet.path} and stops the context, naming the property, when it is anything but
 * {@code /}. The assumption this paragraph states is therefore enforced rather than trusted, and
 * {@code AuthFilterTest.a_dispatcher_mapped_under_a_prefix_is_refused_at_boot} is what fails if
 * that guard is taken away.
 *
 * <p>A request whose path is empty by both accessors is refused rather than passed. It cannot reach
 * a {@code /v1} controller, so refusing it costs a 401 where a 404 would have done — and the
 * alternative is a branch whose safe behaviour depends on a container detail nobody here has
 * measured.
 *
 * <h2>The refusal says nothing</h2>
 *
 * <p>{@code 401}, {@code application/json}, {@code {"error":"unauthenticated"}}, and that is the
 * whole body for every way of failing. <b>A 401 that distinguished "expired" from "unknown" would
 * be an oracle</b> — it would tell a caller whether a guessed token had ever been real, which is
 * the one bit {@link TokenStore#refresh(String)} also refuses to give away. Nothing presented is
 * echoed back, and nothing presented is logged: this class holds a logger for exactly one line, and
 * that line is about configuration.
 */
public final class AuthFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

  /**
   * Everything below this is gated unless it is named in {@link #OPEN}.
   *
   * <p>A prefix and not a list of endpoints, which is the difference between a gate that stays shut
   * and one that stays shut until somebody adds a route and does not think about it.
   */
  public static final String GATED_PREFIX = "/v1/";

  /**
   * The door. Exact matches, never prefixes.
   *
   * <p>A prefix here would open {@code /v1/authorised-secrets} and anything else a future path
   * happened to start with, so the exemption is spelled as the three strings it is. A trailing
   * slash is not one of them and is therefore gated. That is only harmless because the dispatcher
   * declines a trailing slash too, so the pair agree that {@code /v1/auth/} is not the door —
   * measured on the gated probe by {@code AuthFilterTest.a_trailing_slash_is_not_the_same_route},
   * which was written when the door had no route behind it and still measures the container's
   * routing rather than this set.
   *
   * <p>{@code /v1/auth/login} joined the other two at task 2 of the admin-login slice for the same
   * reason either of them is here: a caller with no session yet has to be able to reach the
   * endpoint that would give it one, or the endpoint is unreachable by anyone who needs it.
   */
  static final Set<String> OPEN =
      Set.of("/v1/auth", "/v1/auth/refresh", "/v1/auth/login", "/ready");

  /** The scheme, spelled once, with the trailing space the header carries. */
  private static final String BEARER = "Bearer ";

  /**
   * The query parameter a ticket rides in, honoured on {@link EventChannelHandler#PATH} and {@link
   * FileChannelHandler#PATH} — the two socket paths, and nothing else.
   *
   * <p>A query parameter and not a header, because the whole reason a ticket exists is that a
   * WebSocket upgrade cannot carry a header at all — see the class note on the third credential.
   */
  static final String TICKET_PARAM = "ticket";

  /**
   * The account on every gated request. HandleInterceptor copies it onto both sockets; REST
   * controllers use it as their caller and log owner. The accepted token's handle wins, otherwise
   * the configured seeded admin is used. Tickets are accepted only on upgrades. A missing or blank
   * configured handle leaves this attribute absent.
   */
  public static final String HANDLE_ATTRIBUTE = "plowshare.handle";

  /**
   * The whole of what a refusal says. See the class note on why it is one sentence for every way of
   * failing.
   */
  private static final byte[] UNAUTHENTICATED =
      "{\"error\":\"unauthenticated\"}".getBytes(StandardCharsets.UTF_8);

  private AdminStore admins;

  public AuthFilter withAccounts(AdminStore admins) {
    this.admins = admins;
    return this;
  }

  private final TokenStore tokens;
  private final AuthProperties properties;

  /**
   * @param tokens the store this asks, and the only thing it asks
   * @param properties read for {@link AuthProperties#isEnabled()} and the access cookie's name; the
   *     lifetimes here belong to whoever built {@code tokens} and are not re-read
   */
  public AuthFilter(TokenStore tokens, AuthProperties properties) {
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.properties = Objects.requireNonNull(properties, "properties");
    if (!properties.isEnabled()) {
      // In the constructor rather than in init(FilterConfig), so that a
      // filter built by hand warns too: init() is the container's call and
      // a test that never makes it would otherwise be running an unguarded
      // server in silence, which is the state this line exists to make
      // impossible anywhere.
      log.warn(
          "plowshare.auth.enabled is false. This server authenticates NOTHING:"
              + " every /v1 endpoint, the /v1/events listener socket and the /v1/files"
              + " channel are open to anyone who can reach the port, and a session id is"
              + " again a bearer capability with nothing behind it. Set"
              + " PLOWSHARE_AUTH_ENABLED=true, or remove the override, to close it.");
    }
  }

  /**
   * Whether this filter refuses {@code path} without a credential.
   *
   * <p>Static and pure so that the rule can be enumerated against every route in the tree without a
   * running server — see the class note on the test that does it.
   *
   * @param path a decoded request path, or null
   * @return true if a credential is required. Null is required rather than open, for the reason the
   *     class note gives about an empty path.
   */
  public static boolean gates(String path) {
    if (path == null || path.isEmpty()) {
      return true;
    }
    if (OPEN.contains(path)) {
      return false;
    }
    return path.equals("/v1") || path.startsWith(GATED_PREFIX);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!properties.isEnabled()) {
      // Auth is off, so nothing below asks TokenStore anything — but a
      // gated request still needs an account, and the only one this filter can name with no
      // credential to read at all is the seeded admin. See the class note
      // on HANDLE_ATTRIBUTE.
      if (request instanceof HttpServletRequest disabledHttp && gates(path(disabledHttp))) {
        admin().ifPresent(h -> request.setAttribute(HANDLE_ATTRIBUTE, h));
      }
      chain.doFilter(request, response);
      return;
    }
    if (!(request instanceof HttpServletRequest http)
        || !(response instanceof HttpServletResponse answer)) {
      chain.doFilter(request, response);
      return;
    }
    String path = path(http);
    if (!gates(path)) {
      chain.doFilter(request, response);
      return;
    }
    // BOTH SOCKETS, AND FOR ONE REASON. A ticket exists because a client that
    // cannot set a header on an upgrade -- a browser, and Node's WHATWG
    // WebSocket -- has no other way to present a credential there. That is
    // as true of the file channel as of the listener, and admitting it on
    // one only meant the terminal client could hear a run and never lend it
    // a file. The restricted-chain refusal follows the same path set, so a
    // flagged account holds neither socket by either credential.
    boolean isSocketPath = isSocket(path);
    List<String> credentials = presented(http);
    var authentication =
        credentials.stream().flatMap(value -> tokens.authentication(value).stream()).findFirst();
    if (authentication.isPresent()) {
      if ((authentication.get().restricted() || restricted(credentials))
          && !Set.of("/v1/auth/setup", "/v1/auth/password", "/v1/auth/session", "/v1/auth/logout")
              .contains(path)) {
        // See the class note "A restricted chain may not hold the
        // socket, cookie or ticket": a flagged admin's own ps_access
        // cookie is otherwise sent on this upgrade exactly like any
        // other /v1 request, and admitting it here would leave the
        // ticket refusal below covering only the caller that never
        // needed a cookie in the first place.
        refuse(answer);
        return;
      }
      Optional<String> account = Optional.ofNullable(authentication.get().handle()).or(this::admin);
      if (admins != null
          && account.isPresent()
          && admins.byHandle(account.get()).filter(AdminRecord::enabled).isEmpty()) {
        refuse(answer);
        return;
      }
      if (admins != null
          && (path.startsWith("/v1/projects") || path.startsWith("/v1/search/providers"))
          && !http.getMethod().equals("GET")
          && !admins.isServerAdmin(account.orElse(null))) {
        answer.sendError(403, "Only a server administrator may manage server resources");
        return;
      }
      account.ifPresent(h -> request.setAttribute(HANDLE_ATTRIBUTE, h));
      if (admins != null && account.isPresent())
        request.setAttribute(
            "plowshare.sessionVersion",
            authentication.get().version() == null
                ? admins.sessionVersion(account.get())
                : authentication.get().version());
      chain.doFilter(request, response);
      return;
    }
    if (isSocketPath) {
      // Whether ?ticket=... on this request is one TokenStore#mintTicket
      // produced, spending it either way. Asked only for the two socket
      // paths, EventChannelHandler#PATH and FileChannelHandler#PATH, and
      // only after accepted(credentials) has already answered no — see
      // the class note on why a caller genuinely authenticated by the
      // header or the cookie never spends a ticket it happened to also
      // carry. getParameter answers null for an absent parameter, which
      // TokenStore#redeemTicket already treats as the ordinary shape of an
      // unauthenticated request.
      Optional<TokenStore.Redeemed> redeemed = tokens.redeemTicket(http.getParameter(TICKET_PARAM));
      if (redeemed.isPresent()) {
        String ticketHandle = redeemed.get().handle();
        if (admins != null && ticketHandle != null) {
          if (admins.byHandle(ticketHandle).filter(AdminRecord::enabled).isEmpty()) {
            refuse(answer);
            return;
          }
          request.setAttribute(
              "plowshare.sessionVersion",
              redeemed.get().version() == null
                  ? admins.sessionVersion(ticketHandle)
                  : redeemed.get().version());
        }
        // The ticket's own handle wins when it has one; a ticket minted
        // for nobody — the operator or bootstrap path trading for one —
        // falls back to the seeded admin the same way any other accepted
        // credential does, per HANDLE_ATTRIBUTE's class note. The file
        // channel is told its handle as the listener is (Enzo's decision
        // of 2026-09-30), so a session id is held to one account on both.
        Optional.ofNullable(redeemed.get().handle())
            .or(this::admin)
            .ifPresent(h -> request.setAttribute(HANDLE_ATTRIBUTE, h));
        chain.doFilter(request, response);
        return;
      }
    }
    refuse(answer);
  }

  /**
   * The handle to attribute a gated request to when none of the credentials it presented names one
   * directly — the operator token, the bootstrap chain, or {@code plowshare.auth.enabled: false}.
   * See the class note on {@link #HANDLE_ATTRIBUTE} for why this is narrower than {@link
   * AuthController}'s "not an admin" and not a contradiction of it.
   *
   * @return {@link AuthProperties#getAdminHandle()}, trimmed, or nothing if it is blank — no admin
   *     has been asked for
   */
  private Optional<String> admin() {
    String handle = properties.getAdminHandle();
    return handle == null || handle.isBlank() ? Optional.empty() : Optional.of(handle.trim());
  }

  /**
   * The handle any of {@code credentials} names, or {@link #admin()} if none of them does — asked
   * only for {@link EventChannelHandler#PATH}, and only once {@link #accepted(List)} has already
   * accepted one of them.
   *
   * <p>Every credential is asked and not only the one that authenticated, the same shape {@link
   * #restricted(List)} already uses: {@link TokenStore#handleFor(String)} answers empty for a
   * credential that names no account, so asking it about a stray cookie this request also carried
   * costs a digest and two map reads and changes nothing.
   */
  private Optional<String> handleOf(List<String> credentials) {
    for (String credential : credentials) {
      Optional<String> handle = tokens.handleFor(credential);
      if (handle.isPresent()) {
        return handle;
      }
    }
    return admin();
  }

  /** Whether {@code path} accepts single-use upgrade tickets. */
  private static boolean isSocket(String path) {
    return EventChannelHandler.PATH.equals(path) || FileChannelHandler.PATH.equals(path);
  }

  /**
   * The decoded path, assembled the way the servlet specification divides it — see the class note
   * on why this is not the request URI, and on why this pair is context-relative only while the
   * dispatcher is mapped at {@code /}, which {@link AuthConfig} refuses to boot without.
   */
  private static String path(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    String pathInfo = request.getPathInfo();
    return (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
  }

  /**
   * Whether any of what this request presented authenticates it.
   *
   * <p>An empty list is the ordinary shape of an unauthenticated request rather than an error, and
   * it asks {@link TokenStore} nothing.
   */
  private boolean accepted(List<String> credentials) {
    for (String credential : credentials) {
      if (tokens.validAccess(credential)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether any of {@code credentials} names a live access token on a chain {@link
   * TokenStore#issuePair(boolean)} started restricted — asked only for {@link
   * EventChannelHandler#PATH} and {@link FileChannelHandler#PATH}, and only once {@link
   * #accepted(List)} has already said one of them authenticates the request. See the class note "A
   * restricted chain may not hold the socket, cookie or ticket" for why this is a second, separate
   * refusal from the one {@code AuthController#ticket()} already makes, rather than the same check
   * reached twice.
   *
   * <p>Every presented credential is asked rather than only the one that authenticated — the same
   * shape {@code AuthController#ticket()} already uses for the same reason: {@link
   * TokenStore#chainIsRestricted(String)} answers false for anything that is not itself a live
   * access token, so asking it about a stray cookie this request also carried costs a digest and
   * two map reads and changes nothing.
   */
  private boolean restricted(List<String> credentials) {
    for (String credential : credentials) {
      if (tokens.chainIsRestricted(credential)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The credentials this request carries, in the order the container hands them over, or empty.
   *
   * <p>A {@code Bearer} header is one credential and suppresses the cookies; an {@code
   * Authorization} header in another scheme is treated as absent. Every cookie under the configured
   * name is collected rather than only the first, because a duplicate the operator did not set
   * would otherwise refuse a request their own valid one accompanies. Both rules are measured in
   * {@code AuthFilterTest}; see the class note for what each costs.
   */
  private List<String> presented(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return List.of(authorization.substring(BEARER.length()).trim());
    }
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return List.of();
    }
    String name = properties.getAccessCookie();
    // An ArrayList and not a Stream collector, because a cookie value may be
    // null and the immutable factories reject one; validAccess answers false
    // for it either way.
    List<String> presented = new ArrayList<>(1);
    for (Cookie cookie : cookies) {
      if (cookie.getName().equals(name)) {
        presented.add(cookie.getValue());
      }
    }
    return presented;
  }

  /**
   * One sentence, one status, no detail.
   *
   * <p>{@code WWW-Authenticate: Bearer} and nothing after it: naming the scheme is what a 401 is
   * for, and the {@code error="invalid_token"} parameters the same header can carry are precisely
   * the oracle the body declines to be.
   */
  private static void refuse(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentLength(UNAUTHENTICATED.length);
    response.getOutputStream().write(UNAUTHENTICATED);
  }
}
