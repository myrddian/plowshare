package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.server.blobs.BinaryBlobStorage;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.blobs.BlobSource;
import io.aeyer.plowshare.server.blobs.BlobUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Trusted existing-layout adapter. Media IDs/metadata stay outside the generic blob registry. */
public final class DiskImageBlobAccess implements ImageBlobAccess {
  private final Path root;
  private final BinaryBlobStorage blobs;

  public DiskImageBlobAccess(Path root, BinaryBlobStorage blobs) {
    this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    this.blobs = Objects.requireNonNull(blobs);
  }

  @Override
  public void validate(Path directory, StoredImage image) {
    scope(directory);
    check(directory.resolve(image.recordName()));
    check(directory.resolve(image.fileName()));
  }

  @Override
  public void store(Path directory, StoredImage image, byte[] bytes, String sha256) {
    if (image.inWorkspace())
      throw new IllegalArgumentException("workspace note is not a held blob");
    validate(directory, image);
    if (!image.id().equals("img_" + sha256.substring(0, 32))) throw new BlobUnavailableException();
    String file =
        root.relativize(directory.resolve(image.fileName()).toAbsolutePath().normalize())
            .toString()
            .replace(java.io.File.separatorChar, '/');
    blobs.store(
        new BlobSource(new BlobKey(scope(directory), sha256), file, image.bytes()),
        bytes,
        image.id(),
        ImageBlobAccess.retained(image.id()));
  }

  @Override
  public boolean adopted(Path directory, StoredImage image) {
    return blobs.resolve(scope(directory), image.id()).isPresent();
  }

  @Override
  public Optional<byte[]> read(Path directory, StoredImage image) {
    if (image.inWorkspace())
      throw new IllegalArgumentException("workspace note is not a held blob");
    validate(directory, image);
    return blobs.read(scope(directory), image.id(), ImageBlobAccess.retained(image.id()));
  }

  private String scope(Path directory) {
    Path normalized = directory.toAbsolutePath().normalize();
    if (!normalized.startsWith(root) || !normalized.getFileName().toString().equals("images")) {
      throw new BlobUnavailableException();
    }
    String scope =
        root.relativize(normalized.getParent()).toString().replace(java.io.File.separatorChar, '/');
    BlobKey.requireScope(scope);
    return scope;
  }

  private void check(Path file) {
    Path current = root;
    for (Path part : root.relativize(file.toAbsolutePath().normalize())) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new BlobUnavailableException();
    }
  }
}
