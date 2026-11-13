package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Relay orders requests; the single-owner journal fences external effects across restart. Definite
 * refusals produce durable negative observations rather than poisoning the queue. Unknown effects,
 * corrupt storage, changed broker generations and gaps still require operator intervention.
 */
public final class RelayAdapter {
  public enum ReplyEncoding {
    A2A_OBSERVATION,
    MESSAGE_RETURN
  }

  public record Binding(
      String project,
      String requests,
      String replies,
      String group,
      String consumer,
      String replyPublisher,
      Set<String> producers,
      int maxDepth,
      ReplyEncoding encoding) {
    public Binding(
        String project,
        String requests,
        String replies,
        String group,
        String consumer,
        String replyPublisher,
        Set<String> producers,
        int maxDepth) {
      this(
          project,
          requests,
          replies,
          group,
          consumer,
          replyPublisher,
          producers,
          maxDepth,
          ReplyEncoding.A2A_OBSERVATION);
    }

    public Binding {
      Objects.requireNonNull(encoding);
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

  /** Read-only discovery is retried finitely; no mutation ever uses this retry policy. */
  public record DiscoveryPolicy(int attempts, Duration backoff) {
    public DiscoveryPolicy {
      if (attempts < 1
          || attempts > 8
          || backoff.isNegative()
          || backoff.compareTo(Duration.ofSeconds(30)) > 0)
        throw new IllegalArgumentException("Invalid discovery retry policy");
    }
  }

  @FunctionalInterface
  interface Pause {
    void await(Duration duration) throws InterruptedException;
  }

  public record Diagnostics(
      long foreignPublications, long rejectedRequests, long discoveryFailures) {}

  public record Reconciliation(
      int confirmed, String next, String head, boolean reachedHead, int unresolved) {}

  /**
   * Safe to retry publication after backoff: the transport positively proved it was not submitted.
   */
  public static final class ReplyNotSubmitted extends IOException {
    ReplyNotSubmitted() {
      super("A2A reply was not submitted; retry is safe without repeating peer work");
    }
  }

  private static final System.Logger LOG = System.getLogger(RelayAdapter.class.getName());
  private final Binding binding;
  private final RelayGateway relay;
  private final DispatchJournal journal;
  private final Map<String, RemotePeer> peers;
  private final Supplier<Instant> clock;
  private final DiscoveryPolicy discovery;
  private final Pause pause;
  private long foreignPublications;
  private long rejectedRequests;
  private long discoveryFailures;

  public RelayAdapter(
      Binding binding,
      RelayGateway relay,
      DispatchJournal journal,
      Map<String, RemotePeer> peers,
      Supplier<Instant> clock) {
    this(
        binding,
        relay,
        journal,
        peers,
        clock,
        new DiscoveryPolicy(3, Duration.ofMillis(250)),
        duration -> Thread.sleep(duration.toMillis()));
  }

  RelayAdapter(
      Binding binding,
      RelayGateway relay,
      DispatchJournal journal,
      Map<String, RemotePeer> peers,
      Supplier<Instant> clock,
      DiscoveryPolicy discovery,
      Pause pause) {
    this.binding = Objects.requireNonNull(binding);
    this.relay = Objects.requireNonNull(relay);
    this.journal = Objects.requireNonNull(journal);
    this.peers = Map.copyOf(peers);
    this.clock = Objects.requireNonNull(clock);
    this.discovery = Objects.requireNonNull(discovery);
    this.pause = Objects.requireNonNull(pause);
    if (peers.isEmpty() || peers.size() > 64)
      throw new IllegalArgumentException("Configure 1..64 peers");
    peers.keySet().forEach(RelayWork::alias);
  }

  public synchronized Diagnostics diagnostics() {
    return new Diagnostics(foreignPublications, rejectedRequests, discoveryFailures);
  }

  /**
   * One bounded pass; uncertainty and storage failures stop the owner, never replay a peer effect.
   */
  public synchronized boolean tick() throws IOException {
    if (journal.entries().stream().anyMatch(e -> e.phase() == RelayWork.Phase.REPLY_INTENT))
      throw new IOException(
          "A2A reply publication is unconfirmed; reconcile or explicitly abandon it before continuing");
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
    else {
      foreignPublications = increment(foreignPublications);
      // Aggregate only: a bad producer cannot create per-publisher labels or disclose its body.
      if (foreignPublications == 1 || foreignPublications % 1000 == 0)
        LOG.log(
            System.Logger.Level.WARNING,
            "A2A ignored publications from unconfigured producers; total=" + foreignPublications);
    }
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
    if (!event.payload().kind().equals("TEXT")) {
      reject(event, "UNSUPPORTED_CARRIER", "_invalid");
      return;
    }
    if (event.causation() == null
        || event.causation().depth() < 0
        || event.causation().depth() >= binding.maxDepth()) {
      reject(event, "EFFECT_ALLOWANCE_UNAVAILABLE", "_invalid");
      return;
    }
    RelayWork.Request request;
    try {
      request = RelayWorkCodec.request(event.payload().text());
    } catch (IOException invalid) {
      reject(event, "INVALID_REQUEST", "_invalid");
      return;
    }
    var peer = peers.get(request.peer());
    if (peer == null) {
      reject(event, "UNKNOWN_PEER", request.peer());
      return;
    }
    String hash = RelayPort.hash(RelayWorkCodec.writeRequest(request));
    var archived = journal.archived(request.requestId());
    if (archived.isPresent()) {
      var retained = archived.orElseThrow();
      if (!retained.hash().equals(hash)
          || !retained.sourceEvent().equals(event.eventId())
          || !retained.producer().equals(event.publisher()))
        reject(event, "REQUEST_ID_CONFLICT", request.peer());
      return;
    }
    var retained = journal.find(request.requestId());
    RelayWork.Entry entry;
    if (retained.isPresent()) {
      entry = retained.orElseThrow();
      if (!entry.hash().equals(hash)
          || !entry.sourceEvent().equals(event.eventId())
          || !entry.producer().equals(event.publisher())) {
        reject(event, "REQUEST_ID_CONFLICT", request.peer());
        return;
      }
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
      if (!ownsRemote(request, event.publisher())) {
        reject(event, "UNOBSERVED_REMOTE_IDENTITY", request.peer());
        return;
      }
      if (!discover(peer)) {
        reject(event, "PEER_DISCOVERY_UNAVAILABLE", request.peer());
        return;
      }
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
      entry = entry.observed(observed);
      journal.save(entry);
    }
    publish(entry);
  }

  private boolean discover(RemotePeer peer) throws IOException {
    for (int attempt = 0; attempt < discovery.attempts(); attempt++) {
      try {
        Objects.requireNonNull(peer.agentCard(), "Peer discovery requires a validated card");
        return true;
      } catch (IOException unavailable) {
        discoveryFailures = increment(discoveryFailures);
        if (attempt + 1 < discovery.attempts()) {
          try {
            pause.await(discovery.backoff().multipliedBy(attempt + 1));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("A2A discovery interrupted before dispatch");
          }
        }
      }
    }
    return false;
  }

  private void reject(RelayLog.Event event, String category, String peer) throws IOException {
    String hash =
        RelayPort.hash(event.payload().kind() + ":" + Objects.toString(event.payload().text(), ""));
    for (int salt = 0; salt < 32; salt++) {
      UUID identity =
          UUID.nameUUIDFromBytes(
              ("a2a-rejection/1:"
                      + binding.project()
                      + ":"
                      + binding.requests()
                      + ":"
                      + event.publisher()
                      + ":"
                      + event.eventId()
                      + ":"
                      + hash
                      + ":"
                      + salt)
                  .getBytes(StandardCharsets.UTF_8));
      var archived = journal.archived(identity);
      if (archived.isPresent()) {
        var previous = archived.orElseThrow();
        if (previous.hash().equals(hash)
            && previous.sourceEvent().equals(event.eventId())
            && previous.producer().equals(event.publisher())) return;
        continue;
      }
      var retained = journal.find(identity);
      RelayWork.Entry entry;
      if (retained.isPresent()) {
        entry = retained.orElseThrow();
        if (!entry.hash().equals(hash)
            || !entry.sourceEvent().equals(event.eventId())
            || !entry.producer().equals(event.publisher())) continue;
      } else {
        entry =
            new RelayWork.Entry(
                identity,
                hash,
                event.eventId(),
                event.publisher(),
                peer,
                RelayWork.Action.SEND,
                null,
                null,
                RelayWork.Phase.OBSERVED,
                new RemotePeer.Observation("REJECTED", null, null, null, category),
                clock.get());
        journal.save(entry);
        rejectedRequests = increment(rejectedRequests);
      }
      publish(entry);
      return;
    }
    throw new IOException("A2A rejection identity capacity exhausted");
  }

  private void publish(RelayWork.Entry entry) throws IOException {
    if (entry.terminal()) return;
    if (entry.phase() != RelayWork.Phase.OBSERVED)
      throw new IOException("A2A reply needs explicit reconciliation");
    String report = report(entry);
    entry = entry.advance(RelayWork.Phase.REPLY_INTENT);
    journal.save(entry);
    RelayPort.Published receipt;
    try {
      receipt = relay.publish(publication(entry, report));
    } catch (RelayGateway.PublicationNotSubmitted proven) {
      journal.save(entry.notSubmitted());
      throw new ReplyNotSubmitted();
    }
    if (!receipt.requestId().equals(entry.replyId())
        || !receipt.project().equals(binding.project())
        || !receipt.topic().equals(binding.replies()))
      throw new IOException("Foreign A2A reply receipt");
    journal.save(entry.advance(RelayWork.Phase.CONFIRMED));
  }

  private boolean ownsRemote(RelayWork.Request request, String producer) throws IOException {
    String task = request.message() == null ? request.task() : request.message().taskId();
    String context = request.message() == null ? request.context() : request.message().contextId();
    if (task == null && context == null) return true;
    return context != null && journal.ownsRemote(producer, request.peer(), task, context);
  }

  private String report(RelayWork.Entry entry) throws IOException {
    String body = RelayWorkCodec.writeReport(entry.report());
    if (binding.encoding() == ReplyEncoding.A2A_OBSERVATION) return body;
    // The final flag settles this dispatch-observation request. The body preserves remote
    // WORKING/UNKNOWN exactly; it never declares that the remote task or source run completed.
    return IngressCodec.write(
        new IngressWire.Return(
            "plowshare-message-return/1", entry.replyId(), entry.sourceEvent(), body, true));
  }

  private RelayPort.Publish publication(RelayWork.Entry entry, String report) {
    // Definite rejection is a diagnostic, not execution. It must also be deliverable for exhausted
    // or absent ancestry; correlation/source fields remain explicit in its typed body.
    boolean rejection =
        entry.rejection()
            || entry.observation().state().equals("REJECTED")
                && entry.observation().result() == null;
    return new RelayPort.Publish(
        entry.replyId(),
        binding.project(),
        binding.replies(),
        report,
        entry.occurredAt(),
        entry.requestId().toString(),
        rejection || binding.encoding() == ReplyEncoding.MESSAGE_RETURN ? null : binding.requests(),
        rejection || binding.encoding() == ReplyEncoding.MESSAGE_RETURN
            ? null
            : entry.sourceEvent());
  }

  /**
   * Explicit operator choice to lose an unconfirmed reply; never asserts delivery or repeats I/O.
   */
  public synchronized void abandonReply(UUID requestId, String evidence) throws IOException {
    var entry =
        journal.find(requestId).orElseThrow(() -> new IOException("No retained uncertain reply"));
    journal.save(entry.abandon(evidence));
  }

  /** Positive retained evidence only: absence, a gap or expiry never proves non-delivery. */
  public synchronized int reconcile(RelayLog.Page page) throws IOException {
    if (page.scope().system()
        || !Objects.equals(page.scope().project(), binding.project())
        || !page.topic().name().equals(binding.replies()))
      throw new IOException("Foreign A2A reconciliation page");
    int confirmed = 0;
    for (var entry : journal.entries()) {
      if (entry.phase() != RelayWork.Phase.REPLY_INTENT) continue;
      var expected = publication(entry, report(entry));
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

  /**
   * Bounded paging to the initially captured head. Positive matches commit; missing ones stay
   * unknown.
   */
  public synchronized Reconciliation reconcileThroughHead(String after, int maximumPages)
      throws IOException {
    if (maximumPages < 1 || maximumPages > 1000)
      throw new IllegalArgumentException("Use 1..1000 reconciliation pages");
    String cursor = after, head = null, generation = null;
    int confirmed = 0;
    boolean reached = false;
    for (int pageNumber = 0; pageNumber < maximumPages; pageNumber++) {
      var page =
          relay.log(new RelayLog.Query(binding.project(), false, binding.replies(), cursor, 100));
      if (!page.after().equals(cursor)) throw new IOException("Foreign A2A reconciliation cursor");
      if (head == null) {
        head = page.topic().through();
        generation = page.topic().generation();
      } else if (!Objects.equals(generation, page.topic().generation()))
        throw new IOException("A2A reply topic incarnation changed during reconciliation");
      confirmed += reconcile(page);
      String next = page.next();
      if (Long.parseLong(next) >= Long.parseLong(head)) {
        cursor = next;
        reached = true;
        break;
      }
      if (Long.parseLong(next) <= Long.parseLong(cursor))
        throw new IOException("Reply log scan made no progress; missing receipts remain unknown");
      cursor = next;
    }
    int unresolved =
        (int)
            journal.entries().stream()
                .filter(e -> e.phase() == RelayWork.Phase.REPLY_INTENT)
                .count();
    return new Reconciliation(confirmed, cursor, head, reached, unresolved);
  }

  private static long increment(long count) {
    return count == Long.MAX_VALUE ? count : count + 1;
  }
}
