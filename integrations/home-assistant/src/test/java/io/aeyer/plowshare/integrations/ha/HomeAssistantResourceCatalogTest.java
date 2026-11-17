package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.ha.HomeAssistantDiscoveryPolicy.Metadata;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Connection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.MetadataSnapshot;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Selection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.StateSnapshot;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Page;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Registries;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.RegistryAvailability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.State;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HomeAssistantResourceCatalogTest {
  private static final Binding BINDING = new Binding("house", "credential-a");
  private static final Reference FIRST = new Reference(BINDING, "first");
  private static final Reference SECOND = new Reference(BINDING, "second");
  private static final Registries REGISTRIES =
      new Registries(
          RegistryAvailability.AVAILABLE,
          RegistryAvailability.AVAILABLE,
          RegistryAvailability.AVAILABLE,
          RegistryAvailability.AVAILABLE);

  private static final class Source implements HomeAssistantResourceSource {
    volatile Connection current = new Connection("epoch-a", 0, 0, true);
    int metadataCalls;
    int readCalls;
    List<Selection> readSelection = List.of();
    CountDownLatch started;
    CountDownLatch release;
    boolean changeDuringRead;
    boolean foreignState;
    boolean omitUnit;

    @Override
    public Binding binding() {
      return BINDING;
    }

    @Override
    public Connection connection() {
      return current;
    }

    @Override
    public MetadataSnapshot metadata(List<Selection> selections, OperationBudget budget)
        throws IOException {
      metadataCalls++;
      if (started != null) started.countDown();
      if (release != null) {
        try {
          if (!release.await(budget.remaining().toNanos(), TimeUnit.NANOSECONDS))
            throw new IOException("fixture deadline");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("fixture interrupted");
        }
      }
      Description first = description(FIRST, "sensor.first");
      if (omitUnit)
        first =
            new Description(
                first.reference(),
                first.entityId(),
                first.name(),
                first.device(),
                first.area(),
                Set.of("private"),
                first.registries());
      return new MetadataSnapshot(
          current,
          List.of(
              first,
              description(new Reference(BINDING, "private"), "sensor.private"),
              description(SECOND, "sensor.second")));
    }

    @Override
    public StateSnapshot read(List<Selection> selections, OperationBudget budget) {
      readCalls++;
      readSelection = selections;
      Connection before = current;
      if (changeDuringRead) current = new Connection("epoch-b", 0, 0, true);
      List<State> states = selections.stream().map(value -> state(value.reference())).toList();
      if (foreignState) states = List.of(state(new Reference(BINDING, "private")));
      return new StateSnapshot(before, states);
    }
  }

  private static Description description(Reference reference, String id) {
    return new Description(
        reference,
        id,
        "Duplicate name",
        Optional.of("device-fixture"),
        Optional.of("area-fixture"),
        Set.of("unit_of_measurement", "private"),
        REGISTRIES);
  }

  private static State state(Reference reference) {
    return new State(
        reference,
        Availability.AVAILABLE,
        Optional.of("22"),
        Map.of(),
        Set.of(),
        Optional.empty(),
        Instant.EPOCH,
        false);
  }

  private static HomeAssistantDiscoveryPolicy policy(String revision, boolean includeSecond) {
    HomeAssistantDiscoveryPolicy.Selection first =
        new HomeAssistantDiscoveryPolicy.Selection(
            "sensor.first", Set.of("unit_of_measurement"), new Metadata(false, false, false));
    HomeAssistantDiscoveryPolicy.Selection second =
        new HomeAssistantDiscoveryPolicy.Selection(
            "sensor.second", Set.of(), new Metadata(false, false, false));
    return new HomeAssistantDiscoveryPolicy(
        BINDING,
        revision,
        includeSecond ? Map.of("first", first, "second", second) : Map.of("first", first),
        Map.of());
  }

  private static OperationBudget budget() {
    return OperationBudget.start(Duration.ofSeconds(2));
  }

  private static Search search(int limit) {
    return new Search("", Optional.empty(), Optional.empty(), limit, Optional.empty());
  }

  @Test
  void filtersBeforePagingAndReusesOneAuthorizedMetadataSnapshot() throws Exception {
    Source source = new Source();
    var catalog =
        new HomeAssistantResourceCatalog(source, policy("policy-a", true), Duration.ofSeconds(2));
    Page first = catalog.search(search(1), budget());
    assertEquals("first", first.resources().getFirst().name());
    assertFalse(first.toString().contains("private"));
    assertTrue(first.resources().getFirst().device().isEmpty());
    Page second =
        catalog.search(
            new Search("", Optional.empty(), Optional.empty(), 1, first.next()), budget());
    assertEquals(List.of(SECOND), second.resources().stream().map(Description::reference).toList());
    assertTrue(second.next().isEmpty());
    assertEquals(1, source.metadataCalls);
    assertTrue(
        catalog.describe(new Reference(BINDING, "private"), first.revision(), budget()).isEmpty());
  }

  @Test
  void readsOneBatchInRequestedOrderAndStateChangesDoNotReloadMetadata() throws Exception {
    Source source = new Source();
    var catalog =
        new HomeAssistantResourceCatalog(source, policy("policy-a", true), Duration.ofSeconds(2));
    Page first = catalog.search(search(100), budget());
    var read = catalog.read(new ReadRequest(first.revision(), List.of(SECOND, FIRST)), budget());
    assertEquals(List.of(SECOND, FIRST), read.states().stream().map(State::reference).toList());
    assertEquals(1, source.readCalls);
    assertEquals(2, source.readSelection.size());
    source.current = new Connection("epoch-a", 0, 1, true);
    assertThrows(
        IOException.class,
        () -> catalog.read(new ReadRequest(first.revision(), List.of(FIRST)), budget()));
    Page newer = catalog.search(search(100), budget());
    assertEquals(1, newer.revision().stateVersion());
    assertEquals(1, source.metadataCalls);
  }

  @Test
  void metadataInvalidationRefusesOldPagesAndRefreshesOnlyFreshSearch() throws Exception {
    Source source = new Source();
    var catalog =
        new HomeAssistantResourceCatalog(source, policy("policy-a", true), Duration.ofSeconds(2));
    Page first = catalog.search(search(1), budget());
    source.current = new Connection("epoch-a", 1, 0, true);
    source.omitUnit = true;
    assertThrows(
        IOException.class,
        () ->
            catalog.search(
                new Search("", Optional.empty(), Optional.empty(), 1, first.next()), budget()));
    assertEquals(2, source.metadataCalls);
    Page newer = catalog.search(search(1), budget());
    assertEquals(2, newer.revision().metadataVersion());
    assertEquals(2, source.metadataCalls);
  }

  @Test
  void privateMetadataChangesDoNotAdvancePublicRevisionOrInvalidatePage() throws Exception {
    Source source = new Source();
    var catalog =
        new HomeAssistantResourceCatalog(source, policy("policy-a", true), Duration.ofSeconds(2));
    Page first = catalog.search(search(1), budget());
    source.current = new Connection("epoch-a", 900, 0, true);
    Page second =
        catalog.search(
            new Search("", Optional.empty(), Optional.empty(), 1, first.next()), budget());
    assertEquals(first.revision(), second.revision());
    assertEquals(List.of(SECOND), second.resources().stream().map(Description::reference).toList());
    assertEquals(2, source.metadataCalls);
  }

  @Test
  void metadataExpiryAndReconnectNeverSilentlyRebindOldCursor() throws Exception {
    Source source = new Source();
    AtomicLong clock = new AtomicLong();
    var catalog =
        new HomeAssistantResourceCatalog(
            source, policy("policy-a", true), Duration.ofSeconds(2), clock::get);
    Page first = catalog.search(search(1), budget());
    clock.set(Duration.ofSeconds(3).toNanos());
    assertTrue(catalog.describe(FIRST, first.revision(), budget()).isPresent());
    assertEquals(
        first.revision().metadataVersion(),
        catalog.search(search(1), budget()).revision().metadataVersion());
    assertEquals(2, source.metadataCalls);
    source.current = new Connection("epoch-a", 0, 0, false);
    assertThrows(
        IOException.class,
        () -> catalog.read(new ReadRequest(first.revision(), List.of(FIRST)), budget()));
    assertEquals(0, source.readCalls);
    source.current = new Connection("epoch-b", 0, 0, true);
    assertThrows(
        IOException.class,
        () ->
            catalog.search(
                new Search("", Optional.empty(), Optional.empty(), 1, first.next()), budget()));
    assertEquals("epoch-b", catalog.search(search(100), budget()).revision().epoch());
  }

  @Test
  void policyReplacementAndForeignCredentialFenceBeforeTransport() throws Exception {
    Source source = new Source();
    var catalog =
        new HomeAssistantResourceCatalog(source, policy("policy-a", true), Duration.ofSeconds(2));
    Page first = catalog.search(search(100), budget());
    catalog.replacePolicy(policy("policy-b", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.read(new ReadRequest(first.revision(), List.of(FIRST)), budget()));
    Page updated = catalog.search(search(100), budget());
    assertEquals(1, updated.resources().size());
    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.read(new ReadRequest(updated.revision(), List.of(SECOND)), budget()));
    assertEquals(0, source.readCalls);
    var other =
        new HomeAssistantDiscoveryPolicy(
            new Binding("house", "credential-b"), "other", Map.of(), Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new HomeAssistantResourceCatalog(source, other, Duration.ofSeconds(2)));
  }

  @Test
  void lostGenerationAndUnexpectedBatchDoNotPublishFetchedValues() throws Exception {
    Source source = new Source();
    var catalog =
        new HomeAssistantResourceCatalog(source, policy("policy-a", true), Duration.ofSeconds(2));
    Page first = catalog.search(search(100), budget());
    source.changeDuringRead = true;
    assertThrows(
        IOException.class,
        () -> catalog.read(new ReadRequest(first.revision(), List.of(FIRST)), budget()));
    source.changeDuringRead = false;
    source.foreignState = true;
    Page newer = catalog.search(search(100), budget());
    assertThrows(
        IOException.class,
        () -> catalog.read(new ReadRequest(newer.revision(), List.of(FIRST)), budget()));
  }

  @Test
  void concurrentMetadataWaitUsesRemainingCallerBudgetWithoutExtraLoad() throws Exception {
    Source source = new Source();
    source.started = new CountDownLatch(1);
    source.release = new CountDownLatch(1);
    var catalog =
        new HomeAssistantResourceCatalog(source, policy("policy-a", true), Duration.ofSeconds(2));
    try (var workers = Executors.newSingleThreadExecutor()) {
      var first = workers.submit(() -> catalog.search(search(100), budget()));
      assertTrue(source.started.await(1, TimeUnit.SECONDS));
      long started = System.nanoTime();
      assertThrows(
          IOException.class,
          () -> catalog.search(search(100), OperationBudget.start(Duration.ofMillis(30))));
      assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
      source.release.countDown();
      assertEquals(2, first.get(1, TimeUnit.SECONDS).resources().size());
      assertEquals(1, source.metadataCalls);
    }
  }
}
