package io.aeyer.plowshare.server.messaging;

/**
 * Current source and return port authority; persisted pins and payloads do not grant this access.
 */
@FunctionalInterface
public interface MessageRelayAccess {
  void require(MessageRelayRepository.Family family);
}
