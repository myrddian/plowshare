package io.aeyer.plowshare.server.api;

/**
 * What {@code POST /v1/documents/retrieve} is asked.
 *
 * <p><b>Three field names differ from Anchor's and the reasons are local.</b> Anchor sends {@code
 * query} / {@code document_id} / {@code k}. The first is kept because {@link
 * SearchDocumentsRequest} already spells it that way; {@code document} is what {@code GET
 * /v1/documents/citations} already calls the same parameter, and two spellings of one field on one
 * resource is the drift this server keeps a register to prevent; {@code limit} is what every other
 * read here calls the same number, and {@code k} is a word from the retrieval literature rather
 * than from the caller's question.
 *
 * @param query what is being asked, in plain language. Embedded and compared against the corpus's
 *     chunk vectors
 * @param document the document to read, as a uuid, or <b>absent for the whole corpus</b>. Anchor's
 *     own javadoc calls corpus-wide retrieval "provided for completeness, not what the system is
 *     optimised for" and its shell never reaches it — every command there that retrieves is
 *     unavailable until a document is bound. It is a string rather than a {@code UUID} so that
 *     something which is not an id is refused with a message saying what one is, rather than by
 *     Jackson with a message about a constructor
 * @param limit how many chunks at most. Absent is {@code RetrievalService.DEFAULT_RETRIEVED}; a
 *     number above the store's cap is passed through and capped there, and the response says which
 *     figure was used
 */
public record RetrieveRequest(String query, String document, Integer limit) {}
