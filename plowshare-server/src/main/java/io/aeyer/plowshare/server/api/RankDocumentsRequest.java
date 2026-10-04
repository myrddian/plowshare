package io.aeyer.plowshare.server.api;

/**
 * What {@code POST /v1/documents/rank} is asked.
 *
 * @param query what is being asked, in plain language. Compared against each document's
 *     <b>summary</b> — a model's sentence about the paper, and not the paper's own words
 * @param limit how many documents at most. Absent is {@code RetrievalService.DEFAULT_RANKED}
 */
public record RankDocumentsRequest(String query, Integer limit) {}
