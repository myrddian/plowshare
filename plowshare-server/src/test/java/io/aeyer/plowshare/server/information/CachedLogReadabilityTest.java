package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.cache.ItemProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionItemVersions;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The fallback contract of the cached log check, with mocked repositories and in-memory item
 * caches: every condition that cannot be served from confirmed facts must leave the answer to SQL,
 * a served answer costs exactly one confirmation statement, trees are shared across accounts, and a
 * stale root hint is resolved again rather than trusted. Verdicts are compared with SQL in {@code
 * LogReadabilityDifferentialDatabaseTest}.
 */
class CachedLogReadabilityTest {
  private static final ProjectionVersions.Domain READABILITY =
      ProjectionVersions.Domain.READABILITY;
  private static final UUID ALICE = UUID.randomUUID();
  private static final UUID ROLE = UUID.randomUUID();
  private static final UUID SCOPE = UUID.randomUUID();
  private static final UUID REVISION = UUID.randomUUID();
  private static final UUID RESOURCE = UUID.randomUUID();
  private static final LogTreeFact.JobInput PERSONAL =
      new LogTreeFact.JobInput(REVISION, "personal", Optional.empty(), true);

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

  /** Stores each value under its item counters, as an item-scoped projection does. */
  private static final class Cache<T> implements ItemProjectionCache<T> {
    final Map<SnapshotCache.Key, ItemProjectionCache.Unconfirmed<T>> stored = new HashMap<>();
    final Map<ProjectionVersions.Item, Long> counters;
    final List<ProjectionVersions.Item> invalidated = new ArrayList<>();
    final AtomicBoolean ready = new AtomicBoolean(true);
    final AtomicInteger loads = new AtomicInteger();

    Cache(Map<ProjectionVersions.Item, Long> counters) {
      this.counters = counters;
    }

    public long maxWeight() {
      return 1_000_000;
    }

    public T read(
        SnapshotCache.Key key,
        Set<ProjectionVersions.Item> items,
        boolean cacheable,
        Supplier<T> boundedLoader,
        Supplier<T> uncachedLoader,
        ToLongFunction<T> weight) {
      throw new AssertionError("The composite check uses unconfirmed reads only");
    }

    public Optional<ItemProjectionCache.Unconfirmed<T>> readUnconfirmed(
        SnapshotCache.Key key,
        Set<ProjectionVersions.Item> items,
        Supplier<T> boundedLoader,
        ToLongFunction<T> weight) {
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

  /** Confirms against the shared counters and counts statements. */
  private static final class Versions implements ProjectionItemVersions {
    final Map<ProjectionVersions.Item, Long> counters;
    final AtomicInteger statements = new AtomicInteger();

    Versions(Map<ProjectionVersions.Item, Long> counters) {
      this.counters = counters;
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
                  if (!counters.getOrDefault(item, 0L).equals(counter)) stale.add(item);
                });
      return stale;
    }

    public boolean canShare() {
      return true;
    }
  }

  private final Map<ProjectionVersions.Item, Long> counters = new HashMap<>();
  private final Cache<LogTreeFact> trees = new Cache<>(counters);
  private final Cache<KnowledgeFact> knowledge = new Cache<>(counters);
  private final Versions versions = new Versions(counters);
  private final LogTreeFactRepository repository = mock(LogTreeFactRepository.class);
  private final KnowledgeFactRepository facts = mock(KnowledgeFactRepository.class);

  private CachedLogReadability check(Optional<AuthoritySnapshot> authority, int maxFacts) {
    AuthoritySnapshots snapshots = () -> authority;
    return new CachedLogReadability(
        trees,
        repository,
        new KnowledgeFactSource(knowledge, facts, ReadAdmission.direct(), maxFacts),
        versions,
        snapshots,
        ReadAdmission.direct(),
        64);
  }

  private CachedLogReadability check() {
    return check(Optional.of(snapshot()), 64);
  }

  private static ProjectionVersions.Item tree(String root) {
    return new ProjectionVersions.Item(READABILITY, "conversation:" + root);
  }

  private static LogTreeFact.Log log(List<String> parents, LogTreeFact.JobInput... inputs) {
    return new LogTreeFact.Log(parents, false, List.of(inputs));
  }

  /** Root {@code root} with {@code child} under it; the root retains alice's personal input. */
  private void personalTree(String root, String child) {
    when(repository.tree(root))
        .thenReturn(
            new LogTreeFact.Tree(
                root, Map.of(root, log(List.of(), PERSONAL), child, log(List.of(root)))));
    when(repository.roots(child)).thenReturn(Optional.of(List.of(root)));
    when(repository.roots(root)).thenReturn(Optional.of(List.of(root)));
    when(facts.revision(REVISION))
        .thenReturn(
            new KnowledgeFact.Revision(
                REVISION, RESOURCE, true, true, false, false, List.of(), List.of()));
    when(facts.resource(RESOURCE))
        .thenReturn(
            new KnowledgeFact.Resource(
                RESOURCE,
                Optional.of(SCOPE),
                Optional.of(
                    new KnowledgeFact.RootPolicy(
                        KnowledgeFact.Audience.HOME, false, Optional.empty())),
                new KnowledgeFact.NavigationPolicy(false),
                Optional.of(REVISION),
                List.of()));
  }

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void aServedAnswerCostsOneConfirmationAndTreesAreSharedAcrossAccountsAndLogs() {
    personalTree("root", "child");
    var check = check();
    assertEquals(Optional.of(true), check.readable("alice", "child"));
    assertEquals(Optional.of(true), check.readable("alice", "child"));
    assertEquals(Optional.of(false), check.readable("bob", "child"), "Bob cannot read the input");
    assertEquals(Optional.of(true), check.readable("alice", "root"));
    assertEquals(1, trees.loads.get(), "One tree fill serves every account and log of the tree");
    assertEquals(2, knowledge.loads.get(), "One load per knowledge fact");
    assertEquals(4, versions.statements.get(), "One confirmation statement per check");
    verify(repository, times(1)).roots("child");
    verify(repository, times(1)).roots("root");
  }

  @Test
  void aQuarantinedAncestorIsUnreadableWithoutConsultingInputs() {
    when(repository.roots("child")).thenReturn(Optional.of(List.of("root")));
    when(repository.tree("root"))
        .thenReturn(
            new LogTreeFact.Tree(
                "root",
                Map.of(
                    "root",
                    new LogTreeFact.Log(List.of(), true, List.of(PERSONAL)),
                    "child",
                    log(List.of("root")))));
    assertEquals(Optional.of(false), check().readable("alice", "child"));
    verifyNoInteractions(facts);
    assertEquals(1, versions.statements.get());
  }

  @Test
  void aWritingTransactionIsLeftToSql() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertEquals(Optional.empty(), check().readable("alice", "child"));
    verifyNoInteractions(repository, facts);
    assertEquals(0, versions.statements.get());
  }

  @Test
  void aReadOnlyReadCommittedTransactionIsServed() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    personalTree("root", "child");
    assertEquals(Optional.of(true), check().readable("alice", "child"));
  }

  @Test
  void noAuthoritySnapshotIsLeftToSql() {
    assertEquals(Optional.empty(), check(Optional.empty(), 64).readable("alice", "child"));
    verifyNoInteractions(repository, facts);
  }

  @Test
  void unaddressableOrTooManyRootsAreLeftToSql() {
    when(repository.roots("cycle")).thenReturn(Optional.empty());
    assertEquals(Optional.empty(), check().readable("alice", "cycle"));
    when(repository.roots("spaced")).thenReturn(Optional.of(List.of("has space")));
    assertEquals(Optional.empty(), check().readable("alice", "spaced"));
    var many = new ArrayList<String>();
    for (int root = 0; root <= CachedLogReadability.MAX_TREES; root++) many.add("root-" + root);
    when(repository.roots("wide")).thenReturn(Optional.of(many));
    assertEquals(Optional.empty(), check().readable("alice", "wide"));
    verify(repository, never()).tree("has space");
    verify(repository, never()).tree("root-0");
    assertEquals(0, versions.statements.get());
  }

  @Test
  void aCacheThatCannotServeOrAnOversizedTreeIsLeftToSql() {
    personalTree("root", "child");
    trees.ready.set(false);
    assertEquals(Optional.empty(), check().readable("alice", "child"));
    trees.ready.set(true);
    when(repository.tree("root")).thenReturn(new LogTreeFact.Oversized("root"));
    assertEquals(Optional.empty(), check().readable("alice", "child"));
    assertEquals(0, versions.statements.get(), "Nothing to confirm");
  }

  @Test
  void anInputTheKnowledgeRuleCannotServeIsLeftToSql() {
    personalTree("root", "child");
    when(facts.revision(REVISION)).thenReturn(new KnowledgeFact.Oversized(REVISION));
    assertEquals(Optional.empty(), check().readable("alice", "child"));
    knowledge.stored.clear();
    personalTree("root", "child");
    // One fact fits the budget: the revision; its resource does not.
    assertEquals(Optional.empty(), check(Optional.of(snapshot()), 1).readable("alice", "child"));
    assertEquals(0, versions.statements.get());
  }

  @Test
  void aStaleTreeIsInvalidatedAloneAndLeftToSql() {
    personalTree("root", "child");
    var check = check();
    assertEquals(Optional.of(true), check.readable("alice", "child"));
    // A job input on the tree commits after the tree was stored.
    counters.put(tree("root"), 9L);
    assertEquals(Optional.empty(), check.readable("alice", "child"));
    assertTrue(trees.invalidated.contains(tree("root")));
    assertEquals(2, knowledge.stored.size(), "Knowledge facts stay cached");
    assertEquals(Optional.of(true), check.readable("alice", "child"));
    assertEquals(2, trees.loads.get(), "Only the stale tree reloads");
    assertEquals(2, knowledge.loads.get());
  }

  @Test
  void aJoinedTreeSendsTheRememberedRootsBackToResolution() {
    personalTree("root", "child");
    var check = check();
    assertEquals(Optional.of(true), check.readable("alice", "child"));
    // The root was attached under a quarantined caller: its own tree now lists the parent and
    // its item moved (V258 names the child of a new edge).
    counters.put(tree("root"), 5L);
    trees.invalidate(tree("root"));
    when(repository.tree("root"))
        .thenReturn(
            new LogTreeFact.Tree(
                "root",
                Map.of("root", log(List.of("caller"), PERSONAL), "child", log(List.of("root")))));
    when(repository.roots("child")).thenReturn(Optional.of(List.of("caller")));
    when(repository.tree("caller"))
        .thenReturn(
            new LogTreeFact.Tree(
                "caller",
                Map.of(
                    "caller",
                    new LogTreeFact.Log(List.of(), true, List.of()),
                    "root",
                    log(List.of("caller"), PERSONAL),
                    "child",
                    log(List.of("root")))));
    assertEquals(Optional.of(false), check.readable("alice", "child"));
    verify(repository, times(2)).roots("child");
  }

  @Test
  void rootsThatStillDoNotCoverTheLogAfterResolutionAreLeftToSql() {
    when(repository.roots("child")).thenReturn(Optional.of(List.of("root")));
    when(repository.tree("root"))
        .thenReturn(new LogTreeFact.Tree("root", Map.of("root", log(List.of()))));
    assertEquals(Optional.empty(), check().readable("alice", "child"));
    verify(repository, times(1)).roots("child");
    assertEquals(0, versions.statements.get());
  }

  @Test
  void theVisibilityAsksTheCheckFirstAndThePredicateOnlyWhenItDeclines() {
    var declined = new AtomicBoolean();
    LogReadabilityCheck check =
        (account, log) -> declined.get() ? Optional.empty() : Optional.of(log.equals("open"));
    var visibility =
        new JdbcInformationLogVisibility(
            mock(org.springframework.jdbc.core.JdbcTemplate.class),
            io.aeyer.plowshare.server.cache.ProjectionCache.direct(),
            mock(ProjectionVersions.class),
            ReadAdmission.direct(),
            check);
    var predicate = new AtomicInteger();
    assertTrue(visibility.readable("alice", "open", () -> predicate.incrementAndGet() < 0));
    assertFalse(visibility.readable("alice", "closed", () -> predicate.incrementAndGet() > 0));
    assertEquals(0, predicate.get());
    declined.set(true);
    assertTrue(visibility.readable("alice", "closed", () -> predicate.incrementAndGet() > 0));
    assertEquals(1, predicate.get());
  }
}
