package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.FileStoreReference;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Administrator-admitted host placement, independent of source lifecycle and account grants. */
public record ApplicationPlacement(
    FileStoreReference applicationRoot, List<FileStoreReference> writableAreas) {
  public ApplicationPlacement {
    Objects.requireNonNull(applicationRoot, "Application root");
    writableAreas = List.copyOf(writableAreas);
    if (writableAreas.size() > 100 || new HashSet<>(writableAreas).size() != writableAreas.size())
      throw new IllegalArgumentException("Use at most 100 distinct Application writable areas");
  }
}
