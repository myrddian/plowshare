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
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;

/**
 * Socket-owned snapshots with fixed ranges. The sender coalesces by subscription, never by account.
 */
@Component
public class UsageSubscriptions {
  public static final String UPDATED = "usage.updated";
  public static final String CLOSED = "usage.closed";

  private record Connection(
      String account, BiConsumer<String, Envelope> sink, Map<String, Subscription> active) {}

  private static final class Subscription {
    final String id;
    final Usage.Resolved query;
    Signature signature;
    long revision;
    boolean ready;

    Subscription(String id, Usage.Resolved query, Signature signature) {
      this.id = id;
      this.query = query;
      this.signature = signature;
    }
  }

  private final UsageReports queries;
  private final Map<String, Connection> connections = new HashMap<>();
  private ScheduledExecutorService timer;

  public UsageSubscriptions(UsageReports queries) {
    this.queries = queries;
  }

  @PostConstruct
  public void start() {
    timer =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("usage-snapshots").factory());
    timer.scheduleWithFixedDelay(this::publish, 1, 1, TimeUnit.SECONDS);
  }

  public synchronized void connect(String id, String account, BiConsumer<String, Envelope> sink) {
    connections.put(id, new Connection(account, sink, new LinkedHashMap<>()));
  }

  public synchronized void disconnect(String id) {
    connections.remove(id);
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

  public synchronized Usage.Initial subscribe(Asking asking, Usage.Resolved query) {
    if (!UsageQueryService.REPORTS.contains(query.type()) || query.filter().cursor() != null)
      throw new CallerFault("usage.subscribe takes an aggregate report without a cursor");
    Connection c = connection(asking);
    if (c.active.size() >= 8)
      throw new CallerFault("a socket can hold at most eight usage subscriptions");
    var report = queries.report(c.account, query);
    String id = UUID.randomUUID().toString();
    c.active.put(id, new Subscription(id, query, signature(report)));
    return new Usage.Initial(id, 0, query, report);
  }

  public synchronized void unsubscribe(Asking asking, String id) {
    Connection c = connection(asking);
    // An absent ID is idempotent. No lookup in another connection is performed.
    c.active.remove(id);
    c.sink.accept(id, null);
  }

  public void publish() {
    List<Map.Entry<String, Connection>> snapshot;
    synchronized (this) {
      snapshot = new ArrayList<>(connections.entrySet());
    }
    for (var entry : snapshot) {
      List<Subscription> subscriptions;
      synchronized (this) {
        subscriptions = new ArrayList<>(entry.getValue().active.values());
      }
      for (var sub : subscriptions) {
        synchronized (this) {
          if (!sub.ready) continue;
        }
        try {
          var report = queries.report(entry.getValue().account, sub.query);
          Signature signature = signature(report);
          synchronized (this) {
            Connection current = connections.get(entry.getKey());
            if (current != entry.getValue() || current.active.get(sub.id) != sub) continue;
            if (signature.equals(sub.signature)) continue;
            sub.signature = signature;
            current.sink.accept(
                sub.id,
                new Envelope(
                    null,
                    UPDATED,
                    Envelope.CURRENT_VERSION,
                    pushPayload(new Usage.Updated(sub.id, ++sub.revision, report))));
          }
        } catch (CallerFault revoked) {
          synchronized (this) {
            Connection current = connections.get(entry.getKey());
            if (current != entry.getValue() || current.active.remove(sub.id) != sub) continue;
            current.sink.accept(
                sub.id,
                new Envelope(
                    null,
                    CLOSED,
                    Envelope.CURRENT_VERSION,
                    pushPayload(new Usage.Closed(sub.id, "BAD_REQUEST"))));
          }
        } catch (RuntimeException unavailable) {
          // A transient query outage preserves the subscription and retries next tick. No error
          // text crosses the socket.
        }
      }
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
    connections.clear();
  }
}
