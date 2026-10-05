package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One ingest: text in, a stored and embedded document out.
 *
 * <p>Anchor's {@code IngestService} is 478 lines and orchestrates a four-level summariser cascade,
 * a metadata extractor and a phase/percent progress budget. <b>None of that is this slice.</b> The
 * cascade is what the 242 model calls are; this pipeline makes <em>no chat call at all</em>, which
 * is what lets the whole of it be tested without a model.
 *
 * <h2>Text first, then vectors, in bounded batches</h2>
 *
 * <p>{@link DocumentStore#write} commits the document, its paragraphs and its chunks with no
 * vectors, in one transaction, and returns. Only then is the endpoint called. The reason is in that
 * class and in {@code UnitOfWork}: a model call inside a transaction has already, once, discarded
 * every committed memory a server wrote.
 *
 * <p><b>Bounded batches, and this is the second defect the survey found.</b> Anchor's {@code
 * EmbeddingService.embedAll} submits every chunk of a document as ONE HTTP request. Three things
 * follow and all three are bad: one oversized chunk fails the entire document — after the
 * summarisation cascade has been paid for; a large document is a single request nothing can retry a
 * part of; and there is no boundary at which the work can be stopped or resumed. Here a batch is
 * {@code plowshare.documents.embed-batch-size} chunks, a failure costs one batch, and the boundary
 * between batches is where cancellation lands.
 *
 * <h2>What an outage leaves behind, and why that is the design</h2>
 *
 * <p>A failed batch ends the ingest {@link Outcome.Ending#UNAVAILABLE} with the document's text
 * intact and some chunks still holding no vector. That is V1's rule for memories at corpus scale —
 * the endpoint being down loses the vector and never the write — and it is <b>resumable</b>: the
 * next ingest of the same document finds those chunks from their own rows and embeds them, deriving
 * nothing again and needing no state outside the database to have survived.
 *
 * <p>The outcome says how many chunks are unsearchable, because a document stored with no vectors
 * is invisible to every search and an ingest that reported plain success would leave somebody
 * interrogating a corpus that cannot answer.
 *
 * <h2>Then the cascade, and it is an ingest's own allowance that pays for it</h2>
 *
 * <p>{@link Summariser} labels every paragraph with what it claims and folds those upward until one
 * sentence stands for the document. It is the expensive half by three orders of magnitude — one
 * model call a paragraph, so about ~220 for a 30-page document at a measured 6.5 s median, half an
 * hour serial — and it is the whole reason this pipeline is worth having inside Plowshare rather
 * than beside it, because every one of those calls goes through {@code JobRuntime} and therefore
 * keeps a log, a budget, a turn row and a trajectory.
 *
 * <p><b>An ingest is a job with its own allowance, exactly as a curator pass is.</b> {@code
 * agents.curator.Passes.start} mints {@code Budget.of(plowshare .curator-budget)} for a server-side
 * job that does not spend its caller's; this mints {@code
 * Budget.of(plowshare.documents.ingest-budget)}. That is the answer to the collision the survey
 * names — 242 calls against an interlocutor's allowance of 40 — and it is a precedent followed
 * rather than an exception invented.
 *
 * <p><b>And that allowance is read when the ingest starts, not when the server did.</b> {@code
 * ingest-budget} is a live key, so this pipeline holds the properties object rather than a number
 * and asks it once, as its first act, before anything is derived. The comment on that line is where
 * the argument for <em>once, and there</em> lives.
 *
 * <h2>Vectors before summaries, which is the opposite of Anchor's order</h2>
 *
 * <p>Anchor summarises, then embeds, then persists, all of it before anything is readable. Here the
 * text is committed first, then the vectors, and only then the cascade — so a document is
 * <b>searchable within seconds</b> and gains its labels over the following half hour. The failure
 * that ordering avoids is specific: a cascade that ran first and then met a dead embedding endpoint
 * would have spent half an hour of model calls on a document no search can reach.
 *
 * <p><b>A failed embedding does not start a cascade.</b> Whatever took the embedding endpoint away
 * is usually the same box the chat endpoint is on, and beginning two hundred model calls to
 * discover that is the shape {@code Summariser} declines one paragraph at a time.
 */
public final class IngestService implements UsageAware {
  private DualEmbeddings dualEmbeddings;

  public void useDualEmbeddings(DualEmbeddings embeddings) {
    dualEmbeddings = java.util.Objects.requireNonNull(embeddings);
  }

  private UsageOwners usageOwners = UsageOwners.NONE;

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

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  private final DocumentStore store;
  private final EmbeddingClient embeddings;
  private final Clock clock;
  private final Chunking chunking;
  private final int embedBatchSize;

  /**
   * The cascade, or null for an ingest that stores and embeds and labels nothing.
   *
   * <p><b>Nullable, and unlike {@code Curator.logs} the null is reachable in production</b>: a
   * server with no agent directory defines no summarisers, which {@code AgentsConfig} treats as a
   * legal running deployment. Such an ingest still stores the document and still makes it
   * searchable, and says in its outcome that nothing was summarised — rather than reporting plain
   * success over a corpus with no labels in it.
   */
  private final Summariser summariser;

  /**
   * Where an ingest's allowance is <b>read from</b>, rather than the allowance itself.
   *
   * <p>{@code plowshare.documents.ingest-budget} is live: an operator can change it on a running
   * server, and a field holding an {@code int} taken at construction is a value no write of theirs
   * can ever reach. That is the defect this field closes — the accessor was made live one task
   * earlier and an actual ingest went on running on the number the server booted with, so a write
   * was visible through the config API and nowhere else.
   *
   * <p><b>The asymmetry with the chunk bounds and the batch size above is the point.</b> Those are
   * boot-bound keys, checked by {@code DocumentsConfig} at boot and frozen here on purpose; a final
   * field says "already decided" and is true of them. This one is not decided until an ingest asks,
   * so it is an object that can be asked.
   */
  private final DocumentsProperties documents;

  /**
   * An ingest that stores and embeds, and labels nothing. The shape this pipeline had before the
   * cascade, kept for the fixtures that are about chunking and embedding and have no business
   * standing up an agent runtime.
   *
   * <p>An unbound {@link DocumentsProperties} rather than a null one, and the two facts that carry
   * it are both checkable: it is non-null, which is all the constructor below asks of it, and it
   * carries no {@link io.aeyer.plowshare.server.config.RuntimeConfig}, so this constructor still
   * reaches no database. <b>What its allowance answers does not come into it</b> — this constructor
   * passes no cascade, and an ingest with no cascade returns before an allowance decides anything.
   */
  public IngestService(
      DocumentStore store,
      EmbeddingClient embeddings,
      Clock clock,
      Chunking chunking,
      int embedBatchSize) {
    this(store, embeddings, clock, chunking, embedBatchSize, null, new DocumentsProperties());
  }

  public IngestService(
      DocumentStore store,
      EmbeddingClient embeddings,
      Clock clock,
      Chunking chunking,
      int embedBatchSize,
      Summariser summariser,
      DocumentsProperties documents) {
    this.store = Objects.requireNonNull(store, "store");
    this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.chunking = Objects.requireNonNull(chunking, "chunking");
    this.embedBatchSize = embedBatchSize;
    this.summariser = summariser;
    this.documents = Objects.requireNonNull(documents, "documents");
  }

  /**
   * Derive, store, embed.
   *
   * <p>Extraction has already happened: it is the caller's error when it fails — a format this
   * server does not read — and belongs where the caller is still standing, not in a job that has to
   * be polled to find out.
   *
   * @param sourceName what the corpus files this document as
   * @param extracted its title, its content hash and its text
   * @param byteSize how large the upload was
   * @param by who asked
   * @param cancelled consulted at every batch boundary, exactly as {@code JobRuntime} consults it
   *     at every turn boundary. A flag and not an interrupt, for {@code JobStore.cancel}'s reason:
   *     an in-flight HTTP call cannot be taken back, and interrupting one abandons a request that
   *     still lands on the box
   * @return how the ingest ended, for the {@link io.aeyer.plowshare.server.agents.JobStore} handle
   *     it runs under
   */
  public Outcome ingest(
      String sourceName, Extracted extracted, long byteSize, String by, BooleanSupplier cancelled) {
    return ingest(
        sourceName,
        extracted,
        byteSize,
        by,
        cancelled,
        usageOwners.in(
            io.aeyer.plowshare.protocol.Home.global(),
            null,
            UsageAttribution.Operation.EMBEDDING_WRITE));
  }

  public Outcome ingest(
      String sourceName,
      Extracted extracted,
      long byteSize,
      String by,
      BooleanSupplier cancelled,
      UsageAttribution owner) {
    Objects.requireNonNull(cancelled, "cancelled");
    if (cancelled.getAsBoolean()) {
      // Before anything is written. A cancel that arrived while the job
      // was still queued should leave no document behind at all.
      return new Outcome(
          Outcome.Ending.CANCELLED,
          "This ingest was cancelled before it wrote anything. '"
              + sourceName
              + "' is not in the corpus.",
          0,
          0,
          "");
    }

    // THE ALLOWANCE IS READ ONCE, HERE, AND HELD FOR THE WHOLE OF THIS
    // INGEST. Spec 1.3 is that a config write changes what NEW work is
    // created with, and for an ingest "created" is this line: everything
    // below runs on one number, and an operator's write reaches the next
    // ingest rather than this one.
    //
    // NOT AT THE CASCADE, which is the alternative and is wrong by minutes.
    // The read would then happen after the whole embedding phase, so "work
    // already under way" would mean "work that has not reached the expensive
    // part yet" -- and the cascade is the half-hour part, so it is precisely
    // the work a mid-flight change must not reach. A budget lowered while a
    // cascade was running would also strand it below what it had already
    // paid for, and the paragraphs it never reached would look like the
    // ordinary resumable stop rather than like somebody moving the floor.
    //
    // The mechanism that DOES reach a run in flight is Budget.changeTo, per
    // run and on purpose, and it is untouched: this is a global default and
    // that is an operator rescuing one run.
    //
    // Below the cancel check above, which returns before there is any work
    // for an allowance to belong to -- an ingest cancelled while it was
    // still queued owes the map no query.
    int budget = documents.ingestBudgetNow();

    boolean code =
        io.aeyer.plowshare.protocol.DocumentType.classify(
                sourceName,
                PdfExtraction.CONVERTER.equals(extracted.converter()) ? "application/pdf" : null)
            .isCode();
    DerivedDocument derived =
        code ? CodeDerivation.derive(extracted, chunking) : Derivation.derive(extracted, chunking);
    if (derived.paragraphs().isEmpty()) {
      // Unreachable through the controller — TextExtraction refuses a
      // document whose text is blank — and answered rather than thrown,
      // because a caller reaching it has a real document that derived
      // nothing and needs to be told which.
      return new Outcome(
          Outcome.Ending.ANSWERED,
          "'" + sourceName + "' produced no paragraphs, so nothing was stored.",
          0,
          0,
          "");
    }

    DocumentStore.Written written =
        store.write(sourceName, extracted, byteSize, by, clock.instant(), derived);

    if (code) {
      store.locateCodePassages(written.documentId(), extracted.text());
      store.writeCodeOutline(
          written.documentId(),
          CodeOutline.parse(
              extracted.text(),
              io.aeyer.plowshare.protocol.DocumentType.classify(sourceName, null).subtype(),
              sourceName));
    }
    List<DocumentStore.UnembeddedChunk> waiting = store.unembedded(written.documentId());
    int embedded = 0;
    int calls = 0;
    for (int from = 0; from < waiting.size(); from += embedBatchSize) {
      if (cancelled.getAsBoolean()) {
        return new Outcome(
            Outcome.Ending.CANCELLED,
            stopped(sourceName, written, embedded, waiting.size() - embedded, "It was cancelled"),
            calls,
            calls,
            "");
      }
      List<DocumentStore.UnembeddedChunk> batch =
          waiting.subList(from, Math.min(from + embedBatchSize, waiting.size()));
      if (dualEmbeddings == null) calls++;
      List<float[]> vectors;
      try {
        if (dualEmbeddings != null) {
          var result =
              dualEmbeddings.repairAll(
                  batch.stream()
                      .map(
                          chunk ->
                              EmbeddingWorkRepository.Key.of(
                                  EmbeddingWorkRepository.Store.CHUNKS, chunk.id().toString()))
                      .toList(),
                  owner.forOperation(
                      UsageAttribution.Operation.EMBEDDING_WRITE, owner.agentName()));
          calls += result.modelCalls();
          embedded += result.completeSources();
          if (result.completeSources() != batch.size())
            throw new EmbeddingException(
                "chunk changed during embedding; current source remains queued");
          continue;
        } else
          vectors =
              EmbeddingClient.owned(
                  embeddings,
                  batch.stream().map(DocumentStore.UnembeddedChunk::text).toList(),
                  owner.forOperation(
                      UsageAttribution.Operation.EMBEDDING_WRITE, owner.agentName()));
      } catch (EmbeddingException down) {
        if (down instanceof EmbeddingRepairException batchFailure)
          calls += batchFailure.modelCalls();
        // The text is committed and stays committed. Logged whole here
        // because the outcome carries only a message, and the endpoint's
        // own account of the failure is what an operator needs.
        log.warn("ingest of '{}' stored the document and could not embed it", sourceName, down);
        return new Outcome(
            Outcome.Ending.UNAVAILABLE,
            stopped(
                sourceName,
                written,
                embedded,
                waiting.size() - embedded,
                "The embedding endpoint could not be reached"),
            calls,
            calls,
            down.getMessage() == null ? "" : down.getMessage());
      }
      for (int i = 0; i < batch.size(); i++) {
        store.attach(batch.get(i).id(), vectors.get(i));
        embedded++;
      }
    }

    String stored = done(sourceName, written, embedded);
    if (code)
      return new Outcome(
          Outcome.Ending.ANSWERED,
          stored + " Filed in the code corpus; prose summaries are skipped.",
          calls,
          calls,
          "");
    // TWO STATES, TWO SENTENCES, AND THEY WERE ONE UNTIL THIS KEY WENT
    // LIVE. Both end an ingest with the corpus stored, searchable and
    // unlabelled -- said out loud rather than reported as plain success,
    // because a corpus whose paragraphs carry no claim is one every later
    // surface (a citation, a query agent, a document listing) reads as
    // having nothing to say about itself, and nothing else would ever
    // mention it. What differs is who has to do what about it, and a
    // message naming the wrong cause sends them to the wrong place.
    //
    // The allowance below one used to be unreachable here: DocumentsConfig
    // validated the same number it froze into this constructor, so the only
    // way in was the six-argument constructor, which passes no cascade
    // either. A live value reaches it on its own -- spec 1.1 accepts a live
    // key as checked at boot and NOT on write, so a PUT of zero is
    // permitted, and the boot check reads the value as bound, so a stale
    // zero survives every boot of a deployment nobody has pinned.
    if (summariser == null) {
      return new Outcome(
          Outcome.Ending.ANSWERED,
          stored + " Nothing was summarised: this server has no summariser cascade" + " wired.",
          calls,
          calls,
          "");
    }
    if (budget < 1) {
      // NAMING THE CHANNEL IS THE HALF THAT MAKES THIS ACTIONABLE, and
      // an earlier version of this sentence left it out.
      //
      // The leading case is a stale row on a deployment nobody pinned:
      // application.yml says 1000, the boot that just succeeded validated
      // 1000, and this says 0. An operator who greps the file finds 1000
      // and reads this as simply wrong. The number lives in a
      // runtime_config row and is reachable only through the config API
      // or SQL -- so the message that does not say so sends them to a knob
      // that cannot move it.
      //
      // It borrows DocumentsConfig's words for the key and the bound, and
      // then must NOT borrow the rest: that refusal is about the file, and
      // the two sentences being indistinguishable is exactly wrong at the
      // point where what to do differs. RuntimeConfig's own warnings set
      // the house precedent -- they say "the runtime config map" out loud.
      //
      // WHAT A RESTART DOES IS NOT A FACT THIS METHOD KNOWS, and the
      // sentence used to assert it anyway. It said flatly that "a restart
      // will not clear it", which is true only of the leading case above
      // -- a deployment nobody pinned. RuntimeConfigSeed.operatorPinned is
      // the other half: on a deployment that DID pin this key, the seed
      // rewrites the row from the pin on every boot, so a 0 written
      // through PUT /v1/config is cleared by the next restart and an
      // operator told otherwise waits for a symptom that has already
      // gone. This service holds no seed and cannot ask, so the message
      // names the surface that can answer instead of guessing. GET
      // /v1/config's `pinned` field is that answer, and it exists for
      // exactly this question.
      return new Outcome(
          Outcome.Ending.ANSWERED,
          stored
              + " Nothing was summarised: plowshare.documents.ingest-budget is "
              + budget
              + "; it must be at least 1. That value came from the"
              + " runtime config map rather than from the configuration this"
              + " server booted with, so the file will not show it: change it"
              + " through the config API. Whether a restart clears it depends on"
              + " whether this key is pinned outside the jar, and GET /v1/config"
              + " says which. The cascade is wired; the allowance is what stopped"
              + " it.",
          calls,
          calls,
          "");
    }
    Outcome cascade =
        owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
            ? summariser.summarise(
                written.documentId(), extracted.title(), Budget.of(budget), cancelled)
            : summariser.summarise(
                written.documentId(),
                extracted.title(),
                Budget.of(budget),
                cancelled,
                owner.accountHandle());
    return new Outcome(
        cascade.ending(),
        stored + " " + cascade.text(),
        calls + cascade.steps(),
        calls + cascade.modelCalls(),
        cascade.detail());
  }

  /**
   * What a finished ingest says.
   *
   * <p>Counts and not prose. A re-ingest that kept everything is a legitimate and common answer,
   * and it has to be legible as one rather than as an ingest that did nothing.
   */
  private static String done(String sourceName, DocumentStore.Written written, int embedded) {
    return "Ingested '"
        + sourceName
        + "': "
        + written.added()
        + " new paragraph(s), "
        + written.kept()
        + " unchanged, "
        + written.removed()
        + " removed. "
        + embedded
        + " chunk(s) embedded.";
  }

  /**
   * What a stopped ingest says, and it has to say what is now unsearchable.
   *
   * <p>{@code MemoryStore.countUnsearchable} exists so that an {@code embedding IS NULL} filter
   * stops being invisible; this is the same sentence at the moment it becomes true. A document in
   * the corpus with no vectors answers no question, and an outcome that did not say so would let
   * somebody conclude the corpus is empty of the subject rather than of the vectors.
   */
  private static String stopped(
      String sourceName, DocumentStore.Written written, int embedded, int missing, String why) {
    return why
        + ". '"
        + sourceName
        + "' is stored — "
        + written.added()
        + " new paragraph(s), "
        + written.kept()
        + " unchanged — and "
        + embedded
        + " chunk(s) were embedded, leaving "
        + missing
        + " that no search can reach. Ingesting it again embeds those and re-derives"
        + " nothing.";
  }
}
