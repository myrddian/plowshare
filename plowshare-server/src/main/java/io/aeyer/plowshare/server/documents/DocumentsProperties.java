package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.config.Live;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The numbers the ingest pipeline takes that are not properties of the model.
 *
 * <p>The <b>ceiling</b> is deliberately not here. It is {@code
 * plowshare.llm.embedding-max-input-tokens}, because it is a fact about the loaded embedding model
 * rather than about this pipeline, and every caller of the embedding endpoint needs it — not only
 * this one. Two spellings of one bound that had to agree is exactly the drift {@code
 * LlmProperties#embeddingModel} was written about.
 *
 * <p>No field initializers, for {@link io.aeyer.plowshare.server.llm.LlmProperties}' reason: {@code
 * application.yml} owns these defaults, it is where an operator reads the argument for them, and a
 * Java initializer would be a second spelling nothing compares. An unbound instance answers zero
 * and {@link DocumentsConfig} refuses that at boot, naming the key.
 */
@ConfigurationProperties(prefix = "plowshare.documents")
public class DocumentsProperties {

  /**
   * The key {@link #ingestBudgetNow()} is live under, written once.
   *
   * <p>{@link Live} takes a compile-time constant and {@link RuntimeConfig#intOr} takes the same
   * string at runtime, so the alternative is the key spelled twice a few lines apart — and the boot
   * check that makes a misspelling a refusal reads the annotation, so the copy that could drift is
   * the one nothing checks.
   */
  private static final String INGEST_BUDGET = "plowshare.documents.ingest-budget";

  /**
   * The map this accessor reads through, or {@code null} where there is none.
   *
   * <p><b>Null is a supported state and not an accident.</b> A properties class is a POJO Spring
   * binds, and the ways one comes into existence without a container are ordinary: {@code
   * DocumentsConfigTest} builds one with {@code new} to assert what an unbound instance holds, and
   * a context with no datasource has no {@link RuntimeConfig} bean to inject. The plan calls this
   * the stated cost of setter injection; the answer is that the accessor falls through to the bound
   * value, which is what {@code
   * DocumentsPropertiesTest.a_properties_object_with_no_runtime_config_returns_its_bound_value}
   * holds.
   *
   * <p>Not {@code final}, and not a constructor parameter, because Spring binds this class by
   * calling setters on an instance it made — a constructor argument that is not a bound property
   * would take that away from it.
   */
  private RuntimeConfig live;

  /**
   * What chunk packing aims at, in tokens as the configured {@code Tokenizer} counts them.
   *
   * <p>A target and not the ceiling: whole sentences are added while they fit under this, and a
   * sentence that would not fit starts the next chunk. It must be at or below {@code
   * plowshare.llm.embedding-max-input-tokens}, which {@link DocumentsConfig} checks at boot and
   * {@link Chunking} checks again wherever a pair of bounds is made.
   *
   * <p><b>Changing it is a re-chunk and a re-embed of the corpus, not a setting</b>, on {@code
   * embedding-model}'s terms: every stored chunk was packed under the rule in force when it was
   * written, and a re-ingest will not repair a chunk whose paragraph did not change.
   */
  private int chunkTargetTokens;

  /**
   * How many chunks go to the embedding endpoint in one request.
   *
   * <p>The number Anchor does not have: its {@code EmbeddingService.embedAll} sends a document's
   * every chunk as one request, so one failure costs the whole document and there is no boundary at
   * which the work can be stopped or resumed. What this trades is round trips against blast radius
   * — and it is the boundary cancellation lands on, so a very large value is also an ingest that
   * takes a long time to notice it was cancelled.
   */
  private int embedBatchSize;

  public int getChunkTargetTokens() {
    return chunkTargetTokens;
  }

  public void setChunkTargetTokens(int chunkTargetTokens) {
    this.chunkTargetTokens = chunkTargetTokens;
  }

  /**
   * How many model calls one ingest's summariser cascade may spend, across every run in it.
   *
   * <p><b>{@code plowshare.agents.curator-budget}'s counterpart, and the precedent it follows.</b>
   * A curator pass is a server-side job with its own allowance, shared by reference across every
   * ruling; an ingest is that shape, with arithmetic of its own: one call a paragraph, one per
   * section, one per chapter, one for the document, and one per fold of {@link #spanSize} summaries
   * wherever a unit has more children than fit a call. Measured on the 30-page paper this is sized
   * against — seven sections of 29 paragraphs — that is <b>233</b>, pinned by {@code
   * SummariserTest}.
   *
   * <p>Spending it is not a failure. The cascade stops, says so, and keeps every summary it already
   * paid for — and the next ingest of the same document finds exactly the paragraphs it never
   * reached, because a paragraph with no summary is the query that says what is still owed.
   */
  private int ingestBudget;

  /**
   * How many summaries one fold reads — and, the same number in its other job, <b>how many a tier
   * may be shown at once</b>.
   *
   * <p>The fold stopped being a tier when V26 gave this server chapters and sections to hang the
   * middle levels on; it is now how a tier's children are made to fit one call, which is what
   * "fits" means here as a measurable rule: at most this many of them. <b>A count and not a length,
   * because the count is the only dimension a document controls</b> — each child was written by an
   * agent that was told how long to be, and how many children a unit has is bounded by nothing.
   *
   * <p><b>At least two.</b> A fold of one summary is a paraphrase rather than a compression, and a
   * span size of one would fold a level into a level of the same length and never finish. {@link
   * DocumentsConfig} refuses it at boot and {@link Summariser}'s constructor refuses it again.
   *
   * <p>It decides the shape of the cascade and not only its cost: a larger span is fewer, longer
   * folds — more of the document in front of one model call, which is where an argument's arc is
   * easiest to see and hardest to hold — and a smaller one is more, shorter folds and a deeper
   * tree.
   */
  private int spanSize;

  /**
   * How many model calls one per-document ask may spend, across all three of its stages.
   *
   * <p><b>{@link #ingestBudget}'s counterpart with a fixed arithmetic rather than a measured
   * one.</b> An ingest's cost scales with the document; a deliberation's does not. It is a
   * proposer, a critic and a synthesiser — three calls — plus {@link Deliberation}'s one retry of a
   * critic whose JSON did not parse, which is a fourth. {@link Deliberation#A_PASS} is that number
   * and {@link DeliberationConfig} refuses anything below it at boot, because an allowance that
   * cannot cover a pass's worst case is a deliberation that reports {@code CALL_BUDGET} the first
   * time a small model answers a JSON question in prose.
   *
   * <p>Higher than four buys nothing today: nothing in a pass loops, and the three definitions
   * declare one turn and one call each. It is a property rather than a constant so that an operator
   * can stop the ask outright by lowering the ceiling on a running one, which is what {@code
   * Budget.changeTo} is for, and so that a stage growing a second call later is a configuration
   * change rather than a code change.
   */
  private int askBudget;

  public int getEmbedBatchSize() {
    return embedBatchSize;
  }

  public void setEmbedBatchSize(int embedBatchSize) {
    this.embedBatchSize = embedBatchSize;
  }

  /**
   * {@link #ingestBudget} as Spring bound it — <b>what the operator configured</b>, before and
   * regardless of anything written at runtime.
   *
   * <p><b>The plain name, and therefore the plain behaviour.</b> The six accessors around it answer
   * from the field beside them, that is what a reader who has not read this class takes {@code
   * getX()} to mean, and this one does the same. The live read is {@link #ingestBudgetNow()}, which
   * has to be reached for by name.
   *
   * <p>That is the whole of the defence, and it is asymmetric on purpose, because the two ways of
   * picking wrong are not equally findable. A runtime consumer that writes this one by analogy
   * produces a live key nothing an operator's write can reach — the failure Task 4 shipped and Task
   * 4a closed, and one any test asserting spec §1.3 for that key will show, because it is visible
   * from a write and a call. A boot check that reads the live one instead produces the paragraph
   * below: a refusal that appears only on a deployment where an operator's pin and a stale row
   * disagree, which is a state no test in this tree stands up by accident. The name that is written
   * without thinking should be the one whose mistake is caught.
   *
   * <p><b>{@link DocumentsConfig}'s boot check is the caller this matters to.</b> That check
   * refuses an allowance below one, and reading the live value there has a failure the bound value
   * does not. {@code RuntimeConfigSeed} writes the map in {@code afterSingletonsInstantiated} —
   * after the {@code @Configuration} classes have run — so at the moment of the check the map has
   * not yet been reconciled with the environment. An operator who pins {@code
   * PLOWSHARE_INGEST_BUDGET=2000} over a stale runtime {@code 0} would have the boot refused by the
   * very value their pin was about to replace.
   *
   * <p>And the coverage the other reading would buy is not there to buy. Spec §1.1 already accepts
   * that a live key is checked at boot and <b>not</b> on write, so a {@code PUT} of zero a second
   * after the check is legal whichever value the check read. Validating the map at boot resembles a
   * guarantee about runtime writes and is not one. What an ingest does when it meets a zero the
   * boot never saw is {@link IngestService}'s to answer, and it answers it by name.
   *
   * <p><b>Deliberately not {@link Live}-annotated</b>, and that is a declaration rather than an
   * omission: {@code RuntimeConfigSeed} refuses a boot in which two accessors claim one key, and
   * this accessor's whole job is to be the one that does not read the map.
   */
  public int getIngestBudget() {
    return ingestBudget;
  }

  /**
   * {@link #ingestBudget}, <b>or what an operator has since changed it to</b> — the allowance as of
   * this call, which is the one accessor here that can answer differently twice in a row.
   *
   * <p><b>The name asks about a moment, and that is the point of it.</b> This is the only accessor
   * in this class that does not answer from the field beside it, so it does not wear the name that
   * means "the field beside it" everywhere else on this server. Both accessors return a plausible
   * {@code int} and picking the wrong one has no symptom of its own, so what separates them has to
   * be legible at the call site rather than in here: {@code props.getIngestBudget()} is a value the
   * server booted with and {@code props.ingestBudgetNow()} is a question about this instant.
   *
   * <p><b>Not a second key.</b> Nothing binds a property by this name, there is one field under
   * both accessors, and the name costs nothing at the framework boundary: {@code RuntimeConfigSeed}
   * asks of a {@code @Live} method only that a value comes back without an argument, {@link Live}
   * carries the key rather than deriving it from the method, and Spring binds through {@link
   * #setIngestBudget(int)}.
   *
   * <p>{@link RuntimeConfig#intOr} argues what the bound value means here — a floor rather than a
   * fallback — and why an unreachable map is loud.
   *
   * <p><b>Who reads it, and when.</b> {@link IngestService} asks once as an ingest begins and holds
   * the answer for that ingest, which is spec §1.3 — a write changes what new work is created with
   * — with "created" meaning the moment {@code IngestService.ingest} is entered. So the map is
   * consulted once per ingest, an operator's write reaches the next one, and the cascade spending
   * half an hour on a document cannot have its allowance moved under it. The per-run intervention
   * that <em>does</em> reach work in flight is {@code Budget.changeTo}, which is a different
   * mechanism with a different reach and is untouched by this.
   */
  @Live(INGEST_BUDGET)
  public int ingestBudgetNow() {
    return live == null ? ingestBudget : live.intOr(INGEST_BUDGET, ingestBudget);
  }

  /**
   * Setter injection, and the whole of how a bound POJO reaches a Spring bean.
   *
   * <p>{@code required = false} because a context can legitimately have no {@link RuntimeConfig} —
   * see the field — and because the alternative is a properties class that refuses to exist without
   * a database.
   *
   * <p><b>Not a proxy over the bean and not a change to the call sites.</b> Proxying every
   * {@code @ConfigurationProperties} bean is invisible machinery for <b>one</b> key — two was the
   * count while spec §6's second was expected, and {@code RuntimeConfigSeedTest} records why the
   * agents directory key, since retired, did not become it — and a reader stepping into this getter
   * would find a value arriving from somewhere the source does not mention. Asking consumers to
   * read {@link RuntimeConfig} themselves would spread the knowledge of which keys are live across
   * every call site instead of keeping it on the accessor that declares it.
   */
  @Autowired(required = false)
  public void setLive(RuntimeConfig live) {
    this.live = live;
  }

  public void setIngestBudget(int ingestBudget) {
    this.ingestBudget = ingestBudget;
  }

  public int getSpanSize() {
    return spanSize;
  }

  public void setSpanSize(int spanSize) {
    this.spanSize = spanSize;
  }

  public int getAskBudget() {
    return askBudget;
  }

  public void setAskBudget(int askBudget) {
    this.askBudget = askBudget;
  }
}
