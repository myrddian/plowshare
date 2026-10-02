package io.aeyer.plowshare.server.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * A picture inside a server-rooted project's own files: named on a read, never
 * copied, and resolved by touching the path again.
 *
 * <h2>What this file holds up</h2>
 *
 * <p>§6a of {@code implementation rationale},
 * as behaviour. Four rules, and each has a mutation that reddens exactly one
 * test here:
 *
 * <ul>
 *   <li><b>only a named file, never a walk.</b> {@code LocalProvider.NAMING} and
 *       {@code AS_IT_LIES} own the argument, which is {@code
 *       ClientEnforcer.CONVERTING}'s word for word: a walk must not pay a
 *       per-file cost the caller did not choose, on the socket a job is blocked
 *       on. Making the walk name too reddens {@code
 *       a_walk_names_nothing_and_a_read_of_the_same_file_does};
 *   <li><b>{@link ImageFormat} is the allow-list.</b> Sniffing anything
 *       image-shaped instead reddens {@code
 *       a_format_this_server_cannot_show_a_model_is_still_not_utf8_text};
 *   <li><b>no copy for a server project.</b> Writing bytes beside the record
 *       reddens {@code naming_a_picture_writes_a_record_and_not_a_second_copy};
 *   <li><b>resolution re-reads the path and re-asks {@code permits}.</b>
 *       Dropping the fence call reddens {@code
 *       an_id_stops_resolving_when_an_exclusion_covers_the_file_it_named}.
 * </ul>
 *
 * <h2>Why this test has a database in it</h2>
 *
 * <p>{@code LocalProviderTest}'s reason, and one more that is this file's own.
 * The fence a resolution re-asks has to be the <em>same</em> expression the read
 * was allowed through — {@code ProjectStore.fence} — or an id could outlive a
 * permission while every test stayed green. A stubbed fence would satisfy that
 * by construction, so the binding under test is {@link
 * ImagesConfig#fenceOver}, over a real {@link ProjectStore}, over a real
 * {@code projects} row an operator can edit.
 *
 * <p>The end-to-end half — an id named here reaching a vision agent through
 * {@code agent_run}, and the two failing resolutions reaching a model as two
 * different sentences — is {@code DelegationTest}'s, because that is where a
 * tool result can be read.
 */
@Testcontainers
class WorkspaceImagesTest {

    /** The pgvector image, as {@code ProjectStoreTest} uses: V1's first line is
     *  CREATE EXTENSION vector and this class runs the whole migration chain. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Grant READ = new Grant(Scope.WORKSPACE, Mode.READ);

    private static final Window FIRST = Window.of(0, Window.MAX_WINDOW_LINES);

    /**
     * A PNG, by its signature and nothing else.
     *
     * <p>{@link ImageFormat} reads the fixed prefix each specification defines
     * and validates nothing past it — it is not a decoder — so eight bytes and a
     * tail are a PNG here exactly as a real one would be, and the tail is what
     * makes two fixtures in this file different pictures.
     */
    private static byte[] png(int tail) {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) tail};
    }

    /**
     * A BMP: an image everywhere else and not one here.
     *
     * <p>{@link ImageFormat}'s four are what an OpenAI-compatible {@code
     * image_url} part takes, and this is deliberately one of the formats a
     * sniffer would recognise and this server refuses — so a mutant that
     * replaced the allow-list with "does this look like a picture" would name
     * it, and one test below would go red.
     *
     * <p><b>The tail is deliberately not valid UTF-8.</b> A file that is not one
     * of the four falls through to the strict decoder, so a fixture whose bytes
     * happened to decode would come back as <em>lines</em> and the refusal this
     * test is about would never be reached — measured: {@code 'B', 'M', 0x36}
     * and five nulls is perfectly good UTF-8, and the first draft of this
     * fixture was exactly that.
     */
    private static final byte[] BMP =
        {'B', 'M', 0x36, 0, 0, 0, (byte) 0xFF, (byte) 0xFF};

    private static JdbcTemplate jdbc;

    @TempDir
    Path tmp;

    /** The directory the project points at. */
    private Path repo;

    /** Where this deployment keeps what it owns, images included. Disjoint from
     *  the workspace, which is the whole point: the record goes here and the
     *  picture stays there. */
    private Path dataDir;

    private ProjectStore projects;
    private ImageStore images;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void freshProjects() throws IOException {
        jdbc.execute("TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans,"
                + " digest_revisions, memory_provenance, projects CASCADE");
        Path real = tmp.toRealPath();
        repo = Files.createDirectory(real.resolve("repo"));
        Path server = Files.createDirectory(real.resolve("srv"));
        // Not the names the server ships, for ProjectStoreTest's reason.
        Path configFile = Files.writeString(server.resolve("plowshare.yml"), "a fixture");
        Path samplingDir = Files.createDirectory(server.resolve("profiles"));
        dataDir = Files.createDirectory(real.resolve("owned"));
        projects = new ProjectStore(jdbc, configFile, samplingDir,
                server.resolve("console-token"), server.resolve("exports"), dataDir);
        // The production binding and not a copy of it -- see the class comment.
        // The tier names the directory with the project's own name where
        // ImageDirectories would put its id, which is ImageStoreTest's fixture.
        images = new ImageStore(
                home -> home.isGlobal()
                        ? dataDir.resolve("images/global")
                        : dataDir.resolve("images").resolve(home.project()),
                4096,
                ImagesConfig.fenceOver(projects));
    }

    private LocalProvider provider() {
        return new LocalProvider(projects, Home.of("payments"), List.of(READ), images);
    }

    /** Where this fixture's records land, which is the one directory that has to
     *  hold a record and no picture. */
    private Path recordsDir() {
        return dataDir.resolve("images").resolve("payments");
    }

    /** The id in a naming answer, so a test asserts on the id the tool actually
     *  said rather than on one it computed for itself. */
    private static String idIn(String said) {
        java.util.regex.Matcher found =
                java.util.regex.Pattern.compile("img_[0-9a-f]{32}").matcher(said);
        assertTrue(found.find(), "no image id in: " + said);
        return found.group();
    }

    // --- a named file ------------------------------------------------------------

    /**
     * <b>A picture a model asked for by name is answered with an id, where it
     * used to be refused.</b>
     *
     * <p>The refusal this replaces is {@code LocalProvider.decode}'s "is not
     * UTF-8 text; these tools read text files only", which is a true fact about
     * the bytes and a useless one about the file: the server can perfectly well
     * have this picture looked at, by an agent that can see, and until now had
     * no way to say so.
     *
     * <p>Asserted on the id being <em>in</em> the answer and on the answer being
     * lines rather than bytes: naming is the whole of what happens here, and a
     * read that had attached the picture instead would fail §4 of the design note
     * without failing anything else.
     */
    @Test
    void a_picture_a_model_named_is_answered_with_an_id() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(1));

        Span answer = provider().read(logo, FIRST);

        assertEquals(1, answer.totalLines(), "naming is one line: " + answer.lines());
        String said = answer.lines().get(0);
        assertTrue(said.contains("is a png image"), said);
        assertTrue(said.contains(logo.toString()), said);
        assertEquals(ImageStore.idFor(png(1)), idIn(said),
                "the id a model is handed is not the hash of the bytes it named");
        // NAME, DO NOT ATTACH. The bytes must not be in what the model reads --
        // a base64 payload here would be the volume trap §4 exists to shut,
        // arriving through the one tool a glob can be looped over.
        assertFalse(said.contains("base64"), said);
    }

    /**
     * <b>A stat of the same file counts what a read would return, which is the
     * naming.</b>
     *
     * <p>§6a says {@code file_stat} names an id and stores nothing, and the two
     * halves are one method: {@code LocalProvider.lines} is what {@code read} and
     * {@code stat} share, so a stat cannot see something a read cannot. What
     * this pins is that the invariant survived — {@code stat} counts lines and
     * the naming is one line, so a caller planning a read is told the truth
     * about what it would get.
     */
    @Test
    void a_stat_of_a_picture_names_it_too_and_counts_one_line() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(2));

        Span stat = provider().stat(logo);

        assertEquals(1, stat.totalLines());
        assertEquals(List.of(), stat.lines(), "a stat carries no lines, only the count");
        assertEquals(ImageStore.idFor(png(2)),
                idIn(provider().read(logo, FIRST).lines().get(0)));
    }

    /**
     * <b>A walk names nothing, and a read of the very same file names an id.</b>
     *
     * <p>The asymmetry, asserted from both sides in one test so that it cannot be
     * half-kept. {@code LocalProvider.NAMING} owns the argument and it is
     * {@code ClientEnforcer.CONVERTING}'s, unchanged: a walk over a workspace
     * holding two hundred pictures would turn one {@code file_grep} into two
     * hundred hashes and two hundred sidecar writes nobody asked for, and it
     * would do it on the socket a job is blocked on. <b>A file the caller named
     * is a cost the caller chose.</b>
     *
     * <p>It is the containment half as well, read the other way: a walk that
     * minted ids would be the closest thing to an enumeration of a tree's
     * pictures that an agent could reach, which is exactly what {@code
     * NothingEnumeratesImagesTest} exists to keep out of reach.
     *
     * <p><b>Asserted on the directory and not only on the answer.</b> A walk that
     * named the file and then dropped the sentence would look identical from the
     * grep result and would still have written the record — so the mutation is
     * caught by what is on disk, which is where the cost actually lands.
     */
    @Test
    void a_walk_names_nothing_and_a_read_of_the_same_file_does() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(3));
        Files.writeString(repo.resolve("notes.txt"), "png is mentioned here\n");

        // The walk: no path, so every root is swept. `png` matches the text file
        // and would match the naming sentence too, which is what makes this a
        // test and not a tautology.
        Found swept = provider().grep(new Needle("png", false), null);

        assertEquals(List.of(repo.resolve("notes.txt").toString()),
                swept.matches().stream().map(Found.Match::path).toList(),
                "the walk read the picture; a walk skips what it cannot decode and must not"
                        + " start naming instead");
        assertFalse(Files.exists(recordsDir()),
                "the walk wrote a record. Nothing a model did not name a path for may cost a"
                        + " hash and a write -- LocalProvider.NAMING owns the argument");

        // The same file, named. This is the seam the walk deliberately does not
        // cross, and it is stated in LocalProvider.NAMING rather than hidden: a
        // walk does not find what a search of the same file by name would.
        String said = provider().read(logo, FIRST).lines().get(0);
        assertEquals(ImageStore.idFor(png(3)), idIn(said));
    }

    /**
     * <b>A format this server cannot show a model is refused in the words it has
     * always been refused in.</b>
     *
     * <p>{@link ImageFormat} is the allow-list and there is no sniffing past it:
     * PNG, JPEG, GIF and WebP, because those are what an OpenAI-compatible
     * {@code image_url} part takes. A BMP is an image to a person and to every
     * general-purpose sniffer, and naming one would mint an id whose only
     * possible use is a vision call that fails a hop away from anything that
     * could explain it. Codex supports a fixed set and refuses BMP, TIFF, SVG
     * and HEIC; this is that idea, and it is the only one taken.
     *
     * <p><b>The sentence is asserted whole and not by {@code contains}.</b> It is
     * the answer every binary in every workspace still gets, so a change to it is
     * a change to what thousands of reads say — and a mutant that replaced the
     * allow-list with a general sniff would pass a looser assertion by producing
     * a different message for a file that is genuinely a picture.
     */
    @Test
    void a_format_this_server_cannot_show_a_model_is_still_not_utf8_text() throws IOException {
        projects.define("payments", repo, List.of());
        Path bitmap = Files.write(repo.resolve("scan.bmp"), BMP);

        WorkspaceRefusedException refused = assertThrows(WorkspaceRefusedException.class,
                () -> provider().read(bitmap, FIRST));

        assertEquals("path " + bitmap + " is not UTF-8 text; these tools read text files only",
                refused.getMessage());
        assertFalse(Files.exists(recordsDir()), "a format outside the allow-list was recorded");
    }

    /**
     * <b>Naming a picture writes a record and not a second copy of the file.</b>
     *
     * <p>Enzo, 2026-09-08: <i>"we never store bytes on the server unless it's in
     * a server project — the project's own files can contain an image, file_stat
     * just points to the ID."</i> {@code ImageStore.idFor} is a static hash, so
     * identity costs nothing; what the record buys is resolution, and that is the
     * only reason it is written at all.
     *
     * <p><b>Asserted as "one file in that directory, and it is the record"</b>
     * rather than as "the bytes file is absent". The second passes for a store
     * that wrote the copy under some other name, and the volume claim this rests
     * on — a hundred pictures read by name are a hundred small records — is about
     * how much is written, not about what one path is called.
     */
    @Test
    void naming_a_picture_writes_a_record_and_not_a_second_copy() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(4));
        String id = idIn(provider().read(logo, FIRST).lines().get(0));

        try (var held = Files.list(recordsDir())) {
            assertEquals(List.of(id + ".json"),
                    held.map(each -> each.getFileName().toString()).sorted().toList(),
                    "something besides the record was written; the workspace file is meant to"
                            + " be the only copy of the picture");
        }
        String record = Files.readString(recordsDir().resolve(id + ".json"));
        assertTrue(record.contains("\"path\":\"" + logo + "\""),
                "the record does not point at the file it named: " + record);

        // And it still resolves, out of the project's own bytes.
        assertTrue(images.dataUri(Home.of("payments"), id).startsWith("data:image/png;base64,"));
    }

    // --- resolving one, afterwards -----------------------------------------------

    /**
     * <b>An id named out of a file stops resolving when the project stops
     * reaching that file.</b>
     *
     * <p>The rule {@link ImageFence} exists for. An uploaded image needs no such
     * check — the data directory is a mandatory exclusion, so {@code home} plus an
     * unguessable id is its whole boundary — and a named one does, because a path
     * {@code permits} allowed once would otherwise stay resolvable after a root
     * was unlent, after an exclusion was added, after the hidden-component rule
     * would now refuse it. Content addressing sharpens it: the same bytes
     * anywhere are the same id, so an id is globally meaningful while permission
     * is per path.
     *
     * <p><b>Refused, and it must not say gone.</b> The file is sitting right
     * there and an operator can put the root back; telling a run the picture had
     * disappeared would send whoever reads the transcript looking for a file that
     * never moved. The two are separate types with no shared supertype for
     * exactly this reason, which is {@code ClientEnforcer.Vanished}'s argument.
     *
     * <p><b>And nothing comes back.</b> Asserted, because a fence that refused in
     * words and returned the picture anyway is the failure that produces no
     * error at all.
     */
    @Test
    void an_id_stops_resolving_when_an_exclusion_covers_the_file_it_named() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(5));
        Home payments = Home.of("payments");
        String id = idIn(provider().read(logo, FIRST).lines().get(0));
        // It resolved a moment ago, which is what makes the next line about the
        // exclusion rather than about a lookup that never worked.
        assertTrue(images.dataUri(payments, id).contains("base64"));

        // The operator narrows what this project reaches. Nothing on disk moves.
        projects.define("payments", repo, List.of(repo.resolve("logo.png")));

        ImageRefusedException refused = assertThrows(ImageRefusedException.class,
                () -> images.dataUri(payments, id));
        assertTrue(refused.getMessage().contains("may no longer read that file"),
                refused.getMessage());
        assertFalse(refused.getMessage().contains("gone"),
                "a refusal said the picture was gone; it is still there, and the two states"
                        + " need two sentences -- see §6a of the design note");
        assertFalse(refused.getMessage().contains("base64"), "the refusal carried the picture");
        assertTrue(Files.isRegularFile(logo), "the fixture moved the file; it must not");
    }

    /**
     * <b>The same, for the whole workspace being taken away.</b>
     *
     * <p>An exclusion is the narrow case and an operator running {@code forget}
     * is the wide one, and they must not need different code. This is the state
     * {@code LocalProvider}'s per-call re-read of the leash already honours for a
     * file read — "a revoked workspace must actually be revoked" — arriving at
     * the other door, which is the one an id opens.
     */
    @Test
    void an_id_stops_resolving_when_the_project_is_forgotten() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(6));
        Home payments = Home.of("payments");
        String id = idIn(provider().read(logo, FIRST).lines().get(0));

        projects.forget("payments");

        assertThrows(ImageRefusedException.class, () -> images.dataUri(payments, id));
    }

    /**
     * <b>A file that has been deleted is gone, and is not called refused.</b>
     *
     * <p>Enzo's reason for the path check, which is the better one and is not
     * about security: <i>"path check still happens because we don't know if it
     * exists."</i> These bytes are the project's and this server never copied
     * them, so resolution has to touch the path to know they are still there —
     * and touching the path is where the fence already is. The liveness check and
     * the permission check are one act, and this is the half that is liveness.
     *
     * <p><b>Absence before containment</b>, which is {@code
     * ClientEnforcer.Vanished}'s order and its argument: a session whose
     * workspace has been deleted is not a session that should be told a path is
     * outside every root. Here the permission is still perfectly good, so a
     * refusal would be false as well as unhelpful.
     */
    @Test
    void a_picture_whose_file_was_deleted_is_gone_and_not_refused() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(7));
        Home payments = Home.of("payments");
        String id = idIn(provider().read(logo, FIRST).lines().get(0));

        Files.delete(logo);

        ImageVanishedException gone = assertThrows(ImageVanishedException.class,
                () -> images.dataUri(payments, id));
        assertTrue(gone.getMessage().contains("no longer there"), gone.getMessage());
        assertFalse(gone.getMessage().contains("refus"),
                "a picture that has gone was called refused; there is no permission to fix");
    }

    /**
     * <b>When the file has gone <em>and</em> the project no longer reaches it,
     * the answer is gone.</b>
     *
     * <p>The order of the two checks, which is the decision {@code
     * ImageStore.workspaceBytes} argues hardest and the only one the two tests
     * above cannot break: each of them puts the store in a state where exactly
     * one check fails, so either order produces the same sentence, and the
     * mutant that swaps them survives both.
     *
     * <p>{@code ClientEnforcer.Vanished} is the precedent and owns the argument
     * — it is raised <em>before</em> containment, because <i>"a session whose
     * workspace has been deleted is not a session that should be told a path is
     * 'outside every root'"</i>. Same here: an operator whose picture and whose
     * root are both gone is told the picture is gone, because that is the fact
     * that explains the other one. A refusal would send them to re-lend a root
     * and find nothing there.
     */
    @Test
    void a_picture_gone_from_a_project_that_also_lost_the_root_is_gone() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(12));
        Home payments = Home.of("payments");
        String id = idIn(provider().read(logo, FIRST).lines().get(0));

        Files.delete(logo);
        projects.forget("payments");

        assertThrows(ImageVanishedException.class, () -> images.dataUri(payments, id),
                "with both wrong, the containment answer won; absence is asked first, on"
                        + " ClientEnforcer.Vanished's argument");
    }

    /**
     * <b>A path that now holds different bytes holds a different picture.</b>
     *
     * <p>An id is the hash of what it names, so this is the one failure nothing
     * downstream could detect: a model shown the wrong image under a right-looking
     * id answers confidently about a picture nobody meant. Counted as vanished
     * rather than as a fourth state — the id was real, and what it named is no
     * longer there — which is why the sentence says what it says.
     */
    @Test
    void a_file_that_now_holds_other_bytes_no_longer_holds_that_picture() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(8));
        Home payments = Home.of("payments");
        String id = idIn(provider().read(logo, FIRST).lines().get(0));

        Files.write(logo, png(9));

        ImageVanishedException gone = assertThrows(ImageVanishedException.class,
                () -> images.dataUri(payments, id));
        assertTrue(gone.getMessage().contains("different bytes"), gone.getMessage());
    }

    /**
     * <b>Naming the same file twice is the same id and one record.</b>
     *
     * <p>Content addressing, arriving where the volume argument needs it: a model
     * that reads a picture, forgets, and reads it again has cost the server one
     * record, and the id it was handed the second time is the one it was handed
     * the first. It is the property {@code ImageStore.store}'s first-write-wins
     * rule already has for uploads, and it holds across the two kinds because
     * both are the hash of the same bytes.
     */
    @Test
    void naming_the_same_file_twice_is_one_record_and_one_id() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(10));

        String first = idIn(provider().read(logo, FIRST).lines().get(0));
        String again = idIn(provider().read(logo, FIRST).lines().get(0));

        assertEquals(first, again);
        try (var held = Files.list(recordsDir())) {
            assertEquals(1, held.count());
        }
    }

    /**
     * <b>A deployment that keeps no data directory refuses a picture exactly as
     * it always did.</b>
     *
     * <p>The change is additive or it is a new answer every deployment gets
     * whether it wanted one or not. {@link ImageStore#NONE} names nothing, and so
     * does any store with no {@link ImageFence} — {@code ImageStore.note} answers
     * null under either — so the file falls through to the strict decoder and
     * gets the sentence it has always had.
     */
    @Test
    void a_server_that_holds_no_images_refuses_a_picture_as_before() throws IOException {
        projects.define("payments", repo, List.of());
        Path logo = Files.write(repo.resolve("logo.png"), png(11));

        LocalProvider nameless =
                new LocalProvider(projects, Home.of("payments"), List.of(READ), ImageStore.NONE);

        assertEquals("path " + logo + " is not UTF-8 text; these tools read text files only",
                assertThrows(WorkspaceRefusedException.class,
                        () -> nameless.read(logo, FIRST)).getMessage());
    }
}
