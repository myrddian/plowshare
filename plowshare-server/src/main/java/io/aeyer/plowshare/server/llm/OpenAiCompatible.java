package io.aeyer.plowshare.server.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.InferenceObserver;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.ToolChoice;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.openai.OpenAiTransport;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The provider every backend can be: {@code /v1}, and nothing else.
 *
 * <p>It knows a context length only if it was told one. There is no field in the OpenAI model list
 * to read one from — measured against the reference box on 2026-08-31, {@code GET /v1/models}
 * returns {@code id}, {@code object} and {@code owned_by} per model and nothing more — so an
 * unconfigured length here is not a lookup that failed, it is a question the contract cannot
 * express.
 *
 * <p>This is also the object a vendor decorator wraps, and the reason it owns the transport rather
 * than being handed one: a decorator's extra endpoint is reached through {@link #probe(String)} and
 * therefore through the pool's one HTTP client, which is what keeps the containment invariant on
 * {@code OkHttpClient} holders true.
 */
public class OpenAiCompatible implements LlmProvider, WebSocketOpener {

  private final PoolProperties props;
  private final OpenAiTransport transport;

  /**
   * Builds the pool's transport, and is therefore what has to be closed if anything downstream of
   * it fails to build.
   *
   * <p>{@code LlmConfig} hoists this out of the argument list it is used in for that reason — an
   * argument is evaluated before the constructor it is passed to, so a provider built inline and a
   * pool constructor that then throws leaves an {@code OkHttpClient} with no owner and nothing to
   * close it.
   */
  public OpenAiCompatible(PoolProperties props, ObjectMapper mapper) {
    this.props = props;
    this.transport = new OpenAiTransport(props, mapper);
  }

  @Override
  public io.aeyer.plowshare.server.llm.counting.PromptCount countChat(
      String model, io.aeyer.plowshare.server.llm.dispatch.ChatRequest request) {
    return transport.countChat(model, request);
  }

  @Override
  public io.aeyer.plowshare.server.llm.counting.PromptCount countEmbedding(
      String model, io.aeyer.plowshare.server.llm.dispatch.EmbeddingRequest request) {
    return transport.countEmbedding(model, request);
  }

  @Override
  public boolean automaticCounting() {
    return transport.automaticCounting();
  }

  @Override
  public String poolName() {
    return transport.poolName();
  }

  @Override
  public Completion complete(
      String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
    return transport.complete(wireModel, messages, sampling, tools);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned) {
    return transport.stream(wireModel, messages, sampling, tools, sink, abandoned);
  }

  /**
   * {@inheritDoc} Delegated, because the transport underneath is what writes the body and therefore
   * what knows.
   */
  @Override
  public Set<Sampling.Parameter> carries(String model, Sampling sampling) {
    return transport.carries(model, sampling);
  }

  @Override
  public Set<Sampling.Parameter> carries() {
    return transport.carries();
  }

  @Override
  public Embeddings embed(String wireModel, List<String> input) {
    return transport.embed(wireModel, input);
  }

  @Override
  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice choice,
      InferenceObserver observer) {
    return transport.complete(wireModel, messages, sampling, tools, choice, observer);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice choice,
      InferenceObserver observer) {
    return transport.stream(
        wireModel, messages, sampling, tools, sink, abandoned, choice, observer);
  }

  @Override
  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      io.aeyer.plowshare.server.llm.dispatch.ToolChoice choice,
      InferenceObserver observer,
      Duration timeout) {
    return transport.complete(wireModel, messages, sampling, tools, choice, observer, timeout);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      io.aeyer.plowshare.server.llm.dispatch.ToolChoice choice,
      InferenceObserver observer,
      Duration timeout) {
    return transport.stream(
        wireModel, messages, sampling, tools, sink, abandoned, choice, observer, timeout);
  }

  @Override
  public Duration promptTimeout() {
    return transport.promptTimeout();
  }

  @Override
  public Embeddings embed(String wireModel, List<String> input, InferenceObserver observer) {
    return transport.embed(wireModel, input, observer);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Forwarded to the transport for the same containment reason {@link #probe(String)} lives
   * there: the pool has one HTTP client and a vendor decorator reaches it through this class rather
   * than opening its own.
   */
  @Override
  public okhttp3.WebSocket openWebSocket(okhttp3.HttpUrl url, okhttp3.WebSocketListener listener) {
    return transport.openWebSocket(url, listener);
  }

  @Override
  public OptionalInt maxContextLength(String wireModel) {
    Integer value = props.getMaxContextLengths().get(wireModel);
    return value == null ? OptionalInt.empty() : OptionalInt.of(value);
  }

  /**
   * Whatever {@code context-lengths} says, and nothing else.
   *
   * <p>Read from the properties per call rather than snapshotted in the constructor, on the rule
   * {@code OpenAiTransport} states for every value it consults: {@link PoolProperties} is a mutable
   * bean, and a value that can still be acted on should be read where it is acted on.
   */
  @Override
  public OptionalInt contextLength(String wireModel) {
    return configuredContextLength(wireModel);
  }

  /**
   * The same answer, and that is the whole content of this provider's position: {@code /v1} has no
   * field carrying a context length, so everything this transport knows is something an operator
   * wrote.
   *
   * <p>Overridden rather than left to the interface default all the same. The default answers
   * empty, and a bare transport genuinely holds no configuration — but this one does, and
   * inheriting silence here would mean a pool with a {@code context-lengths} entry logging that its
   * length came from the node it cannot even ask.
   */
  @Override
  public OptionalInt configuredContextLength(String wireModel) {
    Integer configured = props.getContextLengths().get(wireModel);
    return configured == null ? OptionalInt.empty() : OptionalInt.of(configured);
  }

  /**
   * Whatever {@code compaction-thresholds} says, and nothing else.
   *
   * <p>Read per call for the reason above, and configured-or-nothing for the reason {@link
   * io.aeyer.plowshare.server.llm.dispatch.LlmTransport#compactionThreshold} gives: no endpoint has
   * an opinion about when a conversation should fold.
   */
  @Override
  public OptionalInt compactionThreshold(String wireModel) {
    Integer configured = props.getCompactionThresholds().get(wireModel);
    return configured == null ? OptionalInt.empty() : OptionalInt.of(configured);
  }

  /**
   * Whatever {@code compaction-now-thresholds} says, and nothing else, for the reason {@link
   * #compactionThreshold} gives.
   */
  @Override
  public OptionalInt compactionNowThreshold(String wireModel) {
    Integer configured = props.getCompactionNowThresholds().get(wireModel);
    return configured == null ? OptionalInt.empty() : OptionalInt.of(configured);
  }

  /**
   * False, and it is a statement about the {@code /v1} contract rather than about this endpoint's
   * health.
   *
   * <p>So a pool on this provider with no configured length will never have one, which is what
   * makes the boot warning in {@code LlmConfig} actionable rather than premature.
   */
  @Override
  public boolean canDiscover() {
    return false;
  }

  /**
   * An authenticated {@code GET} beside this pool's {@code /v1} root, for a decorator that has a
   * vendor endpoint to read.
   *
   * <p>Not something this provider itself ever calls. It is here because the pool's HTTP client is
   * here: {@code InvariantsTest} names the three files in {@code main} allowed to hold one, and a
   * vendor provider that opened its own would be a fourth place configuring timeouts, retries and a
   * connection pool for the same box. The vendor decides which URL; the transport keeps every guard
   * on the key and on what the endpoint says back.
   *
   * <p>Throws rather than returning empty. A decorator is the layer that decides a failed probe is
   * survivable, and it cannot decide that about a failure it was never shown.
   *
   * @param path an absolute path on the host, beginning with {@code /}
   */
  public JsonNode probe(String path) {
    return transport.metadata(path);
  }

  @Override
  public void close() {
    transport.close();
  }
}
