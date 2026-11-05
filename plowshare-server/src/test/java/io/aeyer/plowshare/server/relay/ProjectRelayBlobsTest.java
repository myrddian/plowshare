package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayBlob;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.blobs.BinaryBlobStorage;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectRelayBlobsTest {
  final Relay relay = mock(Relay.class);
  final BinaryBlobStorage storage = mock(BinaryBlobStorage.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  final RelayPortProperties grants = new RelayPortProperties();
  final RelayBlobProperties properties = new RelayBlobProperties();
  final ProjectRelayPorts ports =
      new ProjectRelayPorts(relay, mock(RelayPortRepository.class), grants, members, projects, 8);
  final ProjectRelayBlobs blobs =
      new ProjectRelayBlobs(ports, relay, storage, UnitOfWork.NONE, properties);
  final byte[] bytes = {1, 2, 3};
  final BlobKey key = new BlobKey("projects/7", "a".repeat(64));
  final Relay.TopicKey topic = new Relay.TopicKey(7, "media.capture");
  final RelayBlob.Publish request =
      new RelayBlob.Publish(
          UUID.randomUUID().toString(),
          "atlas",
          "media.capture",
          Instant.EPOCH,
          "application/octet-stream",
          null,
          null,
          null);

  @BeforeEach
  void allow() {
    properties.setEnabled(true);
    when(projects.id("atlas")).thenReturn(7L);
    when(members.mayWork("atlas", "writer")).thenReturn(true);
    when(members.mayWork("atlas", "reader")).thenReturn(true);
    grants.setBindings(
        List.of(
            new RelayPortProperties.Binding(
                "atlas",
                "media.capture",
                "writer",
                RelayPortProperties.Direction.INGRESS,
                List.of()),
            new RelayPortProperties.Binding(
                "atlas",
                "media.capture",
                "reader",
                RelayPortProperties.Direction.EGRESS,
                List.of("workers"))));
    when(storage.prepare(eq("projects/7"), any())).thenReturn(key);
    when(relay.publish(eq(topic), any()))
        .thenAnswer(call -> new Relay.Publication(topic, 1, Instant.EPOCH, call.getArgument(1)));
  }

  @Test
  void publication_derives_identity_and_transports_only_descriptor() {
    var receipt = blobs.publish("writer", request, 3, new ByteArrayInputStream(bytes));
    assertEquals(key.sha256(), receipt.blob().sha256());
    assertEquals("1", receipt.publication().position());
    verify(storage)
        .prepare(eq("projects/7"), argThat(value -> java.util.Arrays.equals(value, bytes)));
    verify(relay)
        .publish(
            eq(topic),
            argThat(
                d ->
                    d.publisher().equals(RelayPort.publisher("writer"))
                        && d.payload() instanceof RelayPayload.Blob b
                        && b.blob().byteLength() == 3
                        && d.causation().equals(new RelayCausation(request.requestId(), null, 0))));
  }

  @Test
  void upload_flag_and_length_refusals_have_no_storage_effect() {
    properties.setEnabled(false);
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 3, new ByteArrayInputStream(bytes)));
    properties.setEnabled(true);
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 0, new ByteArrayInputStream(bytes)));
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 2, new ByteArrayInputStream(bytes)));
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 4, new ByteArrayInputStream(bytes)));
    properties.setMaxBytes(2);
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 3, new ByteArrayInputStream(bytes)));
    verifyNoInteractions(storage, relay);
  }

  @Test
  void foreign_accounts_groups_and_personal_owner_cannot_use_binary_ports() {
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("reader", request, 3, new ByteArrayInputStream(bytes)));
    assertThrows(
        CallerFault.class,
        () ->
            blobs.read(
                "reader",
                new RelayBlob.Read("atlas", "media.capture", "foreign", request.requestId())));
    when(projects.personalOwner("atlas")).thenReturn(Optional.of("someone-else"));
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 3, new ByteArrayInputStream(bytes)));
    verifyNoInteractions(storage, relay);
  }

  @Test
  void revocation_during_finalization_prevents_publication() {
    when(storage.prepare(eq("projects/7"), any()))
        .thenAnswer(
            call -> {
              grants.setBindings(List.of());
              return key;
            });
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 3, new ByteArrayInputStream(bytes)));
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void project_replacement_cannot_retarget_finalized_bytes() {
    when(storage.prepare(eq("projects/7"), any()))
        .thenAnswer(
            call -> {
              when(projects.id("atlas")).thenReturn(8L);
              return key;
            });
    assertThrows(
        CallerFault.class,
        () -> blobs.publish("writer", request, 3, new ByteArrayInputStream(bytes)));
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void reentrant_transfer_refuses_busy_capacity_without_blocking_or_consuming_a_slot() {
    properties.setMaxConcurrent(1);
    var bounded = new ProjectRelayBlobs(ports, relay, storage, UnitOfWork.NONE, properties);
    when(storage.prepare(eq("projects/7"), any()))
        .thenAnswer(
            call -> {
              assertThrows(
                  CallerFault.class,
                  () -> bounded.publish("writer", request, 3, new ByteArrayInputStream(bytes)));
              return key;
            });
    bounded.publish("writer", request, 3, new ByteArrayInputStream(bytes));
    bounded.publish("writer", request, 3, new ByteArrayInputStream(bytes));
    verify(storage, times(2)).prepare(eq("projects/7"), any());
    verify(relay, times(2)).publish(eq(topic), any());
  }

  @Test
  void internal_filter_receivers_cannot_admit_binary_without_an_owning_envelope() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayRouting.Subscription(
                "capture", "media.capture", RelayPayload.Kind.BLOB, Relay.Start.OLDEST_RETAINED));
  }

  @Test
  void binary_ports_cannot_borrow_dynamic_tool_grants() {
    var tools = mock(io.aeyer.plowshare.server.relay.tools.RelayToolAuthority.class);
    when(tools.permits(any(), any(), any(), any(), any())).thenReturn(true);
    var dynamic =
        new ProjectRelayPorts(
            relay,
            mock(RelayPortRepository.class),
            new RelayPortProperties(),
            members,
            projects,
            8,
            tools);
    assertThrows(
        CallerFault.class,
        () ->
            dynamic.authorizeBlob(
                "writer", "atlas", "media.capture", RelayPortProperties.Direction.INGRESS, null));
    assertThrows(
        CallerFault.class,
        () ->
            ports.authorizeBlob(
                "writer",
                "atlas",
                "tool.media.result",
                RelayPortProperties.Direction.INGRESS,
                null));
  }

  @Test
  void read_requires_retained_publication_and_uses_its_exact_claim_even_after_flag_disable() {
    var descriptor = new RelayBlob.Descriptor(key.sha256(), 3, "application/octet-stream");
    var draft =
        new Relay.Draft(
            request.requestId(),
            RelayPort.publisher("writer"),
            Instant.EPOCH,
            null,
            null,
            new RelayPayload.Blob(descriptor));
    when(relay.retained(topic, request.requestId()))
        .thenReturn(Optional.of(new Relay.Publication(topic, 9, Instant.EPOCH, draft)));
    when(storage.read(key, RelayBlobClaims.claim(topic, 9))).thenReturn(bytes);
    properties.setEnabled(false);
    assertArrayEquals(
        bytes,
        blobs
            .read(
                "reader",
                new RelayBlob.Read("atlas", "media.capture", "workers", request.requestId()))
            .bytes());
    when(relay.retained(topic, request.requestId())).thenReturn(Optional.empty());
    assertThrows(
        CallerFault.class,
        () ->
            blobs.read(
                "reader",
                new RelayBlob.Read("atlas", "media.capture", "workers", request.requestId())));
    verify(storage, times(1)).read(any(BlobKey.class), any());
  }

  @Test
  void membership_revoked_while_reading_prevents_byte_delivery() {
    var descriptor = new RelayBlob.Descriptor(key.sha256(), 3, "application/octet-stream");
    var draft =
        new Relay.Draft(
            request.requestId(),
            "writer",
            Instant.EPOCH,
            null,
            null,
            new RelayPayload.Blob(descriptor));
    when(relay.retained(topic, request.requestId()))
        .thenReturn(Optional.of(new Relay.Publication(topic, 1, Instant.EPOCH, draft)));
    when(storage.read(any(BlobKey.class), any()))
        .thenAnswer(
            call -> {
              when(members.mayWork("atlas", "reader")).thenReturn(false);
              return bytes;
            });
    assertThrows(
        CallerFault.class,
        () ->
            blobs.read(
                "reader",
                new RelayBlob.Read("atlas", "media.capture", "workers", request.requestId())));
  }
}
