package io.aeyer.plowshare.server.llm.openai;

import io.aeyer.plowshare.server.llm.dispatch.ReasoningPolicy;
import io.aeyer.plowshare.server.llm.dispatch.ReasoningStoppedException;
import java.util.function.LongSupplier;

/** Bounded, per-attempt reasoning guard; never classifies a partial tool call as safe to retry. */
final class ReasoningMonitor {
  private final ReasoningPolicy policy;
  private final LongSupplier clock;
  private final StringBuilder tail = new StringBuilder();
  private long firstThought;
  private boolean thinking;
  private boolean progressed;

  ReasoningMonitor(ReasoningPolicy policy, LongSupplier clock) {
    this.policy = policy;
    this.clock = clock;
  }

  void progress() {
    progressed = true;
    tail.setLength(0);
  }

  void check() {
    if (!progressed
        && thinking
        && clock.getAsLong() - firstThought >= policy.reasoningTimeout().toNanos())
      throw new ReasoningStoppedException(policy, "reasoning time budget exhausted");
  }

  void thought(String delta) {
    if (progressed) return;
    if (!thinking) {
      thinking = true;
      firstThought = clock.getAsLong();
    }
    int capacity = policy.repetitionWindowCharacters() * policy.repetitionRepeats();
    // A provider controls chunk size. Retain only a fixed suffix, including for one huge delta.
    tail.append(delta, Math.max(0, delta.length() - capacity), delta.length());
    if (tail.length() > capacity) tail.delete(0, tail.length() - capacity);
    check();
    // Compare accumulated text rather than deltas: chunk boundaries have no semantic meaning.
    for (int period = 32; period <= policy.repetitionWindowCharacters(); period++) {
      int start = tail.length() - period * policy.repetitionRepeats();
      if (start < 0) break;
      boolean repeated = true;
      for (int at = start + period; at < tail.length(); at++) {
        if (tail.charAt(at) != tail.charAt(at - period)) {
          repeated = false;
          break;
        }
      }
      if (repeated) throw new ReasoningStoppedException(policy, "repeated reasoning text");
    }
  }
}
