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
