package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Invalidation;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Pure state transitions over {@link Memory}. Ported from Excalibur's {@code archive/lifecycle.py}.
 *
 * <p><b>Nothing here deletes anything.</b> Every "removal" is a demotion out of the active index,
 * so a wrong eviction costs a slower recall rather than a lost memory — which is what makes it safe
 * to keep the working set aggressively lean. A transition that dropped a body or a row would turn a
 * cheap mistake into an unrecoverable one.
 *
 * <p>No database and no Spring: each method takes a memory and returns a new one. Persisting the
 * result is the caller's job, which is what lets the store decide whether a transition is one row's
 * update or part of a larger transaction.
 */
public final class Lifecycle {

  /**
   * The stamp Excalibur writes into a merge seam: ISO-8601 UTC, seconds precision or finer, with
   * {@code Z} rather than {@code +00:00}. Python reaches that spelling by {@code
   * astimezone(UTC).isoformat().replace(...)}; an {@link Instant} is already UTC and {@code
   * ISO_INSTANT} already writes {@code Z}, so the normalisation has nowhere to go wrong here.
   */
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_INSTANT;

  private Lifecycle() {}

  /**
   * Mark {@code old} replaced, and point it at what replaced it.
   *
   * <p>The body is untouched — only the state and the backward pointer change, so the chain stays
   * resolvable forever and the old text survives as the evidence of what was once believed.
   *
   * <p>Deliberately does not touch {@code supersedes}: that is the forward link, and it belongs to
   * the <em>replacement</em>, set through the canonical constructor when the new memory is formed.
   * Writing it here would have the old record claim it replaced its own successor, and a walker
   * following the chain would loop between the two.
   */
  public static Memory supersede(Memory old, String newId) {
    return old.withState(MemoryState.SUPERSEDED).withSupersededBy(newId);
  }

  /**
   * Append to a record the scribe judged to be the same memory.
   *
   * <p>Appends rather than rewrites: no prose is lost, and no small model is asked to reconcile two
   * texts into one. Identity, summary and scope are unchanged — the scribe said this <em>is</em>
   * that memory, so its index entry still applies.
   *
   * <p>The dated line in the seam is load-bearing, not decoration. Without it the merged body reads
   * as a single voice and nobody can tell afterwards which half arrived when, or from whom — which
   * is exactly what a reader needs when only one half turns out to be wrong.
   *
   * <p>The target is trimmed on the right and the addition on both sides, so a body that already
   * ends in a newline does not widen the gap a little further on every merge.
   */
  public static Memory mergeBody(Memory target, String addition, Instant at, String by) {
    String separator = "---\n*Merged " + STAMP.format(at) + " by " + by + "*";
    return target.withBody(
        target.body().stripTrailing() + "\n\n" + separator + "\n\n" + addition.strip());
  }

  /**
   * Kill a memory, keeping the corpse.
   *
   * <p>The reason is load-bearing: without it, an agent that rediscovers the old fact from stale
   * code will cheerfully write it back in, and the archive relearns its own mistakes forever. The
   * body stays for the same reason — a tombstone that lost its body loses the thing that lets
   * someone later judge whether the invalidation was right.
   *
   * <p>Sets the state <em>and</em> the tombstone. {@link Memory#withInvalidation} deliberately
   * leaves the state alone (it is an accessor, not a policy), so invalidating through it alone
   * would leave a memory carrying a reason it stopped being true while still reading {@code active}
   * — and recall would go on returning it.
   */
  public static Memory invalidate(Memory memory, Instant at, String by, String reason) {
    return memory
        .withState(MemoryState.INVALIDATED)
        .withInvalidation(new Invalidation(at, by, reason));
  }

  /**
   * Drop a memory out of the working set for disuse.
   *
   * <p>State only. A cold memory is still true, merely unused: it keeps its body, its use count and
   * its reachability by id and by search. Zeroing the counter here would make a memory that fell
   * out of the working set once indistinguishable from one that was never useful at all, and decay
   * would then have no history to reconsider it on.
   */
  public static Memory demote(Memory memory) {
    return memory.withState(MemoryState.COLD);
  }

  /**
   * Count a recall and stamp when it happened.
   *
   * <p>Increments; never assigns. The count and the timestamp are the only evidence decay has that
   * a memory is still earning its place in the working set, so a use that overwrote the count
   * instead of adding to it would make every memory look equally cold.
   */
  public static Memory recordUse(Memory memory, Instant at) {
    return memory.withUses(memory.uses() + 1).withLastUsed(at);
  }
}
