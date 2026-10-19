package io.aeyer.plowshare.server.allowances;

import io.aeyer.plowshare.server.agents.Budget;
import java.util.Optional;

/**
 * Owns shared call counters and immutable claims; callers retain authority and lifecycle checks.
 */
public interface AllowanceRepository {
  /** Inserts a new selected source; an existing identity is never reset or reconfigured. */
  void retain(AllowanceSnapshot selected);

  Optional<AllowanceSnapshot> find(AllowanceId id);

  /** Atomically spends one call, preserving the supplied finite closing reserve. */
  default Optional<Budget.Claim> claim(AllowanceId id, int reserve) {
    return claim(id, reserve, null);
  }

  /** Optional expected usage preserves preparation's single-winner stage claim. */
  Optional<Budget.Claim> claim(AllowanceId id, int reserve, Integer expectedSpent);

  /** Admits a live-object claim once, or validates its previously committed reservation. */
  void accept(Budget.Claim claim);

  /**
   * Authorized ceiling change, preserving usage. Disabled/native sources stay disabled; an
   * explicit-only document's initially unallocated work retains its existing allocation control.
   */
  void ceiling(AllowanceId id, Integer total);

  /** Finite automatic raise, conditional on the exhausted snapshot; never resets spent. */
  void increase(AllowanceId id, int total, int observedSpent);

  /** Existing message admission adds a finite chunk once in its owning transaction. */
  void add(AllowanceId id, int calls);

  /** Retain a newly selected source and bind this same live object. */
  default Budget bind(Budget budget) {
    if (budget.durable()) return attach(budget);
    retain(budget.snapshot());
    return wire(budget);
  }

  /** Attach an existing source without writing; safe for history/status read transactions. */
  default Budget attach(Budget budget) {
    AllowanceId id = new AllowanceId(budget.identity());
    find(id).orElseThrow(() -> new IllegalStateException("Missing retained allowance"));
    // A board wake/preparation binding also owns lifecycle and scheduling fences. Attaching a
    // history row must not replace that callback with an unrestricted source claim.
    if (budget.durable()) {
      return budget;
    }
    return wire(budget);
  }

  private Budget wire(Budget budget) {
    AllowanceId id = new AllowanceId(budget.identity());
    budget.bindAllowance(
        () -> claim(id, 0),
        total -> ceiling(id, total),
        () -> find(id).orElseThrow(() -> new IllegalStateException("Missing allowance")));
    return budget;
  }

  default Budget restore(AllowanceId id) {
    AllowanceSnapshot snapshot =
        find(id).orElseThrow(() -> new IllegalStateException("Missing allowance"));
    return wire(Budget.restored(id.value(), snapshot.ceiling(), snapshot.spent()));
  }
}
