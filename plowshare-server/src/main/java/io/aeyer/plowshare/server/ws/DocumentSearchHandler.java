package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.DocumentSearchResponse;
import io.aeyer.plowshare.server.api.SearchDocumentsRequest;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.requests.RequestedCorpusPage;
import io.aeyer.plowshare.server.requests.RequestedCorpusQuestion;
import io.aeyer.plowshare.server.requests.RequestedSearchMode;
import java.util.Map;
import java.util.Objects;

/**
 * {@code document.search} — the chunks of the corpus nearest a question, each
 * carrying the paragraph to cite. The frame equivalent of {@code POST
 * /v1/documents/search}.
 *
 * <h2>An empty answer stays {@link Code#OK}, and is not promoted to a failure</h2>
 *
 * <p><b>Do not "fix" this.</b> An empty {@code hits} is an answer and not an
 * absence of one: {@code searchable} and {@code unsearchable} beside it are
 * what say which kind of empty it is, since a corpus can hold a paragraph and
 * have no vector for it. Answering {@code NOT_FOUND} would collapse "the corpus
 * holds nothing about this" and "the corpus holds it and cannot reach it" into
 * one code, which is the distinction this response shape exists to draw. The
 * breadth plan rules it for every endpoint that embeds that kind of answer in a
 * 200.
 *
 * <h2>Two doors and no second spelling of "hybrid"</h2>
 *
 * <p>A request that named no mode reaches {@code RetrievalService.search(String,
 * int)}, whose own default is documented; a request that named one reaches the
 * three-argument door through {@link RequestedSearchMode}. <b>The same two
 * calls the endpoint makes, and for the same reason</b> — a handler that
 * resolved the default itself would put a second spelling of {@code HYBRID}
 * into this package for the first one to drift away from. {@link
 * RequestedSearchMode} refuses to offer a null-tolerant factory precisely so
 * that this branch cannot be collapsed by accident.
 *
 * <h2>The cap is not repeated here either</h2>
 *
 * <p>A number above {@code RetrievalService.MAX_HITS} is passed through and
 * capped there, and the response says which figure was used. {@link
 * RequestedCorpusPage} applies no cap for the same reason the controller
 * applied none.
 */
public final class DocumentSearchHandler implements FrameHandler {

    private final RetrievalService retrieval;

    /**
     * @param retrieval the one service both surfaces search through
     */
    public DocumentSearchHandler(RetrievalService retrieval) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        SearchDocumentsRequest asked =
                Payloads.as(payload, SearchDocumentsRequest.class, FrameTypes.DOCUMENT_SEARCH);
        String query = RequestedCorpusQuestion.searched(asked.query());
        int limit = RequestedCorpusPage.searched(asked.limit());
        return Outcome.ok(DocumentSearchResponse.of(asked.mode() == null
                ? (retrieval.accountingEnabled() ? retrieval.search(query, limit,RetrievalService.Mode.HYBRID,retrieval.usage(Home.global(),asking.handle(),UsageAttribution.Operation.EMBEDDING_QUERY)) : retrieval.search(query,limit))
                : (retrieval.accountingEnabled() ? retrieval.search(query, limit, RequestedSearchMode.in(asked.mode()),retrieval.usage(Home.global(),asking.handle(),UsageAttribution.Operation.EMBEDDING_QUERY)) : retrieval.search(query,limit,RequestedSearchMode.in(asked.mode())))));
    }
}
