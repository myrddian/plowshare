package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;

/**
 * A way to ask one client session for something and wait for the answer.
 *
 * <h2>Why this interface exists rather than {@link RemoteProvider} holding a
 * socket</h2>
 *
 * <p>Because {@code files/} must not know about WebSockets, and {@code ws/}
 * already depends on {@code files/}. The one implementation is {@code
 * ws.FileChannelHandler}, which is a Spring {@code WebSocketHandler}; putting
 * the seam here is what lets the dependency run in the one direction the module
 * already runs in, and what lets {@link RemoteProvider} be tested against a
 * two-line fake rather than against a port.
 *
 * <h2>Why this stays in {@code files/} rather than moving to {@code ws/} with
 * its one implementation</h2>
 *
 * <p>This is the port; {@code ws.FileChannelHandler} is its adapter, and the two
 * belong in different places for the reason the terms name — a port lives with
 * the subsystem that needs it, an adapter with the transport that supplies it.
 * Its dependents are {@link RemoteProvider} and {@link SessionCloseListener}
 * here in {@code files/}, plus five classes in {@code agents/} ({@code
 * AgentsConfig}, {@code ChannelDefinitions}, {@code DefinitionResolver}, {@code
 * JobEvents} and {@code JobStore}); moving the interface to {@code ws/} would
 * make every one of those import a package named for a socket, for a capability
 * none of them should have to know exists. Leaving it here is what lets {@code
 * files/} and {@code agents/} go on knowing nothing about a socket at all.
 *
 * <p>It is also where the <b>two bounds</b> live, and they are the whole reason
 * this returns rather than taking a callback:
 *
 * <ul>
 *   <li><b>a closed socket is an event.</b> That is why the channel is a
 *       WebSocket and not polling: the moment a session goes, every request
 *       outstanding on it fails, rather than each one waiting out a deadline
 *       that was written for a different failure. Measured on this host: a
 *       client's clean {@code close} reaches the server's handler in 4 ms and an
 *       abrupt {@code cancel} — what a killed client process looks like — in
 *       1 ms;
 *   <li><b>a client that is connected and wedged is not a closed socket.</b>
 *       Measured: nothing fires at all, and the session still reports itself
 *       open. So every request also carries a deadline, and the deadline is the
 *       only instrument that case has.
 * </ul>
 *
 * <p><b>Blocking here is free, and 3a proved why.</b> A job is a virtual thread;
 * one blocked on a file request holds no lane slot, exactly as a parent blocked
 * on a child does not. So this returns the answer and there are no callbacks
 * anywhere above it.
 */
public interface SessionChannel {

    /** Opt-in from the actual attached file socket; old clients keep text replies. */
    default boolean sources(String session) { return false; }

    /**
     * Put one request to a session and block until it answers, closes, or runs
     * out of time.
     *
     * @param session the id the client registered under when it opened the
     *     socket. Chosen by the client, not by any model
     * @param request what to do, carrying the correlation id this answer will
     *     come back on
     * @return the client's answer, which is either done or refused. It is never
     *     "unavailable": {@link FileReply} says why that is not a thing a client
     *     can say about itself
     * @throws SessionGoneException if the session is known to have gone — no
     *     such session is connected, it closed, it was taken over, or the write
     *     to it failed. This is the subtype {@code JobRuntime} turns into {@code
     *     SESSION_GONE}
     * @throws WorkspaceUnavailableException for the outages that are <em>not</em>
     *     a client going away, chiefly the deadline: a client that has not
     *     answered is still connected, and nothing can tell a wedged one from a
     *     slow one. The message says which in every case, because a socket that
     *     closed and a socket that went quiet are two different things to an
     *     operator even though both end the run
     */
    FileReply ask(String session, FileRequest request);

    /**
     * {@link #ask(String, FileRequest)}, waiting as long as {@code deadline}
     * rather than the channel's own.
     *
     * <p>For {@link FileRequest#RUN}, which lasts as long as its command: a build
     * that takes four minutes is not a client that went quiet. A channel with no
     * deadline of its own to replace waits as it always does.
     */
    default FileReply ask(String session, FileRequest request, java.time.Duration deadline) {
        return ask(session, request);
    }
}
