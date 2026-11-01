package io.aeyer.plowshare.server.blobs;

import java.util.Optional;

/**
 * Shared immutable disk bytes. Owners authorize access before using this internal capability;
 * storage enforces their retained claims but does not resolve user/project permissions.
 */
public interface BinaryBlobStorage {
  /** Finalize bounded bytes with no claim; the owner's later transaction must acquire one. */
  BlobKey prepare(String scope, byte[] bytes);

  /**
   * Media adapter write: prefer the existing legacy location only when content is new. An existing
   * canonical blob wins, so a BLOB-first image gains a sidecar/claim without a compatibility copy.
   */
  void store(BlobSource preferred, byte[] bytes, String alias, BlobClaim claim);

  /** Internal alias lookup; neither existence nor the returned identity confers authority. */
  Optional<BlobKey> resolve(String scope, String alias);

  /** Read through an authorized retained owner, independent of a media alias. */
  byte[] read(BlobKey key, BlobClaim claim);

  /**
   * Verifies a finalized file in place, then atomically registers its alias and initial claim.
   * Caller supplies a trusted disk-adapter source; no file copy or media parsing occurs.
   */
  void adopt(BlobSource source, String alias, BlobClaim claim);

  /**
   * Empty means an alias has never been adopted. An adopted but inaccessible/corrupt/deleted object
   * throws; it must never be interpreted as permission to try legacy resolution.
   */
  Optional<byte[]> read(String scope, String alias, BlobClaim claim);

  /** Acquires the same owning-record slot idempotently inside the caller's database transaction. */
  void acquire(BlobKey key, BlobClaim claim);

  /** Releases exactly one releasable claim inside its owner's deletion transaction. */
  void release(BlobKey key, BlobClaim claim);

  /** One bounded maintenance pass; durable deletion markers make subsequent passes recoverable. */
  int collect(int limit);
}
