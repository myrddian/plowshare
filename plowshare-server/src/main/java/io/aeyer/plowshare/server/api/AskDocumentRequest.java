package io.aeyer.plowshare.server.api;

/**
 * What {@code POST /v1/documents/&#123;id&#125;/ask} takes.
 *
 * <p><b>No document field, because the document is the path.</b> The ask is per-document by
 * construction — its critic holds a proposed answer against what <em>this document</em> argues as a
 * whole — so the document is what the resource is, not a filter on a search. {@code
 * SearchDocumentsRequest} beside this one is the corpus-wide question and carries no document at
 * all; the two shapes say the difference rather than describing it.
 *
 * @param question what the reader wants to know, in prose. Required
 * @param maxModelCalls how many model calls this whole pass may spend across its three stages, or
 *     null for {@code plowshare.documents.ask-budget}. {@code CurateRequest}'s field and its
 *     reason: an allowance is a decision about one run, and a caller that has one should not have
 *     to change a property to express it
 */
public record AskDocumentRequest(String question, Integer maxModelCalls) {}
