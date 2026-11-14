package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.cache.ItemProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionItemVersions;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.Objects;
import java.util.Optional;
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
 * <p>{@link #fetchIfReadable} serves the catalogue's single-revision row reads with the same
 * statement: the row is fetched between the evaluation and the confirmation, so the confirmation
 * also vouches for the fetched row.
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
  static final String FETCH = KnowledgeFactSource.FETCH;

  private final KnowledgeFactSource facts;
  private final ProjectionItemVersions versions;
  private final AuthoritySnapshots authority;

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
    this(new KnowledgeFactSource(cache, repository, admission, maxFacts), versions, authority);
  }

  /** Reads facts from {@code facts}, which log visibility may share. */
  CachedKnowledgeReadability(
      KnowledgeFactSource facts, ProjectionItemVersions versions, AuthoritySnapshots authority) {
    this.facts = Objects.requireNonNull(facts);
    this.versions = Objects.requireNonNull(versions);
    this.authority = Objects.requireNonNull(authority);
  }

  @Override
  public Optional<Boolean> readable(InformationContext context, UUID revision) {
    return switch (fetchIfReadable(context, revision, () -> Optional.of(Boolean.TRUE))) {
      case Guarded.Answered<Boolean> answered -> Optional.of(answered.row().isPresent());
      case Guarded.Declined<Boolean> declined -> Optional.empty();
    };
  }

  /**
   * Evaluates the rule on unconfirmed facts, runs {@code fetch} only for a readable verdict, and
   * then confirms every fact the evaluation read with the one {@link
   * ProjectionItemVersions#staleItems} statement this check always issues.
   *
   * <h2>The gap between the decision and the fetch</h2>
   *
   * <p>The confirmation runs after the fetch, on a fresh connection, so its empty result proves
   * that no write to any fact the verdict depends on committed between that fact's load and the
   * confirmation. The fetch's own statement snapshot lies inside that interval (the caller holds no
   * transaction or a read-only READ COMMITTED one, so its statement sees everything committed
   * before it), hence every data-side input of the verdict (availability, the allowance row,
   * exclusion and quarantine, root and navigation policies, inputs, links, navigation heads and
   * reference obligations, over the whole dependency closure) was, at the fetch, exactly what the
   * verdict used: the row is returned only if the permission-filtered statement, run in place of
   * the fetch, would have returned it. A stale item is invalidated and the read is declined, so the
   * caller's permission-filtered statement answers and the fetched row is discarded. Confirming
   * before the fetch would not be enough: a dependency withdrawn after that confirmation is not on
   * the fetched row, so no predicate of the fetch could catch it. The authority half is the
   * snapshot's notification-trusted contract, as for {@link #readable}: a committed membership,
   * grant, token or principal change may apply up to the staleness bound (2 s) later.
   *
   * <p>An exception from {@code fetch} propagates unconfirmed; nothing was returned to disclose.
   */
  @Override
  public <T> Guarded<T> fetchIfReadable(
      InformationContext context, UUID revision, Supplier<Optional<T>> fetch) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(revision);
    Objects.requireNonNull(fetch);
    if (!VersionedProjectionCache.callerHasNoOwnWrites()) return new Guarded.Declined<>();
    var snapshot = authority.current();
    if (snapshot.isEmpty()) return new Guarded.Declined<>();
    var reads = facts.reads();
    var verdict =
        KnowledgeReadability.readable(
            KnowledgeReadability.Request.of(context, revision), snapshot.get(), reads);
    if (verdict.isEmpty()) return new Guarded.Declined<>();
    Optional<T> row = verdict.get() ? Objects.requireNonNull(fetch.get()) : Optional.empty();
    var stale = versions.staleItems(reads.tokens());
    if (stale.isEmpty()) return new Guarded.Answered<>(row);
    // Drop exactly the outdated facts; the next check reloads them and keeps the rest.
    stale.forEach(facts::invalidate);
    return new Guarded.Declined<>();
  }
}
