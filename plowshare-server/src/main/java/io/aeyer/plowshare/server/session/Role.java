package io.aeyer.plowshare.server.session;

/**
 * What a connection attached to a session <em>as</em>.
 *
 * <h2>Both are optional, and they are independent</h2>
 *
 * <p>"A client is a role, not a location" is already this project's rule; a
 * session takes it one step further, because a client is not even one
 * connection. What a session can do is decided by which of these are attached to
 * it at the moment a job asks:
 *
 * <ul>
 *   <li><b>neither</b> — an id nobody is using;
 *   <li><b>{@link #FILE_PROVIDER} only</b> — jobs reach the operator's disk and
 *       nobody is watching;
 *   <li><b>{@link #LISTENER} only</b> — jobs are watched and files are
 *       server-local;
 *   <li><b>both</b> — the command-line client.
 * </ul>
 *
 * <p><b>The listener-only row is not padding.</b> It is the browser: a browser
 * cannot hold {@link #FILE_PROVIDER}, because doing so would need a second
 * implementation of {@code FileAccess} in JavaScript, and this project's
 * enforcement is one execution run twice rather than two implementations that
 * can disagree. Building the roles independent now is what makes that client
 * supported later rather than a special case.
 *
 * <p><b>A session existing is not a role being attached</b>, and the two are
 * separate questions here so that nothing above can conflate them. A job
 * submitted under an id whose only role is a listener must get exactly the
 * providers a job submitted with no session at all gets.
 */
public enum Role {

    /**
     * Answers file requests for the machine that opened the session — in
     * production the {@code /v1/files} socket that {@code ws.FileChannelHandler}
     * serves.
     */
    FILE_PROVIDER,

    /**
     * Watches the lifecycle of jobs submitted under the session.
     *
     * <p>Watching is not owning: what this role receives is droppable, and a job
     * whose listener has gone finishes and files its outcome exactly as one that
     * never had one.
     */
    LISTENER
}
