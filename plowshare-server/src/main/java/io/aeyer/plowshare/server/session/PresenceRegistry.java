package io.aeyer.plowshare.server.session;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which live session roots which project, right now.
 *
 * <p>The one piece the presence design was missing, and the whole of it: {@code
 * AgentsConfig.runProviders} asks this rather than assuming the caller, so a run
 * reaches <b>the machine that holds the project</b> and not the machine that
 * happened to submit it.
 *
 * <h2>A mapping, not a set — and it needed no migration</h2>
 *
 * <p><b>At most one presence serves a project at a time.</b> That is the owner's
 * decision of 2026-09-03 and it removes the hardest problem in the design rather
 * than deferring it: no divergence, no reconciliation, no authoritative-copy
 * rule, and {@code AmbiguousPathException} stays a genuine error. A second
 * session claiming a rooted project is refused with {@link
 * PresenceConflictException}, naming both claims.
 *
 * <p>And <b>at most one project per session</b>, which is this class's own and
 * follows from where a declaration comes from: it rides on the file channel, and
 * a session has one file channel. So {@link #declare} frees whatever the session
 * used to root before it claims anything — without that, a client that reconnects
 * pointed somewhere new leaves its old project claimed by a socket that will
 * never answer for it again, and every run in that project ends {@code
 * SESSION_GONE} naming a session nobody is at.
 *
 * <p>Nothing here is stored. A presence is a property of a <em>running
 * session</em>, exactly as the design spec's §2 says, and a project rooted
 * nowhere right now is still a project — which is why this arrived with no
 * migration behind it.
 *
 * <h2>Who may address a presence: one operator, so any of them</h2>
 *
 * <p><b>This is the genuinely new question presence raises and it is answered
 * here rather than by default.</b> Before presence the leash answered itself: a
 * run reached the caller's own machine because {@code RemoteProvider} was built
 * from the caller's own session, so there was no machine to address that the
 * caller was not sitting at. With presence there is.
 *
 * <p>The rule: <b>the operator may address any presence they have running.</b>
 * That is not a guess and it is not permissiveness — it follows from there being
 * exactly one principal. {@code plowshare.auth} is single-user: one operator, one
 * credential, and {@code AuthFilter} gates every {@code /v1} path with it, both
 * of a session's sockets included. {@code SessionClient}'s javadoc already states
 * the half this rests on — "authentication here is operator-level and not
 * per-session: holding the token means being the operator, and the operator may
 * attach to any session by name". Every presence in this registry was therefore
 * declared by the operator, and a caller who could reach this server at all is
 * the operator. A check comparing the caller's session against the presence's
 * would be comparing one principal with itself.
 *
 * <p><b>What that means concretely, so it is not discovered:</b> a run submitted
 * from one terminal causes reads and writes on whichever machine roots the
 * project, including a machine the person is not sitting at. That is the
 * feature. The leash is unchanged and still doubled — {@code ClientEnforcer}
 * refuses against the workspace that session holds at the instant the file would
 * be opened, and the agent's own {@code scopes:} still bound the mode — so what
 * presence widens is <em>which machine</em>, never <em>how much of it</em>.
 *
 * <p><b>THIS IS THE SENTENCE THAT NEEDS REVISITING WHEN AUTH BECOMES
 * MULTI-USER.</b> The moment there are two principals, "one operator" stops
 * being true and this paragraph stops being an argument: a presence would then
 * need an owner, {@link #serving} would need to be asked on behalf of somebody,
 * and this registry would be where that is enforced. Nothing else in the system
 * would notice the difference, which is exactly why it is written here rather
 * than left to be inferred from the absence of a check. {@code
 * InvariantsTest.the_presence_registry_states_who_may_address_a_presence} holds
 * the paragraph in place so a rewrite of this class cannot quietly drop it.
 *
 * <h2>{@link ConcurrentHashMap} plus one lock, and the lock has something under
 * it</h2>
 *
 * <p>The divergence from {@link SessionRegistry}, which deliberately has none.
 * There every mutation is a single already-atomic map operation; here {@link
 * #declare} is a compound action — free what this session used to root, then
 * claim this project, refusing if somebody else holds it — and two of them
 * interleaving could leave a project claimed by nobody. So the two mutators take
 * one lock and {@link #serving}, which is the hot path and reads a single key,
 * takes none.
 *
 * <p>A {@link ReentrantLock} and never {@code synchronized}, for the reason
 * {@link SessionRegistry} measured on this build's JVM: a virtual thread that
 * blocks inside a monitor pins its carrier, and the readers here are the virtual
 * threads running jobs.
 */
public final class PresenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(PresenceRegistry.class);

    /** Keyed by the friendly project name — what {@code Home} carries and what a
     *  person types. <b>Not by the canonical name</b>, and that is the task
     *  boundary: {@code forRun} is handed a {@code Home}, {@code Home} carries
     *  the name a person typed, and nothing translates one into the other until
     *  a move operation exists to rewrite {@code projects.name}. The canonical
     *  name is composed and reported; it is not yet a key. */
    private final Map<String, Presence> byProject = new ConcurrentHashMap<>();

    private final ReentrantLock claiming = new ReentrantLock();

    /**
     * Record that {@code presence}'s session roots its project, freeing whatever
     * that session used to root.
     *
     * @return the presence now in force, which is the argument
     * @throws PresenceConflictException if another live session already roots
     *     that project. Both canonical names are in the message: an operator
     *     hitting this has two clients running and needs to know which to close
     */
    public Presence declare(Presence presence) {
        Objects.requireNonNull(presence, "presence");
        claiming.lock();
        try {
            Presence held = byProject.get(presence.project());
            if (held != null && !held.session().equals(presence.session())) {
                // Refused, and nothing is changed. Not a displacement: a second
                // socket under one session id is one client reconnecting and the
                // newer one wins, but a second MACHINE claiming one project is
                // two places asserting they are one, and nothing can check which
                // is right.
                // BOTH CANONICAL NAMES FIRST, and that ordering is load-bearing
                // rather than a preference: FileChannelHandler sends this
                // sentence back in a WebSocket close reason, whose control
                // payload is 125 bytes, so anything after the first ~120 may not
                // reach the person who has to act on it. The names are what they
                // act on; the advice is what they can do without.
                throw new PresenceConflictException(held.canonicalName() + " already roots '"
                        + presence.project() + "'; " + presence.canonicalName() + " cannot also"
                        + " root it. A project exists in exactly one location, so close"
                        + " whichever of the two is the wrong one rather than running both."
                        + " (sessions '" + held.session() + "' and '" + presence.session()
                        + "')");
            }
            // Frees the session's previous project before claiming this one. A
            // no-op for the ordinary first declaration and for a reconnect that
            // names the same project; the case it is for is a client that comes
            // back pointed somewhere else.
            withdrawWhileHolding(presence.session());
            byProject.put(presence.project(), presence);
            log.info("Session '{}' roots the project '{}' at {}.", presence.session(),
                    presence.project(), presence.canonicalName());
            return presence;
        } finally {
            claiming.unlock();
        }
    }

    /**
     * The presence serving {@code project} right now, if any.
     *
     * <p><b>Empty is an ordinary answer and the common one.</b> A project nobody
     * is running a client for is rooted nowhere, and a run in it has a smaller
     * set rather than an empty capability — {@code RunProviders.forRun}'s rule,
     * extended. The refusal a tool then gives names the project, which is {@code
     * AbsentPresence}'s whole job.
     *
     * <p>Lenient about its argument for {@code SessionRegistry.find}'s reason: a
     * run's home may be global, and <b>the global tier is the absence of a
     * project</b>, so a null arriving here is the ordinary shape of a run that is
     * not place-bound rather than a fault.
     */
    public Optional<Presence> serving(String project) {
        if (project == null || project.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byProject.get(project));
    }

    /**
     * The presence {@code session} holds right now, if any — {@link #serving}
     * asked from the other end.
     *
     * <p>At most one, because {@link #declare} withdraws a session's previous
     * claim before it records the next. <b>The caller it exists for</b> is
     * {@code AgentsConfig.definitionResolver}: a session's own {@code
     * .plowshare/} belongs to the project it roots, and a resolver that holds a
     * project id and a session id has no name to hand {@link #serving}. A scan
     * rather than a second map, on this class's own precedent in {@code
     * withdrawWhileHolding}: there are as many entries as there are rooted
     * projects, and a second map is a second thing for a declaration to keep in
     * step.
     */
    public Optional<Presence> rootedBy(String session) {
        if (session == null) {
            return Optional.empty();
        }
        return byProject.values().stream()
                .filter(presence -> presence.session().equals(session))
                .findFirst();
    }

    /**
     * Stop {@code session} rooting anything, because its file channel has gone.
     *
     * @return whether this call removed a presence. False for a session that
     *     never declared one, which is every listener-only client there has ever
     *     been, and false for one already withdrawn
     */
    public boolean withdraw(String session) {
        Objects.requireNonNull(session, "session");
        claiming.lock();
        try {
            return withdrawWhileHolding(session);
        } finally {
            claiming.unlock();
        }
    }

    /** How many projects are rooted. Package-private: the only caller is the
     *  test that pins what does and does not leave a claim behind. */
    int count() {
        return byProject.size();
    }

    private boolean withdrawWhileHolding(String session) {
        boolean removed = false;
        for (Map.Entry<String, Presence> entry : byProject.entrySet()) {
            if (entry.getValue().session().equals(session)) {
                // remove(key, value) rather than remove(key): the entry is
                // re-read from the map here, and the two-argument form is what
                // makes this a removal of the presence that was seen rather than
                // of whatever holds the key by the time the loop reaches it.
                removed |= byProject.remove(entry.getKey(), entry.getValue());
            }
        }
        return removed;
    }
}
