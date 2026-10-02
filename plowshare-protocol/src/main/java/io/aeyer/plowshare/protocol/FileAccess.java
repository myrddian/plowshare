package io.aeyer.plowshare.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The paths one job's file tools may touch: a list of granted roots, a list of
 * exclusions, and the rule that decides between them.
 *
 * <h2>The rule, ported from Excalibur's {@code permits}</h2>
 *
 * <p><b>Longest match wins.</b> A candidate is allowed when some granted root
 * contains it <em>and</em> no exclusion contains it more specifically. That
 * ordering is what lets a sub-directory stay readable while the tree above it is
 * excluded: the deeper grant is the more specific statement about that subtree,
 * so it is the one that applies.
 *
 * <p><b>Ties go to the exclusion.</b> Excluding exactly a granted root means the
 * root is unreachable, which is the only reading that is not a silent no-op —
 * and a silent no-op is the one outcome an exclusion must never have.
 *
 * <p><b>And hidden means hidden.</b> A path component beginning with {@code .}
 * is not reachable unless a root names it — the one rule here that is a
 * predicate rather than a comparison of two lists, because the hidden
 * directories that hold secrets are the ones nobody thought to enumerate.
 * {@link #hiddenBelow} owns it, says why it is not an exclusion, and states
 * what it costs.
 *
 * <h2>Both sides are canonicalised here, and that is not tidiness</h2>
 *
 * <p>{@code ProjectStore} stores a workspace and its exclusions absolute and
 * normalised but never real-pathed, on purpose ({@code ProjectStore.absolute}
 * says why), so <b>neither</b> side of a comparison arrives canonical. Two
 * measured consequences of canonicalising only one of them:
 *
 * <ul>
 *   <li>{@code /tmp} is a symlink to {@code /private/tmp} on this host and
 *       {@code /var} to {@code /private/var}. A configuration file living under
 *       either — where a test fixture or a container-ish deployment naturally
 *       puts it — yields a mandatory exclusion that could never match a resolved
 *       candidate. An exclusion that excludes nothing and says nothing is the
 *       worst failure this class can have;
 *   <li>an operator who symlinks a checkout into place has a workspace stored as
 *       the link, and every candidate resolved through it would sit outside it.
 * </ul>
 *
 * <p>So {@link #of} resolves the roots and the exclusions once, and {@link
 * #permits} resolves the candidate on every call. Excalibur's {@code permits}
 * takes an already-resolved path and leaves that step to its callers; here it is
 * inside, because a caller that forgets it is one symlink away from a job
 * writing outside its workspace. <b>There are three, measured rather than
 * predicted:</b> {@code LocalProvider}, {@code ClientEnforcer} and {@code
 * Workspace}, with {@code ProviderRouter} a fourth caller of {@link #canonical}
 * alone. This said "there will be four callers" while it was a forecast; it is
 * now a fact and is written as one.
 *
 * <h2>What this class is not</h2>
 *
 * <p>It never opens a file, and it is not the only check. The provider that
 * actually opens the file re-checks, and the client module checks again in the
 * process that owns the disk — same argument as Excalibur's grep child process
 * re-checking every path it is handed.
 *
 * <h2>Why this lives in {@code plowshare-protocol} and not beside the providers</h2>
 *
 * <p><b>Because two processes have to mean the same thing by "inside".</b> This
 * class shipped in {@code plowshare-server/files/} while there was one process
 * that enforced anything. Task 7 makes the client module an enforcement point of
 * its own — {@code ClientEnforcer} answers a server's file request against the
 * workspace that session currently holds — and {@code plowshare-client} must not
 * depend on {@code plowshare-server}. The two available shapes were one class
 * both modules see, or two implementations of {@link #permits} and {@link
 * #canonical}, and <b>that is the pair this project can least afford to let
 * drift</b>: a client whose {@code canonical} follows a different number of links
 * from the server's is a client that permits what the server refused, with
 * nothing anywhere to say so.
 *
 * <p>{@link #canonical}'s own javadoc has listed "the client module's own check"
 * among its callers since it was written. This move is what makes that sentence
 * true rather than aspirational.
 *
 * <p><b>The independence the spec asks of the client is not a second
 * implementation.</b> It is a second <em>execution</em>, in the process that owns
 * the disk, against state only that process has — the client's live workspace,
 * which the server's copy can be a round trip out of date about. Excalibur's
 * {@code file_grep} child process re-runs the same containment code for the same
 * reason; what makes it a second check is where it runs and what it runs
 * against, not that somebody wrote it twice.
 *
 * <p>This module's build file says it carries Jackson annotations and nothing
 * else, on the argument that anything reachable from here is reachable from a
 * stdio client process that holds no durable state. That argument is about a
 * capability the client should not have — a JDBC driver — and the client is now
 * exactly the process that must be able to answer a question about its own disk.
 * {@link Home} is the precedent for a shared <em>rule</em> rather than a shared
 * record: it refuses a blank tier identically in both processes because there is
 * one copy of that refusal.
 *
 * <p>The roots and exclusions are resolved when this object is built, so it is a
 * snapshot: a root that is replaced by a symlink afterwards is still compared as
 * the directory it was. That is the intended lifetime — one of these is built
 * per job — and it is why a root that <em>vanishes</em> is a provider's problem
 * to report rather than something this class can notice.
 */
public final class FileAccess {

    /**
     * How many symlinks {@link #canonical} will follow by hand before it gives
     * up and answers with the path as far as it got.
     *
     * <p><b>The value is load-bearing, and an earlier version of this javadoc
     * said only the finiteness was.</b> The invariant is {@code MAX_LINK_HOPS >=
     * the longest chain the kernel itself will follow}. Below that is a band
     * that fails open: a five-link dangling chain out of the workspace, with the
     * budget at three, canonicalises to a link's own location inside the
     * workspace and the write lands outside — measured, with the suite green.
     * Above the kernel's limit, a chain we stop expanding is one no {@code open}
     * can follow either, so permitting it costs nothing.
     *
     * <p><b>Measured on this host: 16 links resolve and 17 raise {@code
     * FileSystemException}.</b> 40 is above that with margin for a platform
     * whose {@code SYMLOOP_MAX} is higher — POSIX leaves it to the
     * implementation, so a host found above 40 moves this constant. Held from
     * below by {@code a_dangling_chain_the_kernel_would_follow_is_refused_by_where_it_ends},
     * which fails at a budget of three, and from the harmless side by {@code
     * a_chain_past_the_budget_is_left_where_it_was_typed}.
     *
     * <p>Finiteness is a second, separate need: two links pointing at each other
     * make the hand expansion yield each other for ever, and a wedged file tool
     * is this project's worst failure mode. {@code
     * a_link_the_platform_will_not_resolve_terminates_and_stays_put} is the
     * ten-second bound on that.
     */
    private static final int MAX_LINK_HOPS = 40;

    private final List<Path> granted;
    private final List<Path> excluded;

    private FileAccess(List<Path> granted, List<Path> excluded) {
        this.granted = granted;
        this.excluded = excluded;
    }

    /**
     * The ordinary way to build one: the workspaces a caller pointed the server
     * at, and what nothing may reach.
     *
     * <p>Every path is canonicalised here, once, so that later comparisons are
     * canonical against canonical — the class javadoc says what happens when
     * only one side is.
     *
     * <h2>A workspace any exclusion covers is dropped, and that is this method</h2>
     *
     * <p>Longest match cuts both ways. A root <em>below</em> an exclusion beats
     * it — which is what makes "this tree, except that subtree, except this
     * directory inside it" expressible at all — and that is an escalation when
     * the root came from outside the server. A {@code projects} row whose
     * workspace is a subdirectory of the agents directory would otherwise grant
     * exactly what the mandatory exclusion exists to forbid, because the
     * workspace is the more specific statement about that subtree and wins.
     *
     * <p>Ported from Excalibur's {@code FileAccess.resolve}
     * ({@code scopes.py:300-317}) — including where it lives, and for the reason
     * it gives: <i>"Enforced here because there are two ways a workspace
     * arrives, {@code workspace_set} at runtime and {@code file_roots} at
     * construction, and a guard on one of them is not a guard."</i> Plowshare
     * has three ways — {@code LocalProvider} on the server's disk, {@code
     * ClientEnforcer} on the client's, and {@code Workspace} when a human moves
     * one — and this method is the one thing all of them pass through.
     *
     * <p><b>This drop is also why "hidden means hidden" is not an exclusion.</b>
     * A {@code .}-shaped exclusion would cover an explicitly added {@code
     * ~/proj/.github} root and this loop would discard it, so the escape hatch
     * would be destroyed by the mechanism meant to honour it — silently, with
     * {@link #roots()} reporting an empty list and nothing anywhere saying which
     * exclusion did it. {@link #hiddenBelow} is where that rule lives instead.
     *
     * <p><b>This named {@code RemoteProvider} until it existed, and then went on
     * naming it.</b> That class calls nothing here: it has no disk under it, so
     * it does no containment at all, and its own javadoc now says so in its first
     * paragraph. A prediction written before the class existed survived the class
     * being written and contradicting it — the eighth instance of this drift shape
     * in the slice, and the second time a correction round fixed one end of a
     * contradiction and left the owner alone.
     *
     * <h2>Why a workspace cannot be passed as a server-owned root by accident</h2>
     *
     * <p>Because there is nowhere to pass it. An earlier version of this took
     * {@code (serverOwned, workspaces, excluded)} — two adjacent lists of the
     * same type, where transposing them reproduced the exact escalation the drop
     * exists to prevent, silently, with nothing to catch it: not the compiler,
     * not a runtime check, not a test. That is a positional guard wearing a
     * structural one's clothes. {@link #withServerOwned} takes the other kind of
     * root in a call of its own, so no signature here has a pair to swap.
     *
     * @param workspaces directories a caller pointed the server at — a project's
     *     workspace, or a client session's roots. Empty is an ordinary answer: a
     *     job in the global tier has no workspace, and so reaches no local file
     * @param excluded paths that stay unreachable even when a granted root
     *     contains them. <b>This must come from {@code
     *     ProjectStore.effectiveExclusions}</b>, which carries the two no
     *     project may override, and never from {@code
     *     ProjectRecord.exclusions()}, which is the row alone — a check built
     *     from the row is a check a {@code projects} row can switch off
     */
    public static FileAccess of(List<Path> workspaces, List<Path> excluded) {
        List<Path> exclusions = excluded.stream().map(FileAccess::canonical).toList();
        List<Path> roots = new ArrayList<>(workspaces.size());
        for (Path workspace : workspaces) {
            Path resolved = canonical(workspace);
            // Any covering exclusion, not just an exactly equal one: this is the
            // half of the rule `roots()` cannot express, because by the time a
            // root is in the list there is nothing left to say where it came
            // from.
            if (covering(resolved, exclusions) == null) {
                roots.add(resolved);
            }
        }
        return new FileAccess(roots, exclusions);
    }

    /**
     * The same containment, plus roots the <em>server</em> named — which keep
     * the longest-match reading a workspace is denied.
     *
     * <p>The asymmetry is Excalibur's and it is the reason this is a second
     * call: a deeper grant inside a directory the server owns is the deliberate
     * statement about that subtree, and a caller's path in the same position is
     * an escalation. {@code scratch} and {@code archive} are that method's
     * suppliers there ({@code scopes.py:311-321}); they pass through untouched
     * while the workspace is dropped.
     *
     * <p><b>Nothing in Plowshare supplies one yet</b>, and this is deliberately
     * the awkward call rather than a parameter on {@link #of}: a root that
     * survives an exclusion above it is the rarer and sharper thing, so it
     * should have to be asked for by name. It is not dead weight — {@code
     * permits} keeps longest match, and without a way to express a server-owned
     * root the rule that {@code the_more_specific_statement_about_a_subtree_wins}
     * states could not be exercised at all. A per-job scratch directory, if one
     * ever lands, is the supplier this is waiting for.
     *
     * <p>Prepended rather than appended, matching {@code read_roots}: the
     * server's own directories are listed first, because they are the ones that
     * always exist.
     *
     * <p><b>It does not lift the hidden rule, and that is a decision rather than
     * an omission.</b> A server-owned root consents to a dot in its own path the
     * way any root does, and to nothing hidden below it; the asymmetry this call
     * carries is about surviving an <em>exclusion</em>, and stretching it to
     * cover {@link #hiddenBelow} as well would make this the one call that
     * un-hides a tree. {@link #hiddenBelow} argues both directions, since
     * nothing supplies a server-owned root yet and this is therefore a choice
     * made for a caller that does not exist.
     */
    public FileAccess withServerOwned(List<Path> serverOwned) {
        List<Path> roots = new ArrayList<>(serverOwned.size() + granted.size());
        for (Path root : serverOwned) {
            roots.add(canonical(root));
        }
        roots.addAll(granted);
        return new FileAccess(roots, excluded);
    }

    /**
     * The granted roots, canonical, minus the ones that permit nothing.
     *
     * <p>A root that an exclusion covers at least as specifically is dropped
     * rather than listed and then refused path by path: a glob would otherwise
     * walk a whole tree it can return nothing from, and {@code file_roots} would
     * tell an agent about a directory it cannot read. A root that merely sits
     * <em>inside</em> an excluded tree is kept, because longest match leaves it
     * reachable — that is the same rule, not an exception to it.
     *
     * <p>Filtering by {@link #permits} rather than by a second comparison of its
     * own: a root always covers itself and nothing granted can cover it more
     * specifically, so {@code permits(root)} reduces to exactly Excalibur's
     * condition on {@code read_roots} ({@code scopes.py:251-256}), with one
     * statement of the rule instead of two.
     *
     * <p><b>A hidden root is still advertised</b>, and it has to be. {@code
     * permits} finds such a root as its own deepest cover, so {@link
     * #hiddenBelow} has no component below it to look at and answers false — a
     * root somebody named on purpose is the escape hatch, and a hatch nobody is
     * told about is not one.
     *
     * <p>This is the weaker of the two filters and it applies to every root
     * alike, exactly as {@code read_roots} does. The stronger one — any covering
     * exclusion drops a <em>workspace</em> — has already run, in {@link #of},
     * because only that method still knows which roots came from a caller. A
     * root added by {@link #withServerOwned} never met it, which is the whole
     * difference between the two.
     */
    public List<Path> roots() {
        return granted.stream().filter(this::permits).toList();
    }

    /**
     * May this path be touched?
     *
     * <p>The candidate is canonicalised first, so the answer is about the file
     * the platform would open and not about the name that was typed.
     *
     * <p>Nothing here is about read versus write. A grant's {@code Mode} is
     * settled before this object is built, and a provider holds the one boolean
     * it turned into; this is the question of <em>where</em>, asked identically
     * by every tool.
     *
     * <p><b>The roots consulted here are the unfiltered ones, where {@code
     * scopes.py:271} consults {@code read_roots}.</b> Not an oversight and not
     * free: {@link #roots()} filters by {@code permits}, so asking it here would
     * recurse. It is also unobservable, which is why it is safe — a root the
     * filter drops is one an exclusion covers at least as specifically, and that
     * same exclusion covers every candidate the root covers, at the same depth
     * or deeper. Such a root can never be the winner of a comparison it is left
     * in, so leaving it in changes no answer.
     *
     * <p><b>Excalibur writes this as two statements and it is one here.</b> Its
     * {@code permits} returns early when no root covers the candidate, and that
     * branch cannot change an answer: {@link #depth} reports 0 for "covered by
     * nothing", 0 is not greater than any depth, and every real path has a depth
     * of at least 1. A mutant that stopped the early return from ever firing
     * survived the whole file, which is this project's definition of a line
     * carrying no rule. The reasoning it carried is the sentence in {@link
     * #depth} instead, where the 0 is: it is safe on both sides, because an
     * uncovered candidate is in no root and an uncovered candidate is under no
     * exclusion.
     *
     * <p>The {@code &&} is what makes {@link #hiddenBelow}'s root argument
     * non-null without a check: the left side is true only when the covering
     * root has a depth of at least 1, and {@link #depth} answers 1 or more for
     * exactly the paths that are not null.
     */
    public boolean permits(Path candidate) {
        Path resolved = canonical(candidate);
        Path root = covering(resolved, granted);
        return depth(root) > depth(covering(resolved, excluded)) && !hiddenBelow(root, resolved);
    }

    /**
     * Does any component of {@code candidate} <em>below</em> {@code root} begin
     * with a dot?
     *
     * <h2>Hidden means hidden, and it is a predicate because it cannot be a list</h2>
     *
     * <p><b>A path component beginning with {@code .} is not reachable unless a
     * root names it.</b> Files and directories alike, and with no allowlist of
     * hidden-but-fine names, because the hidden directories that matter are the
     * ones nobody thought to name: {@code .env}, {@code .ssh}, {@code .aws},
     * {@code .npmrc}, {@code .netrc}, {@code .docker/config.json} — and {@code
     * ~/.config/plowshare/console-token}, which is the finding this rule came
     * from. That file is a permanent access grant for every gated route on this
     * server ({@code TokenStore.acceptOperator} files it with no expiry and
     * {@code AuthFilter} asks only {@code validAccess}), and a workspace over
     * {@code $HOME} handed it to any agent that could read a file. The
     * enumeration this replaces is {@code ProjectStore.mandatoryExclusions},
     * which named four paths and none of them that one.
     *
     * <p><b>A dot inside a root is consent by construction</b>, which is the
     * whole of the escape hatch: a root of {@code ~/proj/.github} makes {@code
     * ~/proj/.github/ci.yml} reachable, because the deepest covering root is
     * {@code .github} itself and nothing below it is hidden. Somebody named that
     * directory on purpose. It also settles symlinks for free — {@link
     * #canonical} runs on both sides before this does, so a workspace whose real
     * path runs through a hidden directory does not refuse itself, and a link
     * out of a root into one is refused by where it points rather than by how it
     * was spelled.
     *
     * <p><b>Why it is here and not one more entry in {@code mandatoryExclusions}.</b>
     * Two reasons, both measured rather than argued:
     *
     * <ul>
     *   <li>an exclusion of the dot-shape would have to be a list, and no list
     *       of hidden directories is ever complete;
     *   <li>{@link #of} drops any workspace root that an exclusion <em>covers</em>,
     *       not merely one it equals. A dot-hiding exclusion plus an explicitly
     *       added {@code ~/proj/.github} root therefore yields no root at all —
     *       the escape hatch discarded by the mechanism meant to honour it. That
     *       is the one property this rule cannot lose, so it is expressed where
     *       nothing drops a root: here.
     * </ul>
     *
     * <p>And because {@code permits} is the chokepoint both enforcement points
     * share. {@code ClientEnforcer} passes {@code List.of()} for exclusions and
     * always will — the client's workspace is the human's own disk and the
     * server's mandatory exclusions are not facts about it — so an
     * exclusion-based rule would reach the machine where the CLI keeps its own
     * copy of that token not at all. This reaches {@code LocalProvider} (read,
     * write, stat), {@code FileSearch} (glob and grep, per candidate during the
     * walk), {@code ClientEnforcer} and {@code Workspace} with no coordination
     * between the two processes.
     *
     * <h2>Provenance does not enter, and that is the decision about
     * {@link #withServerOwned}</h2>
     *
     * <p>The root this measures from is the deepest covering root of any kind. A
     * server-owned root consents to a dot in its own path exactly as a
     * workspace does — a per-job scratch directory at {@code ~/.plowshare/scratch}
     * is reachable — and <b>no further</b>: a hidden component below it is
     * refused like any other. Nothing supplies a server-owned root today, so
     * this is a choice made for a future caller rather than a behaviour
     * observed, and the reason for it is that the alternative is worse in both
     * directions. Exempting server-owned roots entirely would make {@code
     * withServerOwned} a way to un-hide a whole tree, which is a second rule to
     * remember on the call whose javadoc already says a caller's path in that
     * position is an escalation; refusing them a dot in their own path would
     * make a hidden scratch directory grant nothing while reporting itself as a
     * root. Keying on depth alone means there is one rule and it reads the same
     * from either list. {@code a_server_owned_root_names_its_own_dot_and_no_more}
     * is what holds it.
     *
     * <h2>What it costs, and the two things it does not</h2>
     *
     * <p><b>{@code .git} and {@code .github}, and the cost is real.</b> Agents
     * legitimately read CI configuration, and {@code file_grep} over a
     * repository stops covering {@code .git}. Both are recoverable by adding
     * them as explicit roots, so the friction lands on the common case rather
     * than the rare one — accepted deliberately, because the rule's value is
     * that it has no exceptions to remember and an allowlist of "hidden but
     * fine" directories would be the four-path fence again in a new costume.
     *
     * <p><b>It does not touch {@code file_roots}.</b> Roots are named by an
     * operator or by a human at a CLI, and a dot in a root is consent, so {@link
     * #roots()} still advertises a hidden root: {@code permits(root)} finds
     * itself as the deepest cover and has no component below it to look at.
     *
     * <p><b>And no component can be {@code "."} or {@code ".."}.</b> Those would
     * be false refusals rather than a security win, and they cannot arrive:
     * {@link #canonical} ends in {@code normalize()} on both of its exits, and
     * measured on this host {@code /a/./b} normalises to {@code /a/b}, {@code
     * /a/b/../..} to {@code /}, and even a leading {@code /..} to {@code /} —
     * an absolute path has nowhere for a {@code ..} to survive to. {@code
     * a_path_spelled_with_dot_and_dot_dot_is_not_read_as_hidden} is the
     * instrument, and it exists because {@code startsWith(".")} is true of both
     * strings and nothing else in this class would notice.
     *
     * @param root the deepest granted root covering {@code candidate}, never
     *     null — see {@link #permits} for what guarantees that
     * @param candidate the canonical candidate, which {@code root} is a prefix
     *     of by path element, so the components below it are exactly the names
     *     from {@code root.getNameCount()} on
     */
    private static boolean hiddenBelow(Path root, Path candidate) {
        // From the root's name count and not from 1: this index IS the
        // deepest-covering-root qualifier, and starting at 0 instead would refuse
        // the escape hatch — an explicitly added `.github` root would be refused
        // by its own name. `a_hidden_root_that_was_named_makes_its_contents_reachable`
        // is what dies when this loop starts anywhere else.
        for (int name = root.getNameCount(); name < candidate.getNameCount(); name++) {
            if (candidate.getName(name).toString().startsWith(".")) {
                return true;
            }
        }
        // Reached when the candidate IS the root, which must be permitted: a
        // hidden root has to permit itself or `roots()` -- which filters by
        // `permits` -- would drop it and the escape hatch would be a root nobody
        // is told about.
        return false;
    }

    /**
     * Absolute, with every symlink the platform will resolve resolved, for a
     * path that may not exist yet.
     *
     * <h2>Why not simply {@code toRealPath()}, and why not simply {@code
     * normalize()}</h2>
     *
     * <p>Measured on this host (macOS 26.6.2, JDK 21), and the reason this
     * method is not two lines:
     *
     * <ul>
     *   <li>{@code toRealPath()} throws {@code NoSuchFileException} for anything
     *       that does not exist, including a file below a directory that does.
     *       A write tool has to name a file before it exists, so real-pathing
     *       alone cannot answer for {@code file_edit} at all;
     *   <li>falling back to {@code toAbsolutePath().normalize()} for the whole
     *       path when that happens is a containment hole in exactly the case
     *       that motivates the fallback. With {@code ws/escape} a symlink to a
     *       directory outside the workspace, {@code ws/escape/notyet} normalises
     *       to {@code ws/escape/notyet} — inside the root — while the byte lands
     *       outside it. Measured against the same call on {@code
     *       ws/escape/secret}, which exists and answers {@code outside/secret};
     *   <li>{@code normalize()} also settles {@code ..} textually, which
     *       disagrees with the filesystem across a symlink: {@code ws/escape/..}
     *       is {@code ws} by text and the parent of the link's target in fact.
     * </ul>
     *
     * <p>So the deepest ancestor that <em>does</em> resolve is real-pathed and
     * the names below it are put back — which is what Python's {@code
     * Path.resolve()} does, and therefore what Excalibur's containment has
     * always compared. A trailing {@code ..} below a directory that does not
     * exist is then settled by {@code normalize}, the one place where text is
     * all there is to go on.
     *
     * <p><b>A symlink whose target is absent is followed by hand.</b> {@code
     * toRealPath} refuses the whole path in that case, so the walk above would
     * answer with the link's own location — and a write to a dangling link
     * creates its target, measured: writing to {@code ws/gate} created {@code
     * outside/gone}. Following it is also what {@code Path.resolve()} does, so
     * this is the port rather than an addition to it. {@link #MAX_LINK_HOPS} is
     * what keeps that expansion from looping.
     *
     * <p>Failure returns the path absolute and normalised rather than raising,
     * which is Excalibur's choice and its wording: it keeps a path the platform
     * dislikes out of the <em>comparison</em> rather than out of the server.
     *
     * <p><b>It does not inherit Excalibur's safety argument, and an earlier
     * version of this javadoc claimed it did.</b> "An unresolved candidate
     * matches no root and is refused" is true where {@code canonical} hands back
     * the path exactly as given, often relative; it is false here, because this
     * one begins with {@code toAbsolutePath()} and a path that fails to resolve
     * <em>inside</em> a granted root stays inside it. Measured: with a mutual
     * pair {@code ws/here ↔ ws/there} and {@code ws} granted, {@code
     * permits(ws/here)} is true, and {@code
     * a_link_the_platform_will_not_resolve_terminates_and_stays_put} asserts
     * exactly that.
     *
     * <p>The argument that does hold is {@link #MAX_LINK_HOPS}'s: a path this
     * method cannot resolve is one no {@code open} can follow either, so
     * permitting it costs nothing — the tool that acts on it fails where the
     * kernel fails. {@code a_chain_past_the_budget_is_left_where_it_was_typed}
     * measures the pair. An exclusion is still never dropped for being
     * unresolvable, only kept as it was written, because the same reasoning does
     * not run in that direction: an exclusion that goes missing from the
     * comparison is a fence that stops existing.
     *
     * <h2>The rule every caller needs: canonicalise once, then ask about the
     * answer</h2>
     *
     * <p><b>{@link #permits}(p) and {@code permits(canonical(p))} can give
     * different answers</b>, so a caller must not check one path and act on
     * another. Canonicalise the candidate once, hand <em>that</em> to {@code
     * permits}, and open <em>that</em>. Asking about the path as it was typed
     * and then opening its canonical form is a check about a different file
     * from the one that gets touched.
     *
     * <p>The reason is that this method is <b>not idempotent</b>. Where it
     * succeeds it is — a real path real-paths to itself — but where it gives up
     * it answers with the link it stopped at, and running it again resolves the
     * next stretch of the chain. Measured, with a 45-link chain inside a granted
     * root whose last link points outside it and {@link #MAX_LINK_HOPS} at 40:
     * {@code canonical} answers link 41, which is inside the root, and {@code
     * canonical} of <em>that</em> answers the target outside it. So {@code
     * permits} says true of the path as typed and false of its own canonical
     * form. {@code
     * canonical_is_not_idempotent_past_the_budget_so_permits_can_disagree_with_itself}
     * is what holds that, and it is here rather than in a caller because every
     * caller inherits it. Measured, the callers of this method are {@code
     * LocalProvider}, {@code ClientEnforcer} and {@code ProviderRouter} — not
     * {@code RemoteProvider}, which has no disk to resolve against, and not the
     * file tools, which are handed a {@code Path} and never resolve one.
     *
     * <p>Neither answer is unsafe on its own — the chain is one no {@code open}
     * can follow, as {@link #MAX_LINK_HOPS} says. What is unsafe is mixing them.
     *
     * <p>Public because more than one thing has to mean the same thing by it.
     * Two copies of this drifting apart is how {@code /tmp/proj} and {@code
     * /private/tmp/proj} become two different projects.
     */
    public static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath();
        // `below` is filled head-first and read head-to-tail — ArrayDeque's
        // documented iteration order, and the reason the three-deep fixture in
        // canonical_resolves_the_deepest_real_ancestor_and_keeps_the_names_below_it
        // is three deep: a reversed order answers `c/b/a` and a one-deep fixture
        // could not tell.
        Deque<Path> below = new ArrayDeque<>();
        // Named for what it is rather than where it started: it is an ancestor
        // of `absolute` until a link is expanded below, after which it is
        // somewhere else entirely and the names in `below` hang off that.
        Path resolving = absolute;
        int hops = 0;
        while (true) {
            try {
                Path resolved = resolving.toRealPath();
                for (Path name : below) {
                    resolved = resolved.resolve(name);
                }
                return resolved.normalize();
            } catch (IOException unresolvable) {
                Path target = linkTarget(resolving);
                if (target != null && hops < MAX_LINK_HOPS) {
                    hops++;
                    resolving = target;
                    continue;
                }
                Path parent = resolving.getParent();
                if (parent == null) {
                    // The filesystem root itself would not resolve. Not
                    // reachable on this host — `/` real-paths to `/`, measured —
                    // and it is here because the alternative is a
                    // NullPointerException raised inside a catch block, which
                    // would take a job down over a path it could simply have
                    // refused.
                    break;
                }
                below.addFirst(resolving.getFileName());
                resolving = parent;
            }
        }
        return absolute.normalize();
    }

    /**
     * Where this path points if it is a symlink the platform would not follow,
     * or null.
     *
     * <p>A relative target is resolved against the link's own directory, which
     * is what the kernel does with it; an absolute one replaces the path
     * outright, which is what {@code resolveSibling} does with an absolute
     * argument.
     */
    private static Path linkTarget(Path path) {
        try {
            return Files.isSymbolicLink(path)
                    ? path.resolveSibling(Files.readSymbolicLink(path))
                    : null;
        } catch (IOException unreadable) {
            // NOT a guard, and no test reaches it: `readSymbolicLink` declares
            // IOException where `isSymbolicLink` does not, so the catch is the
            // compiler's requirement and the only way in is a race between the
            // two calls. Recorded because a mutant replacing this body with a
            // throw survives all 30 tests, and an unexplained survivor reads as
            // a missing test rather than as an unreachable line.
            //
            // Null means "treat it as not a link", which sends the walk up one
            // level — the same answer as for an ordinary missing file, and the
            // fallback above says why that is not a hole.
            return null;
        }
    }

    /** The most specific of {@code paths} that contains {@code candidate}, or null. */
    private static Path covering(Path candidate, List<Path> paths) {
        Path best = null;
        for (Path path : paths) {
            // startsWith and not a text comparison: it is by path element, so
            // `/w/wsx/secret` is not inside `/w/ws`, and it is reflexive, so a
            // path contains itself without a second equality test.
            if (candidate.startsWith(path) && depth(path) > depth(best)) {
                best = path;
            }
        }
        return best;
    }

    /**
     * How specific a covering path is. Zero means "did not cover it at all",
     * which is safe on both sides: an uncovered candidate is in no root, and an
     * uncovered candidate is under no exclusion.
     *
     * <p>The {@code + 1} counts the filesystem root as an element, which is what
     * Python's {@code len(path.parts)} does and what makes zero a number no real
     * path can produce. Without it {@code Path.of("/").getNameCount()} is 0 —
     * measured — so granting {@code /} would read as granting nothing, and
     * {@code ProjectStore.define} accepts {@code /} because it is a directory
     * that exists.
     */
    private static int depth(Path covering) {
        return covering == null ? 0 : covering.getNameCount() + 1;
    }
}
