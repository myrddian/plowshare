package io.aeyer.plowshare.server.llm.dispatch;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * How to sample from a model, with every field able to be absent.
 *
 * <h2>Absent is not zero, and that distinction is the whole of this type</h2>
 *
 * <p>{@code AgentDefinition} chose a primitive {@code double} for {@code temperature:} and wrote
 * down the exact condition for revisiting it: <em>"the distinction becomes worth representing only
 * when a second source of a default exists… and that is a change at the loader where the layering
 * would live."</em> A per-model profile is that second source, so the condition is met and the
 * distinction is now represented.
 *
 * <p><b>{@link #NONE} means send nothing, and it is safer than any number this repository could
 * pick.</b> LM Studio resolves a setting in the order model defaults → {@code model.yaml} →
 * load-time → inference-time, later winning, and a model's own {@code model.yaml} carries its
 * vendor's recommended values. So a request carrying no {@code temperature} key runs the model at
 * the numbers its own packaging ships. Measured on 2026-09-04 ({@code implementation rationale}):
 * the runaway that started this design was not a missing setting, it was {@code ChatRequest.of}'s
 * hardcoded {@code 0.0} <em>overriding</em> a correct one — 3 999 completion tokens, 3 997 of them
 * reasoning, and empty content, twice, deterministically. Silence would have been right all along.
 *
 * <h2>Three layers, later winning, and each states only what it knows</h2>
 *
 * <p>{@link #overriddenBy} is the layering and {@link #carriedBy} is the filter:
 *
 * <pre>    profile for this wire model at this agent's intent
 *      -&gt; per-agent explicit override   ({@link #overriddenBy})
 *        -&gt; what this transport can send ({@link #carriedBy})</pre>
 *
 * <p>An override states only what it means to move — an agent naming a temperature and nothing else
 * must not silently erase the profile's truncation, because a temperature without the {@code top_p}
 * it was recommended beside is a different configuration and not a smaller change.
 *
 * <p>Immutable, and every {@code with…} returns a new one. Values are validated where they are
 * written for the reason {@code ChatRequest} validates its temperature there: JSON has no encoding
 * for NaN or an infinity, and a value that reached the endpoint would fail as a type error about a
 * field the caller never wrote.
 */
public record Sampling(
    OptionalDouble temperature,
    OptionalDouble topP,
    OptionalInt topK,
    OptionalInt maxTokens,
    Optional<Effort> reasoningEffort,
    Optional<JsonSchema> responseFormat) {

  /**
   * What an agent declares, resolved per model rather than per file.
   *
   * <p>Three levels and no fourth. The vocabulary has to express Anchor's
   * proposer/critic/synthesiser spread, which is the hardest case in the shipped tree, and it does;
   * a fourth waits for something that needs one.
   *
   * <p><b>{@code PRECISE} does not mean zero.</b> It means the most reproducible configuration
   * <em>the model in use actually supports</em>, which on the Gemma family is a temperature of 1.0
   * with the truncation pulled in. A profile's job is to know that the floor is not zero.
   */
  public enum Intent {
    /** The same input should give the same label. Every summariser, the scribe. */
    PRECISE,
    /** No opinion; what an agent that names no key declares. */
    BALANCED,
    /** Range is the point. A proposer drafting against a critic. */
    EXPLORATORY;

    /** What an agent file that names no {@code sampling:} key means. */
    public static final Intent DEFAULT = BALANCED;

    /** The spelling used in an agent file and in a profile file. */
    public String declared() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * {@code reasoning_effort}, which is not a sampling parameter at all.
   *
   * <p>It rides here because a profile holds <em>how to talk to this model</em> rather than merely
   * how to sample from it: on {@code gpt-oss-20b} the answer to "spend fewer reasoning tokens" is
   * this field, and on Gemma there is no such knob at all. Closed at the values below, so a
   * misspelling in a profile file is refused by name rather than sent.
   *
   * <p><b>{@code NONE} is not a fourth point on the same scale, and it was added because a
   * measurement said so.</b> Measured against GLM-4.7-Flash on llama.cpp, 2026-09-07, one prompt at
   * {@code max_tokens} 1024: {@code low}, {@code medium}, {@code high} and <em>omitting the field
   * entirely</em> are indistinguishable — ~17s, ~5 200 characters of reasoning, and the answer
   * truncated at {@code length} before it finished. {@code none} returned in <b>1.3s</b> with a
   * complete answer and no reasoning at all. On that family this field is a switch and not a dial;
   * on {@code gpt-oss} the three gradations are real, which is why both spellings live here.
   *
   * <p><b>What it costs to omit it.</b> Without {@code NONE} the only lever on a reasoning model is
   * the endpoint's own global flag — {@code --no-reasoning-preserve} on llama.cpp — which turns
   * reasoning off for every agent on that node at once and needs a restart to change. With it, a
   * profile decides per intent, which is the granularity the rest of this class already works at.
   */
  public enum Effort {
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * One thing a request can carry, so that a transport can say which of them it cannot.
   *
   * <p>Named rather than left implicit because the contract is that a parameter a transport cannot
   * send is dropped <b>with a note</b>. A drop nothing can name is a drop nothing can report, and a
   * profile written for one backend would quietly become a different configuration on another.
   */
  public enum Parameter {
    TEMPERATURE,
    TOP_P,
    TOP_K,
    MAX_TOKENS,
    REASONING_EFFORT,
    RESPONSE_FORMAT;

    /** The wire spelling, which is also the profile file's key. */
    public String declared() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * The shape this call requires its answer in, or empty for prose.
   *
   * <h2>It rides here, and the reason is not {@code reasoning_effort}'s</h2>
   *
   * <p>{@link Effort} rides on this type because a profile holds <em>how to talk to this
   * model</em>, and "spend fewer reasoning tokens" is a fact about a model family. <b>A schema is
   * not.</b> It is a fact about one request — what this caller is asking for this time — and the
   * design that introduced it names "in the sampling profile" as the wrong answer explicitly, so
   * that nobody proposes it later.
   *
   * <p>What it is here for instead is mechanical, and it is worth being plain about: {@link
   * Parameter} and {@link #carriedBy} are the only vocabulary in this server for <em>a field a
   * transport may not be able to send</em>, and {@code LlmDispatcher.carried} is the only thing
   * that says so out loud when it cannot. A {@code response_format} written by a transport that did
   * not declare it would be dropped in silence by that filter; one declared and not written would
   * be a schema an agent file asks for and no request carries. Both are the invisible-fact failure
   * this contract exists to end, and putting the field anywhere else would mean building a second
   * copy of the same machinery for one value.
   *
   * <p><b>So the guard is at the other end: {@code SamplingProfiles} reads no key for this and must
   * not grow one.</b> A profile file names a model family and cannot know what any particular call
   * is asking for. The only thing that puts a value in here is an agent definition's own {@code
   * schema:} block, which is where "this agent is a microservice with a contract" is legible to the
   * person reading the agent.
   */
  @Override
  public Optional<JsonSchema> responseFormat() {
    return responseFormat;
  }

  /** Send nothing, and let the model's own defaults apply. See the class javadoc. */
  public static final Sampling NONE =
      new Sampling(
          OptionalDouble.empty(),
          OptionalDouble.empty(),
          OptionalInt.empty(),
          OptionalInt.empty(),
          Optional.empty(),
          Optional.empty());

  public Sampling {
    Objects.requireNonNull(temperature, "temperature");
    Objects.requireNonNull(topP, "topP");
    Objects.requireNonNull(topK, "topK");
    Objects.requireNonNull(maxTokens, "maxTokens");
    Objects.requireNonNull(reasoningEffort, "reasoningEffort");
    Objects.requireNonNull(responseFormat, "responseFormat");
    requireFinite("temperature", temperature);
    requireFinite("top_p", topP);
    requirePositive("top_k", topK);
    requirePositive("max_tokens", maxTokens);
  }

  /**
   * A temperature, and nothing else stated.
   *
   * <p>The range is deliberately only "finite", quoted from {@code ChatRequest} rather than
   * re-decided here: backends disagree about the ceiling and llama.cpp reads a temperature at or
   * below zero as greedy sampling rather than as an error, so refusing a value the local server
   * would have honoured is the worse of the two mistakes.
   */
  public Sampling withTemperature(double value) {
    return new Sampling(
        OptionalDouble.of(value), topP, topK, maxTokens, reasoningEffort, responseFormat);
  }

  public Sampling withTopP(double value) {
    return new Sampling(
        temperature, OptionalDouble.of(value), topK, maxTokens, reasoningEffort, responseFormat);
  }

  public Sampling withTopK(int value) {
    return new Sampling(
        temperature, topP, OptionalInt.of(value), maxTokens, reasoningEffort, responseFormat);
  }

  public Sampling withMaxTokens(int value) {
    return new Sampling(
        temperature, topP, topK, OptionalInt.of(value), reasoningEffort, responseFormat);
  }

  public Sampling withReasoningEffort(Effort value) {
    return new Sampling(
        temperature,
        topP,
        topK,
        maxTokens,
        Optional.of(Objects.requireNonNull(value, "reasoningEffort")),
        responseFormat);
  }

  /**
   * The same call, required to answer in this shape.
   *
   * <p>Layered exactly like every other field — an agent's explicit block wins over a profile,
   * which cannot state one — so nothing about the ordering here is special. What is special is that
   * no profile can put a value in: see {@link #responseFormat}.
   */
  public Sampling withResponseFormat(JsonSchema value) {
    return new Sampling(
        temperature,
        topP,
        topK,
        maxTokens,
        reasoningEffort,
        Optional.of(Objects.requireNonNull(value, "responseFormat")));
  }

  /** Nothing to send: the request omits every sampling key. */
  public boolean isEmpty() {
    return present().isEmpty();
  }

  /** Exactly the parameters this carries a value for. */
  public Set<Parameter> present() {
    Set<Parameter> set = EnumSet.noneOf(Parameter.class);
    if (temperature.isPresent()) {
      set.add(Parameter.TEMPERATURE);
    }
    if (topP.isPresent()) {
      set.add(Parameter.TOP_P);
    }
    if (topK.isPresent()) {
      set.add(Parameter.TOP_K);
    }
    if (maxTokens.isPresent()) {
      set.add(Parameter.MAX_TOKENS);
    }
    if (reasoningEffort.isPresent()) {
      set.add(Parameter.REASONING_EFFORT);
    }
    if (responseFormat.isPresent()) {
      set.add(Parameter.RESPONSE_FORMAT);
    }
    return set;
  }

  /**
   * This, with everything {@code later} states replacing what is stated here.
   *
   * <p>Field by field and not wholesale: see the class javadoc for why an override naming one
   * parameter must leave the rest of the profile standing.
   */
  public Sampling overriddenBy(Sampling later) {
    return new Sampling(
        later.temperature.isPresent() ? later.temperature : temperature,
        later.topP.isPresent() ? later.topP : topP,
        later.topK.isPresent() ? later.topK : topK,
        later.maxTokens.isPresent() ? later.maxTokens : maxTokens,
        later.reasoningEffort.isPresent() ? later.reasoningEffort : reasoningEffort,
        later.responseFormat.isPresent() ? later.responseFormat : responseFormat);
  }

  /** This, with everything {@code carries} does not name removed. */
  public Sampling carriedBy(Set<Parameter> carries) {
    return new Sampling(
        carries.contains(Parameter.TEMPERATURE) ? temperature : OptionalDouble.empty(),
        carries.contains(Parameter.TOP_P) ? topP : OptionalDouble.empty(),
        carries.contains(Parameter.TOP_K) ? topK : OptionalInt.empty(),
        carries.contains(Parameter.MAX_TOKENS) ? maxTokens : OptionalInt.empty(),
        carries.contains(Parameter.REASONING_EFFORT) ? reasoningEffort : Optional.<Effort>empty(),
        carries.contains(Parameter.RESPONSE_FORMAT)
            ? responseFormat
            : Optional.<JsonSchema>empty());
  }

  /**
   * What {@link #carriedBy} would drop, so a caller can say so out loud.
   *
   * <p>Empty for the ordinary case, which is what makes a note affordable at every dispatch:
   * nothing is formatted unless something was actually lost.
   */
  public Set<Parameter> notCarriedBy(Set<Parameter> carries) {
    Set<Parameter> dropped = EnumSet.noneOf(Parameter.class);
    for (Parameter parameter : present()) {
      if (!carries.contains(parameter)) {
        dropped.add(parameter);
      }
    }
    return dropped;
  }

  /** What a log line or a refusal says about this, shortest first. */
  public String described() {
    if (isEmpty()) {
      return "nothing (the model's own defaults apply)";
    }
    StringBuilder text = new StringBuilder();
    temperature.ifPresent(value -> text.append("temperature=").append(value).append(' '));
    topP.ifPresent(value -> text.append("top_p=").append(value).append(' '));
    topK.ifPresent(value -> text.append("top_k=").append(value).append(' '));
    maxTokens.ifPresent(value -> text.append("max_tokens=").append(value).append(' '));
    reasoningEffort.ifPresent(
        value -> text.append("reasoning_effort=").append(value.wire()).append(' '));
    // The NAME and not the schema. A schema is a nested document and this is
    // one line; a log that printed the whole of it would be a log nobody
    // reads, which is how the line that matters gets buried.
    responseFormat.ifPresent(
        value -> text.append("response_format=").append(value.described()).append(' '));
    return text.toString().strip();
  }

  private static void requireFinite(String field, OptionalDouble value) {
    if (value.isPresent() && !Double.isFinite(value.getAsDouble())) {
      throw new IllegalArgumentException(
          field
              + " must be a finite number; got "
              + value.getAsDouble()
              + ". JSON has no encoding for NaN or an infinity, so this would"
              + " fail at the endpoint as a type error about a field nobody"
              + " here wrote");
    }
  }

  private static void requirePositive(String field, OptionalInt value) {
    if (value.isPresent() && value.getAsInt() <= 0) {
      throw new IllegalArgumentException(
          field
              + " must be greater than zero; got "
              + value.getAsInt()
              + ". Zero is not 'unset' — unset is the absent value this type"
              + " exists to represent");
    }
  }
}
