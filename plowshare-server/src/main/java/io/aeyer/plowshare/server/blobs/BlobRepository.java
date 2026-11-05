package io.aeyer.plowshare.server.blobs;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Specialist persistence boundary; row locks serialize claims with the zero-claim transition. */
public interface BlobRepository {
  enum State {
    READY,
    DELETING,
    DELETED
  }

  /** Private storage metadata; never serialized into public content/log payloads. */
  record Entry(BlobSource source, long generation, State state) {
    public Entry {
      java.util.Objects.requireNonNull(source);
      java.util.Objects.requireNonNull(state);
      if (generation < 1) throw new IllegalArgumentException("invalid blob generation");
    }
  }

  /**
   * Register bytes, alias and first claim together; caller holds the file fence and transaction.
   */
  void adopt(BlobSource source, String alias, BlobClaim claim, Instant now);

  /** Register prepared bytes without an owner; the recovery grace bounds this candidate's life. */
  void register(BlobSource source, Instant now);

  /** Canonical private placement, including tombstones; no caller-selected alternate location. */
  Optional<Entry> find(BlobKey key);

  /** Bounded single-row projection; tombstones retain their aliases. */
  Optional<Entry> bound(String scope, String alias);

  /** Fresh state/claim check after acquiring the file fence, closing the read/delete race. */
  boolean readable(Entry entry, BlobClaim claim);

  /** Caller transaction required; same claim on a different blob is a conflict. */
  void acquire(BlobKey key, BlobClaim claim);

  /** Bind a delivered descriptor's exact length under the same claim-acquisition row lock. */
  void acquire(BlobKey key, BlobClaim claim, long byteLength);

  /** Caller transaction required; pinned claims cannot be released through this capability. */
  void release(BlobKey key, BlobClaim claim, Instant now);

  /** Marks a bounded zero-claim batch DELETING, also returning interrupted deletions for retry. */
  List<Entry> deletions(Instant eligibleThrough, int limit);

  /** Caller owns the file fence. Check exact generation before unlinking a retained deletion. */
  boolean deleting(Entry entry);

  /** Caller transaction required; compare-and-set the completed deletion generation. */
  void deleted(Entry entry);

  static void requireAlias(String alias) {
    if (alias == null || !alias.matches("[A-Za-z0-9_.:-]{1,256}")) {
      throw new IllegalArgumentException("invalid blob alias");
    }
  }
}
