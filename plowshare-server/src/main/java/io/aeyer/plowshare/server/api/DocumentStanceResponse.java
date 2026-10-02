package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.DocumentStore;
import java.util.UUID;

/**
 * What {@code POST /v1/documents/&#123;id&#125;/stance} answers with.
 *
 * <p>Anchor's {@code POST /validate/quick}. No model call: the claim and {@code
 * "not " + claim} are embedded and both are scored against the document's
 * summary vector.
 *
 * <h2>The field that stops this being read as an answer</h2>
 *
 * <p>{@link #basis} is the string {@code "vector_only"} — Anchor calls the same
 * field {@code mode} — and it is on the wire so that a caller storing one of
 * these can tell it apart from a judgment something actually read the paper to
 * reach. Anchor's own DTO says twice that this is not one: <em>"vector-only
 * stance approximation"</em>, <em>"NOT a substitute for full /validate"</em>.
 *
 * <p>The mechanism, stated plainly because a number in {@code [-2, 2]} invites
 * more trust than it has earned: it embeds a negation and hopes the embedding
 * model puts it somewhere useful in cosine space, against a summary a model
 * wrote about a paper nobody here has read. <b>Nothing checks that any of that
 * holds.</b> What it is for is Anchor's stated purpose — a pre-filter over a
 * corpus too large to deliberate on — and what answers the question is {@code
 * POST /v1/documents/&#123;id&#125;/ask}.
 *
 * @param topical cosine of the claim against the summary. <b>Read this first:</b>
 *     a {@code stance} near zero means "argues both ways" when this is high and
 *     "is not about this at all" when it is low, and those are opposite
 *     conclusions
 * @param stance {@code topical} minus the same cosine for the negated claim.
 *     Positive leans toward the claim, negative against it
 */
public record DocumentStanceResponse(
        UUID documentId, String claim, double topical, double stance, String basis) {

    /** What this reading was made of, and the only value it has ever had. A
     *  constant rather than a literal at the call site so that a second basis —
     *  a real one — arrives as a second constant beside this and not as a
     *  string somebody typed twice. */
    public static final String VECTOR_ONLY = "vector_only";

    /**
     * The view, built from what the store answered with.
     *
     * <p><b>Public rather than package-private</b>, which it was while
     * the HTTP surface was the only surface that built one. {@code
     * ws.DocumentFrames}' handlers answer with this same record, and a
     * narrower visibility would have meant either a frame handler living in
     * {@code api} -- the accident {@code requests}' package javadoc
     * describes -- or a second view record with the same fields. The
     * narrower statement is gone, and is the same cost that move charged.
     */
    public static DocumentStanceResponse of(
            UUID documentId, String claim, DocumentStore.Stance read) {
        return new DocumentStanceResponse(
                documentId, claim, read.topical(), read.score(), VECTOR_ONLY);
    }
}
