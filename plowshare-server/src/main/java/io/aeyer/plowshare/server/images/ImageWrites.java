package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.server.blobs.DiskBlobFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Image compatibility facade over the shared disk fence and durable writer. */
final class ImageWrites {
  private ImageWrites() {}

  static <T> T locked(Path directory, Supplier<T> action) {
    return DiskBlobFiles.locked(directory, action);
  }

  static void publish(Path target, byte[] bytes) throws IOException {
    DiskBlobFiles.publish(target, bytes);
  }

  static void forceFile(Path file) throws IOException {
    DiskBlobFiles.forceFile(file);
  }

  static void forceDirectory(Path directory) throws IOException {
    DiskBlobFiles.forceDirectory(directory);
  }
}
