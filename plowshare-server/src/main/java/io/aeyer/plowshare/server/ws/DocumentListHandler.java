package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.DocumentListResponse;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.requests.RequestedCorpusPage;
import java.util.Map;
import java.util.Objects;

/**
 * {@code document.list} — what the corpus holds. The frame equivalent of
 * {@code GET /v1/documents}.
 *
 * <h2>The read a socket-only client cannot do without</h2>
 *
 * <p>Every other document type takes an id or answers with passages: {@code
 * document.ask} needs an id it does not say how to get, {@code
 * document.citations} names the ids of documents that have been cited — a
 * different set from the ones that exist — and {@code document.search} names
 * the ones a question happens to reach. Without this, a client whose user had
 * uploaded a paper and forgotten its name has nowhere to look.
 *
 * <h2>A {@code GET}, so the payload is a record of its query parameters</h2>
 *
 * <p>{@link Paged}'s shape, with the substring filter beside the two numbers
 * under the names the query string already used — {@link Payloads}' convention
 * keeps a client-facing spelling rather than inventing a second one. Not {@code
 * Paged} itself: that record is two fields and this endpoint takes three, and
 * its {@code limit} and {@code offset} are read through {@link
 * RequestedCorpusPage} rather than {@code RequestedWindow}, which caps and
 * whose sentences are about entries in a conversation.
 */
public final class DocumentListHandler implements FrameHandler {

    /**
     * {@code GET /v1/documents}' three query parameters, as a payload.
     *
     * @param limit how many at most, or null for {@link
     *     RequestedCorpusPage#DEFAULT_LISTED}
     * @param offset how many to skip, or null for the beginning
     * @param q a substring of a document's filed name or its title, or null for
     *     the whole corpus
     */
    record Asked(Integer limit, Integer offset, String q) {
    }

    private final DocumentStore documents;

    /**
     * @param documents the one store both surfaces page the corpus out of
     */
    public DocumentListHandler(DocumentStore documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        Asked asked = Payloads.as(payload, Asked.class, FrameTypes.DOCUMENT_LIST);
        int page = RequestedCorpusPage.listed(asked.limit());
        int skip = RequestedCorpusPage.skipped(asked.offset());
        String naming = asked.q() == null || asked.q().isBlank() ? null : asked.q().strip();
        return Outcome.ok(DocumentListResponse.of(
                documents.page(naming, page, skip), documents.count(naming), page, skip, naming));
    }
}
