package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One question against the corpus: prose in, the nearest chunks out, each carrying the paragraph it
 * is a piece of.
 *
 * <h2>The model coupling, which is the whole of why this class exists</h2>
 *
 * <p>A vector means something only inside the space the model that produced it defines. Two models
 * of the same width produce two incompatible spaces, and a question embedded by the wrong one
 * <b>does not fail</b>: the distances are computed, the rows come back sorted, and the answer is a
 * ranking of nothing. That is the failure this repository names most often and guards against
 * hardest — the row is perfect, the search misses, and nothing goes red.
 *
 * <p><b>So there is exactly one door and it takes text.</b> {@link DocumentStore#searchByVector} is
 * package-private and this is the package's only public read; a caller outside {@code documents}
 * cannot hand over a vector at all, whatever it computed it with. The coupling is therefore
 * structural rather than a rule somebody has to remember, which is the shape {@code
 * DispatchingEmbeddingClient} already argues for one package over: the archive cannot make a chat
 * call because it holds an interface that has no such method, not because it was told not to.
 *
 * <p><b>And the client is the corpus's own.</b> {@code DocumentsConfig} builds this and {@link
 * IngestService} from the one {@link EmbeddingClient} bean the server has, so the vectors on disk
 * and the vector a question is turned into come from the same {@code plowshare.llm.embedding-model}
 * by construction. {@code LlmConfig} refuses at boot a model no pool serves and a width that is not
 * the schema's, and {@code DispatchingEmbeddingClient} re-checks the width on every batch because
 * the reference deployment swaps the model behind a name without restarting this server. {@link
 * #search} checks it once more against the number the schema was written with, for the case those
 * two cannot cover: an {@link EmbeddingClient} that is not the shipped one.
 *
 * <h2>Deliberately not here</h2>
 *
 * <p><b>No {@code Precomputed} overload.</b> {@code Archive.recall} has one, for a caller that
 * already paid for the vector — the scribe, embedding a memory's own text on the write path.
 * Nothing here has that shape, and adding the overload speculatively would build precisely the door
 * the paragraph above says there is not.
 *
 * <p><b>No document filter.</b> Anchor's {@code RetrieveRequest} carries an optional {@code
 * document_id} and its own javadoc calls corpus-wide search "provided for completeness, not what
 * the system is optimised for" — because Anchor's caller is {@code AskService}, which is answering
 * a question about one document somebody named. Plowshare's corpus has no project column and is
 * reachable from everywhere on purpose ("a memory has exactly one home; a document has none"), so
 * the question here is asked of the corpus. A filter is additive when something needs one.
 *
 * <h2>The two halves, and what is done with them</h2>
 *
 * <p><b>Hybrid retrieval is new work here and it was never inherited.</b> {@code implementation
 * rationale} and the v1 design both describe {@code tsvector} + BM25 + reciprocal rank fusion as
 * ported from Anchor; the survey grepped Anchor's whole server tree for {@code tsvector}, {@code
 * to_tsquery}, {@code ts_rank}, {@code BM25} and {@code rrf} and found zero hits. What landed is
 * therefore shaped by this corpus rather than by a predecessor, and it differs from what those two
 * documents describe in one way worth saying out loud: <b>it is not BM25 and it cannot be.</b> BM25
 * is term frequency saturation over an inverse document frequency, and Postgres's full text search
 * keeps no corpus-wide term statistics, so there is nowhere for the IDF term to come from — {@code
 * ts_rank_cd} weighs a word that appears in every chunk exactly as heavily as one that appears in a
 * single chunk. That is answered in two places: {@link DocumentStore#LEXICAL_SQL} conjoins the
 * question's terms, so membership rather than score carries the signal, and the fusion below reads
 * only the ORDER of that list and never its numbers.
 *
 * <p><b>Reciprocal rank fusion, and the alternative it was chosen over.</b> A cosine distance and a
 * cover density rank are not comparable quantities and no weighting makes them so — the obvious
 * fix, normalising each list to 0..1 per query, has a defect that is worse than the problem: a
 * search whose best lexical hit is poor still produces a 1.0 for it, so the weakest list gets
 * exactly as much say as the strongest. RRF discards both scores and keeps only each hit's
 * POSITION, which is the one thing both halves genuinely mean. {@link #RRF_K} carries the
 * arithmetic.
 *
 * <p><b>What happens when one half returns nothing, which is the ordinary case.</b> A conjunctive
 * word search finds nothing for most well-formed questions, by design. A hit's score is a sum of
 * one term per list it appears in, and that term is strictly decreasing in rank, so a hit present
 * in only one list is ordered by its position in that list and by nothing else: <b>an empty lexical
 * half leaves the vector half's ranking exactly as it was.</b> Adding the lexical half to a read
 * that three surfaces already depend on therefore has no silent-degradation mode — the worst it can
 * do is contribute nothing — and the same holds in the other direction.
 *
 * <p><b>And vector-only stays reachable.</b> {@link Mode} is why. With one mode there would be no
 * way to tell a lexical half that has broken from one that is correctly quiet, since both look like
 * a vector-only answer.
 */
public final class RetrievalService implements UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;
  private InformationContext usageContext;

  private UsageAttribution queryOwner() {
    var home =
        usageContext == null || usageContext.selection().project() == null
            ? io.aeyer.plowshare.protocol.Home.global()
            : io.aeyer.plowshare.protocol.Home.of(usageContext.selection().project());
    return usageOwners.in(
        home,
        usageContext == null ? null : usageContext.account(),
        UsageAttribution.Operation.EMBEDDING_QUERY);
  }

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  public boolean accountingEnabled() {
    return usageOwners != UsageOwners.NONE;
  }

  public UsageAttribution usage(
      io.aeyer.plowshare.protocol.Home home, String handle, UsageAttribution.Operation operation) {
    return usageOwners.in(home, handle, operation);
  }

  /**
   * How many chunks one search returns when the caller does not say.
   *
   * <p>Anchor's {@code RetrieveController.DEFAULT_K} is 10 and {@code
   * MemoryController.DEFAULT_RECALL_LIMIT} copied it. This is half that, and the difference is what
   * a hit is made of: a memory's summary is a sentence somebody wrote to be read at a glance, and a
   * chunk is up to {@code plowshare.llm.embedding-max-input-tokens} of somebody else's document.
   */
  public static final int DEFAULT_HITS = 5;

  /**
   * The most one search returns, however large a number arrives.
   *
   * <p><b>The number answers to how much uploaded text may land in one prompt.</b> Chunk packing
   * aims at 400 tokens, about 1600 characters of prose, so ten ordinary hits is about sixteen
   * thousand characters — already a real share of {@code FileTools.MAX_DISPLAY_CHARS} and of a
   * local model's window, and every byte of it is text this server did not write.
   *
   * <p><b>Not re-derived when the ceiling moved from bytes to tokens, and the worst case moved with
   * it.</b> This number was set against a ceiling of 2048 bytes a chunk, twenty thousand characters
   * for ten. The ceiling is now 1536 tokens, about six thousand characters of prose, and only a
   * piece cut from an oversized run — a re-flowed table, a reference list — reaches it; ten of
   * those is about sixty thousand. Whether that wants a character budget across hits, a lower cap,
   * or nothing, is open. {@code MemoryTools.MAX_LIMIT} is 20 because twenty summaries are twenty
   * short lines; nothing about that number carries over, and deriving this one from it would be
   * borrowing an argument about a different quantity.
   *
   * <p><b>There is also a floor under it that is not a judgement.</b> pgvector's {@code
   * hnsw.ef_search} defaults to 40 and an HNSW scan returns at most that many rows, so a limit
   * above it comes back short — quietly, with no error and no way to tell a truncated answer from a
   * small corpus. Ten is comfortably under, and anything approaching forty would have to move that
   * setting first.
   */
  public static final int MAX_HITS = 10;

  /**
   * How deep each half is read before the two are fused, and <b>the number is pgvector's rather
   * than a preference</b>.
   *
   * <p>{@code hnsw.ef_search} defaults to 40 and an HNSW scan returns at most that many rows. A
   * vector candidate pool deeper than 40 therefore comes back short — quietly, with no error and no
   * way to tell a truncated list from a small corpus — so 40 is not a tuning choice but the deepest
   * this index can honestly be asked for. Wanting more is not a change to this constant; it is a
   * {@code SET hnsw.ef_search} on the connection and a re-argument of {@link #MAX_HITS}'s floor,
   * which is why {@code MAX_HITS} is 10 and stays there.
   *
   * <p><b>Both halves are read to the same depth, and that is not symmetry for its own sake.</b>
   * Reciprocal rank fusion over two lists of different depths is lopsided: the deeper list can
   * award a small score to a chunk the shallower one has no position left to award anything to at
   * all, so the deeper side quietly decides the tail of every answer.
   *
   * <p>It is deeper than {@link #MAX_HITS} on purpose. A chunk that is ninth on one list and second
   * on the other is exactly the hit fusion exists to promote, and it could not be promoted out of
   * two lists already cut to five. The cut to the caller's limit is the last thing that happens.
   */
  public static final int CANDIDATE_DEPTH = 40;

  /**
   * The constant in {@code 1 / (k + rank)}, at the value the method was published with.
   *
   * <p>Cormack, Clarke and Buettcher's 60, and what it does is worth stating rather than
   * inheriting: <b>k decides how much a first place is worth against agreement.</b> A hit first on
   * one list and absent from the other scores {@code 1/61}; a hit third on both scores {@code 1/63
   * + 1/63}, which is nearly double it. So two independent readings that both liked a passage
   * outrank one reading that loved it, which is the entire proposition of searching twice. A k near
   * zero would invert that and let each list's first place dominate; a much larger k flattens every
   * rank difference until the ranking is just "how many halves found it".
   *
   * <p>It is not calibrated against this corpus and does not pretend to be. Calibrating it needs
   * judged queries, which is an evaluation harness and its own slice; the published default is a
   * stated starting point rather than a measurement wearing one's clothes.
   */
  static final int RRF_K = 60;

  /**
   * Which halves of the corpus a search reads.
   *
   * <p><b>Not a knob for a model.</b> {@code DocumentTools} does not offer it and its schema has no
   * field for it: an agent choosing a retrieval strategy is being asked a question about this
   * server's indexes that nothing in its context could answer. It is reachable from Java and from
   * {@code POST /v1/documents/search}, whose caller is an operator.
   *
   * <p>The two single-half modes exist for one reason and it is not completeness. A conjunctive
   * word search is correctly silent for most well-formed questions, so a lexical half that has
   * broken and one that is working as designed produce the same hybrid answer; running each half
   * alone against the same question is the only way to tell them apart.
   */
  public enum Mode {

    /**
     * Both halves, fused by {@link RetrievalService#RRF_K}. What every surface asks for unless it
     * says otherwise.
     */
    HYBRID,

    /**
     * Meaning only — the search as it stood before the lexical half existed, unchanged and still
     * the fallback every hybrid answer degenerates to.
     */
    VECTOR,

    /**
     * Words only. Finds nothing for most questions and everything for a few, which is what makes it
     * a diagnosis rather than a search.
     */
    LEXICAL
  }

  private final DocumentStore store;
  private final EmbeddingClient embeddings;
  private final String model;
  private final int expectedDim;

  /**
   * @param embeddings <b>the same client the corpus was embedded with.</b> Not "an" embedding
   *     client: see the class javadoc
   * @param model what that client is configured to call, carried only so a refusal can name it.
   *     Never sent anywhere from here — the client owns the call and reads the property itself
   * @param expectedDim {@code plowshare.llm.embedding-dim}, which is also the {@code vector(n)}
   *     width V18 wrote into {@code chunks.embedding}
   */
  public RetrievalService(
      DocumentStore store, EmbeddingClient embeddings, String model, int expectedDim) {
    this.store = Objects.requireNonNull(store, "store");
    this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    this.model = Objects.requireNonNull(model, "model");
    this.expectedDim = expectedDim;
  }

  public RetrievalService audited(java.util.function.Consumer<UUID> audit) {
    return new RetrievalService(store.audited(audit), embeddings, model, expectedDim);
  }

  public RetrievalService scoped(InformationAccess access, InformationContext context) {
    RetrievalService copy =
        new RetrievalService(store.scoped(access, context), embeddings, model, expectedDim);
    copy.usageOwners = usageOwners;
    copy.usageContext = context;
    return copy;
  }

  public RetrievalService checkingConfiguration() {
    RetrievalService copy =
        new RetrievalService(
            store.embeddingSpace(model, expectedDim), embeddings, model, expectedDim);
    copy.usageOwners = usageOwners;
    copy.usageContext = usageContext;
    return copy;
  }

  /**
   * Ask the corpus a question.
   *
   * <p><b>What the corpus could not be asked is read first</b>, and that ordering buys two things.
   * A corpus with no embedded chunk in it is answered without an endpoint call at all — there is no
   * ranking for a vector to produce, and paying a model to compute one would be spending on a
   * question already settled. And every answer, empty or not, carries the count of chunks no
   * question can reach, which is what stops an empty result being read as "the corpus holds nothing
   * about this" when what is true is "the corpus holds it and has no vector for it".
   *
   * <p>The counts are taken a moment before the search rather than inside it. A concurrent ingest
   * can therefore move them between the two, which makes the reported figures a description of the
   * corpus rather than a transaction over it — {@code Archive.recall} takes its inside one unit of
   * work and can, because both halves are one statement each against a table of hundreds.
   *
   * @param query what is being asked, in prose. It is embedded here
   * @param limit how many hits at most, clamped to {@link #MAX_HITS}. The caller's number survives
   *     on {@link Found#asked} so that a surface can say both
   * @throws IllegalArgumentException if the query is blank, or the limit is not at least one.
   *     <b>Refused rather than answered</b>, and not the empty result {@code Archive.recall}
   *     returns for the same input: an answer with no hits in it is a claim about the corpus, and a
   *     caller that asked for none has not made a question this class can make that claim about
   * @throws EmbeddingException if the question could not be embedded, or came back a width the
   *     schema cannot hold. Not swallowed: a search with no query vector has nothing to rank by,
   *     and rows in some fallback order are indistinguishable from a search that worked
   */
  public Found search(String query, int limit) {
    return search(query, limit, Mode.HYBRID);
  }

  /**
   * Ask the corpus a question, reading the halves this mode names.
   *
   * <p>Everything {@link #search(String, int)} documents holds here — it is that method — and this
   * one adds only the choice of halves. <b>The question is embedded once whatever the mode</b>,
   * including for {@link Mode#LEXICAL}, which does not rank by the vector: {@link
   * DocumentStore#searchByText} selects the cosine distance so that a hit found on words and a hit
   * found on meaning are the same record with the same number beside them, and no surface has to
   * learn a second kind of hit.
   *
   * @param mode which halves to read. {@link Mode#HYBRID} unless there is a reason, and the reason
   *     is never a model's preference
   */
  public Found search(String query, int limit, Mode mode) {
    return search(query, limit, mode, queryOwner());
  }

  public Found search(String query, int limit, Mode mode, UsageAttribution owner) {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(query, "query");
    if (query.isBlank()) {
      throw new IllegalArgumentException(
          "a search needs a question; this one is blank. Nothing was searched, which"
              + " is not the same as nothing being found");
    }
    if (limit < 1) {
      throw new IllegalArgumentException(
          "a search was asked for "
              + limit
              + " hits; it must be at least 1. Asking"
              + " for none is not the same as finding none, and this returns an"
              + " empty answer only for a corpus that really held nothing close");
    }
    int applied = Math.min(limit, MAX_HITS);

    DocumentStore.Coverage coverage = store.coverage();
    if (coverage.searchable() == 0) {
      return new Found(query, limit, applied, mode, List.of(), coverage);
    }

    float[] vector =
        EmbeddingClient.owned(
            embeddings,
            query,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName()));
    if (vector.length != expectedDim) {
      // Unreachable through DispatchingEmbeddingClient, which verifies the
      // width of every batch itself and says so in its own vocabulary.
      // Reachable through the interface, which promises nothing of the
      // sort — and what happens without this is a DataAccessException out
      // of pgvector's type check, several layers from the property that
      // caused it, on a read whose caller is often a model that will
      // rephrase the question and try again forever.
      throw new EmbeddingException(
          "the corpus is embedded at "
              + expectedDim
              + " (plowshare.llm.embedding-dim,"
              + " and vector("
              + expectedDim
              + ") in V18__documents.sql) and the"
              + " query came back "
              + vector.length
              + " wide from '"
              + model
              + "'. Nothing was searched: a question in a different space is not a"
              + " worse ranking, it is no ranking at all. Changing the embedding"
              + " model is a re-embed of the corpus, not a setting");
    }
    return new Found(query, limit, applied, mode, read(query, vector, applied, mode), coverage);
  }

  /**
   * The passages of <b>one document</b> nearest this question, nearest first.
   *
   * <h2>Why the ask comes through here and not straight to the store</h2>
   *
   * <p>This class's whole argument is the model coupling, and it does not weaken one scope down:
   * the corpus's vectors and a question's vector have to come from one model, and a mismatch does
   * not fail — the distances compute, the rows sort, and the answer is a ranking of nothing. So
   * {@code DocumentStore.searchWithinDocument} is package-private with this as its only caller, and
   * the deliberation holds no {@link EmbeddingClient} of its own.
   *
   * <h2>Three things this deliberately does not do</h2>
   *
   * <ul>
   *   <li><b>No lexical half and no fusion.</b> That is a fact about Anchor rather than a
   *       simplification: its ask ranks by cosine distance and nothing else — no {@code tsvector},
   *       no {@code ts_rank}, no reciprocal-rank fusion anywhere in its server tree. There is no
   *       {@link Mode} parameter here because there is nothing to choose.
   *   <li><b>No {@link #MAX_HITS} cap.</b> Ten is the bound on a surface a model chooses to call
   *       and pays for out of a conversation, and the fifteen Anchor retrieves is a fixed cost of
   *       one deliberation that nothing asked for. {@code DocumentStore.MOST_PASSAGES} is the bound
   *       that still applies, one layer down.
   *   <li><b>No {@code coverage} short-circuit.</b> Coverage is one number for the whole corpus,
   *       and this read is about one document: a corpus whose other papers are unembedded says
   *       nothing about this one, and refusing on it would refuse an answerable ask. {@code
   *       DocumentStore.countUnembedded} is the per-document counterpart, and telling an empty
   *       answer from an unembedded document is the caller's duty rather than this method's.
   * </ul>
   *
   * @param documentId the document to answer from. One holding nothing — or none at all — is an
   *     empty list
   * @param query what is being asked, in prose. Embedded here
   * @param limit how many passages at most
   * @throws EmbeddingException if the question could not be embedded, or came back at a width the
   *     corpus is not stored at
   */
  public List<DocumentStore.Passage> within(UUID documentId, String query, int limit) {
    return within(documentId, query, limit, queryOwner());
  }

  public List<DocumentStore.Passage> within(
      UUID documentId, String query, int limit, UsageAttribution owner) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(query, "query");
    if (query.isBlank()) {
      throw new IllegalArgumentException(
          "an ask needs a question; this one is blank. Nothing was searched, which is"
              + " not the same as nothing being found");
    }
    if (limit < 1) {
      throw new IllegalArgumentException(
          "an ask was asked for "
              + limit
              + " passages; it must be at least 1. Asking"
              + " for none is not the same as finding none");
    }
    float[] vector =
        EmbeddingClient.owned(
            embeddings,
            query,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName()));
    if (vector.length != expectedDim) {
      // The same refusal as search(), in the same words and for the same
      // reason. Not factored into a shared helper: the two messages name
      // different reads and a caller landing on this one is asking a
      // document rather than a corpus, which is the first thing it needs
      // told.
      throw new EmbeddingException(
          "the corpus is embedded at "
              + expectedDim
              + " (plowshare.llm.embedding-dim,"
              + " and vector("
              + expectedDim
              + ") in V18__documents.sql) and the"
              + " question came back "
              + vector.length
              + " wide from '"
              + model
              + "'. Nothing was searched: a question in a different space is not a"
              + " worse ranking, it is no ranking at all. Changing the embedding"
              + " model is a re-embed of the corpus, not a setting");
    }
    return store.searchWithinDocument(documentId, vector, limit);
  }

  /**
   * How many chunks a retrieve answers with when the caller names no number. Anchor's {@code
   * RetrieveController.DEFAULT_K}, unchanged.
   */
  public static final int DEFAULT_RETRIEVED = 10;

  /**
   * <b>The corpus, or one document, with every tier above each chunk attached.</b>
   *
   * <p>Anchor's {@code POST /retrieve}, and the one read of its API that answers "here is a passage
   * and here is what the paper around it argues" in a single round trip. {@link #search} answers a
   * narrower question — which paragraph to cite — and {@link #within} answers a narrower one still,
   * for one caller that already holds the hierarchy.
   *
   * <h2>Three things this does not do, each for a reason one of the other two already gives</h2>
   *
   * <ul>
   *   <li><b>No lexical half and no fusion</b>, which is {@link #within}'s point about Anchor
   *       rather than a simplification: Anchor's retrieve ranks by cosine distance and nothing
   *       else. There is no {@link Mode} parameter because there is nothing to choose.
   *   <li><b>No {@link #MAX_HITS} cap.</b> Ten is the bound on the tool a model calls out of a
   *       conversation's allowance; this is a read a caller pays for itself, and Anchor clamps it
   *       at a hundred. {@code DocumentStore.MOST_RETRIEVED} is the bound that applies.
   *   <li><b>No {@code coverage} short-circuit and no {@code Found} envelope.</b> {@link Found}
   *       exists to say which kind of empty an empty answer is, and it can, because {@link #search}
   *       is always over the whole corpus. This read is over the corpus <em>or</em> one document,
   *       and one corpus-wide number said of a document-scoped answer would be a claim nothing
   *       checked — the objection {@link #within} raises against the same short-circuit. A caller
   *       that wants it asks {@link DocumentStore#coverage} or {@code countUnembedded} and gets the
   *       one that matches its scope.
   * </ul>
   *
   * @param query what is being asked, in prose. Embedded here
   * @param documentId the document to read, or <b>{@code null} for the whole corpus</b>. Anchor's
   *     own javadoc calls corpus-wide retrieval "provided for completeness, not what the system is
   *     optimised for", and its shell never reaches it — every command that retrieves is
   *     unavailable until a document is bound
   * @param limit how many chunks at most
   * @throws IllegalArgumentException for a blank question or a limit under one, in {@link
   *     #within}'s currency: this is a public method with more than one caller and the HTTP surface
   *     maps these itself
   * @throws EmbeddingException if the question could not be embedded, or came back at a width the
   *     corpus is not stored at
   */
  public List<DocumentStore.Retrieved> retrieve(String query, UUID documentId, int limit) {
    return retrieve(query, documentId, limit, queryOwner());
  }

  public List<DocumentStore.Retrieved> retrieve(
      String query, UUID documentId, int limit, UsageAttribution owner) {
    Objects.requireNonNull(query, "query");
    if (query.isBlank()) {
      throw new IllegalArgumentException(
          "a retrieve needs a question; this one is blank. Nothing was searched, which"
              + " is not the same as nothing being found");
    }
    if (limit < 1) {
      throw new IllegalArgumentException(
          "a retrieve was asked for "
              + limit
              + " chunks; it must be at least 1."
              + " Asking for none is not the same as finding none");
    }
    float[] vector =
        EmbeddingClient.owned(
            embeddings,
            query,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName()));
    if (vector.length != expectedDim) {
      // The third spelling of this refusal in this file, and it is not
      // factored into a helper for the reason the second one gives: the
      // messages name different reads, and a caller landing here asked for
      // a chunk's whole ancestry rather than a paragraph to cite.
      throw new EmbeddingException(
          "the corpus is embedded at "
              + expectedDim
              + " (plowshare.llm.embedding-dim,"
              + " and vector("
              + expectedDim
              + ") in V18__documents.sql) and the"
              + " question came back "
              + vector.length
              + " wide from '"
              + model
              + "'. Nothing was retrieved: a question in a different space is not a"
              + " worse ranking, it is no ranking at all. Changing the embedding"
              + " model is a re-embed of the corpus, not a setting");
    }
    return store.retrieve(documentId, vector, limit);
  }

  /**
   * How many documents a ranking answers with when the caller names no number. Anchor's {@code
   * DEFAULT_SEARCH_K} on the same read, unchanged.
   */
  public static final int DEFAULT_RANKED = 20;

  /**
   * <b>Which papers are about this, without touching a passage.</b>
   *
   * <p>Anchor's {@code GET /documents/search}, and the read V27's column was added for. One vector
   * comparison per document, where {@link #search} compares against every chunk in the corpus and
   * then leaves a caller to decide what a single passage says about the paper it came from.
   *
   * <p><b>Topical relevance and not stance.</b> A document that spends forty pages demolishing a
   * claim is close to that claim in cosine space; that is the correct answer to "which paper is
   * about this" and the wrong answer to "which paper supports this". {@link #stance} is the read
   * for the second question and it is a heuristic; the deliberation is the read that actually
   * answers it.
   *
   * @param query what is being asked, in prose. Embedded here and compared against each document's
   *     <b>summary</b> — a model's sentence about the paper, not the paper's own words
   * @param limit how many documents at most
   * @throws IllegalArgumentException for a blank question or a limit under one
   * @throws EmbeddingException if the question could not be embedded, or came back at a width the
   *     corpus is not stored at
   */
  public Ranking rank(String query, int limit) {
    return rank(query, limit, queryOwner());
  }

  public Ranking rank(String query, int limit, UsageAttribution owner) {
    Objects.requireNonNull(query, "query");
    if (query.isBlank()) {
      throw new IllegalArgumentException(
          "a ranking needs a question; this one is blank. Nothing was ranked, which is"
              + " not the same as nothing being close");
    }
    if (limit < 1) {
      throw new IllegalArgumentException(
          "a ranking was asked for "
              + limit
              + " documents; it must be at least 1."
              + " Asking for none is not the same as finding none");
    }
    return new Ranking(
        store.rankBySummary(embedded(query, "ranked", owner), limit), store.ranking());
  }

  /**
   * What a ranking found, and what it could not see.
   *
   * <p>{@link Found}'s shape for documents, and it carries the corpus's own counts for that
   * record's reason: an empty list is a conclusion a caller acts on, so <em>nothing is close</em>
   * has to be distinguishable from <em>nothing has been embedded</em>. Here the second is not a
   * rare failure — a document is summarised before anything embeds the summary, so a corpus
   * ingested five minutes ago and never restarted can be entirely unrankable while every word of it
   * is searchable.
   */
  public record Ranking(List<DocumentStore.Ranked> documents, DocumentStore.Ranking corpus) {

    public Ranking {
      documents = List.copyOf(documents);
      Objects.requireNonNull(corpus, "corpus");
    }
  }

  /**
   * <b>A vector-only guess at what one document says about a claim.</b>
   *
   * <p>Anchor's {@code POST /validate/quick}: embed the question and {@code "not " + question},
   * score both against the document's summary vector, and return the two numbers and their
   * difference. No model call, one statement, and Anchor's stated purpose is a pre-filter over a
   * corpus too large to deliberate on.
   *
   * <p><b>It is a heuristic and this method will not be the place that forgets it.</b> The
   * mechanism is that {@code "not X"} embeds near the passages a document uses to argue against X,
   * and nothing here or in Anchor checks that any particular embedding model does that. {@code
   * DocumentStore.Stance} carries the argument in full. What answers the question properly is
   * {@code POST /v1/documents/&#123;id&#125;/ask}, which costs three model calls and reads the
   * paper.
   *
   * <p><b>The negation is built here and not by the caller</b>, because the score means nothing
   * unless both vectors come from the same construction — a caller that sent its own "opposite"
   * would get a number in the same range measuring something else. It is {@code "not "} and
   * English, which is Anchor's and is a limitation rather than a choice; nothing on this server
   * knows what language a document is in.
   *
   * @return empty for a document the corpus does not hold <b>or</b> one whose summary has no
   *     vector, which the caller has to tell apart itself — {@code DocumentStore.find} answers the
   *     first
   * @throws EmbeddingException if either vector could not be made, or came back at a width the
   *     corpus is not stored at
   */
  public Optional<DocumentStore.Stance> stance(UUID documentId, String query) {
    return stance(documentId, query, queryOwner());
  }

  public Optional<DocumentStore.Stance> stance(
      UUID documentId, String query, UsageAttribution owner) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(query, "query");
    if (query.isBlank()) {
      throw new IllegalArgumentException(
          "a stance needs a claim to score; this one is blank. Nothing was scored,"
              + " which is not the same as scoring zero");
    }
    // One batch and not two calls, which is what `embedAll` exists for and
    // what Anchor's own comment on this path says: two round trips for two
    // strings that are asked together.
    List<float[]> both =
        EmbeddingClient.owned(
            embeddings,
            List.of(query, "not " + query),
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName()));
    if (both.size() != 2) {
      throw new EmbeddingException(
          "a stance is two vectors — the claim and its negation — and '"
              + model
              + "' answered with "
              + both.size()
              + ". Nothing was scored: a"
              + " difference between one number and a number that does not exist"
              + " is not a weaker reading, it is no reading at all");
    }
    for (float[] vector : both) {
      if (vector.length != expectedDim) {
        throw new EmbeddingException(widthRefusal(vector.length, "scored"));
      }
    }
    return store.stanceOf(documentId, both.get(0), both.get(1));
  }

  /**
   * The question as a vector, refused if it is the wrong width.
   *
   * <p><b>Introduced with the fourth copy of this check and the three that came before it are
   * deliberately left standing.</b> Each of them names the read a caller landed on — a corpus
   * search, a document's passages, a chunk's ancestry — and their javadoc says in as many words
   * that the message doing that is the point. What is shared here is the sentence itself, with the
   * verb as an argument, so a new read gets the wording without a fifth copy of it and the old
   * three keep the messages that were argued for.
   *
   * @param past what did not happen to the caller's question — "ranked", "scored" — which is the
   *     one word that differs between these
   */
  private float[] embedded(String query, String past, UsageAttribution owner) {
    float[] vector =
        EmbeddingClient.owned(
            embeddings,
            query,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName()));
    if (vector.length != expectedDim) {
      throw new EmbeddingException(widthRefusal(vector.length, past));
    }
    return vector;
  }

  private String widthRefusal(int got, String past) {
    return "the corpus is embedded at "
        + expectedDim
        + " (plowshare.llm.embedding-dim, and"
        + " vector("
        + expectedDim
        + ") in V18__documents.sql) and the question came"
        + " back "
        + got
        + " wide from '"
        + model
        + "'. Nothing was "
        + past
        + ": a"
        + " question in a different space is not a worse ranking, it is no ranking at"
        + " all. Changing the embedding model is a re-embed of the corpus, not a"
        + " setting";
  }

  /**
   * The halves this mode asks for, in one list.
   *
   * <p>A single-half mode is asked for exactly what it will return, because there is nothing to
   * fuse and reading deeper would be paying for rows to throw away. Hybrid reads both to {@link
   * #CANDIDATE_DEPTH} and cuts after fusing, which is the whole point of the depth.
   */
  private List<DocumentStore.Hit> read(String query, float[] vector, int limit, Mode mode) {
    return switch (mode) {
      case VECTOR -> store.searchByVector(vector, limit);
      case LEXICAL -> store.searchByText(query, vector, limit);
      case HYBRID ->
          fuse(
              store.searchByVector(vector, CANDIDATE_DEPTH),
              store.searchByText(query, vector, CANDIDATE_DEPTH),
              limit);
    };
  }

  /**
   * <b>Reciprocal rank fusion: two orderings in, one ordering out.</b>
   *
   * <p>Each hit scores {@code 1 / (RRF_K + rank)} for every list it appears in, ranks counted from
   * one, and the sum is the order. Nothing about either list's own numbers survives — not a cosine
   * distance and not a cover density — which is what makes it safe to fuse a calibrated quantity
   * with an uncalibrated one.
   *
   * <p><b>A hit found by both halves is one hit</b>, scored twice and rendered once; the record
   * kept is the vector half's, which is arbitrary and harmless, since {@link DocumentStore.Hit} is
   * the same row read the same way by both queries.
   *
   * <p><b>The tie-break, which matters more than it looks.</b> Two hits can score identically —
   * first on one list and first on the other is the common case — and a {@code List#sort} that left
   * them in encounter order would put the vector half first for no stated reason. Distance
   * ascending is the second key, which is a real quantity both hits carry, and the chunk id is the
   * third so that the order is total; it is {@link DocumentStore#searchByVector}'s own tie-break,
   * one layer up.
   *
   * <p>Over at most {@code 2 × CANDIDATE_DEPTH} rows, so the cost of doing this in Java rather than
   * in SQL is nothing, and what it buys is that neither query has to change shape to accommodate
   * the other — each keeps the plan its own index serves, which the two {@code EXPLAIN} tests over
   * those constants are what protect.
   */
  private static List<DocumentStore.Hit> fuse(
      List<DocumentStore.Hit> byVector, List<DocumentStore.Hit> byText, int limit) {
    Map<UUID, Double> scores = new HashMap<>();
    Map<UUID, DocumentStore.Hit> found = new LinkedHashMap<>();
    contribute(byVector, scores, found);
    contribute(byText, scores, found);
    return found.values().stream()
        .sorted(
            Comparator.comparingDouble((DocumentStore.Hit hit) -> -scores.get(hit.chunkId()))
                .thenComparingDouble(DocumentStore.Hit::distance)
                .thenComparing(hit -> hit.chunkId().toString()))
        .limit(limit)
        .toList();
  }

  /** One list's reciprocal ranks, added to whatever the other half already contributed. */
  private static void contribute(
      List<DocumentStore.Hit> side, Map<UUID, Double> scores, Map<UUID, DocumentStore.Hit> found) {
    for (int i = 0; i < side.size(); i++) {
      DocumentStore.Hit hit = side.get(i);
      scores.merge(hit.chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
      found.putIfAbsent(hit.chunkId(), hit);
    }
  }

  /**
   * What one search came to, with everything a surface needs to word it honestly.
   *
   * @param query what was asked, echoed back. Anchor's {@code RetrieveResponse} does the same, for
   *     the reason {@code RecallResponse} records: a result in a log cannot be read without the
   *     request beside it otherwise
   * @param asked the limit the caller sent, which may be larger than {@link #limit}. <b>Both
   *     numbers, because a shortened answer and the end of the answer are the same list</b> — the
   *     shape {@code FileTools.MAX_READ_LINES} and {@code ResultTools.MOST_LISTED} set
   * @param limit what was actually applied, after {@link RetrievalService#MAX_HITS}
   * @param mode which halves were read. Echoed for {@link #query}'s reason: a hybrid answer and a
   *     vector-only answer over one question are two different claims, and a result in a log cannot
   *     be read without knowing which one it is
   * @param hits the chunks, best first — <b>which is not the same as nearest first</b> under {@link
   *     Mode#HYBRID}: the order is the fused one, so a hit's {@code similarity} is a fact about
   *     that hit rather than the key the list is sorted on
   * @param coverage what the corpus could and could not be asked
   */
  public record Found(
      String query,
      int asked,
      int limit,
      Mode mode,
      List<DocumentStore.Hit> hits,
      DocumentStore.Coverage coverage) {

    public Found {
      hits = List.copyOf(hits);
    }

    /** Chunks a question can reach at all. */
    public int searchable() {
      return coverage.searchable();
    }

    /**
     * Chunks whose text is stored and whose vector is not. Zero means an empty answer is complete.
     */
    public int unsearchable() {
      return coverage.unsearchable();
    }

    /**
     * Whether there was anything to search.
     *
     * <p><b>Not the same question as "were there hits".</b> A corpus nobody has filled and a corpus
     * nobody can ask both answer with an empty list, and only one of them is worth repairing — so
     * the two are told apart here rather than left to each surface to infer from a pair of counts.
     */
    public boolean corpusIsEmpty() {
      return coverage.searchable() == 0;
    }

    /** Whether the caller's number was cut down to {@link RetrievalService#MAX_HITS}. */
    public boolean wasCapped() {
      return asked > limit;
    }
  }
}
