package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The session a request named, or nothing — never a session that is present and empty.
 *
 * <h2>The two statements a client can make, and only one of them is ordinary</h2>
 *
 * <p>Omitting the key says "I have no session", which is ordinary and gets the server's own
 * filesystems. Sending {@code ""} says "here is my session" and names nothing, and starting the run
 * anyway would hand that client a job that silently cannot reach the machine it was submitted from.
 * A 400 is what makes that a message rather than a mystery.
 *
 * <p>The same string is refused from both other ends: {@code JobStore.submit} for callers that do
 * not come through the API at all, and {@code server.session.SessionRegistry.attach} from the
 * socket.
 *
 * <h2>One copy, on {@link RequestedTurnCap}'s reasoning</h2>
 *
 * <p>Two doors start a run carrying a session — {@code POST /v1/agents/&#123;name&#125;/runs} and
 * {@code POST /v1/conversations/&#123;id&#125;/resume} — and this is one refusal about one field,
 * wanted identically by both. It is the same argument that keeps the turn cap's two-field reading
 * in one place and that keeps {@code resolveHome}'s three lines out of one: what is shared here is
 * a sentence, not a shape.
 *
 * <h2>Why a continued run has to be told again</h2>
 *
 * <p>A session is a socket somebody is holding <em>now</em>, not a fact the stopped run left
 * behind. The run being continued may have been attached to a client that has since gone away, and
 * the person granting more allowance may be at a different machine — so the grant says which
 * session this run reaches, the way the utterance did.
 */
public final class RequestedSession {

  private RequestedSession() {}

  /**
   * The session id, or {@code null} for a request that has none.
   *
   * @param session what the body carried
   * @return the id, or null
   * @throws CallerFault if it is present and blank
   */
  public static String in(String session) {
    if (session == null) {
      return null;
    }
    if (session.isBlank()) {
      throw new CallerFault(
          "'session' was given as an empty value. Leave it out for a run with no"
              + " session; an id that cannot be named is one no job can be routed"
              + " to");
    }
    return session;
  }
}
