package io.aeyer.plowshare.server.reads;

/**
 * Read capacity is unavailable because a bounded resource cannot admit more work. Deadlines,
 * invalidation, interruption and closure use {@link ReadFailedException} instead. This is not the
 * transport's CAPACITY_EXHAUSTED promise that an entire request has not started: an enclosing
 * operation may already have committed a mutation before asking for a projection. Clients must not
 * replay it.
 */
public final class ReadUnavailableException extends RuntimeException {
  public ReadUnavailableException() {
    super("Read capacity is busy; retry the read later");
  }

  /**
   * Retains a capacity owner's safe explanation and diagnostic cause without authorizing replay.
   */
  public ReadUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
