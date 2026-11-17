package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.ha.ActionExecutor.Delivery;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ActionDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Page;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadBatch;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Registries;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.RegistryAvailability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.State;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HomeAssistantActionVerifierTest {
  private static final Reference FIRST =
      new Reference(HomeAssistantActionToolsTest.BINDING, "first");

  private static final class Resources implements ResourceDiscovery, ResourceReader {
    int reads;
    String value = "on";
    String entity = "light.first";
    boolean stale;
    Instant observedAt = Instant.now().plusSeconds(1);
    OperationBudget observedBudget;
    Revision returnedRevision;

    @Override
    public Page search(Search search, OperationBudget budget) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ActionDescription> actions(
        Reference reference, Revision revision, OperationBudget budget) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Description> describe(
        Reference reference, Revision revision, OperationBudget budget) {
      return Optional.of(
          new Description(
              reference,
              entity,
              "first",
              Optional.empty(),
              Optional.empty(),
              Set.of(),
              new Registries(
                  RegistryAvailability.AVAILABLE,
                  RegistryAvailability.AVAILABLE,
                  RegistryAvailability.AVAILABLE,
                  RegistryAvailability.AVAILABLE)));
    }

    @Override
    public ReadBatch read(ReadRequest request, OperationBudget budget) {
      reads++;
      observedBudget = budget;
      return new ReadBatch(
          returnedRevision == null ? request.revision() : returnedRevision,
          List.of(
              new State(
                  FIRST,
                  Availability.AVAILABLE,
                  Optional.of(value),
                  Map.of(),
                  Set.of(),
                  Optional.empty(),
                  observedAt,
                  stale)));
    }
  }

  private static ActionVerifier.Request request() {
    var nativeRequest =
        new ActionExecutor.Request(
            UUID.randomUUID().toString(),
            HomeAssistantActionToolsTest.ACTION,
            Map.of("brightness", new NumberParameter(BigDecimal.ONE)));
    return new ActionVerifier.Request(
        HomeAssistantActionToolsTest.receipt(nativeRequest, Delivery.ACKNOWLEDGED),
        HomeAssistantActionToolsTest.REVISION,
        Map.of(FIRST, "on"));
  }

  @Test
  void freshExplicitPredicateIsSeparateFromTheOriginalAcknowledgement() throws Exception {
    var resources = new Resources();
    var verifier = new HomeAssistantActionVerifier(resources, resources);
    var request = request();
    var budget = HomeAssistantActionToolsTest.budget();
    var result = verifier.verify(request, budget);
    assertEquals(ActionVerifier.Predicate.MATCHED, result.predicate());
    assertTrue(result.receipt() == request.receipt());
    assertEquals(1, resources.reads);
    assertTrue(resources.observedBudget == budget);
    resources.value = "off";
    assertEquals(
        ActionVerifier.Predicate.NOT_MATCHED,
        verifier.verify(request, HomeAssistantActionToolsTest.budget()).predicate());
    assertEquals(Delivery.ACKNOWLEDGED, result.receipt().delivery());
    assertEquals(ActionExecutor.Effect.NOT_OBSERVED, result.receipt().effect());
  }

  @Test
  void staleOrPreReceiptEvidenceCannotVerify() throws Exception {
    var resources = new Resources();
    var verifier = new HomeAssistantActionVerifier(resources, resources);
    var request = request();
    resources.stale = true;
    assertEquals(
        ActionVerifier.Predicate.UNAVAILABLE,
        verifier.verify(request, HomeAssistantActionToolsTest.budget()).predicate());
    resources.stale = false;
    resources.observedAt = Instant.EPOCH;
    assertEquals(
        ActionVerifier.Predicate.UNAVAILABLE,
        verifier.verify(request, HomeAssistantActionToolsTest.budget()).predicate());
  }

  @Test
  void unrelatedSelectedResourceCannotVerifyAnActionBundle() {
    var resources = new Resources();
    resources.entity = "light.unrelated";
    var verifier = new HomeAssistantActionVerifier(resources, resources);
    assertThrows(
        IOException.class, () -> verifier.verify(request(), HomeAssistantActionToolsTest.budget()));
    assertEquals(0, resources.reads);
  }

  @Test
  void sameBindingButDifferentObservationRevisionCannotVerify() {
    var resources = new Resources();
    var selected = HomeAssistantActionToolsTest.REVISION;
    resources.returnedRevision =
        new Revision(
            selected.binding(),
            selected.policy(),
            selected.epoch(),
            selected.metadataVersion(),
            selected.stateVersion() + 1);
    var verifier = new HomeAssistantActionVerifier(resources, resources);
    assertThrows(
        IOException.class, () -> verifier.verify(request(), HomeAssistantActionToolsTest.budget()));
  }
}
