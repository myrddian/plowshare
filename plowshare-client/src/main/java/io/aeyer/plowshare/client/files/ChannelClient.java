package io.aeyer.plowshare.client.files;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The socket this process opens so the server can ask it about its own files.
 *
 * <h2>okhttp's WebSocket, and never Spring's</h2>
 *
 * <p>This module has no Spring in it and must not gain any — its build file says so and the
 * invariant pass greps for it. okhttp already ships a WebSocket client and okhttp is already the
 * only HTTP library here, so the channel costs this module <b>zero new dependencies</b>. Adding
 * {@code spring-websocket} to reach a socket that is three classes away would be a containment
 * failure dressed as a convenience.
 *
 * <h2>Client-initiated, and the direction is the design</h2>
 *
 * <p>This process dials out and registers a session id; the server sends correlated requests down
 * the same socket and never dials in. A server that had to reach a laptop would need to know where
 * it is, which is the one thing a machine behind somebody's network cannot promise — and the party
 * that owns the files is the party that decides whether it is reachable at all.
 *
 * <h2>Every request is answered on its own thread</h2>
 *
 * <p>Measured: okhttp delivers {@code onMessage} on one reader thread per socket. Answering there
 * would serialise the file work, and a single slow {@code glob} over a large repository would hold
 * up three other requests until <em>their</em> deadlines fired on the server — turning one slow
 * answer into four ended runs. So each request is handed to a virtual thread and the reader goes
 * back to reading; correlation is the id, so nothing depends on the order answers go out in.
 *
 * <p><b>A {@code run} is the case this matters most for.</b> It holds its thread for as long as the
 * command takes — minutes — and the {@code cancel} that stops it arrives on this same socket while
 * it does. Answered on the reader, the cancel would wait behind the command it exists to kill.
 * okhttp's {@code send} is safe from any thread, so answers need no lock of their own. When the
 * socket goes, every command still running is cancelled: nobody is left to read it.
 *
 * <p>Virtual threads rather than a bounded pool because a bounded pool is the same wedge with a
 * larger number: the server already bounds how many requests can be outstanding, since each one is
 * a job blocked on it.
 *
 * <h2>{@code onClosing} answers the close, because nothing else will</h2>
 *
 * <p><b>Measured on okhttp 4.12:</b> when the server closes, this listener gets {@code onClosing}
 * and then <em>nothing</em> — {@code onClosed} never arrived within ten seconds — unless the
 * listener completes the handshake by calling {@code close} itself. A client that only logged
 * {@code onClosing} would sit holding a half-closed socket for as long as the process lived.
 */
public final class ChannelClient implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(ChannelClient.class);

  /**
   * Matches {@code FileChannelHandler.PATH} on the server. The two are separate literals in
   * separate modules on purpose — this one is a URL this process dials, and the server's is a route
   * it publishes — and a mismatch fails loudly at the upgrade with a 404 rather than quietly, which
   * is measured: okhttp reports it as {@code onFailure} carrying the response code.
   */
  public static final String PATH = "v1/files";

  /** The query parameter naming this session, matching the server's. */
  public static final String SESSION_PARAM = "session";

  /**
   * The three parameters that declare a presence, matching the server's.
   *
   * <p>Separate literals in a separate module, exactly as {@link #PATH} and {@link #SESSION_PARAM}
   * already are and for their reason: this is a URL this process dials and the server's is a route
   * it publishes. A mismatch here is quieter than a mismatched path, which is worth saying — the
   * socket opens and serves files, and the session simply roots nothing. {@code FileChannelTest}
   * and {@code RemoteWiringTest} both drive this class against the real handler, which is what
   * makes the two spellings agree by measurement rather than by intention.
   */
  public static final String PROJECT_PARAM = "project";

  /** See {@link #PROJECT_PARAM}. */
  public static final String MACHINE_PARAM = "machine";

  /** See {@link #PROJECT_PARAM}. */
  public static final String ROOT_PARAM = "root";

  /**
   * The close code for an ordinary shutdown. 1000 is "normal closure"; the server logs it and fails
   * nothing that was not already outstanding.
   */
  private static final int NORMAL = 1000;

  /**
   * Where the server is, with no path of this client's own on it. Kept alongside {@link #url} so
   * {@link #dial} builds the second role's URL from the same base rather than by editing this one's
   * path — a base URL with a prefix on it, which HttpServerClient preserves, would otherwise be
   * lost.
   */
  private final HttpUrl base;

  private final HttpUrl url;
  private final String session;
  private final ClientEnforcer enforcer;
  private final OkHttpClient http;
  private final ExecutorService answering;
  private final ObjectMapper json =
      new ObjectMapper()
          // A server that grows a field must not break a client that has not
          // been rebuilt: the two halves ship separately, and the failure mode
          // of the strict setting is every file request refused over a key
          // nobody reads. HttpServerClient's mapper is configured the same way
          // for the same reason.
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private volatile WebSocket socket;

  /**
   * Counted down the first time the upgrade settles either way, so {@link #awaitOpen} waits for an
   * answer rather than for a duration.
   *
   * <p>A latch and not a poll of {@link #state}: the two outcomes a caller is waiting between are
   * "open" and "refused", and both arrive as a callback. Polling would turn a 404 that came back in
   * two milliseconds into however long the caller was prepared to wait.
   */
  private final CountDownLatch settled = new CountDownLatch(1);

  private volatile State state = State.UNOPENED;

  private volatile String refusal;

  /**
   * @param baseUrl where the server is, as an <em>HTTP</em> URL — the same one {@code
   *     HttpServerClient} is given. Turned into {@code ws} / {@code wss} here rather than
   *     configured twice, because two settings naming one server is how a client ends up talking to
   *     two of them
   * @param session the id this client registers under. Minted by whoever builds this and never by a
   *     model
   * @param enforcer what actually answers, checked against this session's current workspace
   */
  public ChannelClient(String baseUrl, String session, ClientEnforcer enforcer) {
    this(baseUrl, session, enforcer, null, null);
  }

  /**
   * @param baseUrl where the server is, as an <em>HTTP</em> URL — see the three-argument
   *     constructor
   * @param session the id this client registers under
   * @param enforcer what actually answers
   * @param token an access token to present as {@code Authorization: Bearer} on the upgrade, or
   *     null for none. <b>A header and never the query string</b>, even though the session id
   *     already rides there: a URL reaches access logs and referrers, and this project permits
   *     exactly one secret in a URL — the single-use bootstrap token, which is spent immediately.
   *     <p>This process can present a header because okhttp sets one freely. A browser cannot —
   *     {@code new WebSocket(url)} takes a URL and a subprotocol list — which is why the server's
   *     filter also reads a cookie. Two transports, one decision, and this is the CLI's.
   */
  public ChannelClient(String baseUrl, String session, ClientEnforcer enforcer, String token) {
    this(baseUrl, session, enforcer, token, null);
  }

  /**
   * @param baseUrl where the server is, as an <em>HTTP</em> URL
   * @param session the id this client registers under
   * @param enforcer what actually answers
   * @param token an access token, or null — see the four-argument constructor
   * @param rooted what this machine declares it roots, or null for a session that lends its files
   *     and roots nothing. <b>On the upgrade and not in a later frame</b>, because a presence has
   *     to be in force before the first run can be routed: a window in which this session holds a
   *     disk and roots nothing is a window in which a run gets the smaller set and nothing says so,
   *     which is the failure {@code SessionClient}'s "both roles, and then a run — in that order,
   *     loudly" was written against
   */
  public ChannelClient(
      String baseUrl, String session, ClientEnforcer enforcer, String token, Rooting rooted) {
    HttpUrl parsed = HttpUrl.parse(Objects.requireNonNull(baseUrl, "baseUrl"));
    if (parsed == null) {
      // At construction rather than at the first file request, for
      // HttpServerClient's reason: a mistyped URL is a configuration error,
      // and discovering it inside a job makes it look like the workspace is
      // empty.
      throw new IllegalArgumentException("not a usable server URL: " + baseUrl);
    }
    this.session = Objects.requireNonNull(session, "session");
    this.enforcer = Objects.requireNonNull(enforcer, "enforcer");
    // okhttp's HttpUrl has no ws scheme — it maps ws to http and wss to https
    // itself when a request is built — so the URL is assembled as http here
    // and okhttp performs the upgrade.
    this.base = parsed;
    HttpUrl.Builder dialling =
        parsed
            .newBuilder()
            .addPathSegments(PATH)
            .addQueryParameter(SESSION_PARAM, session)
            .addQueryParameter("source", "1");
    if (rooted != null) {
      // All three or none. The server reads fewer than three as "this
      // session declares nothing", so a partial claim would be silently
      // dropped rather than refused -- and there is no partial Rooting to
      // send: that record's components are all required.
      dialling
          .addQueryParameter(MACHINE_PARAM, rooted.machine())
          .addQueryParameter(ROOT_PARAM, rooted.root())
          .addQueryParameter(PROJECT_PARAM, rooted.project());
    }
    this.url = dialling.build();
    OkHttpClient.Builder building = new OkHttpClient.Builder();
    if (token != null && !token.isBlank()) {
      // On the client and not on each request, so that both sockets this
      // class opens — its own in open() and the listener in dial() —
      // authenticate, and so that a third one added later cannot be the
      // one that forgets. okhttp runs application interceptors on a
      // WebSocket upgrade like any other call — measured by
      // ConversationEndToEndTest, which attaches a session through this
      // constructor against a server with the gate on, and whose two roles
      // are refused with 401 the moment this header stops being sent.
      String header = "Bearer " + token.trim();
      building.addInterceptor(
          chain ->
              chain.proceed(chain.request().newBuilder().header("Authorization", header).build()));
    }
    this.http = building.build();
    this.answering = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Open the socket. Returns as soon as the upgrade is under way: a client that refused to start
   * against a server still coming up would leave a harness with no file tools, which reads to a
   * model as a machine with no files on it.
   */
  public void open() {
    socket = http.newWebSocket(new Request.Builder().url(url).build(), new Frames());
    log.info("File channel opening to {} as session '{}'.", url.redact(), session);
  }

  /**
   * Wait for the upgrade to settle, and say whether it opened.
   *
   * <p><b>Nothing on this side of the wire could answer that question before this method
   * existed</b>, and {@link #open}'s deliberate fire-and-forget is why. That is the right default
   * for the MCP client — a harness with no file tools is worse than one whose first request waits —
   * and it is the wrong default for anything that is about to submit a run under this session:
   * {@code AgentController} accepts a session id it has never seen attached, runs the job against
   * the server's own filesystems and reports nothing amiss, so a submission that races the upgrade
   * is a quietly smaller capability. The tests that already drive this class settle the same
   * question by polling the server's {@code SessionRegistry}, which is a thing only a test inside
   * the server module can reach.
   *
   * <p>Returns as soon as the upgrade succeeds <em>or</em> is refused, so a server answering 404 in
   * two milliseconds costs two milliseconds and not {@code timeout}. A {@code false} return means
   * one of those two things and {@link #refusal} says which.
   *
   * @param timeout how long to wait for an answer at all
   * @return whether the socket is open
   */
  public boolean awaitOpen(Duration timeout) throws InterruptedException {
    Objects.requireNonNull(timeout, "timeout");
    settled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    return state == State.OPEN;
  }

  /** Whether this socket is carrying file requests right now. */
  public boolean isOpen() {
    return state == State.OPEN;
  }

  /**
   * What has happened to this socket, for a caller that has to explain why it is not usable. A
   * socket that never opened and one that opened and went away send a reader to different places.
   */
  public State state() {
    return state;
  }

  /**
   * How the socket failed, as an exception <em>type</em> and the HTTP status if the upgrade got one
   * — or null if nothing has failed.
   *
   * <p><b>Never the exception's message</b>, which is {@code Scribe}'s rule and its reason: a
   * transport-layer message can carry a URL, a header or a request body, and {@code
   * OpenAiTransport.withheldIfItQuotesTheKey} exists because the LM Studio endpoint really does
   * echo a submitted token back in an error body. Nothing this class talks to is that endpoint, and
   * the rule is kept anyway, because the value of a rule with no exceptions is that nobody has to
   * decide.
   *
   * <p>The status is the part a reader can act on: a 404 is a server with no such channel, and a
   * refused connection is a server that is not there.
   */
  public String refusal() {
    return refusal;
  }

  /**
   * Open another socket to the same server, under the same session, on this client's own connection
   * pool and dispatcher.
   *
   * <h2>Why the second role borrows this rather than building its own</h2>
   *
   * <p>{@code InvariantsTest.an_http_client_is_held_by_exactly_three_files_in_main} names the three
   * files in {@code main} allowed to hold an {@code OkHttpClient} — this one, {@code
   * HttpServerClient} and {@code OpenAiTransport} — <b>by name and not by count</b>, so a fourth
   * holder fails the build and a swap cannot net out to three. The listener role is a second socket
   * to the same host, and its assertion message says exactly what the objection would be: "a fourth
   * place configuring its own timeouts, retries and connection pool for the same endpoints".
   * Handing the second role this client is not a way around that guard; it is the thing the guard
   * is asking for.
   *
   * <p>So this method is deliberately about a <em>socket</em> and not about events: this class
   * knows a base URL, a session and an HTTP client, and nothing here needs to know what the frames
   * on the other socket mean. The caller supplies the path, which keeps the events path a literal
   * in the class that dials it — the same separation {@link #PATH} already has from the server's
   * route, and which fails at the upgrade with a 404 rather than quietly. That 404 is the runtime
   * half; the compile-time half cannot live in this module, because a test here has no server to
   * compare a literal against. It is {@code EventChannelTest}'s {@code
   * the_production_wiring_publishes_the_listener_on_the_path_the_client_dials}, one module over,
   * beside the pair {@code FileChannelTest} already holds for {@link #PATH}.
   *
   * <p><b>The returned socket must be closed before this client is</b>, because {@link #close}
   * shuts down the dispatcher and evicts the pool both sockets are on.
   *
   * @param pathSegments the path to dial, relative to the base URL and without a leading slash —
   *     the spelling {@link #PATH} uses
   * @param frames what to do with what comes back
   */
  public WebSocket dial(String pathSegments, WebSocketListener frames) {
    Objects.requireNonNull(pathSegments, "pathSegments");
    Objects.requireNonNull(frames, "frames");
    HttpUrl dialled =
        base.newBuilder()
            .addPathSegments(pathSegments)
            .addQueryParameter(SESSION_PARAM, session)
            .build();
    return http.newWebSocket(new Request.Builder().url(dialled).build(), frames);
  }

  @Override
  public void close() {
    WebSocket open = socket;
    if (open != null) {
      open.close(NORMAL, "the client is shutting down");
    }
    state = State.CLOSED;
    // Released rather than left for the timeout: a caller parked in
    // awaitOpen while another thread closes this client is waiting for an
    // answer that is now never coming, and "closed" is that answer.
    settled.countDown();
    enforcer.cancelAll();
    answering.shutdown();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }

  /**
   * Where this socket has got to.
   *
   * <p>Diagnostic, and read by whoever has to say why a run cannot be submitted. {@link #REFUSED}
   * and {@link #CLOSED} are the same fact to okhttp — {@code onFailure} — and are two different
   * sentences to a person: one is a server that would not have this client, the other is a machine
   * that went away.
   */
  public enum State {

    /** {@link #open} has not been called, or the upgrade has not settled. */
    UNOPENED,

    /** The upgrade completed and nothing has closed it. */
    OPEN,

    /** It was open and is not any more — closed by either end, or lost. */
    CLOSED,

    /**
     * It never opened. The server answered something other than 101, or was not reachable at all.
     */
    REFUSED
  }

  private void handle(String frame) {
    FileRequest request;
    try {
      request = json.readValue(frame, FileRequest.class);
    } catch (IOException notARequest) {
      // Dropped, because there is no id to answer on. The server's own
      // deadline is what reports it, which is the honest outcome: a server
      // sending frames this client cannot read is one whose requests
      // cannot be served.
      log.warn(
          "The server sent a frame this client could not read as a request: {}",
          notARequest.toString());
      return;
    }
    answering.execute(() -> reply(enforcer.answer(request)));
  }

  private void reply(FileReply answer) {
    WebSocket open = socket;
    try {
      if (open == null || !open.send(json.writeValueAsString(answer))) {
        // send returns false when the socket is closing or closed. There
        // is nowhere to put the answer and nobody left to read it; the
        // server has already failed this request on the close, which is
        // the event that beat us here.
        log.debug("Could not answer request '{}': the file channel is closed.", answer.id());
      }
    } catch (IOException notSerialisable) {
      // A reply this client built and cannot write down is a bug here, and
      // it must not take the reader thread with it: the request runs out
      // its deadline and the run ends saying so, while this line is where
      // the cause is.
      log.error("Could not serialise the answer to request '{}'", answer.id(), notSerialisable);
    }
  }

  /**
   * okhttp's callbacks, kept in one place so the close handshake and the dispatch are read
   * together.
   */
  private final class Frames extends WebSocketListener {

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      state = State.OPEN;
      settled.countDown();
      log.info("File channel open as session '{}'.", session);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      handle(text);
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
      // MEASURED: without this the handshake never completes and onClosed
      // never arrives — ten seconds of nothing on okhttp 4.12 — leaving a
      // half-closed socket for the life of the process.
      state = State.CLOSED;
      // Counted down here as well, so a caller that called awaitOpen on a
      // socket the server accepted and immediately closed gets an answer
      // rather than the whole timeout. Idempotent, which is the reason a
      // latch and not a flag.
      settled.countDown();
      enforcer.cancelAll();
      log.info("The server closed the file channel ({} {}).", code, reason);
      webSocket.close(NORMAL, null);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
      // Includes the upgrade being refused, which is what a path mismatch
      // or a server with no channel looks like: measured, okhttp reports it
      // as a ProtocolException naming the status it got instead of 101. The
      // code is logged because 404 and 500 send a reader to different
      // places.
      // The type and the status, never the message: Scribe's rule, and
      // refusal()'s javadoc says why it is kept here even though nothing
      // on this socket has ever carried a key.
      refusal =
          failure.getClass().getName() + (response == null ? "" : " (" + response.code() + ")");
      // A socket that had opened is one that went away; one that had not is
      // one this server would not have. okhttp calls both onFailure and
      // this is the only place the two are still distinguishable.
      state = state == State.OPEN ? State.CLOSED : State.REFUSED;
      settled.countDown();
      enforcer.cancelAll();
      log.warn(
          "The file channel to {} failed{}: {}",
          url.redact(),
          response == null ? "" : " (" + response.code() + ")",
          failure.toString());
    }
  }
}
