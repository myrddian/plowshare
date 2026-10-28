package io.aeyer.plowshare.server.information;

/**
 * Send one identifier-free invalidation to currently authenticated account sockets. Never throws or
 * blocks on network IO. It cannot send arbitrary data, name resources or grant read authority.
 */
@FunctionalInterface
public interface InformationInvalidations {
  InformationInvalidations NONE = () -> {};

  void invalidate();

  /** Content changed without changing access. Legacy transports conservatively fence it. */
  default void refresh() {
    invalidate();
  }

  /**
   * Identifier-free progress hint. It cannot change content or authorize disclosure. Legacy
   * transports conservatively use their full invalidation path; capable clients refresh current
   * progress without discarding content, registry definitions or navigation capabilities.
   */
  default void progress() {
    invalidate();
  }
}
