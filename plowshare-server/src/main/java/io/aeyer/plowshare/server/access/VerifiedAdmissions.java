package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.OperationAuthority.Admission;

/**
 * Remembers, for a short bounded time, that an operation admission passed its full live authority
 * walk, so that the same operation rechecking the same admission again within that time need not
 * walk it again.
 *
 * <p>An agent tool call rechecks its admission several times on purpose (before the step, after
 * pre-tool hooks, around target checks and after the tool returns). Each recheck is kept; this
 * contract only makes a repeat cheap. The after-tool recheck is a disclosure gate: it withholds the
 * result from the model and cannot undo writes, which are fenced in their own transactions (see
 * docs/operation-write-fences.md). Reuse never grants anything: an admission is answered from
 * memory only after an equal admission passed the walk, and a walk that refuses throws as it always
 * did and leaves nothing behind.
 *
 * <p><b>Staleness.</b> An implementation that answers from memory may let an already-verified
 * admission keep running after a revocation, membership, role, manifest or record change for at
 * most its documented bound. Callers that cannot accept that lag must not use one.
 */
public interface VerifiedAdmissions {
  /**
   * Runs {@code walk}, the full live check of {@code admission}, unless an equal admission passed
   * it recently enough under the current authority to be reused.
   *
   * @param admission the admission being rechecked; equality over every field is the key
   * @param walk the full check; it throws to refuse and returns normally to admit
   * @throws RuntimeException whatever {@code walk} throws, unchanged
   */
  void require(Admission admission, Runnable walk);

  /** Never remembers: every recheck walks. The default for fixtures and unconfigured servers. */
  static VerifiedAdmissions none() {
    return (admission, walk) -> walk.run();
  }
}
