package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.ProjectRelayPorts;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayPortProperties;
import io.aeyer.plowshare.server.relay.RelayPortRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduledRelayOccurrencesTest {
  private static final Instant AT = Instant.parse("2026-10-05T01:00:00Z");
  private static final RelayPayload.ScheduleDue DUE =
      new RelayPayload.ScheduleDue("morning", "daily", AT);

  private Relay.Publication retained(Relay.Scope scope, Relay.Draft event) {
    return new Relay.Publication(
        new Relay.TopicKey(scope, EventTopics.scheduled(scope)), 116, AT.plusSeconds(3), event);
  }

  private Relay.Draft legacy() {
    return new Relay.Draft(
        ScheduledRelayOccurrences.identity(DUE), "operator", AT, null, null, DUE);
  }

  @Test
  void legacy_independent_occurrences_keep_the_same_root_in_both_scheduler_scopes() {
    for (Relay.Scope scope : List.of(new Relay.ProjectScope(7), Relay.SystemScope.SERVER)) {
      var publication = retained(scope, legacy());
      assertEquals(
          Optional.of(RelayCausation.root(publication.event().eventId())),
          ScheduledRelayOccurrences.legacyRoot(publication));
    }
    // Pin the pre-causation encoding so adding metadata cannot change durable occurrence identity.
    assertEquals(
        "schedule:76a470e05231629aab1436e1128d4788d05ddcb5865ad0f7cebb9000f44a28f9",
        ScheduledRelayOccurrences.identity(DUE));
  }

  @Test
  void missing_or_explicitly_unknown_derived_ancestry_never_gets_a_fresh_root() {
    var event = legacy();
    var scope = new Relay.ProjectScope(7);
    for (var invalid :
        List.of(
            new Relay.Draft("other", "operator", AT, null, null, DUE),
            new Relay.Draft(event.eventId(), "operator", AT.plusSeconds(1), null, null, DUE),
            new Relay.Draft(event.eventId(), "operator", AT, "correlated", null, DUE),
            new Relay.Draft(event.eventId(), "operator", AT, null, "parent", DUE),
            new Relay.Draft(
                event.eventId(), "operator", AT, null, null, new RelayPayload.Text("x")),
            new Relay.Draft(
                event.eventId(),
                "operator",
                AT,
                null,
                null,
                DUE,
                RelayCausation.unknown(event.eventId())),
            new Relay.Draft(
                event.eventId(),
                "operator",
                AT,
                null,
                null,
                DUE,
                new RelayCausation("root", "parent", 8)))) {
      assertTrue(ScheduledRelayOccurrences.legacyRoot(retained(scope, invalid)).isEmpty());
    }
    for (var topic :
        List.of(
            new Relay.TopicKey(scope, "custom.topic"),
            new Relay.TopicKey(scope, EventTopics.scheduled(new Relay.ProjectScope(8))))) {
      assertTrue(
          ScheduledRelayOccurrences.legacyRoot(new Relay.Publication(topic, 1, AT, event))
              .isEmpty());
    }
  }

  @Test
  void sdk_completion_of_a_retained_occurrence_consumes_one_effect_and_keeps_live_grants() {
    var relay = mock(Relay.class);
    var repository = mock(RelayPortRepository.class);
    var members = mock(ProjectMembers.class);
    var projects = mock(ProjectWorkspaces.class);
    var policy = new RelayPortProperties();
    var parent = retained(new Relay.ProjectScope(7), legacy());
    var result = new Relay.TopicKey(7, "completion");
    String requestId = UUID.randomUUID().toString();
    var request =
        new RelayPort.Publish(
            requestId,
            "project",
            "completion",
            "retained evidence",
            AT,
            "scan",
            parent.topic().name(),
            parent.event().eventId());
    var ingress =
        new RelayPortProperties.Binding(
            "project", "completion", "collector", RelayPortProperties.Direction.INGRESS, List.of());
    policy.setBindings(List.of(ingress));
    when(members.mayWork("project", "collector")).thenReturn(true);
    when(projects.id("project")).thenReturn(7L);
    when(relay.retained(parent.topic(), parent.event().eventId())).thenReturn(Optional.of(parent));
    when(relay.publish(eq(result), any()))
        .thenAnswer(call -> new Relay.Publication(result, 1, AT, call.getArgument(1)));
    var ports = new ProjectRelayPorts(relay, repository, policy, members, projects, 8);
    assertThrows(CallerFault.class, () -> ports.publish("collector", request));
    verify(relay, never()).retained(any(), any());
    verify(relay, never()).publish(any(), any());

    policy.setBindings(
        List.of(
            ingress,
            new RelayPortProperties.Binding(
                "project",
                parent.topic().name(),
                "collector",
                RelayPortProperties.Direction.EGRESS,
                List.of("collector"))));
    assertEquals(requestId, ports.publish("collector", request).requestId());
    var draft = ArgumentCaptor.forClass(Relay.Draft.class);
    verify(relay).publish(eq(result), draft.capture());
    assertEquals(
        new RelayCausation(parent.event().eventId(), parent.event().eventId(), 1),
        draft.getValue().causation());
    assertEquals(requestId, draft.getValue().eventId());

    when(members.mayWork("project", "collector")).thenReturn(false);
    assertThrows(CallerFault.class, () -> ports.publish("collector", request));
  }
}
