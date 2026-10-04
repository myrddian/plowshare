package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.hooks.Step;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * What the advisor is shown about a stuck turn, and nothing else.
 *
 * <p><b>It opens with why the harness is asking</b> -- which sign of being stuck fired, with the
 * repeated failure's text -- because an advisor shown a turn with no reason to look at it found
 * something to say about every turn (measured 2026-09-30: 187 hints in 88 conversations, nearly all
 * "stop reading, make a concrete change").
 *
 * <p><b>No persona, no system prompt, no earlier turns.</b> The advisor is an analyst; the
 * character the stuck model plays, and a history it has already misread, would bias the analysis.
 * <b>The date carries its weekday</b> because the failure this was built for was a search for a Fed
 * decision "today", on a Sunday.
 *
 * <p><b>It names the tools the run was offered</b>, and says there are no others. Measured
 * 2026-09-30: an advisor that was not told told a reviewer holding only reads and searches to run a
 * check "with a run/exec tool", and the reviewer called {@code run_python}, which does not exist.
 * {@code StuckVerdict} swallows a note that names a tool not in this list.
 */
final class StuckBrief {

  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);

  private StuckBrief() {}

  /**
   * @param why the signal that made the harness ask, as {@code StuckSignals} words it: the advisor
   *     is told it first, so it answers about that sign and not about the turn at large
   * @param tools the tool names the run was offered, listed in order
   */
  static String render(
      String why,
      String question,
      ZonedDateTime now,
      Collection<String> tools,
      List<Step> steps,
      int argumentsExcerpt,
      int resultExcerpt,
      int thinkingExcerpt) {
    StringBuilder brief = new StringBuilder();
    brief.append("Why the harness is asking: ").append(why).append(".\n\n");
    brief.append("The question: ").append(question.strip()).append("\n\n");
    brief
        .append("Today: ")
        .append(DAY.format(now))
        .append(" (")
        .append(now.getZone())
        .append(")\n\n");
    brief
        .append(
            tools.isEmpty()
                ? "The tools it may call: none."
                : "The tools it may call, and no others: "
                    + String.join(", ", new TreeSet<>(tools)))
        .append("\n\n");
    brief.append("Steps so far, oldest first:\n");
    int shown = 0;
    String thinking = null;
    for (Step step : steps) {
      for (int i = 0; i < step.calls().size(); i++) {
        ToolCall call = step.calls().get(i);
        // Cut like a result: an agent writing a whole file or delegating a long
        // task passes kilobytes as arguments, and the advisor needs the shape of
        // the call, not its payload. One line per call keeps the numbering legible.
        brief
            .append(++shown)
            .append(". ")
            .append(call.name())
            .append(' ')
            .append(cut(call.arguments().strip(), argumentsExcerpt).replace('\n', ' '))
            .append('\n');
        brief
            .append("   → ")
            .append(cut(step.results().get(i), resultExcerpt).replace('\n', ' '))
            .append('\n');
      }
      if (step.thinking() != null && !step.thinking().isBlank()) {
        thinking = step.thinking();
      }
    }
    if (thinking != null) {
      brief
          .append("\nThe model's latest thinking:\n")
          .append(cut(thinking.strip(), thinkingExcerpt));
    }
    return brief.toString().strip();
  }

  /** The first {@code most} characters, and an ellipsis when that is not all of it. */
  static String cut(String text, int most) {
    return text.length() <= most ? text : text.substring(0, most) + "…";
  }
}
