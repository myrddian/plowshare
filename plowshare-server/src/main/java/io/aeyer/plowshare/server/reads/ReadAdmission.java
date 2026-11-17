package io.aeyer.plowshare.server.reads;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

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

  /**
   * Bounded admission before an interactive read handler starts. Implementations may wait among
   * eligible principals, sharing the earlier enclosing deadline. No handler work is replayed. Its
   * caller owns completion; {@link #readAsync} can retain the lease until a source settles.
   */
  default Lease awaitInteractive(String principal) {
    ReadDeadline.check();
    return admit(principal, Work.INTERACTIVE);
  }

  /** Finite queue wait only; this is not a handler, provider or workflow lifetime. */
  default Duration interactiveWaitBudget() {
    return Duration.ofSeconds(2);
  }

  /**
   * Starts one side-effect-free handler after bounded admission. The admission wait installs no
   * handler deadline: SQL transactions, providers and asynchronous stages retain their owning
   * budgets. The lease stays occupied until the source stage settles, even if the caller cancels
   * its returned future. Cancellation never proves physical work stopped.
   */
  default <T> CompletionStage<T> readAsync(String principal, Supplier<CompletionStage<T>> action) {
    Objects.requireNonNull(action);
    var lease = awaitInteractive(principal);
    return readAsync(lease, action);
  }

  /** For an entry point that must classify acquisition separately from any handler failure. */
  default <T> CompletionStage<T> readAsync(Lease lease, Supplier<CompletionStage<T>> action) {
    Objects.requireNonNull(lease);
    Objects.requireNonNull(action);
    try {
      ReadDeadline.check();
      var source = Objects.requireNonNull(action.get());
      var reply = new CompletableFuture<T>();
      // This observer is deliberately not the returned future. Cancelling a dependent future can
      // suppress its completion action and would leak the physical lease.
      source.whenComplete(
          (value, failure) -> {
            Throwable settledFailure = failure;
            try {
              lease.close();
            } catch (RuntimeException | Error cleanupFailure) {
              if (settledFailure == null) settledFailure = cleanupFailure;
              else if (settledFailure != cleanupFailure)
                settledFailure.addSuppressed(cleanupFailure);
            }
            if (settledFailure != null) reply.completeExceptionally(settledFailure);
            else reply.complete(value);
          });
      return reply;
    } catch (RuntimeException | Error failure) {
      try {
        lease.close();
      } catch (RuntimeException | Error cleanupFailure) {
        if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  default <T> T read(String principal, Work work, Supplier<T> action) {
    var lease = admit(principal, work);
    try (lease) {
      return action.get();
    }
  }

  /**
   * Runs a repeatable pure authoritative database read, without mutations or external effects.
   * Implementations may wait for its AUTHORITY seat only before the callback starts and without
   * holding a caller transaction/connection. This never replays a callback or renews its budget.
   */
  default <T> T readAuthoritative(String principal, Supplier<T> action) {
    return read(principal, Work.AUTHORITY, action);
  }

  /** Standalone fixtures retain their original behavior without a production composition root. */
  static ReadAdmission direct() {
    return (principal, work) -> () -> {};
  }
}
