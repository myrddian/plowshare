package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.cache.ItemProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionItemVersions;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The fallback contract of the cached targeted check, with mocked repositories and an in-memory
 * item cache: every condition that cannot be served from confirmed facts must leave the answer to
 * SQL, and a served answer must cost exactly one confirmation statement. The verdicts themselves
 * are compared with SQL in {@code KnowledgeReadabilityDifferentialDatabaseTest}.
 */
class CachedKnowledgeReadabilityTest {
  private static final ProjectionVersions.Domain READABILITY =
      ProjectionVersions.Domain.READABILITY;
  private static final UUID ALICE = UUID.randomUUID();
  private static final UUID ROLE = UUID.randomUUID();
  private static final UUID SCOPE = UUID.randomUUID();
  private static final UUID REVISION = UUID.randomUUID();
  private static final UUID RESOURCE = UUID.randomUUID();
  private static final InformationContext PERSONAL =
      new InformationContext("alice", InformationContext.Selection.personal());

  /** Alice is a general-read member whose own account-knowledge scope is readable. */
  private static AuthoritySnapshot snapshot() {
    return new AuthoritySnapshot(
        new AuthoritySnapshot.Facts(
            Instant.now(),
            List.of(new AuthoritySnapshot.Account("alice", "USER", true, false, false)),
            Map.of(ALICE, "alice"),
            List.of(new AuthoritySnapshot.Role(ROLE, "GLOBAL_READ", Optional.empty(), false)),
            List.of(
                new AuthoritySnapshot.Scope(
                    SCOPE,
                    "ACCOUNT_KNOWLEDGE",
                    Optional.of(ALICE),
                    Optional.empty(),
                    Optional.empty())),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new AuthoritySnapshot.RoleAction(ROLE, 1, "DEFAULT", "READ"))));
  }

  private static KnowledgeFact.Revision revision(List<UUID> inputs) {
    return new KnowledgeFact.Revision(
        REVISION, RESOURCE, true, true, false, false, inputs, List.of());
  }

  private static KnowledgeFact.Resource resource() {
    return new KnowledgeFact.Resource(
        RESOURCE,
        Optional.of(SCOPE),
        Optional.of(
            new KnowledgeFact.RootPolicy(KnowledgeFact.Audience.HOME, false, Optional.empty())),
        new KnowledgeFact.NavigationPolicy(false),
        Optional.of(REVISION),
        List.of());
  }

  /** Stores each fact under its item counter, as the item-scoped projection does. */
  private static final class Cache implements ItemProjectionCache<KnowledgeFact> {
    final Map<SnapshotCache.Key, ItemProjectionCache.Unconfirmed<KnowledgeFact>> stored =
        new HashMap<>();
    final Map<ProjectionVersions.Item, Long> counters = new HashMap<>();
    final List<ProjectionVersions.Item> invalidated = new ArrayList<>();
    final AtomicBoolean ready = new AtomicBoolean(true);
    final AtomicInteger loads = new AtomicInteger();

    public long maxWeight() {
      return 1_000_000;
    }

    public KnowledgeFact read(
        SnapshotCache.Key key,
        Set<ProjectionVersions.Item> items,
        boolean cacheable,
        Supplier<KnowledgeFact> boundedLoader,
        Supplier<KnowledgeFact> uncachedLoader,
        ToLongFunction<KnowledgeFact> weight) {
      throw new AssertionError("The composite check uses unconfirmed reads only");
    }

    public Optional<ItemProjectionCache.Unconfirmed<KnowledgeFact>> readUnconfirmed(
        SnapshotCache.Key key,
        Set<ProjectionVersions.Item> items,
        Supplier<KnowledgeFact> boundedLoader,
        ToLongFunction<KnowledgeFact> weight) {
      if (!ready.get()) return Optional.empty();
      var known = stored.get(key);
      if (known != null) return Optional.of(known);
      var captured = new HashMap<ProjectionVersions.Item, Long>();
      for (var item : items) captured.put(item, counters.getOrDefault(item, 0L));
      var all = ProjectionVersions.Item.all(READABILITY);
      captured.put(all, counters.getOrDefault(all, 0L));
      loads.incrementAndGet();
      var value =
          new ItemProjectionCache.Unconfirmed<>(
              boundedLoader.get(), new ProjectionItemVersions.ItemToken(new UUID(0, 1), captured));
      stored.put(key, value);
      return Optional.of(value);
    }

    public void invalidate(ProjectionVersions.Item item) {
      invalidated.add(item);
      stored.values().removeIf(entry -> entry.items().counters().containsKey(item));
    }
  }

  /** Confirms against the fake cache's counters and counts statements. */
  private static final class Versions implements ProjectionItemVersions {
    final Cache cache;
    final AtomicInteger statements = new AtomicInteger();

    Versions(Cache cache) {
      this.cache = cache;
    }

    public Optional<ItemToken> captureItems(Set<ProjectionVersions.Item> items) {
      throw new AssertionError("Captured by the cache");
    }

    public boolean currentItems(ItemToken token) {
      throw new AssertionError("No per-entry check");
    }

    public Set<ProjectionVersions.Item> staleItems(Collection<ItemToken> tokens) {
      statements.incrementAndGet();
      var stale = new HashSet<ProjectionVersions.Item>();
      for (var token : tokens)
        token
            .counters()
            .forEach(
                (item, counter) -> {
                  if (!cache.counters.getOrDefault(item, 0L).equals(counter)) stale.add(item);
                });
      return stale;
    }

    public boolean canShare() {
      return true;
    }
  }

  private final Cache cache = new Cache();
  private final Versions versions = new Versions(cache);
  private final KnowledgeFactRepository repository = mock(KnowledgeFactRepository.class);

  private CachedKnowledgeReadability check(Optional<AuthoritySnapshot> authority, int maxFacts) {
    AuthoritySnapshots snapshots = () -> authority;
    return new CachedKnowledgeReadability(
        cache, repository, versions, snapshots, ReadAdmission.direct(), maxFacts);
  }

  private CachedKnowledgeReadability check() {
    return check(Optional.of(snapshot()), 64);
  }

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void aServedAnswerCostsOneConfirmationAndRepeatsFromMemory() {
    when(repository.revision(REVISION)).thenReturn(revision(List.of()));
    when(repository.resource(RESOURCE)).thenReturn(resource());
    var check = check();
    assertEquals(Optional.of(true), check.readable(PERSONAL, REVISION));
    assertEquals(Optional.of(true), check.readable(PERSONAL, REVISION));
    assertEquals(2, cache.loads.get(), "One load per fact, then hits");
    assertEquals(2, versions.statements.get(), "One confirmation statement per check");
  }

  @Test
  void aWritingTransactionIsLeftToSql() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertEquals(Optional.empty(), check().readable(PERSONAL, REVISION));
    verifyNoInteractions(repository);
    assertEquals(0, versions.statements.get());
  }

  @Test
  void aReadOnlyReadCommittedTransactionIsServed() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    when(repository.revision(REVISION)).thenReturn(revision(List.of()));
    when(repository.resource(RESOURCE)).thenReturn(resource());
    assertEquals(Optional.of(true), check().readable(PERSONAL, REVISION));
  }

  @Test
  void noAuthoritySnapshotIsLeftToSql() {
    assertEquals(Optional.empty(), check(Optional.empty(), 64).readable(PERSONAL, REVISION));
    verifyNoInteractions(repository);
  }

  @Test
  void aCacheThatCannotServeIsLeftToSql() {
    cache.ready.set(false);
    assertEquals(Optional.empty(), check().readable(PERSONAL, REVISION));
    assertEquals(0, versions.statements.get(), "Nothing to confirm");
  }

  @Test
  void aClosurePastTheFactBudgetIsLeftToSql() {
    var input = UUID.randomUUID();
    when(repository.revision(REVISION)).thenReturn(revision(List.of(input)));
    when(repository.resource(RESOURCE)).thenReturn(resource());
    assertEquals(Optional.empty(), check(Optional.of(snapshot()), 2).readable(PERSONAL, REVISION));
    verify(repository, never()).revision(input);
  }

  @Test
  void anOversizedFactIsLeftToSql() {
    when(repository.revision(REVISION)).thenReturn(new KnowledgeFact.Oversized(REVISION));
    assertEquals(Optional.empty(), check().readable(PERSONAL, REVISION));
  }

  @Test
  void aStaleFactIsInvalidatedAloneAndLeftToSql() {
    when(repository.revision(REVISION)).thenReturn(revision(List.of()));
    when(repository.resource(RESOURCE)).thenReturn(resource());
    var check = check();
    assertEquals(Optional.of(true), check.readable(PERSONAL, REVISION));
    // A policy write on the resource commits after its fact was stored.
    var resourceItem = new ProjectionVersions.Item(READABILITY, "resource:" + RESOURCE);
    cache.counters.put(resourceItem, 9L);
    assertEquals(Optional.empty(), check.readable(PERSONAL, REVISION));
    assertEquals(List.of(resourceItem), cache.invalidated);
    assertEquals(1, cache.stored.size(), "The revision fact stays cached");
    // The next check reloads only the resource fact and is served again.
    assertEquals(Optional.of(true), check.readable(PERSONAL, REVISION));
    assertEquals(3, cache.loads.get());
  }

  @Test
  void anAbsentRevisionIsAnsweredFalseFromAConfirmedNegativeFact() {
    when(repository.revision(REVISION)).thenReturn(new KnowledgeFact.Absent(REVISION));
    assertEquals(Optional.of(false), check().readable(PERSONAL, REVISION));
    assertEquals(1, versions.statements.get());
  }

  @Test
  void theRepositoryRunsItsSqlOnlyWhenTheCheckDeclines() {
    var jdbc = mock(JdbcTemplate.class);
    var declined = new AtomicBoolean();
    KnowledgeReadabilityCheck readability =
        (context, revision) -> declined.get() ? Optional.empty() : Optional.of(true);
    var catalogue =
        new JdbcInformationCatalogueRepository(
            jdbc,
            Clock.systemUTC(),
            mock(AllowanceRepository.class),
            ProjectionCache.direct(),
            ProjectionCache.direct(),
            ProjectionCache.direct(),
            ProjectionCache.direct(),
            ProjectionCache.direct(),
            readability,
            mock(InformationEvents.class));
    assertTrue(catalogue.readable(PERSONAL, REVISION));
    verify(jdbc, never()).queryForList(anyString(), eq(Integer.class), any(Object[].class));
    declined.set(true);
    when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(List.of());
    assertFalse(catalogue.readable(PERSONAL, REVISION));
    verify(jdbc).queryForList(anyString(), eq(Integer.class), any(Object[].class));
  }
}
