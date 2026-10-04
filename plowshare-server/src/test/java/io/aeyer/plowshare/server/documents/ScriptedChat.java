package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A chat endpoint that answers what a test queued and records what it was asked.
 *
 * <p><b>No test in this package may reach a live model</b>, and the cascade is the one workload
 * whose whole subject is how many calls it makes. This is what lets the assertions be about that:
 * how many, to which level, with what in front of it, and what happens when one fails — none of
 * which a real endpoint could be asked for on demand.
 *
 * <p>Shared by the three classes that drive a cascade, which is the exception to this repository's
 * usual preference for a fake per test. {@code CuratorTest.Scripted} is private to its file because
 * it exists to script one judgement; this one exists to <em>attribute</em> a call to a level, and
 * three copies of that attribution rule would be three chances for a test to be right about a
 * fixture and wrong about the cascade.
 *
 * <h2>How a call is attributed to a level</h2>
 *
 * <p>{@code JobRuntime} puts the agent's own prompt at index zero as the one system message, and
 * the fixture definitions in these tests end theirs with the agent's name. So {@link Call#agent()}
 * is read off the wire rather than being told to this class by the code under test — which is the
 * property that matters, since "the paragraph level was called two hundred times" is exactly what a
 * cascade could get wrong.
 */
final class ScriptedChat implements LlmTransport {

  /**
   * What one request was: which level it went to, what it sampled at, and every message in it.
   *
   * <p><b>{@code temperature} is here because one caller varies it within a single pass.</b> The
   * cascade does not — every summariser samples at the one value its file declares — but {@code
   * Deliberation} retries an unparseable critic at zero whatever {@code ask_critic.md} says, and
   * that is a per-call override with no other observable trace.
   */
  record Call(String wireModel, String agent, Sampling sampling, List<String> contents) {}

  private final List<Supplier<Completion>> steps = new ArrayList<>();
  private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger index = new AtomicInteger();
  private volatile Supplier<Completion> fallback;

  /** The next answer, once. */
  ScriptedChat then(String text) {
    steps.add(() -> content(text));
    return this;
  }

  /** The next answer, once, however it goes. */
  ScriptedChat then(Supplier<Completion> step) {
    steps.add(step);
    return this;
  }

  /**
   * Every answer past the queued ones.
   *
   * <p>Set it to something that does not quote its input whenever a test is about what a level was
   * <em>shown</em>: the default below echoes, which is what makes an arithmetic test readable and
   * is exactly wrong for an assertion that no summary carried a paragraph upward.
   */
  ScriptedChat thenAlways(String text) {
    fallback = () -> content(text);
    return this;
  }

  List<Call> calls() {
    synchronized (calls) {
      return List.copyOf(calls);
    }
  }

  /** Which level each call went to, in order. */
  List<String> agents() {
    return calls().stream().map(Call::agent).toList();
  }

  /**
   * A dispatcher over this transport, with the two model classes resolving to different wire models
   * so a definition's specifier is distinguishable at the wire.
   */
  LlmDispatcher dispatcher() {
    return new LlmDispatcher(
        List.of(
            new LlmPool(
                "scripted",
                List.of("model-fast", "model-reasoning"),
                Map.of("fast", "model-fast", "reasoning", "model-reasoning"),
                4,
                1,
                Duration.ofSeconds(5),
                this)),
        new NoOpTokenLedger());
  }

  @Override
  public String poolName() {
    return "scripted";
  }

  @Override
  public Completion complete(
      String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
    int at = index.getAndIncrement();
    List<String> contents = messages.stream().map(ScriptedChat::text).toList();
    calls.add(new Call(wireModel, agentOf(contents), sampling, contents));
    if (at < steps.size()) {
      return steps.get(at).get();
    }
    if (fallback != null) {
      return fallback.get();
    }
    // "summary of X", where X is the last user message: the thing this level
    // was asked about. It makes a document sentence a readable record of the
    // whole chain in one string.
    String asked = contents.isEmpty() ? "" : contents.get(contents.size() - 1);
    return content("summary of " + asked);
  }

  private static String agentOf(List<String> contents) {
    String system = contents.isEmpty() ? "" : contents.get(0);
    return system.startsWith("You are the ")
        ? system.substring("You are the ".length()).replace(".", "").trim()
        : "?";
  }

  private static String text(ChatMessage message) {
    return message.content() == null ? "" : message.content();
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned) {
    // Delegated to complete(...) on CuratorTest.Scripted's reasoning:
    // reconciling two wire formats is the transport's problem and
    // OpenAiTransportTest is where it is proved, so a fake answering
    // differently down this path would only test itself.
    Completion streamed = complete(wireModel, messages, sampling, tools);
    if (abandoned.getAsBoolean()) {
      throw new CallerAbandonedException(poolName());
    }
    String content = streamed.content();
    if (content != null && !content.isEmpty()) {
      sink.answered(content);
    }
    return streamed;
  }

  @Override
  public Embeddings embed(String wireModel, List<String> input) {
    throw new UnsupportedOperationException("the cascade does not embed");
  }

  @Override
  public void close() {}

  static Completion content(String text) {
    return new Completion(text, "stop", TokenUsage.UNKNOWN, List.of());
  }
}
