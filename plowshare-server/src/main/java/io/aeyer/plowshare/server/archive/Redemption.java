package io.aeyer.plowshare.server.archive;

import java.time.Instant;
import java.util.Objects;

/**
 * What a handle resolves to: the result, or the account of where it went.
 *
 * <h2>Why this is a type and not a {@code String}</h2>
 *
 * <p>{@code Transcript.redeem} answered {@code Optional<String>} until payloads
 * could be ejected, and that signature has exactly two answers where there are
 * now three: the result, and nothing. <b>An ejected payload arriving as
 * "nothing" is the one outcome the retention design refuses in as many
 * words</b> — {@code result_read} must not answer not-found, because a model
 * told "there is no result at that address" concludes it invented the handle
 * and stops looking, when the truth is that the result was here, was read, and
 * has been put somewhere it can still be fetched from by a person.
 *
 * <p>The alternative was a second method on the interface asked only when the
 * first came back empty — two round trips on the one path where the answer
 * matters, and a pair of calls a caller can perform half of. One answer that
 * carries which of the two it is cannot be half-read.
 *
 * @param content the stored result, exactly as the tool returned it, or {@code
 *     null} for an ejected payload. <b>Never the empty string in the ejected
 *     case</b>: blank is what a tool returning nothing looks like, and {@code
 *     entries_a_tool_result_is_not_blank} refuses one for exactly that reason
 * @param ejectedAt when the payload was ejected, or {@code null} while it is
 *     still here. Exactly one of this and {@link #content()} is present, which
 *     the compact constructor holds and {@code entries_an_ejected_payload_is_
 *     gone} holds one layer down
 * @param export where the bytes were written, as the exporter wrote it down, or
 *     {@code null} for a payload ejected by a deployment that keeps no export.
 *     <b>A record of where it was put and not a live pointer</b> — nothing in
 *     this server resolves it, opens it or checks it, because an export only
 *     Plowshare can find is the export nobody can open
 */
public record Redemption(String content, Instant ejectedAt, String export) {

    /**
     * Exactly one of the two, and the other is what is missing.
     *
     * <p>A redemption with neither is a row that answered with nothing while
     * claiming to be here; one with both is a claim that the text was ejected
     * and is also right there, which would make {@code result_read} tell a model
     * a payload is gone while holding it.
     */
    public Redemption {
        if ((content == null) == (ejectedAt == null)) {
            throw new IllegalArgumentException(
                    "a redemption is either the stored result or the account of where it went,"
                            + " and this is " + (content == null ? "neither" : "both"));
        }
        if (export != null && ejectedAt == null) {
            throw new IllegalArgumentException(
                    "an export belongs to an ejection: a path on a payload that is still here is"
                            + " a claim nothing wrote and nothing would maintain");
        }
    }

    /** The result, still here. */
    public static Redemption held(String content) {
        return new Redemption(Objects.requireNonNull(content, "content"), null, null);
    }

    /**
     * The account of a payload that has gone.
     *
     * @param export where it went, or {@code null} for a deployment that keeps
     *     none — which is a real configuration and not an omission, and the
     *     sentence a model reads says so rather than naming an empty place
     */
    public static Redemption ejected(Instant at, String export) {
        return new Redemption(null, Objects.requireNonNull(at, "at"), export);
    }

    /** Whether the bytes are gone. Said as its own question because every caller
     *  branches on it, and {@code content() == null} is the same test written in
     *  a way a reader has to think about. */
    public boolean wasEjected() {
        return ejectedAt != null;
    }

    /**
     * The row as one of these, which is where the two shapes are told apart
     * once.
     *
     * <p>Here rather than in {@link EntryStore}'s row mapper because {@link
     * EntryRecord} is the whole row and this is one question about it, and
     * because the mapper is shared by the reads that want the row rather than
     * the answer.
     */
    public static Redemption of(EntryRecord row) {
        return row.ejectedAt() == null
                ? held(row.content())
                : ejected(row.ejectedAt(), row.export());
    }
}
