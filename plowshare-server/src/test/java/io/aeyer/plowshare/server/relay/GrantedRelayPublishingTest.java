package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class GrantedRelayPublishingTest {
  private final RelayPublishingRepository repository = mock(RelayPublishingRepository.class);
  private final RelayPublishing.Definitions definitions = mock(RelayPublishing.Definitions.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final RelayPublishingProperties grants = new RelayPublishingProperties();
  private final Instant now = Instant.parse("2026-11-13T00:00:00Z");
  private final UsageAttribution owner =
      UsageAttribution.project("alice", "7", UsageAttribution.Operation.AGENT_CHAT)
          .withExecution(
              UsageLineage.root("conversation"),
              UsageLineage.root("run"),
              UsageLineage.NONE,
              "writer",
              1L,
              1L);
  private final RelayPublishing.Execution execution =
      new RelayPublishing.Execution(owner, "session", "call", RelayPublishing.DefinitionKind.AGENT);
  private final RelayPublishing.Request request =
      new RelayPublishing.Request("reports.input", "  text\nunchanged  ");
  private GrantedRelayPublishing publishing;

  @BeforeEach
  void granted() {
    grants.setGrants(
        List.of(new RelayPublishingProperties.Grant("research", "writer", "reports.input")));
    when(members.mayWork("research", "alice")).thenReturn(true);
    when(projects.id("research")).thenReturn(7L);
    when(projects.personalOwner("research")).thenReturn(Optional.empty());
    when(definitions.permits(7, execution)).thenReturn(true);
    when(repository.ancestry(7, owner))
        .thenReturn(Optional.of(new RelayCausation("root", "incoming", 2)));
    when(repository.publish(any()))
        .thenAnswer(
            call -> {
              RelayPublishingRepository.Intent intent = call.getArgument(0);
              return new RelayPublishing.Receipt(intent.id(), intent.request().topic(), "5", now);
            });
    publishing =
        new GrantedRelayPublishing(
            repository, grants, definitions, members, projects, 8, () -> now);
  }

  @Test
  void stable_call_identity_preserves_text_and_inherits_the_effect_budget() {
    var first = publishing.publish("research", execution, request);
    var repeat = publishing.publish("research", execution, request);
    assertEquals(first, repeat);
    var captured = ArgumentCaptor.forClass(RelayPublishingRepository.Intent.class);
    verify(repository, Mockito.times(2)).publish(captured.capture());
    assertEquals(request.text(), captured.getValue().request().text());
    assertEquals(new RelayCausation("root", "run", 3), captured.getValue().causation());
    var changed =
        publishing.publish(
            "research", execution, new RelayPublishing.Request(request.topic(), "different"));
    assertEquals(first.eventId(), changed.eventId());
    assertEquals(3, captured.getValue().causation().depth());
  }

  @Test
  void topic_definition_and_membership_grants_are_independent_and_live() {
    assertThrows(CallerFault.class, () -> publishing.publish("other", execution, request));
    assertThrows(
        CallerFault.class,
        () ->
            publishing.publish(
                "research", execution, new RelayPublishing.Request("other.topic", "text")));
    verifyNoInteractions(repository);
    when(definitions.permits(7, execution)).thenReturn(false);
    assertThrows(CallerFault.class, () -> publishing.publish("research", execution, request));
    when(definitions.permits(7, execution)).thenReturn(true);
    when(members.mayWork("research", "alice")).thenReturn(false);
    assertThrows(CallerFault.class, () -> publishing.publish("research", execution, request));
    verifyNoInteractions(repository);
  }

  @Test
  void personal_ownership_and_recreated_project_identity_are_checked() {
    when(projects.personalOwner("research")).thenReturn(Optional.of("bob"));
    assertThrows(CallerFault.class, () -> publishing.publish("research", execution, request));
    when(projects.personalOwner("research")).thenReturn(Optional.empty());
    when(projects.id("research")).thenReturn(8L);
    assertThrows(CallerFault.class, () -> publishing.publish("research", execution, request));
    verifyNoInteractions(repository);
  }

  @Test
  void unknown_exhausted_and_missing_ancestry_cannot_become_a_fresh_root() {
    for (var ancestry :
        List.of(RelayCausation.unknown("root"), new RelayCausation("root", "parent", 8))) {
      when(repository.ancestry(7, owner)).thenReturn(Optional.of(ancestry));
      assertThrows(CallerFault.class, () -> publishing.publish("research", execution, request));
    }
    when(repository.ancestry(7, owner)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> publishing.publish("research", execution, request));
    verify(repository, never()).publish(any());
  }

  @Test
  void system_legacy_and_unbound_work_never_publish() {
    for (var invalid :
        List.of(
            UsageAttribution.LEGACY,
            UsageAttribution.system("7", UsageAttribution.Operation.AGENT_CHAT),
            UsageAttribution.project("alice", "7", UsageAttribution.Operation.AGENT_CHAT))) {
      var context =
          new RelayPublishing.Execution(
              invalid, null, "call", RelayPublishing.DefinitionKind.AGENT);
      assertThrows(CallerFault.class, () -> publishing.publish("research", context, request));
    }
    verifyNoInteractions(repository);
  }

  @Test
  void invalid_and_duplicate_grants_fail_configuration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayPublishingProperties.Grant("research", "writer", "*"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayPublishingProperties.Grant("research", "Bad Name", "input"));
    var grant = grants.getGrants().getFirst();
    assertThrows(IllegalArgumentException.class, () -> grants.setGrants(List.of(grant, grant)));
    assertThrows(
        IllegalArgumentException.class, () -> new RelayPublishing.Request("bad topic", "text"));
  }
}
