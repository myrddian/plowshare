package io.aeyer.plowshare.server.reads;

/**
 * Process-wide execution budgets for read work, independent of socket or feed identity. Admission
 * grants capacity only; the owning service still applies current policy and disclosure fences.
 * Acquire before borrowing a database connection. A lease may be closed on another completion
 * thread and must be retained until the physical work finishes, even if its client disconnects.
 */
public interface ReadAdmission {
  enum Work {
    INTERACTIVE,
    REPORT,
    REPORT_REFRESH,
    AUTHORITY,
    /**
     * The cache's own slots into PostgreSQL, used to fetch a shared value such as an account's log
     * deny set or the authority snapshot. These seats belong to the cache, not to the requester:
     * the cache merges concurrent misses for one key before admission, and a requester on a miss
     * waits for a slot instead of being refused. The admission principal is the cache's name, so
     * each cache owns {@code fetches} slots and {@code fetches-total} bounds all of them; it is
     * never a per-account budget.
     */
    FETCH,
    MAINTENANCE,
    OBSERVER,
    SOCKET,
    SUBSCRIPTION
  }

  interface Lease extends AutoCloseable {
    @Override
    void close();
  }

  Lease admit(String principal, Work work);

  default <T> T read(String principal, Work work, java.util.function.Supplier<T> action) {
    var lease = admit(principal, work);
    try (lease) {
      return action.get();
    }
  }

  /** Standalone fixtures retain their original behavior without a production composition root. */
  static ReadAdmission direct() {
    return (principal, work) -> () -> {};
  }
}
