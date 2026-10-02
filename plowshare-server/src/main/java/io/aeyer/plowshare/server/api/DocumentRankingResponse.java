package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What {@code POST /v1/documents/rank} answers with — <b>which papers are about
 * this</b>.
 *
 * <p>Anchor's {@code GET /documents/search}, under a different name because
 * {@code POST /v1/documents/search} is already taken here and means something
 * else: that one answers with <em>passages</em> and this one answers with
 * <em>documents</em>, and two routes called search that return different kinds
 * of thing would be one word doing two jobs on one resource.
 *
 * @param rankable how many documents in the corpus have a summary vector, and
 *     so could have appeared here
 * @param unranked how many have a summary and <b>no vector for it yet</b>.
 *     <b>The field that says which kind of empty an empty answer is</b>, and it
 *     is not a rare state: a document is summarised before anything embeds the
 *     summary, so a corpus ingested five minutes ago and never restarted can be
 *     entirely unrankable while every word of it is searchable. Documents the
 *     cascade has not reached at all are in neither number — they are waiting on
 *     a model rather than on an embedding
 */
public record DocumentRankingResponse(
        String query, int limit, List<Ranked> documents, int rankable, int unranked) {

    /**
     * One document, and how close its summary is.
     *
     * @param score nearness in {@code [-1, 1]}, and <b>topical relevance rather
     *     than agreement</b>: a paper that spends forty pages demolishing a
     *     claim is close to that claim. {@code POST
     *     /v1/documents/&#123;id&#125;/stance} is the cheap guess at the second
     *     question and the deliberation is the answer to it
     * @param summary the sentence that was actually compared. Returned because
     *     it is what the score is <em>of</em> — a reader surprised by a ranking
     *     should be able to see whether the summary or the question is what
     *     surprised them
     */
    public record Ranked(
            UUID documentId,
            String sourceName,
            String title,
            String summary,
            Instant ingestedAt,
            double score) {

        static Ranked of(DocumentStore.Ranked row) {
            DocumentStore.StoredDocument document = row.document();
            return new Ranked(
                    document.id(),
                    document.sourceName(),
                    document.title(),
                    document.summary(),
                    document.ingestedAt(),
                    row.similarity());
        }
    }

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
    public static DocumentRankingResponse of(
            String query, int limit, RetrievalService.Ranking found) {
        return new DocumentRankingResponse(
                query, limit,
                found.documents().stream().map(Ranked::of).toList(),
                found.corpus().rankable(),
                found.corpus().unranked());
    }
}
