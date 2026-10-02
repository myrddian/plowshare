package io.aeyer.plowshare.server.llm;

import io.aeyer.plowshare.server.llm.dispatch.EmbeddingRequest;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EmbeddingClient} over the dispatcher.
 *
 * <p><b>Narrow — and it is {@code Archive} the narrowness is about, not this
 * class.</b> This class holds an {@link LlmDispatcher} and could perfectly well
 * call {@code complete}, {@code stream} or {@code close} on it. What it does is
 * expose none of that. {@code Archive} holds an {@link EmbeddingClient}, whose
 * whole surface is {@code embed} and {@code embedAll}, and imports nothing from
 * {@code llm.dispatch} at all — so the service that writes memories is
 * structurally unable to make a chat call, name a host, or open a socket, and
 * this class is the single place that boundary is drawn. That is the shape
 * every service that follows gets — one dispatcher, narrow interfaces per
 * capability — and it is enforced by what a caller is able to reach rather than
 * by a rule someone has to remember.
 *
 * <p><b>It also translates.</b> Every {@link LlmException} leaves here as an
 * {@link EmbeddingException}, because that is the type the write path catches
 * and the one {@code ApiExceptionHandler} answers with a {@code 503} — a side
 * service is down, the archive is intact, ask again later. A failure in any
 * other currency escapes {@code Archive.embed} and reaches the agent
 * unclassified as a {@code 500}. That has happened once already.
 *
 * <p><b>What that costs today, stated exactly, because these sentences are the
 * justification for the narrow catch and they were overpricing it.</b> Three
 * comments in this package said such an escape <em>loses the memory</em>. It
 * did, when the boundary was a {@code @Transactional} on every {@code
 * MemoryController} method that wrapped the model call too. It is not true now:
 * the boundary is the programmatic {@code UnitOfWork} that {@code
 * Archive.applyVerdict} closes <em>before</em> calling {@code embed}, so the row
 * is on disk and no failure of any kind can take it back. The cost is a {@code
 * 500} on a committed write — a lie to the agent about what happened, not a
 * lost corpus.
 *
 * <p><b>The condition, so it is not merely hedged but usable.</b> The old
 * sentence becomes true again the moment any transaction encloses the embedding
 * call — a {@code @Transactional} put back on a controller, or a new write path
 * that embeds inside its own unit of work. {@code MemoryController}'s javadoc
 * says in as many words not to do the first. Anyone weighing whether a guard
 * here earns its complexity should price it against a 500, and anyone widening a
 * boundary should price it against the corpus.
 *
 * <p><b>The message, and never the cause chain.</b> {@code
 * ApiExceptionHandler.safeMessage} reads {@code getMessage()} alone, so what is
 * written here is the whole of what an operator is shown. The cause is attached
 * for the log's stack trace and is deliberately not consulted: {@code
 * OpenAiTransport.read} puts Jackson's parse message into an exception, and a
 * translation that walked down to a cause would be free to put a fragment of a
 * body this server did not write into a {@code 503} that a model reads.
 *
 * <h2>What deliberately does not become an {@code EmbeddingException}</h2>
 *
 * <p>The catch below is {@link LlmException} and not {@code RuntimeException},
 * on {@code Archive.embed}'s own reasoning: a clause wide enough to swallow
 * every runtime failure would report a null dereference in the dispatcher as
 * "the endpoint is down, ask again later" — and on the write path that is
 * swallowed and logged, so the archive would quietly stop embedding and nothing
 * would say so. Two consequences, and both are chosen rather than overlooked.
 *
 * <p><b>One is a cost.</b> A {@code RuntimeException} raised below this line in
 * some other currency — OkHttp's {@code IllegalArgumentException} being the one
 * that has actually happened — escapes as a {@code 500} and, on a write path
 * with a wider transaction than today's, would lose the memory. Nothing here
 * can close that. It is closed by {@link
 * io.aeyer.plowshare.server.llm.dispatch.LlmTransport} implementations
 * translating where they call a library, which is what {@code
 * OpenAiTransport.url} exists to do. A new transport that skips that obligation
 * reintroduces the original bug, and this class will not notice.
 *
 * <p><b>The other is correct and must stay that way.</b> Three inputs fail here
 * in the caller's currency and not in the endpoint's: {@code embed(null)} and
 * {@code embedAll(null)} raise {@code NullPointerException}, and a {@code null}
 * element inside the batch reaches {@code EmbeddingRequest}, which names the
 * offending index in an {@code IllegalArgumentException}. Every one of those
 * means the <em>caller</em> is broken, not the endpoint, and a {@code 500}
 * saying the server is broken is the true answer. Dressing them as {@code
 * EmbeddingException} would be worse than untidy: {@code Archive.embed}
 * swallows that type by design, so a null bug in the archive would write
 * memories unembedded forever while the log said the endpoint was down, and the
 * agent would be told to retry something that can never succeed. Compare the
 * blank-model guard in {@code embedAll}, which <em>does</em> translate: that one
 * is a misconfiguration wearing a caller-error type, which is the inverse case
 * and the one that cost a memory. The distinguishing question is never which
 * type was thrown; it is whose fault the failure names.
 *
 * <p>Safe to call from several threads at once, as {@link EmbeddingClient}
 * requires: the dispatcher is, and the only state here is the log-once latch.
 *
 * <p><b>No {@code @Service}, deliberately.</b> This package is component
 * scanned, so a stereotype here makes this an eagerly built singleton that
 * needs an {@link LlmDispatcher} bean. Measured rather than assumed, while
 * {@code LlmConfig} did not yet exist: annotating this {@code @Service} failed
 * all four {@code TransactionBoundaryTest} tests at context startup with {@code
 * NoSuchBeanDefinitionException}, and would have failed {@code EndToEndTest} the
 * same way. {@code LlmConfig} now exists and declares this as a {@code @Bean}
 * beside the dispatcher it depends on, which is also the honest place for it:
 * the two are built from the same properties and have to be refused together at
 * boot if those properties are wrong.
 */
public final class DispatchingEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DispatchingEmbeddingClient.class);

    private final LlmDispatcher dispatcher;
    private final String model;
    private final int expectedDim;

    /**
     * Whether the width has been checked and logged once, and nothing more.
     *
     * <p>Emphatically <b>not</b> "the check has been done, so skip it". An
     * earlier version of this field returned early on it, justified by the
     * width not being able to change under a running server. That was false
     * twice over without leaving this slice: {@code LlmPool.resolve} matches a
     * <em>class</em> name as well as a model name, and {@code
     * LlmDispatcher.route} picks among every pool serving a specifier by load —
     * so two pools mapping one class to wire models of different widths make
     * the width a load reading rather than a setting. And the reference
     * deployment is LM Studio, where the model behind a name is swapped without
     * restarting this server. The width is therefore checked on every batch and
     * this flag only keeps the log line to one.
     */
    private final AtomicBoolean dimLogged = new AtomicBoolean(false);

    /**
     * The largest input, in tokens as {@link #tokenizer} counts them, this
     * endpoint may be sent.
     *
     * <p>{@link #expectedDim}'s counterpart on the side of the call that had no
     * guard at all. Every string handed to {@link #embedAll} is counted against
     * it before anything leaves the process; see {@link #refuseOversized}.
     */
    private final int maxInputTokens;

    /**
     * What {@link #maxInputTokens} is counted with: the server's one {@code
     * Tokenizer} bean, and the same one {@code documents.Chunker} packs by, so
     * a chunk the chunker let through is never one this class refuses.
     *
     * <p>The interface and not a named implementation. The one that ships is a
     * heuristic kept so it can be replaced; this class must go on refusing by
     * whatever replaces it, and never by a length it works out for itself.
     */
    private final Tokenizer tokenizer;

    /**
     * The properties are read once here, and that is a departure from {@code
     * OpenAiTransport}, which re-reads {@code base-url} and {@code api-key} on
     * every call because {@link PoolProperties} is a mutable bean it must be
     * safe to share.
     *
     * <p>The difference is what a stale read would cost. There, re-reading
     * defends against quoting one value while having sent another, on a field
     * that must never be printed. Here, both values are things a corpus is
     * built on rather than things an operator retunes: changing {@code
     * embedding-model} or {@code embedding-dim} under a running server means
     * re-embedding every row, so a snapshot that keeps one process on one model
     * for its whole life is the safer reading of a mid-flight edit, not the
     * more dangerous one.
     *
     * <p>{@code embedding-max-input-tokens} joins them on the same terms and for
     * the same reason: changing it is a re-chunk and a re-embed of the corpus,
     * so a process that keeps the value it started with is the safer reading.
     */
    public DispatchingEmbeddingClient(
            LlmDispatcher dispatcher, LlmProperties props, Tokenizer tokenizer) {
        this.dispatcher = dispatcher;
        this.model = props.getEmbeddingModel();
        this.expectedDim = props.getEmbeddingDim();
        this.maxInputTokens = props.getEmbeddingMaxInputTokens();
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    }

    @Override
    public float[] embed(String text) {
        // Explicit, so the failure names the caller's mistake instead of
        // arriving from inside List.of two frames down. Still a
        // NullPointerException and still a 500: see the class javadoc for why
        // this must not become an EmbeddingException.
        Objects.requireNonNull(text, "embed(null): there is no text to turn into a vector");
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) { return embedAll(texts, UsageAttribution.LEGACY); }

    @Override public float[] embed(String text, UsageAttribution owner) {
        return embedAll(List.of(text), owner).getFirst();
    }

    @Override public List<float[]> embedAll(List<String> texts, UsageAttribution owner) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(texts, "embedAll(null): there is no batch to embed");
        if (texts.isEmpty()) {
            // Not an error and not a round trip. An archive with nothing to
            // embed is an ordinary state on a first run.
            //
            // Ahead of the model guard below, so an empty batch does not report
            // a misconfiguration: there is no embedding to fail, and answering
            // "the endpoint is unusable" to a caller that asked for nothing
            // would be a 503 about work nobody requested. A blank model is
            // reported by the first call that actually needs one.
            return List.of();
        }

        // In this class's currency, and this is the inverse of the null cases
        // above: EmbeddingRequest refuses a blank specifier with an
        // IllegalArgumentException, which is the right answer for a caller that
        // named no model — but this caller does not name one, it reads
        // plowshare.llm.embedding-model. So a blank value is a misconfiguration
        // wearing the type reserved for a bad request. That is the base-url bug
        // exactly: past Archive.embed's narrow catch and out to the agent as a
        // 500 on a row that is already committed — and, on any write path whose
        // transaction encloses the embed call, the memory with it. See this
        // class's javadoc for why that distinction is stated rather than
        // assumed. Task 8's boot check should make this
        // unreachable in a server that started; it is here because "unreachable
        // if someone remembers to validate" is the assumption that produced the
        // incident this class is named for.
        if (model == null || model.isBlank()) {
            throw new EmbeddingException(
                    "no embedding model is configured: set plowshare.llm.embedding-model to a"
                            + " model one of the declared pools serves");
        }
        refuseOversized(texts);

        Embeddings embeddings;
        try {
            embeddings = dispatcher.embed(EmbeddingRequest.of(model, texts).withAttribution(owner));
        } catch (LlmException e) {
            // getMessage() and not the cause chain — see the class javadoc. A
            // null message is survivable rather than handled here: it would
            // reach ApiExceptionHandler.safeMessage, whose fallback names this
            // wrapper ("EmbeddingException") and not the LlmException subtype
            // that fired, which is a poor message but not a wrong status. Every
            // LlmException in this slice is constructed with one.
            throw new EmbeddingException(e.getMessage(), e);
        }

        List<float[]> vectors = embeddings.vectors();
        if (vectors.size() != texts.size()) {
            // Checked again here, having been checked in OpenAiTransport.embed.
            // Deliberate belt-and-braces at a boundary where the mistake is
            // silent and permanent: the response is positional, so a short list
            // attaches every vector after the gap to the wrong text, and no row
            // records that it happened. A transport that forgets the check —
            // and a fake in a test is a transport — is caught here instead.
            throw new EmbeddingException(
                    "asked for " + texts.size() + " embeddings and got " + vectors.size());
        }
        verifyDim(vectors.get(0).length);
        return vectors;
    }

    /**
     * <b>Nothing this endpoint cannot embed is sent to it.</b>
     *
     * <p>{@link #verifyDim} guards what comes back and this guards what goes
     * out, and until this method existed the second half was simply missing:
     * there was no chunker, splitter, truncator or counter anywhere on the
     * embedding path. That was survivable while every embedded string was a
     * short generated label — {@code Archive.embeddedText} is a memory's summary
     * plus its scope, and the body is deliberately not embedded at all — and it
     * stops being survivable the moment a chunk's text, which is unbounded
     * content somebody uploaded, arrives here.
     *
     * <p><b>Refused and not truncated</b>, which is the decision rather than a
     * consequence of one. {@code implementation rationale} records that nobody knows
     * whether this endpoint truncates or refuses a longer input; a truncation,
     * wherever it happened, would produce a vector for text that is not the
     * text, and the row would be perfect, the search would miss it, and nothing
     * would go red. Losing a chunk loudly is cheaper than storing a wrong
     * vector quietly. Cutting an oversized run into pieces is a decision about
     * <em>content</em> and belongs to whoever produced the text — which for
     * documents is {@code documents.Chunker}, where the cut is recorded on the
     * row.
     *
     * <p><b>The whole batch, before any of it is submitted.</b> The endpoint
     * takes an array and a batch fails whole, so checking as it went would leave
     * the caller unable to say which input was the problem — Anchor's
     * {@code EmbeddingService} sends a document's every chunk in one request and
     * discovers exactly that, after the summarisation cascade has been paid for.
     * The index is named for the same reason.
     *
     * <p><b>Counted by the tokenizer, and the refusal says how it knows.</b>
     * The shipped tokenizer estimates, so a refusal reports the count's own
     * account of itself: an operator reading "1750 tokens" should be told it
     * is 7000 characters at four to a token, and not a measurement.
     */
    private void refuseOversized(List<String> texts) {
        if (maxInputTokens < 1) {
            // A misconfiguration wearing a caller-error type, exactly like the
            // blank model above: nobody calling embedAll named this number.
            // LlmConfig.requireEmbeddingMaxInputTokens should make this
            // unreachable in a server that started, and it is here on the same
            // terms as the guard above — "unreachable if someone remembers to
            // validate" is the assumption that produced the incident this class
            // is named for.
            throw new EmbeddingException(
                    "no embedding input ceiling is configured: set"
                            + " plowshare.llm.embedding-max-input-tokens to the largest input, in"
                            + " tokens, this model's context will hold");
        }
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null) {
                // Left for EmbeddingRequest, which already names the index in an
                // IllegalArgumentException. Measuring it here would raise a
                // NullPointerException from inside a size check, which reports a
                // caller's bug in the vocabulary of a bound it has nothing to do
                // with — and this class's javadoc is explicit that a null input
                // must not become an EmbeddingException, because Archive.embed
                // swallows that type by design.
                continue;
            }
            TokenCount count = tokenizer.count(text);
            if (count.tokens() > maxInputTokens) {
                throw new EmbeddingException(
                        "input at index " + i + " is " + count.tokens() + " tokens ("
                                + count.how() + ") and the embedding model takes at most "
                                + maxInputTokens + " (plowshare.llm.embedding-max-input-tokens);"
                                + " it is refused rather than truncated, because a truncated"
                                + " input produces a vector for text that is not the text and"
                                + " nothing downstream can tell that from a correct one");
            }
        }
    }

    /**
     * The vector's width against the schema's, on every batch.
     *
     * <p>{@code vector(768)} is fixed in V1__memories.sql, so a 1024-wide model
     * otherwise surfaces as a Postgres type error under the write path — where
     * it escapes {@code Archive.embed}'s catch as a {@code DataAccessException},
     * i.e. an unclassified {@code 500}, several layers from the setting that
     * caused it, after which every memory written before someone noticed has to
     * be re-embedded.
     *
     * <p><b>Every batch and not just the first.</b> Checking once was this
     * method's original shape and it was wrong; {@link #dimLogged} carries the
     * account of why, since the reasons are about routing rather than about
     * this method.
     *
     * <p><b>The first vector of a batch, and not every vector in it.</b> A batch
     * of mixed widths would still pass. No endpoint produces one — the width is
     * a property of the model and the whole batch goes to one model in one
     * request — so there is no failure to defend against and nothing that could
     * test the defence. Said plainly, because a check that reads as covering a
     * batch and covers one element is worse than one that says which it is.
     *
     * <p><b>This is the one {@code EmbeddingException} for which the {@code
     * 503} body lies, and the trade is taken knowingly.</b> {@code
     * ApiExceptionHandler.embeddingUnavailable} appends "this will work again
     * once the endpoint is back" to every one of them, which for a width
     * mismatch is untrue — as this message's own last clause says. An agent is
     * told to retry something that cannot succeed until an operator acts. The
     * alternative is worse in every case that matters: an unclassified {@code
     * 500} would say the archive is broken when it is intact, and the write path
     * would stop swallowing it, so a misconfigured width would begin destroying
     * writes instead of merely leaving them unembedded. Losing the retry advice
     * is cheaper than losing memories.
     *
     * <p>{@code LlmConfig} has since taken the half of this that a boot check
     * can take: it refuses a non-positive {@code embedding-dim}, which is what
     * produced the worst of these messages — one asserting that {@code
     * vector(0)} is in the schema. The <em>mismatch</em> itself cannot move
     * there and this check is permanent: a model's width is not knowable without
     * calling the model, and the routing reasons on {@link #dimLogged} mean the
     * width is a load reading rather than a setting anyway.
     */
    private void verifyDim(int observed) {
        if (observed != expectedDim) {
            throw new EmbeddingException(
                    "embedding dimension mismatch: expected " + expectedDim
                            + " (plowshare.llm.embedding-dim, and vector(" + expectedDim
                            + ") in the schema) but the model returned " + observed
                            + "; changing the embedding model is a migration, not a setting");
        }
        // CAS, so this is logged once even if two threads arrive together. It
        // is the log line that is once-only, never the check above.
        if (dimLogged.compareAndSet(false, true)) {
            log.info("Embedding dimension verified: {} matches the schema", observed);
        }
    }
}
