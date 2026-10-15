package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.llm.dispatch.Lane;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Capture the production admission metadata, without a database or model request. */
class ServiceUsageOwnershipTest {
  @Test
  void sharing_model_capacity_does_not_combine_human_service_or_system_accounting() {
    String service = "@service/11111111-1111-1111-1111-111111111111";
    var recorder = mock(AccountingRecorder.class);
    var prices = mock(InferencePrices.class);
    when(recorder.begin(any())).thenReturn(mock(AccountingRecorder.Call.class));
    when(prices.routeFor("shared-capacity"))
        .thenReturn(new PricingCatalog.Route("shared-billing-route", Map.of()));
    when(prices.select(eq("shared-billing-route"), eq("model"), any()))
        .thenReturn(Optional.empty());
    var accounting =
        new DurableInferenceAccounting(
            recorder, prices, Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));
    var human = UsageAttribution.project("alice", "project", UsageAttribution.Operation.AGENT_CHAT);
    var machine =
        UsageAttribution.project(service, "project", UsageAttribution.Operation.AGENT_CHAT);
    var system = UsageAttribution.system("project", UsageAttribution.Operation.DOCUMENT_SUMMARY);
    for (var owner : List.of(human, machine, system)) {
      accounting.begin("shared-capacity", "model", "configured-model", Lane.CHAT, owner);
    }
    var captured = ArgumentCaptor.forClass(AccountingEvent.CallCreated.class);
    verify(recorder, times(3)).begin(captured.capture());
    var admitted = captured.getAllValues();
    assertSame(human, admitted.get(0).attribution());
    assertSame(machine, admitted.get(1).attribution());
    assertSame(system, admitted.get(2).attribution());
    assertEquals("alice", admitted.get(0).attribution().accountHandle());
    assertEquals(service, admitted.get(1).attribution().accountHandle());
    assertNull(admitted.get(2).attribution().accountHandle());
    assertEquals(UsageAttribution.Status.SYSTEM, admitted.get(2).attribution().status());
    for (var record : admitted) {
      assertEquals("shared-capacity", record.pool());
      assertEquals("shared-billing-route", record.billingRoute());
      assertNull(record.price(), "Absent prices remain unknown; they are not a zero-cost budget");
    }
  }
}
