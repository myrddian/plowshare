package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/documents/search}.
 *
 * <p><b>A body and not a query string</b>, matching Anchor's {@code
 * RetrieveRequest} and this server's own {@code RecallRequest}: a question is
 * prose of arbitrary length, and a URL is the one place in this system that
 * ends up in an access log.
 *
 * <p><b>No {@code document_id}.</b> Anchor's request has one, and its own
 * javadoc calls corpus-wide search "provided for completeness, not what the
 * system is optimised for" — because Anchor's caller is an ask pipeline
 * answering a question about a paper somebody named. Plowshare's corpus is
 * reachable from every project on purpose ("a memory has exactly one home; a
 * document has none") and the question here is asked of the corpus. A filter is
 * additive when something needs one.
 *
 * @param query what is being asked, in prose. Embedded server-side, by the same
 *     model the corpus was embedded with — which is why there is no field for a
 *     vector and could not be one
 * @param limit how many hits at most; {@code null} takes {@code
 *     RetrievalService.DEFAULT_HITS}. A number above {@code
 *     RetrievalService.MAX_HITS} is capped rather than refused, and the response
 *     carries the figure that was used — this record does not repeat either
 *     number, because a cap spelled in two places is the one that drifts
 * @param mode which halves of the corpus to read — {@code hybrid}, {@code
 *     vector} or {@code lexical}, case-insensitively; {@code null} takes the
 *     service's default, which this record does not name for the reason it does
 *     not name the cap. <b>Here and on no other surface.</b> {@code
 *     DocumentTools}' schema has no such field and will not grow one: an agent
 *     choosing between this server's indexes is being asked a question about
 *     HNSW and GIN that nothing in its context could answer. The caller of this
 *     endpoint is an operator, and what the field is for is running one question
 *     twice — a conjunctive word search is correctly silent for most well-formed
 *     questions, so a lexical half that has broken and one that is working as
 *     designed produce the same hybrid answer, and comparing the halves is the
 *     only thing that tells them apart
 */
public record SearchDocumentsRequest(String query, Integer limit, String mode) {}
