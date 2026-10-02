package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The body of {@code POST /v1/documents/search}'s response.
 *
 * <p>Echoes the question and the limit actually applied, as {@code
 * RecallResponse} does and for its reason: a result in a log cannot be read
 * without the request beside it otherwise. {@link #limit} is <b>what was
 * used</b> and not what was asked for, which are different exactly when the
 * caller sent a number above {@code RetrievalService.MAX_HITS}.
 *
 * @param mode which halves the search read, lower-cased. Echoed for {@link
 *     #query}'s reason and it is the sharper case of it: a hybrid answer and a
 *     vector-only answer to one question are two different claims, and the
 *     field exists precisely so somebody can put the two side by side
 * @param hits the chunks, <b>best first, which is not the same as nearest
 *     first</b>. Under hybrid the order is the fused one, so {@link
 *     Hit#similarity} is a fact about each hit rather than the key the list is
 *     sorted on
 * @param searchable how many chunks in the corpus a question can reach at all
 * @param unsearchable how many chunks hold text and no vector, and so were
 *     skipped whatever the question was. <b>The field that stops an empty
 *     answer being a lie</b> — {@code RecallResponse.unsearchable}'s argument,
 *     at corpus scale, where it is worse: a whole document stored while the
 *     embedding endpoint was down is every word of a paper that answers nothing.
 *     Zero means the answer is complete
 */
public record DocumentSearchResponse(
        String query, int limit, String mode, List<Hit> hits, int searchable,
        int unsearchable) {

    /**
     * One chunk a search found, and the citation to keep.
     *
     * <p><b>Flat, where Anchor's {@code RetrieveHit} carries a four-level
     * ancestor stack</b> — paragraph, section, chapter and document summaries,
     * so its caller "never has to make a follow-up read to understand context".
     * Three of those four do not exist here: V18 stores no sections, no chapters
     * and no summaries, and says at length why. What is left is the half that
     * was ever about identity.
     *
     * @param chunkId what matched, and <b>deliberately not the thing to
     *     cite</b>: a chunk is an artefact of the chunker, so changing {@code
     *     chunk-target-tokens} moves every one of these while no document has
     *     moved
     * @param paragraphId <b>the citation.</b> V18's surrogate key: it survives a
     *     re-ingest that left this paragraph's text alone, and a re-ingest that
     *     changed the text issues a new one rather than repointing this. So a
     *     citation held across an edit either still means these words or means
     *     nothing, and never quietly means different words
     * @param paragraphOrdinal where the paragraph sits in the document today —
     *     position and not identity, so it moves when a paragraph is inserted
     *     above it and {@link #paragraphId} does not
     * @param similarity 1 for an identical direction and 0 for an unrelated one.
     *     Comparable between two hits of one search and <b>not</b> a probability
     *     or a threshold anyone has calibrated
     */
    public record Hit(
            UUID chunkId, String text, double similarity, UUID paragraphId, String paragraphText,
            int paragraphOrdinal, UUID documentId, String sourceName, String title) {}

    /** What a search came to, on the wire. */
    public static DocumentSearchResponse of(RetrievalService.Found found) {
        return new DocumentSearchResponse(
                found.query(),
                found.limit(),
                // Lower-cased on the way out and read case-insensitively on the
                // way in, so the wire never carries a Java enum's spelling as
                // though it were a contract.
                found.mode().name().toLowerCase(Locale.ROOT),
                found.hits().stream().map(DocumentSearchResponse::hit).toList(),
                found.searchable(),
                found.unsearchable());
    }

    private static Hit hit(DocumentStore.Hit found) {
        return new Hit(found.chunkId(), found.chunkText(), found.similarity(),
                found.paragraphId(), found.paragraphText(), found.paragraphOrdinal(),
                found.documentId(), found.sourceName(), found.title());
    }
}
