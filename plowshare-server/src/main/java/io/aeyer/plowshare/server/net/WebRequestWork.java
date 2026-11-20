package io.aeyer.plowshare.server.net;

import io.aeyer.plowshare.server.hooks.Deadline;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import okhttp3.Call;

/** The remaining work owned by a managed web operation, including provider fallback. */
public record WebRequestWork(
    Deadline deadline, BooleanSupplier cancelled, Predicate<String> redirectAllowed) {
  public WebRequestWork(Deadline deadline, BooleanSupplier cancelled) {
    this(deadline, cancelled, ignored -> false);
  }

  public WebRequestWork {
    Objects.requireNonNull(deadline);
    Objects.requireNonNull(cancelled);
    Objects.requireNonNull(redirectAllowed);
  }

  /** Local provider restrictions run before spending time on the requesting run's review. */
  public WebRequestWork restrictRedirects(Predicate<String> restriction) {
    return new WebRequestWork(deadline, cancelled, restriction.and(redirectAllowed));
  }

  public boolean permitsRedirect(String destination) {
    return !stopped() && redirectAllowed.test(destination) && !stopped();
  }

  public boolean stopped() {
    return Thread.currentThread().isInterrupted()
        || cancelled.getAsBoolean()
        || deadline.remaining().isZero();
  }

  public long capNanos(long providerRemaining) {
    return stopped() ? 0 : Math.min(providerRemaining, deadline.remaining().toNanos());
  }

  @FunctionalInterface
  public interface Watch extends AutoCloseable {
    @Override
    void close();
  }

  /** Request physical cancellation for an in-flight call, including response body reads. */
  public static Watch watch(WebRequestWork work, Call call) {
    if (work == null) return () -> {};
    if (work.stopped()) {
      call.cancel();
      return () -> {};
    }
    Thread owner = Thread.currentThread();
    Thread watcher =
        Thread.startVirtualThread(
            () -> {
              try {
                while (!work.stopped() && !owner.isInterrupted())
                  Thread.sleep(Duration.ofMillis(20));
                if (!Thread.currentThread().isInterrupted()) call.cancel();
              } catch (InterruptedException closed) {
                // Closing the response ended physical ownership of this call.
                Thread.currentThread().interrupt();
              }
            });
    return watcher::interrupt;
  }
}
