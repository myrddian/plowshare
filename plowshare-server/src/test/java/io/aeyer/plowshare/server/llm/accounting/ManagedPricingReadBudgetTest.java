package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.PoolProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class ManagedPricingReadBudgetTest {
  final PricingRepository repository = mock(PricingRepository.class);
  final AdminStore accounts = mock(AdminStore.class);
  final Instant now = Instant.parse("2026-10-01T12:00:00Z");

  ManagedPricing service() {
    var properties = new LlmProperties();
    var pool = new PoolProperties();
    pool.setName("hosted");
    pool.setModels(List.of("one", "two"));
    properties.setPools(List.of(pool));
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    return new ManagedPricing(
        properties, repository, accounts, manager, Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void listingLoadsExactOverridesTogetherAndKeepsAbsentConfigurationEntries() {
    var service = service();
    when(repository.findAll(anyList())).thenReturn(Map.of());
    var entries = service.list("operator");
    assertEquals(
        List.of("one", "two"), entries.stream().map(PricingAdministration.Entry::model).toList());
    assertTrue(entries.stream().allMatch(entry -> entry.origin().equals("configuration")));
    verify(accounts).requireServerAdmin("operator");
    verify(repository)
        .findAll(
            List.of(
                new PricingRepository.Key("hosted", "one"),
                new PricingRepository.Key("hosted", "two")));
    verifyNoMoreInteractions(repository, accounts);
  }

  @Test
  void editingChecksOnlyTheVersionThenReturnsItsWrittenSnapshotWithoutReloading() {
    var service = service();
    when(repository.find("hosted", "one")).thenReturn(Optional.empty());
    when(repository.save(any(), eq("operator")))
        .thenAnswer(call -> new PricingRepository.PriceOverride(call.getArgument(0), now));
    var result =
        service.set(
            "operator",
            new PricingAdministration.Change(
                "hosted", "one", "boot:", "UNPRICED", "USD", null, List.of(), null, "operator"));
    assertEquals("override", result.origin());
    assertEquals(now, result.updatedAt());
    verify(accounts, times(2)).requireServerAdmin("operator");
    verify(repository).lockChanges();
    verify(repository).find("hosted", "one");
    verify(repository).save(any(), eq("operator"));
    verifyNoMoreInteractions(repository, accounts);
  }
}
