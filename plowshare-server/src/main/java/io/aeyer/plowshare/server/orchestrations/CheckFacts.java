package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.CommandRunner;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The run's check as the harness last ran it, worded for a delegate that judges work it cannot run
 * — the block {@link Orchestrations#checkFacts} puts above such a delegate's task. Measured
 * 2026-09-30, {@code orc_3190C667F18B8E57}: the check had just passed 26 of 26, and {@code
 * code_reviewer}, which runs nothing, reported with "High" confidence that one of those tests
 * fails; the conductor believed it over the check and returned to {@code code}, twice.
 *
 * <p>Every fact here is the harness's own: the command is the run's stored check ({@link
 * OrchestrationChecks}), and when it ran, how it ended and what it printed last are its latest
 * {@code check_ran} row ({@link RecordKeeper#latestCheck}). Nothing a model wrote is read. Pure:
 * the reads are the caller's.
 */
public final class CheckFacts {

  /**
   * Of each stream, how many last lines are kept: where a test runner prints its counts, whatever
   * the language.
   */
  static final int TAIL_LINES = 30;

  /**
   * Of each stream, how many last characters are kept, so a line that never ends cannot fill the
   * block.
   */
  static final int TAIL_CHARACTERS = 3_000;

  /**
   * The most of the output a block shows, keeping its end: both streams at their bound, and their
   * two headings. A row the record holds is already inside it; this binds any other.
   */
  static final int MOST_SHOWN = 2 * TAIL_CHARACTERS + 64;

  /** How much of the command a block quotes. */
  static final int MOST_COMMAND = 200;

  /** How a check's run ended, as its record row says it. */
  public enum Result {
    PASSED,
    FAILED,
    TIMED_OUT,
    /** A row whose line this build cannot read the ending of. */
    UNKNOWN
  }

  /**
   * One run of the check, read back from the record.
   *
   * @param at when it was recorded — as it ended
   * @param result how it ended
   * @param exitCode its exit code, or null when it gave none
   * @param output the end of what it printed, as {@link #output} kept it, or null for a row written
   *     with none
   */
  public record Ran(Instant at, Result result, Integer exitCode, String output) {}

  private CheckFacts() {}

  /**
   * The end of what a check printed: each stream's last {@link #TAIL_LINES} lines, and of those its
   * last {@link #TAIL_CHARACTERS} characters.
   *
   * @param outcome how the check ended
   * @return {@code --- stdout ---} and {@code --- stderr ---}, each followed by its tail, or by
   *     {@code (nothing)}
   */
  public static String output(CommandRunner.Outcome outcome) {
    return "--- stdout ---\n"
        + last(outcome.stdout())
        + "\n--- stderr ---\n"
        + last(outcome.stderr());
  }

  private static String last(String text) {
    String kept = text == null ? "" : text.stripTrailing();
    if (kept.isEmpty()) {
      return "(nothing)";
    }
    List<String> lines = Arrays.asList(kept.split("\n", -1));
    String tail =
        String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
    return endOf(tail, TAIL_CHARACTERS);
  }

  /** The last {@code most} characters of {@code text}, {@code …} first when any were cut. */
  private static String endOf(String text, int most) {
    return text.length() <= most ? text : "…" + text.substring(text.length() - most + 1);
  }

  /**
   * The block, as the harness says it.
   *
   * @param argv the run's check, or null for a run that has none set
   * @param latest its latest run in the record, or empty when it has not run
   * @return one paragraph starting {@code [harness]}; with the output's end below it when the
   *     record holds it
   */
  public static String block(List<String> argv, Optional<Ran> latest) {
    if (argv == null) {
      return "[harness] This run has no check yet: no command has been set to show the work"
          + " is done, so the harness has run nothing and has no result to go on.";
    }
    String command = "`" + quoted(String.join(" ", argv)) + "`";
    if (latest.isEmpty()) {
      return "[harness] The run's check "
          + command
          + " has not run yet: the harness runs it"
          + " each time a checked stage is marked done, and none has been, so there is"
          + " no result to go on.";
    }
    Ran ran = latest.get();
    String at = ran.at().truncatedTo(ChronoUnit.SECONDS).toString();
    String head = "[harness] The run's check " + command + " last ran at " + at;
    if (ran.result() == Result.UNKNOWN) {
      return head + ", and the record does not say how it ended.";
    }
    String ended =
        switch (ran.result()) {
          case PASSED -> "passed (exit 0)";
          case TIMED_OUT -> "timed out, with no exit code";
          default ->
              ran.exitCode() == null
                  ? "failed, with no exit code"
                  : "failed (exit " + ran.exitCode() + ")";
        };
    String output =
        ran.output() == null || ran.output().isBlank()
            ? " The record holds none of its output."
            : " Its output ended:\n" + endOf(ran.output().strip(), MOST_SHOWN);
    return head
        + ": "
        + ended
        + ". The harness ran it itself; this is its result, not"
        + " anyone's account of it."
        + output;
  }

  private static String quoted(String command) {
    String flat = command.replaceAll("\\s+", " ").strip();
    return flat.length() <= MOST_COMMAND ? flat : flat.substring(0, MOST_COMMAND - 1) + "…";
  }
}
