package io.aeyer.plowshare.server.information;

/**
 * Send one identifier-free invalidation to currently authenticated account sockets. Never throws or
 * blocks on network IO. It cannot send arbitrary data, name resources or grant read authority.
 */
@FunctionalInterface
public interface InformationInvalidations {
  InformationInvalidations NONE = () -> {};

  void invalidate();
}
