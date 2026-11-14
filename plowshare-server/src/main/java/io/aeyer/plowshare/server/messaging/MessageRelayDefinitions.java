package io.aeyer.plowshare.server.messaging;

/** Current served definition and CommandCatalog grants, resolved before any native admission. */
@FunctionalInterface
public interface MessageRelayDefinitions {
  void require(MessageRelayRepository.Family family, String command);
}
