package io.aeyer.plowshare.server.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/**
 * Filesystem publication helper; callers own authorization and participating-writer serialization.
 */
final class AtomicFileWrites {
  private AtomicFileWrites() {}

  /**
   * Publishes a complete replacement, or fails without truncating the destination. COPY_ATTRIBUTES
   * retains the platform's supported metadata; the original must also be writable. This changes
   * inode identity, so multiply linked files are refused. An atomic move is mandatory: no in-place
   * fallback. Neither this move nor flushing the staged file promises directory-entry durability
   * across machine failure, or compare-and-swap against an external filesystem writer.
   */
  static void replace(Path target, String content, Runnable recheck) throws IOException {
    Path staged = target.resolveSibling(".plowshare-write-" + UUID.randomUUID() + ".tmp");
    BasicFileAttributes before = null;
    try {
      try {
        before = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException absent) {
        // Ordinary writes may create; edits deliberately do not use this publication path.
      }
      if (before != null) {
        if (!before.isRegularFile()) throw new IOException("Replacement requires a regular file");
        if (Files.getFileStore(target).supportsFileAttributeView("unix")
            && ((Number) Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                    .longValue()
                != 1)
          throw new IOException("Atomic replacement of a multiply linked file is unsafe");
        // Rename authority comes from the directory. Do not let it bypass file write permission.
        try (var writable =
            FileChannel.open(target, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
          if (writable.size() != before.size())
            throw new IOException("The destination changed before staging");
          Files.copy(target, staged, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        }
      }
      try (var output =
          FileChannel.open(
              staged,
              before == null ? StandardOpenOption.CREATE_NEW : StandardOpenOption.WRITE,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING,
              LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer bytes =
            StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(content));
        while (bytes.hasRemaining()) output.write(bytes);
        output.force(true);
      }
      BasicFileAttributes current = null;
      try {
        current =
            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException absent) {
        // Detect disappearance below; do not publish against a changed destination identity.
      }
      if ((before == null) != (current == null)
          || (before != null && !java.util.Objects.equals(before.fileKey(), current.fileKey())))
        throw new IOException("The destination changed while preparing its replacement");
      recheck.run();
      Files.move(
          staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(staged);
    }
  }
}
