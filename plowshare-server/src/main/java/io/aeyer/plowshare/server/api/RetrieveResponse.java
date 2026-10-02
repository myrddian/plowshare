package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.DocumentStore;
import java.util.List;
import java.util.UUID;

/**
 * What {@code POST /v1/documents/retrieve} answers with.
 *
 * <p><b>An empty {@code hits} is an answer and not an absence of one</b>, and
 * this response deliberately carries no {@code searchable}/{@code unsearchable}
 * pair to say which kind of empty it is. {@code DocumentSearchResponse} can
 * carry those because its read is always over the whole corpus and {@code
 * DocumentStore.coverage} describes exactly that; this read is over the corpus
 * <em>or</em> one document, and one corpus-wide number said of a
 * document-scoped answer would be a claim nothing checked. The scope is echoed
 * instead, so a caller knows which question its empty list answers.
 *
 * @param query the question, as it was asked
 * @param document the document that was read, or <b>{@code null} for the whole
 *     corpus</b>. Echoed because the two are different questions and an empty
 *     answer to them means different things
 * @param limit the figure actually used, after the default and the store's cap.
 *     {@code DocumentSearchResponse}'s rule: a caller that asked for a thousand
 *     is told what it got rather than left to infer it from a short list
 * @param hits the chunks, nearest first
 */
public record RetrieveResponse(String query, UUID document, int limit, List<Hit> hits) {

    /**
     * One chunk a question reached.
     *
     * <p><b>{@code chunk} is nested rather than flattened into this record</b>,
     * so that it is the same object {@code GET /v1/documents/chunks/&#123;id&#125;}
     * answers with. Flattening would produce a nicer-looking body and a second
     * description of one chunk; Anchor has the second description and its two
     * DTOs already carry different field sets for the same row.
     *
     * @param score nearness, in {@code [0, 1]} — Anchor's word for it and {@code
     *     DocumentStore.Hit#similarity()}'s number
     */
    public record Hit(double score, ChunkDetailResponse chunk) {}

    /** The store's rows as a body. */
    public static RetrieveResponse of(
            String query, UUID document, int limit, List<DocumentStore.Retrieved> found) {
        return new RetrieveResponse(query, document, limit, found.stream()
                .map(row -> new Hit(row.similarity(), ChunkDetailResponse.of(row.chunk())))
                .toList());
    }
}
