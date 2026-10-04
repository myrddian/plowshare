package io.aeyer.plowshare.server.orchestrations;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The acceptance checker's list of concerns for a run (spec 2026-10-01, the acceptance checker §2):
 * each with the requirement or plan item it is about, why it is a concern, and its state. A seam,
 * so the gates are tested without a database; {@link OrchestrationConcerns} is V77's table. Every
 * write is the harness's: a model never writes a concern, it only says what the harness records.
 */
public interface Concerns {

  /** Raised, with nothing outstanding; checked at the end. */
  String OPEN = "open";

  /** The checker asked the conductor why, and waits on its answer. */
  String ASKED = "asked";

  /** The conductor answered, and the checker gave no verdict on the answer. */
  String ANSWERED = "answered";

  /** The checker accepted the conductor's reason, or the person accepted it. */
  String RESOLVED = "resolved";

  /** Waiting on the person. */
  String FOR_THE_PERSON = "for_the_person";

  /** The end pass checked it: {@link #HOLDS} or {@link #DOES_NOT_HOLD}. */
  String CHECKED = "checked";

  /** The project shows the concern is settled. */
  String HOLDS = "holds";

  /** The project shows it is not: the move into acceptance is refused with the finding. */
  String DOES_NOT_HOLD = "does_not_hold";

  /** The checker cannot check it itself: the person is asked at acceptance. */
  String CANNOT_CHECK = "cannot_check";

  /** Raised at the plan pass. */
  String AT_PLAN = "plan";

  /** Raised at the end pass. */
  String AT_END = "end";

  /** How many WHY questions a concern may put to the conductor before it goes to the person. */
  int MOST_ROUNDS = 2;

  /**
   * One concern, as the store holds it.
   *
   * @param id {@code c1}, {@code c2}, … in the order raised, per run
   * @param about the requirement or plan item it is about
   * @param why why it is a concern
   * @param raised {@link #AT_PLAN} or {@link #AT_END}
   * @param state one of the states above
   * @param rounds how many WHY questions it has put to the conductor
   * @param question the WHY last put to the conductor, or null
   * @param reason the conductor's last answer, or null
   * @param objection why the checker did not accept that answer, or null
   * @param verdict {@link #HOLDS}, {@link #DOES_NOT_HOLD} or {@link #CANNOT_CHECK} once the end
   *     pass looked at it, else null
   * @param finding what the end pass found, or null
   * @param personCheck what the person is asked to do and see for it at acceptance, or null
   * @param personAnswer the person's answer for it, or null
   */
  record Concern(
      String orchestration,
      String id,
      String about,
      String why,
      String raised,
      String state,
      int rounds,
      String question,
      String reason,
      String objection,
      String verdict,
      String finding,
      String personCheck,
      String personAnswer,
      Instant updatedAt) {
    public Concern {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(state, "state");
    }

    /** Waiting on the person at plan time: unresolved after its rounds. */
    public boolean forThePersonAtPlan() {
      return FOR_THE_PERSON.equals(state) && verdict == null;
    }

    /** Waiting on the person at acceptance: the end pass could not check it. */
    public boolean forThePersonAtAcceptance() {
      return FOR_THE_PERSON.equals(state) && CANNOT_CHECK.equals(verdict);
    }

    /**
     * The person's at acceptance, whether still asked or already accepted by them: what the product
     * check shows, so the check they accepted is the same check when the stage is marked done
     * again.
     */
    public boolean thePersonsToCheck() {
      return CANNOT_CHECK.equals(verdict)
          && (FOR_THE_PERSON.equals(state) || RESOLVED.equals(state));
    }

    /** Checked at the end, and the project shows it is not settled. */
    public boolean doesNotHold() {
      return CHECKED.equals(state) && DOES_NOT_HOLD.equals(verdict);
    }
  }

  /**
   * @return the run's concerns, in the order raised
   */
  List<Concern> of(String run);

  /**
   * @return one concern of the run
   */
  default Optional<Concern> find(String run, String id) {
    return of(run).stream().filter(each -> each.id().equals(id)).findFirst();
  }

  /**
   * A new concern.
   *
   * @param question the WHY to put to the conductor, or null for none: then it is {@link #OPEN}
   * @return it, with its id
   */
  Concern raise(String run, String about, String why, String raised, String question);

  /** The conductor answered its WHY; the checker has yet to give its verdict. */
  void answered(String run, String id, String reason);

  /** The checker accepted the conductor's reason. */
  void resolved(String run, String id);

  /** The checker did not accept the reason, and asks again: one more round. */
  void askedAgain(String run, String id, String objection, String question);

  /** The checker did not accept the reason, and its rounds are spent: the person's. */
  void forThePerson(String run, String id, String objection);

  /**
   * The end pass's verdict: {@link #HOLDS} or {@link #DOES_NOT_HOLD} is {@link #CHECKED}; {@link
   * #CANNOT_CHECK} is {@link #FOR_THE_PERSON}, with what the person is to check.
   */
  void checked(String run, String id, String verdict, String finding, String personCheck);

  /**
   * The person answered for it: {@code accepted} resolves it; otherwise their words are a
   * direction, and it is {@link #OPEN} again, to be checked at the end.
   */
  void personAnswered(String run, String id, String answer, boolean accepted);
}
