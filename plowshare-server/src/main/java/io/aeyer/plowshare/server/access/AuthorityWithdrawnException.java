package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * An operation's retained authority no longer covers what it is doing: a grant, membership, role or
 * record it rests on was withdrawn or never reached the requested action.
 *
 * <p>It is a marker type for a retained-authority refusal, thrown at admission and on rechecks. It
 * is a {@link CallerFault}, so every existing handler keeps treating a refusal as one; the type
 * only records that the refusal came from retained authority rather than from the request itself.
 */
public class AuthorityWithdrawnException extends CallerFault {
  public AuthorityWithdrawnException(String message) {
    super(message);
  }
}
