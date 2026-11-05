package io.aeyer.plowshare.server.blobs;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small storage fake for disk tests; real transaction/locking assertions live in full-db tests. */
public class MemoryBlobRepository implements BlobRepository {
  private record Alias(String scope, String alias) {}

  private record Slot(String scope, String kind, String owner, int slot) {}

  private record Held(BlobKey key, BlobClaim claim) {}

  private final ConcurrentMap<BlobKey, Entry> entries = new ConcurrentHashMap<>();
  private final ConcurrentMap<Alias, BlobKey> aliases = new ConcurrentHashMap<>();
  private final ConcurrentMap<Slot, Held> claims = new ConcurrentHashMap<>();
  private final ConcurrentMap<BlobKey, Instant> unclaimed = new ConcurrentHashMap<>();

  @Override
  public synchronized void adopt(BlobSource source, String alias, BlobClaim claim, Instant now) {
    BlobRepository.requireAlias(alias);
    register(source, now);
    var aliasKey = new Alias(source.key().scope(), alias);
    var binding = aliases.get(aliasKey);
    if (binding != null && !binding.equals(source.key())) throw new BlobUnavailableException();
    aliases.put(aliasKey, source.key());
    acquire(source.key(), claim);
  }

  @Override
  public synchronized void register(BlobSource source, Instant now) {
    Entry original = entries.get(source.key());
    if (original != null
        && (!original.source().equals(source) || original.state() == State.DELETING)) {
      throw new BlobUnavailableException();
    }
    long generation =
        original == null ? 1 : original.generation() + (original.state() == State.DELETED ? 1 : 0);
    entries.put(source.key(), new Entry(source, generation, State.READY));
    unclaimed.putIfAbsent(source.key(), now);
  }

  @Override
  public Optional<Entry> find(BlobKey key) {
    return Optional.ofNullable(entries.get(key));
  }

  @Override
  public Optional<Entry> bound(String scope, String alias) {
    BlobKey.requireScope(scope);
    BlobRepository.requireAlias(alias);
    return Optional.ofNullable(aliases.get(new Alias(scope, alias))).map(entries::get);
  }

  @Override
  public synchronized boolean readable(Entry entry, BlobClaim claim) {
    return entry.equals(entries.get(entry.source().key()))
        && entry.state() == State.READY
        && new Held(entry.source().key(), claim)
            .equals(claims.get(slot(entry.source().key(), claim)));
  }

  @Override
  public synchronized void acquire(BlobKey key, BlobClaim claim) {
    Entry entry = entries.get(key);
    if (entry == null || entry.state() != State.READY) throw new BlobUnavailableException();
    Held held = new Held(key, claim);
    Held old = claims.putIfAbsent(slot(key, claim), held);
    if (old != null && !old.equals(held)) throw new BlobUnavailableException();
  }

  @Override
  public synchronized void acquire(BlobKey key, BlobClaim claim, long byteLength) {
    var entry = entries.get(key);
    if (entry == null || entry.source().byteLength() != byteLength)
      throw new BlobUnavailableException();
    acquire(key, claim);
  }

  @Override
  public synchronized void release(BlobKey key, BlobClaim claim, Instant now) {
    if (claim.retention() == BlobClaim.Retention.PINNED)
      throw new IllegalArgumentException("pinned");
    Held held = claims.get(slot(key, claim));
    if (held != null) {
      if (!held.equals(new Held(key, claim))) throw new BlobUnavailableException();
      claims.remove(slot(key, claim));
      unclaimed.put(key, now);
    }
  }

  @Override
  public synchronized List<Entry> deletions(Instant through, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("invalid batch");
    return entries.values().stream()
        .filter(
            entry ->
                entry.state() == State.DELETING
                    || entry.state() == State.READY
                        && !unclaimed.get(entry.source().key()).isAfter(through)
                        && claims.values().stream()
                            .noneMatch(held -> held.key().equals(entry.source().key())))
        .limit(limit)
        .map(
            entry -> {
              Entry marked = new Entry(entry.source(), entry.generation(), State.DELETING);
              entries.put(entry.source().key(), marked);
              return marked;
            })
        .toList();
  }

  @Override
  public synchronized boolean deleting(Entry entry) {
    return entry.state() == State.DELETING && entry.equals(entries.get(entry.source().key()));
  }

  @Override
  public synchronized void deleted(Entry entry) {
    if (!deleting(entry)) throw new BlobUnavailableException();
    entries.put(entry.source().key(), new Entry(entry.source(), entry.generation(), State.DELETED));
  }

  public int claimCount() {
    return claims.size();
  }

  private static Slot slot(BlobKey key, BlobClaim claim) {
    return new Slot(key.scope(), claim.kind(), claim.owner(), claim.slot());
  }
}
