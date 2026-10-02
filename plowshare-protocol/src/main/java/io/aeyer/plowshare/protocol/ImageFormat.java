package io.aeyer.plowshare.protocol;

import java.util.Locale;

/**
 * What kind of image a byte array is, decided by reading the bytes.
 *
 * <h2>Sniffed, never declared</h2>
 *
 * <p>A multipart upload carries a {@code Content-Type} and a filename, and both
 * are the caller's to write. Neither is consulted. The format decides the media
 * type in the {@code data:} URI the model is eventually shown, and a caller who
 * says {@code image/png} over a JPEG would have the endpoint handed a URI whose
 * declared type and payload disagree — a refusal from the far side of a
 * transport, about a request nothing here built wrong.
 *
 * <p>So the signature is read instead, which is the one thing about a file that
 * is not the uploader's opinion. This is {@code TextExtraction}'s rule reached
 * from the other end: that class dispatches on the <em>name</em> because a text
 * format is a parser choice and being wrong costs a bad extraction, and this one
 * dispatches on the bytes because a media type is a claim made to somebody else.
 *
 * <h2>Four formats, and the fifth is a refusal</h2>
 *
 * <p>PNG, JPEG, GIF and WebP — the four an OpenAI-compatible {@code image_url}
 * part is documented to take. <b>Anything else is refused rather than passed
 * through</b> with whatever type the caller claimed: a store that accepted a
 * TIFF would hold bytes no model in this fleet can be shown, discovered at the
 * first vision call rather than at the upload that could have said so.
 *
 * <p><b>SVG is refused and is worth naming</b>, because it is an image
 * everywhere else and is not one here: it is a document with a script element
 * in its grammar, no vision endpoint rasterises it, and a store that took one
 * would be holding markup under a name that says picture.
 *
 * <h2>In {@code plowshare-protocol}, for {@link FileAccess}'s reason</h2>
 *
 * <p>This was {@code io.aeyer.plowshare.server.images.ImageFormat} until the
 * client learned to upload a picture out of a remote workspace, and it moved for
 * the reason {@link FileAccess} moved: <b>both halves have to read one
 * definition of the set, and a second copy that drifts is not a duplicate but a
 * disagreement.</b> The client checks these bytes to decide whether to upload at
 * all and {@code ImageController} checks them again to decide whether to keep
 * what arrived — so a client whose set had grown would upload a user's file for
 * the server to refuse with a {@code 415}, which is exactly the speculative
 * upload the allow-list exists to prevent.
 *
 * <p>It carries no server dependency and never did: four signatures, a media
 * type, an extension. That is what made the move a rename rather than a
 * decision — the shared fence and format rules live here, and this is one.
 *
 * <p><b>Both halves still ship separately</b>, so the two builds can disagree
 * about this file the way they can disagree about any other, and the {@code 415}
 * remains reachable for that reason alone. What the shared definition removes is
 * disagreement <em>within one build</em>, which is the case nothing else could
 * catch.
 */
public enum ImageFormat {

    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp");

    private final String mediaType;
    private final String extension;

    ImageFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    /** What goes between {@code data:} and {@code ;base64,} on the wire. */
    public String mediaType() {
        return mediaType;
    }

    /** What the stored file is called after its id. Lower case, no dot. */
    public String extension() {
        return extension;
    }

    /** The spelling a record file and an API response use. */
    public String declared() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The format these bytes are, or {@code null} for none of the four.
     *
     * <p><b>Null and not an exception</b>, and it matters more now than it did
     * when there was one caller. {@code ImageController} owes an uploader a 415
     * naming what <em>is</em> accepted, and an exception carrying that sentence
     * would be this enum writing the controller's message for it; the two file
     * readers — {@code LocalProvider.named} and {@code ClientEnforcer.named} —
     * want the opposite answer entirely, which is <em>this is not a picture, so
     * carry on and let the decoder refuse it as it always has</em>. One null
     * serves all three; a thrown refusal would serve none of them.
     *
     * <p>The signatures are the fixed prefixes each format's specification
     * defines, and nothing beyond them is validated — this is not a decoder. A
     * truncated PNG is accepted, because nothing on either side of the wire
     * renders it and the vision endpoint that does is entitled to its own
     * opinion; what is refused is a file that is not claiming to be one of these
     * at all.
     */
    public static ImageFormat of(byte[] bytes) {
        if (starts(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        // Every JPEG begins SOI; the third byte is the first marker and varies
        // (0xE0 JFIF, 0xE1 Exif, 0xDB straight to a quantisation table), so it
        // is deliberately not part of the test.
        if (starts(bytes, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (starts(bytes, 'G', 'I', 'F', '8')) {
            return GIF;
        }
        // RIFF....WEBP -- the four length bytes at offset 4 are skipped, which
        // is why this is not one prefix test.
        if (starts(bytes, 'R', 'I', 'F', 'F') && bytes.length >= 12
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return WEBP;
        }
        return null;
    }

    /** What a refusal lists, so an uploader is told the set rather than the
     *  one thing they got wrong. */
    public static String accepted() {
        StringBuilder text = new StringBuilder();
        for (ImageFormat format : values()) {
            text.append(text.isEmpty() ? "" : ", ").append(format.mediaType);
        }
        return text.toString();
    }

    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes == null || bytes.length < signature.length) {
            return false;
        }
        for (int at = 0; at < signature.length; at++) {
            if ((bytes[at] & 0xFF) != signature[at]) {
                return false;
            }
        }
        return true;
    }
}
