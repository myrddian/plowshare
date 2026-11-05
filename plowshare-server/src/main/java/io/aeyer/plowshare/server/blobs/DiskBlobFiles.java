package io.aeyer.plowshare.server.blobs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Shared disk fences and durable writes for legacy images and registered blobs across JVMs. */
public final class DiskBlobFiles {
  private static final ReentrantLock[] STRIPES = new ReentrantLock[64];
  private static final ThreadLocal<java.util.Set<Path>> HELD =
      ThreadLocal.withInitial(java.util.HashSet::new);

  static {
    for (int i = 0; i < STRIPES.length; i++) STRIPES[i] = new ReentrantLock();
  }

  private DiskBlobFiles() {}

  public static <T> T locked(Path directory, Supplier<T> action) {
    Path named = directory.toAbsolutePath().normalize();
    // Image and generic blob placement must serialize in either arrival order. Both directories
    // share their stable storage-scope fence; callers already holding it can reenter for a read.
    Path key =
        java.util.Set.of("images", "blobs").contains(named.getFileName().toString())
            ? named.getParent()
            : named;
    var local = STRIPES[Math.floorMod(key.hashCode(), STRIPES.length)];
    local.lock();
    try {
      // Snapshot verification can enter through an image hold already fencing this directory.
      // Reuse its OS lock; taking an overlapping FileLock in the same JVM would fail.
      if (HELD.get().contains(key)) return action.get();
      Files.createDirectories(directory);
      // The bounded JVM stripes prevent overlapping FileLocks across multiple ImageStore objects.
      // The project-local OS lock also fences another server process using the same data layout.
      try (var channel =
              FileChannel.open(
                  key.resolveSibling("." + key.getFileName() + ".image-write.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  java.nio.file.LinkOption.NOFOLLOW_LINKS);
          var lock = channel.lock()) {
        if (!lock.isValid()) throw new IOException("image lock was lost");
        HELD.get().add(key);
        try {
          return action.get();
        } finally {
          HELD.get().remove(key);
          if (HELD.get().isEmpty()) HELD.remove();
        }
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("image write lock is unavailable", failure);
    } finally {
      local.unlock();
    }
  }

  /**
   * Caller holds the shared directory lock. At most one bounded staging file per final pathname.
   */
  public static void publish(Path target, byte[] bytes) throws IOException {
    Path staging = target.resolveSibling(target.getFileName() + ".pending");
    try {
      try (var channel =
          FileChannel.open(
              staging,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE,
              java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(
          staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      forceDirectory(target.getParent());
    } finally {
      Files.deleteIfExists(staging);
    }
  }

  public static void forceFile(Path file) throws IOException {
    try (var channel =
        FileChannel.open(file, StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      channel.force(true);
    }
  }

  public static void forceDirectory(Path directory) throws IOException {
    // Files.createDirectories can have created several ancestors (for a first project upload).
    // Force their directory entries as well, so the finalized files remain reachable after a crash.
    for (Path parent = directory; parent != null; parent = parent.getParent()) {
      try (var channel = FileChannel.open(parent, StandardOpenOption.READ)) {
        channel.force(true);
      }
    }
  }
}
