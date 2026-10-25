package io.aeyer.plowshare.server.applications;

import java.util.Set;

/**
 * Which contained source areas a creation is currently writing.
 *
 * <p>It exists for one race, and the race is worse than the leak it closes. A creation stages its
 * source <em>before</em> any durable row exists — that ordering is the whole of refusal totality,
 * because a refused manifest must leave no project row behind — so between the first byte and the
 * commit there is a directory under the containment root that no committed record accounts for.
 * That is exactly the shape {@link ContainedSourceCollector} collects. A sweep arriving in that
 * window would delete the source of a creation that is about to succeed, and the caller would get a
 * receipt for an Application whose files are gone.
 *
 * <p>A claim is therefore held from before the first byte until the unit of work has finished, and
 * released in a {@code finally} so a refusal cannot leave a project permanently exempt.
 *
 * <p><strong>Exact in one process, and only in one process.</strong> This is deliberately not
 * durable: a claim describes work a live thread is doing, so a claim that outlived its process
 * would be a claim nothing will ever release — a permanent leak dressed as safety. Across processes
 * the bound is the sweep's configured grace on the directory's own age. The remaining gap, and what
 * would close it, is recorded in ADR 0042.
 */
public interface ContainedStaging {
  /** Claims a project's source area. Called before the first byte is written. */
  void claim(String project);

  /** Releases the claim. Called in a {@code finally}, so a refusal releases it too. */
  void release(String project);

  /** Whether a creation in this process is writing this project's source area right now. */
  boolean staging(String project);

  /** The live claims, for assertions. Never a basis for a collection decision. */
  Set<String> claimed();
}
