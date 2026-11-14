package io.aeyer.plowshare.server.agents;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The shared turn-scheduling decision. Owners supply validated wait/availability facts; this value
 * neither creates a waiter nor grants access. Actual start claims remain atomic at their owners.
 */
public record TurnEligibility(Reason reason) {
  public enum Reason {
    READY,
    CHILD,
    ANSWER,
    TERMINAL,
    OCCUPIED,
    RESOURCE
  }

  public TurnEligibility {
    Objects.requireNonNull(reason);
  }

  /**
   * Resolve the owning hold first, then occupancy, then resource availability. Lazy facts avoid
   * resource queries while a known hold or active turn already makes a start impossible.
   */
  public static TurnEligibility decide(
      Reason hold, BooleanSupplier occupied, BooleanSupplier resourceBlocked) {
    Objects.requireNonNull(hold);
    Objects.requireNonNull(occupied);
    Objects.requireNonNull(resourceBlocked);
    if (hold == Reason.OCCUPIED || hold == Reason.RESOURCE)
      throw new IllegalArgumentException(
          "Occupancy and resources are observed, not persistent holds");
    if (hold != Reason.READY) return new TurnEligibility(hold);
    if (occupied.getAsBoolean()) return new TurnEligibility(Reason.OCCUPIED);
    return new TurnEligibility(resourceBlocked.getAsBoolean() ? Reason.RESOURCE : Reason.READY);
  }

  public boolean eligible() {
    return reason == Reason.READY;
  }
}
