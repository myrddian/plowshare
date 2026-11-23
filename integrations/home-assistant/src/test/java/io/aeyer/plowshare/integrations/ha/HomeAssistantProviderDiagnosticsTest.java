package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.integrations.ha.HomeAssistantDiscoveryPolicy.Metadata;
import io.aeyer.plowshare.integrations.ha.HomeAssistantDiscoveryPolicy.Selection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantReadDiagnostics.CatalogueObservation;
import io.aeyer.plowshare.integrations.ha.HomeAssistantReadDiagnostics.ConnectionObservation;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Connection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HomeAssistantProviderDiagnosticsTest {
  @Test
  void disconnectedSourceAndExpiredLocalLeaseDoNotEraseHistoricalSdkObservation() {
    var source = mock(HomeAssistantResourceSource.class);
    var binding = new Binding("fixture-house", "fixture-credential");
    var policy =
        new HomeAssistantDiscoveryPolicy(
            binding,
            "fixture-policy",
            Map.of(
                "office",
                new Selection(
                    "climate.office",
                    Set.of("unit_of_measurement"),
                    new Metadata(false, false, false))),
            Map.of());
    when(source.connection()).thenReturn(new Connection("epoch", 1, 1, true));
    var diagnostics = new HomeAssistantProviderDiagnostics(source, policy);
    assertEquals(CatalogueObservation.NOT_OBSERVED, diagnostics.snapshot().catalogue());
    assertTrue(diagnostics.snapshot().lastSuccessfulSdkCommunication().isEmpty());
    var available = new AtomicBoolean(true);
    diagnostics.attach(available::get);
    diagnostics.sdkSucceeded();
    var first = diagnostics.snapshot();
    assertEquals(ConnectionObservation.AUTHENTICATED_CONNECTION, first.homeAssistantConnection());
    assertEquals(CatalogueObservation.CONFIRMED_LOCAL_LEASE, first.catalogue());
    assertTrue(first.lastSuccessfulSdkCommunication().isPresent());
    when(source.connection()).thenReturn(new Connection("epoch", 1, 1, false));
    available.set(false);
    var second = diagnostics.snapshot();
    assertEquals(ConnectionObservation.UNAVAILABLE, second.homeAssistantConnection());
    assertEquals(CatalogueObservation.UNAVAILABLE, second.catalogue());
    assertEquals(first.lastSuccessfulSdkCommunication(), second.lastSuccessfulSdkCommunication());
    assertFalse(second.checkedAt().isBefore(first.checkedAt()));
    var revision = new Revision(binding, policy.revision(), "epoch", 1, 1);
    assertEquals(
        Set.of("unit_of_measurement"),
        diagnostics.permittedAttributes(new Reference(binding, "office"), revision).orElseThrow());
    assertTrue(
        diagnostics.permittedAttributes(new Reference(binding, "hidden"), revision).isEmpty());
    assertTrue(
        diagnostics
            .permittedAttributes(
                new Reference(binding, "office"),
                new Revision(binding, "another-policy", "epoch", 1, 1))
            .isEmpty());
    assertThrows(IllegalStateException.class, () -> diagnostics.attach(() -> true));
  }
}
