package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.documents.Corpus;
import io.aeyer.plowshare.server.documents.Deliberation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.documents.Extracted;
import io.aeyer.plowshare.server.documents.IngestService;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.documents.TextExtraction;
import io.aeyer.plowshare.server.requests.RequestedCitationScope;
import io.aeyer.plowshare.server.requests.RequestedCorpusPage;
import io.aeyer.plowshare.server.requests.RequestedCorpusQuestion;
import io.aeyer.plowshare.server.requests.RequestedDocument;
import io.aeyer.plowshare.server.requests.RequestedDocumentName;
import io.aeyer.plowshare.server.requests.RequestedSearchMode;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /v1/documents} — the one door a document comes in by.
 *
 * <h2>Multipart, and only multipart</h2>
 *
 * <p>Anchor exposes two ingest routes and they are not equivalent. {@code POST
 * /ingest/upload} takes a {@code MultipartFile}; {@code POST /ingest} takes
 * {@code IngestRequest(source_path)} and tells the <b>server</b> to read a path
 * off its own filesystem. <b>Only the first ports</b> (spec decision 4). The
 * second is the leash-bypass the survey rejected: slice 2 made the client the
 * enforcement point between this server and the user's disk, and a server-side
 * read undoes it.
 *
 * <p>So the client reads the file — under the workspace leash, as it does for
 * every other file operation — and uploads the bytes. The server never touches
 * the user's disk, and the file channel's lack of a byte-oriented operation
 * stops being a problem because bytes do not travel over that channel at all.
 * <b>Nothing in {@code plowshare-protocol} changes</b>: this is an HTTP
 * endpoint and not a seventh channel frame, so the doubled enforcement is
 * untouched.
 *
 * <h2>Extraction here, the rest in a job</h2>
 *
 * <p>Turning bytes into text happens on the request thread and its failures are
 * answered as {@code 415}. That is where they belong: a format this server does
 * not read is the caller's, it is knowable in microseconds, and a caller handed
 * a job id and told to poll it to discover that its PDF was never going to be
 * read is worse off than one refused.
 *
 * <p>Everything after that is a job — derivation, persistence, and an embedding
 * call per batch — polled at {@code GET /v1/jobs/&#123;id&#125;} and stopped at
 * {@code POST /v1/jobs/&#123;id&#125;/cancel} like any other. This is {@code
 * POST /v1/curate}'s shape exactly, and deliberately: an ingest is a
 * server-side job that is not one agent's run, which is the door {@code
 * JobStore.submit(String, Function)} exists for.
 *
 * <p>Anchor's {@code GET /ingest/jobs/&#123;jobId&#125;} therefore has no
 * counterpart here and needs none — a second job endpoint for one kind of job
 * would be a second place to answer a question {@code /v1/jobs} already answers.
 *
 * <h2>What bounds an upload</h2>
 *
 * <p>{@code spring.servlet.multipart.max-file-size} in {@code application.yml},
 * at the same 8 MiB the file channel's {@code LocalProvider.MAX_FILE_BYTES} and
 * {@code ClientEnforcer.MAX_FILE_BYTES} already agree on. A document too large
 * for the client to read off the disk should not arrive by another door, and a
 * second number would be a second thing to keep in step.
 */
@RestController
public class DocumentController {
    private io.aeyer.plowshare.server.information.InformationAccess informationAccess;

    @org.springframework.beans.factory.annotation.Autowired
    public void useInformationAccess(io.aeyer.plowshare.server.information.InformationAccess access) {
        informationAccess = access;
    }

    private io.aeyer.plowshare.server.information.InformationContext informationContext() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes request)) {
            throw new io.aeyer.plowshare.server.faults.CallerFault("information needs an authenticated request");
        }
        Object account = request.getRequest().getAttribute(
                io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE);
        String project = request.getRequest().getParameter("project");
        return informationAccess.resolve(account instanceof String handle ? handle : null,
                project == null ? null
                        : io.aeyer.plowshare.server.information.InformationContext.Selection.project(project));
    }

    private io.aeyer.plowshare.server.information.InformationCatalogue catalogue;
    @org.springframework.beans.factory.annotation.Autowired
    public void useInformationCatalogue(io.aeyer.plowshare.server.information.InformationCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    private DocumentStore documentReads() {
        return informationAccess == null ? documents : documents.scoped(informationAccess, informationContext());
    }

    private RetrievalService retrievalReads() {
        return informationAccess == null ? retrieval : retrieval.scoped(informationAccess, informationContext());
    }

    private CitationStore citationReads() {
        return informationAccess == null ? citations : citations.scoped(informationAccess, informationContext());
    }

    private Deliberation documentPass() {
        return informationAccess == null ? deliberation
                : deliberation.scoped(informationAccess, informationContext(), null);
    }

    /** What a document's job is called, for {@code Job.agent} and the job
     *  listing. Not an agent — no {@code AgentDefinition} exists for it —
     *  which is what {@code JobStore.submit(String, Function)}'s javadoc means
     *  by "the caller's own name for the run". */
    public static final String BY = "ingest";

    /**
     * What a deliberation's job is called.
     *
     * <p>{@link #BY}'s twin and not an agent name either, although this one has
     * three real agents under it — which is exactly why it is not one of theirs:
     * a pass is a proposer, a critic and a synthesiser, and putting any single
     * one of those on the job row would name a third of the run.
     */
    public static final String ASKED_BY = "ask";

    private final IngestService ingest;
    private final JobStore jobs;
    private final RetrievalService retrieval;
    private final CitationStore citations;
    private final DocumentStore documents;
    private final Deliberation deliberation;
    private final DocumentsProperties props;

    public DocumentController(
            IngestService ingest, JobStore jobs, RetrievalService retrieval,
            CitationStore citations, DocumentStore documents, Deliberation deliberation,
            DocumentsProperties props) {
        this.ingest = ingest;
        this.jobs = jobs;
        this.retrieval = retrieval;
        this.citations = citations;
        this.documents = documents;
        this.deliberation = deliberation;
        this.props = props;
    }

    /**
     * {@code POST /v1/documents/&#123;id&#125;/ask} — one document, three agents
     * in series, and an answer that says what it rests on.
     *
     * <h2>Why it is per-document, and why it is not the search beside it</h2>
     *
     * <p>The deliberation's critic holds a proposed answer against what
     * <em>this document</em> argues as a whole, from the document's own summary
     * and its top-level summaries and nothing below them. "The document as a
     * whole" has no corpus-wide analogue, so this is a different question from
     * {@code /v1/documents/search} rather than a narrowing of it — a {@code
     * document:} filter on that route would look like it answered this and would
     * not.
     *
     * <h2>A job, where the search beside it is synchronous</h2>
     *
     * <p>{@link #upload}'s split, drawn in the same place and for the same
     * arithmetic. A search is one embedding call and one index scan; a pass is
     * three model calls in series on a local model, which is minutes. So this is
     * {@code POST /v1/curate}'s shape — 202 and a handle, polled at {@code GET
     * /v1/jobs/&#123;id&#125;} and stopped at {@code POST
     * /v1/jobs/&#123;id&#125;/cancel} — which is also what makes cancellation
     * real here. Anchor's cancel endpoint claims the orchestrator discards a
     * cancelled result and there is no such check in it: {@code
     * runDeliberation} never reads the job's status, so the next transition
     * overwrites CANCELLED. Plowshare's is asked at every turn boundary.
     *
     * <p><b>{@code POST /v1/jobs/&#123;id&#125;/limits} does not serve it, and
     * that is the one thing this shape costs.</b> That endpoint reads {@code
     * RunLimits} off the job, and only an agent run has them: a pass submitted
     * through {@code JobStore.submit(String, Function)} is refused there in as
     * many words, exactly as a curator pass is. So {@code maxModelCalls} on
     * <em>this</em> body is the only place a caller can size one, which is why
     * it is on the request and not only in configuration.
     *
     * <h2>Two refusals before the job, and one after</h2>
     *
     * <p>A malformed id and a document the corpus does not hold are answered
     * now, on {@link #upload}'s stated rule: both are knowable in microseconds
     * and both are the caller's, and a caller handed a job id and told to poll
     * it to discover it named a document that never existed is worse off than
     * one refused. <b>Everything else is the job's</b> — a document nothing has
     * summarised, one whose chunks were never embedded, one with a paragraph in
     * no section — because each of those is a fact about the corpus that the
     * deliberation has to state in its own words, and stating it twice in two
     * vocabularies is how the two come to disagree.
     *
     * @param id the document to ask, as a uuid
     * @param request the question, and optionally this pass's own allowance
     * @return {@code 202} and the job handle, at once
     */
    @PostMapping(path = "/v1/documents/{id}/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StartedJob> ask(
            @PathVariable String id, @RequestBody AskDocumentRequest request,
            @org.springframework.web.bind.annotation.RequestAttribute(name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {

        UUID document = RequestedDocument.askedAbout(id);
        String question = RequestedCorpusQuestion.asked(request.question());
        Corpus.theOneToAsk(documentReads(), document);
        int allowance = request.maxModelCalls() == null
                ? props.getAskBudget()
                : request.maxModelCalls();
        Budget budget = Budget.of(allowance);
        Deliberation pass = documentPass();
        var context = informationAccess == null ? null : informationContext();
        String jobId = context == null ? jobs.submit(ASKED_BY,
                cancelled -> pass.accountingEnabled() ? pass.ask(document, question, budget, cancelled,handle) : pass.ask(document, question, budget, cancelled))
                : jobs.submitInformation(ASKED_BY, context.selection().project() == null ? io.aeyer.plowshare.protocol.Home.global()
                        : io.aeyer.plowshare.protocol.Home.of(context.selection().project()), context, java.util.List.of(document),
                        cancelled -> pass.accountingEnabled() ? pass.ask(document, question, budget, cancelled,handle) : pass.ask(document, question, budget, cancelled));
        return ResponseEntity.accepted().body(new StartedJob(jobId, ASKED_BY));
    }

    /**
     * How many documents a listing answers with when the caller names no
     * number. Anchor's {@code DocumentController.DEFAULT_LIMIT}, unchanged.
     *
     * <p>The number itself lives on {@link RequestedCorpusPage}, which is
     * where the refusal that names it went when this controller's inline rules
     * came down; this field is the public spelling {@code DocumentTools}'
     * javadoc and {@code CorpusReadControllerTest} already name, kept so that
     * moving the rule did not move a constant out from under them.
     */
    public static final int DEFAULT_LISTED = RequestedCorpusPage.DEFAULT_LISTED;

    /**
     * {@code POST /v1/documents/retrieve} — <b>a passage, and what the paper
     * around it argues, in one round trip.</b>
     *
     * <h2>Why it is not {@code /v1/documents/search} with more fields</h2>
     *
     * <p>The two answer different questions and one of them is already load
     * bearing. Search answers <em>which paragraph should I cite</em>: it is
     * hybrid — vector and lexical, fused — it is capped at {@code
     * RetrievalService.MAX_HITS} because a model calls it out of a
     * conversation's allowance, and {@code DocumentStore.coverage}'s contract
     * turns on it being corpus-wide, in as many words: "a corpus whose reachable
     * half depended on which words a question used could not be described by
     * one". This answers <em>what does this passage sit inside</em>: pure
     * vector, optionally scoped to one document, and every tier of the hierarchy
     * attached to every row.
     *
     * <p>Widening search to do both would have cost that contract and would have
     * changed a tool description two shipped agents read, which §3.3 already
     * declined for a smaller change than this one.
     *
     * <h2>A POST for a read, which is what the body is for</h2>
     *
     * <p>{@link #search}'s shape exactly: the request carries prose, and prose
     * has no business in a query string. {@code GET /v1/documents} beside it is
     * a {@code GET} because its filter is a name.
     *
     * <h2>Synchronous</h2>
     *
     * <p>One embedding call and one index scan, which is {@link #search}'s
     * arithmetic and not {@link #ask}'s. Handing back a job id would make a
     * caller poll for an answer that was ready before the first poll.
     *
     * @param request the question, optionally the document, optionally how many
     * @return {@code 200} and the hits, nearest first. <b>An empty {@code hits}
     *     is an answer</b>; the echoed {@code document} is what says which
     *     question it is an answer to
     */
    @PostMapping(path = "/v1/documents/retrieve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RetrieveResponse> retrieve(@RequestBody RetrieveRequest request,
            @org.springframework.web.bind.annotation.RequestAttribute(name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {

        String query = RequestedCorpusQuestion.retrieved(request.query());
        // Refused rather than defaulted, which is `search`'s rule and its
        // reason: a caller that sent zero has said something, and answering it
        // with ten hits would be this server deciding what they meant.
        int limit = RequestedCorpusPage.retrieved(request.limit());
        UUID document = request.document() == null
                ? null : RequestedDocument.retrievedFrom(request.document());
        return ResponseEntity.ok(RetrieveResponse.of(
                query, document, limit, retrievalReads().retrieve(query, document, limit)));
    }

    /**
     * {@code GET /v1/documents} — <b>what the corpus holds.</b>
     *
     * <h2>The read that was missing, and what its absence cost</h2>
     *
     * <p>Every other document route on this server takes an id or answers with
     * passages. {@code POST /v1/documents/&#123;id&#125;/ask} needs an id it
     * does not say how to get; {@link #citations} answers with ids of documents
     * that have been cited, which is a different set from the ones that exist;
     * {@link #search} answers with the ids of documents that happen to match a
     * question. <b>A person who could not remember what they had uploaded could
     * not find out.</b>
     *
     * <h2>{@code q}, and the thing built on top of it</h2>
     *
     * <p>Anchor's shell binds a document with {@code use <uuid-or-title>}, and
     * the substring half of that is not a route: it is this parameter, called
     * with a limit of ten and refused unless exactly one document comes back.
     * <b>So the resolution a person needs is this filter, and the binding is the
     * caller's.</b> That is Anchor's own division — its SPEC §5 says the
     * "select a document" pattern "belongs in the client, not the server" — and
     * it is the right one here for a second reason: this server is stateless
     * about documents and a bound document is state.
     *
     * <p>It matches the filed name as well as the title, which Anchor's does
     * not, because Plowshare has two names where Anchor has one. {@code
     * DocumentStore}'s own javadoc gives the argument.
     *
     * @param limit how many at most. Capped at {@code DocumentStore.MOST_LISTED},
     *     and the cap is not repeated here for {@link #search}'s reason
     * @param offset how many to skip
     * @param q a substring of a document's filed name or its title. Absent is
     *     the whole corpus
     * @return {@code 200} and the page. <b>{@code total} is what a caller pages
     *     against</b>, and it counts what the naming reaches rather than what
     *     the corpus holds
     */
    @GetMapping("/v1/documents")
    public ResponseEntity<DocumentListResponse> list(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "q", required = false) String q) {

        int page = RequestedCorpusPage.listed(limit);
        // Refused rather than clamped to zero. A negative offset is a caller's
        // arithmetic having gone wrong somewhere above this call, and answering
        // it with the first page would hide the fault behind a plausible
        // answer.
        int skip = RequestedCorpusPage.skipped(offset);
        String naming = q == null || q.isBlank() ? null : q.strip();
        return ResponseEntity.ok(DocumentListResponse.of(
                documentReads().page(naming, page, skip), documentReads().count(naming), page, skip, naming));
    }

    /**
     * {@code GET /v1/documents/&#123;id&#125;} — <b>one document's structure,
     * with none of its text in it.</b>
     *
     * <p>The outline a person or a model reads to decide whether asking this
     * paper is worth minutes of deliberation. Until this shipped, the hierarchy
     * this port spent a migration and two detectors building was visible to the
     * three ask agents and to nothing else.
     *
     * <p><b>A literal that could have been read as an id.</b> {@code
     * /v1/documents/citations} matches this pattern too, and Spring prefers the
     * literal — pinned in {@code CorpusReadControllerTest} rather than left to a
     * reader's confidence in the framework, because the failure would be a
     * working route quietly answering 400.
     *
     * @param id the document, as a uuid
     * @return {@code 200} and the structure, {@code 404} for a document the
     *     corpus does not hold, {@code 400} for something that is not an id at
     *     all — {@link RequestedDocument#askedAbout}'s distinction, and the same method draws it
     */
    @GetMapping("/v1/documents/{id}")
    public ResponseEntity<DocumentDetailResponse> detail(@PathVariable String id) {
        UUID document = RequestedDocument.askedAbout(id);
        DocumentStore.StoredDocument found = Corpus.theOneToRead(documentReads(), document);
        return ResponseEntity.ok(
                DocumentDetailResponse.of(found, documentReads().hierarchy(document)));
    }

    /**
     * {@code GET /v1/documents/chunks/&#123;id&#125;} — <b>one chunk and
     * everything above it.</b>
     *
     * <h2>Under {@code /v1/documents}, where Anchor's is top-level</h2>
     *
     * <p>Anchor mounts this at {@code /chunks/&#123;id&#125;}, which reads as a
     * resource of its own. A chunk is not one here: V18 made it an artefact of
     * the chunker, {@code DocumentStore.Passage} says in as many words that a
     * chunk id is "deliberately not a citation", and the durable handle this
     * corpus hands out is the paragraph. The path says which of those it is.
     *
     * <h2>It answers with the object a retrieve hit already carries</h2>
     *
     * <p>Deliberately, and it is why this route reaches no client surface —
     * {@code Capabilities} declares that gap and gives the argument. Anchor's
     * own retrieve promises "no follow-up reads", nothing in Anchor's SDK, shell
     * or docs calls this route, and a chunk id is the one id here that a
     * re-ingest may not preserve. So it exists for the caller that held one and
     * came back, and it says the same thing the hit said.
     *
     * @param id the chunk, as a uuid
     * @return {@code 200} and the chunk, {@code 404} for one the corpus does not
     *     hold — which is also what a chunk whose document was re-ingested looks
     *     like
     */
    @GetMapping("/v1/documents/chunks/{id}")
    public ResponseEntity<ChunkDetailResponse> chunk(@PathVariable String id) {
        UUID chunk = RequestedDocument.askedAbout(id);
        return ResponseEntity.ok(ChunkDetailResponse.of(Corpus.theChunk(documentReads(), chunk)));
    }

    /**
     * {@code POST /v1/documents/rank} — <b>which papers are about this.</b>
     *
     * <h2>Called rank, where Anchor calls it search</h2>
     *
     * <p>Anchor mounts this at {@code GET /documents/search}. That spelling is
     * taken here and means something else: {@code POST /v1/documents/search}
     * answers with <em>passages</em> and this answers with <em>documents</em>,
     * and one word doing two jobs on one resource is how a caller comes to
     * believe it has the corpus's best passages when it has a list of papers.
     *
     * <h2>A POST, where Anchor's is a GET</h2>
     *
     * <p>{@link #search} and {@link #retrieve}'s rule, kept: the request carries
     * prose, and prose has no business in a query string. {@code GET
     * /v1/documents} beside it is a {@code GET} because its filter is a name.
     *
     * <h2>What the answer is not</h2>
     *
     * <p>Topical relevance and not agreement. A paper that spends forty pages
     * demolishing a claim is close to that claim in cosine space, and that is
     * the correct answer to the question this route asks. It is also compared
     * against a <em>summary</em> — a model's sentence about the paper — rather
     * than against the paper, which is what makes it one vector lookup instead
     * of a chunk fan-out and is the whole reason V27 exists.
     *
     * @return {@code 200} and the documents, nearest first. <b>{@code unranked}
     *     is what says which kind of empty an empty answer is</b>: a document is
     *     summarised before anything embeds the summary, so a corpus can be
     *     entirely unrankable while every word of it is searchable
     */
    @PostMapping(path = "/v1/documents/rank", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentRankingResponse> rank(
            @RequestBody RankDocumentsRequest request,
            @org.springframework.web.bind.annotation.RequestAttribute(name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {

        String query = RequestedCorpusQuestion.ranked(request.query());
        int limit = RequestedCorpusPage.ranked(request.limit());
        return ResponseEntity.ok(
                DocumentRankingResponse.of(query, limit, retrievalReads().rank(query, limit)));
    }

    /**
     * {@code POST /v1/documents/&#123;id&#125;/stance} — <b>a cheap guess at what
     * one paper says about a claim, with no model call at all.</b>
     *
     * <h2>What it is, and why the route says so in its own name</h2>
     *
     * <p>Anchor's {@code POST /validate/quick}, and the name is the one
     * departure that matters. "Quick" describes how long it takes; what a caller
     * has to know is what it <em>is</em> — a difference between two cosines
     * against a summary a model wrote — and Anchor's own field for that is
     * called {@code mode: "vector_only"}, which arrives after the number rather
     * than before it. The route is named for the thing it approximates and
     * {@link DocumentStanceResponse#basis} repeats it on the wire.
     *
     * <h2>Beside the ask, and pointing at it</h2>
     *
     * <p>This and {@code POST /v1/documents/&#123;id&#125;/ask} answer the same
     * question at opposite prices: one embedding call against a summary, or
     * three model calls against the paper. Anchor's design is that the first is a
     * <em>pre-filter</em> for the second at a scale where deliberating on
     * everything is impossible, and both are under the document for that reason —
     * a caller that has narrowed the corpus with {@link #rank} scores the
     * survivors here and deliberates on what is left.
     *
     * <h2>The two refusals, and one absence that is not a refusal</h2>
     *
     * <p>A malformed id and a blank claim are answered now, on {@link #ask}'s
     * rule. <b>A document with no summary vector is a 404 rather than a zero</b>,
     * and that is the one decision in this method: zero is a real reading — it is
     * what a paper unrelated to both the claim and its negation scores — so
     * answering an unembedded document with one would be indistinguishable from
     * a genuine result. The 404 says the same thing as a 404 for a document that
     * does not exist, which is the one thing this shape costs; {@code GET
     * /v1/documents/&#123;id&#125;} tells the two apart and the message says so.
     *
     * @return {@code 200} and the reading, {@code 404} for a document the corpus
     *     does not hold or has not embedded the summary of
     */
    @PostMapping(path = "/v1/documents/{id}/stance", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentStanceResponse> stance(
            @PathVariable String id, @RequestBody StanceRequest request,
            @org.springframework.web.bind.annotation.RequestAttribute(name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {

        UUID document = RequestedDocument.askedAbout(id);
        String claim = RequestedCorpusQuestion.scored(request.claim());
        return ResponseEntity.ok(DocumentStanceResponse.of(
                document, claim, Corpus.theStance(retrievalReads(), document, claim)));
    }

    /**
     * What answers have said they took from the corpus.
     *
     * <h2>GET, and the three scopes are one route</h2>
     *
     * <p>A read with no body, so it is a {@code GET} where the two routes above
     * are {@code POST}s — search takes prose that has no business in a query
     * string, and this takes an id or nothing. The scopes are two optional
     * parameters rather than three routes because they are one question asked of
     * three different keys, and {@code CitationStore} has one index for each.
     *
     * <p><b>An empty list is an answer and not an absence of one</b>, which is
     * why {@link CitationsResponse#scope} is echoed: nothing has cited anything,
     * this conversation cited nothing, and this document has never been cited are
     * three different facts that come back as the same empty array.
     *
     * <p><b>No 404 for a conversation or a document that does not exist.</b> A
     * citation listing is not a read of that row and this endpoint does not
     * pretend to be one: it answers "what citations name this id", and for an id
     * nothing names the true answer is none. Refusing would make this the second
     * place on the server that decides whether a conversation exists, and it
     * would disagree with the first the moment retention changed.
     *
     * @param conversation list only what this conversation cited, in the order it
     *     said so
     * @param document list only what has cited this document, newest first.
     *     Answered over the document rather than the paragraph, so a citation
     *     whose paragraph was edited away is still counted against the paper it
     *     came from
     * @param limit how many at most. Capped at {@code
     *     CitationStore.MOST_LISTED}, and the cap is not repeated here for the
     *     reason {@link #search} gives about its own
     * @return {@code 200} and the citations
     */
    @GetMapping("/v1/documents/citations")
    public ResponseEntity<CitationsResponse> citations(
            @RequestParam(value = "conversation", required = false) String conversation,
            @RequestParam(value = "document", required = false) String document,
            @RequestParam(value = "limit", required = false) Integer limit) {

        // Two scopes at once is refused rather than one silently winning. Both
        // were sent on purpose and answering one of them would be this server
        // deciding which the caller meant -- `mode` below declines the same
        // move for the same reason.
        RequestedCitationScope scope = RequestedCitationScope.in(conversation, document);
        int most = RequestedCorpusPage.cited(limit);
        return ResponseEntity.ok(
                CitationsResponse.of(scope.scope(), most, scope.from(citationReads(), most)));
    }

    /**
     * Take a document and start ingesting it.
     *
     * @param file the document's bytes. The part is named {@code file},
     *     matching Anchor's {@code @RequestParam("file")}, so a client written
     *     against either sends the same request
     * @param name what the corpus should file it under. Defaults to the
     *     uploaded filename. <b>This is the document's identity</b> — a second
     *     upload under the same name is a re-ingest of the same document, and a
     *     paragraph whose text is unchanged keeps its id — so it is offered as a
     *     decision somebody can take rather than left as an accident of what the
     *     file happened to be called on a disk
     * @param by who asked, recorded on the row. Defaults to {@code operator},
     *     which is who is holding the credential: {@code AuthFilter} gates every
     *     {@code /v1} path, and this server is single-user
     * @return {@code 202} and the job handle, at once
     */
    @PostMapping(path = "/v1/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StartedJob> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "by", required = false) String by,
            @org.springframework.web.bind.annotation.RequestAttribute(name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {

        // Blank is a caller that meant to name this document and sent the field
        // empty; falling back to the filename would file it somewhere they did
        // not choose and never say so. The same distinction JobStore draws
        // between a null session id and a blank one.
        String sourceName = RequestedDocumentName.in(name, file.getOriginalFilename());
        byte[] bytes = read(file);

        if (informationAccess != null) {
            if (catalogue == null) throw new IllegalStateException("retained information intake is not wired");
            var context = informationContext();
            var attributes = (org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
            String requested = attributes.getRequest().getParameter("requestId");
            UUID requestId;
            try { requestId = requested == null ? UUID.randomUUID() : UUID.fromString(requested); }
            catch (IllegalArgumentException invalid) { throw new io.aeyer.plowshare.server.faults.CallerFault("requestId must be a UUID"); }
            var admission = catalogue.admit(context, requestId, sourceName, bytes, file.getContentType(), null);
            var home = context.selection().project() == null ? io.aeyer.plowshare.protocol.Home.global()
                    : io.aeyer.plowshare.protocol.Home.of(context.selection().project());
            String job = jobs.submitInformation(BY, home, context, java.util.List.of(admission.revision()),
                    cancelled -> catalogue.awaitProcessing(context, admission.revision(), cancelled));
            return ResponseEntity.accepted().body(new StartedJob(job, BY, admission.revision().toString()));
        }

        // On the request thread, so a refusal is a 415 the caller reads now.
        Extracted extracted = TextExtraction.extract(sourceName, bytes);
        long size = bytes.length;
        String ingestedBy = by == null || by.isBlank() ? "operator" : by;

        var ingestionOwner = ingest.accountingEnabled() ? ingest.usage(Home.global(),handle,UsageAttribution.Operation.EMBEDDING_WRITE) : UsageAttribution.LEGACY;
        String id = jobs.submit(BY,
                cancelled -> ingest.accountingEnabled()
                        ? ingest.ingest(sourceName, extracted, size, ingestedBy, cancelled, ingestionOwner)
                        : ingest.ingest(sourceName, extracted, size, ingestedBy, cancelled));
        return ResponseEntity.accepted().body(new StartedJob(id, BY));
    }

    /**
     * {@code POST /v1/documents/search} — the chunks of the corpus nearest a
     * question, each carrying the paragraph to cite.
     *
     * <h2>Synchronous, where the upload beside it is a job</h2>
     *
     * <p>An ingest is a half-hour of model calls and a search is one embedding
     * call and one index scan. Handing back a job id for that would make a
     * caller poll to read an answer that was ready before the first poll — and
     * {@code POST /v1/memories/recall} already answers the same shape of
     * question in line.
     *
     * <h2>The refusals, and why neither is left to the service</h2>
     *
     * <p>{@link RetrievalService} raises {@code IllegalArgumentException} for a
     * blank question and for a limit under one, which is the right currency for
     * a Java caller and the wrong one for HTTP: nothing maps it, so it would
     * arrive as a {@code 500} saying this server is broken when what happened is
     * that a caller asked for nothing. Both are therefore read through {@code
     * requests}' own types — {@link RequestedCorpusQuestion#searched} and {@link
     * RequestedCorpusPage#searched} — and the service keeps its own guards
     * because it is a public method with three callers. <b>The sentences are in
     * {@code requests} and not here</b> because {@code document.search} refuses
     * the same two fields and must refuse them in the same words; they were
     * inline in this method until the breadth plan's Task 2.
     *
     * <p><b>The cap is not repeated here.</b> A number above {@code
     * RetrievalService.MAX_HITS} is passed through and capped there, and the
     * response says which figure was used. A second spelling of the bound in
     * this file is the copy that would go on saying ten after the other one
     * moved.
     *
     * @return {@code 200} with the hits. An empty {@code hits} is an answer and
     *     not an absence of one — {@code searchable} and {@code unsearchable}
     *     beside it are what say which kind of empty it is
     */
    @PostMapping(path = "/v1/documents/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentSearchResponse> search(
            @RequestBody SearchDocumentsRequest request,
            @org.springframework.web.bind.annotation.RequestAttribute(name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {

        String query = RequestedCorpusQuestion.searched(request.query());
        // Refused rather than defaulted. A caller that sent zero has said
        // something, and answering it with five hits would be this server
        // deciding what they meant; answering it with none would be a claim
        // about the corpus that nothing checked.
        int limit = RequestedCorpusPage.searched(request.limit());
        // Two calls and not one with a resolved default, which is the same move
        // the limit above declines to make in the other direction: a request
        // that named no mode is answered by the door whose default is
        // documented, so this file holds no second spelling of "hybrid" to drift
        // away from the first.
        return ResponseEntity.ok(DocumentSearchResponse.of(request.mode() == null
                ? retrievalReads().search(query, limit)
                : retrievalReads().search(query, limit, RequestedSearchMode.in(request.mode()))));
    }

    /**
     * The upload's bytes.
     *
     * <p>{@code getBytes()} rather than a stream, because the whole document is
     * what extraction and derivation want and the part is already bounded by
     * {@code spring.servlet.multipart.max-file-size} — the container has either
     * refused it or buffered it before this method runs.
     *
     * <p>An {@link IOException} here is the container failing to hand over a
     * part it accepted, which is this server's fault and not the document's — so
     * it is deliberately not an {@code UnreadableDocumentException}, whose
     * {@code 415} would tell the caller their file was the problem.
     */
    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException couldNotRead) {
            throw new IllegalStateException(
                    "the uploaded part could not be read back from this server's own buffer",
                    couldNotRead);
        }
    }
    public ResponseEntity<RetrieveResponse> retrieve(RetrieveRequest request) { return retrieve(request, null); }
    public ResponseEntity<DocumentRankingResponse> rank(RankDocumentsRequest request) { return rank(request, null); }
    public ResponseEntity<DocumentStanceResponse> stance(String id, StanceRequest request) { return stance(id, request, null); }
    public ResponseEntity<DocumentSearchResponse> search(SearchDocumentsRequest request) { return search(request, null); }
    public ResponseEntity<StartedJob> ask(String id, AskDocumentRequest request) { return ask(id, request, null); }
    public ResponseEntity<StartedJob> upload(MultipartFile file, String name, String by) { return upload(file, name, by, null); }
}
