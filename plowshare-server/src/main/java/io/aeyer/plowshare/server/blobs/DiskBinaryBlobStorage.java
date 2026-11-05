package io.aeyer.plowshare.server.blobs;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Reuses canonical files and finalizes bounded new bytes. No SQL transaction spans verification or
 * unlink; callers attach their own durable claims after preparation.
 */
public final class DiskBinaryBlobStorage implements BinaryBlobStorage {
  private final Path root;
  private final BlobRepository repository;
  private final UnitOfWork work;
  private final Clock clock;
  private final Duration grace;
  private final int maxBytes;

  public DiskBinaryBlobStorage(
      Path root,
      BlobRepository repository,
      UnitOfWork work,
      Clock clock,
      Duration grace,
      int maxBytes) {
    this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    this.repository = Objects.requireNonNull(repository);
    this.work = Objects.requireNonNull(work);
    this.clock = Objects.requireNonNull(clock);
    this.grace = Objects.requireNonNull(grace);
    if (grace.isNegative() || maxBytes < 1)
      throw new IllegalArgumentException("invalid blob bounds");
    this.maxBytes = maxBytes;
  }

  @Override
  public BlobKey prepare(String scope, byte[] bytes) {
    BlobKey.requireScope(scope);
    byte[] snapshot = snapshot(bytes);
    BlobKey key = key(scope, snapshot);
    persist(
        new BlobSource(key, scope + "/blobs/" + key.sha256() + ".bin", snapshot.length),
        snapshot,
        Optional.empty());
    return key;
  }

  private record Attachment(String alias, BlobClaim claim) {}

  @Override
  public void store(BlobSource preferred, byte[] bytes, String alias, BlobClaim claim) {
    BlobRepository.requireAlias(alias);
    byte[] snapshot = snapshot(bytes);
    if (snapshot.length != preferred.byteLength()
        || !key(preferred.key().scope(), snapshot).equals(preferred.key())) {
      throw new BlobUnavailableException();
    }
    persist(preferred, snapshot, Optional.of(new Attachment(alias, Objects.requireNonNull(claim))));
  }

  private void persist(BlobSource preferred, byte[] bytes, Optional<Attachment> attachment) {
    checked(preferred);
    // One scope fence chooses canonical placement, including concurrent image/BLOB-first writes.
    Path scope = root.resolve(preferred.key().scope());
    fenced(
        scope.resolve(".placement"),
        () -> {
          var previous = repository.find(preferred.key());
          var target = previous.map(BlobRepository.Entry::source).orElse(preferred);
          if (target.byteLength() != bytes.length
              || previous.map(e -> e.state() == BlobRepository.State.DELETING).orElse(false)) {
            throw new BlobUnavailableException();
          }
          Path file = checked(target);
          try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
              Files.createDirectories(file.getParent());
              checked(target);
              DiskBlobFiles.publish(file, bytes);
            }
            // Existing corruption is not overwritten. A missing file may be repaired from these
            // exact caller-supplied bytes, without repeating an external effect.
            verify(target, file, false);
            DiskBlobFiles.forceFile(file);
            DiskBlobFiles.forceDirectory(file.getParent());
          } catch (IOException unavailable) {
            throw new BlobUnavailableException();
          }
          work.inTransaction(
              () -> {
                if (attachment.isPresent()) {
                  var value = attachment.get();
                  repository.adopt(target, value.alias(), value.claim(), clock.instant());
                } else repository.register(target, clock.instant());
                return null;
              });
          return null;
        });
  }

  private byte[] snapshot(byte[] bytes) {
    if (bytes == null || bytes.length < 1 || bytes.length > maxBytes) {
      throw new IllegalArgumentException("binary blob exceeds the storage byte allowance");
    }
    return bytes.clone();
  }

  private static BlobKey key(String scope, byte[] bytes) {
    try {
      return new BlobKey(
          scope, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  @Override
  public Optional<BlobKey> resolve(String scope, String alias) {
    return repository.bound(scope, alias).map(entry -> entry.source().key());
  }

  @Override
  public byte[] read(BlobKey key, BlobClaim claim) {
    return readEntry(repository.find(key).orElseThrow(BlobUnavailableException::new), claim);
  }

  @Override
  public void adopt(BlobSource source, String alias, BlobClaim claim) {
    BlobRepository.requireAlias(alias);
    Path file = checked(source);
    fenced(
        file,
        () -> {
          // No byte copy: stream the existing file through the full integrity check.
          verify(source, file, false);
          try {
            DiskBlobFiles.forceFile(file);
            DiskBlobFiles.forceDirectory(file.getParent());
          } catch (IOException failure) {
            throw new BlobUnavailableException();
          }
          work.inTransaction(
              () -> {
                repository.adopt(source, alias, claim, clock.instant());
                return null;
              });
          return null;
        });
  }

  @Override
  public Optional<byte[]> read(String scope, String alias, BlobClaim claim) {
    var bound = repository.bound(scope, alias);
    if (bound.isEmpty()) return Optional.empty();
    return Optional.of(readEntry(bound.get(), claim));
  }

  private byte[] readEntry(BlobRepository.Entry entry, BlobClaim claim) {
    Path file = checked(entry.source());
    return fenced(
        file,
        () -> {
          // Recheck state: cleanup may have marked DELETING while we waited for the disk fence.
          if (!repository.readable(entry, claim)) throw new BlobUnavailableException();
          return verify(entry.source(), file, true);
        });
  }

  @Override
  public void acquire(BlobKey key, BlobClaim claim) {
    repository.acquire(key, claim);
  }

  @Override
  public void release(BlobKey key, BlobClaim claim) {
    repository.release(key, claim, clock.instant());
  }

  @Override
  public int collect(int limit) {
    var candidates =
        work.inTransaction(() -> repository.deletions(clock.instant().minus(grace), limit));
    int deleted = 0;
    for (var entry : candidates) {
      Path file = checked(entry.source());
      boolean removed =
          fenced(
              file,
              () -> {
                // Recheck the generation under the disk fence. Another sweeper may have completed
                // deletion and allowed verified registration while this candidate waited.
                if (!repository.deleting(entry)) return false;
                try {
                  if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    verify(entry.source(), file, false);
                    Files.delete(file);
                  }
                  DiskBlobFiles.forceDirectory(file.getParent());
                } catch (IOException failure) {
                  throw new BlobUnavailableException();
                }
                work.inTransaction(
                    () -> {
                      repository.deleted(entry);
                      return null;
                    });
                return true;
              });
      if (removed) deleted++;
    }
    return deleted;
  }

  /**
   * Reject symlinks below the configured root before any open; root itself may be configured via a
   * link.
   */
  private Path checked(BlobSource source) {
    if (source.byteLength() > maxBytes) throw new BlobUnavailableException();
    Path relative = Path.of(source.file());
    Path current = root;
    for (Path component : relative) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current)) throw new BlobUnavailableException();
    }
    return current;
  }

  private static <T> T fenced(Path file, java.util.function.Supplier<T> action) {
    try {
      return DiskBlobFiles.locked(file.getParent(), action);
    } catch (java.io.UncheckedIOException unavailable) {
      // Lock/directory failures are the same explicit unavailability as a failed file open.
      // Do not leak private paths or turn them into an empty (unadopted) alias result.
      throw new BlobUnavailableException();
    }
  }

  /** Verification streams through a fixed buffer; reads retain only the bounded payload. */
  private byte[] verify(BlobSource source, Path file, boolean retain) {
    checked(source);
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) != source.byteLength()) throw new BlobUnavailableException();
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] result = retain ? new byte[Math.toIntExact(source.byteLength())] : new byte[0];
      try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[8192];
        int offset = 0;
        long remaining = source.byteLength();
        while (remaining > 0) {
          int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
          if (count < 0) throw new BlobUnavailableException();
          digest.update(buffer, 0, count);
          if (retain) System.arraycopy(buffer, 0, result, offset, count);
          offset += count;
          remaining -= count;
        }
        if (input.read() != -1
            || !HexFormat.of().formatHex(digest.digest()).equals(source.key().sha256())) {
          throw new BlobUnavailableException();
        }
      }
      return result;
    } catch (IOException failure) {
      throw new BlobUnavailableException();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
