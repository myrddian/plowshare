package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Connection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Registries;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.RegistryAvailability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HomeAssistantActionOfferFenceTest {
  private static final class Source implements HomeAssistantResourceSource {
    Connection current = new Connection("epoch-a", 0, 0, true);
    int collections;

    @Override
    public HomeAssistantResources.Binding binding() {
      return HomeAssistantActionToolsTest.BINDING;
    }

    @Override
    public Connection connection() {
      return current;
    }

    @Override
    public MetadataSnapshot metadata(List<Selection> selections, OperationBudget budget) {
      collections++;
      var registries =
          new Registries(
              RegistryAvailability.AVAILABLE,
              RegistryAvailability.AVAILABLE,
              RegistryAvailability.AVAILABLE,
              RegistryAvailability.AVAILABLE);
      return new MetadataSnapshot(
          current,
          selections.stream()
              .map(
                  selection ->
                      new Description(
                          selection.reference(),
                          selection.entityId(),
                          selection.reference().alias(),
                          Optional.empty(),
                          Optional.empty(),
                          Set.of(),
                          registries))
              .toList());
    }

    @Override
    public StateSnapshot read(List<Selection> selections, OperationBudget budget) {
      throw new AssertionError("Action admission must never read state");
    }
  }

  private static HomeAssistantDiscoveryPolicy policy(String revision, boolean includeActions) {
    var metadata = new HomeAssistantDiscoveryPolicy.Metadata(false, false, false);
    return new HomeAssistantDiscoveryPolicy(
        HomeAssistantActionToolsTest.BINDING,
        revision,
        Map.of(
            "first",
            new HomeAssistantDiscoveryPolicy.Selection("light.first", Set.of(), metadata),
            "second",
            new HomeAssistantDiscoveryPolicy.Selection("light.second", Set.of(), metadata)),
        includeActions ? Map.of("lighting", HomeAssistantActionToolsTest.PROFILE) : Map.of());
  }

  @Test
  void currentOfferChecksArePurelyLocalAndRefuseDirtyEpochPolicyStateAndExpiry() throws Exception {
    for (String invalidation :
        List.of("metadata", "epoch", "policy", "state", "expiry", "offline")) {
      var source = new Source();
      var clock = new AtomicLong();
      var catalog =
          new HomeAssistantResourceCatalog(
              source, policy("policy-a", true), Duration.ofSeconds(10), clock::get);
      var page =
          catalog.search(
              new Search("", Optional.empty(), Optional.empty(), 10, Optional.empty()),
              HomeAssistantActionToolsTest.budget());
      var reference = new Reference(source.binding(), "first");
      var offered =
          (ConfiguredAction)
              catalog
                  .actions(reference, page.revision(), HomeAssistantActionToolsTest.budget())
                  .getFirst();
      catalog.requireCurrent(reference, offered, HomeAssistantActionToolsTest.budget());
      assertEquals(1, source.collections);
      switch (invalidation) {
        case "metadata" -> source.current = new Connection("epoch-a", 1, 0, true);
        case "epoch" -> source.current = new Connection("epoch-b", 0, 0, true);
        case "state" -> source.current = new Connection("epoch-a", 0, 1, true);
        case "offline" -> source.current = new Connection("epoch-a", 0, 0, false);
        case "policy" -> catalog.replacePolicy(policy("policy-b", false));
        case "expiry" -> clock.set(Duration.ofSeconds(11).toNanos());
        default -> throw new AssertionError();
      }
      assertThrows(
          IOException.class,
          () -> catalog.requireCurrent(reference, offered, HomeAssistantActionToolsTest.budget()));
      assertEquals(1, source.collections, "Final effect fence must not refresh");
    }
  }
}
