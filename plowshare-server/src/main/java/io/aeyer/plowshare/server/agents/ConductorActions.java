package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.hooks.Gate;
import java.util.List;
import java.util.Optional;

/**
 * The database half of a conductor's two tools, behind the seam {@link ConductorTools} calls before
 * it ever touches {@link TurnEnd}.
 *
 * <p>Neither method throws for a caller's mistake — the empty {@link Optional} is the success case,
 * and a present one is the sentence the model reads back, on {@link AgentTool#run}'s own contract
 * extended one layer down. {@link ConductorTools} trips {@link TurnEnd} only after the call here
 * returns empty, which is the whole of the "write to the DB first" rule: a refused action leaves
 * nothing for a later batch entry to see and nothing for {@link JobRuntime} to end the turn over.
 */
public interface ConductorActions {

  /**
   * Records the question and moves the run to asking.
   *
   * @param orchestration the run's own orchestration id
   * @param question what the model could not go on without knowing
   * @return empty on success; otherwise the sentence the model reads
   */
  Optional<String> ask(String orchestration, String question);

  /**
   * {@link #ask(String, String)} for a question with options (spec 2026-09-29-orchestration-studio
   * §2): {@code question} is its rendered text, which the run is spoken and recorded by, and {@code
   * structure} what a client draws. An implementation that cannot keep a structure refuses rather
   * than dropping the options.
   *
   * @param structure {@code StructuredQuestions.structure}, or null for a plain question
   */
  default Optional<String> ask(
      String orchestration,
      String question,
      io.aeyer.plowshare.protocol.Orchestration.Structure structure) {
    return structure == null
        ? ask(orchestration, question)
        : Optional.of("this server cannot put a question with options; ask it in words");
  }

  /**
   * Records the result and finishes the run if every stage is done.
   *
   * @param orchestration the run's own orchestration id
   * @param result what the conductor is finishing with
   * @return empty on success; otherwise why not
   */
  Optional<String> finish(String orchestration, String result);

  /** What setting a run's check came to. */
  sealed interface CheckSet {
    record Refused(String why) implements CheckSet {}

    /**
     * Set, with no one asked.
     *
     * @param how why no one was asked, when it was not an open side: the consent that already
     *     covered it, or the command judge's words (V67); null for an open side
     */
    record Set(String how) implements CheckSet {
      /** Set on an open side's say-so. */
      public Set() {
        this(null);
      }
    }

    record Asking(String question) implements CheckSet {}
  }

  /**
   * Record the run's check once. {@code mode} is the side's environment mode now: {@code open}
   * records it without asking; anything else asks a person, and the answer is {@link
   * CheckSet.Asking}.
   *
   * @param orchestration the run's own orchestration id
   * @param argv the program and its arguments
   * @param side {@code local} or {@code server} — the side the command runs on
   * @param cwd where the command runs
   * @param mode the side's environment mode when this was called
   * @return {@link CheckSet.Refused} if this run has no checked stage, already has a check, or has
   *     already passed one; {@link CheckSet.Set} when {@code mode} is {@code open}; {@link
   *     CheckSet.Asking} when a person was asked
   */
  CheckSet setCheck(String orchestration, List<String> argv, String side, String cwd, String mode);

  /**
   * {@link #setCheck(String, List, String, String, String)}, saying whether a hook on the run tool
   * asked about the command. A hook that asks puts the check to the person — never covered by
   * consent given before, and never shown to the command judge (V67).
   *
   * @param hookAsked whether a hook asked; {@code mode} is then not {@code open}
   */
  default CheckSet setCheck(
      String orchestration,
      List<String> argv,
      String side,
      String cwd,
      String mode,
      boolean hookAsked) {
    return setCheck(orchestration, argv, side, cwd, mode);
  }

  /**
   * {@code approval.pre} for the question {@link #setCheck} is about to put to a person, bound by
   * the tool to where the command would run and whose run it is (spec
   * 2026-09-28-hooks-reach-the-log §3). It records what it decided itself.
   */
  @FunctionalInterface
  interface BeforeAsking {
    BeforeAsking NONE = reason -> Gate.NOTHING;

    /**
     * @param reason what the person would be told the approval is for
     */
    Gate before(String reason);
  }

  /**
   * {@link #setCheck(String, List, String, String, String)}, asking {@code before} once a person is
   * about to be asked and before the question exists: a denial refuses the check with nothing set
   * or cleared, and a note joins the question.
   */
  default CheckSet setCheck(
      String orchestration,
      List<String> argv,
      String side,
      String cwd,
      String mode,
      BeforeAsking before) {
    return setCheck(orchestration, argv, side, cwd, mode);
  }

  /**
   * Both of the above: whether a hook on the run tool asked (V67), and {@code approval.pre} asked
   * only once a person is about to be — not when consent already given covers the check, not when
   * the command judge allows it, and not on an open side.
   *
   * @param hookAsked whether a hook asked; {@code mode} is then not {@code open}
   * @param before {@code approval.pre}, asked before the question exists
   */
  default CheckSet setCheck(
      String orchestration,
      List<String> argv,
      String side,
      String cwd,
      String mode,
      boolean hookAsked,
      BeforeAsking before) {
    return setCheck(orchestration, argv, side, cwd, mode, hookAsked);
  }

  /**
   * What answering the acceptance checker came to (spec 2026-10-01 §3).
   *
   * @param text what the conductor reads back: the checker's verdict, from the harness's store
   * @param personAsked whether the person was asked about the concerns the checker could not
   *     resolve, in which case the conductor's turn ends
   */
  record CheckerAnswered(String text, boolean personAsked) {}

  /**
   * The conductor's answer to one of its acceptance checker's WHY questions: recorded, shown to the
   * checker — which does not have to accept it — and settled as its verdict says.
   *
   * @param orchestration the run's own orchestration id
   * @param concern the concern's id, as the question named it
   * @param reason the conductor's answer
   * @param home the run's tier, which the checker reads
   * @return what came of it; an implementation without a checker says so
   */
  default CheckerAnswered checkerAnswer(
      String orchestration, String concern, String reason, Home home) {
    return new CheckerAnswered(
        "This server runs no acceptance checker; nothing was recorded.", false);
  }
}
