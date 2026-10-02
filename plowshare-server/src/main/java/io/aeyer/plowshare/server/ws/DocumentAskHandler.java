package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.api.AskDocumentRequest;
import io.aeyer.plowshare.server.api.DocumentController;
import io.aeyer.plowshare.server.api.StartedJob;
import io.aeyer.plowshare.server.documents.Corpus;
import io.aeyer.plowshare.server.documents.Deliberation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.requests.RequestedCorpusQuestion;
import io.aeyer.plowshare.server.requests.RequestedDocument;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code document.ask} — one document, three agents in series, and an answer
 * that says what it rests on. The frame equivalent of {@code POST
 * /v1/documents/&#123;id&#125;/ask}.
 *
 * <h2>{@link Code#ACCEPTED} and never {@link Code#OK}</h2>
 *
 * <p>A pass is three model calls in series on a local model, which is minutes,
 * so the endpoint answers 202 with a handle to poll at {@code GET
 * /v1/jobs/&#123;id&#125;}. An {@code OK} here would tell a client its
 * deliberation had finished while the run it names has not started.
 *
 * <h2>Two refusals before the job, in the endpoint's order</h2>
 *
 * <p>A blank question is answered before a document the corpus does not hold,
 * which is the order {@code DocumentController.ask} evaluates them in and the
 * kind of parity no payload comparison sees: a handler that looked the
 * document up first would agree with the endpoint on every other case and
 * answer a different status for a request that got both wrong. Both sentences
 * belong to {@link RequestedCorpusQuestion} and {@link Corpus}, and neither is
 * restated here.
 *
 * <p>Everything past those two is the job's — a document nothing has
 * summarised, one whose chunks were never embedded — because each is a fact
 * about the corpus that the deliberation states in its own words, and stating
 * it twice in two vocabularies is how the two come to disagree.
 */
public final class DocumentAskHandler implements FrameHandler {

    private final DocumentStore documents;
    private final JobStore jobs;
    private final Deliberation deliberation;
    private final DocumentsProperties props;

    /**
     * @param documents the store both surfaces ask whether the corpus holds it
     * @param jobs the one store both surfaces start a pass through
     * @param deliberation what the job actually runs
     * @param props the operator's own default allowance for a pass
     */
    public DocumentAskHandler(DocumentStore documents, JobStore jobs, Deliberation deliberation,
            DocumentsProperties props) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.deliberation = Objects.requireNonNull(deliberation, "deliberation");
        this.props = Objects.requireNonNull(props, "props");
    }

    private io.aeyer.plowshare.server.information.InformationContext context;
    public DocumentAskHandler forAccount(io.aeyer.plowshare.server.information.InformationContext context) {
        this.context = context;
        return this;
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        UUID document = RequestedDocument.askedAbout(Payloads.required(payload, "document",
                FrameTypes.DOCUMENT_ASK,
                "the id GET /v1/documents lists. Nothing was deliberated."));
        AskDocumentRequest asked =
                Payloads.as(payload, AskDocumentRequest.class, FrameTypes.DOCUMENT_ASK);
        String question = RequestedCorpusQuestion.asked(asked.question());
        Corpus.theOneToAsk(documents, document);
        int allowance = asked.maxModelCalls() == null
                ? props.getAskBudget()
                : asked.maxModelCalls();
        Budget budget = Budget.of(allowance);
        String job = context == null ? jobs.submit(DocumentController.ASKED_BY,
                cancelled -> deliberation.accountingEnabled() ? deliberation.ask(document, question, budget, cancelled,asking.handle()) : deliberation.ask(document, question, budget, cancelled))
                : jobs.submitInformation(DocumentController.ASKED_BY, context.selection().project() == null
                        ? io.aeyer.plowshare.protocol.Home.global() : io.aeyer.plowshare.protocol.Home.of(context.selection().project()),
                        context, java.util.List.of(document), cancelled -> deliberation.accountingEnabled() ? deliberation.ask(document, question, budget, cancelled,asking.handle()) : deliberation.ask(document, question, budget, cancelled));
        return new Outcome(Code.ACCEPTED, null,
                new StartedJob(job, DocumentController.ASKED_BY));
    }
}
