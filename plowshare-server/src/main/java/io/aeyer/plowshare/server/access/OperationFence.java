package io.aeyer.plowshare.server.access;

/**
 * A live authority check that a mutating write runs inside its own transaction, immediately before
 * its first durable write and after any row lock that transaction already takes.
 *
 * <p>The agent runtime builds one per run from the run's admission and retained operation, so a
 * grant revoked after the tool call began but before the write commits refuses the write and the
 * whole transaction rolls back. {@code JobRuntime}'s before-tool and after-tool checks run outside
 * the transaction; this fence is the only one that can stop a write that is already in flight.
 * Inside a write transaction {@code BoundedVerifiedAdmissions} bypasses its reuse window and the
 * scoped grant cache reads SQL, so the fence always walks current authority.
 *
 * <p>{@link #require()} signals refusal by throwing; it must not swallow a failure, return a
 * status, or perform writes of its own. Callers without a run operation — socket handlers, system
 * work and administration — pass {@link #none()} and keep their existing authorization unchanged.
 * See {@code docs/operation-write-fences.md} for which tool writes are fenced.
 */
@FunctionalInterface
public interface OperationFence {
  /** Throws when the operation may no longer write; otherwise returns without effect. */
  void require();

  /** The fence for callers whose authority is checked by their own boundary, not by a run. */
  static OperationFence none() {
    return () -> {};
  }
}
