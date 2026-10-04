package io.aeyer.plowshare.server.ws;

/**
 * A bare push to one session's listener — {@code events.AccountPushes}' shape, addressed by session
 * rather than by account. It goes through the same per-socket queue as the session's job events, so
 * a push made before an event is received before it.
 */
@FunctionalInterface
public interface SessionPushes {

  SessionPushes NONE = (session, body) -> {};

  void tell(String session, Object body);
}
