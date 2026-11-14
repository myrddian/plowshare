package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.web.socket.WebSocketSession;

/**
 * The account-session fence for one socket request (or one socket's delivery), answering from the
 * last success for at most {@code bound}.
 *
 * <p>One request asks the same question several times: at execution, after the handler, before
 * disclosure and in each handler wait loop. The session is the same one every time, so only the
 * first answer inside the bound reaches {@link SocketAuthorization#current}. The bound is {@code
 * plowshare.projection-cache.notified.staleness}, the lag every other reused authority decision
 * accepts, so a revocation stops delivery within it. The scheduled sweep and every new frame still
 * check on their own.
 *
 * <p>Only a success is remembered. A refusal throws, and the next call asks again rather than
 * replaying it; {@link SocketAuthorization#current} has already closed the socket by then. A zero
 * {@code bound} never reuses, which is how disabled notifications keep exact checks. Calls may
 * arrive from several threads; a race at most repeats a check.
 */
final class RequestSessionValidity implements Runnable {
  private final SocketAuthorization authorization;
  private final WebSocketSession socket;
  private final LongSupplier nanos;
  private final long boundNanos;
  private volatile long verifiedAtNanos;

  /**
   * @param verifiedAtNanos when the caller last saw this session current, on the {@code nanos}
   *     clock; a check the caller just made seeds the instance so its first use costs nothing
   */
  RequestSessionValidity(
      SocketAuthorization authorization,
      WebSocketSession socket,
      LongSupplier nanos,
      Duration bound,
      long verifiedAtNanos) {
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.socket = Objects.requireNonNull(socket, "socket");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
    Objects.requireNonNull(bound, "bound");
    if (bound.isNegative()) throw new IllegalArgumentException("Bound must not be negative");
    this.boundNanos = bound.toNanos();
    this.verifiedAtNanos = verifiedAtNanos;
  }

  @Override
  public void run() {
    long now = nanos.getAsLong();
    if (now - verifiedAtNanos < boundNanos) return;
    if (!authorization.current(socket)) throw new CallerFault("Query connection ended");
    verifiedAtNanos = now;
  }
}
