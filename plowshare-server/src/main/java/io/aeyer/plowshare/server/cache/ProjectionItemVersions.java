package io.aeyer.plowshare.server.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authoritative version evidence for item-scoped projections (migration V257).
 *
 * <p>Item counters are transactional rows moved once per writing transaction, at its commit, to a
 * value never used before. A reader captures its items' counters, loads, and later compares the
 * same counters for equality: any write committed after the capture changes at least one of them.
 * Unlike {@link ProjectionVersions.Token}, no pending-transaction evidence is needed, because an
 * uncommitted counter is invisible to both the capture and the load. A notification is never
 * evidence that a token is current.
 */
public interface ProjectionItemVersions {
  /** Upper bound on items one entry may read, including each domain's fan-out item. */
  int MAX_ITEMS = 64;

  /**
   * The counters of exactly the captured items, plus each of their domains' {@link
   * ProjectionVersions.Item#ALL} item. A counter of 0 means the item has never been written.
   * Process-local evidence: callers must not persist it.
   */
  record ItemToken(UUID database, Map<ProjectionVersions.Item, Long> counters) {
    public ItemToken {
      Objects.requireNonNull(database);
      if (counters == null || counters.isEmpty() || counters.size() > MAX_ITEMS)
        throw new IllegalArgumentException("Invalid projection item versions");
      counters = Map.copyOf(counters);
      if (counters.values().stream().anyMatch(value -> value < 0))
        throw new IllegalArgumentException("Invalid projection item counter");
      for (var item : counters.keySet())
        if (!counters.containsKey(ProjectionVersions.Item.all(item.domain())))
          throw new IllegalArgumentException("Item versions must include the domain fallback");
    }

    /** Equality-only identity of the database, the item set and its counters. */
    public SnapshotCache.SourceVersion sourceVersion() {
      var canonical = new StringBuilder(database.toString());
      counters.entrySet().stream()
          .sorted(
              Comparator.comparing(
                      (Map.Entry<ProjectionVersions.Item, Long> entry) -> entry.getKey().domain())
                  .thenComparing(entry -> entry.getKey().address()))
          .forEach(
              entry ->
                  canonical
                      .append('|')
                      .append(entry.getKey().domain())
                      .append(':')
                      .append(entry.getKey().address())
                      .append('=')
                      .append(entry.getValue()));
      try {
        return new SnapshotCache.SourceVersion(
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(StandardCharsets.UTF_8))));
      } catch (NoSuchAlgorithmException unavailable) {
        throw new IllegalStateException("Required SHA-256 implementation unavailable", unavailable);
      }
    }

    /** One cache dependency per captured item, so a keyed notification reaches this entry. */
    public Set<SnapshotCache.Dependency> dependencies() {
      return counters.keySet().stream()
          .map(ProjectionVersions.Item::dependency)
          .collect(Collectors.toUnmodifiableSet());
    }
  }

  /**
   * Reads the counters of {@code items} and their domains' fallback items in one independent
   * read-only statement, after checking that the caller's own transaction may share a value (the
   * same guard as {@link #canShare}). Named apart from {@link ProjectionVersions#capture} because
   * one repository implements both. Returns empty for an unsafe caller or a database identity
   * mismatch. Database failures propagate, without retry.
   *
   * @param items one to {@link #MAX_ITEMS} items, counting the fallback items this adds
   */
  Optional<ItemToken> captureItems(Set<ProjectionVersions.Item> items);

  /**
   * Whether every counter in {@code token} still has its captured value, compared in one indexed
   * statement on a fresh independent connection, never the caller's historical snapshot.
   */
  boolean currentItems(ItemToken token);

  /** The caller-bound guard of {@link ProjectionVersions#canShare}. */
  boolean canShare();
}
