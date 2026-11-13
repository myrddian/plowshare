package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayPorts;
import io.aeyer.plowshare.server.relay.RelayPublishing;
import io.aeyer.plowshare.server.relay.RelayPublishingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelayMessageExportsTest {
  private static final UUID EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID GENERATION = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
  private final MessageExportProperties properties = new MessageExportProperties();
  private final MessageExportRepository repository = mock(MessageExportRepository.class);
  private final NativeMessageExports nativeMessages = mock(NativeMessageExports.class);
  private final Relay relay = mock(Relay.class);
  private final RelayPorts ports = mock(RelayPorts.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final RelayMessageExports exports =
      new RelayMessageExports(
          properties,
          repository,
          nativeMessages,
          relay,
          ports,
          members,
          projects,
          UnitOfWork.NONE,
          8);
  private final MessageExportProperties.Policy policy =
      new MessageExportProperties.Policy(
          "project", "external.requests", "channel", "external.returns", "adapter");
  private final NativeMessageExports.Export nativeRequest =
      new NativeMessageExports.Export(
          "bdm_Request", "ins_Source", "ins_External", "alice", "project");
  private final MessageExportRepository.Binding binding =
      new MessageExportRepository.Binding(
          EVENT,
          9,
          policy,
          GENERATION,
          nativeRequest,
          new RelayCausation("root", "run:source:1", 1));
  private final MessageExports.Command command =
      new MessageExports.Command(
          9,
          "project",
          "channel",
          "external.returns",
          "adapter",
          new MessageExports.Return(UUID.randomUUID(), EVENT, "Remote result", true),
          "return-event");

  private RelayPublishingRepository.Intent intent() {
    var owner =
        UsageAttribution.project("alice", "9", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("cnv_Source"),
                UsageLineage.root("run:source:1"),
                UsageLineage.NONE,
                "worker",
                1L,
                1L);
    return new RelayPublishingRepository.Intent(
        EVENT,
        9,
        "project",
        owner,
        "call",
        new RelayPublishing.Request("external.requests", "Outbound request"),
        RelayPort.hash("work"),
        NOW,
        new RelayCausation("root", "run:source:1", 1));
  }

  @BeforeEach
  void setup() {
    properties.setPolicies(List.of(policy));
    when(ports.authorizePublisher("adapter", "project", "external.returns"))
        .thenReturn(new Relay.TopicKey(9, "external.returns"));
    when(relay.topic(new Relay.TopicKey(9, "external.returns")))
        .thenReturn(
            new Relay.Topic(
                new Relay.TopicKey(9, "external.returns"),
                RelayPayload.Kind.TEXT,
                Relay.Policy.systemDefault(),
                0,
                0,
                GENERATION));
    when(repository.binding(EVENT, false)).thenReturn(Optional.of(binding));
    when(repository.binding(EVENT, true)).thenReturn(Optional.of(binding));
    when(members.mayWork("project", "alice")).thenReturn(true);
    when(projects.personalOwner("project")).thenReturn(Optional.empty());
  }

  @Test
  void unrelated_publications_have_no_native_effect_or_extra_source_reads() {
    properties.setPolicies(List.of());
    exports.beforeAppend(intent());
    verifyNoInteractions(repository, nativeMessages, relay, ports, members, projects);
  }

  @Test
  void export_retains_proven_source_before_the_observer_returns_to_the_broker() {
    var intent = intent();
    var source =
        new NativeMessageExports.Source(
            intent.owner(), "project", Origin.TURN, Optional.empty(), intent.causation());
    when(repository.source(intent.owner())).thenReturn(source);
    when(nativeMessages.export(source, "channel", EVENT, "Outbound request"))
        .thenReturn(nativeRequest);
    exports.beforeAppend(intent);
    var order = inOrder(repository, nativeMessages);
    order.verify(repository).source(intent.owner());
    order.verify(nativeMessages).export(source, "channel", EVENT, "Outbound request");
    order.verify(repository).bind(binding);
    verifyNoInteractions(
        members, projects); // The granted publication already authorized this actor.
  }

  @Test
  void wrong_adapter_scope_and_unproven_sources_refuse_before_any_native_export() {
    when(ports.authorizePublisher("adapter", "project", "external.returns"))
        .thenReturn(new Relay.TopicKey(10, "external.returns"));
    assertThrows(MessageRefused.class, () -> exports.beforeAppend(intent()));
    verifyNoInteractions(repository, nativeMessages);
  }

  @Test
  void accepted_return_uses_only_retained_endpoints_and_saves_native_receipt() {
    when(nativeMessages.returnReply(
            nativeRequest,
            new NativeMessageExports.Return(
                command.value().requestId(),
                "Remote result",
                true,
                binding.causation().next(command.returnEvent(), 8))))
        .thenReturn("bdm_Return");
    assertEquals(new MessageExports.Receipt("bdm_Return"), exports.accept(command));
    var order = inOrder(repository, nativeMessages);
    order.verify(repository).lockReturn(command.value().requestId());
    order.verify(repository).receipt(command);
    order.verify(repository).binding(EVENT, true);
    order
        .verify(nativeMessages)
        .returnReply(
            nativeRequest,
            new NativeMessageExports.Return(
                command.value().requestId(),
                "Remote result",
                true,
                binding.causation().next(command.returnEvent(), 8)));
    order
        .verify(repository)
        .save(
            command,
            new MessageExports.Receipt("bdm_Return"),
            binding.causation().next(command.returnEvent(), 8));
  }

  @Test
  void revoked_exporter_and_replaced_return_topic_cannot_acquire_a_paid_native_wake() {
    when(members.mayWork("project", "alice")).thenReturn(false);
    assertThrows(MessageRefused.class, () -> exports.accept(command));
    verifyNoInteractions(nativeMessages);
    when(members.mayWork("project", "alice")).thenReturn(true);
    when(relay.topic(any()))
        .thenReturn(
            new Relay.Topic(
                new Relay.TopicKey(9, "external.returns"),
                RelayPayload.Kind.TEXT,
                Relay.Policy.systemDefault(),
                0,
                0,
                UUID.randomUUID()));
    assertThrows(MessageRefused.class, () -> exports.accept(command));
    verifyNoInteractions(nativeMessages);
  }

  @Test
  void canonical_receipt_survives_current_grant_policy_and_source_lifecycle_loss() {
    var receipt = new MessageExports.Receipt("bdm_Return");
    when(repository.receipt(command)).thenReturn(Optional.of(receipt));
    properties.setPolicies(List.of());
    assertEquals(receipt, exports.accept(command));
    assertTrue(exports.inspect(command).isPresent());
    verifyNoInteractions(nativeMessages, ports, relay, members, projects);
    verify(repository, never()).save(any(), any(), any());
  }

  @Test
  void exhausted_retained_cause_refuses_a_new_return_even_if_sdk_would_offer_a_fresh_root() {
    var exhausted =
        new MessageExportRepository.Binding(
            EVENT,
            9,
            policy,
            GENERATION,
            nativeRequest,
            new RelayCausation("original-root", "export-parent", 8));
    when(repository.binding(EVENT, true)).thenReturn(Optional.of(exhausted));
    assertThrows(MessageRefused.class, () -> exports.accept(command));
    verifyNoInteractions(nativeMessages);
  }

  @Test
  void another_channel_adapter_or_project_cannot_use_a_retained_export() {
    var forged =
        new MessageExports.Command(
            10,
            "project",
            "channel",
            "external.returns",
            "adapter",
            command.value(),
            "return-event");
    assertThrows(MessageRefused.class, () -> exports.require(forged));
    var wrongChannel =
        new MessageExports.Command(
            9, "project", "other", "external.returns", "adapter", command.value(), "return-event");
    assertThrows(MessageRefused.class, () -> exports.require(wrongChannel));
    var wrongActor =
        new MessageExports.Command(
            9, "project", "channel", "external.returns", "other", command.value(), "return-event");
    assertThrows(MessageRefused.class, () -> exports.require(wrongActor));
    verifyNoInteractions(nativeMessages, ports, relay, members, projects);
  }
}
