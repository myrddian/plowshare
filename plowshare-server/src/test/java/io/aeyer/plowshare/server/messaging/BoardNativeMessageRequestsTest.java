package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.BoardBridge;
import io.aeyer.plowshare.server.board.BoardBridgeRepository;
import io.aeyer.plowshare.server.board.BoardNativeRequests;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayPublisherAccounts;
import io.aeyer.plowshare.server.relay.RelayPublishing;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import io.aeyer.plowshare.server.relay.RelayPublishingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoardNativeMessageRequestsTest {
  private static final UUID EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
  private final BoardMessageExportProperties properties = new BoardMessageExportProperties();
  private final MessageExportProperties external = new MessageExportProperties();
  private final BoardNativeExportRepository repository = mock(BoardNativeExportRepository.class);
  private final NativeExecutionSources sources = mock(NativeExecutionSources.class);
  private final NativeMessageExports messages = mock(NativeMessageExports.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final RelayPublishingProperties grants = new RelayPublishingProperties();
  private final BoardMessageExportProperties.Policy policy =
      new BoardMessageExportProperties.Policy("project", "forum.requests", "board", "board.post");
  private final NativeMessageExports.Export request =
      new NativeMessageExports.Export(
          "bdm_Request", "ins_Source", "ins_External", "alice", "project");
  private final RelayCausation cause = new RelayCausation("root", "run:source:1", 1);
  private final BoardNativeExportRepository.Binding binding =
      new BoardNativeExportRepository.Binding(EVENT, 9, policy, "worker", request, cause);
  private BoardNativeMessageRequests boundary;

  @BeforeEach
  void setup() {
    properties.setPolicies(List.of(policy));
    grants.setGrants(
        List.of(new RelayPublishingProperties.Grant("project", "worker", "forum.requests")));
    when(repository.binding(EVENT)).thenReturn(Optional.of(binding));
    when(members.mayWork("project", "alice")).thenReturn(true);
    when(projects.personalOwner("project")).thenReturn(Optional.empty());
    boundary =
        new BoardNativeMessageRequests(
            properties,
            external,
            repository,
            sources,
            messages,
            grants,
            members,
            projects,
            8,
            UnitOfWork.NONE,
            Duration.ofSeconds(30),
            () -> NOW);
  }

  private Relay.Publication publication() {
    return new Relay.Publication(
        new Relay.TopicKey(9, "forum.requests"),
        1,
        NOW,
        new Relay.Draft(
            EVENT.toString(),
            "execution:" + "a".repeat(64) + ":" + "b".repeat(64),
            NOW,
            null,
            null,
            new RelayPayload.Text("Request"),
            cause));
  }

  @Test
  void proven_actor_is_an_agent_and_current_policy_loss_does_not_retarget_immutable_proof() {
    var proof = new RelayPublisherAccounts.Execution("alice", "worker");
    var principal = boundary.actor(publication(), "project", "board", "board.post", proof, false);
    assertEquals(new BoardBridge.Agent("alice", "worker", EVENT), principal.actor());
    assertTrue(principal.authorized());
    properties.setPolicies(List.of());
    assertFalse(
        boundary.actor(publication(), "project", "board", "board.post", proof, false).authorized());
    assertThrows(
        BoardNativeRequests.Unavailable.class,
        () -> boundary.actor(publication(), "project", "other", "board.post", proof, false));
    assertThrows(
        BoardNativeRequests.Unavailable.class,
        () -> boundary.actor(publication(), "project", "board", "board.close", proof, false));
    verifyNoInteractions(messages, sources, members, projects);
  }

  @Test
  void overlap_with_an_external_adapter_is_refused_instead_of_creating_two_requesters() {
    external.setPolicies(
        List.of(
            new MessageExportProperties.Policy(
                "project", "forum.requests", "adapter", "adapter.returns", "adapter")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BoardNativeMessageRequests(
                properties,
                external,
                repository,
                sources,
                messages,
                grants,
                members,
                projects,
                8,
                UnitOfWork.NONE,
                Duration.ofSeconds(30),
                () -> NOW));
    verifyNoInteractions(messages, sources);
  }

  @Test
  void export_reuses_the_exact_source_proof_and_retains_native_endpoints_transactionally() {
    var owner =
        UsageAttribution.project("alice", "9", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("cnv_Source"),
                UsageLineage.root("run:source:1"),
                UsageLineage.NONE,
                "worker",
                1L,
                1L);
    var source =
        new NativeMessageExports.Source(owner, "project", Origin.TURN, Optional.empty(), cause);
    when(sources.source(owner)).thenReturn(source);
    when(messages.export(source, "board-board", EVENT, "Request")).thenReturn(request);
    boundary.beforeAppend(
        new RelayPublishingRepository.Intent(
            EVENT,
            9,
            "project",
            owner,
            "call",
            new RelayPublishing.Request("forum.requests", "Request"),
            RelayPort.hash("work"),
            NOW,
            cause));
    verify(repository).bind(binding);
  }

  private BoardBridgeRepository.Pending pending() {
    return new BoardBridgeRepository.Pending(
        UUID.randomUUID(),
        new BoardBridgeRepository.NativeDestination(EVENT),
        EVENT.toString(),
        "bdt_Topic",
        "bdm_Answer",
        "Full answer",
        "member",
        "reviewer",
        true,
        NOW,
        cause.next(EVENT.toString(), 8));
  }

  @Test
  void expected_native_refusal_is_a_value_and_capacity_remains_retryable() {
    var pending = pending();
    when(messages.tryReturnReply(any(), any()))
        .thenReturn(
            new NativeMessageExports.Unavailable(NativeMessageExports.UnavailableReason.CLOSED));
    assertEquals(BoardNativeRequests.Reply.UNAVAILABLE, boundary.reply(pending));
    when(messages.tryReturnReply(any(), any())).thenReturn(new NativeMessageExports.Capacity());
    assertThrows(IllegalStateException.class, () -> boundary.reply(pending));
    when(messages.tryReturnReply(any(), any()))
        .thenReturn(new NativeMessageExports.Accepted("bdm_Return"));
    assertEquals(BoardNativeRequests.Reply.PUBLISHED, boundary.reply(pending));
  }

  @Test
  void fixed_refusal_outcome_uses_existing_native_loop_and_retains_safe_receipt() {
    var outcome =
        new BoardNativeExportRepository.Outcome(UUID.randomUUID(), UUID.randomUUID(), binding);
    when(repository.pendingOutcome()).thenReturn(Optional.of(outcome));
    when(messages.tryReturnReply(any(), any()))
        .thenReturn(new NativeMessageExports.Accepted("bdm_Return"));
    assertTrue(boundary.publishNextOutcome());
    verify(repository).published(outcome, "bdm_Return");
    when(messages.tryReturnReply(any(), any()))
        .thenReturn(
            new NativeMessageExports.Unavailable(NativeMessageExports.UnavailableReason.CLOSED));
    assertTrue(boundary.publishNextOutcome());
    verify(repository).unavailable(outcome);
  }
}
