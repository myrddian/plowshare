package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.ArrayList;
import java.util.List;
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
    private final CapturedText thoughts = new CapturedText();
    private final CapturedText answers = new CapturedText();
    private final CapturedText toolFragments = new CapturedText();
    private long answerCharacters;

    private Call(long sent) {
      this.sent = sent;
    }

    /**
     * {@code sink}, with its first delta of either kind timed.
     *
     * <p>Capture is bounded independently of the droppable preview. Both are called on the thread
     * reading the response and must not block.
     */
    Deltas timing(Deltas sink) {
      return Deltas.observing(
          sink,
          new Deltas() {
            @Override
            public void answered(String delta) {
              arrived();
              answerCharacters += delta.length();
              answers.append(delta);
            }

            @Override
            public void thought(String delta) {
              arrived();
              thoughtCharacters += delta.length();
              thoughts.append(delta);
            }

            @Override
            public void toolCall(int index, String name, String arguments) {
              arrived();
              toolFragments.append(
                  "[tool index=" + index + " name=" + name + "] " + arguments + "\n");
            }
          });
    }

    /**
     * Bounded reasoning from this call, or null for a call that streamed none. Large calls retain
     * their beginning and end, separated by an explicit omitted-character count. Capture sits
     * before the client's droppable queue; the independent bound keeps runaway calls bounded.
     */
    String thought() {
      return thoughts.empty() ? null : thoughts.text();
    }

    /** A protected, nonprojected log record; never an assistant answer or executable tool call. */
    String failedOutput(String reason) {
      long elapsed = Math.max(0, (ticker.getAsLong() - sent) / NANOS_PER_MILLI);
      String firstDelta =
          first < 0 ? "none" : Long.toString(Math.max(0, (first - sent) / NANOS_PER_MILLI));
      return "ERROR: incomplete model call; "
          + reason
          + "\nelapsed_ms="
          + elapsed
          + " first_delta_ms="
          + firstDelta
          + "\nreasoning_characters="
          + thoughtCharacters
          + " answer_characters="
          + answerCharacters
          + " tool_fragment_characters="
          + toolFragments.characters
          + "\n[diagnostic text: NUL is shown as ␀]\n[reasoning]\n"
          + thoughts.text().replace('\0', '␀')
          + "\n[partial answer; not projected]\n"
          + answers.text().replace('\0', '␀')
          + "\n[partial tool fragments; never executed]\n"
          + toolFragments.text().replace('\0', '␀');
    }

    /**
     * Each diagnostic row must fit the existing trajectory excerpt ceiling: DIAGNOSTIC has no
     * full-text retrieval handle. Reserve 100 characters for a part label below that 8,000 limit.
     * Splitting preserves UTF-16 pairs and keeps the head/tail of every channel publicly readable.
     */
    List<String> failedOutputParts(String reason) {
      String output = failedOutput(reason);
      if (output.length() <= 8000) return List.of(output);
      List<String> chunks = new ArrayList<>();
      for (int start = 0; start < output.length(); ) {
        int end = Math.min(start + 7900, output.length());
        if (end < output.length()
            && Character.isHighSurrogate(output.charAt(end - 1))
            && Character.isLowSurrogate(output.charAt(end))) end--;
        chunks.add(output.substring(start, end));
        start = end;
      }
      List<String> parts = new ArrayList<>();
      for (int index = 0; index < chunks.size(); index++) {
        parts.add(
            "ERROR: incomplete model call; diagnostic part "
                + (index + 1)
                + "/"
                + chunks.size()
                + " (nonprojected)\n"
                + chunks.get(index));
      }
      return List.copyOf(parts);
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

  /** Fixed head plus rolling tail: memory and diagnostic volume stay bounded per call/channel. */
  private static final class CapturedText {
    private static final int HALF_LIMIT = 8192;
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder tail = new StringBuilder();
    private long characters;

    void append(String text) {
      characters += text.length();
      int copied = Math.min(HALF_LIMIT - head.length(), text.length());
      head.append(text, 0, copied);
      int remaining = text.length() - copied;
      if (remaining >= HALF_LIMIT) {
        tail.setLength(0);
        tail.append(text, text.length() - HALF_LIMIT, text.length());
      } else if (remaining > 0) {
        int excess = Math.max(0, tail.length() + remaining - HALF_LIMIT);
        tail.delete(0, excess);
        tail.append(text, copied, text.length());
      }
    }

    boolean empty() {
      return characters == 0;
    }

    String text() {
      String beginning = head.toString();
      String ending = tail.toString();
      if (characters > head.length() + tail.length()) {
        if (!beginning.isEmpty()
            && Character.isHighSurrogate(beginning.charAt(beginning.length() - 1)))
          beginning = beginning.substring(0, beginning.length() - 1);
        if (!ending.isEmpty() && Character.isLowSurrogate(ending.charAt(0)))
          ending = ending.substring(1);
      }
      long omitted = characters - beginning.length() - ending.length();
      return beginning + (omitted > 0 ? "\n[" + omitted + " characters omitted]\n" : "") + ending;
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
