package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.files.ClientEnforcer;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.agents.FileTools;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The doubled enforcement, asserted rather than argued: one file, one window,
 * two halves of a wire, one {@link Span}.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>Every javadoc in this slice says the same thing — {@code Window} lives in
 * {@code plowshare-protocol} so that a remote read and a local one cannot
 * disagree, {@code FileProvider.read} says both implementations cut with {@link
 * Window#cut} and neither works out a range of its own, {@code ClientEnforcer}
 * and {@code LocalProvider} each say it about themselves. <b>None of that was a
 * test.</b> Each half was measured against {@code Window.cut} inside its own
 * module, which is the strongest statement either module can make on its own and
 * is not the statement the design rests on: the two could each be right about
 * {@code cut} and still be reached through code that clamped, or defaulted, or
 * counted lines differently before {@code cut} ever ran.
 *
 * <p>This is the only source set in the repository that can hold both. {@code
 * plowshare-client} must not depend on {@code plowshare-server} and does not;
 * this module's tests depend on the client, which is what {@code
 * plowshare-server/build.gradle.kts} says the dependency is for.
 *
 * <h2>The fixtures have to be able to express a disagreement</h2>
 *
 * <p><b>A file of short lines proves nothing here.</b> Task 4 wrote a mutant
 * that replaced {@code Window.cut} with a hand-rolled {@code subList} — exactly
 * the drift this architecture forbids — and it passed the whole suite, because
 * every fixture was short enough that the byte ceiling never fired and a
 * {@code subList} is a correct implementation of the line allowance alone. The
 * ceiling is the branch a hand-rolled cut gets wrong, so a file that reaches it
 * is the instrument.
 *
 * <p>So the fixtures are shaped by which branch each one reaches. {@link #notes}
 * is short lines and its windows stop on {@link Span#LINES} and {@link
 * Span#END}; {@link #fat} is lines wide enough that {@link
 * Window#MAX_WINDOW_BYTES} is spent long before the line allowance, so its
 * windows stop on {@link Span#BYTES}. The sweep asserts it saw every one of
 * those, rather than trusting that the windows it lists reach them — a fixture
 * that quietly stopped exercising the ceiling would otherwise leave this file
 * passing and measuring the same thing task 4 already measured.
 *
 * <p><b>{@link #bundle} is the fixture that is not a window at all.</b> One line
 * past the ceiling on its own is the answer {@code cut} cannot give, so both
 * halves refuse it — and a refusal is where two implementations diverge most
 * easily, since one machine throwing and the other returning a frame is already
 * two different code paths agreeing only by intention. This file compares the
 * sentences, not the outcomes.
 *
 * <h2>Why there is a database in a test about arithmetic</h2>
 *
 * <p>Reluctantly, and because {@link LocalProvider} cannot be built without one:
 * its leash comes from {@link ProjectStore}, which is a final class over a
 * {@code JdbcTemplate} and has no seam to stub. Faking it would mean
 * reimplementing the row this provider re-reads on every call. The container is
 * per-class and shared by every test here, and {@code LocalProviderTest} already
 * pays the same cost for the same reason.
 *
 * <p>The client half needs nothing of the sort — a {@link Workspace} is a field
 * — and that asymmetry is the two designs and not an accident: a client's
 * workspace is set over its own socket and a server's comes out of a table an
 * operator writes.
 */
@Testcontainers
class WindowAgreementTest {

    /** The pgvector image, as {@code LocalProviderTest} uses: V1's first line is
     *  CREATE EXTENSION vector and this class runs the whole migration chain. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final String PROJECT = "payments";

    /**
     * How wide a line in {@link #fat} is, and how many of them there are.
     *
     * <p>Chosen so that {@link Window#MAX_WINDOW_BYTES} is reached after a
     * couple of dozen lines and the file is several windows long: the ceiling
     * has to fire with lines still unread, or {@code cut} reports {@link
     * Span#END} and the branch this file is about never runs.
     */
    private static final int WIDE = 4096;

    private static final int WIDE_LINES = 200;

    /** What the search below looks for inside the line no read can return. */
    private static final String WANTED = "wanted";

    private static JdbcTemplate jdbc;

    @TempDir
    Path tmp;

    /** The one tree both halves are pointed at. Real-pathed, because the server
     *  canonicalises its roots and a macOS temp directory is reached through a
     *  symlink — a comparison against the spelling {@code @TempDir} hands out
     *  would be about this host and not about either provider. */
    private Path repo;

    /** Short lines. Its windows stop on the line allowance and on the end of the
     *  file, and never on the byte ceiling. */
    private Path notes;

    /** Lines wide enough that the byte ceiling is spent first. The fixture the
     *  hand-rolled-subList mutant survives without. */
    private Path fat;

    /** One line no window can carry, with ordinary lines on either side of it.
     *  The shape that used to end the session, and the only fixture here whose
     *  windows are not all spans. */
    private Path bundle;

    private LocalProvider local;
    private ClientEnforcer client;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void twoHalvesOverOneTree() throws IOException {
        // CASCADE since V14: `memories` and `conversations` reference this
        // table now, so Postgres refuses a plain TRUNCATE of it whether or not
        // they hold anything. Nothing here holds a memory or a conversation.
        jdbc.execute("TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, projects CASCADE");
        repo = Files.createDirectory(tmp.resolve("repo")).toRealPath();
        Path server = Files.createDirectory(tmp.resolve("srv"));
        Path configFile = Files.writeString(server.resolve("plowshare.yml"),
                "a fixture, not a key");
        Path samplingDir = Files.createDirectory(server.resolve("profiles"));

        notes = write("notes.md", 500, line -> "line " + line + " of the notes");
        fat = write("fat.log", WIDE_LINES,
                line -> Character.toString('a' + line % 26).repeat(WIDE));
        // A needle at the end of the wide line, so the search below has
        // something to find that a read could never have reached.
        bundle = Files.writeString(repo.resolve("bundle.min.js"),
                "var a=1;\n" + "x".repeat(Window.MAX_WINDOW_BYTES * 2) + WANTED + "\nvar b=2;\n");

        ProjectStore store = new ProjectStore(jdbc, configFile, samplingDir,
                configFile.resolveSibling("console-token"),
                configFile.resolveSibling("exports"), configFile.resolveSibling("data"));
        store.define(PROJECT, repo, List.of());
        local = new LocalProvider(store, Home.of(PROJECT),
                List.of(new Grant(Scope.WORKSPACE, Mode.READ)));

        Workspace workspace = new Workspace();
        workspace.set(List.of(repo));
        client = new ClientEnforcer(workspace);
    }

    /**
     * The property the whole slice rests on, and the first time it is written
     * down as an assertion rather than as a paragraph.
     *
     * <p>Several windows and two files, because a half that ignored the offset,
     * or clamped the limit differently, or stopped a line early agrees with the
     * other on some single window by accident. The offsets are chosen to land
     * inside a file, on its last line, and past its end — that third one is the
     * answer a caller paging forward reaches exactly once, and it is the one a
     * provider is most likely to turn into a refusal on one side and an empty
     * span on the other.
     */
    @Test
    void the_same_window_of_the_same_file_is_the_same_span_on_both_machines() {
        Set<String> reached = new HashSet<>();

        for (Path file : List.of(notes, fat)) {
            for (Window asked : windows()) {
                Span here = local.read(file, asked);
                Span there = span(FileRequest.read("r", file.toString(), asked));

                assertEquals(here, there,
                        "the two halves cut " + asked + " of " + file.getFileName()
                                + " differently, so a file's contents depend on which machine"
                                + " the job happened to run on");
                reached.add(here.stoppedBy());
            }
        }

        // The sweep says what it exercised rather than being trusted to. Without
        // this, a fixture that stopped reaching the ceiling — a wider constant,
        // a narrower line — would leave every assertion above still passing and
        // this file measuring what task 4's in-module test already measured.
        assertEquals(Set.of(Span.LINES, Span.BYTES, Span.END), reached,
                "every limit a window can stop on was actually reached — " + reached);
    }

    /**
     * The one a hand-rolled cut fails, named on its own so that a failure says
     * which branch went.
     *
     * <p>A {@code subList} over the line allowance is a correct implementation of
     * everything the short file can see. It stops in the wrong place the moment
     * the bytes run out first, and it says {@link Span#LINES} where the truth is
     * {@link Span#BYTES} — which is not a cosmetic difference: {@code BYTES}
     * tells a caller that asking for a wider {@code limit} would come back the
     * same size, and {@code LINES} invites exactly that wasted request.
     */
    @Test
    void the_byte_ceiling_stops_both_halves_on_the_same_line_and_says_so() {
        Window all = Window.of(0, Window.MAX_WINDOW_LINES);

        Span here = local.read(fat, all);
        Span there = span(FileRequest.read("r", fat.toString(), all));

        assertEquals(Span.BYTES, here.stoppedBy(),
                "the ceiling really was what stopped this read, and not the line allowance");
        assertEquals(here, there);
        assertTrue(here.lines().size() < Window.MAX_WINDOW_LINES,
                "and it stopped well short of the line limit — " + here.lines().size());
        assertTrue(here.more(), "with the rest of the file still to come");
    }

    /**
     * The refusal, which is the third thing a window can be and the only one
     * that is not a {@link Span}.
     *
     * <p><b>Word for word on both machines, and that is a stronger claim than
     * these two halves make anywhere else.</b> Their other refusals differ on
     * purpose — one says "this server will not read more than" and the other
     * says "this client will not", because {@code MAX_FILE_BYTES} really is each
     * machine's own judgement about its own heap. Nothing in this one is: a line
     * number, a size and {@link Window#MAX_WINDOW_BYTES} are the file's and the
     * wire's, so the sentence is written once in {@code Window.LineTooWide} and
     * carried unchanged by both catches. Comparing the two messages is what
     * holds that, and a copy written out on either side fails here.
     *
     * <p>The two paths into it are as different as they can be — a thrown
     * exception on one side, a {@code REFUSED} frame on the other — which is
     * exactly why the sentence has to be the thing compared.
     */
    @Test
    void a_line_no_window_can_carry_is_refused_in_the_same_words_on_both_machines() {
        // At the wide line rather than in front of it. A window that starts
        // earlier is an ordinary window and stops before it; the test below is
        // about that pair.
        Window at = Window.of(1, 500);

        WorkspaceRefusedException here = assertThrows(WorkspaceRefusedException.class,
                () -> local.read(bundle, at),
                "the server refuses rather than handing a frame to a socket that closes on it");
        FileReply there = client.answer(FileRequest.read("r", bundle.toString(), at));

        assertEquals(FileReply.REFUSED, there.outcome(),
                "the client refuses too, and as a refusal rather than as an outage: an outage"
                        + " ends the run where a refusal costs a turn — " + there.sentence());
        assertEquals(here.getMessage(), FileWords.said(there),
                "and in the same words once the one renderer words the client's facts, because"
                        + " every fact in them belongs to the file and the wire rather than to"
                        + " either machine");
        assertTrue(here.getMessage().contains("line 1"),
                "the line it is about, counted as offset counts — " + here.getMessage());
        assertTrue(here.getMessage().contains(FileTools.GREP_NAME),
                "and the tool that still works on this file, spelled as this build registers"
                        + " it — " + here.getMessage());
    }

    /**
     * The window in front of that line is an ordinary window, and both halves
     * agree it is.
     *
     * <p><b>The refusal is about a window with no choice and not about a file
     * containing a wide line</b>, so the lines before it are readable and the
     * two halves have to be reading them the same way. Then the same offset that
     * window hands back is the one that refuses — on both machines — which is
     * what makes the pair a single rule rather than two behaviours that happen
     * to coexist.
     */
    @Test
    void the_window_in_front_of_that_line_agrees_and_the_next_one_refuses_on_both() {
        Window first = Window.of(0, 500);

        Span here = local.read(bundle, first);
        Span there = span(FileRequest.read("r", bundle.toString(), first));

        assertEquals(here, there);
        assertEquals(List.of("var a=1;"), here.lines(),
                "everything up to the line that cannot be carried");
        assertEquals(Span.BYTES, here.stoppedBy());
        assertTrue(here.more());

        Window next = Window.of(here.offset() + here.lines().size(), 500);
        WorkspaceRefusedException stopped = assertThrows(WorkspaceRefusedException.class,
                () -> local.read(bundle, next));
        FileReply alsoStopped = client.answer(FileRequest.read("r", bundle.toString(), next));

        assertEquals(FileReply.REFUSED, alsoStopped.outcome(), alsoStopped.sentence());
        assertEquals(stopped.getMessage(), FileWords.said(alsoStopped));
    }

    /**
     * The refusal names a remedy, and this is the remedy answering.
     *
     * <p><b>Asserted rather than claimed.</b> A sentence pointing at {@code
     * file_grep} is worth what {@code file_grep} is worth on this exact file, so
     * the search runs over the fixture the read just refused and finds the word
     * buried in the line no window returns. {@code Needle.MAX_LINE_CHARS} is why
     * it can, and both halves apply it, so this is an agreement test as well as
     * a redirection test.
     *
     * <p>It does not claim more than that. The line comes back cut, the search
     * answers where something is and not what the file says, and no offset ever
     * returns that line whole.
     */
    @Test
    void the_file_neither_machine_will_read_is_searchable_on_both() {
        Needle needle = new Needle(WANTED, false);

        Found here = local.grep(needle, bundle);
        Found there = client.answer(FileRequest.grep("g", bundle.toString(), needle)).found();

        assertEquals(here, there, "one search, two machines, one answer");
        assertEquals(1, here.matches().size(),
                "the word inside the unreadable line was found — " + here.matches());
        assertEquals(1, here.matches().get(0).offset(),
                "at the offset a read would have needed, if a read could have carried it");
        assertTrue(here.matches().get(0).truncated(),
                "and cut, which is the whole reason a search survives a file a window cannot");
        assertEquals(Needle.MAX_LINE_CHARS, here.matches().get(0).line().length());
    }

    /**
     * The other half of the pair, and the one written twice by hand.
     *
     * <p>{@code LocalProvider.stat} and {@code ClientEnforcer.stat} are two
     * separately typed copies of one expression — the empty span, the total, and
     * the {@link Span#LINES}-or-{@link Span#END} choice that keeps {@code
     * Window.cut}'s invariant. There is no shared method behind them to make
     * them agree, so this is the only thing that does.
     */
    @Test
    void a_stat_of_one_file_is_the_same_answer_on_both_machines() {
        for (Path file : List.of(notes, fat, empty())) {
            assertEquals(local.stat(file), span(FileRequest.stat("s", file.toString())),
                    "the two halves count " + file.getFileName() + " differently");
        }
    }

    /**
     * The windows the sweep asks for: the first line alone, the whole of the
     * short file, a window in the middle, the last line of the wide file, an
     * offset past the end of both, and the cap.
     */
    private static List<Window> windows() {
        return List.of(Window.of(0, 1), Window.of(0, 500), Window.of(7, 13),
                Window.of(WIDE_LINES - 1, 5), Window.of(500, 5), Window.of(0, 200),
                Window.of(0, Window.MAX_WINDOW_LINES));
    }

    /** One answer out of the client, with the outcome checked so that a refusal
     *  fails here rather than as a null span three lines later. */
    private Span span(FileRequest request) {
        FileReply reply = client.answer(request);
        assertEquals(FileReply.OK, reply.outcome(), reply.sentence());
        return reply.span();
    }

    private Path empty() {
        try {
            return Files.writeString(repo.resolve("nothing.txt"), "");
        } catch (IOException unwritable) {
            throw new IllegalStateException("the fixture could not be written", unwritable);
        }
    }

    private Path write(String name, int count, java.util.function.IntFunction<String> line)
            throws IOException {
        StringBuilder text = new StringBuilder();
        for (int at = 0; at < count; at++) {
            text.append(line.apply(at)).append('\n');
        }
        return Files.writeString(repo.resolve(name), text.toString());
    }
}
