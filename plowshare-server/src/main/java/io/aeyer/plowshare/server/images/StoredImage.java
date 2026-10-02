package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageFormat;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * The record every stored image has, and without which no image is stored.
 *
 * <h2>The property promoted from {@code PayloadExport}, and the file convention
 * that was not</h2>
 *
 * <p>The export manifest's argument is that <b>nothing is written into the data
 * directory without a record saying what it is and whose it is</b> — otherwise
 * retention and orphan cleanup have nothing to reason over and "what is in
 * here" is unanswerable. That property is promoted here in full: {@link
 * ImageStore} writes this record beside the bytes and a byte file with no record
 * is not an image, merely a file.
 *
 * <p><b>The {@code manifest.jsonl} convention itself is deliberately not
 * promoted, and this is the one place the two designs part.</b> An export is
 * written and never read back by this server; a manifest is therefore the right
 * shape, being a list that only ever grows and only ever streams. An image is
 * <em>looked up by its id</em> on the path that attaches it to a model call, so
 * a single growing file per project would make every attachment a scan of every
 * image the project has ever held, and two concurrent uploads an append race
 * over the one file the lookup depends on. So the record is a sidecar — {@code
 * &lt;id&gt;.json} beside {@code &lt;id&gt;.&lt;ext&gt;} — which makes a lookup
 * one read, a listing one directory listing, and a re-upload of identical bytes
 * an overwrite of a file with the same content rather than a duplicate line.
 *
 * <p>What is kept from the manifest is everything else: JSON a person can read
 * with no Plowshare in the picture, written by hand rather than through an
 * {@code ObjectMapper} so that an operator's archive does not depend on a
 * Jackson version, and the project's <em>name</em> on the record even though the
 * directory is named by its id — because a person reading the file wants the
 * word they typed, and the id is already in the path.
 *
 * @param id the UID, content-addressed. See {@link ImageStore#idFor}
 * @param home whose image it is: a project, or the global tier. Carried as a
 *     {@link Home} and not a {@code Long} for {@code ExportDirectories}'
 *     reason — the global tier has no id, and an id-shaped field would have to
 *     say so with a null
 * @param format what the bytes are, sniffed and never declared — see {@link
 *     ImageFormat}
 * @param filename what the uploader called it, or {@code null} if they sent no
 *     name. <b>Never used to decide anything</b>: it is here so a person
 *     listing a directory of {@code img_…} files can tell which is which, which
 *     is the whole of what a declared name is good for once the format has been
 *     read from the bytes
 * @param bytes how many bytes the image is, <b>raw and not base64</b>. The
 *     encoded payload on the wire is about 4/3 of this; {@link
 *     ImagesProperties#getMaxBytes} argues which of the two the cap measures
 *     and why
 * @param at when it was stored. The first write wins and a re-upload of
 *     identical bytes does not move it — see {@link ImageStore#store}
 * @param path where the bytes are, for an image this server <b>named</b> rather
 *     than stored, or {@code null} for one it holds. See {@link #inWorkspace}
 */
public record StoredImage(
        String id, Home home, ImageFormat format, String filename, long bytes, Instant at,
        Path path) {

    public StoredImage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(at, "at");
        if (bytes <= 0) {
            throw new IllegalArgumentException(
                    "an image of " + bytes + " bytes is not an image; ImageStore refuses an empty"
                            + " upload before it gets this far");
        }
    }

    /**
     * A record for bytes this server holds, which is what an upload writes.
     *
     * <p>Kept so that {@link ImageStore} has one place that says "no path" and
     * the six-argument shape every existing caller wrote stays readable.
     */
    public StoredImage(
            String id, Home home, ImageFormat format, String filename, long bytes, Instant at) {
        this(id, home, format, filename, bytes, at, null);
    }

    /**
     * Whether the bytes live in a project's own files rather than in the data
     * directory.
     *
     * <h2>The one thing this server does not copy</h2>
     *
     * <p>Enzo, 2026-09-08: <i>"we never store bytes on the server unless it's in
     * a server project — the project's own files can contain an image, file_stat
     * just points to the ID."</i> A server-rooted project's picture is already
     * on this disk, so naming it costs a record and no bytes: {@link
     * ImageStore#idFor} is a static hash, so identity is free, and a glob over
     * two hundred PNGs that were each read by name writes two hundred small
     * records rather than two hundred copies.
     *
     * <p><b>Which is why the two kinds resolve differently and both are still
     * one id scheme.</b> A held image is read out of {@link #fileName} beside
     * its record, unconditionally, because this server owns those bytes. A named
     * one is read back through {@link ImageFence}, because those bytes are the
     * project's and the permission that reached them can be taken away.
     *
     * <p>Content addressing makes the two interchangeable where they meet: a
     * picture uploaded to a project <em>and</em> sitting in its workspace has one
     * id, and {@link ImageStore#store}'s first-write-wins rule keeps whichever
     * record arrived first — the held one is the better of the two to keep,
     * since it cannot vanish.
     */
    public boolean inWorkspace() {
        return path != null;
    }

    /** What the bytes are called on disk: the id, and the format's extension so
     *  that a person who opens the directory can double-click one.
     *
     *  <p>Meaningless for a record with a {@link #path} — nothing was written
     *  under this name — and {@link ImageStore} asks {@link #inWorkspace} before
     *  it ever resolves one. */
    public String fileName() {
        return id + '.' + format.extension();
    }

    /** What this record is called on disk, beside the bytes it describes. */
    public String recordName() {
        return id + ".json";
    }
}
