package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Usage;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageReports;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UsageSubscriptionCoordinationTest {
  @Test
  void subscriptionCapacityIsSharedAcrossSocketsAndReleasedOnEveryRemoval() {
    var reports = mock(UsageReports.class);
    var query = new Usage.Resolved("usage.models", mock(Usage.Filter.class));
    var report = mock(Usage.Report.class);
    when(report.filters()).thenReturn(query);
    when(report.health()).thenReturn(mock(Usage.Health.class));
    when(report.groups()).thenReturn(List.of());
    when(reports.report("alice", query)).thenReturn(report);
    when(reports.refresh("alice", query))
        .thenThrow(new io.aeyer.plowshare.server.reads.ReadUnavailableException());
    var limits =
        new io.aeyer.plowshare.server.reads.ReadCapacityProperties(4, 2, 2, 1, 8, 2, 16, 8, 4, 2);
    var admission = new io.aeyer.plowshare.server.reads.BoundedReadAdmission(limits);
    var subscriptions = new UsageSubscriptions(reports, admission);
    subscriptions.connect("one", "alice", (id, body) -> {});
    subscriptions.connect("two", "alice", (id, body) -> {});
    subscriptions.connect("three", "alice", (id, body) -> {});
    var first = subscriptions.subscribe(new Asking("session", "alice", "one"), query);
    var second = subscriptions.subscribe(new Asking("session", "alice", "two"), query);
    assertThrows(
        io.aeyer.plowshare.server.reads.ReadUnavailableException.class,
        () -> subscriptions.subscribe(new Asking("session", "alice", "three"), query));
    verify(reports, times(2)).report("alice", query);
    verify(reports, never()).refresh(anyString(), any());
    subscriptions.unsubscribe(new Asking("session", "alice", "one"), first.subscription());
    subscriptions.subscribe(new Asking("session", "alice", "three"), query);
    assertEquals(
        2, admission.active(io.aeyer.plowshare.server.reads.ReadAdmission.Work.SUBSCRIPTION));
    subscriptions.disconnect("two");
    assertEquals(
        1, admission.active(io.aeyer.plowshare.server.reads.ReadAdmission.Work.SUBSCRIPTION));
    subscriptions.close();
    assertEquals(
        0, admission.active(io.aeyer.plowshare.server.reads.ReadAdmission.Work.SUBSCRIPTION));
  }

  @Test
  void oneAuthorizedSnapshotPerPrincipalAndSelectionFansOutWithIndependentSocketRevisions() {
    var reports = mock(UsageReports.class);
    var query = query("usage.models");
    var otherQuery = query("usage.pools");
    when(reports.report(anyString(), any())).thenAnswer(call -> report(call.getArgument(1), 0));
    when(reports.refresh(anyString(), any())).thenAnswer(call -> report(call.getArgument(1), 0));
    var subscriptions = new UsageSubscriptions(reports);
    var updates =
        new java.util.concurrent.CopyOnWriteArrayList<
            io.aeyer.plowshare.protocol.frames.Envelope>();
    for (int i = 0; i < 16; i++) {
      String socket = "socket-" + i;
      subscriptions.connect(
          socket,
          i == 15 ? "bob" : "alice",
          (id, body) -> {
            if (body != null) updates.add(body);
          });
      var receipt =
          subscriptions.subscribe(
              new Asking("session", i == 15 ? "bob" : "alice", socket),
              i == 14 ? otherQuery : query);
      subscriptions.ready(socket, receipt.subscription());
    }
    clearInvocations(reports);
    doAnswer(
            call ->
                report(
                    call.getArgument(1), call.getArgument(0, String.class).equals("bob") ? 2 : 1))
        .when(reports)
        .refresh(anyString(), any());
    subscriptions.publish();
    verify(reports, times(1)).refresh("alice", query);
    verify(reports, times(1)).refresh("alice", otherQuery);
    verify(reports, times(1)).refresh("bob", query);
    assertEquals(16, updates.size());
    var ids = new java.util.HashSet<Object>();
    for (var envelope : updates) {
      assertEquals(1L, ((Number) envelope.payload().get("revision")).longValue());
      ids.add(envelope.payload().get("subscription"));
    }
    assertEquals(16, ids.size(), "Delivery identity must never be coalesced across sockets");
    updates.clear();
    when(reports.refresh("alice", query)).thenThrow(new CallerFault("revoked"));
    subscriptions.publish();
    assertEquals(14, updates.size());
    assertTrue(
        updates.stream().allMatch(envelope -> envelope.type().equals(UsageSubscriptions.CLOSED)));
    subscriptions.close();
  }

  @Test
  void concurrentPublishCoalescesWhileUnsubscribeAndDisconnectFenceLateSharedResults()
      throws Exception {
    var reports = mock(UsageReports.class);
    var query = query("usage.models");
    when(reports.report(anyString(), any())).thenReturn(report(query, 0));
    when(reports.refresh(anyString(), any())).thenReturn(report(query, 0));
    var subscriptions = new UsageSubscriptions(reports);
    var delivered = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var initial = new java.util.HashMap<String, Usage.Initial>();
    for (String socket : List.of("unsubscribed", "disconnected", "retained")) {
      subscriptions.connect(
          socket,
          "alice",
          (id, body) -> delivered.add(socket + (body == null ? ":tombstone" : ":update")));
      var receipt = subscriptions.subscribe(new Asking("session", "alice", socket), query);
      initial.put(socket, receipt);
      subscriptions.ready(socket, receipt.subscription());
    }
    var entered = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    doAnswer(
            call -> {
              entered.complete(null);
              release.get(5, TimeUnit.SECONDS);
              return report(query, 1);
            })
        .when(reports)
        .refresh("alice", query);
    clearInvocations(reports);
    try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var publishing = callers.submit(subscriptions::publish);
      entered.get(5, TimeUnit.SECONDS);
      subscriptions.publish();
      subscriptions.unsubscribe(
          new Asking("session", "alice", "unsubscribed"),
          initial.get("unsubscribed").subscription());
      subscriptions.disconnect("disconnected");
      release.complete(null);
      publishing.get(5, TimeUnit.SECONDS);
      verify(reports, times(1)).refresh("alice", query);
      assertEquals(List.of("unsubscribed:tombstone", "retained:update"), delivered);
    } finally {
      release.complete(null);
      subscriptions.close();
    }
  }

  @Test
  void aBrokenSocketCallbackCannotStopOtherSocketsOrThePeriodicPublisher() {
    var reports = mock(UsageReports.class);
    var query = query("usage.models");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(reports.report(anyString(), any())).thenAnswer(call -> report(query, calls.get()));
    when(reports.refresh(anyString(), any())).thenAnswer(call -> report(query, calls.get()));
    var subscriptions = new UsageSubscriptions(reports);
    var delivered = new java.util.ArrayList<io.aeyer.plowshare.protocol.frames.Envelope>();
    subscriptions.connect(
        "broken",
        "alice",
        (id, body) -> {
          throw new IllegalStateException("private details");
        });
    subscriptions.connect("retained", "alice", (id, body) -> delivered.add(body));
    for (String socket : List.of("broken", "retained")) {
      var receipt = subscriptions.subscribe(new Asking("session", "alice", socket), query);
      subscriptions.ready(socket, receipt.subscription());
    }
    calls.incrementAndGet();
    assertDoesNotThrow(subscriptions::publish);
    calls.incrementAndGet();
    assertDoesNotThrow(subscriptions::publish);
    assertEquals(2, delivered.size());
    subscriptions.close();
  }

  @Test
  void distinctSelectionsHaveABoundedRotatingRefreshBudget() {
    var reports = mock(UsageReports.class);
    when(reports.report(anyString(), any())).thenAnswer(call -> report(call.getArgument(1), 0));
    when(reports.refresh(anyString(), any())).thenAnswer(call -> report(call.getArgument(1), 0));
    var subscriptions = new UsageSubscriptions(reports);
    for (int i = 0; i < 20; i++) {
      String account = "account-" + i;
      subscriptions.connect(account, account, (id, body) -> {});
      var receipt =
          subscriptions.subscribe(new Asking("session", account, account), query("usage.models"));
      subscriptions.ready(account, receipt.subscription());
    }
    clearInvocations(reports);
    subscriptions.publish();
    assertEquals(8, mockingDetails(reports).getInvocations().size());
    subscriptions.publish();
    assertEquals(16, mockingDetails(reports).getInvocations().size());
    subscriptions.publish();
    var accounts =
        mockingDetails(reports).getInvocations().stream()
            .map(call -> call.getArgument(0, String.class))
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(20, accounts.size(), "New/fast groups cannot starve older selections");
    subscriptions.close();
  }

  private static Usage.Resolved query(String type) {
    var from = java.time.Instant.parse("2026-01-01T00:00:00Z");
    return new Usage.Resolved(
        type,
        new Usage.Filter(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "direct",
            from,
            from.plusSeconds(60),
            List.of(),
            null,
            200));
  }

  private static Usage.Report report(Usage.Resolved query, int calls) {
    return new Usage.Report(
        query,
        new Usage.Aggregate(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Integer.toString(calls),
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            java.util.Map.of(),
            true,
            true,
            true,
            null),
        List.of(),
        null,
        new Usage.Health(
            null,
            null,
            Integer.toString(calls),
            java.time.Instant.EPOCH,
            "not_imported",
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
  }

  @Test
  void blocked_report_does_not_hold_registry_monitor_and_disconnect_fences_finalization()
      throws Exception {
    var reports = mock(UsageReports.class);
    var query = new Usage.Resolved("usage.models", mock(Usage.Filter.class));
    var report = mock(Usage.Report.class);
    when(report.filters()).thenReturn(query);
    when(report.health()).thenReturn(mock(Usage.Health.class));
    when(report.groups()).thenReturn(List.of());
    var entered = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    when(reports.report("alice", query))
        .thenAnswer(
            ignored -> {
              entered.complete(null);
              release.get(5, TimeUnit.SECONDS);
              return report;
            });
    when(reports.report("bob", query)).thenReturn(report);
    var subscriptions = new UsageSubscriptions(reports);
    subscriptions.connect("alice-socket", "alice", (id, body) -> {});
    var result = new CompletableFuture<Usage.Initial>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                result.complete(
                    subscriptions.subscribe(new Asking("session", "alice", "alice-socket"), query));
              } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
              }
            });
    entered.get(5, TimeUnit.SECONDS);
    try {
      subscriptions.connect("bob-socket", "bob", (id, body) -> {});
      assertNotNull(subscriptions.subscribe(new Asking("other", "bob", "bob-socket"), query));
      subscriptions.disconnect("alice-socket");
    } finally {
      release.complete(null);
    }
    var failure =
        assertThrows(
            java.util.concurrent.ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
    assertInstanceOf(CallerFault.class, failure.getCause());
    subscriptions.close();
  }
}
