package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * An operation's retained authority no longer covers what it is doing: a grant, membership, role or
 * record it rests on was withdrawn or never reached the requested action.
 *
 * <p>It is a {@link CallerFault}, so every existing handler keeps treating a refusal as one. It is
 * a separate type so that the one caller which must say <em>why</em> can: the agent runtime's
 * after-tool check withholds a result and tells the model its authority changed, which would be
 * wrong for an infrastructure failure (those stay as they were) and misleading for a refusal before
 * the tool ran.
 */
public class AuthorityWithdrawnException extends CallerFault {
  public AuthorityWithdrawnException(String message) {
    super(message);
  }
}
