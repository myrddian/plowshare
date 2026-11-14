package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.a2a.model.ExternalMessage;
import io.aeyer.plowshare.a2a.model.ExternalResult;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayRemediationTest {
  @TempDir Path directory;

  private RelayAdapter worker(
      DispatchJournal journal,
      RelayAdapterTest.FakeGateway gateway,
      RelayAdapterTest.FakePeer peer,
      Instant clock) {
    return new RelayAdapter(
        RelayAdapterTest.BINDING,
        gateway,
        journal,
        Map.of("peer", peer),
        () -> clock,
        new RelayAdapter.DiscoveryPolicy(3, Duration.ofMillis(1)),
        duration -> {});
  }

  @Test
  void nanosecond_clock_and_legacy_intent_reconcile_against_microsecond_server_receipt()
      throws Exception {
    var gateway = new RelayAdapterTest.FakeGateway();
    var peer = new RelayAdapterTest.FakePeer();
    var request = RelayAdapterTest.send();
    gateway.sources.add(RelayAdapterTest.event(request));
    gateway.loseReply = true;
    Instant precise = RelayAdapterTest.TIME.plusNanos(123456789);
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = worker(journal, gateway, peer, precise);
      assertThrows(IOException.class, worker::tick);
      assertEquals(
          precise.truncatedTo(ChronoUnit.MICROS),
          journal.find(request.requestId()).orElseThrow().occurredAt());
      assertEquals(1, worker.reconcile(gateway.page("project", RelayAdapterTest.PUBLISHER)));
      assertEquals(1, peer.sends);
    }
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      assertEquals(
          precise.truncatedTo(ChronoUnit.MICROS),
          journal.find(request.requestId()).orElseThrow().occurredAt());
    }
  }

  @Test
  void malformed_unknown_peer_uuid_conflict_and_peer_outage_do_not_poison_later_work()
      throws Exception {
    var gateway = new RelayAdapterTest.FakeGateway();
    var peer = new RelayAdapterTest.FakePeer();
    gateway.sources.add(
        new RelayLog.Event(
            "1",
            "malformed",
            RelayAdapterTest.PRODUCER,
            RelayAdapterTest.TIME,
            RelayAdapterTest.TIME,
            null,
            null,
            new RelayLog.Payload("TEXT", "{}", null, null, null),
            RelayCausation.root("root")));
    var unknown =
        new RelayWork.Request(
            "plowshare-a2a-dispatch/1",
            UUID.randomUUID(),
            "missing",
            RelayWork.Action.SEND,
            RelayAdapterTest.send().message(),
            null,
            null);
    gateway.sources.add(RelayAdapterTest.event(unknown));
    var first = RelayAdapterTest.send();
    gateway.sources.add(RelayAdapterTest.event(first));
    gateway.sources.add(RelayAdapterTest.event(first));
    gateway.sources.add(RelayAdapterTest.event(RelayAdapterTest.send()));
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = worker(journal, gateway, peer, RelayAdapterTest.TIME);
      for (int n = 0; n < 5; n++) assertTrue(worker.tick());
      assertEquals(2, peer.sends);
      assertEquals(5, gateway.acks);
      assertEquals(3, worker.diagnostics().rejectedRequests());
      assertTrue(gateway.publications.getFirst().text().contains("INVALID_REQUEST"));
      assertTrue(
          gateway.publications.stream().noneMatch(p -> p.text().contains("secret remote body")));
      peer.unavailableCards = 3;
      gateway.sources.add(RelayAdapterTest.event(RelayAdapterTest.send()));
      gateway.sources.add(RelayAdapterTest.event(RelayAdapterTest.send()));
      assertTrue(worker.tick());
      assertTrue(worker.tick());
      assertEquals(3, peer.sends);
      assertEquals(3, worker.diagnostics().discoveryFailures());
      assertEquals(4, worker.diagnostics().rejectedRequests());
    }
  }

  @Test
  void only_positive_not_submitted_evidence_allows_another_publication_attempt() throws Exception {
    var gateway = new RelayAdapterTest.FakeGateway();
    var peer = new RelayAdapterTest.FakePeer();
    var request = RelayAdapterTest.send();
    gateway.sources.add(RelayAdapterTest.event(request));
    gateway.notSubmitted = true;
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = worker(journal, gateway, peer, RelayAdapterTest.TIME);
      assertThrows(RelayAdapter.ReplyNotSubmitted.class, worker::tick);
      var pending = journal.find(request.requestId()).orElseThrow();
      assertEquals(RelayWork.Phase.OBSERVED, pending.phase());
      assertEquals("NOT_SUBMITTED", pending.resolution());
      assertEquals(1, peer.sends);
      assertEquals(0, gateway.publications.size());
      gateway.notSubmitted = false;
      assertTrue(worker.tick());
      assertEquals(1, peer.sends);
      assertEquals(1, gateway.publications.size());
      assertEquals(2, journal.find(request.requestId()).orElseThrow().replyAttempts());
    }
  }

  @Test
  void absent_or_pruned_receipt_stays_unknown_until_explicit_operator_abandonment()
      throws Exception {
    var gateway = new RelayAdapterTest.FakeGateway();
    var peer = new RelayAdapterTest.FakePeer();
    var request = RelayAdapterTest.send();
    gateway.sources.add(RelayAdapterTest.event(request));
    gateway.loseReply = true;
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = worker(journal, gateway, peer, RelayAdapterTest.TIME);
      assertThrows(IOException.class, worker::tick);
      var absent =
          new RelayLog.Page(
              new RelayLog.Scope("project", false),
              new RelayLog.Topic(
                  RelayAdapterTest.BINDING.replies(), "TEXT", "345600", null, "1", "1"),
              "0",
              "1",
              "1",
              List.of(),
              List.of(),
              List.of());
      assertEquals(0, worker.reconcile(absent));
      assertThrows(IOException.class, worker::tick);
      assertEquals(1, gateway.publications.size());
      worker.abandonReply(
          request.requestId(),
          "Operator elected to lose this reply after independent investigation; delivery remains unconfirmed.");
      assertEquals(
          RelayWork.Phase.ABANDONED, journal.find(request.requestId()).orElseThrow().phase());
      assertTrue(worker.tick());
      assertEquals(1, peer.sends);
      assertEquals(1, gateway.publications.size());
      assertEquals(1, gateway.acks);
    }
  }

  @Test
  void reconciliation_pages_to_captured_head_without_treating_absence_as_non_delivery()
      throws Exception {
    var delegate = new RelayAdapterTest.FakeGateway();
    var peer = new RelayAdapterTest.FakePeer();
    var request = RelayAdapterTest.send();
    delegate.sources.add(RelayAdapterTest.event(request));
    delegate.loseReply = true;
    var cursors = new ArrayList<String>();
    RelayGateway gateway =
        new RelayGateway() {
          public RelayPort.Batch consume(RelayPort.Consume value) {
            return delegate.consume(value);
          }

          public RelayPort.Published publish(RelayPort.Publish value) throws IOException {
            return delegate.publish(value);
          }

          public RelayPort.Acknowledged acknowledge(RelayPort.Ack value) throws IOException {
            return delegate.acknowledge(value);
          }

          public RelayLog.Page log(RelayLog.Query query) {
            cursors.add(query.after());
            var receipt = delegate.page("project", RelayAdapterTest.PUBLISHER).events().getFirst();
            boolean first = query.after().equals("0");
            var event =
                first
                    ? new RelayLog.Event(
                        "1",
                        "unrelated",
                        "other",
                        RelayAdapterTest.TIME,
                        RelayAdapterTest.TIME,
                        null,
                        null,
                        new RelayLog.Payload("TEXT", "unrelated", null, null, null),
                        null)
                    : new RelayLog.Event(
                        "2",
                        receipt.eventId(),
                        receipt.publisher(),
                        receipt.occurredAt(),
                        receipt.publishedAt(),
                        receipt.correlationId(),
                        receipt.causationId(),
                        receipt.payload(),
                        receipt.causation());
            return new RelayLog.Page(
                new RelayLog.Scope("project", false),
                new RelayLog.Topic(query.topic(), "TEXT", "345600", null, "2", "0"),
                query.after(),
                first ? "1" : "2",
                null,
                List.of(event),
                List.of(),
                List.of());
          }
        };
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker =
          new RelayAdapter(
              RelayAdapterTest.BINDING,
              gateway,
              journal,
              Map.of("peer", peer),
              () -> RelayAdapterTest.TIME);
      assertThrows(IOException.class, worker::tick);
      var result = worker.reconcileThroughHead("0", 10);
      assertEquals(List.of("0", "1"), cursors);
      assertTrue(result.reachedHead());
      assertEquals(1, result.confirmed());
      assertEquals(0, result.unresolved());
      assertEquals(1, peer.sends);
      assertEquals(1, delegate.publications.size());
    }
  }

  @Test
  void compaction_outlives_1024_requests_and_preserves_dedup_and_remote_ownership()
      throws Exception {
    String owner = "a".repeat(64);
    Map<String, RelayWork.Entry> records = new HashMap<>();
    for (int n = 0; n < 1024; n++) {
      UUID id = new UUID(0, n + 1);
      var entry =
          new RelayWork.Entry(
              id,
              "b".repeat(64),
              "source-" + n,
              RelayAdapterTest.PRODUCER,
              "peer",
              RelayWork.Action.SEND,
              null,
              null,
              RelayWork.Phase.CONFIRMED,
              RelayAdapterTest.FakePeer.observed("WORKING"),
              RelayAdapterTest.TIME,
              1,
              null);
      records.put(id.toString(), entry);
    }
    try (var journal = new FileDispatchJournal(directory, owner)) {
      journal.generation(RelayAdapterTest.GENERATION);
    }
    Files.write(
        directory.resolve("dispatch.json"),
        RelayWorkCodec.snapshot(
            new RelayWork.Snapshot(
                "plowshare-a2a-journal/2",
                owner,
                owner,
                null,
                null,
                RelayAdapterTest.GENERATION,
                records)));
    UUID retained = new UUID(0, 1);
    UUID fresh = UUID.randomUUID();
    try (var journal = new FileDispatchJournal(directory, owner)) {
      journal.save(
          new RelayWork.Entry(
              fresh,
              "c".repeat(64),
              "fresh",
              RelayAdapterTest.PRODUCER,
              "peer",
              RelayWork.Action.SEND,
              null,
              null,
              RelayWork.Phase.INTENT,
              null,
              RelayAdapterTest.TIME));
      assertEquals(1, journal.entries().size());
      assertTrue(journal.archived(retained).isPresent());
      assertTrue(
          journal.ownsRemote(RelayAdapterTest.PRODUCER, "peer", "remote-task", "remote-context"));
      assertFalse(journal.ownsRemote("other", "peer", "remote-task", "remote-context"));
      assertThrows(
          IOException.class,
          () ->
              journal.save(
                  new RelayWork.Entry(
                      retained,
                      "d".repeat(64),
                      "replay",
                      RelayAdapterTest.PRODUCER,
                      "peer",
                      RelayWork.Action.SEND,
                      null,
                      null,
                      RelayWork.Phase.INTENT,
                      null,
                      RelayAdapterTest.TIME)));
    }
    try (var journal = new FileDispatchJournal(directory, owner)) {
      assertTrue(journal.archived(retained).isPresent());
      assertTrue(journal.ownsRemote(RelayAdapterTest.PRODUCER, "peer", null, "remote-context"));
    }
  }

  @Test
  void interrupted_compaction_with_both_receipt_and_live_entry_resumes_without_loss()
      throws Exception {
    UUID id = UUID.randomUUID();
    var live =
        new RelayWork.Entry(
            id,
            "b".repeat(64),
            "source",
            RelayAdapterTest.PRODUCER,
            "peer",
            RelayWork.Action.SEND,
            null,
            null,
            RelayWork.Phase.CONFIRMED,
            RelayAdapterTest.FakePeer.observed("WORKING"),
            RelayAdapterTest.TIME,
            1,
            null);
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      journal.generation(RelayAdapterTest.GENERATION);
    }
    Files.write(
        directory.resolve("dispatch.json"),
        RelayWorkCodec.snapshot(
            new RelayWork.Snapshot(
                "plowshare-a2a-journal/2",
                "a".repeat(64),
                "a".repeat(64),
                null,
                null,
                RelayAdapterTest.GENERATION,
                Map.of(id.toString(), live))));
    Path receipt =
        directory.resolve("receipts").resolve(id.toString().substring(0, 2)).resolve(id + ".json");
    Files.createDirectories(receipt.getParent());
    Files.write(receipt, RelayWorkCodec.archived(RelayWork.Archived.of(live)));
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      assertEquals(1, journal.archiveConfirmed());
      assertTrue(journal.find(id).isEmpty());
      assertTrue(journal.archived(id).isPresent());
      assertTrue(
          journal.ownsRemote(RelayAdapterTest.PRODUCER, "peer", "remote-task", "remote-context"));
    }
  }

  @Test
  void configuration_rotation_is_explicit_and_cannot_retarget_identity_or_destinations()
      throws Exception {
    var oldPeer =
        new RelayConfiguration.Peer(
            "https://peer.example.invalid/rpc",
            null,
            "old-peer-secret",
            Duration.ofSeconds(20),
            "peer-account");
    var old =
        new RelayConfiguration(
            "https://server.example.invalid",
            "old-service-secret",
            directory,
            RelayAdapterTest.BINDING,
            Map.of("peer", oldPeer));
    var changed =
        new RelayConfiguration(
            old.origin(),
            "new-service-secret",
            directory,
            old.binding(),
            Map.of(
                "peer",
                new RelayConfiguration.Peer(
                    oldPeer.endpoint(),
                    null,
                    "new-peer-secret",
                    Duration.ofSeconds(40),
                    "peer-account")));
    assertEquals(old.identityFingerprint(), changed.identityFingerprint());
    assertFalse(old.fingerprint().equals(changed.fingerprint()));
    try (var journal =
        new FileDispatchJournal(
            directory, old.fingerprint(), old.identityFingerprint(), old.legacyFingerprint())) {
      journal.generation(RelayAdapterTest.GENERATION);
    }
    assertThrows(
        IOException.class,
        () ->
            new FileDispatchJournal(
                directory,
                changed.fingerprint(),
                changed.identityFingerprint(),
                changed.legacyFingerprint()));
    try (var journal =
        FileDispatchJournal.rotate(
            directory,
            changed.fingerprint(),
            changed.identityFingerprint(),
            changed.legacyFingerprint(),
            old.fingerprint())) {
      assertEquals(changed.fingerprint(), journal.ownerFingerprint());
    }
    var retargeted =
        new RelayConfiguration(
            changed.origin(),
            changed.token(),
            directory,
            changed.binding(),
            Map.of(
                "peer",
                new RelayConfiguration.Peer(
                    "https://other.example.invalid/rpc",
                    null,
                    "new-peer-secret",
                    Duration.ofSeconds(40),
                    "peer-account")));
    assertThrows(
        IOException.class,
        () ->
            FileDispatchJournal.rotate(
                directory,
                retargeted.fingerprint(),
                retargeted.identityFingerprint(),
                retargeted.legacyFingerprint(),
                changed.fingerprint()));
    var timeOnly =
        new RelayConfiguration(
            changed.origin(),
            changed.token(),
            directory,
            changed.binding(),
            Map.of(
                "peer",
                new RelayConfiguration.Peer(
                    oldPeer.endpoint(),
                    null,
                    "new-peer-secret",
                    Duration.ofSeconds(60),
                    "peer-account")));
    assertEquals(changed.fingerprint(), timeOnly.fingerprint());
    try (var journal =
        new FileDispatchJournal(
            directory,
            timeOnly.fingerprint(),
            timeOnly.identityFingerprint(),
            timeOnly.legacyFingerprint())) {
      assertEquals(changed.fingerprint(), journal.ownerFingerprint());
    }
  }

  @Test
  void legacy_journal_upgrade_requires_matching_old_configuration_before_rotation()
      throws Exception {
    String previous = "a".repeat(64), credential = "b".repeat(64), identity = "c".repeat(64);
    try (var journal = new FileDispatchJournal(directory, previous)) {
      journal.generation(RelayAdapterTest.GENERATION);
    }
    Files.writeString(
        directory.resolve("dispatch.json"),
        "{\"schema\":\"plowshare-a2a-journal/1\",\"owner\":\""
            + previous
            + "\",\"generation\":\""
            + RelayAdapterTest.GENERATION
            + "\",\"entries\":{}}");
    assertThrows(
        IOException.class,
        () -> new FileDispatchJournal(directory, credential, identity, "d".repeat(64)));
    try (var upgraded = new FileDispatchJournal(directory, credential, identity, previous)) {
      assertEquals(credential, upgraded.ownerFingerprint());
    }
    assertTrue(
        Files.readString(directory.resolve("dispatch.json")).contains("plowshare-a2a-journal/2"));
    try (var reopened = new FileDispatchJournal(directory, credential, identity, previous)) {
      assertEquals(credential, reopened.ownerFingerprint());
    }
  }

  @Test
  void first_send_unknown_does_not_fabricate_a_recoverable_remote_identity() throws Exception {
    var gateway = new RelayAdapterTest.FakeGateway();
    var peer = new RelayAdapterTest.FakePeer();
    peer.fail = true;
    var send = RelayAdapterTest.send();
    gateway.sources.add(RelayAdapterTest.event(send));
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = worker(journal, gateway, peer, RelayAdapterTest.TIME);
      assertTrue(worker.tick());
      var unknown = journal.find(send.requestId()).orElseThrow().observation();
      assertEquals("UNKNOWN", unknown.state());
      assertEquals(null, unknown.task());
      assertEquals(null, unknown.context());
      journal.archiveConfirmed();
      assertFalse(
          journal.ownsRemote(RelayAdapterTest.PRODUCER, "peer", "remote-task", "remote-context"));
      var read =
          new RelayWork.Request(
              "plowshare-a2a-dispatch/1",
              UUID.randomUUID(),
              "peer",
              RelayWork.Action.READ,
              null,
              "remote-task",
              "remote-context");
      gateway.sources.add(RelayAdapterTest.event(read));
      assertTrue(worker.tick());
      assertEquals(0, peer.reads);
      assertEquals(1, peer.sends);
    }
  }

  @Test
  void byte_pressure_compacts_settled_results_before_reserving_a_new_effect() throws Exception {
    String owner = "a".repeat(64);
    var part = ExternalMessage.Part.text("x".repeat(256 * 1024));
    var result =
        new ExternalResult.MessageResult(
            new ExternalMessage(
                "reply", "ROLE_AGENT", List.of(part, part, part), null, null, null, null));
    var observation = new RemotePeer.Observation("COMPLETED", null, null, result, null);
    var retained = new HashMap<String, RelayWork.Entry>();
    for (int n = 0; n < 33; n++) {
      boolean unsettled = n < 30;
      var id = new UUID(0, n + 1);
      retained.put(
          id.toString(),
          new RelayWork.Entry(
              id,
              "b".repeat(64),
              "source-" + n,
              RelayAdapterTest.PRODUCER,
              "peer",
              RelayWork.Action.SEND,
              null,
              null,
              unsettled ? RelayWork.Phase.INTENT : RelayWork.Phase.CONFIRMED,
              unsettled ? null : observation,
              RelayAdapterTest.TIME,
              unsettled ? 0 : 1,
              null));
    }
    try (var journal = new FileDispatchJournal(directory, owner)) {
      journal.generation(RelayAdapterTest.GENERATION);
    }
    byte[] large =
        RelayWorkCodec.snapshot(
            new RelayWork.Snapshot(
                "plowshare-a2a-journal/2",
                owner,
                owner,
                null,
                null,
                RelayAdapterTest.GENERATION,
                retained));
    assertTrue(
        large.length + 30L * RelayWorkCodec.MAX_ENTRY_BYTES < RelayWorkCodec.MAX_JOURNAL_BYTES);
    assertTrue(
        large.length + 31L * RelayWorkCodec.MAX_ENTRY_BYTES > RelayWorkCodec.MAX_JOURNAL_BYTES);
    Files.write(directory.resolve("dispatch.json"), large);
    try (var journal = new FileDispatchJournal(directory, owner)) {
      var id = UUID.randomUUID();
      journal.save(
          new RelayWork.Entry(
              id,
              "c".repeat(64),
              "fresh",
              RelayAdapterTest.PRODUCER,
              "peer",
              RelayWork.Action.SEND,
              null,
              null,
              RelayWork.Phase.INTENT,
              null,
              RelayAdapterTest.TIME));
      assertEquals(31, journal.entries().size());
      assertTrue(journal.archived(new UUID(0, 31)).isPresent());
      assertTrue(journal.find(id).isPresent());
    }
  }

  @Test
  void native_return_translates_full_observation_and_does_not_depend_on_retained_parent()
      throws Exception {
    var original = RelayAdapterTest.BINDING;
    var binding =
        new RelayAdapter.Binding(
            original.project(),
            original.requests(),
            original.replies(),
            original.group(),
            original.consumer(),
            original.replyPublisher(),
            original.producers(),
            original.maxDepth(),
            RelayAdapter.ReplyEncoding.MESSAGE_RETURN);
    var gateway = new RelayAdapterTest.FakeGateway();
    var peer = new RelayAdapterTest.FakePeer();
    var request = RelayAdapterTest.send();
    var source = RelayAdapterTest.event(request);
    gateway.sources.add(source);
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker =
          new RelayAdapter(
              binding, gateway, journal, Map.of("peer", peer), () -> RelayAdapterTest.TIME);
      assertTrue(worker.tick());
      var publication = gateway.publications.getFirst();
      var body = new ObjectMapper().readTree(publication.text());
      assertEquals("plowshare-message-return/1", body.get("schema").asText());
      assertEquals(source.eventId(), body.get("sourceEvent").asText());
      assertTrue(body.get("finalReply").booleanValue());
      assertEquals(
          RelayWorkCodec.writeReport(journal.find(request.requestId()).orElseThrow().report()),
          body.get("body").textValue());
      assertEquals(null, publication.parentTopic());
      assertEquals(null, publication.parentEventId());
      assertEquals(1, peer.sends);
    }
  }

  @Test
  void native_return_format_cannot_adopt_a_v1_observation_journal() throws Exception {
    var binding = RelayAdapterTest.BINDING;
    var peer =
        new RelayConfiguration.Peer(
            "https://peer.example.invalid/rpc", null, null, Duration.ofSeconds(20));
    var old =
        new RelayConfiguration(
            "https://server.example.invalid",
            "fixture-token",
            directory,
            binding,
            Map.of("peer", peer));
    var nativeBinding =
        new RelayAdapter.Binding(
            binding.project(),
            binding.requests(),
            binding.replies(),
            binding.group(),
            binding.consumer(),
            binding.replyPublisher(),
            binding.producers(),
            binding.maxDepth(),
            RelayAdapter.ReplyEncoding.MESSAGE_RETURN);
    var nativeConfig =
        new RelayConfiguration(old.origin(), old.token(), directory, nativeBinding, old.peers());
    assertFalse(old.legacyFingerprint().equals(nativeConfig.legacyFingerprint()));
    assertFalse(old.identityFingerprint().equals(nativeConfig.identityFingerprint()));
    try (var journal = new FileDispatchJournal(directory, old.legacyFingerprint())) {
      journal.generation(RelayAdapterTest.GENERATION);
    }
    Files.writeString(
        directory.resolve("dispatch.json"),
        "{\"schema\":\"plowshare-a2a-journal/1\",\"owner\":\""
            + old.legacyFingerprint()
            + "\",\"generation\":\""
            + RelayAdapterTest.GENERATION
            + "\",\"entries\":{}}");
    assertThrows(
        IOException.class,
        () ->
            new FileDispatchJournal(
                directory,
                nativeConfig.fingerprint(),
                nativeConfig.identityFingerprint(),
                nativeConfig.legacyFingerprint()));
    try (var journal =
        new FileDispatchJournal(
            directory, old.fingerprint(), old.identityFingerprint(), old.legacyFingerprint())) {
      assertEquals(old.fingerprint(), journal.ownerFingerprint());
    }
  }
}
