package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.a2a.IngressRefused;
import io.aeyer.plowshare.a2a.Receiver;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * SDK-only admission through configured general Relay receivers. The journal remembers publication
 * intent and A2A provenance, while authoritative task state always comes from messaging snapshots.
 * Repeated HTTP requests inspect uncertain submissions; they never blindly repeat a mutation.
 */
public final class RelayIngressBridge implements Receiver.Bridge {
  @FunctionalInterface
  public interface Catalog {
    Ingress.Catalog read() throws IOException;
  }

  public record Binding(
      String project,
      String agent,
      String requests,
      String replies,
      String group,
      String consumer,
      String publisher,
      long waitMillis) {
    public Binding {
      RelayPort.identity(project);
      RelayPort.identity(agent);
      RelayPort.topic(requests);
      RelayPort.topic(replies);
      RelayPort.group(group);
      IngressWire.uuid(consumer);
      RelayPort.identity(publisher);
      if (requests.equals(replies)
          || !publisher.equals("messaging:ingress")
          || waitMillis < 1
          || waitMillis > 300000)
        throw new IllegalArgumentException("Invalid receiving Relay binding");
    }
  }

  /** Bounded process-local diagnostics with no body or publisher labels. */
  public record Diagnostics(long foreignSnapshots, long unownedSnapshots, long retentionGaps) {}

  private static final System.Logger LOG = System.getLogger(RelayIngressBridge.class.getName());
  private long foreignSnapshots;
  private long unownedSnapshots;
  private long retentionGaps;

  public synchronized Diagnostics diagnostics() {
    return new Diagnostics(foreignSnapshots, unownedSnapshots, retentionGaps);
  }

  private static long increment(long value) {
    return value == Long.MAX_VALUE ? value : value + 1;
  }

  private static void notice(String category, long total) {
    if (total == 1 || total % 1000 == 0)
      LOG.log(System.Logger.Level.WARNING, "A2A receiving " + category + "; aggregate=" + total);
  }

  private record Awaited(IngressJournal.Projection projection, String next, String resume) {}

  private final Binding binding;
  private final RelayGateway relay;
  private final IngressJournal journal;
  private final Catalog catalog;
  private final Supplier<Instant> clock;

  public RelayIngressBridge(
      Binding binding,
      RelayGateway relay,
      IngressJournal journal,
      Catalog catalog,
      Supplier<Instant> clock) {
    this.binding = Objects.requireNonNull(binding);
    this.relay = Objects.requireNonNull(relay);
    this.journal = Objects.requireNonNull(journal);
    this.catalog = Objects.requireNonNull(catalog);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Ingress.Catalog catalog() throws IOException {
    return catalog.read();
  }

  @Override
  public Ingress.Task receive(Ingress.Receive request) throws IOException {
    return receive(request, 200);
  }

  @Override
  public synchronized Ingress.Task receive(Ingress.Receive request, int history)
      throws IOException {
    if (!binding.project().equals(request.project())
        || !binding.agent().equals(request.agent())
        || request.source() == null) throw new IngressRefused("INVALID_REQUEST");
    String wire =
        IngressCodec.write(
            new IngressWire.Receive(
                "plowshare-message-request/1",
                request.requestId().toString(),
                request.client(),
                string(request.context()),
                request.body(),
                request.command()));
    String hash = RelayPort.hash(wire + "\n" + IngressCodec.write(request.source()));
    var imported = journal.importedExternal(request.requestId());
    if (imported.isEmpty()) imported = journal.imported(request.client(), request.requestId());
    if (imported.isPresent()) {
      var old = imported.orElseThrow();
      if (!old.client().equals(request.client())
          || !old.agent().equals(request.agent())
          || !old.originalContextKnown()
          || old.source() == null
          || !old.source().equals(request.source())) throw new IngressRefused("REQUEST_CONFLICT");
      var current = inspect(request.client(), old.task(), null);
      if (!current.task().requestId().equals(old.originalRequest().toString()))
        throw new IOException("Historical source no longer matches its native request");
      return projected(request.client(), current, history);
    }
    var stored = journal.submission(request.requestId());
    if (stored.isPresent()) {
      var original = stored.orElseThrow();
      if (!original.hash().equals(hash)
          || !original.client().equals(request.client())
          || original.action() != IngressWire.Action.RECEIVE)
        throw new IngressRefused("REQUEST_CONFLICT");
      if (original.refusal() != null) throw new IngressRefused(original.refusal());
      if (original.phase() != IngressJournal.Phase.READY) {
        // A read-only lookup of the retained request proves identity without another publication.
        var recovered = inspect(request.client(), null, request.requestId());
        journal.save(original.settled(snapshotFor(original, recovered)));
        return projected(request.client(), recovered, history);
      }
    }
    if (journal.ambiguousClient(request.client()))
      throw new IngressRefused(
          "Historical original context is unknown; fresh admission for this client requires a deliberately new binding");
    var operation =
        stored.orElseGet(
            () ->
                submission(
                    request.requestId(),
                    IngressWire.Action.RECEIVE,
                    request.client(),
                    hash,
                    wire,
                    null,
                    request.requestId(),
                    request.context(),
                    request.source()));
    if (stored.isEmpty()) journal.save(operation);
    submit(operation);
    return projected(
        operation.client(), completePages(operation.client(), await(operation)), history);
  }

  @Override
  public Ingress.Task status(Ingress.Id id) throws IOException {
    return status(id, 200);
  }

  @Override
  public synchronized Ingress.Task status(Ingress.Id id, int history) throws IOException {
    scope(id);
    return projected(id.client(), inspect(id.client(), id.id(), null), history);
  }

  @Override
  public Ingress.Task cancel(Ingress.Id id) throws IOException {
    return cancel(id, 200);
  }

  @Override
  public synchronized Ingress.Task cancel(Ingress.Id id, int history) throws IOException {
    scope(id);
    UUID request =
        UUID.nameUUIDFromBytes(
            ("a2a-cancel/1:"
                    + binding.project()
                    + ":"
                    + binding.requests()
                    + ":"
                    + id.client()
                    + ":"
                    + id.id())
                .getBytes(StandardCharsets.UTF_8));
    String wire =
        IngressCodec.write(
            new IngressWire.Cancel(
                "plowshare-message-cancel/1", request.toString(), id.client(), id.id().toString()));
    var previous = journal.submission(request);
    if (previous.isPresent()) {
      var operation = previous.orElseThrow();
      if (!operation.client().equals(id.client()) || !operation.wire().equals(wire))
        throw new IngressRefused("REQUEST_CONFLICT");
      if (operation.refusal() != null) throw new IngressRefused(operation.refusal());
      if (operation.phase() != IngressJournal.Phase.READY) {
        var current = inspect(id.client(), id.id(), null);
        if (current.task().state().equals("CANCELED"))
          return projected(id.client(), current, history);
        throw new IOException("Cancellation delivery is unconfirmed; no mutation was repeated");
      }
    }
    var operation =
        previous.orElseGet(
            () ->
                submission(
                    request,
                    IngressWire.Action.CANCEL,
                    id.client(),
                    RelayPort.hash(wire),
                    wire,
                    id.id(),
                    null,
                    null,
                    null));
    if (previous.isEmpty()) journal.save(operation);
    submit(operation);
    return projected(
        operation.client(), completePages(operation.client(), await(operation)), history);
  }

  /** Read-only authoritative evidence used by the explicit local provenance importer. */
  public synchronized IngressJournal.Projection inspectTask(Ingress.Id id) throws IOException {
    scope(id);
    return inspect(id.client(), id.id(), null);
  }

  private IngressJournal.Projection inspect(String client, UUID task, UUID original)
      throws IOException {
    UUID known = task;
    if (known == null && original != null)
      known = journal.submission(original).map(IngressJournal.Submission::task).orElse(null);
    String cursor =
        known == null
            ? null
            : journal
                .projection(known)
                .filter(value -> value.client().equals(client))
                .map(value -> value.next() != null ? value.next() : value.resume())
                .orElse(null);
    return completePages(client, inspectPage(client, task, original, cursor));
  }

  private Awaited inspectPage(String client, UUID task, UUID original, String cursor)
      throws IOException {
    UUID request = UUID.randomUUID();
    String wire =
        IngressCodec.write(
            new IngressWire.Inspect(
                "plowshare-message-inspect/1",
                request.toString(),
                client,
                string(task),
                string(original),
                cursor));
    var operation =
        submission(
            request,
            IngressWire.Action.INSPECT,
            client,
            RelayPort.hash(wire),
            wire,
            task,
            original,
            null,
            null);
    journal.save(operation);
    submit(operation);
    return await(operation);
  }

  private IngressJournal.Projection completePages(String client, Awaited first) throws IOException {
    var page = first;
    long deadline = System.nanoTime() + binding.waitMillis() * 1_000_000L;
    for (int count = 0; ; count++) {
      // Persist fixed-head progress even when this bounded call cannot fetch another page. A
      // restart must continue unread chunks rather than repeatedly scanning the same prefix.
      var checkpoint =
          journal.checkpoint(
              page.projection(), page.next(), page.resume(), page.projection().terminalFollowed());
      String cursor = page.next();
      if (cursor == null) {
        boolean terminal =
            switch (checkpoint.task().state()) {
              case "COMPLETED", "FAILED", "CANCELED" -> true;
              default -> false;
            };
        if (!terminal || checkpoint.terminalFollowed()) return checkpoint;
        // Headers are current while reply pages belong to a captured head. A terminal header
        // therefore needs one new FOLLOW capture, whose head includes that terminal transaction.
        // Save its read intent as next first, so restart cannot emit a result without this read.
        checkpoint = journal.checkpoint(checkpoint, page.resume(), null, true);
        cursor = checkpoint.next();
      }
      if (count >= 10000 || System.nanoTime() >= deadline)
        throw new IOException(
            "Task history paging exceeded its bounded deadline; retained chunks remain recoverable");
      page = inspectPage(client, UUID.fromString(checkpoint.task().id()), null, cursor);
    }
  }

  private IngressJournal.Submission submission(
      UUID request,
      IngressWire.Action action,
      String client,
      String hash,
      String wire,
      UUID task,
      UUID original,
      UUID context,
      Ingress.Source source) {
    String event =
        UUID.nameUUIDFromBytes(
                ("a2a-ingress-publication/1:"
                        + binding.project()
                        + ":"
                        + binding.requests()
                        + ":"
                        + request)
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    return new IngressJournal.Submission(
        request,
        action,
        client,
        hash,
        wire,
        event,
        clock.get(),
        IngressJournal.Phase.READY,
        task,
        original,
        context,
        source,
        null,
        null,
        null);
  }

  private void submit(IngressJournal.Submission operation) throws IOException {
    if (operation.phase() != IngressJournal.Phase.READY)
      throw new IOException("Uncertain ingress mutation requires inspection");
    // Capture the actual return incarnation before committing any publication intent. This read
    // is a freshness fence, not absence evidence; an unknown send is still recovered by inspection.
    var boundary =
        relay.log(new RelayLog.Query(binding.project(), false, binding.replies(), null, 1));
    if (boundary.scope().system()
        || !binding.project().equals(boundary.scope().project())
        || !binding.replies().equals(boundary.topic().name())
        || !"TEXT".equals(boundary.topic().kind())
        || boundary.topic().generation() == null)
      throw new IOException("Return topic is unavailable or differs from its configured binding");
    journal.returnBoundary(boundary.topic().generation(), boundary.topic().through());
    var intent = operation.phase(IngressJournal.Phase.INTENT);
    journal.save(intent);
    try {
      var receipt =
          relay.publish(
              new RelayPort.Publish(
                  operation.event(),
                  binding.project(),
                  binding.requests(),
                  operation.wire(),
                  operation.occurredAt(),
                  operation.request().toString(),
                  null,
                  null));
      if (!receipt.requestId().equals(operation.event())
          || !receipt.project().equals(binding.project())
          || !receipt.topic().equals(binding.requests()))
        throw new IOException("Foreign ingress publication receipt");
      journal.save(intent.phase(IngressJournal.Phase.SUBMITTED));
    } catch (RelayGateway.PublicationNotSubmitted known) {
      journal.save(intent.phase(IngressJournal.Phase.READY));
      throw new IOException("Ingress publication was not submitted; identical retry remains safe");
    }
  }

  private Awaited await(IngressJournal.Submission operation) throws IOException {
    long deadline = System.nanoTime() + binding.waitMillis() * 1_000_000L;
    while (System.nanoTime() < deadline) {
      var retained = journal.submission(operation.request()).orElseThrow();
      if (retained.phase() == IngressJournal.Phase.SETTLED) {
        if (retained.refusal() != null) throw new IngressRefused(retained.refusal());
        var projection =
            journal
                .projection(retained.task())
                .orElseThrow(() -> new IOException("Task receipt has no retained projection"));
        return new Awaited(projection, retained.next(), retained.resume());
      }
      consume();
    }
    throw new IOException(
        "Messaging response is unresolved; inspect retained intent before any mutation retry");
  }

  private void consume() throws IOException {
    var batch =
        relay.consume(
            new RelayPort.Consume(
                binding.project(),
                binding.replies(),
                binding.group(),
                binding.consumer(),
                RelayPort.Start.OLDEST_RETAINED,
                100,
                Math.min(1000, (int) binding.waitMillis())));
    if (batch.status() == RelayPort.Status.BUSY || batch.status() == RelayPort.Status.EMPTY) {
      if (batch.generation() != null) journal.generation(batch.generation());
      return;
    }
    journal.generation(batch.generation());
    if (batch.status() == RelayPort.Status.GAP) {
      journal.observedGap(batch.expiredThrough());
      retentionGaps = increment(retentionGaps);
      notice("return retention gap requires authoritative inspection", retentionGaps);
    } else
      for (var event : batch.events()) {
        if (!event.publisher().equals(binding.publisher())) {
          foreignSnapshots = increment(foreignSnapshots);
          notice("ignored foreign snapshot", foreignSnapshots);
          continue;
        }
        if (!event.payload().kind().equals("TEXT"))
          throw new IOException("Messaging return uses an unsupported carrier");
        var snapshot = IngressCodec.snapshot(event.payload().text());
        var retained = journal.submission(UUID.fromString(snapshot.requestId()));
        if (retained.isEmpty()) {
          unownedSnapshots = increment(unownedSnapshots);
          notice("ignored unowned snapshot", unownedSnapshots);
          continue;
        }
        var operation = retained.orElseThrow();
        if (!operation.client().equals(snapshot.client())
            || !operation.event().equals(snapshot.sourceEvent()))
          throw new IOException("Foreign messaging response correlation");
        if (snapshot.task() != null) validate(operation, snapshot.task());
        if (snapshot.task() != null)
          journal.project(snapshot.client(), Long.parseLong(snapshot.revision()), snapshot.task());
        journal.save(operation.settled(snapshot));
      }
    // Every projection/evidence write lands first. Losing this acknowledgement safely replays
    // views.
    relay.acknowledge(
        new RelayPort.Ack(
            binding.project(),
            binding.replies(),
            binding.group(),
            binding.consumer(),
            batch.batchId(),
            batch.fence(),
            batch.status() == RelayPort.Status.GAP ? batch.expiredThrough() : null));
  }

  private void validate(IngressJournal.Submission operation, IngressWire.Task task)
      throws IOException {
    if (!binding.agent().equals(task.agent())
        || operation.task() != null && !operation.task().toString().equals(task.id())
        || operation.originalRequest() != null
            && !operation.originalRequest().toString().equals(task.requestId())
        || operation.context() != null && !operation.context().toString().equals(task.context()))
      throw new IOException("Foreign authoritative task snapshot");
  }

  private Ingress.Task projected(String client, IngressJournal.Projection projection, int history)
      throws IOException {
    if (!projection.client().equals(client)) throw new IngressRefused("NOT_ACCESSIBLE");
    var original = journal.submission(UUID.fromString(projection.task().requestId()));
    var source =
        original
            .filter(
                value ->
                    value.client().equals(client) && value.action() == IngressWire.Action.RECEIVE)
            .map(IngressJournal.Submission::source)
            .orElse(null);
    if (source == null) {
      var historical = journal.imported(client, UUID.fromString(projection.task().requestId()));
      if (historical.isPresent()) {
        var imported = historical.orElseThrow();
        if (!imported.client().equals(client)
            || !imported.task().toString().equals(projection.task().id())
            || !imported.context().toString().equals(projection.task().context())
            || !imported.message().equals(projection.task().message()))
          throw new IOException("Historical source ownership differs from the inspected task");
        source = imported.source();
      }
    }
    return projection.task().projection(source, journal.replies(projection, Math.max(1, history)));
  }

  private IngressWire.Snapshot snapshotFor(
      IngressJournal.Submission operation, IngressJournal.Projection projection) {
    return new IngressWire.Snapshot(
        "plowshare-message-task/1",
        operation.request().toString(),
        operation.event(),
        operation.client(),
        Long.toString(projection.revision()),
        projection.task(),
        null,
        null,
        projection.resume());
  }

  private void scope(Ingress.Id id) throws IngressRefused {
    if (!binding.project().equals(id.project())) throw new IngressRefused("NOT_ACCESSIBLE");
  }

  private static String string(UUID value) {
    return value == null ? null : value.toString();
  }
}
