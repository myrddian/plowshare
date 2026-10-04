package io.aeyer.plowshare.server.orchestrations;

/**
 * Where a run's failed check — or failed acceptance commands — is counted, and the person asked
 * once it has failed its project's {@code caps: failed-checks} times (V69, {@link
 * Orchestrations#checkFailures}). A seam, so {@link StageChecks} and {@link AcceptanceGate} are
 * tested without the engine; {@link #NONE} counts nothing and asks nobody, which refuses every
 * failure as it always was refused.
 */
@FunctionalInterface
public interface CheckFailures {

  /** Counts nothing and asks nobody. */
  CheckFailures NONE = (run, what, output) -> false;

  /**
   * One more failure.
   *
   * @param run the run whose check failed
   * @param what what failed, as the person's question names it — {@code check `pytest -q`}
   * @param output the end of its output, some twenty lines
   * @return whether the person was asked, in which case the conductor's turn ends: the run is
   *     asking, and their answer is its next message
   */
  boolean failed(String run, String what, String output);
}
