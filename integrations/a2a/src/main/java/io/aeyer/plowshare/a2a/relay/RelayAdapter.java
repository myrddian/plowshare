package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Relay is the queue; the adapter journal fences external effects across lease expiry/restart. One
 * dispatcher owns one journal and topic/group binding. Calls are serialized per dispatcher.
 */
public final class RelayAdapter {
  public record Binding(
      String project,
      String requests,
      String replies,
      String group,
      String consumer,
      String replyPublisher,
      Set<String> producers,
      int maxDepth) {
    public Binding {
      RelayPort.identity(project);
      RelayPort.topic(requests);
      RelayPort.topic(replies);
      RelayPort.group(group);
      if (!UUID.fromString(consumer).toString().equals(consumer))
        throw new IllegalArgumentException("Canonical consumer UUID required");
      if (requests.equals(replies)
          || replyPublisher == null
          || !replyPublisher.matches("sdk:[a-f0-9]{64}")
          || maxDepth < 1
          || maxDepth > 32) throw new IllegalArgumentException("Invalid A2A Relay binding");
      producers = Set.copyOf(producers);
      if (producers.isEmpty() || producers.size() > 64)
        throw new IllegalArgumentException("Explicit producer allowlist required");
      producers.forEach(RelayPort::identity);
    }
  }

  private final Binding binding;
  private final RelayGateway relay;
  private final DispatchJournal journal;
  private final Map<String, RemotePeer> peers;
  private final Supplier<Instant> clock;

  public RelayAdapter(
      Binding binding,
      RelayGateway relay,
      DispatchJournal journal,
      Map<String, RemotePeer> peers,
      Supplier<Instant> clock) {
    this.binding = Objects.requireNonNull(binding);
    this.relay = Objects.requireNonNull(relay);
    this.journal = Objects.requireNonNull(journal);
    this.peers = Map.copyOf(peers);
    this.clock = Objects.requireNonNull(clock);
    if (peers.isEmpty() || peers.size() > 64)
      throw new IllegalArgumentException("Configure 1..64 peers");
    peers.keySet().forEach(RelayWork::alias);
  }

  /**
   * One bounded pass. Any exception stops the owner; storage or publication failure needs
   * reconciliation.
   */
  public synchronized boolean tick() throws IOException {
    if (journal.entries().stream().anyMatch(e -> e.phase() == RelayWork.Phase.REPLY_INTENT))
      throw new IOException(
          "A2A reply publication is unconfirmed; reconcile retained Relay log before continuing");
    var batch =
        relay.consume(
            new RelayPort.Consume(
                binding.project(),
                binding.requests(),
                binding.group(),
                binding.consumer(),
                RelayPort.Start.OLDEST_RETAINED,
                1,
                1000));
    if (batch.status() == RelayPort.Status.BUSY) return false;
    journal.generation(batch.generation());
    if (batch.status() == RelayPort.Status.GAP)
      throw new IOException("A2A request retention gap; explicit recovery required");
    if (batch.status() == RelayPort.Status.EMPTY) return false;
    if (batch.events().size() != 1)
      throw new IOException("A2A source exceeded requested batch bound");
    var event = batch.events().getFirst();
    if (binding.producers().contains(event.publisher())) dispatch(event);
    relay.acknowledge(
        new RelayPort.Ack(
            binding.project(),
            binding.requests(),
            binding.group(),
            binding.consumer(),
            batch.batchId(),
            batch.fence(),
            null));
    return true;
  }

  private void dispatch(RelayLog.Event event) throws IOException {
    if (!event.payload().kind().equals("TEXT")
        || event.causation() == null
        || event.causation().depth() < 0
        || event.causation().depth() >= binding.maxDepth())
      throw new IOException("A2A source has no supported text/effect allowance");
    var request = RelayWorkCodec.request(event.payload().text());
    var peer = peers.get(request.peer());
    if (peer == null) throw new IOException("A2A request names an unconfigured peer");
    String hash = RelayPort.hash(RelayWorkCodec.writeRequest(request));
    var retained = journal.find(request.requestId());
    RelayWork.Entry entry;
    if (retained.isPresent()) {
      entry = retained.orElseThrow();
      if (!entry.hash().equals(hash)
          || !entry.sourceEvent().equals(event.eventId())
          || !entry.producer().equals(event.publisher()))
        throw new IOException("A2A request UUID was reused with different content or source");
      if (entry.phase() == RelayWork.Phase.INTENT) {
        entry =
            entry.observed(
                new RemotePeer.Observation(
                    "UNKNOWN",
                    entry.task(),
                    entry.context(),
                    null,
                    "External dispatch was interrupted; no send or cancel was replayed."));
        journal.save(entry);
      }
    } else {
      requireRemoteOwnership(request, event.publisher());
      // Discovery is read-only and precedes the durable effect marker; an unavailable card cannot
      // consume the sole dispatch attempt or cause an unvalidated endpoint to receive a message.
      peer.agentCard();
      String task = request.message() == null ? request.task() : request.message().taskId();
      String context =
          request.message() == null ? request.context() : request.message().contextId();
      entry =
          new RelayWork.Entry(
              request.requestId(),
              hash,
              event.eventId(),
              event.publisher(),
              request.peer(),
              request.action(),
              task,
              context,
              RelayWork.Phase.INTENT,
              null,
              clock.get());
      journal.save(entry);
      RemotePeer.Observation observed;
      try {
        observed =
            switch (request.action()) {
              case SEND -> peer.send(request.requestId(), request.message());
              case READ -> peer.status(request.task(), request.context());
              case CANCEL -> peer.cancel(request.task(), request.context());
            };
      } catch (IOException | RuntimeException unknown) {
        observed =
            new RemotePeer.Observation(
                "UNKNOWN",
                task,
                context,
                null,
                "External outcome is unconfirmed; the operation was not replayed.");
      }
      // A crash here leaves INTENT. Recovery must report uncertainty even if the peer completed.
      entry = entry.observed(observed);
      journal.save(entry);
    }
    if (entry.phase() == RelayWork.Phase.CONFIRMED) return;
    if (entry.phase() != RelayWork.Phase.OBSERVED)
      throw new IOException("A2A reply needs explicit reconciliation");
    String report = RelayWorkCodec.writeReport(entry.report());
    entry = entry.advance(RelayWork.Phase.REPLY_INTENT);
    journal.save(entry);
    var receipt = relay.publish(publication(entry, report));
    if (!receipt.requestId().equals(entry.replyId())
        || !receipt.project().equals(binding.project())
        || !receipt.topic().equals(binding.replies()))
      throw new IOException("Foreign A2A reply receipt");
    journal.save(entry.advance(RelayWork.Phase.CONFIRMED));
  }

  private void requireRemoteOwnership(RelayWork.Request request, String producer)
      throws IOException {
    String task = request.message() == null ? request.task() : request.message().taskId();
    String context = request.message() == null ? request.context() : request.message().contextId();
    if (task == null && context == null) return;
    if (context == null
        || journal.entries().stream()
            .noneMatch(
                e -> {
                  var observed = e.observation();
                  return e.producer().equals(producer)
                      && e.peer().equals(request.peer())
                      && observed != null
                      && observed.result() != null
                      && context.equals(observed.context())
                      && (task == null || task.equals(observed.task()));
                }))
      throw new IOException("Remote task/context was not observed for this configured peer");
  }

  private RelayPort.Publish publication(RelayWork.Entry entry, String report) {
    return new RelayPort.Publish(
        entry.replyId(),
        binding.project(),
        binding.replies(),
        report,
        entry.occurredAt(),
        entry.requestId().toString(),
        binding.requests(),
        entry.sourceEvent());
  }

  /**
   * Read-only server reconciliation; only matching retained reply evidence can release a source
   * ack.
   */
  public synchronized int reconcile(RelayLog.Page page) throws IOException {
    if (page.scope().system()
        || !Objects.equals(page.scope().project(), binding.project())
        || !page.topic().name().equals(binding.replies()))
      throw new IOException("Foreign A2A reconciliation page");
    int confirmed = 0;
    for (var entry : journal.entries()) {
      if (entry.phase() != RelayWork.Phase.REPLY_INTENT) continue;
      var expected = publication(entry, RelayWorkCodec.writeReport(entry.report()));
      var match =
          page.events().stream().filter(e -> e.eventId().equals(entry.replyId())).findFirst();
      if (match.isEmpty()) continue;
      var event = match.orElseThrow();
      if (!event.publisher().equals(binding.replyPublisher())
          || !event.payload().kind().equals("TEXT")
          || !event.payload().text().equals(expected.text())
          || !event.occurredAt().equals(expected.occurredAt())
          || !Objects.equals(event.correlationId(), expected.correlationId())
          || !Objects.equals(event.causationId(), expected.parentEventId()))
        throw new IOException("Conflicting A2A reply evidence");
      journal.save(entry.advance(RelayWork.Phase.CONFIRMED));
      confirmed++;
    }
    return confirmed;
  }
}
