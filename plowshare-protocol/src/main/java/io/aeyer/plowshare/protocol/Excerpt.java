package io.aeyer.plowshare.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Some lines of a file, raw, and which lines they are — what the file side hands back after an
 * edit, and after an edit whose {@code old} was not there.
 *
 * <h2>Facts, not a view</h2>
 *
 * <p>The machine that owns the file finds the lines; the server words them (spec 2026-09-30, the
 * file side reports facts). So this carries no header, no marker and no sentence: the lines as the
 * file has them, where they are, and what had to be left out to keep the frame bounded. Whoever
 * renders them decides how a model is told that.
 *
 * <h2>Bounded here, because the frame is</h2>
 *
 * <p>{@link #of} keeps at most {@link Replacement#MAX_SHOWN_LINES} lines — the first and the last
 * half of a longer region — and cuts each at {@link Replacement#MAX_SHOWN_LINE_CHARS} characters.
 * The bound belongs on the side that has the file: a region of a million lines must not cross the
 * wire so that the server can decline to show it.
 *
 * @param from the first line of the region, counted from 0 as a read's offset is, and as {@link
 *     String#lines()} splits
 * @param to the last line of the region, inclusive; {@code from - 1} for a region of no lines,
 *     which only an empty file has
 * @param total how many lines the file has
 * @param lines the lines shown, each at most {@link Replacement#MAX_SHOWN_LINE_CHARS} characters.
 *     Every line from {@link #from} to {@link #to} unless {@link #gap} says some are left out
 * @param gap where in {@link #lines} the left-out lines belong — the first {@code gap} lines are
 *     the region's head and the rest its tail — or null when nothing is left out
 * @param clipped the indexes in {@link #lines} of the lines cut at {@link
 *     Replacement#MAX_SHOWN_LINE_CHARS}; empty when none was
 */
public record Excerpt(
    int from, int to, int total, List<String> lines, Integer gap, List<Integer> clipped) {

  public Excerpt {
    lines = lines == null ? List.of() : List.copyOf(lines);
    // Absent is none: a client that sends no key means nothing was cut.
    clipped = clipped == null ? List.of() : List.copyOf(clipped);
  }

  /**
   * Whether this is every line of the file, uncut — the one case in which seeing it is seeing the
   * file, which is what lets the server count an edit that showed it as a read. An empty file is
   * all of itself.
   */
  public boolean whole() {
    return from == 0 && to == total - 1 && gap == null && clipped.isEmpty();
  }

  /** How many lines of the region {@link #gap} stands for; 0 when none. */
  public int omitted() {
    return gap == null ? 0 : (to - from + 1) - lines.size();
  }

  /**
   * Lines {@code from} to {@code to} of a file, clamped to it and bounded.
   *
   * @param all the file's lines, as {@link String#lines()} splits it
   */
  public static Excerpt of(List<String> all, int from, int to) {
    Objects.requireNonNull(all, "all");
    int total = all.size();
    from = Math.max(0, from);
    to = Math.min(total - 1, to);
    int count = to - from + 1;
    List<String> shown = new ArrayList<>();
    List<Integer> clipped = new ArrayList<>();
    Integer gap = null;
    if (count > Replacement.MAX_SHOWN_LINES) {
      int head = Replacement.MAX_SHOWN_LINES / 2;
      int tail = Replacement.MAX_SHOWN_LINES - head;
      for (int i = from; i < from + head; i++) {
        shown.add(clipped(all.get(i), shown.size(), clipped));
      }
      gap = head;
      for (int i = to - tail + 1; i <= to; i++) {
        shown.add(clipped(all.get(i), shown.size(), clipped));
      }
    } else {
      for (int i = from; i <= to; i++) {
        shown.add(clipped(all.get(i), shown.size(), clipped));
      }
    }
    return new Excerpt(from, to, total, shown, gap, clipped);
  }

  private static String clipped(String line, int index, List<Integer> clipped) {
    int most = Replacement.MAX_SHOWN_LINE_CHARS;
    if (line.length() <= most) {
      return line;
    }
    clipped.add(index);
    // Never half of a surrogate pair: a lone surrogate is not text, and a
    // frame carrying one does not survive a strict encoder.
    int end = Character.isHighSurrogate(line.charAt(most - 1)) ? most - 1 : most;
    return line.substring(0, end);
  }
}
