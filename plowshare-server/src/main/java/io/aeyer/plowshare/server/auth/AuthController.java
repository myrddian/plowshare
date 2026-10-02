package io.aeyer.plowshare.server.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The door: the three paths {@link AuthFilter} leaves open, and the only way
 * into a server whose gate is on.
 *
 * <p>Before this class, nothing in this repository could obtain a credential. The
 * store could mint one and the filter could check one, and the only route from
 * one to the other was a test holding both halves — so a default-configured
 * server was reachable by no client this project ships, and {@code
 * PLOWSHARE_AUTH_ENABLED=false} was the documented way in. That is what this
 * closes, and every comment saying otherwise was swept in the same commit.
 *
 * <h2>Two credentials at startup, because there are two clients</h2>
 *
 * <p>The slice design says the bootstrap token is printed in a URL <em>and</em>
 * written to {@code ~/.config/plowshare/console-token}. Both cannot be true of
 * one single-use token: the browser spends it at {@link #exchange} and gets
 * cookies, the CLI would spend it and get nothing readable back — this endpoint
 * answers 204 with no body on purpose — and whichever went first would leave the
 * other with no credential at all. {@link AuthConfig} therefore mints two, and
 * {@link TokenStore#acceptOperator(String)} carries the argument for the second
 * one and for what its lack of expiry costs.
 *
 * <h2>Cookies, and one of them on a narrower path than the other</h2>
 *
 * <p>{@code ps_access} is {@code Path=/v1}: the browser must attach it to every
 * API call and to both WebSocket upgrades, which is the whole reason the design
 * chose a cookie over a header — {@code new WebSocket(url)} takes a URL and a
 * subprotocol list and nothing else.
 *
 * <p>{@code ps_refresh} is {@code Path=/v1/auth}, and the narrowing is the point:
 * the long-lived secret is then sent on the two requests that need it and on
 * nothing else, so it does not ride on every poll of a job. Both are {@code
 * HttpOnly}, because this console renders model output, file contents and memory
 * bodies — text an attacker can influence — and a cookie JavaScript cannot read
 * survives an XSS that would take anything JavaScript holds. Both are {@code
 * SameSite=Strict}.
 *
 * <p>{@code Secure} is set from {@link HttpServletRequest#isSecure()} rather than
 * unconditionally, which is the design's "Secure-when-TLS". Unconditional would
 * be a console that cannot log in at all: this server binds loopback over plain
 * HTTP by default, and a browser discards a {@code Secure} cookie arriving over
 * {@code http://}. The honest limit of that accessor, since this file is not
 * allowed to explain a mechanism it did not run: it reports the scheme this
 * container saw. Behind a proxy that terminates TLS it is false unless forwarded
 * headers are switched on, and this repository does not switch them on.
 *
 * <h2>What {@link #exchange} and {@link #refresh} answer, which is nothing</h2>
 *
 * <p>204 and an empty body on success; 401 and an empty body on every failure.
 * <b>No response either of these two methods writes ever carries a token</b>,
 * in any shape — the cookie header is the only channel, and a token in a JSON
 * body would be a token in JavaScript's hands, which is the one thing {@code
 * HttpOnly} was chosen to prevent. Neither method has a caller with any other
 * way to hold a credential — a browser reading {@code Set-Cookie} is the only
 * client either one is written for — so neither has a reason to open that
 * channel.
 *
 * <p><b>This is no longer true of the class as a whole, and it must not be read
 * as though it were.</b> {@link #login}, added later, is a deliberate exception
 * that states its own conditions where it lives: see "How a non-browser caller
 * asks for the pair in the body" below for exactly when a token leaves this
 * class inside a JSON body and the finding that shaped how that is guarded.
 * This section is a statement about {@link #exchange} and {@link #refresh}
 * only, both of which still hold it without exception.
 *
 * <p>There is no "which check failed" either, for the reason {@link
 * TokenStore#refresh(String)} gives at length: an answer that distinguished
 * expired from unknown from reused would tell a caller whether a guessed token
 * had ever been real.
 *
 * <h2>No logger, deliberately</h2>
 *
 * <p><b>This class has no {@code Logger} field and must not acquire one.</b>
 * Every value crossing it is a live secret. The one line in this server that
 * prints a token is {@link AuthConfig}'s startup announcement, and there is not
 * a second — not on refresh, not on failure, not at debug level.
 *
 * <h2>A third door: {@code POST /v1/auth/login}, task 2 of the admin-login
 * slice</h2>
 *
 * <p>The first two doors exchange one credential this server already handed a
 * caller for another. This one takes a password a person chose, checks it
 * against {@link AdminStore}, and on success does exactly what {@link
 * #exchange} does from there — {@link #issued} writes the same two cookies the
 * same way, because a session this endpoint starts is not a different kind of
 * session from one the bootstrap token started. {@link #login} is the one
 * method here allowed to touch a password, and it never logs one, never puts
 * one in a response, and never puts one in a URL — the same rules {@link
 * PasswordHasher} states for the hash and this class states for a token.
 *
 * <h2>How a non-browser caller asks for the pair in the body</h2>
 *
 * <p>{@link #exchange} and {@link #refresh} never had this problem: a browser
 * is the only caller either one is written for, {@code Set-Cookie} is the only
 * channel either uses, and a CLI that wants in presents the separate operator
 * token as {@code Authorization: Bearer} instead. {@code /v1/auth/login} is
 * different because logging in <em>as the admin</em> is a thing both a browser
 * and a script legitimately want to do, and a script cannot read a cookie jar
 * the way {@code Set-Cookie} expects one to be read — a CLI built on nothing
 * fancier than an HTTP client has no persistent cookie store to hand the pair
 * to and no browser DOM to keep it in.
 *
 * <p><b>The signal is a request header this endpoint invented and nothing else
 * sends: {@link #TOKEN_DELIVERY_HEADER} set to the literal value {@code
 * "body"}.</b> Present with that exact value, {@link #login} answers {@code
 * 200} with {@code {"access":"...","refresh":"...","mustChangePassword":false}}
 * in addition to the same two {@code Set-Cookie} headers {@link #exchange}
 * would have written; absent, or present with any other value, the answer is
 * {@code 204} with no body at all, cookies only. See {@link
 * #wantsTokenInBody(HttpServletRequest)}.
 *
 * <h3>What this replaced, and why it had to</h3>
 *
 * <p>The first cut of this method used the {@code Accept} request header as the
 * signal — a request naming {@code application/json} concretely got the body,
 * on the reasoning that content negotiation already exists to carry exactly
 * this kind of preference. <b>That was measured against two HTTP clients this
 * repository does not control, and it failed against both.</b> axios's own
 * documented default request headers name {@code application/json}, then
 * {@code text/plain}, then a trailing wildcard — and jQuery's {@code
 * $.ajax({dataType: 'json'})} names {@code application/json}, then {@code
 * text/javascript}, then a low-quality wildcard fallback. Both name {@code
 * application/json} concretely, at a real quality value, with no caller ever
 * deciding this endpoint should answer differently — a page built with either
 * library, and both are common defaults rather than an unusual choice, would
 * have received the access and refresh tokens in a body its own JavaScript can
 * read. That is precisely the scenario {@code HttpOnly} cookies exist to
 * survive: an XSS that can read a {@code fetch} response can read a JSON body
 * regardless of what cookies sit beside it. Filtering by quality value would
 * not have saved it either — both defaults name {@code application/json} above
 * zero, and a page could as easily send {@code text/html} first with {@code
 * application/json} at a token quality and still opt in by that reading.
 * {@code
 * AuthControllerTest.axioss_default_accept_header_does_not_leak_the_pair_into_a_body}
 * is the regression this header exists to prevent recurring: it sends axios's
 * literal default {@code Accept} string with no {@link #TOKEN_DELIVERY_HEADER}
 * and asserts {@code 204} with no body.
 *
 * <p>A header this endpoint invented closes that hole because <b>nothing sends
 * one incidentally</b>: no HTTP client, browser default, or JSON-consuming
 * library this project has found sets {@link #TOKEN_DELIVERY_HEADER} on its
 * own, so its presence is evidence of a caller that read this endpoint's
 * contract and decided it wants a body — which is exactly the caller a CLI or
 * a script is, and a browser's own login form is not. Three alternatives were
 * considered and rejected before landing here:
 *
 * <ul>
 *   <li><b>a flag in the request body</b> — {@code {"handle":...,
 *       "password":..., "wantTokens":true}}. Rejected because it conflates
 *       authentication input with a preference about the response's shape;
 *   <li><b>refusing whenever {@code text/html} is also named</b> in {@code
 *       Accept}, rather than abandoning that header outright. Rejected because
 *       it still makes the response's shape a function of a header a caller
 *       does not assemble by hand — {@code Accept} is built by whatever HTTP
 *       stack sits underneath a caller's code, exactly as axios's and jQuery's
 *       defaults show, so it is not a signal a caller chose. A header this
 *       endpoint invented, that nothing else populates, is; and
 *   <li><b>a second path</b>, such as {@code /v1/auth/login/cli}. Rejected
 *       because the password check, the throttle and the {@code
 *       mustChangePassword} handling are one piece of logic regardless of who
 *       is asking, and a second route either duplicates all of it or the two
 *       routes call each other. It would also need its own entry in {@link
 *       AuthFilter#OPEN}, doubling the door's surface for what is, underneath,
 *       one operation.
 * </ul>
 *
 * <p>Cookies are written either way rather than only for the 204 case, on
 * purpose: a caller that happens to use a cookie-aware HTTP client loses
 * nothing by also receiving them, and branching what gets written on top of
 * what already branches the status and the body would be two decisions doing
 * the work of one.
 *
 * <h2>Indistinguishable failure, on purpose</h2>
 *
 * <p>A handle {@link AdminStore#byHandle} does not find and a handle it finds
 * with the wrong password answer identically: {@code 401}, no body, no
 * cookies, and no header — the same shape {@link #exchange} and {@link
 * #refresh} already answer with for every way they can fail. <b>The one thing
 * that makes this worth its own paragraph is that both cases pay for a real
 * Argon2id verification</b>, not only that both produce the same bytes on the
 * wire. A branch that special-cased "no such handle" into an instant 401 would
 * make an unknown handle answer in microseconds and a known one in the tens of
 * milliseconds {@link PasswordHasher} costs, which is a timing side channel
 * that tells a caller which handles this server has whether or not the JSON
 * body ever differs.
 *
 * <p>So {@link #login} always calls {@link PasswordHasher#matches}, once, on
 * every attempt with a present handle and password: against {@link
 * AdminRecord#passwordHash()} if {@link AdminStore#byHandle} found a record,
 * against {@link #DUMMY_PASSWORD_HASH} — a real, valid Argon2id hash of a
 * value nothing but a {@code SecureRandom} inside {@link Tokens#mint()} ever
 * saw — if it did not. <b>Whether the login succeeds is decided separately,
 * from whether a record was found at all</b> — {@code admin.isPresent() &&
 * matched}, not {@code matched} alone — because {@link #DUMMY_PASSWORD_HASH}'s
 * defence is that nobody chose its plaintext, not that nobody could ever
 * compile the class and read the verification logic; relying on the hash
 * comparison by itself to also mean "and a record existed" would make a
 * successful verify against the dummy a login for a handle with no row behind
 * it.
 *
 * <h2>The throttle, and the denial of service it trades for</h2>
 *
 * <p>{@link LoginAttempts#tooMany} is asked before {@link AdminStore#byHandle}
 * runs and before any hashing happens, which is safe rather than a second
 * timing channel: {@link LoginAttempts} records a failure under whatever
 * string was presented, real handle or not, so whether a given string is
 * currently throttled says nothing about whether it names an admin — only
 * about how many failed attempts under that exact string this process has
 * seen recently.
 *
 * <p><b>The limit and the lockout are {@link AuthProperties} keys, not
 * constants</b> — {@link AuthProperties#getLoginMaxFailures()} and {@link
 * AuthProperties#getLoginLockout()} — because the throttle itself is a denial
 * of service against the one account it protects: this server has exactly one
 * admin, so a handful of wrong passwords, mistyped or somebody else's, lock
 * that admin out of their own console for the whole lockout, with no recovery
 * short of restarting the process. A fixed constant would leave that operator
 * with only that one way out; a configuration key at least leaves how much of
 * that trade to accept with the person who owns the single account it
 * protects. See {@link LoginAttempts} for the rest of what "in memory" costs,
 * and for why a bypass for the legitimate admin was not built instead — it
 * would be a bypass for whoever guessed the password too.
 *
 * <h2>{@code must_change_password}: an access token and no refresh, not
 * enforcement everywhere</h2>
 *
 * <p>{@code V37__admins.sql} and {@link AdminRecord} state the requirement
 * this method is the first to touch: an admin still carrying the flag must not
 * have it quietly become permanent. The full shape named in the design — every
 * <em>other</em> gated route refuses until the flag clears — needs machinery
 * this endpoint does not have and this task does not own: {@link AuthFilter}
 * has no way to ask "does the session presenting this token belong to an
 * admin who still must change their password?" without learning to ask a
 * second question of every request. <b>This paragraph used to add that
 * {@link TokenStore} itself had no way to answer that question at all,
 * because a grant carried no admin identity — only a chain id — and that is
 * no longer the case</b>: {@link TokenStore#handleFor(String)}, added for
 * {@code POST /v1/auth/password} below, is exactly that tagging. What is
 * still missing is {@link AuthFilter} asking it on every request; see "What
 * this still does not do" further down for why that gap outlives this
 * method regardless.
 *
 * <p><b>The first cut of this ruling stopped there and shipped only a response
 * header — full pair, full access, nothing withheld, with a header saying the
 * flag was still set. That was wrong, and wrong in the dangerous direction.</b>
 * A flagged admin received a refresh cookie that rotates forever, so one login
 * on a seeded password became a permanent session with nothing anywhere
 * refusing it — the opposite of what the flag exists to prevent, and quietly
 * so, since nothing about the response said a session had been withheld.
 *
 * <p><b>What ships: an admin still carrying the flag gets an access token and
 * no refresh token at all.</b> {@link #login} calls {@link
 * TokenStore#issuePair(String, boolean)} with the handle just verified and
 * {@link AdminRecord#mustChangePassword()}, which both starts the chain
 * restricted — see that method and {@link TokenStore}'s class notes "A sixth
 * kind" and "A seventh kind" — and, back in {@link #loggedIn},
 * decides whether the refresh half of the pair is ever delivered: no {@code
 * ps_refresh} {@code Set-Cookie}, and a {@code null} {@code refresh} field in
 * the JSON body. <b>And, since this was the gap {@code
 * a_flagged_login_does_not_expire_an_existing_ps_refresh} pinned: an
 * <em>expired</em> {@code ps_refresh} {@code Set-Cookie} is written in its
 * place</b> — {@code Max-Age=0} — rather than the cookie simply being left
 * out of the response. A browser that already held a live {@code ps_refresh}
 * from an earlier, unflagged exchange — the bootstrap flow, most plausibly —
 * would otherwise keep that cookie exactly as it was: omitting a
 * {@code Set-Cookie} header changes nothing already stored under that name,
 * where an explicit expiry clears it. The refresh token {@code issuePair}
 * minted for the new, restricted chain is still never delivered anywhere, and
 * ages out on its own — see {@link TokenStore}'s class note on why an
 * undelivered record is harmless.
 *
 * <p><b>What this still does not do, said plainly rather than implied away.</b>
 * The access token itself is ordinary: for as long as it is live — the
 * configured access lifetime, fifteen minutes by default — it authenticates
 * every gated route exactly the way any other session's access token does.
 * Nothing here refuses a single request from a flagged admin's session before
 * it expires; the enforcement is entirely in how long the session can last,
 * not in what it is allowed to do while it lasts. Closing that gap in
 * general — refusing requests outright for a session whose admin must still
 * change their password, on every gated route — needs {@link AuthFilter} to
 * ask a second question of every request; that is a larger change than this
 * fix wave and remains future work, and it is unaffected by {@link
 * TokenStore#handleFor(String)} existing, since that method answers <em>whose</em>
 * a chain is and this gap is about {@link AuthFilter} never asking at all,
 * on any chain, restricted or not. {@link #ticket} below closes the one
 * instance of it that this wave's review named specifically, without
 * attempting the general form.
 *
 * <p>The response header is kept regardless of the mechanism behind it: {@code
 * X-Plowshare-Must-Change-Password: true} (see {@link
 * #MUST_CHANGE_PASSWORD_HEADER}) on every successful login, cookie-only or
 * JSON, so a console or a CLI has a place to look and can choose to prompt for
 * a change well before the short-lived session runs out — and {@code POST
 * /v1/auth/password}, below, is what that prompt leads to.
 *
 * <h2>A fourth door, but not one {@link AuthFilter#OPEN} names: {@code POST
 * /v1/auth/ticket}, task 3 of the auth slice</h2>
 *
 * <p>The three doors above all answer a caller with no session yet. This one is
 * the opposite: it is reachable only <em>with</em> one, because {@link #ticket}
 * is behind the gate like every other {@code /v1} route and needs no entry in
 * {@link AuthFilter#OPEN} — a caller with nothing to present is refused by the
 * filter before this method is ever entered, which is exactly right, since a
 * ticket that anyone could mint without a session would be a second, weaker
 * credential sitting next to the one that actually matters.
 *
 * <p>What it is for: a WebSocket upgrade is an ordinary HTTP request before it
 * is a socket, and {@code new WebSocket(url)} takes a URL and a subprotocol
 * list — no header, and for a non-browser client no cookie either. {@link
 * #ticket} is the one HTTP round trip that turns a session this server already
 * trusts into a credential the upgrade <em>can</em> carry: {@link
 * TokenStore#mintTicket()}, single-use and seconds-long, presented as {@code
 * ?ticket=...} on {@code /v1/events} and spent there by {@link AuthFilter} —
 * see that class for why it is honoured on no other path.
 *
 * <p>The session token itself never appears in a URL; only the ticket does, and
 * only because it is single-use and short-lived enough that its presence in an
 * access log is close to worthless by the time anyone reads one.
 *
 * <h2>A flagged admin cannot trade an access token for a ticket</h2>
 *
 * <p><b>This used to be false, and the paragraph above used to claim it was
 * fine anyway.</b> A ticket minted from an access token has no lifetime tied
 * to that token at all — {@link TokenStore#spendTicket(String)} checks only
 * the ticket's own short expiry, never the access token that bought it — and
 * {@code EventChannelHandler} holds a socket open until it is closed for some
 * other reason, since the channel is one-way and there is nothing further for
 * either side to authenticate once it is attached. So an admin still carrying
 * {@code must_change_password} could mint a ticket in the first second of
 * their fifteen-minute access token and hold an events socket open
 * indefinitely — well past the access lifetime that is supposed to be the
 * whole of what bounds that session. "A session for a flagged admin dies at
 * the access lifetime", which an earlier version of the section above stated
 * as settled, was not true of a session that had opened this socket.
 *
 * <p>{@link #ticket} now refuses one to such a session: {@link
 * TokenStore#chainIsRestricted(String)} is asked about the access credential
 * this very request presented, and a match answers {@code 403} rather than
 * minting anything. This reads the one bit set once at issuance by {@link
 * TokenStore#issuePair(boolean)} — not a lookup of which admin a chain
 * belongs to, which {@link TokenStore#handleFor(String)} now answers
 * elsewhere but which this method has no need of — and it closes exactly
 * this instance rather than attempting the general form named above. What
 * remains true after this fix: a ticket minted <em>before</em> a
 * password change, by a session that was not restricted at the time, is
 * unaffected by a later {@code POST /v1/auth/password} on a different
 * session — this endpoint has no way to reach a ticket already spent into an
 * open socket, and closing that socket is not attempted here.
 *
 * <p><b>This closed only the non-browser half, and an earlier version of this
 * section stopped here as though that were the whole of it.</b> A ticket is
 * the only way a caller with no cookie jar and no way to set a header on a
 * WebSocket upgrade can reach {@code EventChannelHandler}, so refusing the
 * trade above does cover that caller completely — but a browser is not that
 * caller. {@code new WebSocket(url)} sends whatever cookies are already set
 * for the origin, so a flagged admin's own {@code ps_access} cookie reached
 * the events socket exactly the way an unrestricted session's does, needing
 * no ticket and therefore untouched by this method refusing one. {@link
 * AuthFilter} is what closes that half: it asks {@link
 * TokenStore#chainIsRestricted(String)} itself, on {@code
 * EventChannelHandler.PATH} alone and only once a presented credential has
 * already authenticated the upgrade — see that class's own note "A restricted
 * chain may not hold the socket, cookie or ticket". The two checks are
 * separate calls into the same one-bit fact for the two different transports
 * that can reach the same socket, not one mechanism reused.
 *
 * <p>And the file channel shares no code with either check: {@code
 * FileChannelHandler} does not go through {@link #ticket}, and it is not
 * {@code EventChannelHandler.PATH} that {@link AuthFilter} asks the same
 * question on, so this section is about the events socket alone.
 *
 * <h2>A fifth door, but not one {@link AuthFilter#OPEN} names either: {@code
 * GET /v1/auth/session}, task 1 of the admin-login-console slice</h2>
 *
 * <p>The console is getting a login screen, and on every page load it has to
 * ask two questions before it can decide where to send the browser: does this
 * browser have a session at all, and if so, must that session still change
 * its password before it dies. {@link #session} is gated exactly like {@link
 * #ticket} — no entry in {@link AuthFilter#OPEN}, because a caller with
 * nothing to present is refused by the filter before this method is ever
 * entered, and that refusal is the whole of the answer to the first
 * question. Reaching this method at all already says "yes" to it.
 *
 * <p>Neither existing route can stand in for the second question. {@link
 * #refresh} cannot be the probe: an admin still carrying {@code
 * must_change_password} holds an access token and no refresh cookie at all
 * — see the class note on {@code must_change_password} — so refresh answers
 * 401 for someone who is in fact signed in, which is exactly backwards for a
 * console trying to route a flagged admin to a change-password screen rather
 * than drop them once their fifteen-minute access token expires. And an
 * ordinary gated read answers the first question but not the second; on a
 * reload there is no login response left for the console to read {@link
 * #MUST_CHANGE_PASSWORD_HEADER} from, since that header is only ever written
 * by {@link #login}'s own response.
 *
 * <p>{@link #session} answers both at once, from the same one bit {@link
 * #ticket} already asks: {@link TokenStore#chainIsRestricted(String)} on
 * whichever access credential {@link AuthFilter} just accepted. No second
 * mechanism was built to ask a question this store can already answer.
 *
 * <p><b>It must not extend or rotate anything</b> — the same trade the first
 * cut of {@code must_change_password} got wrong for {@link #login}, see that
 * section above. A probe that refreshed or reissued a session on every call
 * would turn polling this endpoint into a way to stay signed in forever, so
 * {@link #session} reads {@link TokenStore#chainIsRestricted(String)} and
 * nothing else: no {@code Set-Cookie}, no call to {@link
 * TokenStore#issuePair(boolean)} or {@link TokenStore#refresh(String)}, no
 * write to any map this store holds.
 */
@RestController
public class AuthController {

    /** {@code Path=/v1}, so the browser attaches it to every API call and to
     *  both WebSocket upgrades. */
    static final String ACCESS_PATH = "/v1";

    /**
     * {@code Path=/v1/auth}, so the long-lived secret rides on the door and
     * nothing else.
     *
     * <p>Cookie path matching is prefix-with-a-boundary, so this one value has to
     * cover {@code /v1/auth/refresh} as well or the rotation could never be
     * reached — and the boundary is the half worth pinning, because a plain
     * prefix would also send this cookie to {@code /v1/authorize}, a path this
     * value is one character short of covering.
     *
     * <p><b>Measured, and by the suite rather than by a hand-run.</b> {@code
     * AuthControllerTest.the_cookie_paths_match_the_requests_they_are_for} parses
     * each {@code Set-Cookie} this class writes with {@link okhttp3.Cookie} — an
     * RFC 6265 implementation this repository already depends on and did not
     * write — and asserts where each cookie is and is not sent. The previous
     * wording here cited a {@code curl} session against a real port that nothing
     * in the tree could repeat.
     */
    static final String REFRESH_PATH = "/v1/auth";

    /**
     * {@code X-Plowshare-Must-Change-Password}: {@code true} or {@code false},
     * set on every successful {@link #login} answer. See the class note on
     * {@code must_change_password} for why this header exists and, just as
     * important, for what it does not do.
     */
    static final String MUST_CHANGE_PASSWORD_HEADER = "X-Plowshare-Must-Change-Password";

    /**
     * The request header a caller sets to {@link #TOKEN_DELIVERY_BODY} to ask
     * {@link #login} for the pair in the response body instead of cookies
     * only. See the class note "How a non-browser caller asks for the pair in
     * the body" for why this exists, what it replaced, and why the
     * replacement was necessary rather than a refinement.
     */
    static final String TOKEN_DELIVERY_HEADER = "X-Plowshare-Token-Delivery";

    /** The one value of {@link #TOKEN_DELIVERY_HEADER} that means anything;
     *  any other value, including a near miss, is read the same as the header
     *  being absent — see {@link #wantsTokenInBody(HttpServletRequest)}. */
    static final String TOKEN_DELIVERY_BODY = "body";

    /** The scheme {@link #presentedAccess(HttpServletRequest)} recognises, on
     *  {@link AuthFilter#presented(HttpServletRequest)}'s own constant of the
     *  same value — not shared as an import because that field is private to
     *  that class and the two are one word each to keep in step. */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * A real, valid Argon2id hash of a value nothing but {@link Tokens#mint()}
     * ever produced, computed once when this class loads rather than written as
     * a source literal.
     *
     * <p>{@link #login} runs {@link PasswordHasher#matches} against this
     * whenever {@link AdminStore#byHandle} finds no record, so that a request
     * naming a handle this server has never heard of pays the same Argon2id
     * cost as one naming a real handle and the wrong password — see the class
     * note on indistinguishable failure. It has to be a hash the algorithm
     * genuinely verifies against something, not a sentinel string {@link
     * PasswordHasher#matches} would reject without ever hashing — {@link
     * PasswordHasher#matches} short-circuits on a value it cannot parse, which
     * would make an unknown handle answer <em>faster</em> than a known one, the
     * opposite of what this constant exists to prevent.
     *
     * <p>The plaintext behind it is minted by {@link Tokens#mint()} — the same
     * {@link java.security.SecureRandom}-backed generator every token in this
     * server comes from — rather than a fixed literal, so that no source
     * reading this file, decompiled or otherwise, tells anyone what to type to
     * match it. That is defence in depth rather than the only defence: {@link
     * #login} additionally requires {@code admin.isPresent()} as a separate,
     * explicit condition of success, so even a caller who somehow produced a
     * match against this constant could not thereby log in as a handle with no
     * row behind it.
     */
    static final String DUMMY_PASSWORD_HASH =
            new PasswordHasher().hash(Tokens.mint().toCharArray());

    private final TokenStore tokens;

    private final AuthProperties properties;

    private final AdminStore admins;

    private final PasswordHasher hasher;

    private final LoginAttempts attempts;

    public AuthController(
            TokenStore tokens, AuthProperties properties, AdminStore admins,
            PasswordHasher hasher, LoginAttempts attempts) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.admins = Objects.requireNonNull(admins, "admins");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
    }

    /**
     * Spend the one-time bootstrap token, and answer with the cookie pair.
     *
     * <p>The body is optional so that a request carrying none is a 401 rather
     * than a 400: absence of a credential is the ordinary shape of an
     * unauthenticated request and gets the ordinary answer. A body that is not
     * JSON at all is still Spring's 400 — that is a statement about syntax and
     * discloses nothing about any token.
     *
     * <p><b>{@code consumes} does not turn the credential-less request into a
     * 415, and that was worth running rather than assuming.</b> Spring copies
     * {@code required = false} from the {@code @RequestBody} parameter onto the
     * consumes condition, and such a condition is skipped for a request that has
     * no body — so {@code POST /v1/auth} with no {@code Content-Type} and no body
     * reaches this method and answers <b>401</b>, both through {@code MockMvc}
     * and over a real socket. A request that <em>does</em> carry a body without a
     * JSON {@code Content-Type} is a <b>415</b>, and so is one framed chunked with
     * zero bytes: the switch is whether the request has a body, not whether it
     * named a type. The 415 is in the same family as the 400 above — a statement
     * about the shape of a request, telling a caller nothing about whether any
     * token was ever real — so it is left as it is rather than flattened into the
     * 401. {@code AuthControllerTest
     * .no_content_type_is_a_401_without_a_body_and_a_415_with_one} holds all
     * three.
     *
     * @param presented the request body, or null if there was none
     * @return 204 with two {@code Set-Cookie} headers, or 401 with nothing
     */
    @PostMapping(path = "/v1/auth", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> exchange(
            @RequestBody(required = false) Exchange presented, HttpServletRequest request) {
        if (presented == null || !tokens.spendBootstrap(presented.token())) {
            return ResponseEntity.status(401).build();
        }
        return issued(tokens.issuePair(), request);
    }

    /**
     * Rotate the pair, reading the refresh token from its cookie.
     *
     * <p><b>A request carrying no such cookie asks the store nothing.</b> That is
     * the commonest way this endpoint is called wrongly — a console whose cookies
     * expired, or anyone who found the path — and hashing a value that is not
     * there would be work done on behalf of whoever can reach the port.
     *
     * <p>Every {@code ps_refresh} the request carries is tried, not only the
     * first, for the reason {@link AuthFilter} collects every {@code ps_access}:
     * cookies are not port-scoped, so a sibling application on another localhost
     * port can plant one, and stopping at the first would let it log the console
     * out. Trying a planted value is safe rather than merely tolerable — a value
     * naming no record returns empty and retires nothing, so a stray cookie
     * cannot trip the reuse detector that would kill a live chain.
     *
     * @return 204 with a fresh pair of {@code Set-Cookie} headers, or 401 with
     *     nothing — one answer for all four ways {@link TokenStore#refresh} can
     *     decline
     */
    @PostMapping("/v1/auth/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request) {
        for (String presented : cookies(request, properties.getRefreshCookie())) {
            Optional<TokenStore.Pair> rotated = tokens.refresh(presented);
            if (rotated.isPresent()) {
                return issued(rotated.get(), request);
            }
        }
        return ResponseEntity.status(401).build();
    }

    /**
     * Check a password against {@link AdminStore}, and on success answer
     * exactly what {@link #exchange} answers — the same two cookies, written by
     * the same code — plus, for a caller that asked, the pair in the body too.
     *
     * <p>See the class notes for the three decisions this method embodies: how
     * a non-browser caller asks for the pair in the body, why an unknown handle
     * and a wrong password are answered identically including in how long they
     * take, and what this slice does and does not enforce about {@code
     * must_change_password}.
     *
     * <p>The four early exits, in the order they run and why that order is
     * safe:
     *
     * <ol>
     *   <li>a missing handle or password is refused before anything else runs —
     *       the same shape {@link #exchange} refuses a missing body in, and not
     *       a timing concern: there is no handle here to have leaked anything
     *       about;
     *   <li>{@link LoginAttempts#tooMany} is asked next, before {@link
     *       AdminStore#byHandle} and before any hashing — safe rather than a
     *       second oracle, because {@link LoginAttempts} records a failure
     *       under whatever string was presented whether or not it names an
     *       admin, so being throttled says nothing about whether the handle is
     *       real, only that this exact string has failed enough times recently;
     *   <li>{@link AdminStore#byHandle} runs and {@link PasswordHasher#matches}
     *       runs against whatever it found, or against {@link
     *       #DUMMY_PASSWORD_HASH} if it found nothing — always exactly one call
     *       to {@code matches}, so the Argon2id cost is paid once per attempt
     *       whether or not the handle is real; and
     *   <li>{@code admin.isPresent() && matched} decides success — see the
     *       class note on {@link #DUMMY_PASSWORD_HASH} for why presence is
     *       checked separately rather than trusted to fall out of the hash
     *       comparison.
     * </ol>
     *
     * <p>{@link LoginAttempts#record} runs exactly once per attempt that
     * reaches it, with the outcome that was actually decided — never skipped on
     * success, so a login clears a prior run of failures the way {@link
     * LoginAttempts} documents.
     *
     * @param presented the request body, or null if there was none
     * @param request read for {@link HttpServletRequest#isSecure()}, by {@link
     *     #cookie}, and for {@link #TOKEN_DELIVERY_HEADER}, by {@link
     *     #wantsTokenInBody}
     * @return on success, 204 with two {@code Set-Cookie} headers or 200 with
     *     one and the pair in the body — one cookie and a null {@code refresh}
     *     if the admin must still change their password, see the class note —
     *     plus the {@code must_change_password} header either way; 401 with
     *     nothing on any failure, indistinguishable from any other
     */
    @PostMapping(path = "/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(
            @RequestBody(required = false) Login presented, HttpServletRequest request) {
        if (presented == null || presented.handle() == null || presented.handle().isBlank()
                || presented.password() == null || presented.password().isEmpty()) {
            return unauthorized();
        }
        String handle = presented.handle();
        if (attempts.tooMany(handle)) {
            return unauthorized();
        }
        Optional<AdminRecord> admin = admins.byHandle(handle);
        String target = admin.map(AdminRecord::passwordHash).orElse(DUMMY_PASSWORD_HASH);
        boolean matched = hasher.matches(presented.password().toCharArray(), target);
        boolean ok = admin.isPresent() && matched;
        attempts.record(handle, ok);
        if (!ok) {
            return unauthorized();
        }
        boolean mustChangePassword = admin.get().mustChangePassword();
        return loggedIn(tokens.issuePair(handle, mustChangePassword), mustChangePassword, request,
                wantsTokenInBody(request));
    }

    /** 401 with an empty body — every way {@link #login} declines, answered
     *  identically. See the class note on why that includes an unknown handle
     *  and a wrong password. */
    private static ResponseEntity<LoginResponse> unauthorized() {
        return ResponseEntity.status(401).body(null);
    }

    /** Revoke the authenticated session chain. Leaving jobs running is intentional. */
    @PostMapping("/v1/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        for (String candidate : presentedAccess(request)) tokens.revoke(candidate);
        return ResponseEntity.noContent().build();
    }

    /**
     * Mint a single-use ticket for whoever is asking, which is only ever a
     * caller {@link AuthFilter} has already let through — with one exception
     * this method itself now refuses.
     *
     * <p>It is gated by prefix rather than named in {@link AuthFilter#OPEN},
     * so a request with no valid session never reaches this line at all —
     * there is no credential check of that kind for this method to make. See
     * the class note "A fourth door" for what the ticket is for and why the
     * session it is minted from never itself appears in a URL, and "A flagged
     * admin cannot trade an access token for a ticket" for the one thing this
     * method does refuse and why.
     *
     * @param request read by {@link #presentedAccess(HttpServletRequest)} for
     *     the very credential {@link AuthFilter} just accepted, so this method
     *     can ask {@link TokenStore#chainIsRestricted(String)} about it
     * @return 200 with the ticket in the body — the one credential this server
     *     deliberately hands back where a page's own {@code fetch} can read it,
     *     because the caller's next move is to put it in a query string, not to
     *     keep it out of one — or 403 with nothing, for the one caller named
     *     above
     */
    @PostMapping("/v1/auth/ticket")
    public ResponseEntity<TicketResponse> ticket(HttpServletRequest request) {
        for (String candidate : presentedAccess(request)) {
            if (tokens.chainIsRestricted(candidate)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }
        }
        return ResponseEntity.ok(new TicketResponse(tokens.mintTicket(
                handleFromSession(request).orElseGet(this::adminHandleOrNull))));
    }

    /**
     * The seeded admin's handle, or {@code null} — what a ticket is minted
     * for when the session trading for it belongs to no account: the
     * operator and bootstrap paths.
     *
     * <p>This is the same fallback {@link AuthFilter#HANDLE_ATTRIBUTE}'s own
     * class note names, and the same narrower claim applies here: {@link
     * #handleFromSession(HttpServletRequest)} — via {@link
     * TokenStore#handleFor(String)} — still answers empty for the operator
     * and bootstrap paths, and this class's own javadoc still means what it
     * says about neither being "an admin" for {@code POST
     * /v1/auth/password}. This method is asked only for the inbox handle a
     * minted ticket carries, a different question this deployment answers
     * more permissively: a server with no other account routes an
     * operator's or a detached bootstrap session's inbox items to the one
     * admin it has.
     */
    private String adminHandleOrNull() {
        String handle = properties.getAdminHandle();
        return handle == null || handle.isBlank() ? null : handle.trim();
    }

    /**
     * Answer whether the caller already holds a session and whether that
     * session must still change its password — see the class note "A fifth
     * door" for why the console needs this rather than reading {@link
     * #login}'s response header or probing with {@link #refresh}.
     *
     * <p>Gated like {@link #ticket}: no entry in {@link AuthFilter#OPEN}, so a
     * caller with no valid credential never reaches this line — {@link
     * AuthFilter} answers 401 on its own, and this method has no
     * unauthenticated case to handle.
     *
     * <p>Reads {@link TokenStore#chainIsRestricted(String)} and nothing else.
     * No cookie is written, no token is minted or rotated, and no state this
     * store holds is touched — a probe that changed any of those would make
     * polling this endpoint a way to keep a session alive indefinitely, the
     * opposite of what the access lifetime is for.
     *
     * @param request read by {@link #presentedAccess(HttpServletRequest)} for
     *     the very credential {@link AuthFilter} just accepted, so this
     *     method can ask {@link TokenStore#chainIsRestricted(String)} about
     *     it exactly as {@link #ticket} does
     * @return 204 with {@link #MUST_CHANGE_PASSWORD_HEADER} set to {@code
     *     true} or {@code false} — {@code true} exactly when {@link
     *     TokenStore#chainIsRestricted(String)} says so of any credential
     *     this request presented
     */
    @GetMapping("/v1/auth/session")
    public ResponseEntity<Void> session(HttpServletRequest request) {
        boolean mustChangePassword = false;
        for (String candidate : presentedAccess(request)) {
            if (tokens.chainIsRestricted(candidate)) {
                mustChangePassword = true;
                break;
            }
        }
        return ResponseEntity.noContent()
                .header(MUST_CHANGE_PASSWORD_HEADER, Boolean.toString(mustChangePassword))
                .build();
    }

    /**
     * Change the caller's own password, gated like every other {@code /v1}
     * route — {@code POST /v1/auth/password} is not in {@link
     * AuthFilter#OPEN} and needs no entry there, since only a caller who
     * already holds a session has a password to change.
     *
     * <h2>Whose password: the session's, never the request body's</h2>
     *
     * <p><b>This used to take the handle from the request body, and that was
     * the finding this method exists to close.</b> {@link TokenStore}'s
     * grants carried no admin identity — only a chain id — so a valid session
     * proved <em>a</em> caller was authenticated and said nothing about
     * <em>which</em> admin it was; with one admin that named the only row
     * there was to name, and with two it let any authenticated caller change
     * a different admin's password by naming their handle instead of its
     * own. {@link TokenStore#handleFor(String)} is the fix — see that
     * method's own class note "A seventh kind" — and {@link #handleFromSession} names
     * every access credential this very request presented and asks it for
     * the one this method trusts. {@link ChangePassword} no longer has a
     * {@code handle} field at all, deliberately: a field the caller could
     * fill in and this method never read would be a field the next reader
     * assumed still worked, and this is exactly the mistake to not repeat.
     *
     * <p><b>A session that belongs to no account — the bootstrap and
     * operator paths — is refused 401.</b> Neither of those is an admin, and
     * neither owns a row in {@link AdminStore} for this method to update, so
     * {@link #handleFromSession} answering {@link Optional#empty()} for them is not a
     * degraded case of the same request, it is the correct answer: there is
     * no password here to change.
     *
     * <h2>There is no password recovery</h2>
     *
     * <p>This was already true before this method existed at all: changing a
     * password here has always meant proving the current one first, the same
     * way {@link #login} proves one to sign in, so a lost password was never
     * something this endpoint could restore. What changes here is that the
     * fact becomes structural rather than incidental — before, a caller who
     * did not know an admin's current password could still name that admin's
     * handle in the body, and only the (correct) password check stood
     * between that and a change; now there is no field left through which a
     * caller could even attempt to name an account that is not their own
     * session's. <b>The bootstrap URL printed at startup is not a recovery
     * path either.</b> It mints a session belonging to no account, and such a
     * session cannot reach this method's success path at all — see the
     * paragraph above. An operator who has lost every admin password has no
     * way back into the console short of a row in {@code admins} being
     * fixed directly, which is the honest statement of what "no recovery"
     * means here rather than a gap this task left for later.
     *
     * <p>The five checks, in the order they run and why the order is safe —
     * the same shape and the same reasoning {@link #login} already carries:
     *
     * <ol>
     *   <li>a missing current password or new password is refused before
     *       anything else runs, the same 401 {@link #login} answers a
     *       missing body with;
     *   <li>the session's handle is looked up next, via {@link #handleFromSession} —
     *       a session belonging to no account is refused 401 here, before
     *       {@link LoginAttempts#tooMany} or any hashing runs, since there is
     *       no handle yet to throttle or to look up;
     *   <li>{@link LoginAttempts#tooMany} is asked before {@link
     *       AdminStore#byHandle} and before any hashing — this endpoint
     *       shares {@link #attempts} with {@link #login} rather than keeping
     *       a second throttle, since both ask the same question of the same
     *       handle: "has this many recent attempts against it failed?" A
     *       caller who cannot supply the current password is throttled here
     *       exactly as one who cannot supply it at the login form is;
     *   <li>{@link AdminStore#byHandle} runs and {@link
     *       PasswordHasher#matches} runs against whatever it found, or
     *       against {@link #DUMMY_PASSWORD_HASH} if it found nothing — the
     *       same indistinguishable-failure defence {@link #login} argues at
     *       length, applied here for the edge a session outlives its
     *       account: a handle {@link TokenStore#handleFor(String)} still
     *       names but {@link AdminStore#byHandle} no longer finds; and
     *   <li>only once the current password is proven does this method look at
     *       the new one at all — {@link PasswordPolicy#isObviousPlaceholder}
     *       refuses a blank or obviously-placeholder replacement with 400,
     *       the one status in this method that is not the indistinguishable
     *       401 above, because by this point the caller has already proven
     *       who they are and "your new password is unacceptable" discloses
     *       nothing an attacker could not already see for themselves.
     * </ol>
     *
     * <p>On success: {@link AdminStore#changePassword} writes the new hash and
     * clears {@code must_change_password} in one statement, {@link
     * TokenStore#revoke} retires the chain behind whichever access credential
     * this very request presented, and both cookies this class ever writes
     * are answered back expired — see {@link
     * #expiredCookie(String, String, HttpServletRequest)}. A changed password
     * therefore ends the session that changed it: the browser is logged out
     * and must present the new password to get back in, and a CLI holding the
     * old access token as an {@code Authorization} header finds it refused on
     * its very next request. This is the {@link TokenStore#revoke(String)}
     * this task's brief asked for rather than the documented-gap fallback: a
     * specific pair <em>can</em> be revoked without a wider change, because
     * retiring a chain was already this store's mechanism for reuse
     * detection and this method is simply the second caller of it.
     *
     * @param presented the request body, or null if there was none
     * @param request read for {@link HttpServletRequest#isSecure()}, by the
     *     cookie builders, and for the credential to revoke, by {@link
     *     #presentedAccess(HttpServletRequest)}
     * @return 204 with two expired {@code Set-Cookie} headers on success; 401
     *     with nothing if the session belongs to no account or the current
     *     password does not verify, indistinguishable from a session whose
     *     account no longer exists; 400 with nothing if the new password is
     *     blank or an obvious placeholder
     */
    @PostMapping(path = "/v1/auth/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> changePassword(
            @RequestBody(required = false) ChangePassword presented, HttpServletRequest request) {
        if (presented == null
                || presented.currentPassword() == null || presented.currentPassword().isEmpty()
                || presented.newPassword() == null || presented.newPassword().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<String> sessionHandle = handleFromSession(request);
        if (sessionHandle.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String presentedHandle = sessionHandle.get();
        if (attempts.tooMany(presentedHandle)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<AdminRecord> admin = admins.byHandle(presentedHandle);
        String target = admin.map(AdminRecord::passwordHash).orElse(DUMMY_PASSWORD_HASH);
        boolean matched = hasher.matches(presented.currentPassword().toCharArray(), target);
        boolean ok = admin.isPresent() && matched;
        attempts.record(presentedHandle, ok);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (presented.newPassword().isBlank()
                || PasswordPolicy.isObviousPlaceholder(presented.newPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String hash = hasher.hash(presented.newPassword().toCharArray());
        admins.changePassword(presentedHandle, hash);
        for (String candidate : presentedAccess(request)) {
            tokens.revoke(candidate);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        expiredCookie(properties.getAccessCookie(), ACCESS_PATH, request))
                .header(HttpHeaders.SET_COOKIE,
                        expiredCookie(properties.getRefreshCookie(), REFRESH_PATH, request))
                .build();
    }

    /**
     * The handle {@link TokenStore#handleFor(String)} names for whichever
     * access credential {@link #presentedAccess(HttpServletRequest)} finds
     * first, or {@link Optional#empty()} if none of them belong to an
     * account — see {@link #changePassword} for the one caller this exists
     * for and why a session with no account is refused there rather than
     * guessed at.
     *
     * <p>Every candidate is asked and not only the first, the same reason
     * {@link #ticket} and {@link #session} loop over {@link
     * #presentedAccess(HttpServletRequest)} rather than indexing it: cookies
     * are not port-scoped, so a stray value from a sibling application could
     * sit ahead of the real one, and {@link TokenStore#handleFor(String)}
     * answers empty for anything it did not issue rather than throwing.
     */
    private Optional<String> handleFromSession(HttpServletRequest request) {
        for (String candidate : presentedAccess(request)) {
            Optional<String> found = tokens.handleFor(candidate);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code request} asked for the pair in the body: {@link
     * #TOKEN_DELIVERY_HEADER} present with exactly the value {@link
     * #TOKEN_DELIVERY_BODY}, nothing looser. See the class note "How a
     * non-browser caller asks for the pair in the body" for why this replaced
     * an {@code Accept}-based check that a real client's default header
     * defeated.
     *
     * <p>Case-insensitive on the value — {@link String#equalsIgnoreCase} — the
     * one concession to looseness this check makes, since a header value is
     * not a place HTTP itself cares about case and a caller that wrote {@code
     * Body} should not be refused a feature over it. Nothing else about the
     * match is loose: an unset header, an empty one, or any value that is not
     * this one reads as "no", the same as {@link #TOKEN_DELIVERY_HEADER} never
     * having been sent.
     */
    private static boolean wantsTokenInBody(HttpServletRequest request) {
        return TOKEN_DELIVERY_BODY.equalsIgnoreCase(request.getHeader(TOKEN_DELIVERY_HEADER));
    }

    /**
     * A successful login's answer.
     *
     * <p>Always the access cookie. The refresh cookie too, unless {@code
     * mustChangePassword} — see the class note "{@code must_change_password}:
     * an access token and no refresh" for why withholding it there is what
     * keeps that session from being rotated forward, and why {@link
     * TokenStore} mints it regardless. <b>When it is withheld, an expired
     * {@code ps_refresh} is written in its place</b> rather than nothing at
     * all — see {@link #expiredCookie(String, String, HttpServletRequest)} —
     * so that a browser already holding a live one from an earlier, unflagged
     * exchange does not keep it simply because this response never mentioned
     * the cookie's name. The {@code must_change_password} header is set
     * either way. Status and body follow {@code json}: 200 and the pair —
     * {@code refresh} null when it was withheld — or 204 and nothing.
     *
     * <p>{@link ResponseEntity#status(HttpStatus)} rather than {@link
     * ResponseEntity#ok()}/{@link ResponseEntity#noContent()}: the latter two do
     * not share a builder type that both takes headers and can be given a body —
     * {@code noContent()} deliberately returns a builder with no {@code body}
     * method, so a 204 cannot be given one by accident — where {@code
     * status(HttpStatus)} returns a {@link ResponseEntity.BodyBuilder}
     * regardless of which status is named, which is what lets this method pick
     * the status with one ternary and still call {@code body} either way.
     */
    private ResponseEntity<LoginResponse> loggedIn(
            TokenStore.Pair pair, boolean mustChangePassword, HttpServletRequest request,
            boolean json) {
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(json ? HttpStatus.OK : HttpStatus.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, accessCookie(pair, request));
        if (mustChangePassword) {
            builder = builder.header(HttpHeaders.SET_COOKIE,
                    expiredCookie(properties.getRefreshCookie(), REFRESH_PATH, request));
        } else {
            builder = builder.header(HttpHeaders.SET_COOKIE, refreshCookie(pair, request));
        }
        builder = builder.header(MUST_CHANGE_PASSWORD_HEADER, Boolean.toString(mustChangePassword));
        String refresh = mustChangePassword ? null : pair.refresh();
        return builder.body(json ? new LoginResponse(pair.access(), refresh, mustChangePassword) : null);
    }

    /** 204, two {@code Set-Cookie} headers, and no body at all. */
    private ResponseEntity<Void> issued(TokenStore.Pair pair, HttpServletRequest request) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, accessCookie(pair, request))
                .header(HttpHeaders.SET_COOKIE, refreshCookie(pair, request))
                .build();
    }

    /** The {@code Set-Cookie} header value for {@code pair}'s access token,
     *  built through {@link #cookie}. The one place {@link #issued} and {@link
     *  #loggedIn} both do so, so the attributes {@link #cookie} decides are
     *  decided once regardless of which endpoint is issuing. */
    private String accessCookie(TokenStore.Pair pair, HttpServletRequest request) {
        return cookie(properties.getAccessCookie(), pair.access(), ACCESS_PATH,
                properties.getAccessLifetime(), request).toString();
    }

    /** The {@code Set-Cookie} header value for {@code pair}'s refresh token.
     *  Split from {@link #accessCookie} rather than returned alongside it in
     *  one call, because {@link #loggedIn} writes this one conditionally — see
     *  the class note on {@code must_change_password} — where {@link #issued}
     *  always writes both. */
    private String refreshCookie(TokenStore.Pair pair, HttpServletRequest request) {
        return cookie(properties.getRefreshCookie(), pair.refresh(), REFRESH_PATH,
                properties.getRefreshLifetime(), request).toString();
    }

    /**
     * One cookie, with the attributes the design names.
     *
     * <p>A {@link ResponseCookie} and not {@link Cookie}, because {@code
     * SameSite} is not a field the servlet {@code Cookie} class has: setting it
     * through that API means writing the header by hand, and a hand-written
     * header is where an attribute goes missing.
     */
    private static ResponseCookie cookie(
            String name, String value, String path, Duration lifetime,
            HttpServletRequest request) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(path)
                .maxAge(lifetime)
                .build();
    }

    /**
     * The same cookie {@link #cookie} would write, but expired: an empty
     * value and {@code Max-Age=0}, which tells a browser to drop whatever it
     * is holding under this name rather than to keep it.
     *
     * <p>Two callers, both clearing a credential rather than issuing one:
     * {@link #loggedIn} writes this for {@code ps_refresh} when {@code
     * mustChangePassword} is true, so a browser holding a live refresh cookie
     * from an earlier, unflagged exchange is told to drop it rather than left
     * to keep it simply because this response never mentions the name — see
     * the class note on {@code must_change_password}. {@link
     * #changePassword} writes this for both cookie names on success, so a
     * changed password ends the browser session that changed it — see that
     * method's own javadoc.
     *
     * <p>Every attribute but the value and the age matches {@link #cookie}
     * exactly, deliberately: a cookie is cleared by a browser matching name,
     * {@code Path} and {@code Domain} against one it already holds, so an
     * expiry whose {@code Path} disagreed with the cookie it meant to clear
     * would clear nothing.
     */
    private static String expiredCookie(String name, String path, HttpServletRequest request) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(path)
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }

    /**
     * Every cookie under {@code name}, in the order the container hands them
     * over, or empty.
     *
     * <p>An {@link ArrayList} and not an immutable collector for {@link
     * AuthFilter}'s reason: a cookie value may be null, the immutable factories
     * reject one, and {@link TokenStore#refresh(String)} answers empty for it
     * anyway.
     */
    private static List<String> cookies(HttpServletRequest request, String name) {
        Cookie[] carried = request.getCookies();
        if (carried == null) {
            return List.of();
        }
        List<String> presented = new ArrayList<>(1);
        for (Cookie cookie : carried) {
            if (cookie.getName().equals(name)) {
                presented.add(cookie.getValue());
            }
        }
        return presented;
    }

    /**
     * The access credential(s) this request carries — {@link #ticket} and
     * {@link #changePassword} both ask this rather than {@link AuthFilter},
     * because by the time either method runs, {@link AuthFilter} has already
     * decided the request is authenticated and thrown away exactly which
     * credential did it.
     *
     * <p>Mirrors {@link AuthFilter#presented(HttpServletRequest)}'s
     * precedence rule — a {@code Bearer} header, when present, suppresses
     * every cookie — so that the credential this method names is the same
     * one that got the request past the filter, not a second, independent
     * reading of the request that could disagree with the first. Duplicated
     * rather than shared because that method is {@code private} to a
     * different class and the two are one {@code regionMatches} call each to
     * keep in step, not a seam worth a shared utility over.
     */
    private List<String> presentedAccess(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return List.of(authorization.substring(BEARER_PREFIX.length()).trim());
        }
        return cookies(request, properties.getAccessCookie());
    }

    /**
     * The body of {@code POST /v1/auth}: {@code {"token": "..."}}.
     *
     * <p><b>{@code toString} is overridden, and overriding it is the point</b> —
     * the same hazard {@link TokenStore.Pair} documents at length. A record's
     * generated {@code toString} interpolates its components, so the default
     * would print a live bootstrap token from anywhere this object is rendered
     * without anyone deciding to: a {@code log.debug} of a handler argument, an
     * exception constructed with it, or Spring's own error page.
     *
     * @param token whatever the caller presented, including nothing
     */
    public record Exchange(String token) {

        /** Constant, and neither the value nor a prefix of it: a prefix of a
         *  192-bit secret is still a distinguisher, and there is no debugging
         *  question here that knowing which token it was would answer. */
        @Override
        public String toString() {
            return "Exchange[token=<redacted>]";
        }
    }

    /**
     * The body of {@code POST /v1/auth/login}: {@code {"handle": "...",
     * "password": "..."}}.
     *
     * <p>{@code toString} is overridden for {@link Exchange}'s reason — the
     * generated one would print the plaintext password. {@code handle} is left
     * in the clear: it is a login name, not a secret, on the same footing as an
     * email address, and a debugging session that needs to know which handle
     * attempted a login is an ordinary one to have.
     *
     * @param handle the login name presented
     * @param password the plaintext, as Jackson deserialised it into a {@code
     *     String} — see {@link PasswordHasher} for the limit of what this
     *     server can do about a copy that already exists on the heap in that
     *     form before this method ever sees it
     */
    public record Login(String handle, String password) {

        @Override
        public String toString() {
            return "Login[handle=" + handle + ", password=<redacted>]";
        }
    }

    /**
     * The body of {@code POST /v1/auth/password}: {@code
     * {"currentPassword": "...", "newPassword": "..."}}.
     *
     * <p><b>This used to also carry {@code handle}, and that field is gone
     * rather than merely unread.</b> {@link #changePassword} now takes the
     * handle whose row to update from the caller's own session — see that
     * method's class note "Whose password: the session's, never the request
     * body's" — and a field the caller could still fill in but this method
     * never looked at would be a field the next reader assumed still worked.
     * There is nothing left here for a caller to name an account with.
     *
     * <p>{@code toString} is overridden for {@link Login}'s reason: the
     * generated form would print two plaintext passwords.
     *
     * @param currentPassword the plaintext this method verifies before
     *     changing anything
     * @param newPassword the plaintext to hash and store in its place, once
     *     {@code currentPassword} has verified
     */
    public record ChangePassword(String currentPassword, String newPassword) {

        @Override
        public String toString() {
            return "ChangePassword[currentPassword=<redacted>, newPassword=<redacted>]";
        }
    }

    /**
     * The body {@code POST /v1/auth/login} answers with when the caller asked
     * for it in the response — see the class note on {@link #login} for the
     * {@code Accept} header that asks. Both tokens are redacted from {@code
     * toString} for {@link TokenStore.Pair}'s reason; {@code mustChangePassword}
     * is not a secret and is left in the clear.
     *
     * @param access the same token the {@code ps_access} cookie on this same
     *     response carries
     * @param refresh the same token the {@code ps_refresh} cookie on this same
     *     response carries, or {@code null} when {@code mustChangePassword} is
     *     true — see the class note on {@code must_change_password} for why no
     *     refresh token is delivered to a session in that state
     * @param mustChangePassword whether this admin must still change their
     *     password — see the class note on {@code must_change_password} for
     *     what this server does, and does not, do about that
     */
    public record LoginResponse(String access, String refresh, boolean mustChangePassword) {

        @Override
        public String toString() {
            return "LoginResponse[access=<redacted>, refresh="
                    + (refresh == null ? "<absent>" : "<redacted>") + ", mustChangePassword="
                    + mustChangePassword + "]";
        }
    }

    /**
     * The body of {@code POST /v1/auth/ticket}: {@code {"ticket": "..."}}.
     *
     * <p>{@code toString} is overridden for {@link TokenStore.Pair}'s reason — a
     * ticket is short-lived, but for the seconds it lives it is a working
     * credential for the events socket, and a record's generated rendering
     * would print it from anywhere this object is logged or thrown from without
     * anyone deciding to.
     *
     * @param ticket the single-use, seconds-long credential {@link
     *     TokenStore#mintTicket()} produced, to be presented as {@code
     *     ?ticket=...} on the {@code /v1/events} upgrade
     */
    public record TicketResponse(String ticket) {

        @Override
        public String toString() {
            return "TicketResponse[ticket=<redacted>]";
        }
    }
}
