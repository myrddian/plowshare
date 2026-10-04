package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The ordinal a reading of a log starts after, as both surfaces read it.
 *
 * <p><b>Why this is not folded into {@link RequestedWindow}.</b> {@code offset} and {@code limit}
 * name a page; {@code after} names where in the log that page begins, and the two are read together
 * but mean different things — an offset counts rows on the page a reading already picked, and
 * {@code after} picks which reading it is. {@code conversation.trajectory} and {@code GET
 * /v1/conversations/{id}/trajectory} both take exactly this one number, read exactly this one way,
 * so it gets its own type for {@link RequestedWindow}'s own reason: one place to keep a bound in
 * step with itself rather than two.
 */
public final class RequestedAfter {

  private RequestedAfter() {}

  /**
   * The ordinal a request named, or a {@link CallerFault} for one that names before the log's own
   * first entry.
   *
   * @param after the request's own {@code after} field, or null
   * @return 0 when absent, which reads from the first entry
   * @throws CallerFault if {@code after} is negative
   */
  public static int in(Integer after) {
    int given = after == null ? 0 : after;
    if (given < 0) {
      throw new CallerFault(
          "'after' is the ordinal of the last entry already read, and ordinals count"
              + " from 1, so it is 0 or later; this asked for "
              + given
              + ". Leave it out to read from the beginning.");
    }
    return given;
  }
}
