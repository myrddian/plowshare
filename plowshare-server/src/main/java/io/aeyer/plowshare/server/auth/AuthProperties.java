package io.aeyer.plowshare.server.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What an operator may set about console authentication, and the one key that can switch it off.
 *
 * <p>Bound from {@code plowshare.auth} in {@code application.yml}. The defaults here are the slice
 * design's table — fifteen minutes and seven days — and they are written in both places on purpose:
 * the YAML is where an operator looks, and these are what a context that never read a YAML gets,
 * which is every test that builds a filter by hand.
 *
 * <h2>{@code enabled} is a way out, not a setting</h2>
 *
 * <p>The other keys change how long a credential lasts, what a cookie is called or where the
 * operator token is written. {@code enabled: false} changes whether this server asks for one at
 * all, and a deployment left that way is reachable by anyone who can reach the port. {@link
 * AuthFilter} therefore <b>logs a warning naming what is off</b> whenever it is built with a
 * disabled properties object, so the state is visible in a log an operator reads rather than only
 * in a file nobody re-opens. That warning is asserted by {@code AuthFilterTest}: it is part of the
 * behaviour, not a courtesy.
 *
 * <p><b>It is no longer needed to use this server, and that paragraph was swept.</b> It read:
 * between the commit that built the gate and the commit that built {@code POST /v1/auth}, nothing
 * could mint a credential over HTTP, so turning the gate off was the only way in. That window
 * closed — {@link AuthController} exchanges a bootstrap token for cookies, and {@link AuthConfig}
 * announces one at startup and writes an operator token for the CLI. What is left for this key is a
 * deployment that deliberately wants no gate, which is the thing the warning is about.
 *
 * <h2>Not {@code ignoreUnknownFields = false}, unlike {@code LlmProperties}</h2>
 *
 * <p>That class closes its prefix because a mistyped key there sends the server to a different
 * inference host in silence. The failure here is the opposite shape and needs no such guard: a
 * mistyped key under this prefix leaves every value at the default above, and the default is the
 * safe one — the gate on, the lifetimes short. There is no spelling of a key in this prefix that
 * opens something by being ignored.
 */
@ConfigurationProperties(prefix = "plowshare.auth")
public class AuthProperties {

  /**
   * Whether {@link AuthFilter} refuses anything at all.
   *
   * <p>True by default, and the default is the whole of the mechanism — the same shape as {@code
   * server.address}, which defaults to loopback for the same reason. See the class note for the one
   * thing turning it off is for.
   */
  private boolean enabled = true;

  // Enabled by the real server entrypoint, avoiding side effects in embedded test contexts.
  private boolean firstRunSetup;

  public boolean isFirstRunSetup() {
    return firstRunSetup;
  }

  public void setFirstRunSetup(boolean value) {
    firstRunSetup = value;
  }

  /**
   * How long an access token is good for. The design's fifteen minutes: short, so that a leaked one
   * is a leak with an end to it.
   */
  private Duration accessLifetime = Duration.ofMinutes(15);

  /**
   * How long a refresh token is good for. The design's seven days, so a console left open over a
   * weekend is still a console.
   */
  private Duration refreshLifetime = Duration.ofDays(7);

  /**
   * The cookie the browser sends on every request, including the WebSocket upgrade — which is the
   * whole reason there is a cookie and not only a header. See {@link AuthFilter} for what a browser
   * cannot do.
   */
  private String accessCookie = "ps_access";

  /**
   * The cookie exchanged for a new pair.
   *
   * <p>Configurable here and <b>not read by {@link AuthFilter}</b>, which never looks at a refresh
   * token: the design narrows this cookie's {@code Path} to {@code /v1/auth} precisely so that it
   * is not sent on ordinary requests. {@link AuthController#refresh} is its only reader. It is
   * named here rather than there so that the two cookie names an operator may rename sit together,
   * which is how an operator thinks about them.
   */
  private String refreshCookie = "ps_refresh";

  /**
   * Where this server writes the operator token, or blank for "mint nothing and announce nothing at
   * startup".
   *
   * <h2>Blank is the default, and that is the load-bearing part</h2>
   *
   * <p>{@code PlowshareServerApplication.main} sets this to {@code
   * ~/.config/plowshare/console-token} as a <em>default property</em>, which is Spring's
   * lowest-precedence source — so an environment variable, a command line {@code
   * --plowshare.auth.token-file=...}, or a YAML key all still win over it. <b>A context that did
   * not come through {@code main} therefore leaves this blank</b>, and every full-application test
   * context in this repository is such a context — the three classes carrying
   * {@code @SpringBootTest}, and the {@code SpringApplicationBuilder} and
   * {@code @ImportAutoConfiguration} boots that raise a real web server without it.
   *
   * <p>That is the whole mechanism, and it lives here rather than in a {@code @Profile} or a
   * test-only YAML because of what the alternative does: this file is a real path on the machine
   * running the build, holding the live credential of whatever server its operator is actually
   * using. A suite that announced by default would overwrite it once per full-application context —
   * a running server made unreachable by running the tests, with nothing anywhere saying so. {@code
   * AuthControllerTest} asserts both directions: that a context with this set writes the file and
   * prints the line, and that a context without it writes and prints nothing.
   *
   * <p>And the whole suite is the other instrument, which is the one that covers a
   * {@code @SpringBootTest} nobody thought to check: {@code ./gradlew check} was run on a machine
   * whose {@code ~/.config/plowshare/} held only {@code lm-key}, and it still held only {@code
   * lm-key} afterwards.
   *
   * <h2>It also decides a fence, and that is the other reason it is one key</h2>
   *
   * <p>{@code AgentsConfig.projectStore} reads this property and hands the path to {@code
   * ProjectStore.mandatoryExclusions}, so no project's workspace can reach the file this server
   * writes — wherever an operator points it. The <em>configured</em> value and never {@link
   * AuthConfig#defaultTokenFile()}: a fence built from the default would name a path this
   * deployment may never touch and leave the one it does touch readable, and in a context where
   * this is blank it would name the running operator's real credential in a list of things this
   * server claims to own.
   *
   * <p>That fence is not the only protection and is not the first one. {@code
   * io.aeyer.plowshare.protocol.FileAccess#permits} refuses any path with a dot-prefixed component
   * below its root, which covers the default {@code ~/.config/plowshare/console-token} on both
   * machines because of where it sits. <b>The exclusion is for the operator who moves this key
   * somewhere unhidden</b>, which is a change that would otherwise remove a protection with nothing
   * anywhere saying so.
   *
   * <p>Deliberately <b>not</b> given a value in {@code application.yml}. A key there would beat
   * {@code main}'s default property and would be read by every test as well, which is the state
   * this default exists to avoid; the YAML carries a comment pointing here instead.
   *
   * <p>A blank value costs a server that mints no bootstrap token either, so {@code POST /v1/auth}
   * has nothing to spend and the only credentials are the ones a caller issues from {@link
   * TokenStore} directly. That is exactly what the full-application tests do.
   */
  private String tokenFile = "";

  /**
   * The login this server's one admin account is created under, or blank for "no admin has been
   * asked for".
   *
   * <h2>Blank is the default, and it is what makes this key opt-in</h2>
   *
   * <p>{@code AdminSeed} treats a blank handle as "this deployment has not asked for an admin
   * account yet" and does nothing at all — no lookup, no password check, no boot refusal. That is
   * deliberate and it is {@link #tokenFile}'s own reasoning applied to a second key: every
   * full-application test context in this repository boots through a path that never sets {@code
   * PLOWSHARE_ADMIN_HANDLE}, so a value here that defaulted to anything but blank would have {@code
   * AdminSeed} attempt to create an admin — and, on an unset password, refuse the boot — in every
   * one of those contexts. Leaving the key unset is exactly the deployment this default has to keep
   * working: an operator who has not yet moved off the operator token still boots, still
   * authenticates the CLI the way it always has, and sees no admin-related refusal they never asked
   * for.
   *
   * <p>Bound from {@code PLOWSHARE_ADMIN_HANDLE} in {@code application.yml}, unlike {@link
   * #tokenFile}, which is deliberately absent from that file. The difference is what "off" costs to
   * spell: {@code tokenFile}'s off value is a real path with a side effect if written down naively,
   * so it is set only by {@code PlowshareServerApplication.main}, which a test context never calls.
   * This key's off value is the empty string, which is safe to write as the YAML placeholder's own
   * default and therefore needs no such indirection.
   *
   * <p>Set this, and {@code PLOWSHARE_ADMIN_PASSWORD} in the environment, to opt in — but the
   * password is deliberately <b>not</b> a field on this class. See {@code AdminSeed} for where and
   * why: this bean is a process-lifetime singleton, and a {@code String} field on it would hold a
   * plaintext password in the heap for as long as the server runs rather than for as long as
   * seeding needs it. {@code AdminSeed} reads the environment variable directly, through a plain
   * {@link org.springframework.core.env.Environment}, at the one moment it is needed instead.
   */
  private String adminHandle = "";

  /**
   * How many consecutive failed logins under one handle {@link LoginAttempts} allows before locking
   * that handle out for {@link #loginLockout}.
   *
   * <p>A configuration key rather than a constant on {@link LoginAttempts} itself, because the
   * throttle it drives is also a denial of service against this server's one admin: five wrong
   * passwords — mistyped, or somebody else's failed guesses — cost the only operator there is their
   * own access for the whole lockout, with no recovery short of a restart ({@link LoginAttempts}'s
   * own class note says a restart forgets every count, which is a cost there and a mercy here). An
   * operator who finds the default too easy to trip, or too easy to abuse, moves it without a
   * rebuild.
   */
  private int loginMaxFailures = 5;

  /**
   * How long a handle stays locked out once {@link #loginMaxFailures} is reached. See {@link
   * #loginMaxFailures} for why this is a key and not a constant on {@link LoginAttempts}.
   */
  private Duration loginLockout = Duration.ofMinutes(15);

  /**
   * How long a ticket minted by {@code POST /v1/auth/ticket} is good for.
   *
   * <p>Seconds, not minutes — unlike every other lifetime on this class, a ticket is designed to be
   * presented as {@code ?ticket=...} on the {@code /v1/events} upgrade, which means it is designed
   * to sit in a URL and therefore to land in access logs. That is only defensible because it is
   * also single-use: {@link TokenStore#spendTicket(String)} is one atomic {@code Map.remove}, and
   * this key is the other half of the trade — a ticket that outlives its one HTTP round trip and
   * its one upgrade by any meaningful margin is a value worth logging that stays worth something
   * for as long as it takes to copy out of a log.
   *
   * <p>A configuration key rather than a constant on {@link TokenStore}, for the same reason {@link
   * #accessLifetime} and {@link #refreshLifetime} are: a lifetime this class supplies from
   * configuration is a value to pass, and a literal in {@link TokenStore} or {@link AuthFilter}
   * would be a value to move.
   */
  private Duration ticketLifetime = Duration.ofSeconds(10);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getAccessLifetime() {
    return accessLifetime;
  }

  public void setAccessLifetime(Duration accessLifetime) {
    this.accessLifetime = accessLifetime;
  }

  public Duration getRefreshLifetime() {
    return refreshLifetime;
  }

  public void setRefreshLifetime(Duration refreshLifetime) {
    this.refreshLifetime = refreshLifetime;
  }

  public String getAccessCookie() {
    return accessCookie;
  }

  public void setAccessCookie(String accessCookie) {
    this.accessCookie = accessCookie;
  }

  public String getRefreshCookie() {
    return refreshCookie;
  }

  public void setRefreshCookie(String refreshCookie) {
    this.refreshCookie = refreshCookie;
  }

  public String getTokenFile() {
    return tokenFile;
  }

  public void setTokenFile(String tokenFile) {
    this.tokenFile = tokenFile;
  }

  public String getAdminHandle() {
    return adminHandle;
  }

  public void setAdminHandle(String adminHandle) {
    this.adminHandle = adminHandle;
  }

  public int getLoginMaxFailures() {
    return loginMaxFailures;
  }

  public void setLoginMaxFailures(int loginMaxFailures) {
    this.loginMaxFailures = loginMaxFailures;
  }

  public Duration getLoginLockout() {
    return loginLockout;
  }

  public void setLoginLockout(Duration loginLockout) {
    this.loginLockout = loginLockout;
  }

  public Duration getTicketLifetime() {
    return ticketLifetime;
  }

  public void setTicketLifetime(Duration ticketLifetime) {
    this.ticketLifetime = ticketLifetime;
  }
}
