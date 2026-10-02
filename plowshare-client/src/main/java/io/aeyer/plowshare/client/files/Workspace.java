package io.aeyer.plowshare.client.files;

import io.aeyer.plowshare.protocol.FileAccess;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The directories this session currently offers the server, and the ones it used
 * to.
 *
 * <h2>It moves while a run is going, and that is the ordinary case</h2>
 *
 * <p>A human working in one repository switches to another and says so — {@code
 * workspace_set} — while an agent submitted ten minutes ago is still running.
 * {@code FileProvider.roots()} is written for exactly this: roots may shrink or
 * grow between two calls and no caller may cache them. Nothing on the server
 * keeps a copy, so <b>every question about roots is a question to this
 * object</b>, answered from whatever it holds at that moment.
 *
 * <h2>Why the previous roots are kept, and only the previous ones</h2>
 *
 * <p>So that a refusal can say <em>the workspace moved</em> rather than only
 * <em>that is not in the workspace</em>. The two are different instructions: the
 * first tells a model to ask for its roots again, and the second tells it the
 * file is not there — which is what it will conclude, having read that file
 * successfully two turns ago.
 *
 * <p>The distinction has to be earned rather than asserted. Saying "moved" about
 * a path that was never in any workspace this session held is trap 7 — an error
 * message naming a situation that does not hold — so it is said only when the
 * path really was inside a root this session held before its last {@link #set}.
 *
 * <p><b>One step back and no further.</b> A full history would grow for the life
 * of a session, and the second-to-last workspace is not a fact anybody acts on:
 * what a model needs to know is that the ground moved under this run, not the
 * itinerary.
 *
 * <h2>Neither list is canonical, and the canonicalising happens downstream</h2>
 *
 * <p><b>This heading said "Both lists are canonical" and was false the moment the
 * line that made it true came out.</b> {@link #set} used to resolve its roots
 * here; the mutation sweep found that no test could tell the difference, because
 * every consumer hands the list to {@link FileAccess#of}, which resolves both
 * sides itself — so the line went, on this project's rule about lines no mutant
 * can kill, and the heading describing it stayed. <b>That is the seventh restated
 * fact to drift in this slice and the second time a deletion left the sentence
 * that justified it standing.</b> A line removed as unkillable takes a claim with
 * it, and the claim is usually in a heading rather than beside the code.
 *
 * <p>What is true: {@link #roots()} hands back the paths as whoever set them
 * wrote them, and <b>a caller that compares them against anything must resolve
 * them first</b>. {@code FileProvider.roots()} requires canonical roots and
 * {@code ProviderRouter} compares a canonicalised candidate against whatever a
 * provider advertises — a root advertised as a human typed it matches no
 * candidate at all on any host where the tree is reached through a symlink, which
 * on macOS is every tree under {@code /tmp} or {@code /var}. {@link
 * ClientEnforcer} is the one consumer today and satisfies that by building a
 * {@link FileAccess} from this list; {@link FileAccess} is where the argument
 * lives and is the only correct way to do it.
 */
public final class Workspace {

    /**
     * Where this session is pointed, and where it was pointed before.
     *
     * <p>One immutable value behind one {@code volatile} field, rather than two
     * fields or a lock. A reader is a file request being answered on some other
     * thread while {@code workspace_set} runs, and two fields written separately
     * are a window in which the roots are the new ones and the history is the new
     * ones too — so a path under the old root would be refused with "that was
     * never yours", which is the one sentence this class exists to avoid.
     */
    private record Held(List<Path> now, List<Path> before) {}

    private volatile Held held = new Held(List.of(), List.of());

    /**
     * What this session offers right now, <b>as whoever set it wrote it</b>.
     *
     * <p>Empty until a human sets one, which is an ordinary state and not a
     * fault.
     *
     * <p><b>Not canonical.</b> A caller that intends to compare these against a
     * path must build a {@link FileAccess} from them, which resolves both sides;
     * the class javadoc says why that is the only correct way and why the
     * resolution is not done here. Said on this method rather than left to the
     * class, because this one is public and the promise this javadoc used to
     * carry by omission was the false one. <b>{@link ClientEnforcer} is the only
     * caller today</b>; a client-side {@code workspace_set} surface would be a
     * second one, and that is a prediction rather than a description of anything
     * that exists.
     */
    public List<Path> roots() {
        return held.now();
    }

    /** Whether a human has pointed this session at anything yet. */
    public boolean isSet() {
        return !held.now().isEmpty();
    }

    /**
     * Point the session somewhere, keeping where it was.
     *
     * @param roots the directories, as whoever set them wrote them. They are
     *     resolved where they are used rather than here; the body says why.
     *     Empty is legal and means the session offers nothing, which is how a
     *     human takes a workspace back without closing the client
     */
    public void set(List<Path> roots) {
        Objects.requireNonNull(roots, "roots");
        // Kept as they were typed, and canonicalised where they are USED. An
        // earlier version resolved them here as well, and the sweep said that
        // line carried no rule: every consumer passes this list to
        // FileAccess.of, which resolves both sides itself and says at length
        // why — so the mutant replacing this with List.copyOf survived every
        // test, including the one that asserts the roots come back canonical.
        // This project removes lines no mutant can kill rather than keeping
        // them for the shape.
        //
        // The observable rule is unchanged and still held by
        // the_roots_are_advertised_canonical_and_not_as_they_were_typed: what a
        // human typed is what a human sees back, and what the server compares
        // against is real.
        held = new Held(List.copyOf(roots), held.now());
    }

    /**
     * Was this path inside a root this session held before its last {@link #set}?
     *
     * <p>Asked through {@link FileAccess} rather than by comparing prefixes here.
     * The containment rule has one owner for the reason that class's javadoc
     * gives at length, and this is a question about containment even though its
     * answer only ever changes the wording of a refusal — a second, looser
     * comparison living here is how "the workspace moved" would eventually be
     * said about a sibling directory whose name starts the same way.
     */
    public boolean heldPreviously(Path candidate) {
        Held snapshot = held;
        return FileAccess.of(snapshot.before(), List.of()).permits(candidate);
    }
}
