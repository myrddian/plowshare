package io.aeyer.plowshare.server.blobs;

import java.util.Optional;

/** Explicit no-data-directory composition; never supplies fake successful storage or claims. */
final class UnavailableBlobStorage implements BinaryBlobStorage {
  @Override
  public BlobKey prepare(String scope, byte[] bytes) {
    throw new BlobUnavailableException();
  }

  @Override
  public void store(BlobSource source, byte[] bytes, String alias, BlobClaim claim) {
    throw new BlobUnavailableException();
  }

  @Override
  public java.util.Optional<BlobKey> resolve(String scope, String alias) {
    throw new BlobUnavailableException();
  }

  @Override
  public byte[] read(BlobKey key, BlobClaim claim) {
    throw new BlobUnavailableException();
  }

  @Override
  public void adopt(BlobSource source, String alias, BlobClaim claim) {
    throw new BlobUnavailableException();
  }

  @Override
  public Optional<byte[]> read(String scope, String alias, BlobClaim claim) {
    throw new BlobUnavailableException();
  }

  @Override
  public void acquire(BlobKey key, BlobClaim claim) {
    throw new BlobUnavailableException();
  }

  @Override
  public void release(BlobKey key, BlobClaim claim) {
    throw new BlobUnavailableException();
  }

  @Override
  public int collect(int limit) {
    return 0;
  }
}
