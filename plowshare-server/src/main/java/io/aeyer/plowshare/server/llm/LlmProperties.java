package io.aeyer.plowshare.server.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.aeyer.plowshare.server.llm.tokens.TokenizerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The pools, the server-wide class map, and the one embedding model every
 * corpus in this server shares.
 *
 * <p>{@link #classes} joined this list on 2026-09-07 and the summary above did
 * not follow it. It is the one key here that is a statement about the
 * <em>fleet</em> rather than about the archive or about a box — see its own
 * javadoc for why it is server-wide and why {@code context-lengths} is not.
 *
 * <p><b>{@code ignoreUnknownFields = false} is load-bearing.</b> The
 * single-endpoint keys this class used to hold — {@code base-url}, {@code
 * api-key}, {@code timeout-seconds}, {@code retry-*} — are now per pool. Spring
 * ignores an unbound key silently by default, so a deployment still setting
 * {@code LLM_BASE_URL} would boot cleanly and talk to a different host than its
 * operator believed. Failing the startup is the only report anyone gets.
 *
 * <p>The cost is that this prefix is now closed: any key under {@code
 * plowshare.llm} that no setter here or on {@link PoolProperties} accepts stops
 * the server. That is the intent, and it means a new knob is added here before
 * it is added to a deployment's environment, never the other way round.
 */
@ConfigurationProperties(prefix = "plowshare.llm", ignoreUnknownFields = false)
public class LlmProperties {

    private List<PoolProperties> pools = new ArrayList<>();

    private AccountingProperties accounting = new AccountingProperties();

    public AccountingProperties getAccounting() { return accounting; }
    public void setAccounting(AccountingProperties value) { accounting = java.util.Objects.requireNonNull(value); }

    // Top-level map: price overrides must not replace Spring's entire pool-list overlay.
    private Map<String, PriceProperties> pricing = new LinkedHashMap<>();

    public Map<String, PriceProperties> getPricing() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pricing));
    }

    public void setPricing(Map<String, PriceProperties> value) {
        pricing = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    /**
     * How this deployment counts tokens, and what it falls back to.
     *
     * <p>Server-wide for the reason {@link #classes} is: a tokenizer is a fact
     * about what the fleet can do, not about which box answered. Two pools
     * disagreeing about how to count would make a count a function of queue
     * depth, which is the defect {@code 556cdf8} reverted for model classes.
     */
    private TokenizerProperties tokenizer = new TokenizerProperties();

    public TokenizerProperties getTokenizer() {
        return tokenizer;
    }

    public void setTokenizer(TokenizerProperties tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * What a class name means, for the whole server: one class, one wire model.
     *
     * <h2>It was per pool, and per pool a class could mean two things at once</h2>
     *
     * <p>{@code classes} lived on {@link PoolProperties} until 2026-09-07.
     * {@code LlmDispatcher.route} resolves a specifier by walking every pool and
     * taking the least loaded one that serves it, so two pools mapping {@code
     * fast} to <em>different</em> models made the model a function of which box
     * was busier — the same request answered by qwen or by GLM depending on
     * queue depth, with nothing logged and nothing to grep for. That shipped
     * when the Spark pool was added and was reverted the same day ({@code
     * 556cdf8}) by deleting the second pool's map. This key is the shape fix:
     * with one map there is no second entry to disagree with.
     *
     * <p><b>Load balancing is what the key was for, and it survives.</b> {@code
     * application.yml} says of the old per-pool key that "the field exists so
     * that adding a second [node] is config" — a second node serving the
     * <em>same</em> model, for capacity. Two pools both serving {@code
     * qwen3.8-27b} both answer {@code fast} here, because {@link
     * io.aeyer.plowshare.server.llm.dispatch.LlmPool#resolve} answers for a
     * class only when this map's model is one the pool itself serves, and the
     * dispatcher picks the lighter of them. What is gone is the case nothing
     * ever wanted.
     *
     * <p><b>Why this and not {@code context-lengths} moved.</b> A class is a
     * statement about the fleet — "when an agent says {@code fast}, it means
     * this model" — and has no per-box half. A context length is the opposite:
     * it is a property of <em>this node serving that model</em>, since two nodes
     * may load one model at different {@code --ctx-size} and an operator may
     * hold a margin under what a node reports. Moving that one would assert a
     * model has one length wherever it runs, which is false. Those stay on
     * {@link PoolProperties}.
     *
     * <p>A map and not a list, for the reason the old field gave: {@code fast}
     * is a name no endpoint answers to, so a list would declare a class and
     * leave nothing to put in the request body.
     *
     * <p>Empty is a legitimate configuration — a deployment whose agents all
     * name wire models directly needs no classes at all — so there is no
     * refusal for an absent map. What {@code LlmConfig} does refuse is a class
     * whose model <em>no pool serves</em>, which is the check this move buys and
     * which was not expressible while the map was per pool.
     */
    private Map<String, String> classes = new LinkedHashMap<>();

    /**
     * WHICH MODEL RUNS HARNESS WORK, and it is not a class.
     *
     * <p>{@code classes} are statements about <em>agent capability</em>: an
     * agent declares {@code fast} or {@code reasoning} to say what kind of
     * thinking its job needs. A fold needs no kind of thinking; it needs a
     * machine. Read in code before this key existed, {@code
     * MemoryProperties.model} was {@code "fast"} — which says "run memory
     * digests wherever the quick conversational agents run", a sentence nobody
     * meant to write.
     *
     * <p>Empty means no harness work is bound anywhere, which {@code LlmConfig}
     * refuses only if something asks for a binding; see {@code
     * requireSystemResolvable}.
     */
    private String system = "";

    /**
     * A type of harness work that wants a different machine from the rest.
     *
     * <p><b>Load-bearing rather than convenient, and the reason is measured.</b>
     * {@code implementation rationale} §5.1:
     * the shipped memory prompts on the large reasoning model answer {@code
     * NONE} where the small one finds the branch, take 38 s at medium effort to
     * choose one, and write 533 words against their own 300-word limit. A fold
     * is long-form summarising and wants that model; a digest is terse and
     * instruction-bound and does not. One tag, two destinations.
     *
     * <p>Keys are types — {@code compaction}, {@code memory}, {@code advisor}, {@code validator},
     * {@code checker}, {@code judge}, {@code authoring}. A key absent, or present and blank, inherits {@link
     * #system}.
     */
    private Map<String, String> systemOverrides = new LinkedHashMap<>();

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system == null ? "" : system.strip();
    }

    public Map<String, String> getSystemOverrides() {
        return Map.copyOf(systemOverrides);
    }

    public void setSystemOverrides(Map<String, String> overrides) {
        this.systemOverrides =
                overrides == null ? new LinkedHashMap<>() : new LinkedHashMap<>(overrides);
    }

    /**
     * The specifier that runs one type of harness work.
     *
     * <p>Returns the empty string when nothing is bound, rather than null: a
     * caller's failure should be {@code LlmConfig} naming the key at boot, or
     * {@code LlmDispatcher} naming the specifier at the call, and neither can
     * say anything useful about a null.
     */
    public String systemSpecifier(String type) {
        String override = systemOverrides.get(type);
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        return system;
    }

    /**
     * The specifier the embedding path submits. One model, for the whole
     * archive.
     *
     * <p>Not a per-request knob and not per-pool. Vectors are comparable only
     * within one model's space, so a question embedded with model A searching
     * rows embedded with model B does not error — it returns nonsense. An
     * embedding model is a property of a corpus; changing it is a re-embed of
     * everything in that corpus, not a setting.
     *
     * <p><b>No initializer, and that is the point.</b> The default is
     * {@code application.yml}'s — {@code ${LLM_EMBEDDING_MODEL:nomic-embed-text}}
     * — and it was written here as well until it was noticed that the YAML wins
     * at every boot, because Spring binds over a field initializer. Two spellings
     * of one default, of which only one was ever read: nothing compared them,
     * nothing failed when they drifted, and deleting the YAML key would have
     * brought the stale Java one silently back to life. The initializer could not
     * carry the YAML's reasoning either, which is where an operator reads it.
     *
     * <p>So an unbound instance answers {@code null} here, and {@code
     * LlmConfig.requireEmbeddingModelNamed} refuses to start on it, naming the
     * key. That is a boot failure an operator can act on rather than an archive
     * embedded by a model nobody chose.
     */
    private String embeddingModel;

    /**
     * Must match {@code vector(768)} in V1__memories.sql. Verified against the
     * first response rather than trusted: a model of a different width does not
     * fail at the HTTP layer, it fails at the INSERT, one layer away from the
     * configuration that caused it — and every memory written before someone
     * noticed would have to be re-embedded.
     *
     * <p><b>No initializer</b>, for the reason {@link #embeddingModel} sets out
     * at length: {@code application.yml}'s {@code ${LLM_EMBEDDING_DIM:768}} is
     * the only place this default lives. An unbound instance answers {@code 0},
     * and {@code LlmConfig.requireEmbeddingDim} refuses a non-positive width at
     * boot, naming the key.
     */
    private int embeddingDim;

    /**
     * The largest input, in tokens as the configured {@code Tokenizer} counts
     * them, that may be sent to the embedding endpoint.
     *
     * <p><b>The input-side counterpart of {@link #embeddingDim}</b>, and until
     * a ceiling existed there was nothing on that side at all: {@code
     * DispatchingEmbeddingClient} verifies the width of every vector that comes
     * back and verified nothing about what went out.
     *
     * <p>Measured against the reference node 2026-09-03, {@code
     * nomic-embed-text} reports {@code loaded_context_length} 2048 and {@code
     * max_context_length} 2048 — no headroom to be bought by configuration. The
     * shipped value sits a quarter below that because the shipped tokenizer
     * estimates, and an estimate can be low; {@code application.yml} carries the
     * arithmetic beside the key.
     *
     * <p><b>Tokens and not bytes.</b> This key was {@code
     * embedding-max-input-bytes}, on the argument that no tokenizer emits more
     * tokens than its input has bytes. That made it a bound, and a quarter of
     * the window for English prose: it refused every 300-word digest summary the
     * archive wrote. The count is now the {@code Tokenizer} bean's, the one every
     * surface on this server counts with, so a real tokenizer replacing the
     * heuristic moves this ceiling with nothing else to edit.
     *
     * <p><b>Changing this is a re-chunk and a re-embed of the corpus, not a
     * setting</b>, for the reason {@link #embeddingModel} carries: every stored
     * chunk was cut under the rule in force when it was written, and a
     * re-ingest will not repair a chunk whose paragraph did not change.
     *
     * <p>No initializer, for the reason {@link #embeddingModel} sets out.
     * An unbound instance answers {@code 0}, {@code
     * LlmConfig.requireEmbeddingMaxInputTokens} refuses that at boot, and {@code
     * DispatchingEmbeddingClient} refuses it again on the first call that needs
     * one — because "unreachable if someone remembers to validate" is the
     * assumption that produced the incident that class is named for.
     */
    private int embeddingMaxInputTokens;

    /**
     * The context length to assume for a model when nobody could say what it
     * really is: the bottom of three tiers, and the one that always answers.
     *
     * <p>The tiers, in order. A {@code context-lengths[<model>]} entry an
     * operator wrote wins over everything, because holding a margin under what
     * the node reports is a decision somebody took deliberately. Failing that,
     * what the node itself reports wins, because it is better information than
     * any blanket number this key can hold. Failing both, this.
     *
     * <p><b>It exists because the alternative was a silent off-switch on
     * compaction.</b> {@code Compaction.foldIfItWouldNotFit} takes no decision
     * without a bound to fold towards, so a model whose length could not be
     * determined never folded at all — the server kept working, conversations
     * kept running, and the only report was one warning at boot. A conversation
     * then grows until the endpoint refuses it, which is the failure this whole
     * mechanism exists to prevent.
     *
     * <p>No initializer, for the reason {@link #embeddingModel} sets out:
     * {@code application.yml}'s {@code ${LLM_DEFAULT_CONTEXT_LENGTH:64000}} is
     * the only place this number lives, and it is the place that can carry the
     * argument for choosing it — which the YAML does, at length, because the two
     * directions of being wrong are not symmetric. An unbound instance answers
     * {@code 0}, and {@code LlmConfig.requireDefaultContextLength} refuses that
     * at boot: a bound of zero folds every conversation on its first turn.
     */
    private int defaultContextLength;

    /**
     * A copy of the list, and deliberately not a copy of the pools in it.
     *
     * <p>The copy fixes the shape: nothing can add, drop or reorder a host
     * behind another reader's back, on a bean that is a singleton for the life
     * of the process. The elements are still the live {@link PoolProperties}
     * objects, so this is not the complete defence that {@link
     * PoolProperties#getModels()} is — a caller that wanted to could still set
     * a base URL through one.
     *
     * <p>Said plainly rather than papered over, because a copy that hands back
     * the same mutable elements has been mistaken for a defence on this branch
     * before. It is enough here for a narrow reason: the pools are read once,
     * at boot, by the configuration that turns them into {@code LlmPool}s, and
     * that class copies what it needs out of them. Deep-copying would mean a
     * copy constructor whose job is to be updated every time a field is added,
     * which is the kind of guard that is wrong the first time someone forgets.
     */
    /**
     * Where a person's own sampling profiles live.
     *
     * <h2>{@code sampling}, relative, and the shipped set stands without it</h2>
     *
     * <p>{@code global/agents/} and {@code global/bots/}'s own precedent — see
     * {@code AgentsConfig.agentRegistry} — with one deliberate difference: the
     * profiles this repository ships are loaded from the jar whether or not
     * this directory exists, and files found here are layered over them,
     * exactly as an operator's own definitions layer over the classpath seed.
     * An operator-authored definitions tier with nothing in it changes nothing,
     * because the shipped floor is what runs regardless — a state an operator
     * notices immediately, since it is simply the set this project ships. A
     * profile directory nobody created would mean every model running at its
     * own defaults forever, which is a state nothing reports — so the three
     * families this project has measured against ship live, and this is where
     * a person extends or replaces them.
     *
     * <p>What goes in it: one {@code models.yaml} (or {@code .json}), merged key
     * by key over the shipped mapping, and any number of {@code &lt;name&gt;.yaml}
     * profiles, each replacing a shipped profile of that name whole. Seed it
     * from {@code plowshare-server/src/main/resources/sampling}, which holds the
     * reference copies with the argument for every number written beside it.
     *
     * <p>Relative rather than absolute because no absolute path is right on two
     * machines, and named rather than left unset so that a directory an operator
     * dropped beside the process is picked up.
     */
    private String samplingDirectory = "sampling";

    public String getSamplingDirectory() {
        return samplingDirectory;
    }

    public void setSamplingDirectory(String samplingDirectory) {
        this.samplingDirectory = samplingDirectory;
    }

    public List<PoolProperties> getPools() {
        return List.copyOf(pools);
    }

    /** Copied in, so the caller cannot add or drop a host through the list it
     *  handed over. The pools themselves are shared, exactly as {@link
     *  #getPools()} describes. */
    public void setPools(List<PoolProperties> pools) {
        this.pools = pools == null ? new ArrayList<>() : new ArrayList<>(pools);
    }

    /**
     * A copy, for the reason on {@link PoolProperties#getModels()}; keys and
     * values are both {@code String}, so this one is complete too.
     *
     * <p><b>{@code Map.copyOf} rejects a null value</b>, which is what {@code
     * fast: ~} binds to, so this getter throws for that configuration rather
     * than returning it. {@code LlmConfig} catches the {@code
     * NullPointerException} at the one call site that can name the key, exactly
     * as it already does for a pool's collections.
     */
    public Map<String, String> getClasses() {
        return Map.copyOf(classes);
    }

    /** Copied in, for the reason on {@link PoolProperties#setModels(List)}. */
    public void setClasses(Map<String, String> classes) {
        this.classes = classes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(classes);
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingDim() {
        return embeddingDim;
    }

    public void setEmbeddingDim(int embeddingDim) {
        this.embeddingDim = embeddingDim;
    }

    public int getEmbeddingMaxInputTokens() {
        return embeddingMaxInputTokens;
    }

    public void setEmbeddingMaxInputTokens(int embeddingMaxInputTokens) {
        this.embeddingMaxInputTokens = embeddingMaxInputTokens;
    }

    public int getDefaultContextLength() {
        return defaultContextLength;
    }

    public void setDefaultContextLength(int defaultContextLength) {
        this.defaultContextLength = defaultContextLength;
    }
}
