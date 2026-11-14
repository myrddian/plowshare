package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.Optional;

/**
 * Reads the tenant-independent facts of log visibility, one log tree per call.
 *
 * <p>Each method is one indexed statement in the caller's current transaction context and returns
 * rows as of that statement. Neither performs authorization: the facts are shared across accounts
 * and {@link LogReadability} decides access from them. Results are never truncated. Database
 * failures propagate.
 */
public interface LogTreeFactRepository {
  /**
   * The roots above {@code log} under the ancestry edges of {@code information_log_readable},
   * sorted, exactly as the V257 item triggers address the log's tree ({@code
   * snapshot_cache_log_roots}). Empty when the walk visits more than 256 logs or reaches a cycle
   * with no root, which the triggers address only by the domain fallback item.
   */
  Optional<List<String>> roots(String log);

  /** The tree under {@code root}, or {@link LogTreeFact.Oversized} past the tree's bounds. */
  LogTreeFact tree(String root);
}
