package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.agents.Citing;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the corpus, and <b>refuses to start on a chunk rule that could not be honoured</b>.
 *
 * <p>{@link DocumentStore} and {@link IngestService} take no stereotype of their own, matching
 * {@code ArchiveConfig}: they are framework-free, and whatever wires them supplies the mechanisms
 * they will not name — a {@link UnitOfWork} for the transaction, an {@link EmbeddingClient} for the
 * model, a {@link Clock} for the row.
 */
@Configuration
@EnableConfigurationProperties(DocumentsProperties.class)
public class DocumentsConfig {

  @Bean
  public io.aeyer.plowshare.server.information.InformationAccess informationAccess(
      io.aeyer.plowshare.server.archive.ProjectMembers members) {
    return new io.aeyer.plowshare.server.information.InformationAccess(members);
  }

  @Bean
  public org.springframework.beans.factory.SmartInitializingSingleton informationRuntimeBinding(
      io.aeyer.plowshare.server.information.InformationAccess access,
      ObjectProvider<io.aeyer.plowshare.server.agents.JobRuntime> runtimes) {
    return () -> runtimes.ifAvailable(runtime -> runtime.useInformationAccess(access));
  }

  @Bean
  public DocumentStore documentStore(JdbcTemplate jdbc, UnitOfWork unitOfWork) {
    return new DocumentStore(jdbc, unitOfWork);
  }

  @Bean
  public CitationStore citationStore(JdbcTemplate jdbc) {
    return new CitationStore(jdbc);
  }

  /**
   * What writes down the paragraphs an answer named.
   *
   * <p>Wired here rather than in {@code AgentsConfig} although it implements an interface from that
   * package, on {@code Learner}'s precedent turned around: the bean belongs where its collaborators
   * are, and this one's are the corpus's. {@code AgentsConfig} takes it through an {@code
   * ObjectProvider} exactly as it takes {@code Learning}, so a server built without this
   * configuration closes its turns the way it always did.
   *
   * <p>{@code Clock.systemUTC()} and not a {@code Clock} bean, matching {@link #ingestService} one
   * method up: the corpus stamps its own rows and nothing on this server injects a clock into a
   * bean.
   */
  @Bean
  public Citing citations(CitationStore citations) {
    return new Citations(citations, Clock.systemUTC());
  }

  /**
   * The pipeline, and the boot check that keeps its two bounds consistent.
   *
   * <p>The target is this pipeline's and the ceiling is the model's, so nothing but a check holds
   * them in step. A target above the ceiling means packing aims past what the endpoint will accept:
   * every chunk that reached the target would then be refused at the embedding boundary, so an
   * ingest would store a whole document and embed none of it, and the only report would be an
   * outcome saying a great many chunks are unsearchable. {@link Chunking} refuses the same pair
   * wherever one is made — this is the half that says so before a document has been uploaded, and
   * in the vocabulary of the keys an operator set.
   *
   * <p>Both are tokens, counted by the one {@link Tokenizer} bean every surface on this server
   * counts with — the same one the embedding client refuses by, so the chunker and the refusal
   * cannot disagree about what a chunk costs.
   *
   * <h2>The ingest budget is checked as bound and handed over as an object</h2>
   *
   * <p>It is the one live key here, and each half of that sentence is its own decision.
   *
   * <p><b>Checked as bound</b>, through {@link DocumentsProperties#getIngestBudget()} — the plain
   * accessor, which answers from the bound field as its six siblings do — because this check
   * validates what an operator configured. The live read is spelled {@code ingestBudgetNow()} and
   * has to be asked for by that name.
   *
   * <p>{@code RuntimeConfigSeed} writes the map in {@code afterSingletonsInstantiated}, which is
   * <em>after</em> this method runs, so a check on the live value reads the map at the one moment
   * nothing has reconciled it with the environment: an operator pinning {@code
   * PLOWSHARE_INGEST_BUDGET=2000} over a stale runtime {@code 0} would be refused a boot by the
   * value their pin was about to replace. Spec §1.1 already accepts that a live key is checked at
   * boot and not on write, so reading the map here would not have bought the guarantee it resembled
   * either — and what an ingest does when it meets a zero this check never saw is answered in
   * {@link IngestService#ingest}, in its own sentence.
   *
   * <p><b>Handed over as an object</b>, because freezing the number into a constructor is what made
   * the key live in name only — visible through the accessor and the config API, and invisible to
   * an ingest. Spec §1.3 says a write changes what new work is created with, and an ingest is new
   * work.
   */
  @Bean
  public IngestService ingestService(
      DocumentStore store,
      EmbeddingClient embeddings,
      DocumentsProperties props,
      LlmProperties llm,
      Tokenizer tokenizer,
      ObjectProvider<Summariser> summariser) {
    int target = props.getChunkTargetTokens();
    int ceiling = llm.getEmbeddingMaxInputTokens();
    if (target < 1) {
      throw new IllegalStateException(
          "plowshare.documents.chunk-target-tokens is "
              + target
              + "; it must be positive. It is what chunk packing aims at, in"
              + " tokens, and zero packs nothing");
    }
    if (target > ceiling) {
      throw new IllegalStateException(
          "plowshare.documents.chunk-target-tokens is "
              + target
              + " and"
              + " plowshare.llm.embedding-max-input-tokens is "
              + ceiling
              + "; packing may not aim past what the embedding endpoint accepts."
              + " Lower the target or raise the ceiling — and raising the ceiling"
              + " above what the model is loaded at buys nothing, since the refusal"
              + " then moves to the endpoint");
    }
    if (props.getEmbedBatchSize() < 1) {
      throw new IllegalStateException(
          "plowshare.documents.embed-batch-size is "
              + props.getEmbedBatchSize()
              + "; it must be at least 1. It is how many chunks go to the embedding"
              + " endpoint in one request, and a batch of zero is an ingest that"
              + " stores every document and embeds nothing");
    }
    if (props.getIngestBudget() < 1) {
      throw new IllegalStateException(
          "plowshare.documents.ingest-budget is "
              + props.getIngestBudget()
              + "; it must be at least 1. It is how many model calls one ingest\'s"
              + " summariser cascade may spend, and an allowance of zero is an"
              + " ingest that stores and embeds every document and labels none of"
              + " them");
    }
    // THROUGH A PROVIDER, AND THE ABSENT CASE IS A RUNNING SERVER. The
    // cascade is wired in AgentsConfig because that is where the runtime and
    // the compaction are, and a deployment with no agent directory has
    // neither -- which AgentsConfig treats as legal and says so at boot. An
    // ingest without one stores and embeds and labels nothing, and says
    // THAT in its outcome; refusing to start would make a corpus impossible
    // on a server that had merely not been given any agents.
    return new IngestService(
        store,
        embeddings,
        Clock.systemUTC(),
        new Chunking(tokenizer, target, ceiling),
        props.getEmbedBatchSize(),
        summariser.getIfAvailable(),
        props);
  }

  /**
   * The read half, built from <b>the same {@link EmbeddingClient} the write half above is built
   * from</b>.
   *
   * <p>That is not incidental and it is the one thing about this method worth saying. A vector
   * means something only inside the space the model that produced it defines, so the corpus's
   * vectors and the vector a question is turned into have to come from one model — and a mismatch
   * does not fail: the distances compute, the rows sort, and the answer is a ranking of nothing.
   * There is one {@code EmbeddingClient} bean on this server, {@code LlmConfig} declares it beside
   * the dispatcher, both methods here take it as a parameter, and {@code
   * the_corpus_and_the_questions_asked_of_it_go_through_one_embedding_client} is what stops a
   * second one appearing unnoticed.
   *
   * <p>The width is passed rather than looked up later so that a refusal can name {@code
   * plowshare.llm.embedding-dim} and V18's {@code vector(n)} in one sentence; {@code
   * LlmConfig.requireEmbeddingDim} has already refused a non-positive one by the time this runs,
   * and {@code requireEmbeddingModelIsServed} has already refused a model no pool declares — so
   * there is nothing left for this method to check that would not be a second spelling of a check
   * that fired earlier.
   */
  @Bean
  public RetrievalService retrievalService(
      DocumentStore store, EmbeddingClient embeddings, LlmProperties llm) {
    return new RetrievalService(store, embeddings, llm.getEmbeddingModel(), llm.getEmbeddingDim())
        .checkingConfiguration();
  }

  /**
   * What puts a vector on a document's own summary.
   *
   * <p>{@link #retrievalService} beside it takes the same four things for the same reason and that
   * method's javadoc gives it: one embedding client on this server, and the width passed rather
   * than looked up so that a refusal can name the key and V18's {@code vector(n)} in one sentence.
   * <b>The two are separate beans because one writes and one reads</b>, and the read is
   * package-private on purpose — {@code RetrievalService} is the only public door onto a vector,
   * and a write path is not a door.
   */
  @Bean
  public SummaryEmbeddings summaryEmbeddings(
      DocumentStore store, EmbeddingClient embeddings, LlmProperties llm) {
    return new SummaryEmbeddings(store, embeddings, llm.getEmbeddingModel(), llm.getEmbeddingDim());
  }

  // InformationLifecycle owns summary-vector repair, gates and owner budget. No unscoped
  // startup backfill may process quarantined documents or bypass those stage boundaries.
}
