package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.Usage;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageQueryService;
import io.aeyer.plowshare.server.llm.accounting.UsageReports;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;

/**
 * Socket-owned delivery with fixed ranges. One publish pass loads each effective-principal/query
 * snapshot once; cursors, revisions, acknowledgments and disconnect fences remain socket-owned. A
 * bounded rotating group budget prevents arbitrary sockets from multiplying work per tick.
 */
@Component
public class UsageSubscriptions {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(UsageSubscriptions.class);
  public static final String UPDATED = "usage.updated";
  public static final String CLOSED = "usage.closed";

  private record Connection(
      String account,
      BiConsumer<String, Envelope> sink,
      Map<String, Subscription> active,
      ReentrantLock emitting) {}

  private static final class Subscription {
    final String id;
    final Usage.Resolved query;
    Signature signature;
    long revision;
    boolean ready;
    io.aeyer.plowshare.server.reads.ReadAdmission.Lease budget;

    void release() {
      if (budget != null) budget.close();
    }

    Subscription(String id, Usage.Resolved query, Signature signature) {
      this.id = id;
      this.query = query;
      this.signature = signature;
    }
  }

  private final UsageReports queries;
  private final io.aeyer.plowshare.server.reads.ReadAdmission admission;
  private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();
  private ScheduledExecutorService timer;
  private final java.util.concurrent.atomic.AtomicBoolean publishing =
      new java.util.concurrent.atomic.AtomicBoolean();
  // Accessed only by the elected publisher; at most one entry per live bounded subscription.
  private final LinkedHashSet<Group> rotation = new LinkedHashSet<>();
  private static final int GROUPS_PER_TICK = 8;

  private record Group(String account, Usage.Resolved query) {}

  private record Target(String socket, Connection connection, Subscription subscription) {}

  public UsageSubscriptions(UsageReports queries) {
    this(queries, io.aeyer.plowshare.server.reads.ReadAdmission.direct());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public UsageSubscriptions(
      UsageReports queries, io.aeyer.plowshare.server.reads.ReadAdmission admission) {
    this.queries = Objects.requireNonNull(queries);
    this.admission = Objects.requireNonNull(admission);
  }

  @PostConstruct
  public void start() {
    timer =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("usage-snapshots").factory());
    timer.scheduleWithFixedDelay(this::publish, 1, 1, TimeUnit.SECONDS);
  }

  public synchronized void connect(String id, String account, BiConsumer<String, Envelope> sink) {
    connections.put(
        id, new Connection(account, sink, new ConcurrentHashMap<>(), new ReentrantLock()));
  }

  public void disconnect(String id) {
    Connection removed;
    synchronized (this) {
      removed = connections.remove(id);
    }
    if (removed != null) removed.active.values().forEach(Subscription::release);
  }

  public synchronized void ready(String id, String subscription) {
    var connection = connections.get(id);
    if (connection != null && connection.active.containsKey(subscription))
      connection.active.get(subscription).ready = true;
  }

  private Connection connection(Asking asking) {
    Connection c = connections.get(asking.connectionId());
    if (c == null || !Objects.equals(c.account, asking.requireHandle("usage.subscribe")))
      throw new CallerFault("usage subscriptions need this live socket");
    return c;
  }

  public Usage.Initial subscribe(Asking asking, Usage.Resolved query) {
    if (!UsageQueryService.REPORTS.contains(query.type()) || query.filter().cursor() != null)
      throw new CallerFault("usage.subscribe takes an aggregate report without a cursor");
    String id = UUID.randomUUID().toString();
    var reserved = new Subscription(id, query, null);
    Connection c;
    reserved.budget =
        admission.admit(
            asking.requireHandle("usage.subscribe"),
            io.aeyer.plowshare.server.reads.ReadAdmission.Work.SUBSCRIPTION);
    try {
      synchronized (this) {
        c = connection(asking);
        if (c.active.size() >= 8)
          throw new CallerFault("a socket can hold at most eight usage subscriptions");
        c.active.put(id, reserved);
      }
    } catch (RuntimeException | Error failed) {
      reserved.release();
      throw failed;
    }
    try {
      // Report IO is outside the service-wide monitor. The pending reservation counts toward
      // capacity and cannot become ready if disconnect/unsubscribe wins while this query runs.
      var report = queries.refresh(c.account, query);
      synchronized (this) {
        if (connections.get(asking.connectionId()) != c || c.active.get(id) != reserved)
          throw new CallerFault("usage subscription connection ended");
        reserved.signature = signature(report);
      }
      return new Usage.Initial(id, 0, query, report);
    } catch (RuntimeException | Error failure) {
      c.active.remove(id, reserved);
      reserved.release();
      throw failure;
    }
  }

  public void unsubscribe(Asking asking, String id) {
    Connection c;
    Subscription removed;
    synchronized (this) {
      c = connection(asking);
      // An absent ID is idempotent. No lookup in another connection is performed.
      removed = c.active.remove(id);
    }
    if (removed != null) removed.release();
    // Serialize the queue-only sink with publish so its tombstone cannot be overtaken by a
    // stale in-flight report. No report IO or external callback runs under the registry monitor.
    c.emitting.lock();
    try {
      c.sink.accept(id, null);
    } finally {
      c.emitting.unlock();
    }
  }

  public void publish() {
    if (!publishing.compareAndSet(false, true)) return;
    try {
      var groups = new LinkedHashMap<Group, List<Target>>();
      synchronized (this) {
        for (var entry : connections.entrySet()) {
          var connection = entry.getValue();
          for (var sub : connection.active.values()) {
            if (sub.ready)
              groups
                  .computeIfAbsent(
                      new Group(connection.account, sub.query), ignored -> new ArrayList<>())
                  .add(new Target(entry.getKey(), connection, sub));
          }
        }
      }
      rotation.retainAll(groups.keySet());
      rotation.addAll(groups.keySet());
      var selected = rotation.stream().limit(GROUPS_PER_TICK).toList();
      for (var group : selected) {
        rotation.remove(group);
        rotation.add(group);
        Usage.Report report;
        try {
          // No registry lock or database permit survives between groups. Current policy is checked
          // by the same authorized repository as foreground reads, on every pass (not a TTL).
          report = queries.refresh(group.account, group.query);
        } catch (CallerFault revoked) {
          for (var target : groups.get(group)) emit(() -> revoked(target));
          continue;
        } catch (RuntimeException unavailable) {
          // Bounded background admission yields; subscriptions retain their last snapshot.
          continue;
        }
        var signature = signature(report);
        for (var target : groups.get(group)) emit(() -> updated(target, report, signature));
      }
    } finally {
      publishing.set(false);
    }
  }

  private static void emit(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException failed) {
      // One transport callback cannot terminate the shared periodic publisher. Its connection
      // owner handles the failed delivery; do not log its payload, principal or exception message.
      LOG.warn("Usage subscription delivery failed ({})", failed.getClass().getSimpleName());
    }
  }

  private void updated(Target target, Usage.Report report, Signature signature) {
    var connection = target.connection;
    var sub = target.subscription;
    connection.emitting.lock();
    try {
      Envelope update;
      synchronized (this) {
        if (connections.get(target.socket) != connection || connection.active.get(sub.id) != sub)
          return;
        if (signature.equals(sub.signature)) return;
        sub.signature = signature;
        update =
            new Envelope(
                null,
                UPDATED,
                Envelope.CURRENT_VERSION,
                pushPayload(new Usage.Updated(sub.id, ++sub.revision, report)));
      }
      connection.sink.accept(sub.id, update);
    } finally {
      connection.emitting.unlock();
    }
  }

  private void revoked(Target target) {
    var connection = target.connection;
    var sub = target.subscription;
    connection.emitting.lock();
    try {
      Envelope closed;
      synchronized (this) {
        if (connections.get(target.socket) != connection || !connection.active.remove(sub.id, sub))
          return;
        sub.release();
        closed =
            new Envelope(
                null,
                CLOSED,
                Envelope.CURRENT_VERSION,
                pushPayload(new Usage.Closed(sub.id, "BAD_REQUEST")));
      }
      connection.sink.accept(sub.id, closed);
    } finally {
      connection.emitting.unlock();
    }
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

  // Envelope is the transport's raw representation; subscriptions and consumers use DTOs.
  private static Map<String, Object> pushPayload(io.aeyer.plowshare.protocol.ServerPush update) {
    return JSON.convertValue(update, new com.fasterxml.jackson.core.type.TypeReference<>() {});
  }

  private record HealthSignal(
      java.time.Instant trackingStartedAt,
      String watermark,
      String historicalUsage,
      boolean captureEnabled,
      String pendingEvents,
      String journalBytes,
      String journalCapacityBytes,
      String journalProblem,
      String projectionLagMillis,
      java.time.Instant oldestPendingAt,
      Integer bufferedTerminalEvents,
      String lostTerminalEvents,
      Boolean projectionConflict,
      String projectionProblem) {}

  private record Signature(
      Usage.Aggregate totals, List<Usage.Aggregate> groups, HealthSignal health) {
    private Signature {
      groups = List.copyOf(groups);
    }
  }

  private static Signature signature(Usage.Report report) {
    var h = report.health();
    return new Signature(
        report.totals(),
        report.groups(),
        new HealthSignal(
            h.trackingStartedAt(),
            h.watermark(),
            h.historicalUsage(),
            h.captureEnabled(),
            h.pendingEvents(),
            h.journalBytes(),
            h.journalCapacityBytes(),
            h.journalProblem(),
            h.projectionLagMillis(),
            h.oldestPendingAt(),
            h.bufferedTerminalEvents(),
            h.lostTerminalEvents(),
            h.projectionConflict(),
            h.projectionProblem()));
  }

  @PreDestroy
  public synchronized void close() {
    if (timer != null) timer.shutdownNow();
    connections
        .values()
        .forEach(connection -> connection.active.values().forEach(Subscription::release));
    connections.clear();
  }
}
