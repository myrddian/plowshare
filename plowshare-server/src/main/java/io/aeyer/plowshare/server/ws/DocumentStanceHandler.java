package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.DocumentStanceResponse;
import io.aeyer.plowshare.server.api.StanceRequest;
import io.aeyer.plowshare.server.documents.Corpus;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.requests.RequestedCorpusQuestion;
import io.aeyer.plowshare.server.requests.RequestedDocument;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code document.stance} — a cheap guess at what one paper says about a claim,
 * with no model call at all. The frame equivalent of {@code POST
 * /v1/documents/&#123;id&#125;/stance}.
 *
 * <h2>A document with no summary vector is {@link Code#NOT_FOUND}, not a zero</h2>
 *
 * <p>The one decision this endpoint makes, kept: zero is a real reading — it is
 * what a paper unrelated to both the claim and its negation scores — so
 * answering an unembedded document with one would be indistinguishable from a
 * genuine result. That sentence is {@link Corpus#theStance}'s and is not
 * restated here; it says which read tells "the corpus does not hold it" apart
 * from "it holds it and nothing has embedded its summary yet", which is the one
 * thing this shape costs.
 *
 * <h2>The order is the endpoint's: the id, then the claim</h2>
 *
 * <p>{@code DocumentController.stance} parses its path id before it looks at
 * the body, so a request that sends a malformed id <em>and</em> a blank claim
 * is told about the id. This reads the payload's {@code document} first for the
 * same reason.
 */
public final class DocumentStanceHandler implements FrameHandler {

    private final RetrievalService retrieval;

    /**
     * @param retrieval the one service both surfaces score a stance through
     */
    public DocumentStanceHandler(RetrievalService retrieval) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        UUID document = RequestedDocument.askedAbout(Payloads.required(payload, "document",
                FrameTypes.DOCUMENT_STANCE,
                "the id document.list answers with. Nothing was scored."));
        StanceRequest asked =
                Payloads.as(payload, StanceRequest.class, FrameTypes.DOCUMENT_STANCE);
        String claim = RequestedCorpusQuestion.scored(asked.claim());
        return Outcome.ok(DocumentStanceResponse.of(
                document, claim, (retrieval.accountingEnabled() ? Corpus.theStance(retrieval, document, claim, retrieval.usage(io.aeyer.plowshare.protocol.Home.global(),asking.handle(),io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation.EMBEDDING_QUERY)) : Corpus.theStance(retrieval, document, claim))));
    }
}
