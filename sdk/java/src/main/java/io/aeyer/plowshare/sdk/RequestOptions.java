package io.aeyer.plowshare.sdk;

import java.time.Duration;
import java.util.Objects;

/** Per-call deadline and optional caller cancellation; neither cancels admitted server work. */
public record RequestOptions(Duration timeout, RequestCancellation cancellation) {
  public RequestOptions {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("A positive request timeout is required");
    timeout.toNanos();
  }

  public static RequestOptions defaults(Duration timeout) {
    return new RequestOptions(timeout, null);
  }
}
