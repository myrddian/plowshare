package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.cache.ItemProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionItemVersions;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The targeted readability check from shared, item-cached facts and the in-memory authority
 * snapshot (design slice 1).
 *
 * <h2>What is cached</h2>
 *
 * <p>One fact per item, in one item-scoped projection: each revision under {@code revision:<id>}
 * and each resource under {@code resource:<id>} ({@link KnowledgeFact}). Facts carry no account and
 * no allow decision, so every account shares them; a write invalidates only the facts of the items
 * it changed. Scopes, projects, accounts, grants and contained principals come from the authority
 * snapshot instead, because those tables move the AUTHORITY version rather than an item.
 *
 * <h2>Consistency</h2>
 *
 * <p>An evaluation reads its facts without checking each one ({@link
 * ItemProjectionCache#readUnconfirmed}), then confirms the counters of every item it read with one
 * {@link ProjectionItemVersions#staleItems} statement on a fresh connection. Counters move at
 * commit, so an empty result proves that none of the facts changed after it was loaded: the verdict
 * reflects every data-side write committed before the confirmation, on any process, exactly as the
 * SQL statement would. A stale item is invalidated and the check goes to SQL. The authority half is
 * the snapshot's own contract ({@code CachedAuthoritySnapshots}): it is notification-trusted, so a
 * committed membership, grant or principal change may be reflected up to {@code
 * plowshare.projection-cache.notified.staleness} (2 s) later, as for every other authority-snapshot
 * decision.
 *
 * <h2>When SQL answers instead</h2>
 *
 * <ul>
 *   <li>the caller holds a transaction that may contain its own writes (anything but none or a
 *       read-only READ COMMITTED one), since cached facts cannot see uncommitted rows;
 *   <li>no authority snapshot is available for this caller;
 *   <li>the fact cache cannot serve (listener not ready, ineligible caller, refused load);
 *   <li>the evaluation needs more than {@code maxFacts} facts, or a row is {@link
 *       KnowledgeFact.Oversized};
 *   <li>the confirmation reports a stale item.
 * </ul>
 *
 * <p>A dependency closure past 1,024 nodes needs no fallback: the Java rule answers false, as SQL
 * does, if the walk fits {@code maxFacts}; otherwise the budget sends it to SQL first.
 */
public final class CachedKnowledgeReadability implements KnowledgeReadabilityCheck {
  /** FETCH admission principal: the fact cache's own slots, never a per-account budget. */
  static final String FETCH = "knowledge-facts";

  private final ItemProjectionCache<KnowledgeFact> cache;
  private final KnowledgeFactRepository repository;
  private final ProjectionItemVersions versions;
  private final AuthoritySnapshots authority;
  private final ReadAdmission admission;
  private final int maxFacts;

  /**
   * @param maxFacts most distinct facts one evaluation may read; at most the projection's entry
   *     limit, so one evaluation's facts can all stay resident
   */
  public CachedKnowledgeReadability(
      ItemProjectionCache<KnowledgeFact> cache,
      KnowledgeFactRepository repository,
      ProjectionItemVersions versions,
      AuthoritySnapshots authority,
      ReadAdmission admission,
      int maxFacts) {
    this.cache = Objects.requireNonNull(cache);
    this.repository = Objects.requireNonNull(repository);
    this.versions = Objects.requireNonNull(versions);
    this.authority = Objects.requireNonNull(authority);
    this.admission = Objects.requireNonNull(admission);
    if (maxFacts < 1 || maxFacts >= ProjectionItemVersions.MAX_CONFIRMED_ITEMS)
      throw new IllegalArgumentException("Readability fact budget out of range");
    this.maxFacts = maxFacts;
  }

  @Override
  public Optional<Boolean> readable(InformationContext context, UUID revision) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(revision);
    if (!VersionedProjectionCache.callerHasNoOwnWrites()) return Optional.empty();
    var snapshot = authority.current();
    if (snapshot.isEmpty()) return Optional.empty();
    var facts = new Evaluation();
    var verdict =
        KnowledgeReadability.readable(
            KnowledgeReadability.Request.of(context, revision), snapshot.get(), facts);
    if (verdict.isEmpty()) return verdict;
    var stale = versions.staleItems(facts.tokens);
    if (stale.isEmpty()) return verdict;
    // Drop exactly the outdated facts; the next check reloads them and keeps the rest.
    stale.forEach(cache::invalidate);
    return Optional.empty();
  }

  /** The facts and item counters one evaluation read; confined to the calling thread. */
  private final class Evaluation implements KnowledgeReadability.Facts {
    private final Map<SnapshotCache.Key, KnowledgeFact> read = new HashMap<>();
    private final List<ProjectionItemVersions.ItemToken> tokens = new ArrayList<>();

    @Override
    public Optional<KnowledgeFact> revision(UUID id) {
      return fact("knowledge-revision", "revision:", id, () -> repository.revision(id));
    }

    @Override
    public Optional<KnowledgeFact> resource(UUID id) {
      return fact("knowledge-resource", "resource:", id, () -> repository.resource(id));
    }

    private Optional<KnowledgeFact> fact(
        String kind, String prefix, UUID id, Supplier<KnowledgeFact> loader) {
      var key = new SnapshotCache.Key(kind, id.toString());
      var known = read.get(key);
      if (known != null) return Optional.of(known);
      if (read.size() >= maxFacts) return Optional.empty();
      var item = new ProjectionVersions.Item(ProjectionVersions.Domain.READABILITY, prefix + id);
      var result =
          cache.readUnconfirmed(
              key,
              Set.of(item),
              () -> admission.read(FETCH, ReadAdmission.Work.FETCH, loader),
              KnowledgeFact::weight);
      if (result.isEmpty()) return Optional.empty();
      read.put(key, result.get().value());
      tokens.add(result.get().items());
      return Optional.of(result.get().value());
    }
  }
}
