package io.aeyer.plowshare.server.reads;

/**
 * Read capacity is unavailable. This is a service-unavailable failure, not the transport's
 * CAPACITY_EXHAUSTED promise that an entire request has not started: an enclosing operation may
 * already have committed a mutation before asking for a projection. Clients must not replay it.
 */
public final class ReadUnavailableException extends RuntimeException {
  public ReadUnavailableException() {
    super("Read capacity is busy; retry the read later");
  }
}
