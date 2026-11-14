package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.web.socket.WebSocketSession;

/**
 * The account-session fence for one socket request (or one socket's delivery), answering from the
 * last success only while that answer cannot be older than the authority snapshot allows.
 *
 * <p>One request asks the same question several times: at execution, after the handler, before
 * disclosure and in each handler wait loop. A success is reused only while all of these hold:
 *
 * <ol>
 *   <li>an {@link AuthoritySnapshot} is present now (when none may be used, for instance because
 *       the notification listener is down, every call checks);
 *   <li>it is the very snapshot instance that was current when the success was recorded, so any
 *       AUTHORITY version change, including a session revocation, ends reuse at once;
 *   <li>the success is younger than {@code bound}, {@code
 *       plowshare.projection-cache.notified.staleness}.
 * </ol>
 *
 * Otherwise {@link #run()} calls {@link SocketAuthorization#current}. The timestamp and snapshot
 * are taken before the check and a success is not recorded if the snapshot instance changed during
 * it, as {@code BoundedVerifiedAdmissions} does. Worst-case staleness is therefore the snapshot's
 * own lag; this layer adds none on top. The scheduled sweep and every new frame still check on
 * their own.
 *
 * <p>Only a success is remembered. A refusal throws, and the next call asks again rather than
 * replaying it. A zero {@code bound} never reuses. Calls may arrive from several threads; a race at
 * most repeats a check.
 */
final class RequestSessionValidity implements Runnable {
  /** A success with the stamp and snapshot taken before its check began. */
  private record Verified(long atNanos, AuthoritySnapshot snapshot) {}

  private final SocketAuthorization authorization;
  private final WebSocketSession socket;
  private final LongSupplier nanos;
  private final long boundNanos;
  private final AuthoritySnapshots snapshots;
  private volatile Verified verified;

  RequestSessionValidity(
      SocketAuthorization authorization,
      WebSocketSession socket,
      LongSupplier nanos,
      Duration bound,
      AuthoritySnapshots snapshots) {
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.socket = Objects.requireNonNull(socket, "socket");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
    Objects.requireNonNull(bound, "bound");
    if (bound.isNegative()) throw new IllegalArgumentException("Bound must not be negative");
    this.boundNanos = bound.toNanos();
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
  }

  /**
   * Checks the session now, whatever was remembered, and remembers a success. Returns whether the
   * session is current; {@link SocketAuthorization#current} has closed the socket on false.
   */
  boolean verify() {
    long started = nanos.getAsLong();
    AuthoritySnapshot before = snapshots.current().orElse(null);
    if (!authorization.current(socket)) return false;
    if (before != null && snapshots.current().orElse(null) == before)
      verified = new Verified(started, before);
    return true;
  }

  @Override
  public void run() {
    var last = verified;
    if (last != null && nanos.getAsLong() - last.atNanos() < boundNanos) {
      var now = snapshots.current().orElse(null);
      if (now != null && now == last.snapshot()) return;
    }
    if (!verify()) throw new CallerFault("Query connection ended");
  }
}
