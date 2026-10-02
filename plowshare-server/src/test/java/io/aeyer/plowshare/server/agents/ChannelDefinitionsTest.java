package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.files.SessionChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Definitions on somebody's laptop, read down the socket that exists so the
 * server can ask.
 */
class ChannelDefinitionsTest {

    /**
     * Every request this class sends says it is the harness reading definitions.
     *
     * <p>TODO §13's fence is the model's: a client refuses hidden paths so an
     * agent's file tools cannot reach a credential. This class is not the model,
     * and the client can only tell the difference if the request says so.
     */
    @Test
    void every_request_it_sends_is_marked_as_the_harness_reading_definitions() {
        List<FileRequest> sent = new ArrayList<>();
        FakeFiles files = new FakeFiles()
                .withRoots(List.of("/work"))
                .withListing(".plowshare/agents", List.of("scribe.md"))
                .withFile(".plowshare/agents/scribe.md", "---\nname: scribe\n---\n");
        SessionChannel recording = (session, request) -> {
            sent.add(request);
            return files.ask(session, request);
        };

        new ChannelDefinitions(recording, "s").list();
        new ChannelDefinitions(recording, "s").defaultBot();

        assertFalse(sent.isEmpty());
        assertTrue(sent.stream().allMatch(request ->
                        FileRequest.DEFINITIONS.equals(request.purpose())),
                "a request went out unmarked: " + sent);
    }

    @Test
    void a_clients_definitions_are_listed_from_its_own_dot_directory() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("mine.md", "notes.txt"))
                .withFile(".plowshare/bots/mine.md", "---\nname: mine\n---\nbody\n");

        List<DefinitionSource.Definition> found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list();

        assertEquals(List.of("mine"),
                found.stream().map(DefinitionSource.Definition::name).toList());
    }

    @Test
    void origin_names_the_client_and_the_session_because_the_path_is_not_on_this_disk() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("mine.md"))
                .withFile(".plowshare/bots/mine.md", "---\nname: mine\n---\nbody\n");

        DefinitionSource.Definition one =
                new ChannelDefinitions(files, "session-1", ".plowshare").list().get(0);

        assertTrue(one.origin().contains("session-1"), one.origin());
        assertTrue(one.origin().contains(".plowshare/bots/mine.md"), one.origin());
    }

    @Test
    void a_client_with_no_dot_directory_contributes_nothing_rather_than_failing() {
        assertEquals(List.of(),
                new ChannelDefinitions(new FakeFiles(), "session-1", ".plowshare").list());
    }

    @Test
    void a_socket_that_closed_mid_listing_contributes_nothing_rather_than_failing() {
        FakeFiles gone = new FakeFiles().thatIsClosed();

        assertEquals(List.of(),
                new ChannelDefinitions(gone, "session-1", ".plowshare").list());
    }

    /**
     * Ruling 1: going under {@code RemoteProvider} means going under its
     * containment too, so {@code ChannelDefinitions} has to do its own. A
     * client answering a listing with a path built to climb back out of {@code
     * .plowshare/} is refused rather than read — its sibling in the same
     * listing, which stays inside, is read exactly as ever. Not a whole-source
     * failure: one bad entry costs only itself.
     */
    @Test
    void a_path_that_climbs_out_of_the_dot_directory_is_refused_and_its_sibling_still_reads() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots",
                        List.of("../../etc/passwd", "mine.md"))
                .withFile(".plowshare/bots/mine.md", "---\nname: mine\n---\nbody\n");

        List<DefinitionSource.Definition> found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list();

        assertEquals(List.of("mine"),
                found.stream().map(DefinitionSource.Definition::name).toList());
    }

    /**
     * Ruling 2: a windowed read is paged to its end and never parsed partial.
     * {@link FakeFiles#withPagedFile} answers by the REQUESTED offset rather
     * than call order (I1's fix), so this pins the exact assembled text and
     * not merely that both halves appear somewhere in it — a caller that
     * swapped the two windows, or duplicated one, would fail the equality
     * even though {@code contains} on each half would still pass.
     *
     * <p>Checked by deliberately breaking the offset arithmetic in {@code
     * ChannelDefinitions.read} (advancing by a constant instead of {@code
     * span.lines().size()}) and confirming this test fails: the second
     * request then asks {@link FakeFiles} for an offset nothing was
     * registered for, {@link FakeFiles#read} throws, {@code read} catches it
     * and returns {@code null}, and the file contributes nothing — so {@code
     * list()} comes back empty and this assertion fails rather than passing
     * on a coincidence.
     */
    @Test
    void a_file_spanning_more_than_one_window_is_assembled_whole() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("long.md"))
                .withPagedFile(".plowshare/bots/long.md", Map.of(
                        0, new Span(List.of("---", "name: long", "---", "line one"),
                                0, 5, true, Span.LINES),
                        4, new Span(List.of("line two"), 4, 5, false, Span.END)));

        DefinitionSource.Definition found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list().get(0);

        assertEquals("---\nname: long\n---\nline one\nline two", found.text());
    }

    /**
     * C1: the value this class validates and the value it reads and records
     * must be the SAME string. {@code sub/../mine.md} normalises to something
     * that would pass containment, but a real client resolves {@code sub/..}
     * itself — possibly through a symlink this server never sees — so
     * validating the normalised form and reading the raw one is a check on a
     * string nobody acts on. The fix refuses any hit that normalising would
     * change, which this is one of, so {@code mine} must not appear even
     * though {@code .plowshare/bots/mine.md} names a file that does exist.
     */
    @Test
    void a_path_that_normalises_to_something_different_is_refused_even_though_the_target_exists() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("sub/../mine.md"))
                .withFile(".plowshare/bots/mine.md", "---\nname: mine\n---\nbody\n");

        List<DefinitionSource.Definition> found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list();

        assertEquals(List.of(), found);
    }

    /**
     * C2: containment is anchored at the head of the path and not merely
     * present somewhere in its tail. Every one of these normalises to a
     * string that already equals itself (so C1's check does not fire) and
     * every one has {@code .plowshare/bots/<file>} as its last two segments
     * before the filename — which is exactly what let each of them through
     * the earlier, tail-only check. None may appear in the result, and
     * {@code mine} — genuinely {@code .plowshare/bots/mine.md} — must still
     * be the one thing that does, proving the fix does not merely refuse
     * everything.
     */
    @Test
    void a_path_anchored_only_in_its_tail_is_refused_however_it_climbs_to_get_there() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("mine.md"))
                .withFile(".plowshare/bots/mine.md", "---\nname: mine\n---\nbody\n")
                .withRawListing(".plowshare/bots", List.of(
                        // Opens with '..' that has nothing before it to cancel
                        // against, so normalising leaves it in place -- the
                        // reviewer's own example of what the tail-only check let
                        // through.
                        "../../.plowshare/bots/evil.md",
                        // An absolute path with the identical tail.
                        "/anywhere/.plowshare/bots/evil.md",
                        // The directory prefix itself, spelled almost right.
                        ".plowshareEVIL/bots/evil.md",
                        // The prefix with nothing after it -- no filename.
                        ".plowshare/bots",
                        "/etc/passwd",
                        "",
                        "   "));

        List<DefinitionSource.Definition> found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list();

        assertEquals(List.of("mine"),
                found.stream().map(DefinitionSource.Definition::name).toList());
    }

    /**
     * C3: a client that cannot keep its story straight about how long a file
     * is contributes nothing, rather than an assembly built against a moving
     * target. The first reply says the file is 5 lines and there is more; the
     * second — answering the offset this class's own tally asks for, {@code
     * 4} — says the file is actually 500 lines. A client trying to keep this
     * loop running forever by inflating {@code totalLines} every round is
     * exactly this shape repeated, and the first inconsistency is where this
     * class has to stop rather than the file growing unboundedly in memory.
     */
    @Test
    void a_file_that_reports_a_different_total_partway_through_contributes_nothing() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("greedy.md"))
                .withPagedFile(".plowshare/bots/greedy.md", Map.of(
                        0, new Span(List.of("---", "name: greedy", "---", "line one"),
                                0, 5, true, Span.LINES),
                        4, new Span(List.of("line two"), 4, 500, true, Span.LINES)));

        assertEquals(List.of(),
                new ChannelDefinitions(files, "session-1", ".plowshare").list());
    }

    /**
     * C3's other half: this class's own tally of lines actually received —
     * not the offset a reply echoes back — is what the next request's window
     * is built from. A reply that lies about {@code offset} (claims {@code 0}
     * again on its second answer, which a client stuck replaying its first
     * page would do) is not what steers the loop; {@link FakeFiles} is keyed
     * by the offset {@code ChannelDefinitions} itself asks for, so if this
     * class trusted the reply's own {@code offset} field instead of counting
     * lines it would ask for offset {@code 0} again, {@link FakeFiles} would
     * hand back the SAME first page forever, and this test would hang or
     * (bounded by {@code expectedTotal}) come back with the first page
     * duplicated rather than the true two-page assembly asserted here.
     */
    @Test
    void the_next_offset_is_this_classs_own_tally_and_not_the_peers_echoed_offset() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("long.md"))
                .withPagedFile(".plowshare/bots/long.md", Map.of(
                        // The first reply's own `offset` field is wrong on
                        // purpose -- 99, not 0 -- to prove nothing here reads
                        // it. FakeFiles is keyed by the REQUESTED offset, so
                        // this still answers the first (and only ever) call
                        // at offset 0.
                        0, new Span(List.of("---", "name: long", "---", "line one"),
                                99, 5, true, Span.LINES),
                        4, new Span(List.of("line two"), 4, 5, false, Span.END)));

        DefinitionSource.Definition found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list().get(0);

        assertEquals("---\nname: long\n---\nline one\nline two", found.text());
    }

    // --- C2(a): a segment here is a segment on the client's filesystem too ---------

    /**
     * C2(a): this check runs in the SERVER's {@link java.nio.file.FileSystem}
     * on a string the CLIENT resolves in its own.
     *
     * <p>{@code ..\..\secret.md} is one opaque filename on a POSIX server — it
     * normalises unchanged, it is not absolute, the path counts three segments,
     * the first two are {@code .plowshare} and {@code bots}, and it ends {@code
     * .md}. It passes every structural check that reasons in this JVM's
     * separator, and a Windows client then splits it on the backslashes and
     * climbs two directories out of {@code .plowshare/}. That is the whole
     * finding, and it is the first entry below.
     *
     * <p>The rest are the same property at the other separators — a Windows
     * drive-relative {@code C:}, an NTFS alternate data stream, a name that is
     * dots and spaces and so is {@code ..} once Windows has stripped the
     * trailing ones, and a {@code NUL} that truncates a path in whatever C
     * library eventually sees it. Every one of them is a segment to this server
     * and is not a segment to somebody. {@code mine} must still be the one
     * thing returned, so the rule is a rule and not a refusal of everything.
     *
     * <p><b>Every escaping entry below is also registered as a readable
     * file</b>, and that is what makes this test able to fail. A path that
     * containment refused and a path that nothing answered a read for are the
     * same empty list seen from outside; a fake holding nothing at these names
     * would have made this test pass against the very code it was written to
     * catch, because the READ, and not the containment, would have been what
     * returned nothing.
     */
    @Test
    void a_segment_that_is_a_separator_to_some_other_filesystem_is_refused() {
        // One segment to this server, three to a Windows client, and two of
        // them are '..'. This is the finding.
        String climbs = ".plowshare/bots/..\\..\\secret.md";
        // Drive-relative: 'C:x' resolves against C:'s OWN current directory
        // and not against whatever precedes it in the string.
        String drive = ".plowshare/bots/C:..\\secret.md";
        // An alternate data stream hanging off the file beside it.
        String stream = ".plowshare/bots/mine:secret.md";
        // A control character, which mangles or truncates a path at whatever
        // layer eventually hands it to a C library.
        String control = ".plowshare/bots/ev\nil.md";

        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("mine.md"))
                .withFile(".plowshare/bots/mine.md", "---\nname: mine\n---\nbody\n")
                .withRawListing(".plowshare/bots", List.of(climbs, drive, stream, control,
                        // Windows strips trailing dots and spaces from a name,
                        // so this one is the parent directory once it lands.
                        ".plowshare/bots/.. ",
                        // The same climb with no directory in front of it.
                        "..\\..\\secret.md"))
                .withFile(climbs, "---\nname: climbs\n---\nbody\n")
                .withFile(drive, "---\nname: drive\n---\nbody\n")
                .withFile(stream, "---\nname: stream\n---\nbody\n")
                .withFile(control, "---\nname: control\n---\nbody\n");

        List<DefinitionSource.Definition> found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list();

        assertEquals(List.of("mine"),
                found.stream().map(DefinitionSource.Definition::name).toList());
    }

    // --- C2(b): the shape a real client actually answers with ---------------------

    /**
     * C2(b): <b>a production client answers a glob with ABSOLUTE paths</b>, and
     * the containment that refused every absolute path made this entire source
     * contribute nothing against every real client — silently, logged at debug,
     * with 3 400 green tests, because every fixture in this suite spells its
     * hits relative.
     *
     * <p>Traced rather than assumed. {@code ClientEnforcer.glob} walks {@code
     * FileAccess.roots()}; {@code FileAccess.of} canonicalises every root
     * through {@code toRealPath()}/{@code toAbsolutePath()}; {@code
     * FileSearch.matching} collects candidates from {@code Files.walk(root)},
     * which prefixes each with that absolute root; and {@code
     * ClientEnforcer.strings} hands them over as {@code Path::toString}. The
     * pattern stays relative — {@code glob} refuses an absolute one and matches
     * against {@code root.relativize(candidate)} — but the ANSWER is not.
     *
     * <p>So this is the shape no other fixture here has: a session declaring
     * the root it walks, and a hit under it, spelled the way the wire really
     * spells it.
     */
    @Test
    void the_absolute_path_a_real_client_answers_with_is_read_and_not_silently_dropped() {
        FakeFiles files = new FakeFiles()
                .withRoots(List.of("/Users/x/proj"))
                .withRawListing(".plowshare/bots",
                        List.of("/Users/x/proj/.plowshare/bots/mine.md"))
                .withFile("/Users/x/proj/.plowshare/bots/mine.md",
                        "---\nname: mine\n---\nbody\n");

        List<DefinitionSource.Definition> found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list();

        assertEquals(List.of("mine"),
                found.stream().map(DefinitionSource.Definition::name).toList());
        assertTrue(found.get(0).origin().contains("/Users/x/proj/.plowshare/bots/mine.md"),
                found.get(0).origin());
    }

    /**
     * C2(b)'s other half: accepting the absolute shape is not accepting an
     * absolute path. The anchor is {@link
     * io.aeyer.plowshare.protocol.FileRequest#ROOTS} — what this session itself
     * says it can see — and the hit must be exactly {@code
     * <root>/.plowshare/<subdir>/<one name>} for one of them.
     *
     * <p>Each entry below is absolute and each has {@code
     * .plowshare/bots/<file>} in exactly the position the accepted one does.
     * They differ only in what precedes it: another checkout the session never
     * declared, a prefix that merely starts with the declared root's characters,
     * and the root of the disk. That is the distinction a check anchored on a
     * root can make and a check anchored on a tail cannot.
     */
    @Test
    void an_absolute_path_under_no_root_this_session_declared_is_refused() {
        FakeFiles files = new FakeFiles()
                .withRoots(List.of("/Users/x/proj"))
                .withRawListing(".plowshare/bots", List.of(
                        "/Users/x/proj/.plowshare/bots/mine.md",
                        // A checkout beside the declared one.
                        "/Users/x/other/.plowshare/bots/evil.md",
                        // Starts with the root's CHARACTERS and not with the
                        // root: a string-prefix check passes this one.
                        "/Users/x/project-two/.plowshare/bots/evil.md",
                        // The identical tail, at the top of the disk.
                        "/.plowshare/bots/evil.md",
                        // Deeper than one name under the subdirectory.
                        "/Users/x/proj/.plowshare/bots/nested/evil.md",
                        // The root itself, wearing the tail.
                        "/etc/.plowshare/bots/evil.md"))
                .withFile("/Users/x/proj/.plowshare/bots/mine.md",
                        "---\nname: mine\n---\nbody\n")
                .withFile("/Users/x/other/.plowshare/bots/evil.md",
                        "---\nname: evil\n---\nbody\n")
                .withFile("/Users/x/project-two/.plowshare/bots/evil.md",
                        "---\nname: evil\n---\nbody\n")
                .withFile("/.plowshare/bots/evil.md", "---\nname: evil\n---\nbody\n")
                .withFile("/Users/x/proj/.plowshare/bots/nested/evil.md",
                        "---\nname: evil\n---\nbody\n")
                .withFile("/etc/.plowshare/bots/evil.md", "---\nname: evil\n---\nbody\n");

        List<DefinitionSource.Definition> found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list();

        assertEquals(List.of("mine"),
                found.stream().map(DefinitionSource.Definition::name).toList());
    }

    /**
     * A session that declares no roots anchors no absolute hit. Empty is a real
     * answer to {@code roots} — {@code ClientEnforcer.roots} says so — and the
     * safe reading of it is "this client can see nothing", not "there is no
     * rule". Every other fixture in this file relies on it: they answer with
     * relative hits and never declare a root, and their relative hits still
     * read.
     */
    @Test
    void a_session_that_declares_no_roots_anchors_no_absolute_hit() {
        FakeFiles files = new FakeFiles()
                .withRawListing(".plowshare/bots",
                        List.of("/Users/x/proj/.plowshare/bots/mine.md"))
                .withFile("/Users/x/proj/.plowshare/bots/mine.md",
                        "---\nname: mine\n---\nbody\n");

        assertEquals(List.of(),
                new ChannelDefinitions(files, "session-1", ".plowshare").list());
    }

    /**
     * A deployment that configured two path segments where one belongs gets
     * nothing, rather than a containment check whose arithmetic quietly widened
     * by a segment. {@code .plowshare/nested/bots/x.md} would be four names
     * against a shape written for three.
     */
    @Test
    void a_configured_directory_that_is_not_one_segment_reads_nothing_at_all() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/nested/bots", List.of("mine.md"))
                .withFile(".plowshare/nested/bots/mine.md", "---\nname: mine\n---\nbody\n");

        assertEquals(List.of(),
                new ChannelDefinitions(files, "session-1", ".plowshare/nested").list());
    }

    // --- C3: the ceiling is this server's -----------------------------------------

    /**
     * C3, the finding itself: <b>{@code Span.totalLines} is an {@code int} and
     * it comes from the peer.</b>
     *
     * <p>Every consistency check this class can make against the reply is
     * satisfied here, deliberately: the total never changes, no page is empty,
     * the running tally never overtakes the total, and the offset advances by
     * this class's own count. A client answering {@code Integer.MAX_VALUE} on
     * its first page and then full frames with {@code more=true} forever passes
     * all of them for about 1.07 million rounds, and the accumulator reaches a
     * terabyte. The bound was a number the attacker picked.
     *
     * <p><b>Why the fake generates instead of answering canned pages.</b> A map
     * of offsets has a largest key, so an unbounded caller merely walks off the
     * end of it and gets an exception this class swallows into "contributes
     * nothing" — the same empty list a bounded caller returns, and a test that
     * cannot tell them apart. {@link FakeFiles#withEndlessFile} never runs out;
     * it raises an {@link AssertionError} once it has been read more times than
     * a caller with a real ceiling ever could, and an {@code Error} travels
     * straight out through this class's {@code RuntimeException} catches.
     *
     * <p>{@code stopAfter} is 13: {@code MAX_DEFINITION_LINES} (20 000) over
     * {@code Window.MAX_WINDOW_LINES} (2 000) is ten full pages to reach the
     * ceiling and an eleventh read to cross it, and three spare so that this
     * fails on the ceiling being ABSENT rather than on it being off by one.
     */
    @Test
    void a_file_claiming_a_colossal_total_is_stopped_by_this_servers_own_line_ceiling() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("endless.md"))
                .withEndlessFile(".plowshare/bots/endless.md",
                        2_000, Integer.MAX_VALUE, 13);

        assertEquals(List.of(),
                new ChannelDefinitions(files, "session-1", ".plowshare").list());
    }

    /**
     * C3's second ceiling, and the reason there are two. This file is honest
     * about its length — 1 200 lines, {@code more=false}, one reply and no
     * paging at all, so every line-counting bound in this class is satisfied —
     * and it is 1.2 MB of text. A ceiling counted only in lines lets it through.
     *
     * <p>The inverse case is what the line ceiling is for and the test above
     * covers it: a million empty lines is a few kilobytes of text and a million
     * {@code String}s on the heap.
     */
    @Test
    void a_file_past_the_byte_ceiling_contributes_nothing_even_on_one_honest_page() {
        List<String> fat = new ArrayList<>();
        fat.add("---");
        fat.add("name: fat");
        fat.add("---");
        while (fat.size() < 1_200) {
            fat.add("x".repeat(1_000));
        }

        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("fat.md"))
                .withPagedFile(".plowshare/bots/fat.md", Map.of(
                        0, new Span(fat, 0, fat.size(), false, Span.END)));

        assertEquals(List.of(),
                new ChannelDefinitions(files, "session-1", ".plowshare").list());
    }

    /**
     * C3 at the level above one file. {@link ChannelDefinitions#MAX_DEFINITION_BYTES}
     * bounds one definition, and one definition is not what this source
     * returns: a listing may name as many as {@code ClientEnforcer.MAX_MATCHES}
     * allows, every one of them read to its own ceiling and every one of them
     * KEPT in the list handed back. A ceiling that is only per-file leaves the
     * source unbounded by exactly the argument that made the per-reply ceiling
     * insufficient — the number of files is a number the client picks too.
     *
     * <p>Twelve files of just under 1 MiB each: every one of them is inside the
     * per-file ceiling, and together they are past {@link
     * ChannelDefinitions#MAX_SOURCE_BYTES}.
     *
     * <p><b>Nothing at all comes back, and not the ten that fitted.</b> A
     * definition set that is missing names for a reason nothing downstream can
     * see does not make a resolution fail — it makes it resolve to a DIFFERENT
     * agent, silently, which is {@code read}'s own argument about a truncated
     * file that still parses, one level up.
     */
    @Test
    void a_session_whose_definitions_are_past_the_source_budget_contributes_nothing_at_all() {
        // 900 lines of 1 000 characters, not one line of 900 000: `Window.cut`
        // refuses a line wider than MAX_WINDOW_BYTES, and a fixture built that
        // way would be refused for the wrong reason before any budget was
        // consulted.
        String body = ("x".repeat(1_000) + "\n").repeat(900);
        FakeFiles files = new FakeFiles();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            names.add("fat" + i + ".md");
            files.withFile(".plowshare/bots/fat" + i + ".md",
                    "---\nname: fat" + i + "\n---\n" + body);
        }
        files.withListing(".plowshare/bots", names);

        assertEquals(List.of(),
                new ChannelDefinitions(files, "session-1", ".plowshare").list());
    }

    /**
     * The count is of definitions, not of files. The glob asks for {@code *}
     * and not {@code *.md} on purpose — the suffix test is made on this side so
     * that this source and {@code FilesystemDefinitions} agree about what
     * counts as a definition regardless of how a client's filesystem spells an
     * extension — so a {@code bots/} directory holding six hundred screenshots
     * beside one definition answers with six hundred and one names.
     *
     * <p>Counting all of them against {@link
     * ChannelDefinitions#MAX_DEFINITIONS} would kill the whole tier over a
     * directory containing exactly one definition, which is a ceiling measuring
     * the wrong thing. The one {@code .md} must still be served.
     */
    @Test
    void a_directory_full_of_files_that_are_not_definitions_does_not_spend_the_budget() {
        FakeFiles files = new FakeFiles();
        List<String> names = new ArrayList<>();
        for (int i = 0; i <= ChannelDefinitions.MAX_DEFINITIONS; i++) {
            names.add("shot" + i + ".png");
        }
        names.add("mine.md");
        files.withListing(".plowshare/bots", names)
                .withFile(".plowshare/bots/mine.md", "---\nname: mine\n---\nbody\n");

        List<DefinitionSource.Definition> found =
                new ChannelDefinitions(files, "session-1", ".plowshare").list();

        assertEquals(List.of("mine"),
                found.stream().map(DefinitionSource.Definition::name).toList());
    }

    /**
     * The same budget in its cheaper half: a listing longer than {@link
     * ChannelDefinitions#MAX_DEFINITIONS} is refused <b>before a single read</b>.
     * A directory naming a thousand definitions is not a definitions directory,
     * and the alternative to saying so here is one round trip per name to
     * discover the same thing.
     */
    @Test
    void a_listing_longer_than_this_server_will_read_costs_the_whole_source() {
        FakeFiles files = new FakeFiles();
        List<String> names = new ArrayList<>();
        for (int i = 0; i <= ChannelDefinitions.MAX_DEFINITIONS; i++) {
            names.add("one" + i + ".md");
            files.withFile(".plowshare/bots/one" + i + ".md",
                    "---\nname: one" + i + "\n---\nbody\n");
        }
        files.withListing(".plowshare/bots", names);

        assertEquals(List.of(),
                new ChannelDefinitions(files, "session-1", ".plowshare").list());
    }

    // --- the orchestrations source reads a directory of its own -------------------

    @Test
    void the_orchestrations_source_reads_only_its_own_directory() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/orchestrations", List.of("code_implementation.md"))
                .withFile(".plowshare/orchestrations/code_implementation.md",
                        "---\nname: code_implementation\n---\nbody\n")
                .withListing(".plowshare/agents", List.of("scribe.md"))
                .withFile(".plowshare/agents/scribe.md", "---\nname: scribe\n---\nbody\n");

        List<DefinitionSource.Definition> found =
                ChannelDefinitions.orchestrations(files, "session-1").list();

        assertEquals(List.of("code_implementation"),
                found.stream().map(DefinitionSource.Definition::name).toList());
    }

    @Test
    void the_agent_source_does_not_read_orchestrations() {
        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/orchestrations", List.of("code_implementation.md"))
                .withFile(".plowshare/orchestrations/code_implementation.md",
                        "---\nname: code_implementation\n---\nbody\n");

        assertEquals(List.of(), new ChannelDefinitions(files, "session-1").list());
    }

    /**
     * C3's third half: a reply may not carry more lines than the window that
     * asked for them. {@code Window.cut} is what a client is supposed to use
     * and it cannot overshoot; a reply that does is answering a question nobody
     * put, and it is the cheapest way to make one round trip carry many
     * windows' worth of heap.
     *
     * <p>2 001 lines against a window that asked for {@code
     * Window.MAX_WINDOW_LINES} — one over, so this fails on the rule and not on
     * a ceiling somewhere above it. Everything else about the reply is
     * impeccable: the total matches what was sent, nothing more remains, and
     * the file is well under both ceilings.
     */
    @Test
    void a_reply_carrying_more_lines_than_the_window_asked_for_contributes_nothing() {
        List<String> overshoot = new ArrayList<>();
        overshoot.add("---");
        overshoot.add("name: overshoot");
        overshoot.add("---");
        while (overshoot.size() < Window.MAX_WINDOW_LINES + 1) {
            overshoot.add("body");
        }

        FakeFiles files = new FakeFiles()
                .withListing(".plowshare/bots", List.of("overshoot.md"))
                .withPagedFile(".plowshare/bots/overshoot.md", Map.of(
                        0, new Span(overshoot, 0, overshoot.size(), false, Span.END)));

        assertEquals(List.of(),
                new ChannelDefinitions(files, "session-1", ".plowshare").list());
    }
}
