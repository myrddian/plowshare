package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.function.LongSupplier;

/**
 * One run's {@link Pace}, added up as the loop goes.
 *
 * <p><b>On a ticker and not on the runtime's clock.</b> {@code JobRuntime.clock} is read an exact
 * number of times per call — once before, once after — and the durations the log records are the
 * differences of those reads. A read at the first delta would sit between the two, so it gets a
 * monotonic ticker of its own and the clock keeps its count.
 *
 * <p>Not thread-safe, and it need not be: a run's loop is one thread, and the sink it wraps is
 * called on the thread reading the model's response, strictly inside the call it belongs to.
 */
final class Pacing {

  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  private final LongSupplier ticker;
  private int toolCalls;
  private Integer completionTokens;
  private Integer reasoningTokens;
  private boolean reasoningEstimated;
  private Long firstTokenMillis;
  private long generatedTokens;
  private long generatingNanos;

  Pacing(LongSupplier ticker) {
    this.ticker = ticker;
  }

  /** One model call, from the moment it is sent. */
  Call sending() {
    return new Call(ticker.getAsLong());
  }

  /** The model asked for {@code count} tool calls in one completion. */
  void asked(int count) {
    toolCalls += count;
  }

  Pace pace() {
    Double tps =
        generatingNanos > 0 && generatedTokens > 0
            ? generatedTokens / (generatingNanos / NANOS_PER_SECOND)
            : null;
    return new Pace(
        toolCalls, completionTokens, reasoningTokens, firstTokenMillis, tps, reasoningEstimated);
  }

  /** One call in flight. */
  final class Call {

    private final long sent;
    private long first = -1;
    private long thoughtCharacters;
    private final StringBuilder thoughts = new StringBuilder();
    private long answerCharacters;

    private Call(long sent) {
      this.sent = sent;
    }

    /**
     * {@code sink}, with its first delta of either kind timed.
     *
     * <p>The check is one comparison per chunk on the thread reading the response, and nothing is
     * built.
     */
    Deltas timing(Deltas sink) {
      return new Deltas() {
        @Override
        public void answered(String delta) {
          arrived();
          answerCharacters += delta.length();
          sink.answered(delta);
        }

        @Override
        public void thought(String delta) {
          arrived();
          thoughtCharacters += delta.length();
          thoughts.append(delta);
          sink.thought(delta);
        }
      };
    }

    /**
     * Everything the model streamed as thinking during this call, in order, or null for a call that
     * streamed none.
     *
     * <p>Every delta and not a preview's tail: the wrapper sits inside the call, before the
     * client's droppable queue, so nothing here was lost.
     */
    String thought() {
      return thoughts.isEmpty() ? null : thoughts.toString();
    }

    private void arrived() {
      if (first < 0) {
        first = ticker.getAsLong();
      }
    }

    /**
     * The call came back. Adds it to the run and answers how long it took to its first delta, or
     * null for a call that streamed none — a completion that only asked for tools may say nothing
     * before it does.
     */
    Long ended(Completion completion) {
      long end = ticker.getAsLong();
      TokenUsage usage = completion.usage();
      Integer generated = counted(usage.completionTokens());
      completionTokens = sum(completionTokens, generated);
      reasoningTokens =
          sum(
              reasoningTokens,
              thinking(usage, generated, completion, thoughtCharacters, answerCharacters));
      if (first < 0) {
        return null;
      }
      long toFirst = Math.max(0, (first - sent) / NANOS_PER_MILLI);
      if (firstTokenMillis == null) {
        firstTokenMillis = toFirst;
      }
      if (generated != null && generated > 0 && end > first) {
        generatedTokens += generated;
        generatingNanos += end - first;
      }
      return toFirst;
    }
  }

  /**
   * What this call spent thinking: the model's count when it gave a positive one, and otherwise —
   * when it streamed thinking all the same — the call's own completion count split by the
   * characters each part streamed.
   *
   * <p><b>A reported zero beside streamed thinking is not believed</b>, and nothing else is
   * second-guessed: a zero from a call that streamed no thinking stands, and so does an absent
   * count with nothing to split. The tool calls' own arguments are characters the completion count
   * paid for too, so they are in the whole the thinking is a share of.
   */
  private Integer thinking(
      TokenUsage usage, Integer generated, Completion completion, long thought, long answered) {
    Integer reported = counted(usage.reasoningTokens());
    if (reported != null && reported > 0) {
      return reported;
    }
    if (thought == 0 || generated == null || generated == 0) {
      return reported;
    }
    long called = 0;
    for (ToolCall call : completion.toolCalls()) {
      called += call.name().length() + call.arguments().length();
    }
    long whole = thought + answered + called;
    reasoningEstimated = true;
    return (int) Math.round((double) generated * thought / whole);
  }

  /**
   * A count as reported. Zero stays zero here, unlike a prompt measurement: a model that thought
   * nothing reports a real zero, and it is the absent field — the LM Studio socket's reasoning
   * count — that is null.
   */
  private static Integer counted(Integer count) {
    return count == null || count < 0 ? null : count;
  }

  private static Integer sum(Integer known, Integer more) {
    if (more == null) {
      return known;
    }
    return known == null ? more : known + more;
  }
}
