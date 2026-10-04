package io.aeyer.plowshare.server.llm;

import java.util.OptionalInt;

/**
 * Where a conversation on one model folds: the size at which a fold becomes <b>due</b> and runs
 * between turns, the size at which one runs <b>now</b>, inside the turn, and the ceiling neither
 * may pass — all in tokens, and all of the model's measured context length.
 *
 * <h2>Two thresholds, because one turn can be the whole conversation</h2>
 *
 * <p>Folds used to happen only between turns, at a third of the window. Measured 2026-09-30 over
 * two implement_specification runs on {@code openai/gpt-oss-120b} (131 072 tokens): no conversation
 * had folded in two days, because the long conversations are single turns — a coder delegation is
 * one turn of up to a hundred model calls, and nothing folds until it ends. One such turn reached
 * <b>80 345</b> prompt tokens, 61% of the window and well past a third, with no fold possible. So
 * the between-turn fold keeps its place and moves later ({@link #due}), and a second fold runs
 * inside the turn at a tool-call boundary when the turn itself is the problem ({@link #now}).
 * {@code implementation rationale} is the decision.
 *
 * <h2>They follow the window, and a small window keeps what it has</h2>
 *
 * <p><b>64K tokens is the least context an agent can work in</b> (spec §1a, revised), so a window
 * that small cannot spare room to an early fold: every fold is lossy, and on a 64K model the room a
 * fold would buy back is room the next turn needs to work in at all. At or below 64K there is
 * therefore <b>no between-turn fold</b> and the in-turn fold waits until 90%.
 *
 * <p><b>A large window can afford to fold later</b>, so the between-turn point rises with it: just
 * above 64K a fold is due at 60%, rising <b>linearly in the window size</b> to 75% at 200K (204
 * 800) and staying there — the most. The in-turn fold falls the other way, from 90% at 64K linearly
 * to 80% at 128K (131 072) and 80% beyond, so there is always room between the two and room above
 * the second: <b>now is above due everywhere in the table</b>. A 131 072 window is due at 67.06%
 * (87 895) and folds in the turn at 80% (104 857). The cost argument is unchanged and is why due is
 * never the wall: {@code implementation rationale} measured about 0.845 s a turn per further
 * thousand tokens of standing history.
 *
 * <p>A fraction and not a number of tokens, for the reason the old third was: a model loaded at a
 * different window folds at the same proportion of it rather than at a figure measured against
 * somebody else's.
 *
 * <h2>An operator's number wins, and the ceiling is not theirs to raise</h2>
 *
 * <p>{@code compaction-thresholds} still sets <em>due</em> per pool and wire model, and {@code
 * compaction-now-thresholds} sets <em>now</em>; a configured number wins over the derived one, in
 * the shape a configured context length already wins over a discovered one — including on a window
 * of 64K or less, where the derived due is none. <b>Neither may exceed the context length</b>: a
 * threshold exists to fold earlier than the wall. <b>And now must be above due</b>: {@code
 * LlmConfig} refuses a pool that says otherwise wherever it can see both numbers; where it cannot —
 * a context length discovered at run time — now is raised to due rather than due lowered to now,
 * because a configured due is an operator's decision and an in-turn fold that pre-empted it would
 * overrule them. The fold then happens no later than asked, which is the safe direction.
 *
 * @param due the prompt size at which a fold becomes due and runs when the turn ends, or empty for
 *     none — a window of 64K or less that nobody configured
 * @param now the prompt size at which a fold runs inside the turn, at the next tool-call boundary
 * @param ceiling the model's context length, which neither threshold passes
 */
public record FoldThresholds(OptionalInt due, int now, int ceiling) {

  /**
   * The least context an agent can work in, and the window at and below which nothing folds between
   * turns (spec §1a): 64K.
   */
  static final int SMALL_WINDOW = 65_536;

  /** The window at and above which the in-turn fold stops falling: 128K, at 80%. */
  static final int NOW_WINDOW = 131_072;

  /** The window at and above which the due fold stops rising: 200K, at 75%. */
  static final int DUE_WINDOW = 204_800;

  /** Percent of the window, at the ends of each line. */
  private static final int NOW_SMALL = 90;

  private static final int NOW_LARGE = 80;
  private static final int DUE_SMALL = 60;
  private static final int DUE_LARGE = 75;

  public FoldThresholds {
    if (ceiling <= 0) {
      throw new IllegalArgumentException("a context length is positive: " + ceiling);
    }
    if (due.isPresent() && (due.getAsInt() <= 0 || due.getAsInt() > now)) {
      throw new IllegalArgumentException(
          "a fold is due at a positive size no later than it runs now: due "
              + due
              + ", now "
              + now);
    }
    if (now <= 0 || now > ceiling) {
      throw new IllegalArgumentException(
          "a fold runs now inside the window: now " + now + ", window " + ceiling);
    }
  }

  /**
   * The defaults for a model loaded at {@code window} tokens, nothing configured.
   *
   * <p>Integer arithmetic throughout, rounding down, so the same window gives the same two numbers
   * on every box.
   *
   * @throws IllegalArgumentException for a window that is not positive
   */
  public static FoldThresholds byWindow(int window) {
    if (window <= 0) {
      throw new IllegalArgumentException("a context length is positive: " + window);
    }
    long w = window;
    int now =
        (int)
            (w
                * along(window, SMALL_WINDOW, NOW_WINDOW, NOW_SMALL, NOW_LARGE)
                / (100L * (NOW_WINDOW - SMALL_WINDOW)));
    if (window <= SMALL_WINDOW) {
      return new FoldThresholds(OptionalInt.empty(), now, window);
    }
    int due =
        (int)
            (w
                * along(window, SMALL_WINDOW, DUE_WINDOW, DUE_SMALL, DUE_LARGE)
                / (100L * (DUE_WINDOW - SMALL_WINDOW)));
    return new FoldThresholds(OptionalInt.of(due), now, window);
  }

  /**
   * A percentage on the line from {@code (from, atFrom)} to {@code (to, atTo)}, held at either end
   * beyond it, and scaled by {@code to - from} so that the arithmetic stays exact in integers until
   * the one division its caller makes.
   */
  private static long along(int window, int from, int to, int atFrom, int atTo) {
    long span = to - from;
    long clamped = Math.max(from, Math.min(to, window));
    return atFrom * span + (long) (atTo - atFrom) * (clamped - from);
  }

  /**
   * The thresholds for a model loaded at {@code ceiling}, with whatever an operator configured laid
   * over the defaults.
   *
   * @param ceiling the model's context length
   * @param configuredDue {@code compaction-thresholds} for this model, or empty
   * @param configuredNow {@code compaction-now-thresholds} for this model, or empty
   */
  public static FoldThresholds of(
      int ceiling, OptionalInt configuredDue, OptionalInt configuredNow) {
    FoldThresholds derived = byWindow(ceiling);
    OptionalInt due =
        configuredDue.isPresent()
            ? OptionalInt.of(Math.min(configuredDue.getAsInt(), ceiling))
            : derived.due;
    int now = Math.min(configuredNow.orElse(derived.now), ceiling);
    if (due.isPresent() && due.getAsInt() > now) {
      // See the class javadoc: raised to the operator's due, never the other way round.
      now = due.getAsInt();
    }
    return new FoldThresholds(due, now, ceiling);
  }
}
