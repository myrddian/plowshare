package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.llm.accounting.*;

import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Puts a vector on a document's own summary</b>, which is the write path
 * V27's column has and nothing else does.
 *
 * <h2>Two ways in, and the second exists because the first can fail</h2>
 *
 * <p>{@link #attach} runs at the end of a cascade, on the summary the cascade
 * has just written, and it is where the vector normally comes from — a document
 * summarised at ten past two is rankable at ten past two. {@link #fill} finds
 * every summary that has no vector and embeds them in batches; it runs once at
 * startup and it is what covers the two cases {@link #attach} cannot: <b>a
 * document summarised before V27 existed</b>, and one whose embedding call
 * failed while the summary was already committed.
 *
 * <p>Anchor has only the second and calls it a "one-time backfill", which
 * understates it: Anchor embeds at ingest inside the same transaction that
 * writes the summary, so its runner really does have nothing to do on the second
 * boot. Here the summary is committed before anything embeds it — divergence 2,
 * V18's shape for {@code chunks.embedding} exactly — so this pass is a standing
 * repair rather than a migration aid, and {@code DocumentStore.Ranking} is what
 * makes its backlog visible while it waits.
 *
 * <h2>A failure here never loses a summary</h2>
 *
 * <p>Both methods swallow {@link EmbeddingException} and log it. The summary is
 * already committed and cost a model call; turning an unreachable embedding
 * endpoint into a failed cascade would throw away work that succeeded, and the
 * cascade's own contract — "spending an allowance stops the cascade and keeps
 * every summary already paid for" — says which way that trade goes. What the
 * failure costs is that the document is not rankable until this runs again, and
 * {@code GET /v1/documents/rank} reports the count rather than answering as
 * though the corpus were smaller.
 */
public final class SummaryEmbeddings implements UsageAware {
    private UsageOwners usageOwners = UsageOwners.NONE;
    @Override public void useUsageOwners(UsageOwners source) { usageOwners = java.util.Objects.requireNonNull(source); }


    private static final Logger log = LoggerFactory.getLogger(SummaryEmbeddings.class);

    /**
     * How many summaries one embedding call carries.
     *
     * <p>Anchor's sixteen. Not {@code plowshare.documents.embed-batch-size},
     * which is thirty-two and is about chunks: a chunk is up to 1600 characters
     * of a paper and a document summary is two or three sentences, so the two
     * numbers bound different things and sharing one would make each of them
     * wrong somewhere. Sixteen is kept because the batch that matters here is
     * one per <em>document</em> and a corpus of a few hundred is a few dozen
     * calls whatever the size.
     */
    static final int BATCH = 16;

    private final DocumentStore store;
    private final EmbeddingClient embeddings;
    private final int expectedDim;
    private final String model;

    public static boolean valid(float[] vector,int width) {
        if(vector==null || vector.length!=width)return false;
        boolean nonzero=false;
        for(float value:vector){if(!Float.isFinite(value))return false;if(value!=0)nonzero=true;}
        return nonzero;
    }

    public SummaryEmbeddings(
            DocumentStore store, EmbeddingClient embeddings, String model, int expectedDim) {
        this.store = Objects.requireNonNull(store, "store");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.model = model;
        this.expectedDim = expectedDim;
    }

    /**
     * Embed one summary the cascade has just written.
     *
     * <p>Takes the text rather than reading it back, because the caller is
     * holding it: a second query for a string that has not left the calling
     * method is a round trip for nothing.
     *
     * @return whether a vector was written. {@code false} is an ordinary outcome
     *     and not an error — the summary stands, and {@link #fill} will find it
     */
    public boolean attach(java.util.UUID documentId, String summary) {
        return attach(documentId, summary, usageOwners.in(io.aeyer.plowshare.protocol.Home.global(), null, UsageAttribution.Operation.EMBEDDING_WRITE));
    }

    public boolean attach(java.util.UUID documentId, String summary, UsageAttribution owner) {
        if (summary == null || summary.isBlank()) {
            // Not a failure and not worth a log line: the cascade stopped before
            // the document level, which it says in its own outcome.
            return false;
        }
        try {
            float[] vector = EmbeddingClient.owned(embeddings, summary, owner.forOperation(UsageAttribution.Operation.EMBEDDING_WRITE, owner.agentName()));
            if (wrongWidth(vector, 1)) {
                return false;
            }
            store.attachSummaryEmbedding(documentId, vector);
            return true;
        } catch (EmbeddingException unreachable) {
            log.warn("could not embed the summary of document {}; it keeps its summary and will"
                            + " be ranked once the embedding endpoint is reachable: {}",
                    documentId, unreachable.getMessage());
            return false;
        }
    }

    /**
     * Embed every summary that has no vector.
     *
     * <p><b>Batch by batch, committing as it goes</b>, which is {@code
     * IngestService}'s shape over chunks and its reason: a run that fails
     * halfway keeps what it paid for, and the next run finds exactly what is
     * left. Anchor's equivalent mutates JPA entities inside a {@code
     * @Transactional} method it calls on itself from a pool thread, so the proxy
     * is bypassed and the annotation never applies — recorded in the port spec
     * as an Anchor bug rather than reproduced here.
     *
     * @return how many vectors were written
     */
    public int fill() {
        List<DocumentStore.UnembeddedSummary> waiting = store.summariesAwaitingAVector(true);
        if (waiting.isEmpty()) {
            return 0;
        }
        log.info("{} document summaries have no vector; embedding them in batches of {}.",
                waiting.size(), BATCH);
        int written = 0;
        for (int start = 0; start < waiting.size(); start += BATCH) {
            List<DocumentStore.UnembeddedSummary> batch =
                    waiting.subList(start, Math.min(start + BATCH, waiting.size()));
            written += embed(batch);
        }
        return written;
    }

    /** One batch. Its own method so that a batch that fails is one batch and not
     *  the pass. */
    private int embed(List<DocumentStore.UnembeddedSummary> batch) {
        List<String> texts = new ArrayList<>(batch.size());
        for (DocumentStore.UnembeddedSummary waiting : batch) {
            texts.add(waiting.summary());
        }
        List<float[]> vectors;
        try {
            vectors = EmbeddingClient.owned(embeddings, texts, usageOwners.in(io.aeyer.plowshare.protocol.Home.global(), null, UsageAttribution.Operation.EMBEDDING_REPAIR));
        } catch (EmbeddingException unreachable) {
            log.warn("a batch of {} document summaries could not be embedded; they keep their"
                            + " summaries and this runs again at the next startup: {}",
                    batch.size(), unreachable.getMessage());
            return 0;
        }
        if (vectors.size() != batch.size()) {
            // Anchor skips the batch here too, and is right to: pairing a
            // shorter list of vectors with a longer list of documents by index
            // would attach one document's summary vector to another's row, which
            // is a wrong answer rather than a missing one.
            log.warn("asked for {} summary vectors and got {}; the batch is skipped rather than"
                    + " paired up by position.", batch.size(), vectors.size());
            return 0;
        }
        int written = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (wrongWidth(vectors.get(i), batch.size())) {
                return written;
            }
            store.attachSummaryEmbedding(batch.get(i).documentId(), vectors.get(i));
            written++;
        }
        return written;
    }

    /**
     * Whether a vector is the wrong width for this corpus.
     *
     * <p><b>Logged and refused rather than thrown</b>, unlike {@link
     * RetrievalService}'s three copies of this check. There the caller asked a
     * question and has to be told its question was never asked; here nobody
     * asked — this is a background repair — and a wrong width is a
     * misconfiguration that will be identically wrong on every row, so throwing
     * would replace one loud log line with a stack trace per document.
     */
    private boolean wrongWidth(float[] vector, int inBatch) {
        if (vector.length == expectedDim) {
            return false;
        }
        log.warn("the corpus is embedded at {} (plowshare.llm.embedding-dim, and vector({}) in"
                        + " V18__documents.sql) and '{}' answered {} wide. No summary vector was"
                        + " written for this batch of {}: a vector in a different space is not a"
                        + " worse ranking, it is no ranking at all.",
                expectedDim, expectedDim, model, vector.length, inBatch);
        return true;
    }
}
