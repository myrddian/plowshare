package io.aeyer.plowshare.server.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Where the gate is switched on, and where the first credential comes from.
 *
 * <p>Not in the task's file list, and shipped anyway for the reason {@code FileChannelConfig} gives
 * one directory over: a filter nothing registers is a filter no request reaches and no test can
 * drive over a real socket, so the whole of task 7 would rest on calling {@code doFilter} by hand.
 * This is the one wiring decision that belongs to the thing being built.
 *
 * <h2>The startup announcement, and the two tokens it mints</h2>
 *
 * <p>On {@link ApplicationReadyEvent} this mints a one-time bootstrap token for the browser and a
 * long-lived operator token for the CLI, writes the second to {@link AuthProperties#getTokenFile()}
 * at mode 600, and prints one line:
 *
 * <pre>
 *   Plowshare console: http://127.0.0.1:8091/?token=&lt;48 hex chars&gt;
 * </pre>
 *
 * <p><b>That line is the only place this server LOGS a secret</b> — not on refresh, not on failure,
 * not at debug level. {@link AuthController} and {@link TokenStore} both refuse a logger outright
 * for the same reason; this file holds one because it also has configuration to complain about.
 *
 * <p><b>The same URL is also written to the token file</b>, as its second line, because the console
 * token had lived only in the log and so had to be scraped out of it after every restart. The two
 * lines are the operator token, which the CLI presents as {@code Authorization: Bearer}, and then
 * the console URL, which carries the one-time bootstrap token the browser exchanges at {@code POST
 * /v1/auth}. They are different credentials and neither works where the other does — measured: the
 * operator token is a 200 on {@code /v1/search/providers} and a 401 on {@code /v1/auth}.
 *
 * <p>The operator token stays the FIRST line so that a reader taking the head of this file gets
 * what it always got. A file is a stopgap either way: it can only serve a client on this machine,
 * and both the CLI and the console can be remote, which is why {@code PLOWSHARE_TOKEN} exists and
 * why proper accounts are the real answer — see {@code implementation rationale}.
 *
 * <p>Why two tokens rather than the design's one is argued where the second one is minted, {@link
 * TokenStore#acceptOperator(String)}. Why the announcement is off unless {@code
 * PlowshareServerApplication.main} switched it on — and it is off in every test context in this
 * repository — is argued on the property that switches it, {@link AuthProperties#getTokenFile()}.
 *
 * <h2>A {@link FilterRegistrationBean}, not a bare {@code Filter} bean</h2>
 *
 * <p>So that the URL pattern and the order are <b>written down</b> rather than left to whatever the
 * framework does with a filter it finds: a reader asking "what does this run on, and before what?"
 * gets an answer from this file.
 *
 * <p><b>{@link AuthFilter} is deliberately not also a bean of its own.</b> Two ways of offering one
 * object to the container is how it ends up registered twice, and a request checked twice is
 * harmless only for as long as the check stays idempotent — which is not a property anybody would
 * notice losing. That this file produces exactly one registration, over {@code /*}, is <b>measured
 * against the container's own registry</b> rather than argued here: {@code
 * AuthFilterTest.the_filter_is_registered_once_and_over_everything}.
 *
 * <p>The order puts it near the front. Anything running before it sees an unauthenticated request,
 * so the only things that should are the container's own: {@code /*} and an early order together
 * mean the answer to "what can an anonymous caller reach?" is "this filter".
 *
 * <h2>And one configuration this refuses to start under</h2>
 *
 * <p>{@link AuthFilter} reads {@code getServletPath() + getPathInfo()}, which is the request path
 * relative to the <em>servlet mapping</em>, and its rule is written against the path relative to
 * the <em>context</em>. Those are the same string only while {@code DispatcherServlet} is mapped at
 * {@code /}. A context path does not disturb it — the specification keeps the context path out of
 * {@code getServletPath()}, and {@code --server.servlet.context-path=/ps} was measured still
 * refusing {@code GET /ps/v1/jobs} — but {@code spring.mvc.servlet.path} moves the dispatcher, and
 * then the filter is handed {@code /api/v1/jobs} where its rule wants {@code /v1/jobs}. Measured on
 * this wiring: {@code --spring.mvc.servlet.path=/api} answered an anonymous {@code GET
 * /api/v1/jobs} with a <b>200 from the controller</b>. Every {@code /v1} route, both sockets
 * included, open — and nothing failed, nothing warned.
 *
 * <p><b>So this class refuses the boot instead.</b> That is the half of the fix that matters: the
 * property is one an operator sets, on a machine no test of this repository runs on, and a test can
 * only keep the tree honest. It is unconditional rather than skipped when {@code
 * plowshare.auth.enabled} is false, because a rule with a second state is a rule with a hole, and
 * an operator who wants the gate off already has one key that says so.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

  /**
   * For the announcement and for two complaints about configuration. No method here logs a token
   * except {@link #announce}, and that one logs it once.
   */
  private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

  /**
   * Owner read and write, and nothing for anybody else — the same handling {@code
   * ~/.config/plowshare/lm-key} gets, though not for the same reason: {@link
   * TokenStore#acceptOperator(String)} argues why an accept-side secret on disk is a new exposure
   * and not one that precedent already covered.
   */
  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rw-------");

  /**
   * Early, and not at {@link Ordered#HIGHEST_PRECEDENCE} itself.
   *
   * <p>Spring Boot's character-encoding and request-context filters register at the very front, and
   * displacing them would mean this filter decides about a request whose encoding has not been
   * settled. A hundred is room for those and for nothing that needs to see an unauthenticated
   * request.
   */
  private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

  /**
   * The store the filter asks and {@link AuthController} fills.
   *
   * <p>{@link Clock#systemUTC()} rather than a bean: this repository has no {@code Clock} bean and
   * inventing one here would make every context that wants a fixed clock override a bean it does
   * not otherwise know about. {@code TokenStoreTest} drives the same class with a clock it
   * controls, so nothing about expiry is left to a wall clock in a test.
   */
  @Bean
  public TokenStore tokenStore(
      AuthProperties properties,
      org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate>
          jdbc,
      org.springframework.beans.factory.ObjectProvider<
              org.springframework.transaction.PlatformTransactionManager>
          managers) {
    TokenStore tokens =
        new TokenStore(
            Clock.systemUTC(),
            properties.getAccessLifetime(),
            properties.getRefreshLifetime(),
            properties.getTicketLifetime());
    // Narrow web-filter fixtures have no database. Production has both beans.
    var database = jdbc.getIfAvailable();
    var transactions = managers.getIfAvailable();
    if (database != null) {
      if (transactions == null)
        throw new IllegalStateException("Account session persistence needs a transaction manager.");
      tokens.withServiceCredentials(new ServiceCredentials(database, Clock.systemUTC()));
      tokens.withDurableSessions(
          new DurableSessions(
              database,
              transactions,
              Clock.systemUTC(),
              properties.getAccessLifetime(),
              properties.getRefreshLifetime()));
    }
    return tokens;
  }

  /**
   * The throttle {@link AuthController#login} consults, on the same reasoning as {@link
   * #tokenStore} just above: this repository has no {@code Clock} bean, so the seam is a
   * constructor argument and production passes the wall clock directly rather than adding a bean
   * nothing else asks for. See {@link LoginAttempts} for what it keeps and what it gives up by
   * keeping it in memory, and for why {@link AuthProperties#getLoginMaxFailures()} and {@link
   * AuthProperties#getLoginLockout()} are read here rather than fixed as constants on that class.
   */
  @Bean
  public LoginAttempts loginAttempts(AuthProperties properties) {
    return new LoginAttempts(
        Clock.systemUTC(), properties.getLoginMaxFailures(), properties.getLoginLockout());
  }

  /**
   * The registration, and the one configuration it will not start under.
   *
   * <p>See the class note for the measurement. The check is here rather than in a
   * {@code @PostConstruct} or a listener so that it runs while the thing it protects is being
   * built: no {@link AuthFilter} is constructed at all under a mapping where it could not do its
   * job.
   *
   * @param mvc read for {@code spring.mvc.servlet.path} only. It is a bean wherever the dispatcher
   *     is, which is wherever this filter has anything to gate.
   * @throws IllegalStateException if the dispatcher is mapped anywhere but {@code /}, naming the
   *     property, its value and what it would open
   */
  @Bean
  public FilterRegistrationBean<AuthFilter> authFilter(
      TokenStore tokens,
      AuthProperties properties,
      WebMvcProperties mvc,
      org.springframework.beans.factory.ObjectProvider<AdminStore> admins) {
    String dispatcher = mvc.getServlet().getPath();
    if (!"/".equals(dispatcher)) {
      throw new IllegalStateException(
          "spring.mvc.servlet.path is '"
              + dispatcher
              + "'. AuthFilter reads"
              + " getServletPath()+getPathInfo(), which is the path relative to"
              + " the servlet mapping and not to the context, so with the"
              + " dispatcher moved off / the gate would be handed '"
              + dispatcher
              + "/v1/...' where its rule expects '/v1/...' — and every /v1 route,"
              + " the /v1/events and /v1/files sockets included, would be open to"
              + " an anonymous caller with nothing logged. Leave it at /."
              + " server.servlet.context-path is unaffected and is the key for"
              + " serving this application under a prefix.");
    }
    FilterRegistrationBean<AuthFilter> registration =
        new FilterRegistrationBean<>(
            new AuthFilter(tokens, properties).withAccounts(admins.getIfAvailable()));
    // Everything, and the filter decides. The alternative — registering only
    // on /v1/* — would put the list of gated paths in two places, and the
    // one in this file is the one nobody would think to update.
    registration.addUrlPatterns("/*");
    registration.setOrder(ORDER);
    registration.setName("plowshareAuthFilter");
    return registration;
  }

  /**
   * The startup announcement: two tokens, one file, one line.
   *
   * <p>On {@link ApplicationReadyEvent} rather than {@code @PostConstruct} or a {@code
   * CommandLineRunner}, because the line names a port and the port is not known until the web
   * server is listening — {@code server.port=0} is the case that makes that concrete, and it is the
   * case every test in this repository runs under.
   *
   * <p>A lambda listener bean and not {@code @EventListener} on a method: this class is a
   * {@code @Configuration} whose other beans are wiring, and a bean of the listener type says what
   * it is in its own declaration.
   */
  @Bean
  public ApplicationListener<ApplicationReadyEvent> consoleAnnouncement(
      TokenStore tokens, AuthProperties properties) {
    return ready -> {
      if (properties.isFirstRunSetup()) return;
      String configured = properties.getTokenFile();
      if (configured == null || configured.isBlank()) {
        // The default, and the state every test context is in. See
        // AuthProperties.getTokenFile() for why the default is off.
        return;
      }
      if (!(ready.getApplicationContext() instanceof WebServerApplicationContext web)) {
        // No port, so no URL, so nothing an operator could visit. Said
        // out loud rather than skipped in silence, and it names no token
        // because it mints none.
        log.warn(
            "plowshare.auth.token-file is set to {} but this context serves no web"
                + " server, so there is no console URL to announce and no token was"
                + " minted or written.",
            configured);
        return;
      }
      announce(tokens, properties, Path.of(configured), web.getWebServer().getPort());
    };
  }

  /**
   * Mint, write, print — in that order, so a warning about the file precedes the line an operator
   * is reading for.
   *
   * <p>Package-private so {@code AuthControllerTest} can drive it without a container, which is
   * what lets the file-mode assertion run against a temp directory instead of the operator's own
   * {@code ~/.config/plowshare/console-token}.
   *
   * <p><b>A failed write does not stop the boot.</b> The browser's way in is the printed line and
   * it does not go through the file, so refusing to start would trade a CLI that cannot
   * authenticate for a server that does not exist. The warning names the path and the reason and
   * never the token.
   *
   * <p><b>And a failed write leaves nothing behind in the store.</b> The operator token is minted,
   * written, and only then handed to {@link TokenStore#acceptOperator(String)} — that last call is
   * what makes it a credential. Doing it the other way round, which is what this did, left a
   * never-expiring and never-swept access grant in the store on exactly the path where the warning
   * below tells the operator the CLI has none: measured, with an unwritable path, at {@code
   * trackedRecords()} 0 before and 2 after. Nothing could present that grant — the only copy of its
   * token was the local that went out of scope — but it is a live credential for the life of the
   * process, and the store and the warning disagreeing about whether one exists is the kind of gap
   * that is only ever found the hard way.
   */
  static void announce(TokenStore tokens, AuthProperties properties, Path file, int port) {
    String bootstrap = tokens.mintBootstrap();
    // Mint, write, then record. See the note above on why that order and not
    // the other one; Tokens.mint() is the one generator in this server, so
    // taking the value here rather than from the store weakens nothing.
    String operator = Tokens.mint();
    String consoleUrl = "http://127.0.0.1:" + port + "/?token=" + bootstrap;
    try {
      // Two lines, and the operator token stays the first of them so that a
      // reader taking the head of this file still gets what it always got.
      writeOperatorToken(file, operator + "\n" + consoleUrl + "\n");
      tokens.acceptOperator(operator);
    } catch (IOException | UnsupportedOperationException notWritten) {
      log.warn(
          "could not write the operator token to {}: {}. The console URL below still"
              + " works; the CLI has no credential on this machine until it can be"
              + " written, and PLOWSHARE_TOKEN is the other way to give it one.",
          file,
          notWritten.toString());
    }
    // THE ONE LINE IN THIS SERVER THAT PRINTS A SECRET. Not on refresh, not
    // on failure, not at debug level, and not a second time here.
    log.info("Plowshare console: http://127.0.0.1:{}/?token={}", port, bootstrap);
  }

  /**
   * The operator token, owner-readable and nothing else: staged in a sibling file that never
   * existed before, then renamed over the target.
   *
   * <h2>Why not create-or-chmod, then write in place</h2>
   *
   * <p>That is what this did, and it had three holes that the staged write closes together rather
   * than one at a time.
   *
   * <p><b>It followed a symlink.</b> {@link Files#setPosixFilePermissions} and {@link
   * Files#writeString} both resolve links, so with the target a symlink left by somebody else the
   * token was written into the <em>link's</em> target and that target was chmod'ed to 600 —
   * measured, on a temp path, by {@code
   * a_symlink_at_the_token_path_is_replaced_rather_than_followed}. Not reachable at the default
   * path, where {@code ~/.config/plowshare/} is the operator's own and not writable by others;
   * reachable the moment {@code --plowshare.auth.token-file} names a shared directory. It is worth
   * closing because the mode is the whole protection on this file, and under a symlink that mode
   * lands on a path the operator did not choose.
   *
   * <p><b>It truncated before it wrote.</b> {@code Files.writeString} with no options opens {@code
   * CREATE}, {@code TRUNCATE_EXISTING} and {@code WRITE}, so on a restart the old token left the
   * file before the new one entered it. That window is short and this repository has not caught a
   * reader inside it; it is named because a rename does not have one to catch.
   *
   * <p><b>And it needed a special case for a file already there</b>, which is where the symlink
   * hole lived. A rename has none: {@link StandardCopyOption#REPLACE_EXISTING} replaces whatever
   * occupies the name — a regular file, or a symlink, as the link itself and not as its target,
   * because {@code rename(2)} does not resolve the destination.
   *
   * <p>So: {@code Files.createTempFile} in the target's own directory, which creates with {@code
   * CREATE_NEW} and <em>with</em> the mode attribute rather than creating and then chmod'ing —
   * between those two calls the file exists at whatever the umask allows and every process on the
   * box can read it. Then {@link StandardCopyOption#ATOMIC_MOVE}, which is a rename within one
   * directory and therefore one filesystem, so a reader of this path sees either the old token or
   * the new one and never a partial file.
   *
   * <p>POSIX permissions, with no fallback for a filesystem that has none. That is deliberate
   * rather than an oversight: the whole protection on this file is its mode, and a branch that
   * quietly wrote it without one would be worse than the {@link UnsupportedOperationException} the
   * caller turns into a warning.
   *
   * <p>A failure anywhere after the staging file exists deletes it, so a directory does not
   * accumulate half-written tokens over restarts. The delete is suppressed into the original
   * failure rather than replacing it: what the operator needs in the warning is why the token could
   * not be written, not why the leftover could not be removed.
   *
   * @param file where it goes, with its parent directory created if absent
   * @param token the operator token, which this method neither logs nor returns
   */
  static void writeOperatorToken(Path file, String contents) throws IOException {
    // Absolute, so that a relative path has a parent to stage the sibling in
    // rather than a null one to special-case.
    Path target = file.toAbsolutePath();
    Path directory = target.getParent();
    Files.createDirectories(directory);
    Path staged =
        Files.createTempFile(
            directory, ".console-token", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    try {
      Files.writeString(staged, contents, StandardCharsets.UTF_8);
      Files.move(
          staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException | Error failed) {
      try {
        Files.deleteIfExists(staged);
      } catch (IOException leftBehind) {
        failed.addSuppressed(leftBehind);
      }
      throw failed;
    }
  }

  /**
   * {@code ~/.config/plowshare/console-token}, which is what {@code
   * PlowshareServerApplication.main} sets the property to.
   *
   * <p>Public because the CLI reads the same path and the two must agree; {@code
   * AuthControllerTest} asserts they do, over the client class that resolves it on the other side.
   * It is not shared through a common module because {@code plowshare-protocol} is the wire
   * vocabulary the two halves exchange, and where one machine keeps its own credential is not part
   * of it.
   */
  public static Path defaultTokenFile() {
    return Path.of(System.getProperty("user.home"), ".config", "plowshare", "console-token");
  }
}
