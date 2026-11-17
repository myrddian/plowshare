package io.aeyer.plowshare.server.reads;

import java.util.Objects;

/**
 * A read failed for a reason other than capacity backpressure. The reason never proves an enclosing
 * mutation was unstarted, permits stale disclosure or authorizes replay of a larger operation.
 * Physical cancellation and cleanup remain owned by the SQL, cache or session component.
 */
public class ReadFailedException extends RuntimeException {
  public enum Reason {
    DEADLINE_EXCEEDED,
    INVALIDATED,
    INTERRUPTED,
    CLOSED,
    QUERY_CANCELLED
  }

  private final Reason reason;

  protected ReadFailedException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = Objects.requireNonNull(reason);
  }

  public Reason reason() {
    return reason;
  }

  public static ReadFailedException deadlineExceeded() {
    return new ReadFailedException(
        Reason.DEADLINE_EXCEEDED, "Read deadline expired before a result was available", null);
  }

  public static ReadFailedException invalidated() {
    return new ReadFailedException(
        Reason.INVALIDATED, "Read result changed before it could be safely returned", null);
  }

  public static ReadFailedException interrupted() {
    return interrupted(null);
  }

  public static ReadFailedException interrupted(Throwable cause) {
    return new ReadFailedException(
        Reason.INTERRUPTED, "Read was interrupted before a result was available", cause);
  }

  public static ReadFailedException closed() {
    return new ReadFailedException(Reason.CLOSED, "Read service is closed", null);
  }

  public static ReadFailedException queryCancelled(Throwable cause) {
    return new ReadFailedException(
        Reason.QUERY_CANCELLED,
        "Read query was cancelled before a result was available; no read result was returned",
        cause);
  }
}
