package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * Who is asking, as much of it as a frame actually carries: the session the
 * socket was opened under, and the account the upgrade was signed in as when
 * there is one. Nothing here authenticates; {@code AuthFilter} did, before
 * the upgrade.
 *
 * <h2>This type exists to make one divergence impossible rather than unlikely</h2>
 *
 * <p>A frame handler used to be handed a {@link DefinitionResolver.Caller}
 * built by the channel as {@code Caller(null, sessionId)} — a caller whose
 * project is <b>always</b> null, because a socket is opened per session and
 * never per project. Neither pilot read it, so nothing failed. The trap that
 * left behind is the one a breadth task walks into: most endpoints worth
 * moving <em>do</em> take a project, their controllers resolve it out of the
 * request through {@code Callers.callerFor} or {@code
 * requests.RequestedProjectId}, and a handler that passed this channel's caller
 * straight down would have answered every one of them against the boot set
 * instead of the project's — the same status, the same shape, a different
 * answer, and a parity test written to match whatever the handler did would
 * have agreed with it.
 *
 * <p><b>So the frame surface does not hand a handler a caller at all.</b> There
 * is no project-bearing {@code Caller} on this path to pass on by accident; a
 * handler that needs one has to build it, and the only place it can get a
 * project from is the payload — which is exactly where the endpoint's own
 * controller gets it from, off the request body or the query string. The rule
 * "a frame's project comes from its payload" is therefore not a convention a
 * breadth task can forget: doing anything else does not compile.
 *
 * <h2>The two doors, and why this type offers only those two</h2>
 *
 * <p>{@link #callerFor} and {@link #callerForConversation} are {@link Callers}'
 * own two doors, no wider and no narrower — see that class's javadoc for why
 * they are two and not one. A handler whose endpoint builds its caller some
 * third way ({@code AgentController.agents} resolves through {@code
 * RequestedProjectId.forListing} and deliberately drops the session) builds it
 * that same third way here, naming the payload field it read the project out
 * of. What must not appear is a fourth way invented for the socket.
 *
 * <p><b>Nothing here authenticates.</b> Spec §2 puts every credential check
 * before the socket exists, so this is scoping — which session, and through it
 * which project — and never a grant.
 *
 * @param sessionId the session this socket was opened under, or {@code null}
 *     for a frame that arrived on one this server could not name. Null is
 *     tolerated rather than refused because refusing it here would turn a
 *     channel-level wiring fault into an exception on the inbound thread, and
 *     {@code EventChannelHandler} already closes a socket that named no
 *     session before any frame on it is read
 * @param handle the account this socket's upgrade carried, or {@code null}
 *     for one that carried none — an operator token with no configured
 *     admin handle, say. {@link #requireHandle} is the door a frame that
 *     needs one goes through
 */
public record Asking(String sessionId, String handle, String connectionId) {
    public Asking(String sessionId, String handle) { this(sessionId, handle, null); }


    /** A caller whose socket carries no account: every frame that does not need one. */
    public Asking(String sessionId) {
        this(sessionId, null);
    }

    /** The account this socket is signed in as, or the refusal a frame needing one gives. */
    public String requireHandle(String type) {
        if (handle == null || handle.isBlank()) {
            throw new CallerFault(type + " needs a socket signed in as an account, and this one"
                    + " is not: sign in, or set plowshare.auth.admin-handle so the operator"
                    + " token is the admin.");
        }
        return handle;
    }

    /**
     * The caller for a request that names its own project — {@code
     * Callers.callerFor}, reached with the project this frame's payload
     * carried.
     *
     * @param callers the same service the controller is injected with
     * @param project the project field <em>this payload</em> named, which may
     *     be null or blank exactly as the request body's may be
     */
    public DefinitionResolver.Caller callerFor(Callers callers, String project) {
        return callers.callerFor(project, sessionId);
    }

    /**
     * The caller for a turn spoken into a conversation, whose project is the
     * conversation's own and never a field of the request — {@code
     * Callers.callerForConversation}, reached with the conversation this
     * frame's payload named.
     *
     * @param callers the same service the controller is injected with
     * @param conversation the conversation field this payload named
     */
    public DefinitionResolver.Caller callerForConversation(Callers callers, String conversation) {
        return callers.callerForConversation(conversation, sessionId);
    }
}
