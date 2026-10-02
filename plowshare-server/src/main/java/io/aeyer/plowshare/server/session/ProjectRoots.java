package io.aeyer.plowshare.server.session;

/**
 * Where a project's files are, written down.
 *
 * <p>The durable half of a presence. {@link PresenceRegistry} holds the
 * ephemeral half — <em>which live session can reach that place right now</em> —
 * and deliberately stores nothing; this is the other fact the same declaration
 * asserts, and the design spec's §10 is explicit that they are two fields and
 * not one: <b>"which machine holds it is stable and belongs in the identity;
 * whether a live session is currently serving it is ephemeral and stays a
 * runtime registry."</b>
 *
 * <p>Both are written by one event — a client opening the file channel with
 * {@code ?session=&machine=&root=&project=} — and by nothing else. §13.4 asked
 * for a registration verb and this is the answer to it: <b>declaring a presence
 * is the registration</b>, so there is no second way to assert the same fact and
 * therefore no pair of assertions that can disagree.
 *
 * <h2>A seam, for the reason {@code SessionChannel} is one</h2>
 *
 * <p>{@code session/} depends on nothing in {@code archive/} and this keeps that
 * true: the implementation is {@code ProjectStore::rootOn}, bound in {@code
 * AgentsConfig} beside the store it comes from. What that buys is the same thing
 * {@code files/} gets from {@code SessionChannel} — the socket half of this can
 * be driven in a test with a servlet container and no Postgres behind it, which
 * is exactly the arrangement {@code FileChannelTest} has.
 *
 * <p>It is not a policy seam. There is one implementation and there is meant to
 * be one; a second would be a second place a project's location is written,
 * which is the thing the paragraph above rules out.
 */
@FunctionalInterface
public interface ProjectRoots {

    /**
     * Record that {@code project}'s files are on {@code machine}, at {@code
     * root} on it.
     *
     * <p><b>Nothing validates the path and nothing may.</b> The server does not
     * have that disk; the only check available to it is whether this machine
     * happens to hold a path of the same name, which is the one answer worse
     * than none. The client is the enforcement point for its own files and is
     * therefore the authority on them.
     *
     * <p>Idempotent for the machine that already roots the project, which is
     * what makes it safe on every reconnect — a client re-declares each time its
     * socket comes back.
     *
     * @param project the friendly name, as {@code Home} carries it
     * @param machine what the client calls the box it sits on
     * @param root where the project sits on that machine, as the client spells
     *     it. A {@link String} for {@link Presence#root()}'s reason: a path
     *     resolved against this server's filesystem is a claim about a different
     *     set of files that happen to share a name
     * @param handle the account declaring the presence, recorded as a new project's first member
     * @throws io.aeyer.plowshare.server.archive.ArchiveRefusedException if some
     *     other place already holds the project — another machine, or this
     *     server. A project exists in exactly one location
     */
    void rootOn(String project, String machine, String root, String handle);
}
