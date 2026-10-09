package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectRelayPortsTest {
  final Relay relay = mock(Relay.class);
  final RelayPortRepository repository = mock(RelayPortRepository.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  final RelayPortProperties properties = new RelayPortProperties();
  final ProjectRelayPorts ports =
      new ProjectRelayPorts(relay, repository, properties, members, projects, 8);
  final String id = UUID.randomUUID().toString();

  RelayPort.Publish request() {
    return new RelayPort.Publish(
        id, "project", "custom.topic", "complete message", Instant.EPOCH, null, null, null);
  }

  void allow() {
    when(members.mayWork("project", "reviewer")).thenReturn(true);
    when(projects.id("project")).thenReturn(7L);
    properties.setBindings(
        List.of(
            new RelayPortProperties.Binding(
                "project",
                "custom.topic",
                "reviewer",
                RelayPortProperties.Direction.INGRESS,
                List.of()),
            new RelayPortProperties.Binding(
                "project",
                "source.topic",
                "reviewer",
                RelayPortProperties.Direction.EGRESS,
                List.of("filters"))));
  }

  @Test
  void membership_does_not_open_a_port_and_personal_ownership_cannot_be_bypassed() {
    when(members.mayWork("project", "reviewer")).thenReturn(true);
    assertThrows(CallerFault.class, () -> ports.publish("reviewer", request()));
    allow();
    when(projects.personalOwner("project")).thenReturn(Optional.of("someone-else"));
    assertThrows(CallerFault.class, () -> ports.publish("reviewer", request()));
    verifyNoInteractions(relay, repository);
  }

  @Test
  void publisher_is_authenticated_and_text_is_preserved() {
    allow();
    when(relay.publish(any(), any()))
        .thenAnswer(
            call ->
                new Relay.Publication(call.getArgument(0), 1, Instant.EPOCH, call.getArgument(1)));
    assertEquals("1", ports.publish("reviewer", request()).position());
    verify(relay)
        .publish(
            eq(new Relay.TopicKey(7, "custom.topic")),
            argThat(
                d ->
                    d.publisher().equals(RelayPort.publisher("reviewer"))
                        && d.payload().equals(new RelayPayload.Text("complete message"))
                        && d.causation().depth() == 0));
  }

  @Test
  void derived_ingress_requires_read_authority_and_retained_bounded_ancestry() {
    allow();
    var request =
        new RelayPort.Publish(
            id,
            "project",
            "custom.topic",
            "response",
            Instant.EPOCH,
            id,
            "source.topic",
            "original");
    var parentTopic = new Relay.TopicKey(7, "source.topic");
    when(relay.retained(parentTopic, "original")).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> ports.publish("reviewer", request));
    var original =
        new Relay.Publication(
            parentTopic,
            1,
            Instant.EPOCH,
            new Relay.Draft(
                "original",
                "filter",
                Instant.EPOCH,
                null,
                null,
                new RelayPayload.Text("source"),
                new RelayCausation(id, "older", 8)));
    when(relay.retained(parentTopic, "original")).thenReturn(Optional.of(original));
    assertThrows(CallerFault.class, () -> ports.publish("reviewer", request));
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void egress_and_ack_require_the_configured_group() {
    allow();
    var query =
        new RelayPort.Consume(
            "project", "source.topic", "other", id, RelayPort.Start.OLDEST_RETAINED, 1, 0);
    assertThrows(CallerFault.class, () -> ports.consume("reviewer", query));
    assertThrows(
        CallerFault.class,
        () ->
            ports.acknowledge(
                "reviewer",
                new RelayPort.Ack("project", "source.topic", "other", id, id, "1", null)));
    verifyNoInteractions(repository);
  }

  @Test
  void tool_binding_derives_only_provider_request_egress_and_result_ingress() {
    var tools = new io.aeyer.plowshare.server.relay.tools.RelayToolProperties();
    var binding =
        new io.aeyer.plowshare.server.relay.tools.RelayToolDefinition(
            "project", "scanner", "provider", "network_scope", "Read scope", List.of(), 30);
    tools.setBindings(List.of(binding));
    var scoped = new ProjectRelayPorts(relay, repository, properties, members, projects, 8, tools);
    when(members.mayWork("project", "provider")).thenReturn(true);
    when(members.mayWork("project", "other")).thenReturn(true);
    when(projects.id("project")).thenReturn(7L);
    var query =
        new RelayPort.Consume(
            "project",
            binding.requests(),
            "tool-provider",
            id,
            RelayPort.Start.OLDEST_RETAINED,
            1,
            0);
    when(repository.consume(any(), anyString(), any(), any()))
        .thenReturn(
            new RelayPort.Batch(
                "project",
                binding.requests(),
                "tool-provider",
                id,
                RelayPort.Status.EMPTY,
                null,
                null,
                "0",
                null,
                null,
                List.of()));
    scoped.consume("provider", query);
    verify(relay)
        .registerTopic(
            eq(new Relay.TopicKey(7L, binding.requests())), eq(RelayPayload.Kind.TEXT), any());
    assertThrows(CallerFault.class, () -> scoped.consume("other", query));
    assertThrows(
        CallerFault.class,
        () ->
            scoped.consume(
                "provider",
                new RelayPort.Consume(
                    "project",
                    binding.requests(),
                    "other",
                    id,
                    RelayPort.Start.OLDEST_RETAINED,
                    1,
                    0)));
    assertThrows(
        CallerFault.class,
        () ->
            scoped.consume(
                "provider",
                new RelayPort.Consume(
                    "project",
                    binding.results(),
                    "tool-provider",
                    id,
                    RelayPort.Start.OLDEST_RETAINED,
                    1,
                    0)));
    assertThrows(
        CallerFault.class,
        () ->
            scoped.publish(
                "provider",
                new RelayPort.Publish(
                    id, "project", binding.requests(), "fake", Instant.EPOCH, null, null, null)));
  }

  @Test
  void provider_result_must_identify_the_bound_request_and_preserve_ancestry() {
    var binding =
        new io.aeyer.plowshare.server.relay.tools.RelayToolDefinition(
            "project", "scanner", "provider", "network_scope", "Read scope", List.of(), 30);
    var tools = new io.aeyer.plowshare.server.relay.tools.RelayToolProperties();
    tools.setBindings(List.of(binding));
    var scoped = new ProjectRelayPorts(relay, repository, properties, members, projects, 8, tools);
    when(members.mayWork("project", "provider")).thenReturn(true);
    when(projects.id("project")).thenReturn(7L);
    var result =
        new io.aeyer.plowshare.server.relay.tools.RelayToolCodec.Result(
            io.aeyer.plowshare.server.relay.tools.RelayToolCodec.VERSION,
            id,
            "project",
            "scanner",
            "network_scope",
            io.aeyer.plowshare.server.relay.tools.RelayToolCodec.State.COMPLETED,
            "scope");
    var text = io.aeyer.plowshare.server.relay.tools.RelayToolCodec.write(result);
    assertThrows(
        CallerFault.class,
        () ->
            scoped.publish(
                "provider",
                new RelayPort.Publish(
                    id, "project", binding.results(), text, Instant.EPOCH, null, null, null)));
    var parentTopic = new Relay.TopicKey(7, binding.requests());
    var cause = RelayCausation.root("original").next("run", 8);
    when(relay.retained(parentTopic, id))
        .thenReturn(
            Optional.of(
                new Relay.Publication(
                    parentTopic,
                    1,
                    Instant.EPOCH,
                    new Relay.Draft(
                        id,
                        "tool-runtime",
                        Instant.EPOCH,
                        id,
                        "run",
                        new RelayPayload.Text("request"),
                        cause))));
    when(relay.publish(any(), any()))
        .thenAnswer(
            call ->
                new Relay.Publication(call.getArgument(0), 1, Instant.EPOCH, call.getArgument(1)));
    scoped.publish(
        "provider",
        new RelayPort.Publish(
            io.aeyer.plowshare.server.relay.tools.RelayToolCodec.resultRequestId(
                    UUID.fromString(id))
                .toString(),
            "project",
            binding.results(),
            text,
            Instant.EPOCH,
            id,
            binding.requests(),
            id));
    verify(relay)
        .publish(
            eq(new Relay.TopicKey(7, binding.results())),
            argThat(d -> d.causation().equals(cause.next(id, 8))));
  }
}
