package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.AgentCard;
import io.aeyer.plowshare.protocol.ExternalMessage;
import io.aeyer.plowshare.protocol.ExternalResult;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.AgentCardCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayAdapterTest {
  static final Instant TIME = Instant.parse("2026-11-13T00:00:00Z");
  static final String CONSUMER = "11111111-1111-1111-1111-111111111111",
      GENERATION = "22222222-2222-2222-2222-222222222222";
  static final String PRODUCER = RelayPort.publisher("producer"),
      PUBLISHER = RelayPort.publisher("adapter");
  static final RelayAdapter.Binding BINDING =
      new RelayAdapter.Binding(
          "project",
          "adapter.requests",
          "adapter.replies",
          "adapter",
          "11111111-1111-1111-1111-111111111111",
          PUBLISHER,
          Set.of(PRODUCER),
          32);
  @TempDir Path directory;

  private RelayAdapter adapter(DispatchJournal journal, FakeGateway gateway, FakePeer peer) {
    return new RelayAdapter(
        BINDING, gateway, journal, Map.of("peer", peer, "other", new FakePeer()), () -> TIME);
  }

  static RelayWork.Request send() {
    return new RelayWork.Request(
        "plowshare-a2a-dispatch/1",
        UUID.randomUUID(),
        "peer",
        RelayWork.Action.SEND,
        ExternalMessage.text("  question\n"),
        null,
        null);
  }

  static RelayLog.Event event(RelayWork.Request request) throws IOException {
    return event(request, UUID.randomUUID().toString(), PRODUCER, RelayCausation.root("root"));
  }

  static RelayLog.Event event(
      RelayWork.Request request, String id, String publisher, RelayCausation cause)
      throws IOException {
    return new RelayLog.Event(
        "1",
        id,
        publisher,
        TIME,
        TIME,
        null,
        null,
        new RelayLog.Payload("TEXT", RelayWorkCodec.writeRequest(request), null, null, null),
        cause);
  }

  @Test
  void sends_once_publishes_typed_observation_then_acknowledges() throws Exception {
    var peer = new FakePeer();
    var gateway = new FakeGateway();
    var request = send();
    gateway.sources.add(event(request));
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = adapter(journal, gateway, peer);
      assertTrue(worker.tick());
      assertFalse(worker.tick());
      assertEquals(1, peer.sends);
      assertEquals(1, gateway.publications.size());
      assertEquals(1, gateway.acks);
      assertEquals(
          RelayWork.Phase.CONFIRMED, journal.find(request.requestId()).orElseThrow().phase());
      var reply = gateway.publications.getFirst();
      assertEquals(request.requestId().toString(), reply.correlationId());
      assertEquals(BINDING.requests(), reply.parentTopic());
      assertTrue(reply.text().contains("plowshare-a2a-observation/1"));
    }
  }

  @Test
  void restart_after_peer_effect_but_before_result_save_never_repeats_send() throws Exception {
    var gateway = new FakeGateway();
    var peer = new FakePeer();
    var request = send();
    gateway.sources.add(event(request));
    try (var stored = new FileDispatchJournal(directory, "a".repeat(64))) {
      var failing =
          new DispatchJournal() {
            public void generation(String g) throws IOException {
              stored.generation(g);
            }

            public Optional<RelayWork.Entry> find(UUID id) {
              return stored.find(id);
            }

            public List<RelayWork.Entry> entries() {
              return stored.entries();
            }

            public void save(RelayWork.Entry e) throws IOException {
              if (e.phase() == RelayWork.Phase.OBSERVED)
                throw new IOException("crash after peer response");
              stored.save(e);
            }

            public void close() {}
          };
      assertThrows(IOException.class, () -> adapter(failing, gateway, peer).tick());
      assertEquals(1, peer.sends);
    }
    try (var recovered = new FileDispatchJournal(directory, "a".repeat(64))) {
      assertTrue(adapter(recovered, gateway, peer).tick());
      assertEquals(1, peer.sends);
      assertEquals(
          "UNKNOWN", recovered.find(request.requestId()).orElseThrow().observation().state());
      assertEquals(1, gateway.acks);
    }
  }

  @Test
  void crash_after_intent_before_send_reports_unknown_without_first_send() throws Exception {
    var gateway = new FakeGateway();
    var peer = new FakePeer();
    var request = send();
    var source = event(request);
    gateway.sources.add(source);
    try (var stored = new FileDispatchJournal(directory, "a".repeat(64))) {
      stored.generation(GENERATION);
      stored.save(
          new RelayWork.Entry(
              request.requestId(),
              RelayPort.hash(RelayWorkCodec.writeRequest(request)),
              source.eventId(),
              source.publisher(),
              "peer",
              RelayWork.Action.SEND,
              null,
              null,
              RelayWork.Phase.INTENT,
              null,
              TIME));
    }
    try (var recovered = new FileDispatchJournal(directory, "a".repeat(64))) {
      assertTrue(adapter(recovered, gateway, peer).tick());
      assertEquals(0, peer.sends);
      assertEquals(
          "UNKNOWN", recovered.find(request.requestId()).orElseThrow().observation().state());
    }
  }

  @Test
  void uncertain_reply_requires_exact_read_only_reconciliation_without_republication()
      throws Exception {
    var gateway = new FakeGateway();
    var peer = new FakePeer();
    var request = send();
    gateway.sources.add(event(request));
    gateway.loseReply = true;
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = adapter(journal, gateway, peer);
      assertThrows(IOException.class, worker::tick);
      assertThrows(IOException.class, worker::tick);
      assertEquals(1, peer.sends);
      assertEquals(1, gateway.publications.size());
      assertEquals(0, gateway.acks);
      assertThrows(IOException.class, () -> worker.reconcile(gateway.page("foreign", PUBLISHER)));
      assertThrows(IOException.class, () -> worker.reconcile(gateway.page("project", PRODUCER)));
      assertEquals(1, worker.reconcile(gateway.page("project", PUBLISHER)));
      gateway.loseReply = false;
      assertTrue(worker.tick());
      assertEquals(1, peer.sends);
      assertEquals(1, gateway.publications.size());
      assertEquals(1, gateway.acks);
    }
  }

  @Test
  void lost_ack_and_duplicate_source_never_repeat_external_effects() throws Exception {
    for (boolean committed : List.of(false, true)) {
      Path path = directory.resolve(Boolean.toString(committed));
      var gateway = new FakeGateway();
      var peer = new FakePeer();
      gateway.sources.add(event(send()));
      gateway.failAck = true;
      gateway.commitAck = committed;
      try (var journal = new FileDispatchJournal(path, "a".repeat(64))) {
        assertThrows(IOException.class, () -> adapter(journal, gateway, peer).tick());
      }
      gateway.failAck = false;
      try (var journal = new FileDispatchJournal(path, "a".repeat(64))) {
        adapter(journal, gateway, peer).tick();
        assertEquals(1, peer.sends);
        assertEquals(1, gateway.publications.size());
        assertTrue(gateway.sources.isEmpty());
      }
    }
  }

  @Test
  void foreign_publishers_are_skipped_and_invalid_own_requests_and_effect_depth_stop_before_io()
      throws Exception {
    var gateway = new FakeGateway();
    var peer = new FakePeer();
    gateway.sources.add(
        new RelayLog.Event(
            "1",
            "foreign",
            "foreign",
            TIME,
            TIME,
            null,
            null,
            new RelayLog.Payload("EMPTY", null, null, null, null),
            null));
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = adapter(journal, gateway, peer);
      assertTrue(worker.tick());
      assertEquals(0, peer.sends);
      gateway.sources.add(
          event(send(), "deep", PRODUCER, new RelayCausation("root", "parent", 32)));
      assertThrows(IOException.class, worker::tick);
      assertEquals(0, peer.cards);
      assertEquals(1, gateway.acks);
    }
  }

  @Test
  void remote_task_and_context_are_bound_to_the_observed_peer_and_uuid_reuse_conflicts()
      throws Exception {
    var gateway = new FakeGateway();
    var peer = new FakePeer();
    var request = send();
    gateway.sources.add(event(request));
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = adapter(journal, gateway, peer);
      worker.tick();
      var read =
          new RelayWork.Request(
              "plowshare-a2a-dispatch/1",
              UUID.randomUUID(),
              "peer",
              RelayWork.Action.READ,
              null,
              "remote-task",
              "remote-context");
      gateway.sources.add(event(read));
      worker.tick();
      assertEquals(1, peer.reads);
      var cancel =
          new RelayWork.Request(
              "plowshare-a2a-dispatch/1",
              UUID.randomUUID(),
              "peer",
              RelayWork.Action.CANCEL,
              null,
              "remote-task",
              "remote-context");
      gateway.sources.add(event(cancel));
      worker.tick();
      assertEquals(1, peer.cancels);
      gateway.sources.add(
          event(
              new RelayWork.Request(
                  "plowshare-a2a-dispatch/1",
                  UUID.randomUUID(),
                  "other",
                  RelayWork.Action.READ,
                  null,
                  "remote-task",
                  "remote-context")));
      assertThrows(IOException.class, worker::tick);
      gateway.sources.clear();
      gateway.sources.add(event(request));
      assertThrows(IOException.class, worker::tick);
      assertEquals(1, peer.sends);
    }
  }

  @Test
  void peer_uncertainty_is_reported_as_unknown_and_is_not_a_reconnect_or_retry_signal()
      throws Exception {
    var gateway = new FakeGateway();
    var peer = new FakePeer();
    peer.fail = true;
    var request = send();
    gateway.sources.add(event(request));
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      assertTrue(adapter(journal, gateway, peer).tick());
      assertEquals(1, peer.sends);
      assertEquals(
          "UNKNOWN", journal.find(request.requestId()).orElseThrow().observation().state());
    }
  }

  @Test
  void another_authorized_producer_cannot_adopt_observed_remote_tasks() throws Exception {
    String otherProducer = RelayPort.publisher("other-producer");
    var binding =
        new RelayAdapter.Binding(
            BINDING.project(),
            BINDING.requests(),
            BINDING.replies(),
            BINDING.group(),
            BINDING.consumer(),
            BINDING.replyPublisher(),
            Set.of(PRODUCER, otherProducer),
            32);
    var gateway = new FakeGateway();
    var peer = new FakePeer();
    gateway.sources.add(event(send()));
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      var worker = new RelayAdapter(binding, gateway, journal, Map.of("peer", peer), () -> TIME);
      worker.tick();
      var read =
          new RelayWork.Request(
              "plowshare-a2a-dispatch/1",
              UUID.randomUUID(),
              "peer",
              RelayWork.Action.READ,
              null,
              "remote-task",
              "remote-context");
      gateway.sources.add(
          event(read, "another-source", otherProducer, RelayCausation.root("root")));
      assertThrows(IOException.class, worker::tick);
      assertEquals(0, peer.reads);
      assertEquals(1, peer.cards);
    }
  }

  static final class FakePeer implements RemotePeer {
    int sends, reads, cancels, cards;
    boolean fail;

    @Override
    public AgentCard agentCard() throws IOException {
      cards++;
      return AgentCardCodec.read(
          "{\"name\":\"peer\",\"description\":\"peer\",\"version\":\"1\",\"defaultInputModes\":[\"text/plain\"],\"defaultOutputModes\":[\"text/plain\"],\"capabilities\":{},\"skills\":[],\"supportedInterfaces\":[{\"url\":\"https://peer.example.invalid/rpc\",\"protocolBinding\":\"JSONRPC\",\"protocolVersion\":\"1.0\"}]}");
    }

    @Override
    public Observation send(UUID id, ExternalMessage message) throws IOException {
      sends++;
      if (fail) throw new IOException("secret remote body must not enter report");
      return observed("WORKING");
    }

    @Override
    public Observation status(String task, String context) {
      reads++;
      return observed("WORKING");
    }

    @Override
    public Observation cancel(String task, String context) {
      cancels++;
      return observed("CANCELED");
    }

    @Override
    public void close() {}

    static Observation observed(String state) {
      return new Observation(
          state,
          "remote-task",
          "remote-context",
          new ExternalResult.TaskResult(
              new ExternalResult.Task(
                  "remote-task",
                  "remote-context",
                  new ExternalResult.Status("TASK_STATE_" + state, null, null),
                  null,
                  null,
                  null)),
          null);
    }
  }

  static final class FakeGateway implements RelayGateway {
    final ArrayDeque<RelayLog.Event> sources = new ArrayDeque<>();
    final List<RelayPort.Publish> publications = new ArrayList<>();
    int acks;
    boolean loseReply, failAck, commitAck;

    @Override
    public RelayPort.Batch consume(RelayPort.Consume request) {
      boolean empty = sources.isEmpty();
      return new RelayPort.Batch(
          "project",
          BINDING.requests(),
          BINDING.group(),
          CONSUMER,
          empty ? RelayPort.Status.EMPTY : RelayPort.Status.DATA,
          empty ? null : GENERATION,
          empty ? null : "1",
          empty ? "0" : "1",
          empty ? null : TIME.plusSeconds(30),
          null,
          empty ? List.of() : List.of(sources.getFirst()),
          GENERATION);
    }

    @Override
    public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
      publications.add(request);
      if (loseReply) throw new IOException("lost publication acknowledgement");
      return new RelayPort.Published(
          request.requestId(),
          request.project(),
          request.topic(),
          Integer.toString(publications.size()),
          TIME);
    }

    @Override
    public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
      if (!failAck || commitAck) sources.removeFirst();
      if (failAck) throw new IOException("lost acknowledgement");
      acks++;
      return new RelayPort.Acknowledged(
          request.project(), request.topic(), request.group(), request.batchId(), "1", false);
    }

    @Override
    public RelayLog.Page log(RelayLog.Query request) {
      return page(request.project(), PUBLISHER);
    }

    RelayLog.Page page(String project, String publisher) {
      var reply = publications.getLast();
      return new RelayLog.Page(
          new RelayLog.Scope(project, false),
          new RelayLog.Topic(BINDING.replies(), "TEXT", "345600", null, "1", "0"),
          "0",
          "1",
          null,
          List.of(
              new RelayLog.Event(
                  "1",
                  reply.requestId(),
                  publisher,
                  reply.occurredAt(),
                  TIME,
                  reply.correlationId(),
                  reply.parentEventId(),
                  new RelayLog.Payload("TEXT", reply.text(), null, null, null),
                  RelayCausation.root("root").next(reply.parentEventId(), 32))),
          List.of(),
          List.of());
    }
  }
}
