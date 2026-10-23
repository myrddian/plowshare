package io.aeyer.plowshare.sdk;

import java.time.Duration;
import java.util.Objects;

/** Per-call deadline and optional caller cancellation; neither cancels admitted server work. */
public record RequestOptions(
    Duration timeout, RequestCancellation cancellation, Capacity capacity) {
  /**
   * Which of the dispatch contract's two submission forms a call site chose. A full dispatch window
   * is backpressure rather than a fault, so whether a caller waits it out or sheds the load is a
   * property of the call site, not of the SDK.
   */
  public enum Capacity {
    /**
     * The contract's default. The call does not complete until the request has been transmitted: a
     * full window parks the caller, and a {@code CAPACITY_EXHAUSTED} reply is absorbed as flow
     * control and resubmitted under the same id, so the caller sees a reply or a timeout and never
     * the code. The wait for capacity is charged against nothing and is released by the caller's
     * {@link RequestCancellation} or by the connection closing.
     */
    WAITING,
    /**
     * The explicit fail-fast form, for a caller that would rather shed load than be held. A full
     * window reports {@link Plowshare.Delivery#NOT_SUBMITTED} in the same call, and a {@code
     * CAPACITY_EXHAUSTED} reply is returned to the caller as an outcome: no backoff, no
     * resubmission.
     */
    NON_BLOCKING
  }

  public RequestOptions {
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(capacity, "capacity");
    if (timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("A positive request timeout is required");
    timeout.toNanos();
  }

  /** The waiting form, which is the contract's default, with a caller-owned cancellation signal. */
  public RequestOptions(Duration timeout, RequestCancellation cancellation) {
    this(timeout, cancellation, Capacity.WAITING);
  }

  public static RequestOptions defaults(Duration timeout) {
    return new RequestOptions(timeout, null, Capacity.WAITING);
  }

  public static RequestOptions nonBlocking(Duration timeout) {
    return new RequestOptions(timeout, null, Capacity.NON_BLOCKING);
  }

  /** Keeps this call's deadline and cancellation and sheds load instead of waiting for capacity. */
  public RequestOptions withoutCapacityWait() {
    return new RequestOptions(timeout, cancellation, Capacity.NON_BLOCKING);
  }
}
