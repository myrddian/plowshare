package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionCloseListener;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceConflictException;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The server half of the file channel: the socket a client opened, and every request waiting on it.
 *
 * <h2>Client-initiated, and that is not an implementation detail</h2>
 *
 * <p>The client opens the socket and registers a session id on the query string. A server that
 * dialled out would need to know where every client is, which is the one thing a laptop behind a
 * network cannot promise — and the party that owns the files is the party that decides whether it
 * is reachable at all.
 *
 * <h2>Two bounds, and each one is for a failure the other cannot see</h2>
 *
 * <ul>
 *   <li><b>The close is an event.</b> {@link #afterConnectionClosed} fails every request
 *       outstanding on that session at once. Measured on this host: a clean {@code close} from an
 *       okhttp client reaches this method in 4 ms, and an abrupt {@code cancel} — which is what a
 *       killed client process looks like on the wire — in 1 ms, arriving as a transport error and
 *       then a 1006 close. That is the whole reason this is a WebSocket and not polling: the common
 *       failure is instant rather than a deadline;
 *   <li><b>a client that is connected and wedged is not a closed socket.</b> Measured: nothing
 *       fires at all and the session still reports itself open, so there is no event to hang
 *       anything on and {@link #DEADLINE} is the only instrument that case has.
 * </ul>
 *
 * <p><b>Which of the two happened is in the message and in the type</b>, because "the session
 * closed" and "the session did not answer in 30 seconds" send an operator to different places even
 * though both end the run the same way. The first raises {@link SessionGoneException} and the run
 * ends {@code SESSION_GONE}; the second raises the plain {@link WorkspaceUnavailableException} and
 * it ends {@code UNAVAILABLE}. <b>Which of this class's throw sites is which, and why the deadline
 * is not one of them, is argued in {@link SessionGoneException} and deliberately not restated
 * here</b> — it is one rule and it drifts the moment there are two copies of it. What cannot be
 * distinguished is a wedged client from a merely slow one, and no instrument for that can exist: a
 * WebSocket ping would prove the client's reader loop is alive, which is not the question — a
 * client wedged on one file read answers pings from another thread the whole time — and a half-open
 * connection whose far host lost power sends nothing at all until a TCP keepalive expires, which is
 * minutes. So the deadline's sentence says what is known, that no answer arrived in the time
 * allowed, and does not guess which it was.
 *
 * <h2>Sends are serialised, and it is a lock rather than {@code synchronized}</h2>
 *
 * <p><b>Measured, and it would have happened:</b> eight threads calling {@code
 * WebSocketSession.sendMessage} on one session at once produced seven {@code
 * IllegalStateException}s — {@code The remote endpoint was in state [TEXT_PARTIAL_WRITING]} —
 * because Spring's session is explicitly not safe for concurrent sends. Jobs run concurrently on
 * virtual threads and each one blocked on a file request is a send, so this is the ordinary case
 * rather than a corner. With a lock: eight threads, 320 sends, 320 distinct frames delivered, zero
 * errors.
 *
 * <p>A {@link ReentrantLock} and not {@code synchronized}: on Java 21 a virtual thread that blocks
 * inside a {@code synchronized} block pins its carrier, and every caller here is a virtual thread
 * about to block on a socket write. The lock is the one shape that lets the carrier go.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It does not remember what a client's roots are. {@code FileProvider.roots()} says explicitly
 * that they may change between two calls and that no caller may cache them, so {@code
 * RemoteProvider} asks over this channel every time and a copy kept here would be the cache that
 * promise forbids. "Re-advertised on {@code workspace_set}" is then satisfied by construction
 * rather than by a frame: every ask gets the workspace the session holds at that moment.
 *
 * <p><b>And it no longer keeps a map of its own sessions.</b> It used to, and what replaced it is
 * argued at {@link #sessions}.
 *
 * <h2>The connection is registered in the one {@link SessionRegistry}</h2>
 *
 * <p>An open socket here <em>is</em> the {@link Role#FILE_PROVIDER} role of the session named on
 * its query string, and that is what {@code AgentsConfig.runProviders} reads when it decides
 * whether a run reaches the machine that submitted it. Until this was wired the role was never
 * populated by anything and the whole remote path was unreachable from production.
 */
public final class FileChannelHandler extends TextWebSocketHandler implements SessionChannel {

  private static final Logger log = LoggerFactory.getLogger(FileChannelHandler.class);

  /**
   * Where a client opens the socket. Under {@code /v1} with the rest of the surface, because it is
   * the same version of the same contract.
   */
  public static final String PATH = "/v1/files";

  /**
   * The query parameter naming the session. The client picks it; no model ever sees it, exactly as
   * {@code AgentTool}'s {@code home} is not an argument the model can set.
   */
  public static final String SESSION_PARAM = "session";

  /**
   * The query parameter naming the project this session roots, if it roots one.
   *
   * <h2>Three parameters, and all three or none</h2>
   *
   * <p>A presence is {@code <MACHINE>/<PATH>/<PROJ_NAME>} and none of the three can be supplied by
   * this server. So they arrive together on the upgrade, beside the session id that is already
   * there, and a socket that sends fewer than three declares nothing — which is the state every
   * client built before presence is in, and it still lends its files.
   *
   * <h2>Why the upgrade and not a frame</h2>
   *
   * <p><b>Because a presence must be in force before the first run can be routed</b>, and a frame
   * sent after the socket opens is a window in which the session holds a disk and roots nothing.
   * That window is silent: a run submitted inside it gets the smaller set and reports nothing
   * amiss, which is the exact failure {@code SessionClient}'s "both roles, and then a run — in that
   * order, loudly" was written against. The upgrade is the one moment both halves are known and
   * nothing is running yet.
   *
   * <p><b>What it costs, said rather than discovered:</b> a root is a filesystem path and a URL
   * reaches access logs. That is a considered trade and not an oversight — this project permits
   * exactly one secret in a URL and a path is not one, the same path is already visible to this
   * server in every {@code ROOTS} reply, and the alternative buys a new frame type and the window
   * above. A machine name and a project name are likewise names rather than credentials.
   */
  public static final String PROJECT_PARAM = "project";

  /**
   * The query parameter naming the machine the client sits on. <b>A client property with a default
   * of the hostname</b>, and the server never guesses it: it cannot know what box a socket came
   * from, and a reverse lookup of a peer address would be a different fact wearing the same name.
   * See {@code PROJECT_PARAM} for why it rides here.
   */
  public static final String MACHINE_PARAM = "machine";

  /**
   * The query parameter naming where on that machine the project sits, absolute, as the client
   * spells it. See {@code PROJECT_PARAM}.
   */
  public static final String ROOT_PARAM = "root";

  /**
   * The query parameter by which a client asks to be told its claim has landed: {@code ready=1},
   * answered with one text frame, {@code {"ready":true,"project":"<name>"}}, once the declaration,
   * the row and the attach have all happened.
   *
   * <p><b>Why it is asked for rather than always sent.</b> {@code ChannelClient} reads every frame
   * as a {@code FileRequest} with unknown properties ignored, so a frame it did not expect would
   * parse as a request with no id and no op and be answered with a refusal nobody is waiting for.
   * The TypeScript client asks; the Java one does not and hears nothing new.
   *
   * <p><b>Why a frame at all.</b> A browser-shaped socket's {@code open} fires when the 101
   * arrives, and {@link #afterConnectionEstablished} runs after the 101 has gone out — so a client
   * that asked who answers in its project on {@code open} could be answered before its session
   * rooted anything, and without its own definitions. {@code open} was never "the claim is true";
   * this frame is.
   */
  public static final String READY_PARAM = "ready";

  public static final String SOURCE_PARAM = "source";

  /**
   * How long one file request may go unanswered before the run ends.
   *
   * <p>The bound for the failure the close cannot see. Long enough that a repository-wide {@code
   * file_glob} on somebody's laptop is an ordinary answer rather than an outage; short enough that
   * a wedged client does not hold a job for the length of its whole model-call budget.
   *
   * <p><b>Nothing pins this value and saying so is better than a test that appears to.</b> {@code
   * a_client_that_takes_its_time_is_not_a_client_that_is_gone} holds the accepted side from below
   * with a delay written as a literal, so lowering the deadline under it fails; the refused side is
   * measured against an injected short deadline, because a test that waited this one out would add
   * thirty seconds to every run of the suite. Between that literal and this number there is a
   * judgement about how slow a laptop is allowed to be, with no instrument — the same admission
   * {@code LocalProvider.MAX_FILE_BYTES} makes about its ceiling.
   */
  static final Duration DEADLINE = Duration.ofSeconds(30);

  /**
   * How much of a close reason fits in the frame. A WebSocket close control payload is 125 bytes
   * and the status code spends two of them.
   */
  private static final int MAX_CLOSE_REASON_BYTES = 123;

  private final Duration deadline;

  /**
   * Bound leniently on purpose. A client built against a later server sends a field this one has
   * never heard of, and the two halves ship separately — the same setting, for the same reason, as
   * a typed SDK caller's mapper on the other side of the wire.
   */
  private final ObjectMapper json =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /**
   * Where an open connection lives: the one registry, in the file-provider role, and <b>not a
   * second map beside it</b>.
   *
   * <h2>The registry replaces this class's own map rather than sitting beside it</h2>
   *
   * <p>There was a {@code ConcurrentHashMap<String, Live>} here, and keeping both would have been
   * two answers to one question. <b>The question is asked from two places that never meet</b>:
   * {@code AgentsConfig.runProviders} asks the registry whether a session has a file provider, in
   * order to decide whether a run gets a {@code RemoteProvider} at all; {@link #ask} asks this
   * class where to send a request. Every way the two could disagree is silent —
   *
   * <ul>
   *   <li>in the registry and not here: a run is handed a provider whose every call raises {@link
   *       SessionGoneException}, so a job ends {@code SESSION_GONE} naming a client that is sitting
   *       right there;
   *   <li>here and not in the registry: the run is given the local provider alone and simply cannot
   *       see the machine that asked for it — the "smaller capability arriving by accident" that
   *       this slice's whole nullable-session argument is written against, and it produces no error
   *       anywhere.
   * </ul>
   *
   * <p><b>The map was already a copy of the registry's contract.</b> {@link SessionRegistry}'s
   * javadoc derives its displacement story from this class line by line — the newer socket wins,
   * the displaced one is handed back to the caller that displaced it, and detach is
   * identity-checked so a displaced connection cannot unhook its replacement. Two implementations
   * of one settled story is the drift this project refuses everywhere else.
   *
   * <h2>What stays here, and it is the half the registry must not have</h2>
   *
   * <p>The registry holds no socket and closes nothing, by its own argument. So the ritual for a
   * displaced connection is unchanged and is still this class's: fail its outstanding requests with
   * {@link Live#gone}, then <em>close</em> it, because a socket left open keeps a Tomcat session
   * alive until its peer goes and a client in a reconnect loop would accumulate them.
   *
   * <h2>What is attached is the {@link Live} and not the {@code WebSocketSession}</h2>
   *
   * <p>{@link #ask} needs the outstanding map and the send lock as well as the socket, and a raw
   * session in the role would send this class back to a second map to find them — which is the
   * thing being removed. Identity is not weakened by it: one {@link Live} wraps exactly one socket
   * and is built at the moment that socket connects, so "the same {@code Live}" and "the same
   * connection" are the same distinction.
   *
   * <p><b>{@code EventChannelHandler} attaches the raw socket, and this sentence used to say that
   * was because a listener has nothing else.</b> It has: a listener now owns a queue and a drain
   * thread. What differs is who asks. Every send on this channel goes through {@link #ask}, which
   * needs that state on every call, while nothing above the other role asks about a listener's
   * queue — so it hangs off the connection's own attribute map there and the role keeps the socket.
   * The two are the same trade decided from opposite sides of it, not one class having more to hold
   * than the other.
   *
   * <p>Nothing outside this class reads what is in the role, and nothing should: the question
   * anything above asks is {@code Session.has(Role.FILE_PROVIDER)}, which is why {@code files/} and
   * {@code agents/} still know nothing about a socket.
   */
  private final SessionRegistry sessions;

  /**
   * Which live session roots which project, declared from this socket's own query string and
   * withdrawn when it closes.
   *
   * <p><b>The one registry, for {@link #sessions}' reason exactly.</b> {@code
   * AgentsConfig.runProviders} reads it to decide which machine a run reaches; a second one here
   * would be a presence no run could see, which is the silent half of the same disagreement that
   * argument is about.
   *
   * <p>This class is the only thing in the server that writes to it, and that is the design rather
   * than a coincidence: a presence's lifetime <em>is</em> a file channel's lifetime, so the object
   * that owns the socket is the object that can keep the two in step.
   */
  private final PresenceRegistry presences;

  /**
   * Where the same declaration's durable half goes: which machine the project's files are on,
   * written into the {@code projects} row.
   *
   * <p><b>Two facts, two homes, one event.</b> The design spec's §10 splits them and this class is
   * where the split is made: "which machine holds it is stable and belongs in the identity; whether
   * a live session is currently serving it is ephemeral and stays a runtime registry." {@link
   * #presences} is the second; this is the first.
   *
   * <p>And it is the answer to §13.4's missing verb. That section asked for a deliberate
   * registration operation, because {@code ProjectStore.define} validates against the
   * <em>server's</em> disk and therefore cannot express a project whose files are on a laptop.
   * <b>No new verb was needed.</b> A client declaring a presence already asserts the project, the
   * machine and the root — that is the whole of a place — so the declaration is the registration,
   * and there is no second way to assert the same fact that could disagree with this one.
   *
   * <p>A seam and not a {@code ProjectStore}, so that this class stays something a servlet
   * container and no database can drive; {@code ProjectRoots} makes the argument.
   */
  private final ProjectRoots roots;

  private final ProjectMembers members;

  /**
   * Everything else that must forget a session once its file channel closes — asked for lazily, and
   * never held as a fixed list.
   *
   * <h2>Why a {@code Supplier} and not the list itself</h2>
   *
   * <p><b>Breaks a cycle in the object graph, not merely in the package diagram.</b> {@code
   * DefinitionResolver} is one of these listeners and is built from a {@link SessionChannel} — this
   * class itself — so a constructor that demanded the list eagerly would need {@code
   * DefinitionResolver} fully built before {@code FileChannelHandler} could finish being built,
   * which needs {@code FileChannelHandler} fully built first. Neither bean could go first. A {@code
   * Supplier} is called only from {@link #afterConnectionClosed}, long after the whole context has
   * finished starting, so by the time it is ever invoked every listener genuinely exists.
   *
   * <p>{@code agents/} knows nothing of this field or of this class — see {@link
   * SessionCloseListener}'s own javadoc for why the interface it implements lives in {@code files/}
   * rather than in either package that actually uses it.
   */
  private final Supplier<List<SessionCloseListener>> closeListeners;

  /**
   * Striped by session id: a connect's declaration and attach, and a close's detach and withdrawal,
   * are each one step against the other. Which account holds an id is the registry's ({@link
   * SessionRegistry#claim}), not this. Requests never touch it.
   */
  private final ReentrantLock[] claims = stripes(64);

  /**
   * @param sessions the one registry for this server. Injected rather than created here, for the
   *     reason {@code EventChannelConfig} gives about the bean it defines: the two roles have to
   *     find each other in one session, and a handler holding a registry of its own would be a file
   *     provider no run could see
   * @param presences the one presence registry, injected for the same reason
   * @param roots where a declaration's durable half is written
   */
  public FileChannelHandler(
      SessionRegistry sessions,
      PresenceRegistry presences,
      ProjectRoots roots,
      ProjectMembers members) {
    this(sessions, presences, roots, members, DEADLINE);
  }

  /**
   * @param sessions the one registry for this server
   * @param presences the one presence registry
   * @param roots where a declaration's durable half is written
   * @param deadline how long to wait for one answer. A parameter so that the refused side can be
   *     measured without a test that sleeps for the production value
   */
  FileChannelHandler(
      SessionRegistry sessions,
      PresenceRegistry presences,
      ProjectRoots roots,
      ProjectMembers members,
      Duration deadline) {
    this(sessions, presences, roots, members, deadline, List::of);
  }

  /**
   * {@link #FileChannelHandler(SessionRegistry, PresenceRegistry, ProjectRoots, ProjectMembers,
   * Duration)} with {@link #closeListeners} made explicit — the overload {@code FileChannelConfig}
   * builds the production bean through, since neither existing constructor has anywhere to put one
   * without breaking every caller that has no listener to offer.
   *
   * @param closeListeners see this field's own javadoc for why it is a supplier and not the list
   */
  FileChannelHandler(
      SessionRegistry sessions,
      PresenceRegistry presences,
      ProjectRoots roots,
      ProjectMembers members,
      Duration deadline,
      Supplier<List<SessionCloseListener>> closeListeners) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.presences = Objects.requireNonNull(presences, "presences");
    this.roots = Objects.requireNonNull(roots, "roots");
    this.members = Objects.requireNonNull(members, "members");
    this.deadline = Objects.requireNonNull(deadline, "deadline");
    this.closeListeners = Objects.requireNonNull(closeListeners, "closeListeners");
  }

  // --- the socket ----------------------------------------------------------

  private SocketAuthorization socketAuthorization;

  @org.springframework.beans.factory.annotation.Autowired
  public void useSocketAuthorization(SocketAuthorization authorization) {
    this.socketAuthorization = authorization;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) throws IOException {
    if (socketAuthorization != null && !socketAuthorization.attach(socket)) return;
    String id = sessionId(socket.getUri());
    if (id == null || id.isBlank()) {
      // Refused rather than given a generated id. A session nothing can
      // name is a session no job can be routed to, so it would sit there
      // holding a socket and answering nothing — and the client would
      // never learn that its file tools do not work.
      log.warn(
          "A client opened {} without a '{}' parameter; closing it, since a session"
              + " nothing can name is one no job can reach.",
          PATH,
          SESSION_PARAM);
      socket.close(CloseStatus.BAD_DATA.withReason("open " + PATH + "?" + SESSION_PARAM + "=<id>"));
      return;
    }
    socket.getAttributes().put(SESSION_PARAM, id);
    String handle = handleOn(socket);
    if (!sessions.claim(id, handle)) {
      // ANOTHER ACCOUNT'S ID. Refused, never displacing: a session id is held
      // by the first account that claimed it, for as long as the registry
      // holds the session, on both sockets (Enzo's decision of 2026-09-30,
      // spec 2026-09-30-local-hooks-are-served). Before anything below, so a
      // refused socket declares nothing either.
      log.warn(
          "Session '{}' is held by another account; refusing a second account's"
              + " file channel rather than letting it take over.",
          id);
      refuseAsHeld(socket, id);
      return;
    }
    Live live;
    Live previous;
    // One connect or close at a time per id: a close's detach and withdrawal
    // cannot interleave with a reconnect's declaration and attach, so a close
    // never takes away a presence a reconnect has just made.
    ReentrantLock claiming = claimsFor(id);
    claiming.lock();
    try {
      if (!declared(socket.getUri(), id, socket)) {
        // A conflict, and the socket is already closed. BEFORE the attach and
        // not after it: a claim refused on a connection that had held the
        // file-provider role for even an instant is a window in which a run
        // could have been routed to a machine this server has just decided is
        // not the one that roots the project.
        return;
      }
      live = new Live(socket, handle);
      SessionRegistry.Attached attached = sessions.attach(id, Role.FILE_PROVIDER, live, handle);
      if (attached.refused()) {
        // Not reachable after the claim above, which is never undone; closed
        // rather than trusted if it ever is.
        log.error("Session '{}' refused a file channel it had just claimed.", id);
        refuseAsHeld(socket, id);
        return;
      }
      // The cast is this class's own claim being checked: nothing else puts
      // anything in the file-provider role, so something other than a Live in
      // it is a wiring fault, and a ClassCastException at the seam says so
      // where an ignored attachment would leave a client unclosed and its
      // requests waiting out a deadline.
      previous = (Live) attached.displaced().orElse(null);
    } finally {
      claiming.unlock();
    }
    if (previous != null) {
      // One id, two sockets: a client that reconnected without the old
      // socket having closed yet. The new one wins — it is the one whose
      // workspace a human is looking at — and the old one's outstanding
      // requests fail now rather than waiting out a deadline against a
      // session nothing will ever route to again.
      log.warn(
          "Session '{}' reconnected while an earlier socket was still registered;"
              + " the earlier one's outstanding file requests fail now.",
          id);
      previous.gone(
          "the session '"
              + id
              + "' was replaced by a second connection under"
              + " the same name while this was waiting");
      // AND THE SOCKET IS CLOSED, not merely forgotten. Leaving it open
      // leaves a Tomcat session alive until the peer closes it, which this
      // class's own javadoc says can be minutes on a half-open connection —
      // so a client in a reconnect loop accumulates them without bound.
      // Nothing is waiting on it: `gone` has already failed everything it
      // held.
      try {
        previous.socket.close(
            CloseStatus.NORMAL.withReason("replaced by a newer connection for session " + id));
      } catch (IOException | IllegalStateException already) {
        // Both measured shapes of "it is already going away", and neither
        // is a reason to refuse the new connection: this method's job is
        // to register the socket a human is actually looking at.
        log.debug(
            "The displaced socket for session '{}' would not close: {}", id, already.toString());
      }
    }
    log.info("Session '{}' opened the file channel.", id);
    if ("1".equals(parameter(socket.getUri(), READY_PARAM))) {
      ready(live, id);
    }
  }

  /**
   * Tell a client that asked ({@link #READY_PARAM}) that everything its upgrade claimed is now in
   * force.
   *
   * <p><b>Last, and only on this path.</b> A refused claim returned before the attach and never
   * reaches here, so a client hears exactly one of a 1003 close or this frame. {@code project} is
   * what this session roots now — read back from the registry rather than off the query, so a claim
   * this server could not read as a place (which serves files and roots nothing) says so by leaving
   * it out.
   *
   * <p>Under {@link Live#sending}: a run can already be sending this session a request — it was
   * attached one line up — and two writers on one socket is the interleaving that lock exists for.
   * A failure is logged and nothing else: the socket's own close is what a client learns from, and
   * the client bounds its wait for this frame.
   */
  private void ready(Live live, String id) {
    Map<String, Object> said = new LinkedHashMap<>();
    said.put("ready", true);
    presences.rootedBy(id).ifPresent(rooted -> said.put("project", rooted.project()));
    live.sending.lock();
    try {
      live.socket.sendMessage(new TextMessage(json.writeValueAsString(said)));
    } catch (IOException | IllegalStateException unsent) {
      log.debug("Session '{}' could not be told its claim landed: {}", id, unsent.toString());
    } finally {
      live.sending.unlock();
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
    if (socketAuthorization != null && !socketAuthorization.current(socket)) return;
    FileReply reply;
    try {
      reply = json.readValue(message.getPayload(), FileReply.class);
    } catch (IOException notAReply) {
      // Logged and dropped: there is no id, so there is nothing to fail
      // and nobody to tell. The request it was meant for runs out its
      // deadline, which is the right answer — a client sending frames this
      // server cannot read is one whose answers cannot be trusted.
      log.warn(
          "Session '{}' sent a frame this server could not read as a reply: {}",
          named(socket),
          notAReply.toString());
      return;
    }
    if (reply == null || reply.id() == null) {
      // A FRAME THAT PARSES AND SAYS NOTHING, which the catch above cannot
      // see: it only covers frames that fail to parse, and its comment used
      // to describe precisely this case while not handling it.
      //
      // Measured on Jackson 2.17: `readValue("null", FileReply.class)`
      // returns NULL, and `{}` binds to a record with every component null.
      // Without this guard the first dereferences a null reply and the
      // second reaches `ConcurrentHashMap.remove(null)`, which raises
      // NullPointerException — also measured.
      //
      // AND THE CONSEQUENCE IS NOT ONE DROPPED FRAME. Spring's
      // WebSocketHttpRequestHandler decorates this handler with
      // ExceptionWebSocketHandlerDecorator, so an exception escaping here
      // closes the session with SERVER_ERROR and EVERY OUTSTANDING REQUEST
      // ON IT FAILS. One malformed frame from one client would end every
      // job holding that session.
      //
      // The client half was hardened against the mirror image of this on
      // the day it was written — ClientEnforcer reads a null `op` as ""
      // rather than switching on it — and this end was not.
      log.warn(
          "Session '{}' sent a frame with no request id in it; there is nothing to"
              + " answer, so it is dropped.",
          named(socket));
      return;
    }
    Live live = live(named(socket));
    if (live == null || live.socket != socket) {
      // The same identity check afterConnectionClosed makes, and for the
      // same reason: a socket that has been displaced by a reconnection
      // under the same name is no longer this session, and applying its
      // replies to the live session's outstanding map would answer one
      // job's question with another socket's frame. The two methods
      // disagreed about whether identity mattered, and only one of them
      // could be right.
      //
      // Harmless today because ids are UUIDs, so a displaced socket's
      // reply matches nothing in the new map — worth saying, because it is
      // why this is a correctness repair rather than a bug fix.
      log.debug(
          "Session '{}' answered on a socket that is no longer the registered one.", named(socket));
      return;
    }
    if (!live.answer(reply)) {
      // An id nothing is waiting on. The ordinary cause is an answer that
      // arrived after its own deadline had already ended the run, which is
      // not worth a warning; anything else is a client bug and this line
      // is where it shows.
      log.debug(
          "Session '{}' answered request '{}', which nothing was waiting for.",
          named(socket),
          reply.id());
    }
  }

  @Override
  public void handleTransportError(WebSocketSession socket, Throwable error) {
    // Measured: an abrupt disconnect arrives here as an EOFException and
    // then as a 1006 close one millisecond later, so the draining is
    // afterConnectionClosed's and this only records the cause — which is the
    // half that says whether the client went away or the network did.
    log.info("The file channel for session '{}' failed: {}", named(socket), error.toString());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    if (socketAuthorization != null) socketAuthorization.detach(socket);
    String id = named(socket);
    Live live;
    // Under the id's claim lock, as a connect's declaration and attach are: a
    // reconnect landing between this detach and this withdrawal would
    // otherwise lose the presence it had just declared.
    ReentrantLock claiming = claimsFor(id);
    claiming.lock();
    try {
      live = live(id);
      if (live == null || live.socket != socket) {
        // Already replaced by a later connection under the same id, which
        // has its own outstanding requests. Failing them here would take out
        // the live session because the dead one finally closed.
        return;
      }
      // The identity-checked detach, which is what the registry's two-argument
      // remove was derived from. Its answer is not branched on, for the reason
      // the unconditional remove was not: a displacement landing between the
      // read above and this line has already failed and closed this Live, and
      // `gone` on a drained map is a no-op.
      sessions.detach(id, Role.FILE_PROVIDER, live);
      // AND THE PRESENCE GOES WITH IT. Guarded by the identity check above,
      // which is what makes this safe for a displaced socket's late close: a
      // reconnection under the same id has already re-declared, and a
      // withdrawal here would take the live client's project away because the
      // dead one finally caught up.
      //
      // Withdrawn by session and not by project, because this class does not
      // remember what was declared and must not: the registry holds one
      // presence per session and is the single place that knows which.
      if (presences.withdraw(id)) {
        log.info("Session '{}' closed its file channel, so it roots nothing now.", id);
      }
    } finally {
      claiming.unlock();
    }
    log.info("Session '{}' closed the file channel ({}).", id, status);
    // THE EVENT THE WHOLE CHANNEL SHAPE IS FOR. Every request waiting on
    // this session fails now, rather than each one waiting out a deadline
    // written for a client that is still there and not answering.
    live.gone(
        "the session '"
            + id
            + "' closed while this was waiting, so the files it"
            + " owned cannot be reached");
    // Everything else that must forget this session -- DefinitionResolver
    // among them, for a project tier a ChannelDefinitions built for this
    // session and cannot outlive it. One listener's own bug must not stop
    // the others from hearing this, or corrupt a close already in
    // progress above, so each is isolated in its own catch and logged
    // rather than allowed to propagate out of a WebSocket callback.
    for (SessionCloseListener listener : closeListeners.get()) {
      try {
        listener.sessionClosed(id);
      } catch (RuntimeException failed) {
        log.warn(
            "A session-close listener ({}) failed for session '{}': {}",
            listener.getClass(),
            id,
            failed.toString());
      }
    }
  }

  // --- the seam ------------------------------------------------------------

  @Override
  public FileReply ask(String session, FileRequest request) {
    return ask(session, request, deadline);
  }

  @Override
  public boolean sources(String session) {
    Live attached = live(session);
    return attached != null
        && !attached.closed
        && "1".equals(parameter(attached.socket.getUri(), SOURCE_PARAM));
  }

  /** {@inheritDoc} The send is still bounded by the channel's own deadline. */
  @Override
  public FileReply ask(String session, FileRequest request, Duration deadline) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(deadline, "deadline");
    Live live = live(session);
    if (live == null) {
      // GONE. Either it never opened the channel or it closed and was
      // removed, and neither is distinguishable from here — nor needs to
      // be, because both are "there is no client of that name".
      //
      // THE SENTENCE SAYS THAT AND NOT THAT THE SESSION LEFT. The ending
      // this reaches is named for a client going away, and a session that
      // never connected did not go anywhere — a run pointed at an id no
      // client ever opened is a wiring fault, and it is the one this
      // branch is most likely to be reporting. The ending's verb
      // generalises across five sites; this sentence does not, and it is
      // what lands in the run's detail. SessionGoneException owns the
      // argument.
      throw new SessionGoneException(
          "no client session '"
              + session
              + "' is"
              + " connected, so the files it owns cannot be reached");
    }
    CompletableFuture<FileReply> answer = new CompletableFuture<>();
    live.outstanding.put(request.id(), answer);
    try {
      // Registered before the check, and checked after registering. A
      // close landing between the two would otherwise leave this future in
      // nobody's map — the drain has already run — and it would wait out
      // the whole deadline over a socket that is provably gone.
      if (live.closed) {
        throw new SessionGoneException(
            "the session '"
                + session
                + "' closed"
                + " before this could be asked, so the files it owned cannot be"
                + " reached");
      }
      send(live, session, request);
      // Blocking is free: this is a virtual thread and a job blocked here
      // holds no lane slot, exactly as a parent blocked on a child does
      // not. That is what lets the tool call simply return an answer.
      return answer.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException wedged) {
      // What is known is that no answer arrived in the time allowed. The
      // message says that and does not guess whether the client is slow or
      // gone: see the class javadoc for why no instrument can tell. NOT
      // SessionGoneException, for the same reason and by the same rule —
      // the socket is open, and an ending that said otherwise would be
      // naming a situation that does not hold.
      throw new WorkspaceUnavailableException(
          "the session '"
              + session
              + "' did not"
              + " answer within "
              + deadline.toSeconds()
              + " seconds; it is connected,"
              + " so it is either busy or stuck, and nothing can be concluded from the"
              + " silence",
          wedged);
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof WorkspaceUnavailableException gone) {
        // The close's own sentence, carried through rather than
        // rebuilt: it names the session and what happened to it, and a
        // second sentence composed here would be about a future.
        throw gone;
      }
      // NEITHER, AND IT IS THE ONE SITE HERE WITH NO PRODUCER AT ALL —
      // said at the site rather than only in a survivor census, because a
      // reader counting this class's throw sites needs to find it
      // classified where the others are.
      //
      // Live.gone is the only thing anywhere that completes one of these
      // futures exceptionally — Live.answer uses complete(), and a cancel
      // would arrive as CancellationException rather than here — and it
      // constructs a SessionGoneException, which the clause above takes.
      // So no cause reaches this line today, and it cannot be deleted
      // either: `get` declares a checked ExecutionException whose cause is
      // a Throwable, and something has to turn one into a RuntimeException
      // of the two kinds this seam is allowed to fail in.
      //
      // It is therefore a BOUNDARY rather than a branch — the shape
      // JobRuntime.usable and JobRuntime.synthesisedId already argue at
      // length: a boundary holds for what it is handed rather than for
      // what its current callers happen to send. Not the subtype, because
      // a cause this class did not construct is a cause it knows nothing
      // about, and naming a disappearance on no evidence is the guess this
      // seam exists to avoid.
      throw new WorkspaceUnavailableException(
          "the file channel to session '" + session + "' failed: " + failed.getCause(), failed);
    } catch (InterruptedException stopped) {
      // The flag is restored because swallowing it leaves a job that
      // cannot be cancelled: JobStore.close asks every run to stop, and a
      // thread whose interrupt was eaten here goes back round its loop.
      Thread.currentThread().interrupt();
      throw new WorkspaceUnavailableException(
          "waiting for session '" + session + "' was interrupted", stopped);
    } finally {
      // In a finally, so that a request that timed out is not left in the
      // map for the life of the session. A late answer then finds nothing
      // waiting and is dropped, which is what handleTextMessage's debug
      // line is about.
      live.outstanding.remove(request.id());
    }
  }

  /**
   * One frame, written under the lock and bounded by the same deadline the answer is.
   *
   * <p><b>Neither of this class's two declared bounds used to bound this method, and that was the
   * hole.</b> {@code answer.get(deadline)} starts only after this returns, so a client whose reader
   * has stalled held every other job's send behind an untimed {@code lock()} and then an untimed
   * blocking write. <b>Measured on this classpath (tomcat-embed-websocket 10.1.31):</b> the only
   * real bound was Tomcat's {@code Constants.DEFAULT_BLOCKING_SEND_TIMEOUT}, which is {@code 20000}
   * ms and which nothing here named — so N jobs queued on one wedged session was a worst case of N
   * × 20 seconds.
   *
   * <p>{@code tryLock} with the deadline closes the queueing half: a caller that cannot get the
   * lock in the time its own request had gives up with the sentence that says so, rather than
   * inheriting the wait of everyone in front of it. The write itself is still Tomcat's to bound,
   * and that number is now written down where a reader meets it.
   *
   * <p><b>The JSON is built before the lock is taken</b>, so a caller holds it for the write and
   * not for serialising up to {@code MAX_FILE_BYTES} of content on the write path.
   *
   * <p><b>Why not {@code ConcurrentWebSocketSessionDecorator}</b>, which Spring ships for exactly
   * this: it makes a send that cannot proceed <em>buffer</em> rather than wait, and closes the
   * session when the buffer passes a limit. That is the right shape for a broadcast nobody is
   * blocked on, and the wrong one here — every send on this channel has exactly one job waiting for
   * its answer, so a buffered send is a job that waits for its deadline over a request that never
   * went out, and a limit breach takes down a session whose only fault was one slow reader. Waiting
   * with a bound, and failing that one caller, is what a request-response channel wants.
   */
  private void send(Live live, String session, FileRequest request) {
    String frame;
    try {
      frame = json.writeValueAsString(request);
    } catch (IOException notSerialisable) {
      // A request this server built and cannot write down is this server's
      // bug, and it must not be reported as the client's silence.
      throw new WorkspaceUnavailableException(
          "a file request for session '"
              + session
              + "' could not be serialised: "
              + notSerialisable,
          notSerialisable);
    }
    try {
      if (!live.sending.tryLock(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
        // NOT SessionGoneException, and on conservative grounds rather
        // than on a claim that the client is alive: a peer can be dead
        // while a blocked write has not yet errored. What is known here
        // is only that nothing has observed this socket fail, which is
        // not evidence of a disappearance.
        throw new WorkspaceUnavailableException(
            "the file channel to session '"
                + session
                + "' was busy for longer than "
                + deadline.toSeconds()
                + " seconds;"
                + " another request to the same client is not completing, so this one"
                + " never went out");
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new WorkspaceUnavailableException(
          "waiting to write to session '" + session + "' was interrupted", stopped);
    }
    try {
      live.socket.sendMessage(new TextMessage(frame));
    } catch (IOException | IllegalStateException unusable) {
      // GONE. Both measured. IllegalStateException is what a Spring
      // session raises for a send after it has closed — "no method (apart
      // from close()) may be called on a closed session" — and an
      // IOException on a socket write is that socket failing, so both are
      // facts about the socket rather than bugs here, and neither is a
      // client this server can still reach. Catching only the checked one
      // would also let the other out of this seam as a runtime failure
      // that JobRuntime reports as "the tool failed; you may try something
      // else".
      //
      // Note the asymmetry with the lock timeout above: THAT one has
      // observed nothing about the socket, and THIS one has watched a
      // write to it fail. (The measurement was stated twice in adjacent
      // paragraphs for one commit, which is the one-owner rule broken
      // inside a single catch clause.)
      throw new SessionGoneException(
          "the file channel to session '" + session + "' could not be written to: " + unusable,
          unusable);
    } finally {
      live.sending.unlock();
    }
  }

  // --- one connected client ------------------------------------------------

  /**
   * Whether one named session has the channel open right now.
   *
   * <p>What it is for is {@code FileChannelTest}: the upgrade is asynchronous on both sides, so a
   * test that sent a request the moment {@code newWebSocket} returned would race the registration,
   * and this is what it waits on.
   */
  public boolean isConnected(String session) {
    return live(session) != null;
  }

  /**
   * How many requests are waiting on one session right now.
   *
   * <p><b>An instrument, and it exists because the sweep asked for one.</b> The {@code finally}
   * that removes a request from {@link Live#outstanding} has no effect any caller can see: a
   * request that timed out has already thrown, and a late answer arriving to an entry nobody
   * removed simply completes a future nothing is holding. What it prevents is a <em>leak</em> — one
   * entry per timed-out request, for the life of a session, on a server whose sessions are meant to
   * live as long as somebody's editor — and a leak is invisible to every assertion that can be
   * written about an answer.
   *
   * <p>Package-private, so this is a window rather than an API. {@code
   * the_map_does_not_grow_by_one_every_time_a_client_does_not_answer} is the only caller, and
   * without it the mutant that deletes that {@code finally} survives the whole file.
   */
  int outstanding(String session) {
    Live live = live(session);
    return live == null ? 0 : live.outstanding.size();
  }

  /**
   * Record what this socket says it roots, or close it if somebody else roots it already.
   *
   * <p>Three outcomes, and two of them are ordinary:
   *
   * <ul>
   *   <li><b>no claim</b> — fewer than all three parameters. Every client built before presence is
   *       in this state and still lends its files. True rather than false: nothing was refused;
   *   <li><b>a claim this server cannot read as a place</b> — a relative root, a blank machine.
   *       Logged and dropped, and the socket serves. Refusing the whole upgrade would take a
   *       working file channel away over an identity component, and accepting it would put a name
   *       in the registry that no move operation could resolve;
   *   <li><b>a conflict</b> — another live session roots that project at a different location, or
   *       the archive says some other place already holds it. The socket is closed with the
   *       sentence naming the holder, and this answers false so the caller attaches nothing.
   * </ul>
   *
   * <h2>The registry first, then the row, and the order is not arbitrary</h2>
   *
   * <p>The two writes are the two halves of one declaration — which live session serves the
   * project, and which machine holds it — and either can refuse. Claiming the registry first means
   * <b>a refusal from the registry never writes a row</b>: a second machine competing for a live
   * claim is turned away before anything durable is touched, which is what stops the loser of a
   * race from leaving its root in the row it was refused.
   *
   * <p>The cost is the mirror image, and it is paid explicitly: a refusal from the archive arrives
   * after the registry has already been claimed, so the claim is given back. A session left rooting
   * a project whose row says another machine holds it is precisely the split state §13.2 refuses a
   * move to avoid — runtime state and a database row disagreeing, with nothing that could reconcile
   * them afterwards.
   *
   * <h2>An archive that cannot be reached costs nothing</h2>
   *
   * <p><b>Deliberate, and the ranking is the point.</b> The registry is what routes a run and it is
   * in memory; the row is a durable record. A database that is down must not take away a live
   * capability that never depended on it, so the presence stands, the socket serves, and the
   * writing-down is attempted again at the next reconnect. What is lost is a fact about a project
   * nothing is currently serving — and {@code AgentsConfig.serverCannotServe}, the one reader of
   * that fact, cannot reach a database either while this is true.
   *
   * @return whether the connection should go on to be attached
   */
  private boolean declared(URI uri, String id, WebSocketSession socket) {
    String project = parameter(uri, PROJECT_PARAM);
    String machine = parameter(uri, MACHINE_PARAM);
    String root = parameter(uri, ROOT_PARAM);
    if (project == null || machine == null || root == null) {
      return true;
    }
    Presence claim;
    try {
      claim = new Presence(id, machine, root, project);
    } catch (IllegalArgumentException unreadable) {
      // The socket lives. See this method's javadoc for why an unreadable
      // claim is not a reason to take a file channel away.
      log.warn(
          "Session '{}' opened {} with a claim this server cannot read as a place,"
              + " so it lends its files and roots nothing: {}",
          id,
          PATH,
          unreadable.getMessage());
      return true;
    }
    try {
      if (!io.aeyer.plowshare.server.archive.ClientProjects.visible(
              claim.project(), sessionId(socket.getUri()), handleOn(socket))
          || !members.mayUse(claim.project(), handleOn(socket))) {
        closeQuietly(
            socket,
            id,
            CloseStatus.POLICY_VIOLATION.withReason(
                trimmed("this account may not root '" + claim.project() + "'")));
        return false;
      }
    } catch (ArchiveUnavailableException down) {
      presences.withdraw(id);
      log.warn("Session '{}' cannot check membership and roots nothing: {}", id, down.getMessage());
      return true;
    }
    try {
      presences.declare(claim);
    } catch (PresenceConflictException taken) {
      log.warn(
          "Session '{}' claimed a different location for an already rooted project; closing it. {}",
          id,
          taken.getMessage());
      String reason =
          "'"
              + claim.project()
              + "' is rooted elsewhere; use its directory or reconnect explicitly";
      Optional<Presence> holder = presences.serving(claim.project());
      if (holder.isPresent()
          && handleOn(socket) != null
          && sessions
              .accountOf(holder.get().session())
              .filter(handleOn(socket)::equals)
              .isPresent()) {
        reason = holder.get().canonicalName() + "; " + reason;
      }
      refuse(socket, id, reason);
      return false;
    }
    try {
      roots.rootOn(claim.project(), claim.machine(), claim.root(), handleOn(socket));
    } catch (ArchiveRefusedException held) {
      // The claim is given back before the socket goes, so that no window
      // exists in which the registry says this session roots a project the
      // archive has just said belongs somewhere else.
      presences.withdraw(id);
      log.warn(
          "Session '{}' claimed a project the archive says is held elsewhere;" + " closing it. {}",
          id,
          held.getMessage());
      refuse(socket, id, held.getMessage());
      return false;
    } catch (ArchiveUnavailableException down) {
      // The presence stands. See this method's javadoc: the registry is
      // what routes a run and it does not need the database.
      log.warn(
          "Session '{}' roots '{}' but the archive could not be told so; the presence"
              + " serves and the next reconnect writes it down. {}",
          id,
          claim.project(),
          down.getMessage());
    }
    return true;
  }

  /**
   * Close a socket whose claim was refused, carrying the sentence that says why.
   *
   * <p>The sentence travels in the close reason because it is the whole product: a person with two
   * terminals open needs to know which one to close, and a bare 1003 tells them nothing.
   */
  private void refuse(WebSocketSession socket, String id, String reason) {
    closeQuietly(socket, id, CloseStatus.NOT_ACCEPTABLE.withReason(trimmed(reason)));
  }

  /** Close a socket of another account than the one holding {@code id}: 1008, and why. */
  static void refuseAsHeld(WebSocketSession socket, String id) {
    closeQuietly(
        socket,
        id,
        CloseStatus.POLICY_VIOLATION.withReason(
            trimmed("this session id is held by another account: " + id)));
  }

  private ReentrantLock claimsFor(String id) {
    return claims[Math.floorMod(id.hashCode(), claims.length)];
  }

  private static void closeQuietly(WebSocketSession socket, String id, CloseStatus status) {
    try {
      socket.close(status);
    } catch (IOException | IllegalStateException already) {
      log.debug("The refused socket for session '{}' would not close: {}", id, already.toString());
    }
  }

  /**
   * The account {@code session}'s file channel was opened as, or empty when none is attached or it
   * was opened as nobody. Read-only: {@code PinnedLocalHooks} holds a session's local hooks to the
   * log's owner through it (spec 2026-09-30-local-hooks-are-served).
   */
  public Optional<String> handleOf(String session) {
    Live live = live(session);
    return live == null ? Optional.empty() : Optional.ofNullable(live.handle);
  }

  /** What {@code HandleInterceptor} copied from {@code AuthFilter} onto this socket, or null. */
  static String handleOn(WebSocketSession socket) {
    Object handle = socket.getAttributes().get(EventChannelHandler.HANDLE);
    return handle instanceof String account && !account.isBlank() ? account : null;
  }

  /**
   * A close reason short enough to be sent.
   *
   * <p><b>Measured rather than assumed:</b> the WebSocket close frame's control payload is capped
   * at 125 bytes and the status code spends two of them, so Tomcat refuses a longer reason and the
   * client would be closed with nothing at all — which is the one outcome this sentence exists to
   * prevent. The full sentence is in the WARN above; what survives here is its head, and the
   * conflict message is composed so that the two canonical names come first.
   */
  private static String trimmed(String reason) {
    byte[] utf8 = reason.getBytes(StandardCharsets.UTF_8);
    if (utf8.length <= MAX_CLOSE_REASON_BYTES) {
      return reason;
    }
    // Cut on a char boundary and then on bytes, so a multi-byte character is
    // never halved: shortening by chars until the encoding fits is the only
    // way to be sure of both.
    String shortened = reason;
    while (shortened.getBytes(StandardCharsets.UTF_8).length > MAX_CLOSE_REASON_BYTES) {
      shortened = shortened.substring(0, shortened.length() - 1);
    }
    return shortened;
  }

  /**
   * The connection attached to one session's file-provider role, or null.
   *
   * <p>One reader of the registry rather than five, so that what "this session has a channel open"
   * means is decided in exactly one place. Null and not an {@link java.util.Optional}: every caller
   * here branches on absence with a sentence of its own, and {@link #ask}'s is the one that becomes
   * a run's ending.
   *
   * <p>Lenient about a null id for the reason {@link SessionRegistry#find} is: a session is
   * optional at submission, so "no session" reaching a lookup is an ordinary shape rather than a
   * fault.
   */
  private Live live(String session) {
    return sessions
        .find(session)
        .flatMap(found -> found.attached(Role.FILE_PROVIDER, Live.class))
        .orElse(null);
  }

  private static ReentrantLock[] stripes(int count) {
    ReentrantLock[] made = new ReentrantLock[count];
    for (int i = 0; i < count; i++) {
      made[i] = new ReentrantLock();
    }
    return made;
  }

  private static String named(WebSocketSession socket) {
    Object id = socket.getAttributes().get(SESSION_PARAM);
    return id == null ? "(unnamed)" : id.toString();
  }

  /**
   * The session id from the query string, without a query parser.
   *
   * <p>{@link #parameter} now splits the raw query and decodes each value after, which is what
   * keeps an encoded {@code &} inside a value — a root path is the case that arrives in practice —
   * from being read as a second separator. A session id could carry the same character and be read
   * correctly for the same reason, though it never needs to: the client mints its own id and this
   * server only ever compares it to itself, so an id that round-trips is the whole requirement.
   * Pulling in a URI builder to be exact about a case that does not arise would be the wrong trade.
   *
   * <p><b>Package-private rather than private, so that {@link EventChannelHandler} calls it instead
   * of copying it.</b> The two sockets are two roles of one session and the id has to be the same
   * id on both; a second private parser beside this one would be two conventions that agree until
   * the day one of them is changed. Narrow rather than public, because the only caller that needs
   * it is a sibling in this same package — {@code EventChannelHandler} — and a method visible
   * outside this package would be an invitation this class has no reason to extend.
   */
  static String sessionId(URI uri) {
    return parameter(uri, SESSION_PARAM);
  }

  /**
   * One query parameter, without a query parser.
   *
   * <p>{@link #sessionId}'s body, generalised when the presence parameters arrived rather than
   * copied beside it. The reasoning above is unchanged and now covers four names instead of one:
   * the values here are minted by the client and only ever compared to themselves or handed back in
   * a message, so a value that round-trips is the whole requirement.
   *
   * <p>A blank value is {@code null}, which is what makes "all three or none" a rule the caller can
   * state simply: {@code &project=} is a client that meant to set one, and reading it as a project
   * named "" would put a claim in the registry that no {@code Home} could ever match.
   *
   * <h2>The raw query, split first and decoded second</h2>
   *
   * <p>{@code getQuery()} decodes the whole string before anybody splits it, so a root spelled with
   * {@code %26} — which is how any client must spell a path containing {@code &} — was cut in two
   * at a separator the client never wrote: the first half became this parameter's value and the
   * second was read as if it were its own pair. {@link URI#getRawQuery()} is undecoded, so
   * splitting it first and decoding each side of {@code =} after means an encoded {@code &} inside
   * a value is never mistaken for one that joins two pairs. {@code sessionId}'s own argument about
   * not needing a real query parser is unchanged by this — decoding a value once it is already
   * isolated is not parsing a query, and {@link URLDecoder#decode(String,
   * java.nio.charset.Charset)} is the one call this class trusts to invert whatever encoded it.
   */
  private static String parameter(URI uri, String name) {
    if (uri == null || uri.getRawQuery() == null) {
      return null;
    }
    for (String pair : uri.getRawQuery().split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && pair.substring(0, equals).equals(name)) {
        String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
        return value.isBlank() ? null : value;
      }
    }
    return null;
  }

  /**
   * One open socket, its outstanding requests, and the lock that keeps two jobs from interleaving
   * one frame.
   */
  private static final class Live {

    private final WebSocketSession socket;
    private final ReentrantLock sending = new ReentrantLock();
    private final Map<String, CompletableFuture<FileReply>> outstanding = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /** The account this socket was opened as, or null for nobody. */
    private final String handle;

    Live(WebSocketSession socket, String handle) {
      this.socket = socket;
      this.handle = handle;
    }

    /**
     * @return whether anything was waiting for this id
     */
    boolean answer(FileReply reply) {
      CompletableFuture<FileReply> waiting = outstanding.remove(reply.id());
      return waiting != null && waiting.complete(reply);
    }

    /**
     * Fail everything at once. The flag goes up first, so a request registering concurrently sees
     * it and does not wait on a drained map.
     */
    void gone(String sentence) {
      closed = true;
      for (Map.Entry<String, CompletableFuture<FileReply>> waiting : outstanding.entrySet()) {
        waiting.getValue().completeExceptionally(new SessionGoneException(sentence));
      }
    }
  }
}
