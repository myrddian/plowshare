package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The containment core, and the cases that pin it.
 *
 * <h2>Why the fixture builds its own symlink, and what that is actually worth</h2>
 *
 * <p>{@link #linked} is a second spelling of {@link #real}, and the tests that
 * matter give a root one spelling and a candidate the other. That is the
 * arrangement task 1 leaves behind: it stores a workspace absolute and
 * normalised but never real-pathed, so a stored root and a resolved candidate
 * arrive here spelled differently.
 *
 * <p><b>On this host it is not what kills a one-sided implementation, and an
 * earlier version of this paragraph said it was.</b> {@code @TempDir} hands out
 * a path under {@code /var}, which is itself a symlink to {@code /private/var},
 * so {@code permits} resolves the candidate to {@code /private/var/…} and an
 * implementation that canonicalised only one side is caught by ordinary tests.
 * Measured, with {@code linked} assigned {@code real} so the second spelling is
 * gone: dropping the canonicalisation of the server-owned roots fails 4 tests,
 * of the workspaces 7, of the exclusions 7.
 *
 * <p><b>It is worth exactly one thing, and that thing is measured too:</b> the
 * host where the temp directory is <em>not</em> reached through a symlink — a
 * Linux CI box. With the fixture rebuilt under {@code tmp.toRealPath()} and the
 * second spelling removed, all three of those mutants survive with zero
 * failures. {@code linked} is what keeps them dead there — and only where it is
 * actually spelled that way: an earlier version of this paragraph said it
 * rescued all three, while no {@code withServerOwned} argument in the file used
 * it, so the server-owned canonicalisation was pinned by nothing but this host's
 * {@code /var}. Measured then: that one mutant survived the unsymlinked fixture
 * with zero failures while the other two failed 2 and 1. {@code
 * a_server_owned_root_survives_an_exclusion_that_would_drop_a_workspace} spells
 * its root through {@code linked} for that reason.
 *
 * <h2>What is measured here rather than assumed</h2>
 *
 * <ul>
 *   <li>{@code Path.startsWith} is component-wise, not textual — {@code
 *       a_sibling_whose_name_begins_with_a_roots_name_is_not_inside_it};
 *   <li>{@code Path.toRealPath} throws {@code NoSuchFileException} for a path
 *       that does not exist yet, including one below a directory that does, and
 *       follows a symlink for one that does — the pair {@code
 *       a_write_target_that_does_not_exist_yet_is_refused_by_where_its_link_points}
 *       and {@code canonical_resolves_the_deepest_real_ancestor_and_keeps_the_names_below_it};
 *   <li>a write through a symlink whose target is absent creates the target,
 *       outside the root — {@code a_dangling_symlink_is_refused_by_where_it_points};
 *   <li>{@code Path.normalize} settles {@code ..} textually and disagrees with
 *       the filesystem across a symlink — {@code
 *       dot_dot_is_resolved_where_the_filesystem_says_rather_than_textually};
 *   <li>a symlink pair pointing at each other is an {@code IOException} from
 *       {@code toRealPath} and not a hang, but expanding it by hand is one —
 *       {@code a_link_the_platform_will_not_resolve_terminates_and_stays_put}.
 * </ul>
 */
class FileAccessTest {

    /**
     * The longest symlink chain this kernel resolves rather than refusing with
     * ELOOP. Measured on this host by walking chain lengths until {@code
     * toRealPath} changed its answer: 16 resolves, 17 raises {@code
     * FileSystemException}. It is a fixture size and not a claim about the
     * platform — a host that follows more would make this test weaker, not
     * wrong, and {@code MAX_LINK_HOPS} says what to do about that.
     */
    private static final int KERNEL_FOLLOWS = 16;

    @TempDir
    Path tmp;

    /** The tree everything really lives in. */
    Path real;

    /** A second, equally valid spelling of {@link #real}. */
    Path linked;

    /** The granted root, spelled the real way: {@code real/ws}. */
    Path ws;

    /** A directory beside the root that nothing grants. */
    Path outside;

    @BeforeEach
    void layOutTwoSpellingsOfOneTree() throws IOException {
        real = Files.createDirectory(tmp.resolve("real"));
        linked = Files.createSymbolicLink(tmp.resolve("linked"), real);
        ws = Files.createDirectory(real.resolve("ws"));
        outside = Files.createDirectory(real.resolve("outside"));
    }

    // ------------------------------------------------------------------- roots

    @Test
    void a_path_inside_a_granted_root_is_permitted() {
        FileAccess access = FileAccess.of(List.of(ws), List.of());
        assertTrue(access.permits(ws.resolve("notes.md")));
        assertTrue(access.permits(ws), "a root is inside itself");
    }

    @Test
    void a_path_outside_every_granted_root_is_refused() {
        FileAccess access = FileAccess.of(List.of(ws), List.of());
        assertFalse(access.permits(outside.resolve("secret")));
    }

    @Test
    void with_no_granted_roots_nothing_is_permitted() {
        assertFalse(FileAccess.of(List.of(), List.of()).permits(ws.resolve("notes.md")),
                "an uncovered candidate is in no root, so it is refused before any"
                        + " exclusion is consulted");
    }

    @Test
    void a_sibling_whose_name_begins_with_a_roots_name_is_not_inside_it() throws IOException {
        // `real/wsx` shares every character of `real/ws` and is a different
        // directory. Containment that compared the two as strings would hand a
        // job the neighbouring checkout.
        Path sibling = Files.createDirectory(real.resolve("wsx"));
        Files.writeString(sibling.resolve("secret"), "s");
        assertFalse(FileAccess.of(List.of(ws), List.of()).permits(sibling.resolve("secret")),
                "containment is by path element, not by shared prefix of the text");
    }

    @Test
    void the_filesystem_root_can_be_granted() {
        // The one case where Java and Python disagree about how specific a path
        // is: `Path.of("/").getNameCount()` is 0, which is the same number
        // `covering` returns for "no root covered this at all". A depth that
        // did not count the root element would make a workspace of `/` cover
        // nothing — and `ProjectStore.define` accepts `/`, since it is a
        // directory that exists.
        assertTrue(FileAccess.of(List.of(Path.of("/")), List.of()).permits(ws.resolve("notes.md")),
                "granting / must not read as granting nothing");
    }

    // ----------------------------------------------------------- longest match

    @Test
    void the_more_specific_statement_about_a_subtree_wins() {
        // Excalibur's own example: `archive` stays readable at data/archive
        // while data/ as a whole is excluded, because the archive is the more
        // specific statement about that subtree.
        FileAccess access = FileAccess.of(List.of(), List.of(ws.resolve("data")))
                .withServerOwned(List.of(ws, ws.resolve("data/archive")));
        assertTrue(access.permits(ws.resolve("data/archive/m.md")));
        assertFalse(access.permits(ws.resolve("data/secret.db")));
    }

    @Test
    void the_order_the_roots_are_given_in_does_not_decide() {
        // The same question with the list reversed. Together the two pin
        // "longest wins" against both of its cheap approximations: taking the
        // first covering root, and taking the last one.
        FileAccess access = FileAccess.of(List.of(), List.of(ws.resolve("data")))
                .withServerOwned(List.of(ws.resolve("data/archive"), ws));
        assertTrue(access.permits(ws.resolve("data/archive/m.md")));
        assertFalse(access.permits(ws.resolve("data/secret.db")));
    }

    @Test
    void excluding_exactly_a_granted_root_makes_it_unreachable() {
        // Ties go to the exclusion. The other reading is a silent no-op, which
        // is the one outcome an exclusion must never have.
        FileAccess access = FileAccess.of(List.of(), List.of(ws)).withServerOwned(List.of(ws));
        assertFalse(access.permits(ws.resolve("anything")));
        assertFalse(access.permits(ws), "including the root itself");
    }

    @Test
    void an_exclusion_below_a_root_fences_off_only_that_subtree() {
        FileAccess access = FileAccess.of(List.of(ws), List.of(ws.resolve("keys")));
        assertFalse(access.permits(ws.resolve("keys/api.txt")));
        assertFalse(access.permits(ws.resolve("keys")));
        assertTrue(access.permits(ws.resolve("notes.md")),
                "the rest of the root is untouched");
    }

    @Test
    void a_workspace_set_inside_the_agents_directory_grants_nothing() throws IOException {
        // The escalation longest match creates when the root came from outside
        // the server. `agents` is excluded for every project because a
        // definition declares which tools an agent holds; a projects row whose
        // workspace is a directory inside it is the more specific statement
        // about that subtree, so `permits` alone would hand the agent write
        // access to every definition — with a delay fuse, effective at the next
        // boot.
        Path agents = Files.createDirectories(real.resolve("srv/agents"));
        Path mine = Files.createDirectory(agents.resolve("mine"));
        FileAccess access = FileAccess.of(List.of(mine), List.of(agents));
        assertEquals(List.of(), access.roots(),
                "a workspace any exclusion covers is not a workspace");
        assertFalse(access.permits(mine.resolve("x.txt")),
                "and nothing inside it is reachable either");
    }

    @Test
    void a_server_owned_root_survives_an_exclusion_that_would_drop_a_workspace()
            throws IOException {
        // The other half, and the reason the two lists are two lists: the same
        // path, in the same position under the same exclusion, is kept when the
        // server is the one naming it. Excalibur's asymmetry — a deeper grant
        // inside a directory the server owns is a deliberate statement about
        // that subtree; a caller's path is not.
        Path agents = Files.createDirectories(real.resolve("srv/agents"));
        Path mine = Files.createDirectory(agents.resolve("mine"));
        // Spelled through `linked` and not as `mine`: this is the only place a
        // server-owned root gets canonicalised, and every other argument to
        // withServerOwned in this file is already spelled the real way. On a host
        // whose temp directory is not itself symlinked, dropping that
        // canonicalisation is otherwise invisible — measured, it survived.
        FileAccess access = FileAccess.of(List.of(), List.of(agents))
                .withServerOwned(List.of(linked.resolve("srv/agents/mine")));
        assertEquals(List.of(mine.toRealPath()), access.roots());
        assertTrue(access.permits(mine.resolve("x.txt")));
    }

    // ------------------------------------------------- hidden means hidden

    @Test
    void a_hidden_file_directly_under_a_root_is_refused() throws IOException {
        // `.env` is the shape the rule is named for: a file nobody excluded,
        // holding a credential, sitting in the middle of an ordinary checkout.
        // Nothing in `excluded` here — the refusal has to come from the
        // predicate or from nowhere.
        Files.writeString(ws.resolve(".env"), "AWS_SECRET_ACCESS_KEY=notreal");
        Files.writeString(ws.resolve("notes.md"), "n");
        FileAccess access = FileAccess.of(List.of(ws), List.of());

        assertFalse(access.permits(ws.resolve(".env")));
        assertTrue(access.permits(ws.resolve("notes.md")),
                "and the rest of the root is untouched, or this is not a rule but an outage");
    }

    @Test
    void a_hidden_directory_under_a_root_hides_the_console_token_inside_it() throws IOException {
        // THE ORIGINAL FINDING, with the shape it was found in: a workspace over
        // $HOME, and this server's own operator token four components down. That
        // token is a complete, permanent credential for every gated route —
        // TokenStore.acceptOperator files it with no expiry and AuthFilter asks
        // only validAccess — and ProjectStore.mandatoryExclusions named four
        // paths, none of them this one.
        //
        // A @TempDir stands in for $HOME. Nothing here goes near the real
        // ~/.config/plowshare/, which holds the credential of whatever server
        // this machine's operator is actually using.
        Path home = Files.createDirectory(real.resolve("home"));
        Path config = Files.createDirectories(home.resolve(".config/plowshare"));
        Files.writeString(config.resolve("console-token"), "not a real token");
        Files.writeString(home.resolve("todo.md"), "t");

        FileAccess access = FileAccess.of(List.of(home), List.of());

        assertFalse(access.permits(config.resolve("console-token")),
                "with the token file named in no exclusion list anywhere");
        assertFalse(access.permits(config), "nor the directory it sits in");
        assertTrue(access.permits(home.resolve("todo.md")),
                "a workspace over $HOME is still a workspace");
    }

    @Test
    void a_hidden_component_in_the_middle_of_an_ordinary_path_is_refused() throws IOException {
        // Neither end: the hidden component is two levels down and there are
        // ordinary names above and below it. An implementation that looked only
        // at the candidate's file name, or only at the component directly under
        // the root, passes both tests above and fails this one.
        Path inside = Files.createDirectories(ws.resolve("vendor/.git/objects"));
        Files.writeString(inside.resolve("pack"), "p");

        assertFalse(FileAccess.of(List.of(ws), List.of()).permits(inside.resolve("pack")));
    }

    @Test
    void a_hidden_root_that_was_named_makes_its_contents_reachable() throws IOException {
        // THE ESCAPE HATCH, and the case an exclusion-shaped rule could not have
        // had: FileAccess.of drops any workspace a covering exclusion covers, so
        // a `.`-hiding exclusion plus this root would leave no root at all.
        //
        // A dot inside a root is consent by construction. Somebody named
        // `.github`; nothing below it is hidden relative to it.
        Path github = Files.createDirectory(ws.resolve(".github"));
        Files.writeString(github.resolve("ci.yml"), "on: push");
        Files.writeString(ws.resolve(".env"), "SECRET=notreal");

        FileAccess access = FileAccess.of(List.of(ws, github), List.of());

        assertTrue(access.permits(github.resolve("ci.yml")),
                "the deepest covering root is .github itself, so nothing below it is hidden");
        assertTrue(access.permits(github), "including the root itself");
        assertEquals(List.of(ws.toRealPath(), github.toRealPath()), access.roots(),
                "and it is advertised, or it is a hatch nobody is told about");
        assertFalse(access.permits(ws.resolve(".env")),
                "naming one hidden directory does not un-hide the rest of the tree");
        assertFalse(access.permits(github.resolve(".secrets/key")),
                "nor anything hidden below the named root");
    }

    @Test
    void a_root_whose_canonical_path_runs_through_a_hidden_directory_does_not_refuse_itself()
            throws IOException {
        // What `canonical` settles for free, and the case that would turn this
        // rule into an outage if the comparison ran on the path as typed. The
        // workspace is spelled `real/checkout`; it really lives inside
        // `real/.local/`. Resolving first and measuring from the resolved root
        // is what keeps a deployment whose install directory happens to be
        // hidden working at all.
        Path hidden = Files.createDirectories(real.resolve(".local/share/checkout"));
        Files.writeString(hidden.resolve("notes.md"), "n");
        Path spelling = Files.createSymbolicLink(real.resolve("checkout"), hidden);

        FileAccess access = FileAccess.of(List.of(spelling), List.of());

        assertEquals(List.of(hidden.toRealPath()), access.roots());
        assertTrue(access.permits(spelling), "a root is inside itself however it was spelled");
        assertTrue(access.permits(spelling.resolve("notes.md")));
        assertTrue(access.permits(hidden.resolve("notes.md")),
                "and by the other spelling too, since the comparison is canonical on"
                        + " both sides");
    }

    @Test
    void a_server_owned_root_names_its_own_dot_and_no_more() throws IOException {
        // The judgement call withServerOwned's javadoc records, made where a
        // future caller will meet it rather than left to be discovered. Nothing
        // supplies a server-owned root today; a per-job scratch directory is
        // what this call is waiting for, and a scratch directory under
        // `~/.plowshare/` is the ordinary place to put one.
        //
        // Provenance does not enter: the rule measures from the deepest covering
        // root of either kind, so a server-owned root consents to the dot in its
        // own path exactly as a workspace does, and to nothing hidden below it.
        Path scratch = Files.createDirectories(real.resolve(".plowshare/scratch"));
        Files.writeString(scratch.resolve("draft.md"), "d");
        Files.createDirectory(scratch.resolve(".creds"));
        Files.writeString(scratch.resolve(".creds/key"), "k");

        FileAccess access = FileAccess.of(List.of(), List.of()).withServerOwned(List.of(scratch));

        assertEquals(List.of(scratch.toRealPath()), access.roots());
        assertTrue(access.permits(scratch.resolve("draft.md")),
                "a hidden scratch directory that granted nothing would be a root reported"
                        + " and never usable");
        assertFalse(access.permits(scratch.resolve(".creds/key")),
                "and it is not a way to un-hide a tree — that is what withServerOwned's"
                        + " javadoc says it declines to be");
    }

    @Test
    void a_path_spelled_with_dot_and_dot_dot_is_not_read_as_hidden() throws IOException {
        // `startsWith(".")` is true of both "." and "..", so if either could
        // survive into a canonical path this rule would refuse ordinary files —
        // a false refusal, not a security win.
        //
        // It cannot. `canonical` ends in normalize() on both of its exits, and
        // measured on this host an absolute path has nowhere for a `..` to
        // survive to: /a/./b is /a/b, /a/b/../.. is /, and even a leading /.. is
        // /. This is the instrument for that, because nothing else in the class
        // would notice it changing.
        Files.writeString(ws.resolve("notes.md"), "n");
        FileAccess access = FileAccess.of(List.of(ws), List.of());

        assertTrue(access.permits(ws.resolve("./notes.md")));
        assertTrue(access.permits(ws.resolve("sub/../notes.md")));
        assertTrue(access.permits(Path.of(ws + "/./sub/./../notes.md")));
        assertEquals(List.of(ws.toRealPath()),
                FileAccess.of(List.of(ws.resolve("./")), List.of()).roots(),
                "and a root spelled with a dot component is the same root, not a hidden one");
    }

    // ------------------------------------------------------- the two spellings

    @Test
    void a_root_and_a_candidate_spelled_through_different_links_still_compare() {
        // The root as task 1 stores it — absolute, normalised, symlink intact —
        // against a candidate spelled the real way.
        FileAccess access = FileAccess.of(List.of(linked.resolve("ws")), List.of());
        assertTrue(access.permits(ws.resolve("notes.md")),
                "a root reached through a symlink is the same directory");
    }

    @Test
    void an_exclusion_spelled_through_a_symlink_still_bites() throws IOException {
        // Task 1's measured hazard, in miniature: the server's config path is
        // stored unresolved, so an exclusion that is only ever compared as text
        // matches no canonicalised candidate. On this host that is not a corner
        // case — /tmp and /var are both symlinks, so a config under either
        // yields a mandatory exclusion that can never fire.
        Files.createDirectory(ws.resolve("conf"));
        Files.writeString(ws.resolve("conf/plowshare.yml"), "key: shhh");
        FileAccess access =
                FileAccess.of(List.of(ws), List.of(linked.resolve("ws/conf/plowshare.yml")));
        assertFalse(access.permits(ws.resolve("conf/plowshare.yml")),
                "an exclusion the candidate cannot be spelled to match is an exclusion"
                        + " that excludes nothing");
    }

    // --------------------------------------------------------------- symlinks

    @Test
    void a_symlink_out_of_the_root_is_refused_by_where_it_points() throws IOException {
        Files.writeString(outside.resolve("secret"), "s");
        Files.createSymbolicLink(ws.resolve("escape"), outside);
        assertFalse(FileAccess.of(List.of(ws), List.of()).permits(ws.resolve("escape/secret")),
                "containment is about the resolved target, not the path typed");
    }

    @Test
    void a_write_target_that_does_not_exist_yet_is_refused_by_where_its_link_points()
            throws IOException {
        // The case a read tool never sees. `toRealPath` throws for
        // `ws/escape/notyet` because the file is not there, and settling for
        // `toAbsolutePath().normalize()` on the whole path answers
        // `ws/escape/notyet` — inside the root — while the byte lands in
        // `outside/`. Measured: the same call on `ws/escape/secret`, which does
        // exist, answers `outside/secret`.
        Files.createSymbolicLink(ws.resolve("escape"), outside);
        // Asserted on the resolved path and not only on the refusal: with
        // @TempDir handing out a /var path on this host, the naive strategy
        // refuses this candidate too — for the wrong reason, by comparing an
        // unresolved /var candidate against a /private/var root. The equality
        // below is what the naive strategy cannot produce anywhere.
        assertEquals(outside.toRealPath().resolve("notyet"),
                FileAccess.canonical(ws.resolve("escape/notyet")));
        assertFalse(
                FileAccess.of(List.of(ws), List.of()).permits(ws.resolve("escape/notyet")),
                "a path that does not exist yet is still refused by where it would be"
                        + " created");
    }

    @Test
    void a_dangling_symlink_is_refused_by_where_it_points() throws IOException {
        // Measured: writing to `ws/gate` creates `outside/gone`. The link is
        // inside the root, its target is not, and `toRealPath` refuses the whole
        // path — so a canonical that only walked up to the deepest existing
        // ancestor would answer `ws/gate` and permit the write.
        Files.createSymbolicLink(ws.resolve("gate"), outside.resolve("gone"));
        assertFalse(FileAccess.of(List.of(ws), List.of()).permits(ws.resolve("gate")),
                "a link whose target is absent still points where it points");
    }

    @Test
    void dot_dot_is_resolved_where_the_filesystem_says_rather_than_textually()
            throws IOException {
        // `ws/escape/..` is `real/` by the filesystem — the parent of the
        // symlink's target — and `ws` by text. Only the first is where an open
        // would land.
        Files.createSymbolicLink(ws.resolve("escape"), outside);
        Files.writeString(real.resolve("neighbour"), "n");
        assertFalse(
                FileAccess.of(List.of(ws), List.of()).permits(ws.resolve("escape/../neighbour")),
                "textual normalisation of .. across a symlink names a directory the"
                        + " kernel would never open");
    }

    @Test
    void a_path_that_does_not_exist_yet_inside_a_root_is_still_inside_it() {
        assertTrue(FileAccess.of(List.of(ws), List.of()).permits(ws.resolve("new/deep/file.txt")),
                "a write tool has to be able to name a file before it exists, or"
                        + " nothing could ever be created");
    }

    @Test
    void canonical_resolves_the_deepest_real_ancestor_and_keeps_the_names_below_it()
            throws IOException {
        assertEquals(ws.toRealPath().resolve("new/deep/file.txt"),
                FileAccess.canonical(ws.resolve("new/deep/file.txt")),
                "the part that exists is resolved and the part that does not is kept");
        assertEquals(ws.toRealPath(), FileAccess.canonical(ws),
                "a path that exists in full is simply its real path");
    }

    @Test
    void dot_dot_below_a_directory_that_does_not_exist_is_settled_by_text() throws IOException {
        // The one place where text is all there is to go on: nothing below
        // `notyet` exists, so there is no filesystem answer to ask for, and
        // leaving the `..` in place would hand a file tool a path with a name
        // in it that the tool would then have to settle itself.
        assertEquals(ws.toRealPath().resolve("x"),
                FileAccess.canonical(ws.resolve("notyet/../x")));
    }

    @Test
    void a_relative_path_is_settled_against_the_process_directory() throws IOException {
        // Worth pinning because it is a trap for the tools built on this: a
        // relative path does NOT mean "inside the workspace". It means what the
        // JDK says it means, which is the server's own working directory — and
        // that is almost never inside a root, so it is refused rather than
        // silently reinterpreted.
        assertEquals(Path.of("").toAbsolutePath().toRealPath().resolve("relative.txt"),
                FileAccess.canonical(Path.of("relative.txt")));
        assertFalse(FileAccess.of(List.of(ws), List.of()).permits(Path.of("relative.txt")),
                "a bare filename is not a filename inside the workspace");
    }

    @Test
    void a_relative_link_target_is_resolved_against_the_links_own_directory()
            throws IOException {
        // A dangling link with a relative target, pointing back inside the
        // root: the correct answer is that it is permitted. Resolving that
        // target against anything but the link's own directory — the process
        // directory, say — answers with a path outside every root and refuses a
        // write the job is entitled to make.
        Path inner = Files.createDirectory(ws.resolve("inner"));
        Files.createSymbolicLink(inner.resolve("back"), Path.of("../notyet"));
        assertEquals(ws.toRealPath().resolve("notyet"),
                FileAccess.canonical(inner.resolve("back")));
        assertTrue(FileAccess.of(List.of(ws), List.of()).permits(inner.resolve("back")));
    }

    @Test
    void a_dangling_chain_the_kernel_would_follow_is_refused_by_where_it_ends()
            throws IOException {
        // Sixteen links is the longest chain this kernel resolves — measured:
        // it refuses seventeen with FileSystemException — so this is the far end
        // of the band where the hop budget is load-bearing. Below the kernel's
        // limit a chain we stop expanding early canonicalises to a link's own
        // location, inside the workspace, and the write follows the chain out.
        // Measured with the budget at three and a chain of five: permitted, and
        // the byte landed outside. The whole suite stayed green.
        Path chain = Files.createDirectory(ws.resolve("chain"));
        for (int link = 1; link < KERNEL_FOLLOWS; link++) {
            Files.createSymbolicLink(chain.resolve("l" + link), chain.resolve("l" + (link + 1)));
        }
        Files.createSymbolicLink(
                chain.resolve("l" + KERNEL_FOLLOWS), outside.resolve("target"));
        assertEquals(outside.toRealPath().resolve("target"),
                FileAccess.canonical(chain.resolve("l1")),
                "the budget has to outlast any chain the kernel itself would follow");
        assertFalse(FileAccess.of(List.of(ws), List.of())
                .permits(chain.resolve("l1")));
    }

    @Test
    void a_chain_past_the_budget_is_left_where_it_was_typed() throws IOException {
        // The other end of the hop budget, and the justification the fallback in
        // `canonical` actually rests on: a path this class stops resolving is
        // one no open can follow either, so leaving it inside the workspace
        // costs nothing. 45 is chosen to exceed MAX_LINK_HOPS, which is 40 — if
        // that constant is ever raised past this, this test measures nothing and
        // has to move with it.
        Path chain = Files.createDirectory(ws.resolve("long"));
        for (int link = 1; link < 45; link++) {
            Files.createSymbolicLink(chain.resolve("l" + link), chain.resolve("l" + (link + 1)));
        }
        Files.createSymbolicLink(chain.resolve("l45"), outside.resolve("target"));
        assertTrue(FileAccess.canonical(chain.resolve("l1")).startsWith(ws.toRealPath()),
                "a chain longer than the budget is not followed to its end");
        assertThrows(IOException.class, () -> Files.writeString(chain.resolve("l1"), "x"),
                "and the kernel will not follow it either, which is why permitting"
                        + " it is harmless rather than a hole");
    }

    @Test
    void canonical_is_not_idempotent_past_the_budget_so_permits_can_disagree_with_itself()
            throws IOException {
        // The load-bearing half of the budget's behaviour, and the one a caller
        // has to know: `canonical` gives up at MAX_LINK_HOPS and answers with
        // the link it stopped at, so running it again resolves the next stretch.
        // Here the first answer is inside the root and the second is outside it,
        // and `permits` therefore answers differently about a path and about its
        // own canonical form.
        //
        // Neither answer is unsafe alone — no open can follow this chain either.
        // Mixing them is: a caller that checks the path as typed and then opens
        // its canonical form has checked a different file from the one it
        // touches. `canonical`'s javadoc states the rule; this is what breaks if
        // the budget, or the giving-up, ever changes.
        Path chain = Files.createDirectory(ws.resolve("gap"));
        for (int link = 1; link < 45; link++) {
            Files.createSymbolicLink(chain.resolve("l" + link), chain.resolve("l" + (link + 1)));
        }
        Files.createSymbolicLink(chain.resolve("l45"), outside.resolve("target"));
        FileAccess access = FileAccess.of(List.of(ws), List.of());

        Path typed = chain.resolve("l1");
        Path once = FileAccess.canonical(typed);
        assertTrue(once.startsWith(ws.toRealPath()),
                "the first answer is the link the walk stopped at, inside the root");
        assertEquals(outside.toRealPath().resolve("target"), FileAccess.canonical(once),
                "and canonicalising that answer again reaches the end of the chain,"
                        + " which is what 'not idempotent' means here");
        assertTrue(access.permits(typed));
        assertFalse(access.permits(once),
                "so permits disagrees with itself across one canonicalisation, and a"
                        + " caller that checks one path and opens another is checking"
                        + " a different file from the one it touches");
    }

    @Test
    void a_link_the_platform_will_not_resolve_terminates_and_stays_put() throws IOException {
        // A pair pointing at each other. `toRealPath` answers with an
        // IOException rather than a hang, but expanding links by hand to follow
        // a dangling one turns that into a loop with nothing to stop it, and a
        // wedged file tool is this project's worst failure mode — the killable
        // child process for regexes exists for the same reason.
        Files.createSymbolicLink(ws.resolve("here"), ws.resolve("there"));
        Files.createSymbolicLink(ws.resolve("there"), ws.resolve("here"));
        Path answer = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> FileAccess.canonical(ws.resolve("here")));
        assertTrue(answer.startsWith(ws.toRealPath()),
                "a link the platform will not resolve is left where it was typed, which"
                        + " is inside the root and not beyond it");
    }

    // ------------------------------------------------------------ advertising

    @Test
    void the_advertised_roots_are_canonical_and_the_servers_own_come_first() throws IOException {
        assertEquals(List.of(ws.toRealPath()),
                FileAccess.of(List.of(linked.resolve("ws")), List.of()).roots(),
                "a root is advertised as the directory it is, not as the name it was"
                        + " given");
        // Order is part of the answer, and it is asserted here because
        // `withServerOwned`'s javadoc claims it: the server's own directories
        // are listed first, as read_roots lists scratch first. Nothing depends
        // on it yet, which is exactly why an unheld claim about it would rot.
        assertEquals(List.of(outside.toRealPath(), ws.toRealPath()),
                FileAccess.of(List.of(ws), List.of()).withServerOwned(List.of(outside)).roots());
    }

    @Test
    void a_root_that_is_exactly_excluded_is_not_advertised() {
        assertEquals(List.of(),
                FileAccess.of(List.of(), List.of(ws)).withServerOwned(List.of(ws)).roots(),
                "a root that permits nothing is a tree a glob would walk and return"
                        + " nothing from");
    }

    @Test
    void a_root_that_merely_sits_under_an_exclusion_is_still_advertised() throws IOException {
        // The other half of `the_more_specific_statement_about_a_subtree_wins`:
        // the root inside the excluded tree is reachable, so dropping it from
        // the advertisement would hide a directory its own tools can read.
        Path archive = Files.createDirectories(ws.resolve("data/archive"));
        assertEquals(List.of(archive.toRealPath()),
                FileAccess.of(List.of(), List.of(ws.resolve("data")))
                        .withServerOwned(List.of(archive)).roots());
    }
}
