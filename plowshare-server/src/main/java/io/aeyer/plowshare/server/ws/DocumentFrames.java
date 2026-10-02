package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.documents.Deliberation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.documents.IngestService;
import io.aeyer.plowshare.server.documents.RetrievalService;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code DocumentController}'s area of the frame surface — every {@code
 * document.*} type this server answers, which as of the breadth plan's Task 2
 * is nine of that controller's ten endpoints.
 *
 * <h2>One constructor, and it is the controller's own</h2>
 *
 * <p>The seven services below are the same beans {@code DocumentController} is
 * injected with, in the same order, which is what makes a frame's refusal the
 * endpoint's own refusal rather than a second one shaped like it. {@code
 * IngestService} is taken although no handler below reads it, for exactly that
 * reason: the claim this class exists to make is that two constructors can be
 * put side by side and read as one list, and a list that quietly dropped the
 * service belonging to the endpoint with no frame would be the one place a
 * reader could not check it.
 *
 * <h2>The tenth endpoint, and why its absence is a ruling</h2>
 *
 * <p>{@code POST /v1/documents} is the multipart PDF upload and <b>gets no
 * frame — decided, not deferred</b>. A text frame could carry those bytes as
 * base64, but there is no precedent on this server for it ({@code
 * FileChannelHandler} is a {@code TextWebSocketHandler} that has only ever
 * exchanged JSON) and the event channel's write queue is bounded and sized for
 * small frames. Two binary endpoints — this one and {@code POST /v1/images} —
 * do not justify inventing a binary frame shape in the middle of a migration.
 * The cost is stated in {@code client.Capabilities}, where the ruling is
 * written out in full rather than left as a "not yet": a socket-only client
 * cannot upload and keeps one HTTP call for it, which is visible, declared, and
 * reversible the day a binary frame shape is worth having.
 *
 * <p>One of the {@link FrameArea} classes that javadoc describes. A later
 * breadth task adds <b>its own</b> {@code *Frames.java} rather than editing
 * this one.
 */
@Component
public class DocumentFrames implements FrameArea {

    private final IngestService ingest;
    private final JobStore jobs;
    private final RetrievalService retrieval;
    private final CitationStore citations;
    private final DocumentStore documents;
    private final Deliberation deliberation;
    private final DocumentsProperties props;
    private io.aeyer.plowshare.server.information.InformationAccess access;

    @org.springframework.beans.factory.annotation.Autowired
    public void useInformationAccess(io.aeyer.plowshare.server.information.InformationAccess policy) {
        this.access = Objects.requireNonNull(policy, "policy");
    }

    /**
     * @param ingest what the upload's job runs — held so this list is the
     *     controller's list, and read by no handler here, because {@code POST
     *     /v1/documents} has no frame
     * @param jobs the one store a deliberation is started through
     * @param retrieval the service four of these handlers ask the corpus
     *     through
     * @param citations the store a citation listing is read from
     * @param documents the store the corpus is paged, read and chunked from
     * @param deliberation what an ask's job actually runs
     * @param props the operator's own defaults, which decide an allowance no
     *     caller named
     */
    public DocumentFrames(IngestService ingest, JobStore jobs, RetrievalService retrieval,
            CitationStore citations, DocumentStore documents, Deliberation deliberation,
            DocumentsProperties props) {
        this.ingest = Objects.requireNonNull(ingest, "ingest");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.citations = Objects.requireNonNull(citations, "citations");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.deliberation = Objects.requireNonNull(deliberation, "deliberation");
        this.props = Objects.requireNonNull(props, "props");
    }

    /**
     * What this area holds but does not route.
     *
     * <p>{@link #ingest} is the upload's service and the upload has no frame,
     * so nothing in {@link #frames()} reads it. Named through an accessor
     * rather than left as an unread field so that {@code -Werror} and any
     * future unused-field check see a reader, and so that the ruling above has
     * something in the code to point at.
     *
     * @return the ingest service this area was handed
     */
    public IngestService ingestion() {
        return ingest;
    }

    @Override
    public Map<String, FrameHandler> frames() {
        if (access != null) {
            Map<String, FrameHandler> secured = new java.util.LinkedHashMap<>();
            for (String type : java.util.List.of(FrameTypes.DOCUMENT_ASK, FrameTypes.DOCUMENT_RETRIEVE,
                    FrameTypes.DOCUMENT_LIST, FrameTypes.DOCUMENT_DETAIL, FrameTypes.DOCUMENT_CHUNK,
                    FrameTypes.DOCUMENT_RANK, FrameTypes.DOCUMENT_STANCE, FrameTypes.DOCUMENT_CITATIONS,
                    FrameTypes.DOCUMENT_SEARCH)) {
                secured.put(type, (payload, asking) -> {
                    var context = access.resolve(asking.requireHandle(type),
                            io.aeyer.plowshare.server.information.InformationScopes.from(payload));
                    var scopedDocuments = documents.scoped(access, context);
                    var scopedRetrieval = retrieval.scoped(access, context);
                    FrameHandler handler = switch (type) {
                        case FrameTypes.DOCUMENT_ASK -> new DocumentAskHandler(scopedDocuments, jobs,
                                deliberation.scoped(access, context, asking.sessionId()), props).forAccount(context);
                        case FrameTypes.DOCUMENT_RETRIEVE -> new DocumentRetrieveHandler(scopedRetrieval);
                        case FrameTypes.DOCUMENT_LIST -> new DocumentListHandler(scopedDocuments);
                        case FrameTypes.DOCUMENT_DETAIL -> new DocumentDetailHandler(scopedDocuments);
                        case FrameTypes.DOCUMENT_CHUNK -> new DocumentChunkHandler(scopedDocuments);
                        case FrameTypes.DOCUMENT_RANK -> new DocumentRankHandler(scopedRetrieval);
                        case FrameTypes.DOCUMENT_STANCE -> new DocumentStanceHandler(scopedRetrieval);
                        case FrameTypes.DOCUMENT_CITATIONS -> new DocumentCitationsHandler(citations.scoped(access, context));
                        case FrameTypes.DOCUMENT_SEARCH -> new DocumentSearchHandler(scopedRetrieval);
                        default -> throw new IllegalStateException("unsecured document frame");
                    };
                    return handler.handle(payload, asking);
                });
            }
            return Map.copyOf(secured);
        }
        return Map.ofEntries(
                Map.entry(FrameTypes.DOCUMENT_ASK,
                        new DocumentAskHandler(documents, jobs, deliberation, props)),
                Map.entry(FrameTypes.DOCUMENT_RETRIEVE,
                        new DocumentRetrieveHandler(retrieval)),
                Map.entry(FrameTypes.DOCUMENT_LIST,
                        new DocumentListHandler(documents)),
                Map.entry(FrameTypes.DOCUMENT_DETAIL,
                        new DocumentDetailHandler(documents)),
                Map.entry(FrameTypes.DOCUMENT_CHUNK,
                        new DocumentChunkHandler(documents)),
                Map.entry(FrameTypes.DOCUMENT_RANK,
                        new DocumentRankHandler(retrieval)),
                Map.entry(FrameTypes.DOCUMENT_STANCE,
                        new DocumentStanceHandler(retrieval)),
                Map.entry(FrameTypes.DOCUMENT_CITATIONS,
                        new DocumentCitationsHandler(citations)),
                Map.entry(FrameTypes.DOCUMENT_SEARCH,
                        new DocumentSearchHandler(retrieval)));
    }
}
