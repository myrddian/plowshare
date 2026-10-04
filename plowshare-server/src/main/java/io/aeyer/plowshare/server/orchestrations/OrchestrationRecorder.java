package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.RunActivity;
import io.aeyer.plowshare.server.approvals.ApprovalEvents;
import io.aeyer.plowshare.server.todos.StatusMoves;
import io.aeyer.plowshare.server.todos.TodoItem;
import java.util.List;
import java.util.Optional;

/**
 * Everything the orchestration record is told, at the point each thing happens — spec 2026-09-28,
 * the orchestration record §2. The engine ({@link Orchestrations}), the stall sweep and the stage
 * checks call the methods declared here; the three interfaces it extends are the narrower doors the
 * turn loop, the todo board and the approval store are handed, each owned by its own package so
 * none of them depends on this one.
 *
 * <p>Every method is a no-op by default and {@link #NONE} is all of them: a source that was never
 * wired records nothing. <b>An implementation must never throw</b> — recording never fails the work
 * it describes — and {@link RecordKeeper}, the one that writes, is built to that rule.
 *
 * <p>{@link RunActivity#delegationFacts} — the facts footer, spec 2026-09-29 §1a — is inherited
 * from {@link RunActivity} rather than declared again here: {@link RecordKeeper} overrides it once,
 * reading the same record every other method here writes.
 */
public interface OrchestrationRecorder extends RunActivity, StatusMoves, ApprovalEvents {

  OrchestrationRecorder NONE = new OrchestrationRecorder() {};

  /**
   * A run has been written and is about to be spoken to for the first time.
   *
   * @param phase the phase directory its parent's todo names, or {@code null}
   */
  default void runStarted(OrchestrationRecord run, String request, String phase) {}

  /** A run has just reached a terminal state; {@code run} is its row read after. */
  default void runEnded(OrchestrationRecord run) {}

  default void questionAsked(OrchestrationRecord run, String question) {}

  default void questionAnswered(OrchestrationRecord run, String answer, String author) {}

  /**
   * The harness settled a person's answer to an install question (spec
   * 2026-09-29-orchestration-studio §3.4): {@code outcome} is what the conductor is told —
   * installed where, declined, or refused and why. Recorded when it is settled, so it is on the
   * record even when the conductor is never spoken to again.
   */
  default void installSettled(OrchestrationRecord run, String outcome) {}

  /** The stall sweep has newly judged {@code run} quiet. */
  default void stalled(OrchestrationRecord run, String notice) {}

  /** A run's check has run; {@code exitCode} is {@code null} for one that did not exit. */
  default void checkRan(
      OrchestrationRecord run, List<String> argv, Integer exitCode, boolean timedOut) {}

  /**
   * A run's check has run, with the end of what it printed — which a reviewing delegate is later
   * handed with its result ({@link CheckFacts}). By default the output is dropped and the
   * four-argument form told, so a recorder that knows only that one still hears every check.
   *
   * @param output the end of its output, as {@link CheckFacts#output} keeps it, or null
   */
  default void checkRan(
      OrchestrationRecord run,
      List<String> argv,
      Integer exitCode,
      boolean timedOut,
      String output) {
    checkRan(run, argv, exitCode, timedOut);
  }

  /**
   * The latest run of {@code run}'s own check, as the record holds it — what a reviewing delegate
   * is handed ({@link Orchestrations#checkFacts}).
   *
   * @param run the run
   * @return its newest {@code check_ran} row, read back; empty for none, or a record that cannot be
   *     read
   */
  default Optional<CheckFacts.Ran> latestCheck(OrchestrationRecord run) {
    return Optional.empty();
  }

  /**
   * One of a run's acceptance commands has run (spec 2026-09-29 §1b).
   *
   * @param run the run
   * @param command the command as it ran
   * @param passed whether it ended with its exit code and printed what it must
   * @param why why it failed, or null when it passed
   */
  default void acceptanceRan(OrchestrationRecord run, String command, boolean passed, String why) {}

  /**
   * A run's check was set under consent the person had already given — another run of the same tree
   * allowed the same command, or a standing project approval covers it — so nobody was asked (V67).
   *
   * @param run the run whose check it is
   * @param argv the check
   * @param approval the approval that covered it
   * @param how where that approval came from, in a few words
   */
  default void consentCovered(
      OrchestrationRecord run, List<String> argv, String approval, String how) {}

  /**
   * The acceptance checker's work on a run (spec 2026-10-01, the acceptance checker §2): a concern
   * raised, a WHY put to the conductor and its answer, a verdict, the person's answer — each one
   * line of the run's story, so {@code /watch} and the panel show it.
   *
   * @param run the run
   * @param actor who did it: the checker by name, the conductor, the person or the harness
   * @param text the line
   * @param body the whole text behind the line, or null when the line is all of it
   */
  default void concern(OrchestrationRecord run, String actor, String text, String body) {}

  /**
   * A cap was passed without asking anyone — spec 2026-09-29 §2's auto-continue — the {@code n}th
   * of the {@code most} the person's {@code auto-continue} allows this run.
   *
   * @param run the run that went on
   * @param kind {@code turn_cap}, {@code call_budget} or {@code time_cap} (V69)
   * @param n how many caps this run has now passed without asking, this one included
   * @param most how many it may
   */
  default void capContinued(OrchestrationRecord run, String kind, int n, int most) {}

  /** A finite allowance increase approved by the project's auto-increase setting. */
  default void capIncreased(OrchestrationRecord run, String kind, int n) {}

  /**
   * The latest milestone {@code run} itself recorded, as its line reads — what a time cap's
   * question names as the last thing it did (V69). The one read here: a question about going on is
   * weighed against it, and the engine holds no other door onto the record.
   *
   * @param run the run
   * @return its line, or empty for none, or a record that cannot be read
   */
  default Optional<String> latestMilestone(OrchestrationRecord run) {
    return Optional.empty();
  }

  @Override
  default void moved(String conversation, List<StatusMoves.Move> moved, List<TodoItem> list) {}
}
