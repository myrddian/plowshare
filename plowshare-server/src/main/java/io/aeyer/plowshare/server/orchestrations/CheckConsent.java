package io.aeyer.plowshare.server.orchestrations;

import java.util.List;
import java.util.Optional;

/**
 * Asks a person to allow a run's check: one approval, for the life of the run. The same door asks
 * about a run's acceptance commands (spec 2026-09-29 §1b), with their own reason — once for the
 * whole set (V67) — so both reach the person through the one approval path the check already
 * proved; and it records what the command judge allowed unasked, and finds a standing project
 * approval that already covers a check.
 */
@FunctionalInterface
public interface CheckConsent {

  /** What the person is told a run's check's approval is for. */
  String CHECK_WHY =
      "this run's check: the harness runs it whenever a checked stage is marked" + " done";

  /**
   * @param approval the approval's id; @param question what the person is shown
   */
  record Asked(String approval, String question) {}

  /**
   * Whether anyone could be asked for this run at all — false on {@link #ask}'s own empty case, the
   * project having no id on this server. Asked before {@code approval.pre} (spec
   * 2026-09-28-hooks-reach-the-log §3, ruling F5): no hook fires for a question nobody could be
   * asked, as {@code RunTool} asks it only once a project id is in hand.
   *
   * @param run the run the question would be for
   * @return true unless {@link #ask} would come back empty for this run
   */
  default boolean canAsk(OrchestrationRecord run) {
    return true;
  }

  /**
   * @return empty when nobody can be asked — the project this run answers from has no id on this
   *     server, on {@code RunTool}'s own "nowhere to put a question to a person" case
   */
  Optional<Asked> ask(OrchestrationRecord run, List<String> argv, String side, String cwd);

  /**
   * {@link #ask} for another reason than the run's check's plain one — an acceptance command, or a
   * check with the command judge's words beside it.
   *
   * @param run the run the command is for
   * @param argv the command
   * @param side the side it runs on
   * @param cwd the directory it runs in
   * @param why what the person is told the command is for
   * @return as {@link #ask(OrchestrationRecord, List, String, String)}
   */
  default Optional<Asked> ask(
      OrchestrationRecord run, List<String> argv, String side, String cwd, String why) {
    return ask(run, argv, side, cwd);
  }

  /**
   * Ask the person about a whole acceptance set at once: ONE approval every command of it runs
   * under (V67).
   *
   * @param run the run the set is for
   * @param commands each command's argv, in order
   * @param side the side the set runs on
   * @param cwd the directory it runs in
   * @param why what the person is shown: every command, with what it is given and must show
   * @param judged why the command judge put it to the person, or null
   * @return as {@link #ask(OrchestrationRecord, List, String, String)}
   */
  default Optional<Asked> askSet(
      OrchestrationRecord run,
      List<List<String>> commands,
      String side,
      String cwd,
      String why,
      String judged) {
    throw new UnsupportedOperationException("this consent cannot ask about a set");
  }

  /**
   * Record that the command judge allowed a command or a set, which nobody is asked about: an
   * approval already {@code allowed}, answered by {@link
   * io.aeyer.plowshare.server.approvals.RunApproval#JUDGE}, told to the record as an answer.
   *
   * @param argv the command, or the empty list for a set
   * @param commands the set's commands, or null for one command
   * @param why what it is for, as a person would have been told
   * @param judged the judge's one line
   * @return the approval's id; empty when no approval can be written for this run's project
   */
  default Optional<String> allowedByJudge(
      OrchestrationRecord run,
      List<String> argv,
      List<List<String>> commands,
      String side,
      String cwd,
      String why,
      String judged) {
    throw new UnsupportedOperationException("this consent cannot record the judge's answer");
  }

  /**
   * A standing {@code project} approval the person gave that covers {@code argv} on {@code side},
   * if there is one — the run tool's own rule ({@code RunApprovalStore.covers}).
   *
   * @return its id, or empty
   */
  default Optional<String> standing(OrchestrationRecord run, List<String> argv, String side) {
    return Optional.empty();
  }
}
