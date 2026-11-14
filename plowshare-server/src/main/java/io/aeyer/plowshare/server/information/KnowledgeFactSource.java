package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.cache.ItemProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionItemVersions;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
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
 * The shared, item-cached revision and resource facts of design slice 1 ({@code
 * knowledge-facts-v1}), read by every evaluation that needs them: the targeted readability check
 * ({@link CachedKnowledgeReadability}) and the retained inputs of log visibility ({@link
 * CachedLogReadability}). One projection holds them, so both readers share each loaded fact.
 *
 * <p>Each {@link Reads} reads facts unconfirmed and records their item tokens; its owner confirms
 * every token it read with one {@link ProjectionItemVersions#staleItems} statement before it
 * discloses anything derived from them, and invalidates the stale items that reports.
 */
final class KnowledgeFactSource {
  /** FETCH admission principal: the fact cache's own slots, never a per-account budget. */
  static final String FETCH = "knowledge-facts";

  private final ItemProjectionCache<KnowledgeFact> cache;
  private final KnowledgeFactRepository repository;
  private final ReadAdmission admission;
  private final int maxFacts;

  /**
   * @param maxFacts most distinct facts one evaluation may read; at most the projection's entry
   *     limit, so one evaluation's facts can all stay resident
   */
  KnowledgeFactSource(
      ItemProjectionCache<KnowledgeFact> cache,
      KnowledgeFactRepository repository,
      ReadAdmission admission,
      int maxFacts) {
    this.cache = Objects.requireNonNull(cache);
    this.repository = Objects.requireNonNull(repository);
    this.admission = Objects.requireNonNull(admission);
    if (maxFacts < 1 || maxFacts >= ProjectionItemVersions.MAX_CONFIRMED_ITEMS)
      throw new IllegalArgumentException("Readability fact budget out of range");
    this.maxFacts = maxFacts;
  }

  /** A fresh, empty per-evaluation reader. */
  Reads reads() {
    return new Reads();
  }

  /** Drops every fact that read {@code item}; a no-op for items it never cached. */
  void invalidate(ProjectionVersions.Item item) {
    cache.invalidate(item);
  }

  /** The facts and item counters one evaluation read; confined to the calling thread. */
  final class Reads implements KnowledgeReadability.Facts {
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

    /** Every item token read so far, for the owner's single confirmation. */
    List<ProjectionItemVersions.ItemToken> tokens() {
      return List.copyOf(tokens);
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
