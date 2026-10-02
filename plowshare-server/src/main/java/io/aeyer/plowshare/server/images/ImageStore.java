package io.aeyer.plowshare.server.images;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.server.archive.ImageDirectories;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The image tenant of the data directory: bytes in by upload, bytes out by UID,
 * and no way for an agent to reach either.
 *
 * <h2>An agent names a UID; this class is what the server attaches with</h2>
 *
 * <p>The data directory is a mandatory exclusion of every file scope, so nothing
 * an agent can call reaches this tree — {@code file_read} would be refused by
 * the fence before it got as far as {@code TextExtraction}'s "an image carries
 * no text". That is not a gap being worked around. It is the design: <b>an agent
 * never holds image bytes</b>, it names a UID, and the server puts the bytes on
 * the model call.
 *
 * <p><b>One tool in that set is a caller of this class, as of 2026-09-08, and
 * the rule above is what survived the change.</b> {@code agent_run} takes an
 * {@code images:} list so that an agent can have a callee shown a picture; until
 * that date it resolved every id against the {@code Content.Image} parts its own
 * run already carried, and this paragraph said there was deliberately no third
 * caller here. The owner decided an agent may also name an id it was
 * <em>told</em> — because every ingress but a submit-time attachment delivers
 * one as text, so the old rule was coupling to the narrowest ingress rather than
 * containing anything. So {@code AgentRunTool} calls {@link #find} and {@link
 * #dataUri}, with the {@code Home} its own {@code run} was handed.
 *
 * <p><b>What that does not do is hand an agent bytes.</b> The result of the call
 * is a part on the <em>callee's</em> model request, exactly as {@code
 * ImageController}'s upload becomes a part on the caller's; no tool answers with
 * a picture, and a tool that could ask this class for bytes an agent then reads
 * would be the byte-returning file tool the next paragraph refuses, wearing a
 * different name. The containment that replaced "there is no second caller" is
 * narrower and is asserted rather than argued: an id resolves only within the
 * tier of the run naming it, and nothing agent-facing enumerates this store, so
 * an agent can use an id it was given and cannot discover one it was not. {@code
 * NothingEnumeratesImagesTest} is what holds the second half.
 *
 * <p><b>Which is why there is no byte-returning file tool</b>, and the design
 * says so in as many words: one would bypass every text-shaped assumption below
 * {@code file_read} — {@code Window}'s line model, {@code MAX_WINDOW_BYTES},
 * the refusals — and would need its own argument about what an agent may read
 * as bytes. Naming a UID needs none, because it reaches nothing an agent could
 * not already reach: nothing.
 *
 * <h2>Two kinds of record, and only one of them has bytes here</h2>
 *
 * <p>{@link #store} is an upload: this server holds the bytes and owns them.
 * {@link #note} is a picture that is <b>already on this disk</b>, inside a
 * server-rooted project's own files, which {@code LocalProvider} has just read
 * by name — Enzo, 2026-09-08: <i>"we never store bytes on the server unless it's
 * in a server project."</i> That one writes the record and not the bytes,
 * because a second copy of a file the project already has would be a copy
 * nothing needs and retention would have to answer for.
 *
 * <p>They share the id scheme, the sidecar shape and the directory, and they
 * differ in exactly one place: {@link #dataUri} reads a held image
 * unconditionally and a named one through {@link ImageFence}, which re-reads the
 * path and re-asks the permission. {@link StoredImage#inWorkspace} is the field
 * that tells them apart and {@code ImageFence} carries the argument for why the
 * asymmetry is right rather than an oversight.
 *
 * <h2>Content-addressed, so a re-upload is not a second image</h2>
 *
 * <p>{@code img_} and 128 bits of the SHA-256 of the bytes. The house style for
 * an id is {@code MemoryIds.mint(prefix, at)}, and this one departs from it on
 * purpose: the same bytes uploaded twice are the same image, and a minted id
 * would make them two — two files, two records, and a project directory that
 * grows every time somebody re-runs the script that uploads their figures. It
 * is the property this project already values for paragraph ids, reached for the
 * same reason.
 *
 * <p><b>128 bits and not 256, which is a decision with a number behind it.</b>
 * The id appears in a task an agent is handed and in a directory a person reads,
 * so length is a cost paid by a reader. A collision would mean two different
 * images resolving to one UID; at 128 bits a store would need on the order of
 * 2^64 images before that became likely, and this store is bounded by a person
 * uploading files. It is a truncation of a cryptographic hash and not a
 * checksum: it is not offered as tamper evidence, and nothing here compares it
 * against anything.
 *
 * <p><b>The first write wins.</b> Re-uploading identical bytes returns the
 * existing record, including its original {@code at} and the filename it was
 * first stored under. Nothing is rewritten — so a project's second upload of
 * the same figure under a different name does not silently change what a UID
 * already handed to an agent resolves to.
 *
 * <h2>Nothing is written without a record</h2>
 *
 * <p>{@link StoredImage} is the record and it is written <b>before</b> the
 * bytes. That is the opposite order to {@code PayloadExport}, which writes the
 * file first, and the two are opposite because they fail in opposite
 * directions. An export's row still holds the payload until the file is on disk,
 * so a manifest line written first could describe a file that never arrived. An
 * image has no row: a record with no bytes is refused by {@link #dataUri} and
 * repaired by re-uploading the same file, whereas bytes with no record are
 * invisible to every reader here and would be an orphan nothing could account
 * for — which is the exact state the manifest property exists to make
 * impossible.
 */
public final class ImageStore {

    /**
     * A deployment that holds no images.
     *
     * <p>Not a null at the call sites, on {@code PayloadExport.NONE}'s and
     * {@code DataLayout.NONE}'s reasoning: a caller that had to ask "is there an
     * image store?" first is a null check somebody eventually forgets. This one
     * is asked the same questions and answers "nowhere" — an upload is refused
     * with a sentence naming the key to set, and a lookup finds nothing.
     *
     * <p>It is the state of every Spring context that did not come through
     * {@code PlowshareServerApplication.main}, because {@code DataLayout.NONE}
     * is.
     */
    public static final ImageStore NONE =
            new ImageStore(null, Integer.MAX_VALUE, ImageFence.NOTHING);

    /**
     * What an id looks like, and it is checked before an id is ever resolved
     * against a path.
     *
     * <p><b>This is a containment control and not a tidiness one.</b> A UID
     * arrives from outside — a URL path segment, a field in a run submission —
     * and is turned into a file name. Without this, {@code
     * ../../../../etc/passwd} is a file name too. The pattern admits exactly
     * what {@link #idFor} mints and nothing else, so there is no separator, no
     * dot and no case for a normalisation to disagree about.
     */
    private static final Pattern UID = Pattern.compile("img_[0-9a-f]{32}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImageDirectories where;
    private final int maxBytes;
    /**
     * Whether this tier may still read a file it once named — see {@link
     * ImageFence}, which owns the argument.
     *
     * <p>{@link ImageFence#NOTHING} is not merely a store that would refuse
     * every workspace image; it is a store that <b>names none</b>, because
     * {@link #note} answers null under it. A record whose path could never be
     * re-checked is a record that should not exist.
     */
    private final ImageFence fence;

    /**
     * A store that holds uploads and names nothing in a workspace.
     *
     * <p>The shape every caller had before workspace images existed, kept
     * because it is the honest one for them: {@code ImageController}'s upload
     * needs no fence, and a test about the sidecar layout should not have to
     * invent a project's roots to write one.
     *
     * @param where which directory a tier's images live in, or {@code null} for
     *     a deployment that holds none. Nothing is created here: the directory
     *     is made by the first {@link #store}
     * @param maxBytes the cap, in raw bytes — see {@link ImagesProperties}
     */
    public ImageStore(ImageDirectories where, int maxBytes) {
        this(where, maxBytes, ImageFence.NOTHING);
    }

    /**
     * The same store, able to name a file a project already has.
     *
     * @param fence what re-reads the permission when a named file is resolved.
     *     Never null; {@link ImageFence#NOTHING} is the way to say "this
     *     deployment names none"
     */
    public ImageStore(ImageDirectories where, int maxBytes, ImageFence fence) {
        this.where = where;
        this.maxBytes = maxBytes;
        this.fence = Objects.requireNonNull(fence, "fence");
    }

    /** Whether anything can be held at all. */
    public boolean keepsAnything() {
        return where != null;
    }

    /**
     * Whether this store will name a picture a project's own files hold.
     *
     * <p>Two conditions and both are wiring rather than policy: somewhere to put
     * the record, and a fence to re-ask when it is resolved. A deployment
     * missing either names nothing, and {@code LocalProvider} then refuses an
     * image exactly as it did before any of this existed — which is what keeps
     * the change additive.
     */
    public boolean namesWorkspaceFiles() {
        return where != null && fence != ImageFence.NOTHING;
    }

    /** The cap, so a caller can say the number rather than guess it. */
    public int maxBytes() {
        return maxBytes;
    }

    /**
     * Hold these bytes for this tier, and answer with the record.
     *
     * <p>Every refusal happens before anything is written, and in this order:
     * empty, then over the cap, then not an image. The cap is checked before the
     * format so that a 40 MB upload is refused on its size rather than on the
     * signature of its first eight bytes, which is the more useful of the two
     * sentences to be handed.
     *
     * @param home whose image this is
     * @param filename what the uploader called it, or null. Recorded, never
     *     consulted — {@link ImageFormat} reads the bytes
     * @param bytes the upload exactly as it arrived
     * @throws UnstorableImageException if it is empty, over the cap, or not one
     *     of the four formats a vision endpoint takes
     * @throws CallerFault if this deployment keeps no data directory. <b>This
     *     refusal used to be {@code ImageController}'s own</b>, checked inline
     *     before this method was ever called; it came here because the frame
     *     surface's rule is that a second surface calls the same service method
     *     rather than restating a check — which holds even for the one endpoint
     *     ruled to stay on HTTP, since what keeps it there is bytes on the wire
     *     and not where this decision belongs. A caller here already has bytes
     *     in hand and no fallback place exists, which is {@code
     *     DataLayout.imagesFor}'s argument; what it needs is the key to set,
     *     and that is what the sentence names
     */
    public StoredImage store(Home home, String filename, byte[] bytes) {
        if (!keepsAnything()) {
            // Before the bytes are looked at, because a server with nowhere to
            // put an image refuses every upload whatever was sent -- telling an
            // operator their format was wrong would send them off fixing the one
            // thing that is not the matter.
            throw new CallerFault(
                    "this server keeps no data directory, so it has nowhere to put an image."
                            + " Set PLOWSHARE_DATA_DIR and restart. There is deliberately no"
                            + " per-feature override for where images go");
        }
        if (bytes == null || bytes.length == 0) {
            throw new UnstorableImageException(UnstorableImageException.Reason.EMPTY,
                    "an empty upload is not an image");
        }
        if (bytes.length > maxBytes) {
            throw new UnstorableImageException(UnstorableImageException.Reason.TOO_LARGE,
                    "this image is " + bytes.length + " bytes and this server holds images up to "
                            + maxBytes + " (plowshare.images.max-bytes). The cap counts the file"
                            + " and not its base64 encoding. It is refused rather than resampled,"
                            + " because a model shown a picture nobody chose is answering about"
                            + " the server's resizing as much as about the image");
        }
        ImageFormat format = ImageFormat.of(bytes);
        if (format == null) {
            throw new UnstorableImageException(UnstorableImageException.Reason.UNRECOGNISED,
                    "these bytes are not an image this server can show a model. The formats an"
                            + " OpenAI-compatible endpoint takes are " + ImageFormat.accepted()
                            + ", and the format is read from the bytes rather than from the"
                            + " filename or the content type, both of which are the caller's to"
                            + " write");
        }
        Path directory = directoryFor(home);
        StoredImage image = new StoredImage(
                idFor(bytes), home, format, blankToNull(filename), bytes.length, Instant.now());
        Path record = directory.resolve(image.recordName());
        Path file = directory.resolve(image.fileName());
        try {
            // THE FIRST WRITE WINS, and this is where that is decided. Reading
            // the existing record rather than overwriting it keeps a UID that
            // has already been handed to an agent resolving to what it resolved
            // to before -- same bytes, but also the same recorded name and the
            // same instant.
            //
            // The `&& file` half is what lets an UPLOAD REPLACE A NAMED RECORD,
            // which is the one direction worth allowing: a named record has no
            // bytes beside it, so this test is false and the upload writes both.
            // The id is unchanged either way -- it is the hash -- and what the
            // project gains is a picture that can no longer vanish.
            if (Files.isRegularFile(record) && Files.isRegularFile(file)) {
                return read(home, record);
            }
            Files.createDirectories(directory);
            // The record before the bytes: see the class comment for why this is
            // the opposite order to PayloadExport's and why both are right.
            Files.writeString(record, json(image), StandardCharsets.UTF_8);
            Files.write(file, bytes);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(
                    "the image " + image.id() + " could not be written to " + directory,
                    unwritable);
        }
        return image;
    }

    /**
     * Name the picture in this project's own file, without copying a byte of it.
     *
     * <h2>The record and not the bytes</h2>
     *
     * <p>Enzo, 2026-09-08: <i>"we never store bytes on the server unless it's in
     * a server project — the project's own files can contain an image, file_stat
     * just points to the ID."</i> {@link #idFor} is a static hash, so identity
     * costs nothing; what is written is one sidecar, in the shape {@link #store}
     * already writes, carrying the path instead of a second copy of the file.
     * That is what keeps the volume trap shut — a hundred pictures read by name
     * are a hundred small records — and what lets {@code AgentRunTool} resolve
     * the id afterwards, which is the whole reason a record is written at all
     * rather than the id being handed back and forgotten.
     *
     * <p><b>{@link ImageFormat} is the allow-list and nothing is sniffed beyond
     * it.</b> The four formats an OpenAI-compatible {@code image_url} part takes,
     * read from the signature exactly as an upload's are; a BMP, a TIFF, an
     * SVG or a HEIC answers null here and goes back to being a file that is not
     * UTF-8 text. A store that named anything image-shaped would be holding
     * records for pictures no model in this fleet can be shown, discovered at
     * the first vision call rather than at the read that could have said so.
     *
     * <h2>Null in three states, and every one of them is ordinary</h2>
     *
     * <p>No data directory; no fence, so nothing could re-check the path later;
     * or bytes that are not one of the four. Null and not a refusal, on {@code
     * ClientEnforcer.converted}'s reasoning: the caller falls through to the
     * strict decoder and the file is turned away with the sentence it has always
     * been turned away with. A refusal invented here would be a new answer for
     * every binary in every workspace.
     *
     * <p><b>The cap is not applied and that is deliberate.</b> {@code
     * plowshare.images.max-bytes} is what this deployment will <em>hold</em>;
     * these bytes are already on its disk and are not being held. What bounds a
     * named file is {@code LocalProvider.MAX_FILE_BYTES}, which the caller has
     * already applied to get here — a file too large to read is never offered to
     * this method at all.
     *
     * @param home whose picture it is, which is the tier the id will resolve in
     * @param file the <b>canonical</b> path the caller has just read and been
     *     permitted. Canonical because it is what {@link ImageFence} is handed
     *     back, and a fence asked about a spelling other than the one that was
     *     checked is a fence answering a different question
     * @param bytes the file exactly as it was read
     * @return the record, or {@code null} if this server names no such thing
     * @throws UncheckedIOException if the record cannot be written. Loud rather
     *     than degraded to a plain read: an id handed to a model that nothing can
     *     resolve is worse than a file that would not open
     */
    public StoredImage note(Home home, Path file, byte[] bytes) {
        if (!namesWorkspaceFiles()) {
            return null;
        }
        ImageFormat format = ImageFormat.of(bytes);
        if (format == null) {
            return null;
        }
        Path directory = directoryFor(home);
        StoredImage image = new StoredImage(idFor(bytes), home, format,
                file.getFileName() == null ? null : file.getFileName().toString(),
                bytes.length, Instant.now(), file);
        Path record = directory.resolve(image.recordName());
        try {
            // THE FIRST WRITE WINS HERE TOO, and this is the arm where the two
            // kinds meet. The same bytes uploaded to this project earlier have
            // this id and a record with no path; reading that one back rather
            // than overwriting it keeps the picture resolving out of bytes this
            // server owns, which cannot vanish. It is the better of the two
            // records to keep and the rule that keeps it is the rule `store`
            // already had.
            if (Files.isRegularFile(record)) {
                return read(home, record);
            }
            Files.createDirectories(directory);
            Files.writeString(record, json(image), StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(
                    "the image " + image.id() + " could not be recorded in " + directory,
                    unwritable);
        }
        return image;
    }

    /**
     * The record for this UID in this tier, or empty.
     *
     * <p>Empty covers all of: no such id, an id for another project, and a
     * record whose bytes are missing. A caller does the same thing for each —
     * tell whoever named it that this server has no such image — and
     * distinguishing them would answer a question about one tier's contents to
     * somebody asking about another's.
     *
     * @throws IllegalArgumentException if {@code id} is not the shape {@link
     *     #idFor} mints. Raised rather than answered empty, because an id that
     *     cannot exist is a caller mistake and not a miss, and because this is
     *     the check that stops a path from being built out of one
     */
    public Optional<StoredImage> find(Home home, String id) {
        requireUid(id);
        if (where == null) {
            return Optional.empty();
        }
        Path directory = directoryFor(home);
        Path record = directory.resolve(id + ".json");
        if (!Files.isRegularFile(record)) {
            return Optional.empty();
        }
        StoredImage image;
        try {
            image = read(home, record);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the image record " + record + " could not be read", unreadable);
        }
        // A NAMED FILE IS NOT ASKED ABOUT HERE, and the asymmetry is the point.
        // For a held image the bytes are this server's and their absence means
        // somebody edited the directory, so an empty answer is right. For a
        // named one the bytes are the project's, and whether they are still
        // there is one of the three answers `dataUri` owes -- collapsing "the
        // file is gone" into "no such image" would tell a run its id was never
        // real. So the record alone is what this method answers for.
        return image.inWorkspace() || Files.isRegularFile(directory.resolve(image.fileName()))
                ? Optional.of(image)
                : Optional.empty();
    }

    /**
     * This image as a {@code data:} URI, which is the only form the wire ever
     * sees it in.
     *
     * <h2>A URI is built here and never fetched anywhere</h2>
     *
     * <p>The OpenAI content part for an image is {@code image_url}, and an
     * endpoint will take an {@code https://} one. <b>This server never sends
     * one and never resolves one.</b> A transport that fetched a URL a caller
     * supplied would be a server-side request forgery surface — an agent, or
     * anybody who could get a string into a task, choosing what this process
     * connects to — and this project has spent its containment budget
     * elsewhere deliberately.
     *
     * <p>The control is structural rather than a rule anybody has to remember:
     * the bytes come from this method, out of a directory named by a project id,
     * addressed by a UID that matched {@link #UID}. There is no code path from a
     * caller's string to an outbound connection, so there is nothing for a test
     * to be careful about — which is what {@code
     * a_message_with_an_image_never_makes_the_transport_open_a_connection}
     * asserts from the other end.
     *
     * @return {@code data:image/png;base64,…} and the like
     * @throws IllegalStateException if the record is there and the bytes are
     *     not. This is the half of "nothing is written without a record" that
     *     can still be broken by somebody editing the directory, and it is loud
     *     because the alternative is a model shown nothing and told it was shown
     *     a picture
     */
    public String dataUri(Home home, String id) {
        StoredImage image = find(home, id).orElseThrow(() -> new IllegalArgumentException(
                "this server has no image " + id + " for "
                        + (home.isGlobal() ? "the global tier" : "the project " + home.project())));
        if (image.inWorkspace()) {
            return "data:" + image.format().mediaType() + ";base64,"
                    + Base64.getEncoder().encodeToString(workspaceBytes(home, image));
        }
        Path file = directoryFor(home).resolve(image.fileName());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException unreadable) {
            throw new IllegalStateException(
                    "the image " + id + " has a record and its bytes at " + file + " could not be"
                            + " read, so there is nothing to show a model", unreadable);
        }
        return "data:" + image.format().mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * The bytes of a picture this server named and never copied — re-read, and
     * re-permitted, in that order.
     *
     * <h2>The liveness check and the permission check are one act</h2>
     *
     * <p>Enzo, 2026-09-08: <i>"path check still happens because we don't know if
     * it exists."</i> The reason is not security and the security follows
     * anyway. These bytes are the project's, so this method has to touch the path
     * to know they are still there — and touching the path is where {@code
     * FileAccess.permits} already is. An id that resolved without asking would
     * outlive the permission that produced it: after a root is unlent, after an
     * exclusion is added, after the hidden-component rule would now refuse it.
     *
     * <h2>Three outcomes and three sentences</h2>
     *
     * <p>Resolved; <b>refused</b>, which must not say gone; <b>vanished</b>,
     * which must not say refused. {@code ClientEnforcer.Vanished} is the
     * precedent and its javadoc argues the distinction, so the order here is its
     * order: <b>absence before containment</b>. A run whose picture has been
     * deleted is not a run that should be told it lacks permission, and a
     * permission complaint about a file that is not there sends whoever reads
     * the transcript to fix the wrong thing.
     *
     * <p><b>And the bytes are checked against the id.</b> An id is the hash of
     * what it names, so a path that now holds different bytes holds a different
     * picture — showing a model that one under this id would be the one failure
     * nothing downstream could detect. It is counted as <em>vanished</em> rather
     * than as a fourth state: the id was real, and the picture it named is no
     * longer there.
     */
    private byte[] workspaceBytes(Home home, StoredImage image) {
        Path file = image.path();
        if (!Files.isRegularFile(file)) {
            throw new ImageVanishedException("the image " + image.id() + " was named in a file"
                    + " this project holds, and that file is no longer there. Nothing was copied"
                    + " when it was named -- that is the design, not a fault -- so the picture is"
                    + " gone rather than misplaced");
        }
        if (!fence.permits(home, file)) {
            throw new ImageRefusedException("the image " + image.id() + " was named in a file"
                    + " this project holds, and this project may no longer read that file. The"
                    + " file is still there; what changed is the permission -- a root unlent, an"
                    + " exclusion added, a workspace moved. An id does not outlive the"
                    + " permission that produced it");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException unreadable) {
            // Between the check above and this read: the file has gone, or it
            // has stopped being readable. Vanished and not refused, on the same
            // ordering -- this clause is reached only after `permits` said yes,
            // so the one thing it cannot be is a permission this server applied.
            throw new ImageVanishedException("the image " + image.id() + " was named in a file"
                    + " this project holds, and that file could no longer be read: "
                    + unreadable.getMessage());
        }
        if (!idFor(bytes).equals(image.id())) {
            throw new ImageVanishedException("the image " + image.id() + " was named in a file"
                    + " this project holds, and that file now holds different bytes. An id is a"
                    + " hash of the picture it names, so what is there is a different picture and"
                    + " not a changed one");
        }
        return bytes;
    }

    /**
     * The UID these bytes have: {@code img_} and the first 128 bits of their
     * SHA-256, lower-case hex.
     *
     * <p>Public and static because it is the whole of the id scheme, and a
     * caller that wants to know what a file would be called should not have to
     * store it to find out.
     */
    public static String idFor(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            byte[] first = new byte[16];
            System.arraycopy(digest, 0, first, 0, 16);
            return "img_" + HexFormat.of().formatHex(first);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", impossible);
        }
    }

    /** Whether a string is even shaped like a UID, for a caller that wants to
     *  refuse one in its own words rather than catch an exception. */
    public static boolean isUid(String id) {
        return id != null && UID.matcher(id).matches();
    }

    private static void requireUid(String id) {
        if (!isUid(id)) {
            throw new IllegalArgumentException(
                    "'" + id + "' is not an image id. An id is 'img_' and 32 hexadecimal"
                            + " characters, which is what POST /v1/images answers with");
        }
    }

    private Path directoryFor(Home home) {
        if (where == null) {
            throw new IllegalStateException(
                    "this server keeps no data directory, so it holds no images. Set"
                            + " PLOWSHARE_DATA_DIR to give it somewhere to put them");
        }
        return where.forProject(home);
    }

    /**
     * The record, written by hand.
     *
     * <p>{@code PayloadExport.line}'s reasoning exactly: six fields this server
     * owns, read by {@code jq} and by people more often than by any program, and
     * an {@code ObjectMapper} would put a Jackson version between an operator's
     * directory and their ability to read it. What is borrowed from Jackson is
     * the one part that is easy to get wrong — {@link JsonStringEncoder}, which
     * is what makes a filename with a quotation mark in it come out as valid
     * JSON.
     *
     * <p>Reading is a different question and does use a mapper: an operator's
     * archive has to survive without Plowshare, and this server reading its own
     * directory is not that case.
     */
    private static String json(StoredImage image) {
        JsonStringEncoder escape = JsonStringEncoder.getInstance();
        StringBuilder text = new StringBuilder(256);
        text.append("{\"id\":\"").append(image.id()).append('"');
        text.append(",\"project\":");
        if (image.home().isGlobal()) {
            // NULL AND NOT THE STRING "global". V1: the global tier is the
            // absence of a project rather than a project named 'global', and a
            // record that spelled it as a name would be a record a project
            // actually called that could not be told apart from.
            text.append("null");
        } else {
            text.append('"').append(escape.quoteAsString(image.home().project())).append('"');
        }
        text.append(",\"format\":\"").append(image.format().declared()).append('"');
        text.append(",\"filename\":");
        if (image.filename() == null) {
            text.append("null");
        } else {
            text.append('"').append(escape.quoteAsString(image.filename())).append('"');
        }
        text.append(",\"bytes\":").append(image.bytes());
        text.append(",\"at\":\"").append(image.at()).append('"');
        // ABSENT AND NOT NULL for a held image, which is the one place this
        // record's shape had to grow. Every sidecar written before workspace
        // images existed has no `path` key at all, and `read` treats a missing
        // key and a null one alike -- so an operator's directory from last week
        // still loads, with no migration and no rewrite. A held image is the
        // default state of the field and the default state is silence.
        if (image.path() != null) {
            text.append(",\"path\":\"")
                    .append(escape.quoteAsString(image.path().toString())).append('"');
        }
        text.append("}\n");
        return text.toString();
    }

    private static StoredImage read(Home home, Path record) throws IOException {
        JsonNode node = MAPPER.readTree(Files.readString(record, StandardCharsets.UTF_8));
        String format = node.path("format").asText("");
        ImageFormat parsed = null;
        for (ImageFormat candidate : ImageFormat.values()) {
            if (candidate.declared().equals(format)) {
                parsed = candidate;
            }
        }
        if (parsed == null) {
            throw new IOException(record + " records the format '" + format
                    + "', which this server does not know; the directory has been edited or was"
                    + " written by a later version");
        }
        JsonNode filename = node.path("filename");
        JsonNode path = node.path("path");
        return new StoredImage(
                node.path("id").asText(),
                home,
                parsed,
                filename.isTextual() ? filename.asText() : null,
                node.path("bytes").asLong(),
                Instant.parse(node.path("at").asText()),
                // Missing and null read the same, which is what makes a record
                // written before this field existed a held image rather than an
                // unreadable one. `isTextual` is false for both.
                path.isTextual() ? Path.of(path.asText()) : null);
    }

    private static String blankToNull(String filename) {
        return filename == null || filename.isBlank() ? null : filename;
    }
}
