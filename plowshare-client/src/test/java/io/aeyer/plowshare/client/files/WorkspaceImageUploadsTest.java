package io.aeyer.plowshare.client.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A picture in a <em>remote</em> workspace: uploaded when the caller named the
 * file, and never otherwise.
 *
 * <h2>What this file holds up</h2>
 *
 * <p>§6a of {@code implementation rationale}
 * on the client side, which is the half where the bytes are on somebody's laptop
 * and the file channel has no operation that could carry them back. Four rules,
 * and each has a mutation that reddens exactly one test here:
 *
 * <ul>
 *   <li><b>only a named file, never a walk.</b> Making {@code sweep} pass {@code
 *       CONVERTING} reddens {@code
 *       a_walk_uploads_nothing_and_a_read_of_the_same_file_uploads_it};
 *   <li><b>{@code ImageFormat} is the allow-list and it is asked before the
 *       network.</b> Uploading first and letting the server's 415 decide reddens
 *       {@code a_format_no_vision_endpoint_takes_never_leaves_this_machine};
 *   <li><b>the leash still applies.</b> Dropping {@code permits} from {@code
 *       ClientEnforcer.permitted} reddens {@code
 *       a_picture_outside_the_workspace_is_refused_before_a_byte_leaves};
 *   <li><b>three refusals, three remedies.</b> Collapsing any two arms of {@code
 *       whyNot} reddens {@code
 *       the_three_refusals_the_server_separates_stay_three_sentences_here}.
 * </ul>
 *
 * <h2>Why the uploader is a stub and not a socket</h2>
 *
 * <p>Because the claims here are about <em>whether</em> and <em>when</em> bytes
 * leave this machine, and a counter is the only instrument that can say so. A
 * walk that named a picture and then dropped the sentence would look identical
 * from the reply — so what is asserted is the number of attempts, which is where
 * the cost actually lands. What goes over the wire when one does happen is
 * {@code HttpServerClientTest}'s, against a real socket.
 */
class WorkspaceImageUploadsTest {

    /** The window every test here asks for: the first one of the file, which is
     *  what a frame naming none gets. {@code ClientEnforcerTest} holds the same
     *  constant for the same reason. */
    private static final Window FIRST = Window.of(0, Window.MAX_WINDOW_LINES);

    /**
     * A PNG, by its signature and nothing else.
     *
     * <p>{@code ImageFormat} reads the fixed prefix each specification defines
     * and validates nothing past it — it is not a decoder — so eight bytes and a
     * tail are a PNG here exactly as a real one would be.
     */
    private static byte[] png(int tail) {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) tail};
    }

    /**
     * A BMP: an image everywhere else and not one here.
     *
     * <p>Deliberately a format a general-purpose sniffer would recognise, so
     * that a mutant replacing the allow-list with "does this look like a
     * picture" would upload it. <b>The tail is deliberately not valid UTF-8</b>:
     * a fixture whose bytes happened to decode would come back as lines and the
     * refusal this is about would never be reached.
     */
    private static final byte[] BMP =
        {'B', 'M', 0x36, 0, 0, 0, (byte) 0xFF, (byte) 0xFF};

    /**
     * An uploader that answers with an id, or with a refusal, and counts every
     * time it was asked.
     *
     * <p>The count is the point. "Nothing was uploaded" is not observable from a
     * reply — a walk that uploaded and then said nothing reads identically — so
     * the attempts are recorded before anything is decided about them.
     */
    private static final class Uploads implements ImageUploads {

        private final List<String> attempts = new ArrayList<>();
        private String id = "img_0000000000000001";
        private int status;
        private String detail;

        void refuseWith(int status, String detail) {
            this.status = status;
            this.detail = detail;
        }

        @Override
        public String name(String filename, byte[] bytes) throws IOException, Unnameable {
            attempts.add(filename);
            if (status == -1) {
                throw new IOException("Connection refused");
            }
            if (status != 0) {
                throw new Unnameable(status, detail);
            }
            return id;
        }
    }

    @TempDir
    Path tmp;

    private Path repo;
    private Path other;
    private Workspace workspace;
    private Uploads uploads;
    private ClientEnforcer enforcer;

    @BeforeEach
    void layOutADisk() throws IOException {
        repo = Files.createDirectory(tmp.resolve("repo"));
        other = Files.createDirectory(tmp.resolve("other"));
        workspace = new Workspace();
        workspace.set(List.of(repo));
        uploads = new Uploads();
        enforcer = new ClientEnforcer(workspace, uploads);
    }

    /**
     * The facts of a picture a read named: ok, no lines and no words — the
     * server writes the one line a reader is handed ({@code FileWords.named},
     * whose test holds that a client's line says the bytes were copied).
     */
    private static FileResult named(FileReply reply) {
        assertEquals(FileReply.OK, reply.outcome(), String.valueOf(reply.result()));
        assertNull(reply.sentence());
        assertNull(reply.span(), "the line is the server's to write and to cut");
        assertEquals(FileResult.NAMED, reply.result().kind());
        return reply.result();
    }

    // --- a named file ---------------------------------------------------------

    /**
     * <b>A read of a picture the caller named uploads it and answers with the
     * id.</b>
     *
     * <p>The whole of the remote half in one test. The bytes are on this machine
     * and {@code FileRequest}'s six operations have nothing that could carry
     * them back — a read answers with a {@code Window} of lines — so the picture
     * reaches a vision agent by going up over HTTP and coming back as a name, or
     * it does not reach one at all.
     *
     * <p><b>The sentence says the bytes were copied</b>, and that is not
     * decoration: the server's half says nothing was copied, because for a
     * server-rooted project nothing is. Here the workspace file stops being the
     * only copy the moment this succeeds, and a reader who is told otherwise
     * will believe editing the file changes what the id resolves to.
     */
    @Test
    void a_named_read_of_a_picture_uploads_it_and_answers_with_the_id() throws IOException {
        Path logo = Files.write(repo.resolve("logo.png"), png(1));

        FileResult said = named(enforcer.answer(FileRequest.read("r1", logo.toString(), FIRST)));

        assertEquals(List.of(logo.toString()), uploads.attempts,
                "the file was not uploaded, or something else was");
        assertEquals(FileResult.named(FileRequest.READ, logo.toString(), "png",
                "img_0000000000000001"), said);
    }

    /**
     * <b>A stat of a picture counts what a read of it would return.</b>
     *
     * <p>Not a separate decision and deliberately not a cheaper path: {@code
     * ClientEnforcer.lines} is shared, so a stat converts and names exactly as a
     * read does. §6a says {@code file_stat} names an id, and the shape is the
     * one a converted document already has — what {@code stat} counts is what
     * {@code read} would return, which has never been the file's own structure.
     */
    @Test
    void a_stat_of_a_picture_is_the_one_line_a_read_of_it_answers_with() throws IOException {
        Path logo = Files.write(repo.resolve("logo.png"), png(2));

        FileReply reply = enforcer.answer(FileRequest.stat("s1", logo.toString()));

        // Named exactly as a read names it; the server counts the one line it
        // words, as a read of it would carry (RemoteProviderTest).
        assertEquals(FileRequest.STAT, named(reply).op());
        assertEquals(1, uploads.attempts.size());
    }

    // --- only a named file, never a walk --------------------------------------

    /**
     * <b>A walk uploads nothing, and a read of the very same file uploads it.</b>
     *
     * <p>The asymmetry, asserted from both sides in one test so that it cannot
     * be half-kept. {@code ClientEnforcer.CONVERTING} owns the argument, and the
     * cost it is about is larger here than on the server: a {@code file_grep}
     * over a workspace holding two hundred pictures would push two hundred of
     * the user's files over the network, one at a time, on the socket a job is
     * blocked on — to a workspace that volunteered one file, not the tree.
     *
     * <p>It is the containment half read the other way as well: a walk that
     * minted ids would be the closest thing to an enumeration of a tree's
     * pictures an agent could reach.
     *
     * <p><b>Zero attempts, not an absent id.</b> A walk that uploaded and then
     * dropped the sentence would leave this reply identical and would still have
     * sent the bytes, so the assertion is on the counter.
     */
    @Test
    void a_walk_uploads_nothing_and_a_read_of_the_same_file_uploads_it() throws IOException {
        Path logo = Files.write(repo.resolve("logo.png"), png(3));
        Files.writeString(repo.resolve("notes.txt"), "png is mentioned here\n");

        // No path, so every root is swept. `png` matches the text file and would
        // match the naming sentence too, which is what makes this a test rather
        // than a tautology.
        FileReply swept = enforcer.answer(
                FileRequest.grep("g1", null, new Needle("png", false)));

        assertEquals(FileReply.OK, swept.outcome(), swept.sentence());
        // The real path: a walk returns what it resolved, and @TempDir hands out a
        // path under /var, which is a symlink to /private/var on this host.
        assertEquals(List.of(repo.toRealPath().resolve("notes.txt").toString()),
                swept.found().matches().stream()
                        .map(io.aeyer.plowshare.protocol.Found.Match::path).toList(),
                "the walk read the picture; a walk skips what it cannot decode and must not"
                        + " start uploading instead");
        assertEquals(List.of(), uploads.attempts,
                "the walk put a file on the network. Nothing a caller did not name a path for"
                        + " may leave this machine -- ClientEnforcer.CONVERTING owns the"
                        + " argument, and here the cost is somebody's pictures, not a sidecar");

        // The same file, named. This is the seam the walk deliberately does not
        // cross, and it is stated rather than hidden: a walk does not find what a
        // search of the same file by name would.
        FileResult said = named(enforcer.answer(FileRequest.read("r1", logo.toString(), FIRST)));
        assertEquals("img_0000000000000001", said.image());
        assertEquals(List.of(logo.toString()), uploads.attempts);
    }

    // --- the allow-list is asked before the network ---------------------------

    /**
     * <b>A format no vision endpoint takes is not uploaded at all, and keeps the
     * refusal it has always had.</b>
     *
     * <p>{@code ImageFormat} is the allow-list and it is asked <em>before</em>
     * anything leaves this machine. The alternative — upload it and let the
     * server's 415 decide — would ship a user's arbitrary binaries off their
     * disk to find out, which is a worse thing than the round trip it saves.
     *
     * <p><b>The facts are asserted whole.</b> They are the answer every binary
     * in every workspace still gets, so a change to them is a change to what
     * thousands of reads say — and a mutant that replaced the allow-list with a
     * general sniff would pass a looser assertion by producing a different
     * answer for a file that is genuinely a picture.
     */
    @Test
    void a_format_no_vision_endpoint_takes_never_leaves_this_machine() throws IOException {
        Path bitmap = Files.write(repo.resolve("scan.bmp"), BMP);

        FileReply reply = enforcer.answer(FileRequest.read("r1", bitmap.toString(), FIRST));

        assertEquals(FileReply.REFUSED, reply.outcome());
        assertEquals(FileResult.notText(FileRequest.READ, bitmap.toString(), FileResult.NOT_UTF8),
                reply.result());
        assertEquals(List.of(), uploads.attempts,
                "a format outside the allow-list was put on the network to be refused there");
    }

    /**
     * <b>A picture outside the workspace is refused before a byte leaves.</b>
     *
     * <p>An image is not a way around {@code permits}. The client reads the file
     * under the leash exactly as it does for any other read — {@code
     * ClientEnforcer.permitted} runs before {@code decode} is entered at all —
     * and the ordering is what makes this a containment test rather than a
     * spelling test: a check that ran after the read would already have put the
     * file on the network.
     */
    @Test
    void a_picture_outside_the_workspace_is_refused_before_a_byte_leaves() throws IOException {
        Path outside = Files.write(other.resolve("private.png"), png(4));

        FileReply reply = enforcer.answer(FileRequest.read("r1", outside.toString(), FIRST));

        assertEquals(FileReply.REFUSED, reply.outcome());
        assertEquals(FileResult.OUTSIDE, reply.result().reason());
        assertEquals(List.of(), uploads.attempts,
                "a file this session may not read was uploaded anyway; the leash is the fence"
                        + " for a picture exactly as it is for a line of text");
    }

    // --- three refusals, three remedies ---------------------------------------

    /**
     * <b>The three refusals {@code ImageController} separates stay three
     * sentences here.</b>
     *
     * <p>They are separated on the server because the remedies are: convert it,
     * shrink it, or tell whoever runs the server. A client that collapsed them
     * would undo that at the last hop, where the reader is a model with one turn
     * to decide what to do: told to convert a perfectly good PNG that was merely
     * too big it will convert it and be refused again, and told to shrink a file
     * a deployment structurally cannot hold it will shrink it forever.
     *
     * <p><b>The 400 is the one that is not the caller's fault at all</b>, and its
     * sentence has to say so — a server with no data directory gives the same
     * answer for every file and every path, so a model that goes looking for a
     * different picture is spending turns on a question that has no answer.
     *
     * <p>Pairwise distinctness is asserted as well as the remedies. A mutant
     * that pointed two arms at one string would still contain each substring
     * this test looks for if the shared string were the union of them.
     */
    @Test
    void the_three_refusals_the_server_separates_stay_three_facts_here() throws IOException {
        byte[] picture = png(5);
        Path logo = Files.write(repo.resolve("logo.png"), picture);
        FileRequest read = FileRequest.read("r1", logo.toString(), FIRST);

        // The status and the server's own account, carried rather than
        // paraphrased: the status decides the remedy, which FileWords words,
        // and the server decides the fact.
        for (int status : List.of(415, 413, 400)) {
            uploads.refuseWith(status, "the server's account of " + status);
            FileReply reply = enforcer.answer(read);

            assertEquals(FileReply.REFUSED, reply.outcome());
            assertNull(reply.sentence());
            assertEquals(FileResult.imageRefused(FileRequest.READ, logo.toString(), "png", status,
                    "the server's account of " + status, picture.length), reply.result());
        }
    }

    /**
     * <b>A server that could not be reached is not a file that is not text.</b>
     *
     * <p>{@code ServerClient}'s standing distinction, one layer down: a 413 is a
     * fact about the file and an unreachable server is a fact about the network.
     * Letting this fall through to the strict decoder would answer "not UTF-8
     * text" about a PNG, which is a true fact about the bytes and a useless one
     * about the file — the reader goes off looking at an encoding for a fault
     * that is on another machine.
     *
     * <p>Refused and not unavailable: {@code Vanished} ends the run, and this
     * machine's disk is fine.
     */
    @Test
    void a_server_that_could_not_be_reached_says_so_and_does_not_blame_the_bytes()
            throws IOException {
        Path logo = Files.write(repo.resolve("logo.png"), png(6));
        uploads.refuseWith(-1, null);

        FileReply reply = enforcer.answer(FileRequest.read("r1", logo.toString(), FIRST));

        assertEquals(FileReply.REFUSED, reply.outcome());
        assertEquals(FileResult.IMAGE_UNREACHED, reply.result().reason());
        assertEquals("png", reply.result().format());
    }

    // --- the two orderings that are decisions ---------------------------------

    /**
     * <b>A client with no uploader answers exactly what it answered before any
     * of this existed.</b>
     *
     * <p>{@code ImageUploads.NONE} is {@code ImageStore.NONE} one module over,
     * and this is what makes the change additive rather than a new answer every
     * session gets. A deployment whose server holds no images, and every test in
     * this package that builds a bare enforcer, go on reading a PNG as not UTF-8
     * text.
     */
    @Test
    void a_client_wired_with_no_uploader_refuses_a_picture_as_it_always_has() throws IOException {
        Path logo = Files.write(repo.resolve("logo.png"), png(7));

        FileReply reply = new ClientEnforcer(workspace)
                .answer(FileRequest.read("r1", logo.toString(), FIRST));

        assertEquals(FileReply.REFUSED, reply.outcome());
        assertEquals(FileResult.notText(FileRequest.READ, logo.toString(), FileResult.NOT_UTF8),
                reply.result());
    }

    /**
     * <b>A picture is named before a converter that claims the same bytes gets
     * to read it.</b>
     *
     * <p>The ordering inside {@code decode}, and it is a rule rather than an
     * accident of which line was written first. Nothing in the shipped converter
     * set overlaps {@code ImageFormat} today — PDF is the only one — so both
     * orders give this build the same answers, and <b>that is exactly why this
     * test exists</b>: without it the decision is one no mutant can kill, and the
     * day somebody adds the obvious converter (an OCR pass over a scan; §7 of the
     * ingress note names the two sentences it would falsify) the wrong order
     * silently turns a named picture back into a guess at its text.
     *
     * <p>A picture is named and not read. That is the whole of §6a, and it must
     * not depend on which converters a build happens to ship.
     */
    @Test
    void a_picture_is_named_even_where_a_converter_would_have_claimed_it() throws IOException {
        Path logo = Files.write(repo.resolve("scan.png"), png(8));
        ClientEnforcer greedy = new ClientEnforcer(workspace,
                new Conversions(List.of(new PretendsToReadPictures()), 1024), uploads);

        FileResult said = named(greedy.answer(FileRequest.read("r1", logo.toString(), FIRST)));

        assertEquals("img_0000000000000001", said.image(),
                "a converter got in front of the picture: " + said);
        assertEquals(List.of(logo.toString()), uploads.attempts);
    }

    /** A converter that claims every PNG, which nothing in this build does and
     *  something in a later one might. Only the ordering test uses it. */
    private static final class PretendsToReadPictures implements Converter {

        @Override
        public String format() {
            return "PNG";
        }

        @Override
        public boolean recognises(byte[] bytes) {
            return io.aeyer.plowshare.protocol.ImageFormat.of(bytes)
                    == io.aeyer.plowshare.protocol.ImageFormat.PNG;
        }

        @Override
        public String toText(byte[] bytes) {
            return new String("what a converter would have said".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }
    }
}
