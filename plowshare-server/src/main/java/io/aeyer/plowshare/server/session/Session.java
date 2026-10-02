package io.aeyer.plowshare.server.session;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One session: an id, when it started, when it was last seen, and whatever is
 * attached to it in each {@link Role}.
 *
 * <h2>It outlives every socket attached to it</h2>
 *
 * <p>That is the whole reason it is an object. A file channel may close and
 * reopen, a listener may never exist, and it is the same session throughout — so
 * a job submitted under an id finds whatever is attached at the moment it asks
 * rather than whatever was attached at the moment it was submitted. Everything
 * else here follows from that: the roles are slots that fill and empty, and none
 * of them is the session.
 *
 * <h2>An attachment is an {@link Object}, deliberately</h2>
 *
 * <p>What attaches in production is a Spring {@code WebSocketSession}, and this
 * package must not know that. It is the argument {@code files.SessionChannel}
 * already makes one package over — {@code files/} must not know about
 * WebSockets, and {@code ws/} already depends on {@code files/} — and it buys
 * the same two things: the dependency runs in the one direction the module
 * already runs in, and the registry above this is exercisable without Spring,
 * which is what lets it be pointed at its own edges.
 *
 * <p>{@link #attached(Role, Class)} is where a caller puts the type back. It
 * casts rather than filtering, so a role holding something other than what the
 * caller expects arrives as a {@link ClassCastException} at the seam instead of
 * as an empty {@link Optional} that reads like "nothing is attached".
 *
 * <h2>Mutation belongs to {@link SessionRegistry}</h2>
 *
 * <p>{@code attach}, {@code detach} and {@code seen} are package-private. The
 * registry is where a displacement is reported and recorded, and a second caller
 * able to swap an attachment without going through it would be a second
 * displacement story — which is the thing this package exists not to have.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Attaches arrive on Spring container threads and reads happen on the virtual
 * threads running jobs, so every field here is either final, volatile, or a
 * {@link ConcurrentHashMap}. There is no lock, and {@link SessionRegistry}'s
 * javadoc sets out why none is needed rather than leaving the absence to be read
 * as an oversight.
 */
public final class Session {

    private final String id;

    private final Instant created;

    /** Volatile rather than under a lock: one writer's stamp replacing
     *  another's is not a lost update, because the value is "recently", not a
     *  count. */
    private volatile Instant lastSeen;

    private final Map<Role, Object> attachments = new ConcurrentHashMap<>();

    /**
     * The account this session is held by, from the first socket that claimed it for as long as
     * this object lives (Enzo's decision of 2026-09-30, spec 2026-09-30-local-hooks-are-served):
     * a later socket of another account is refused on either role and never displaces. In
     * memory only; a restart clears it. {@link #claimed} tells a session held by nobody (a null
     * account) from one not yet claimed.
     */
    private final ReentrantLock claiming = new ReentrantLock();
    private boolean claimed;
    private String account;

    Session(String id, Instant created) {
        this.id = Objects.requireNonNull(id, "id");
        this.created = Objects.requireNonNull(created, "created");
        this.lastSeen = created;
    }

    /** The id the client minted and named itself by. */
    public String id() {
        return id;
    }

    /** When this session was first attached to. */
    public Instant created() {
        return created;
    }

    /**
     * When something last attached to or detached from this session.
     *
     * <p><b>Nothing reads this in production yet</b>, because the reaper that
     * would is deliberately not in this task; {@link SessionRegistry} records
     * the decision and what it waits on. It is stamped and tested from the day
     * the field exists so that whatever eventually reads it reads a true value
     * rather than one that has to be retrofitted.
     */
    public Instant lastSeen() {
        return lastSeen;
    }

    /** Whether anything is attached in {@code role} right now. */
    public boolean has(Role role) {
        return attachments.containsKey(Objects.requireNonNull(role, "role"));
    }

    /** The roles filled right now. A snapshot: a role may be attached or
     *  detached the instant after this returns. */
    public Set<Role> roles() {
        return Set.copyOf(attachments.keySet());
    }

    /**
     * What is attached in {@code role}, as the type the caller attached.
     *
     * @param type what the caller put in this role. The cast is the caller's own
     *     claim being checked, and it fails loudly rather than reading as an
     *     empty role
     * @return empty if nothing is attached in that role
     */
    public <T> Optional<T> attached(Role role, Class<T> type) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(type, "type");
        return Optional.ofNullable(attachments.get(role)).map(type::cast);
    }

    /**
     * The account this session is held by: empty when it is unclaimed or held by nobody.
     */
    public Optional<String> account() {
        claiming.lock();
        try {
            return Optional.ofNullable(account);
        } finally {
            claiming.unlock();
        }
    }

    /**
     * Claim this session for {@code handle} if nothing has, under this session's own lock.
     *
     * @return whether it is held by {@code handle} now: true for the first claim and for the
     *     same account again, false for any other
     */
    boolean claim(String handle) {
        claiming.lock();
        try {
            if (!claimed) {
                claimed = true;
                account = handle;
                return true;
            }
            return Objects.equals(account, handle);
        } finally {
            claiming.unlock();
        }
    }

    /**
     * {@link #attach(Role, Object)} for {@code handle}, claimed in the same step under this
     * session's lock, so no other account's attach can land between the check and the put.
     *
     * @return what was displaced, as {@link #attach}; {@link #REFUSED} when another account
     *     holds this session, in which case nothing was attached
     */
    Object attach(Role role, Object attachment, String handle) {
        claiming.lock();
        try {
            if (!claim(handle)) {
                return REFUSED;
            }
            return attach(role, attachment);
        } finally {
            claiming.unlock();
        }
    }

    /** What {@link #attach(Role, Object, String)} answers for another account's socket. */
    static final Object REFUSED = new Object();

    /**
     * Put {@code attachment} in {@code role}, displacing whatever was there.
     *
     * @return what was displaced, or null if the role was empty or already held
     *     this same object. Identity and not equality: the caller that acts on a
     *     displacement closes what it is handed, so handing back the object just
     *     attached would have it close the connection it had this instant
     *     registered
     */
    Object attach(Role role, Object attachment) {
        Object previous = attachments.put(role, attachment);
        return previous == attachment ? null : previous;
    }

    /**
     * Take {@code attachment} out of {@code role}, if it is still the one there.
     *
     * <p><b>Identity-checked, and that is the load-bearing half.</b> A displaced
     * connection still gets its own close event, and a detach that matched on
     * the role alone would let that late event unhook the connection that
     * replaced it. {@code ws.FileChannelHandler} makes the same check twice for
     * the same reason — {@code live.socket != socket} before applying a reply,
     * and this method, which its close calls, on the way out.
     *
     * @return whether this call is the one that removed it. False for a role
     *     that is empty and false for one holding something else
     */
    boolean detach(Role role, Object attachment) {
        return attachments.remove(role, attachment);
    }

    /** Stamp {@link #lastSeen()}. */
    void seen(Instant now) {
        this.lastSeen = now;
    }

    @Override
    public String toString() {
        return "Session[" + id + " " + roles() + "]";
    }
}
