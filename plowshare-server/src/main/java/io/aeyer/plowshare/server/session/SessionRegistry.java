package io.aeyer.plowshare.server.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every session this process knows, and the two things that can happen to one.
 *
 * <h2>A session is created by attaching to it, never by asking about it</h2>
 *
 * <p>{@link #find} returns an {@link Optional} and inserts nothing. That is not politeness about
 * nulls: a job may be submitted with a session id nothing has ever attached to — the plain-HTTP
 * caller, the curator's nightly pass, a browser holding only a listener — and "there is no session
 * here" has to stay a true, ordinary answer for all of them. A lookup that created on demand would
 * make every id anybody ever typed into a live session, and the question above this — <em>does this
 * session have a file provider</em> — would then be asked of a session invented by the asking. It
 * is the opposite choice from {@code agents.JobStore.get}, which throws on an unknown id, and for
 * the opposite reason: a job id a caller holds came from a submission, so a miss there is a fault,
 * while a miss here is the ordinary shape of a client that has not connected.
 *
 * <h2>Displacement is {@code FileChannelHandler}'s story, reported rather than re-invented</h2>
 *
 * <p>{@code ws.FileChannelHandler} already settled what happens when a second socket arrives on an
 * id that already has one, and this class did the same thing one layer down instead of inventing a
 * second answer. <b>It is now the only copy of it</b>: that class attaches its connection here
 * rather than to a map of its own, so the sentences below describe one mechanism with two callers
 * rather than two mechanisms that agree.
 *
 * <ul>
 *   <li><b>the newer one wins, for the same account.</b> {@link #attach} puts into the role for the
 *       account that holds the session, which is what that class's own {@code put} used to do; it
 *       is the connection a human is actually looking at. A socket of another account is refused
 *       and displaces nothing: a session id is held by the first account that claims it, on both
 *       roles, for as long as this registry holds it ({@link #claim}; spec
 *       2026-09-30-local-hooks-are-served decision 11);
 *   <li><b>the displaced one is handed back to the caller that displaced it.</b> There the returned
 *       socket has its outstanding file requests failed at once and is then <em>closed</em> — left
 *       open, a displaced but responsive peer can continue answering transport keepalive probes, so
 *       a client in a reconnect loop would accumulate them. Here {@link #attach} returns it and
 *       stops. <b>This class holds no socket and closes nothing</b>, for the reason {@link Session}
 *       gives: it must not know what a role attached. The ritual stays with the handler that owns
 *       the connection, which is where the one copy of it already is;
 *   <li><b>a displaced connection cannot unhook its replacement.</b> {@link #detach} is
 *       identity-checked, and so is the {@code live.socket != socket} guard that class still makes
 *       before applying a reply, because a displaced socket still gets its own close event
 *       afterwards. <b>There, that guard is a correctness repair rather than a bug fix</b> — the
 *       request ids it would misapply are UUIDs minted per request by {@code files.RemoteProvider},
 *       so a displaced socket's reply matches nothing in the newer socket's outstanding map.
 *       <b>Here the same omission would bite today</b> — there is one slot per role, and a
 *       role-only detach empties the live one. Measured by making the mistake: with {@code detach}
 *       matching on the role alone, {@code
 *       a_displaced_attachment_detaching_does_not_detach_the_one_that_replaced_it} fails.
 * </ul>
 *
 * <p>What this class adds and that one does not need is that displacement is <em>per role</em>. A
 * listener reconnecting displaces a listener and leaves the file provider where it is.
 *
 * <h2>Logical workspace recovery and concurrency</h2>
 *
 * <p>Reads use concurrent maps. Compound mutations serialize under a short monitor: recovering
 * transfers the listener and records its verified logical attachments atomically with attach and
 * detach. This critical section performs only in-memory operations, with no I/O or callbacks.
 * Session role operations retain their own lock and identity-checked displacement rules.
 *
 * <p>A transport may prove several workspace attachments, but switching its current workspace never
 * moves the previous file provider. A new event connection using an old transport ID starts with
 * fresh proofs; the public ID alone cannot recover a workspace. Listener cleanup removes only that
 * exact listener across its verified attachments. File cleanup remains specific to its logical
 * workspace, so late closes cannot withdraw a replacement provider.
 *
 * <h2>Nothing reaps, and that is recorded rather than guessed at</h2>
 *
 * <p>A session with no roles attached is garbage, and this class keeps it forever. <b>No timer is
 * added here, because the number a timer needs cannot be derived from anything that exists yet, and
 * picking one because it sounds right is how a constant becomes folklore.</b> What can be said
 * instead:
 *
 * <ul>
 *   <li><b>the cost is measured, on the JVM this build actually runs</b> — JBR 21.0.8, heap delta
 *       across repeated {@code System.gc()}, {@code -Xmx3g}. A registry holding one million bare
 *       sessions — a UUID id, two instants, an empty attachment map, and the map entry holding it —
 *       retains <b>349 bytes each, 333 MB in total</b>; the {@link Session} object with its id, its
 *       instants and its empty map accounts for 180 of those bytes, that figure taken from an array
 *       and so including the reference slot holding it. So ten thousand abandoned sessions cost
 *       about 3.5 MB. <b>The leak is real and it is slow</b>, which is what buys the room to answer
 *       the questions below before writing a timer;
 *   <li><b>the legitimate rate cannot be measured.</b> Growth needs <em>distinct</em> ids; a client
 *       reconnecting under the id it already has displaces rather than adds, which is the common
 *       failure and which costs nothing. When this was written there was no client at all; there is
 *       one now, {@code cli.Plowshare}, and it mints exactly one id per invocation, so a person
 *       running it is not a rate worth a timer;
 *   <li><b>the adversarial rate is a different question, and this paragraph did not used to ask
 *       it.</b> Every sentence above is about how fast the <em>intended</em> client mints. Nothing
 *       makes minting the client's privilege in this class: {@link #attach} is {@code
 *       computeIfAbsent} and {@link #find} asks only that an id be non-blank, so an id is a name
 *       and never a secret, and one upgrade to {@code /v1/events?session=<a fresh uuid>} leaves 349
 *       bytes behind. <b>What has changed is who can make that upgrade.</b> This paragraph used to
 *       end "with no authentication to stop them, because there is none to have"; slice 4 built it,
 *       and {@code AuthFilter} refuses the upgrade with a 401 before this class is reached at all —
 *       measured, by {@code AuthFilterTest} and by {@code
 *       ConversationEndToEndTest.an_unauthenticated_session_attaches_neither_role}. So the party
 *       who can grow this map is the operator, and the operator growing it a million times is the
 *       intended rate above rather than an adversary. Reachability is still the other bound: {@code
 *       application.yml} binds {@code ${PLOWSHARE_BIND}}, and an operator who opens either that or
 *       {@code plowshare.auth.enabled} takes this back. <b>Neither is a reaper</b>;
 *   <li><b>and the number is not a memory question anyway.</b> An idle timeout is a statement about
 *       how long a human may shut a laptop and still expect to reattach, which is exactly the
 *       reconnection question the design spec lists as open;
 *   <li><b>a reaper cannot be specified before a prior question is answered.</b> Reaping a session
 *       takes the file provider out from under any job still running on it, which is precisely the
 *       spec's other open question — whether a job outlives its session by design. A timer written
 *       before that is settled would be the thing that answers it by accident.
 * </ul>
 *
 * <p><b>Who decides:</b> not this class. The two open questions above are the design spec's own —
 * {@code implementation rationale}, "Open questions, deliberately unresolved" — and the
 * repository's author closes them there, with the command-line client of this slice as the first
 * instrument that could measure a real reattachment gap. {@link Session#lastSeen()} is stamped and
 * tested from today so that whatever eventually reads it reads a true value.
 */
public final class SessionRegistry implements SessionOwners {

  private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  private final Clock clock;
  private final Map<String, Set<String>> recovered = new ConcurrentHashMap<>();
  private final Object recovering = new Object();

  /** The production one. */
  public SessionRegistry() {
    this(Clock.systemUTC());
  }

  /**
   * @param clock where the instants come from. A parameter so that a test asserting on {@code
   *     lastSeen} sets the instant it asserts on, rather than sleeping and hoping — the same shape
   *     as {@code ws.FileChannelHandler}'s package-private constructor taking its deadline
   */
  SessionRegistry(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The session under {@code id}, if anything has attached to it.
   *
   * <p>Lenient about its argument on purpose: a session id is optional at submission, so a null
   * arriving here is the ordinary shape of a job that has no session rather than a fault, and the
   * answer is the same "no" an unknown id gets. The mutating methods are the strict ones.
   */
  public Optional<Session> find(String id) {
    return id == null ? Optional.empty() : Optional.ofNullable(sessions.get(id));
  }

  /** Logical attachment identity after an authenticated workspace recovery. */
  @Override
  public String canonical(String id) {
    return find(id).map(Session::id).orElse(id);
  }

  @Override
  public Set<String> logicalSessions(String session) {
    if (session == null) return Set.of();
    Set<String> verified = recovered.get(session);
    return verified == null ? Set.of(canonical(session)) : Set.copyOf(verified);
  }

  /**
   * Join a fresh transport to a retained logical attachment after its recovery capability was
   * verified. Ownership is checked on both sides. The event listener moves with the connection;
   * late closes still use attachment identity checks and cannot detach the replacement.
   */
  public void recover(String incoming, String retained, String account) {
    synchronized (recovering) {
      Session fresh = sessions.get(named(incoming));
      Set<String> verified =
          recovered.computeIfAbsent(incoming, ignored -> ConcurrentHashMap.newKeySet());
      if (!verified.contains(retained) && verified.size() >= 128)
        throw new IllegalStateException("Use another connection to attach more workspaces");
      Session original =
          sessions.computeIfAbsent(named(retained), id -> new Session(id, clock.instant()));
      if (!original.claim(account) || fresh != null && !fresh.claim(account))
        throw new IllegalStateException(
            "Workspace recovery cannot change the authenticated account");
      if (fresh != null && fresh != original) {
        // The viewer may follow several previously verified workspaces. Only its listener is
        // shared; a file provider always remains attached to its original logical workspace.
        fresh
            .attached(Role.LISTENER, Object.class)
            .ifPresent(listener -> original.attach(Role.LISTENER, listener, account));
      }
      verified.add(retained);
      sessions.put(incoming, original);
    }
  }

  /**
   * Attach something to a session in one role, creating the session if this is the first thing to
   * attach to that id — as nobody's: {@link #attach(String, Role, Object, String)} with no account,
   * which a socket never uses (both channels name theirs).
   *
   * @return what this call displaced, for the caller to close or otherwise finish with. Empty when
   *     the role was free, and empty when the very same object was already in it
   * @throws IllegalArgumentException if {@code id} is blank. A session nothing can name is one no
   *     job can be routed to, which is the same refusal {@code ws.FileChannelHandler} makes about a
   *     socket opened without one
   * @throws IllegalStateException if an account holds the session: nobody's attachment cannot join
   *     it
   */
  public Optional<Object> attach(String id, Role role, Object attachment) {
    Attached attached = attach(id, role, attachment, null);
    if (attached.refused()) {
      throw new IllegalStateException(
          "session '"
              + id
              + "' is held by an account, and an"
              + " attachment naming none cannot join it");
    }
    return attached.displaced();
  }

  /**
   * What an attach for an account came to: refused, because another account holds the session, or
   * attached, displacing what {@link #displaced} names.
   */
  public record Attached(boolean refused, Optional<Object> displaced) {}

  /**
   * Claim {@code id} for {@code handle} if nothing has, creating the session. A connection asks
   * this before it claims anything else (a presence, a delivery), so another account's socket is
   * refused before it has done anything.
   *
   * @param handle the account the connecting socket signed in as, or null for nobody
   * @return whether {@code id} is held by {@code handle} now
   */
  public boolean claim(String id, String handle) {
    synchronized (recovering) {
      String named = named(id);
      Instant now = clock.instant();
      return sessions.computeIfAbsent(named, fresh -> new Session(fresh, now)).claim(handle);
    }
  }

  /**
   * The account {@code id} is held by: empty for an unknown id, an unclaimed one, or one held by
   * nobody.
   */
  public Optional<String> accountOf(String id) {
    return find(id).flatMap(Session::account);
  }

  /**
   * {@link #attach(String, Role, Object)} for the account {@code handle}: the session is held by
   * the first account that claims it, for as long as this registry holds it, and another account's
   * attach is refused on either role and displaces nothing (Enzo's decision of 2026-09-30, spec
   * 2026-09-30-local-hooks-are-served). The same account displaces, as ever.
   *
   * @param handle the account the socket signed in as, or null for nobody
   */
  public Attached attach(String id, Role role, Object attachment, String handle) {
    synchronized (recovering) {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(attachment, "attachment");
      String named = named(id);
      Instant now = clock.instant();
      Session session = sessions.computeIfAbsent(named, fresh -> new Session(fresh, now));
      if (role == Role.LISTENER
          && !session.id().equals(named)
          && session.attached(role, Object.class).orElse(null) != attachment) {
        if (!session.claim(handle)) return new Attached(true, Optional.empty());
        Object previous = session.attached(role, Object.class).orElse(null);
        Session transport = new Session(named, now);
        transport.attach(role, attachment, handle);
        sessions.put(named, transport);
        recovered.remove(named);
        return new Attached(false, Optional.ofNullable(previous));
      }
      session.seen(now);
      Object displaced = session.attach(role, attachment, handle);
      if (displaced == Session.REFUSED) {
        log.warn(
            "Session '{}' is held by another account; a {} for a different account was"
                + " refused and displaced nothing.",
            named,
            role);
        return new Attached(true, Optional.empty());
      }
      if (displaced != null) {
        // WARN and not INFO, and only on the displacement. A line on every
        // attach is noise an operator filters out, and filtering it out
        // filters out this one — a second connection arriving under a live
        // id is either a reconnect the first socket has not noticed yet or
        // two clients sharing an id, and the second is a wiring fault
        // somebody has to see. Says only what this class did: whether the
        // displaced connection is then closed belongs to the caller.
        log.warn(
            "Session '{}' attached a second {} while one was already attached; the"
                + " newer one is the session's {} now, and the older one is returned to"
                + " the caller that displaced it.",
            named,
            role,
            role);
      }
      return new Attached(false, Optional.ofNullable(displaced));
    }
  }

  /**
   * Take something out of the role it is attached to.
   *
   * <p>Leaves the session, and leaves its other role. A listener closing must not take a file
   * provider with it — they are two connections and the session is neither of them.
   *
   * @return whether this call is the one that removed it. False for an id nothing has attached to,
   *     for an empty role, and for an attachment that has already been displaced by another
   */
  public boolean detach(String id, Role role, Object attachment) {
    synchronized (recovering) {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(attachment, "attachment");
      Session session = sessions.get(named(id));
      if (session == null) {
        return false;
      }
      boolean detached = session.detach(role, attachment);
      if (role == Role.LISTENER) {
        for (String alias : logicalSessions(id)) {
          Session logical = sessions.get(alias);
          if (logical != null) detached |= logical.detach(role, attachment);
        }
      }
      if (!detached) return false;
      session.seen(clock.instant());
      return true;
    }
  }

  /**
   * How many sessions are held. Package-private because the only caller is the test that pins what
   * does and does not create one; nothing in {@code main} counts sessions.
   */
  int count() {
    return sessions.size();
  }

  private static String named(String id) {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException(
          "a session needs an id; one that cannot be named is one no job can reach");
    }
    return id;
  }
}
