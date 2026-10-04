package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.RetrieveRequest;
import io.aeyer.plowshare.server.api.RetrieveResponse;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.requests.RequestedCorpusPage;
import io.aeyer.plowshare.server.requests.RequestedCorpusQuestion;
import io.aeyer.plowshare.server.requests.RequestedDocument;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code document.retrieve} — a passage, and what the paper around it argues, in one round trip.
 * The frame equivalent of {@code POST /v1/documents/retrieve}.
 *
 * <h2>An empty answer stays {@link Code#OK}, and is not promoted to a failure</h2>
 *
 * <p><b>Do not "fix" this.</b> An empty {@code hits} is an answer and not an absence of one — the
 * echoed {@code document} is what says which question it is an answer to — and the endpoint has
 * always said so with a 200. A frame that answered {@code NOT_FOUND} for a corpus that held nothing
 * close would be telling a client the retrieve failed when what happened is that it succeeded and
 * found nothing, which is a different fact and one a client acts on differently. The breadth plan
 * rules this explicitly for every endpoint that embeds a refusal or an emptiness in a 200.
 *
 * <h2>The order is the endpoint's: question, limit, then the document</h2>
 *
 * <p>{@code DocumentController.retrieve} reads its query first, its limit second and its optional
 * {@code document} last, so a request that gets more than one of them wrong is told about the
 * first. Both of the first two sentences are {@code requests}' — {@link
 * RequestedCorpusQuestion#retrieved} and {@link RequestedCorpusPage#retrieved} — and the third is
 * {@link RequestedDocument#retrievedFrom}'s, which is the one of that class's three messages that
 * explains leaving the field out.
 */
public final class DocumentRetrieveHandler implements FrameHandler {

  private final RetrievalService retrieval;

  /**
   * @param retrieval the one service both surfaces retrieve through
   */
  public DocumentRetrieveHandler(RetrievalService retrieval) {
    this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    RetrieveRequest asked =
        Payloads.as(payload, RetrieveRequest.class, FrameTypes.DOCUMENT_RETRIEVE);
    String query = RequestedCorpusQuestion.retrieved(asked.query());
    int limit = RequestedCorpusPage.retrieved(asked.limit());
    UUID document =
        asked.document() == null ? null : RequestedDocument.retrievedFrom(asked.document());
    return Outcome.ok(
        RetrieveResponse.of(
            query,
            document,
            limit,
            (retrieval.accountingEnabled()
                ? retrieval.retrieve(
                    query,
                    document,
                    limit,
                    retrieval.usage(
                        Home.global(), asking.handle(), UsageAttribution.Operation.EMBEDDING_QUERY))
                : retrieval.retrieve(query, document, limit))));
  }
}
