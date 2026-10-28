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
