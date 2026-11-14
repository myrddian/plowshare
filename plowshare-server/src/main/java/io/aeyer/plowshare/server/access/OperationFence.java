package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;

/**
 * A live authority check that a mutating write runs inside each of its own transactions, after any
 * row lock that transaction takes and before it writes, and before any receipt replay. A gated
 * write also runs it before firing hooks or opening a pipeline log outside a transaction.
 *
 * <p>The agent runtime builds one per run from the run's admission and retained operation, so a
 * grant revoked after the tool call began but before the write commits refuses the write and the
 * whole transaction rolls back. {@code JobRuntime}'s before-tool and after-tool checks run outside
 * the transaction; this fence is the only one that can stop a write that is already in flight.
 * Inside a write transaction {@code BoundedVerifiedAdmissions} bypasses its reuse window and the
 * scoped grant cache reads SQL, so the fence always walks current authority.
 *
 * <p>{@link #require()} signals refusal by throwing; it must not swallow a failure, return a
 * status, or perform writes of its own. A run's fence throws {@link Withdrawn}, which is
 * deliberately not a {@code CallerFault}: tools turn caller faults into "refused" results, and a
 * fence refusal must instead reach {@code JobRuntime}, which tells the model that the run's
 * authority changed and that any change the tool made may already have happened. Callers without a
 * run operation — socket handlers, system work and administration — pass {@link #none()} and keep
 * their existing authorization unchanged. See {@code docs/operation-write-fences.md} for which tool
 * writes are fenced.
 */
@FunctionalInterface
public interface OperationFence {
  /** Throws when the operation may no longer write; otherwise returns without effect. */
  void require();

  /** The fence for callers whose authority is checked by their own boundary, not by a run. */
  static OperationFence none() {
    return () -> {};
  }

  /**
   * A run's fence refused because the run's admission or retained operation lost authority. The
   * refused transaction rolls back; earlier committed units (an idempotency receipt, a pipeline
   * log) remain, which is why the model is not told that nothing happened.
   */
  final class Withdrawn extends RuntimeException {
    public Withdrawn(CallerFault cause) {
      super(Objects.requireNonNull(cause).getMessage(), cause);
    }
  }
}
