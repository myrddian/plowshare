package io.aeyer.plowshare.server.orchestrations;

/**
 * Where the engine hands a question or an ending that has been written and now has to reach the
 * caller. Implemented by {@code Delivery}; the engine only says that something is ready.
 */
public interface DeliveryPort {

  /** A question was written and the conductor's turn has ended on it. */
  void questionAsked(OrchestrationRecord run, OrchestrationMessage question);

  /** A run reached a terminal state, which {@code run} carries. */
  void runEnded(OrchestrationRecord run);

  /**
   * The person's own copy of a cap question — spec 2026-09-29 §2: the parent model and the person
   * are asked at the same moment, and the first answer settles it. Measured 2026-09-28 23:51,
   * {@code orc_31893856D8F462A1}: a phase's cap question reached only its parent conductor, which
   * ended its turn in prose over it, and each waited on the other for five hours with the person
   * never told. Nothing by default.
   *
   * @param run the run asking, as it now stands
   * @param question its cap question
   */
  default void toThePerson(OrchestrationRecord run, OrchestrationMessage question) {}

  /**
   * A run's state has just changed, and every question of it but the one it is asking now — if it
   * is asking — is settled: answered by anyone, or left behind by a run that stopped asking or
   * ended. The person's inbox notices about them leave it (V68). Nothing by default.
   *
   * @param run the run as it now stands, freshly read
   */
  default void questionsSettled(OrchestrationRecord run) {}
}
