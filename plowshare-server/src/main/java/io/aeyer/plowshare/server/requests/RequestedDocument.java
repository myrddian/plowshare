package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.UUID;

/**
 * A document id read off a request, or a refusal that says what one looks like.
 *
 * <h2>Three factories and one parser, deliberately</h2>
 *
 * <p>All three of {@link #askedAbout}, {@link #retrievedFrom} and {@link
 * #documentId} do the same parse and none of them say the same thing, because
 * each answers a different caller. {@link #askedAbout} reads a path segment and
 * tells a reader that every search hit and every citation carries the id it
 * came from. {@link #retrievedFrom} reads a body field on {@code POST
 * /v1/documents/retrieve} and adds the thing only that endpoint is true of:
 * {@code GET /v1/documents} names the id of everything in the corpus, and
 * leaving the field out retrieves from all of it. {@link #documentId} reads a
 * query parameter on {@code GET /v1/documents/citations} and names search hits
 * only, because that endpoint has no retrieve-shaped "leave it out" to explain.
 * <b>A single shared message would be wrong on two endpoints out of three</b> —
 * collapsing these into one text is the simplification a later reader will
 * reach for, and it is wrong for exactly that reason. What is shared is the
 * parse, and that is all that is shared.
 *
 * <p>A {@code null} is refused exactly like a string that will not parse — the
 * same factory, the same message — because a caller has no other way to name
 * "this field was missing" and that is this class's whole contract for it.
 */
public final class RequestedDocument {

    private RequestedDocument() {
    }

    /**
     * The parse the three refusals share.
     *
     * <p>Shared because it is the only thing they have in common. Each caller
     * below supplies its own message, and a shared message would be wrong on
     * two of the three endpoints — see this class's own javadoc for why.
     *
     * <p>{@code null} is treated as unparseable rather than let through to
     * {@link UUID#fromString} to throw a {@link NullPointerException} — a
     * missing field is a caller's mistake exactly like a malformed one, and
     * this type exists so that mistake answers 400 and not 500.
     */
    private static UUID parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null");
        }
        return UUID.fromString(raw.strip());
    }

    /**
     * The path's id as a uuid, or a refusal that says what one is.
     *
     * <p>A 400 and not a 404: a caller that sent something which is not an id
     * at all has made a mistake it can correct, and answering "no such
     * document" would say the corpus was asked when nothing was.
     */
    public static UUID askedAbout(String id) {
        try {
            return parse(id);
        } catch (IllegalArgumentException notAnId) {
            throw new CallerFault(
                    "'" + id + "' is not a document id. A document id is five groups of"
                            + " hexadecimal separated by hyphens, and every search hit and"
                            + " every citation carries the one it came from", notAnId);
        }
    }

    /**
     * The body's {@code document} as a uuid, or a refusal that says what one is.
     *
     * <p>What this one adds is the way out: leaving the field off is a legal
     * request here and means the whole corpus, which is not true of either of
     * the other two, and a caller that mistyped an id needs to be told that
     * before it decides to drop the field.
     */
    public static UUID retrievedFrom(String sent) {
        try {
            return parse(sent);
        } catch (IllegalArgumentException notAnId) {
            throw new CallerFault(
                    "`document` is '" + sent + "', which is not the shape of a document id."
                            + " A document id is five groups of hexadecimal separated by hyphens,"
                            + " and GET /v1/documents names the id of everything in the corpus."
                            + " Leave the field out to retrieve from the whole corpus", notAnId);
        }
    }

    /** The document id, or a refusal that says what one looks like. */
    public static UUID documentId(String sent) {
        try {
            return parse(sent);
        } catch (IllegalArgumentException notAnId) {
            throw new CallerFault(
                    "`document` is '" + sent + "', which is not the shape of a document id."
                            + " A document id is five groups of hexadecimal separated by hyphens,"
                            + " and every search hit carries the one it came from", notAnId);
        }
    }
}
