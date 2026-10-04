package io.aeyer.plowshare.server.agents;

import java.util.function.BooleanSupplier;

/**
 * Who runs now — spec 2026-09-29, the project board and the swarm, §5. Asked once per run for its
 * {@link Turn}; the turn loop asks the turn before every model call and gives the {@link Slot} back
 * when the call returns or throws, so a slot is never held while tools run.
 *
 * <p>{@link #NONE} until wired, and it schedules nothing: every wait is answered at once with
 * {@link Slot#ANY}, which routes the call exactly as an unscheduled run's was always routed.
 */
@FunctionalInterface
public interface Scheduling {

  Scheduling NONE = context -> Turn.ALWAYS;

  /** This run's turn; {@link Turn#ALWAYS} for a run nobody schedules. */
  Turn forRun(RunExtras.Context context);

  @FunctionalInterface
  interface Turn {

    Turn ALWAYS = (specifier, cancelled) -> Slot.ANY;

    /**
     * Waits until this run may call {@code specifier}'s model.
     *
     * @return the slot, or {@code null} when {@code cancelled} answered true while waiting — in
     *     which case nothing was spent and nothing is held
     */
    Slot await(String specifier, BooleanSupplier cancelled);
  }

  interface Slot {

    Slot ANY =
        new Slot() {
          @Override
          public String pool() {
            return null;
          }

          @Override
          public void release() {}
        };

    /**
     * The pool this call must go to with no queue deadline, or {@code null} to route as before,
     * under the pool's own deadline.
     */
    String pool();

    /** Gives the slot back. Called exactly once per slot by the loop; must tolerate twice. */
    void release();
  }
}
