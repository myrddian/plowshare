package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPorts;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageRelayAccessTest {
  @Test
  void one_current_authority_snapshot_pins_both_topics_and_execution_definition() {
    var ports = mock(RelayPorts.class);
    var policy = new RelayPublishingProperties();
    var access = new LiveMessageRelayAccess(ports, policy);
    UUID generation = UUID.randomUUID();
    var family =
        new MessageRelayRepository.Family(
            "relay-family",
            "alice",
            "project",
            9,
            RelayPort.publisher("alice"),
            "commands",
            "subscriber",
            generation,
            "shared",
            "producer",
            "worker",
            "results",
            generation);
    when(ports.authorizePublishers("alice", "project", List.of("commands", "results")))
        .thenReturn(
            Map.of(
                "commands",
                new Relay.TopicKey(9, "commands"),
                "results",
                new Relay.TopicKey(9, "results")));
    assertThrows(MessageRefused.class, () -> access.require(family));
    policy.setGrants(
        List.of(new RelayPublishingProperties.Grant("project", "producer", "commands")));
    access.require(family);
    policy.setGrants(List.of());
    assertThrows(MessageRefused.class, () -> access.require(family));
    policy.setGrants(
        List.of(new RelayPublishingProperties.Grant("project", "producer", "commands")));
    when(ports.authorizePublishers("alice", "project", List.of("commands", "results")))
        .thenReturn(
            Map.of(
                "commands",
                new Relay.TopicKey(100, "commands"),
                "results",
                new Relay.TopicKey(100, "results")));
    assertThrows(MessageRefused.class, () -> access.require(family));
    when(ports.authorizePublishers("alice", "project", List.of("commands", "results")))
        .thenReturn(Map.of("commands", new Relay.TopicKey(9, "commands")));
    assertThrows(MessageRefused.class, () -> access.require(family));
  }

  @Test
  void legacy_mappings_are_exact_unique_bounded_and_never_adopt_execution_publishers() {
    var properties = new MessageRelayProperties();
    UUID generation = UUID.randomUUID();
    var mapping =
        new MessageRelayProperties.LegacyMapping(
            "alice",
            "project",
            "client",
            "commands",
            "subscriber",
            generation,
            "shared",
            "worker",
            "results",
            generation);
    properties.setLegacyMappings(List.of(mapping));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setLegacyMappings(List.of(mapping, mapping)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessageRelayProperties.LegacyMapping(
                "alice",
                "project",
                "bad client",
                "commands",
                "subscriber",
                generation,
                "shared",
                "worker",
                "results",
                generation));
    var family =
        new MessageRelayRepository.Family(
            "relay-family",
            "alice",
            "project",
            9,
            "execution:server",
            "commands",
            "subscriber",
            generation,
            "shared",
            "producer",
            "worker",
            "results",
            generation);
    assertFalse(properties.permitsMigration(family, "client"));
  }
}
