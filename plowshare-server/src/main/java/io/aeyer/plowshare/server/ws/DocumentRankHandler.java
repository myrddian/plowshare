package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.DocumentRankingResponse;
import io.aeyer.plowshare.server.api.RankDocumentsRequest;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.requests.RequestedCorpusPage;
import io.aeyer.plowshare.server.requests.RequestedCorpusQuestion;
import java.util.Map;
import java.util.Objects;

/**
 * {@code document.rank} — which papers are about this. The frame equivalent of
 * {@code POST /v1/documents/rank}.
 *
 * <h2>Documents, where {@code document.search} answers with passages</h2>
 *
 * <p>The two are not one verb narrowed: a search answers <em>which paragraph
 * should I cite</em> and this answers <em>which paper should I be reading</em>,
 * against a summary a model wrote rather than against the paper, which is what
 * makes it one vector lookup instead of a chunk fan-out. They are two types for
 * the same reason the endpoints are two routes.
 *
 * <h2>An unrankable corpus stays {@link Code#OK}, and is not promoted</h2>
 *
 * <p><b>Do not "fix" this.</b> {@code unranked} is what says which kind of
 * empty an empty answer is: a document is summarised before anything embeds the
 * summary, so a corpus can be entirely unrankable while every word of it is
 * searchable. Both facts travel in a 200 body on the HTTP side and in an {@code
 * OK} payload here, because "I ranked nothing and here is why" is a successful
 * answer to the question that was asked.
 */
public final class DocumentRankHandler implements FrameHandler {

    private final RetrievalService retrieval;

    /**
     * @param retrieval the one service both surfaces rank through
     */
    public DocumentRankHandler(RetrievalService retrieval) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        RankDocumentsRequest asked =
                Payloads.as(payload, RankDocumentsRequest.class, FrameTypes.DOCUMENT_RANK);
        String query = RequestedCorpusQuestion.ranked(asked.query());
        int limit = RequestedCorpusPage.ranked(asked.limit());
        return Outcome.ok(DocumentRankingResponse.of(query, limit, (retrieval.accountingEnabled() ? retrieval.rank(query, limit,retrieval.usage(Home.global(),asking.handle(),UsageAttribution.Operation.EMBEDDING_QUERY)) : retrieval.rank(query,limit))));
    }
}
