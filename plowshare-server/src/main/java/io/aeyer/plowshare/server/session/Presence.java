package io.aeyer.plowshare.server.session;

import java.util.Objects;

/**
 * One live session's claim to root one project: the machine it sits on, the
 * place on that machine, and the friendly name a person gave it.
 *
 * <p>This is the binding the whole presence design was missing. A {@code
 * ProjectRecord} has a path and no machine, and a path means something only on
 * the machine that holds it; a presence is the fact that a particular running
 * client is <em>at</em> that place right now and can be asked about it.
 *
 * <h2>Runtime state, deliberately, and no row anywhere</h2>
 *
 * <p>The design spec's §2 rule, unchanged by §10: <b>which machine holds a
 * project is stable and belongs in the identity; whether a live session is
 * currently serving it is ephemeral and stays here.</b> Nothing in this record
 * is written to the archive, and no migration was needed to add it — a session
 * that goes away takes its presence with it, and a project rooted nowhere right
 * now is still a project.
 *
 * <h2>{@code root} is a {@link String} and not a {@code Path}</h2>
 *
 * <p>Because it is a path on <em>another machine</em>, and this process must not
 * resolve it. {@code RemoteProvider}'s javadoc makes the argument in the place
 * it bites hardest: "a path resolved against this server's filesystem is a claim
 * about a different set of files that happen to share a name". Here the path is
 * never opened, compared or normalised against anything — it is one component of
 * an identity — so turning it into a {@link java.nio.file.Path} would buy
 * platform semantics from the wrong platform and nothing else. A Windows client's
 * {@code C:\work\ledger} is not a relative path, whatever this server's
 * {@code Path.of} would make of it.
 *
 * <p>The one thing that <em>is</em> checked is that it names a place at all: a
 * root with no separator starting it is a path only the machine that typed it
 * could find, and the machine that typed it is not this one.
 *
 * @param session the id the client registered under, which is the identity of
 *     the presence. A reconnect under the same id is the same machine, which is
 *     what makes {@link PresenceRegistry#declare} idempotent for it
 * @param machine what the client calls the box it sits on. A <b>client</b>
 *     property with a default of the hostname — the server cannot know this and
 *     never guesses it. Its known collision mode is recorded in the design
 *     spec's §10: two machines both defaulting to {@code MacBook-Pro.local}
 *     produce colliding canonical names, and setup should push an operator to
 *     set it
 * @param root where the project sits on that machine, absolute, as the client
 *     spells it
 * @param project the friendly name a person gave it — what {@code Home} carries,
 *     what the API takes, and what {@link PresenceRegistry} keys on
 */
public record Presence(String session, String machine, String root, String project) {

    /** The separator the canonical name is built with. Not the server's {@code
     *  File.separator}: this is a name and not a path, and it must read the same
     *  whichever machine composed it or looks at it. */
    private static final char SEPARATOR = '/';

    public Presence {
        session = named(session, "session");
        machine = named(machine, "machine");
        root = named(root, "root");
        project = named(project, "project");
        if (root.charAt(0) != SEPARATOR && !root.contains(":")) {
            // A drive letter is the one other way a path can be rooted, and it
            // is admitted rather than refused: this server does not get to tell
            // a Windows client that its own absolute path is relative.
            throw new IllegalArgumentException("a presence's root must be absolute on the"
                    + " machine that holds it; '" + root + "' names a place only that machine"
                    + " could find, and it is not this one");
        }
    }

    /**
     * {@code <MACHINE>/<PATH>/<PROJ_NAME>}, the design spec's §10 identity.
     *
     * <p>The location is <em>in</em> the identity, which is what settles §8's
     * residual question by construction: a project re-rooted on another machine
     * has a different canonical name and is a different project.
     *
     * <p><b>Nothing files by this yet, and that is the task boundary rather than
     * an omission.</b> {@code projects.name} still holds the friendly name a
     * person typed, and moving a project — the operation that would rewrite it —
     * is the next task. What this method is for today is saying <em>which
     * place</em> in a refusal and in a conflict, where "the presence on
     * bench.local" and "the presence on desk" is the distinction an operator
     * acts on.
     *
     * <p>The root's leading separator is spent as the one between the machine and
     * it, and a trailing one is dropped, so two spellings of one place are one
     * identity rather than two.
     */
    public String canonicalName() {
        String between = root;
        while (!between.isEmpty() && between.charAt(0) == SEPARATOR) {
            between = between.substring(1);
        }
        while (!between.isEmpty() && between.charAt(between.length() - 1) == SEPARATOR) {
            between = between.substring(0, between.length() - 1);
        }
        return machine + SEPARATOR + between + SEPARATOR + project;
    }

    @Override
    public String toString() {
        return "Presence[" + canonicalName() + " on session " + session + "]";
    }

    private static String named(String value, String what) {
        Objects.requireNonNull(value, what);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a presence needs a " + what
                    + "; a claim with a blank one names no place and no claimant");
        }
        return trimmed;
    }
}
