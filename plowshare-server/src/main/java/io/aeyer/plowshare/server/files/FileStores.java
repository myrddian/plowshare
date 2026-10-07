package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.archive.ApplicationPlacement;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Host-local alias resolution. No remote fallback and no implicit account or agent grants. */
public interface FileStores {
  record Placement(Path root, List<Path> writableAreas) {
    public Placement {
      writableAreas = List.copyOf(writableAreas);
    }
  }

  /** Resolve every reference against one validated registry snapshot; missing aliases refuse. */
  Placement resolve(ApplicationPlacement placement);

  /** Direct filesystem access is independent of an Application's runtime write admission. */
  boolean permits(FileStoreReference reference, String account, ProjectRole role);

  /** The registry is private server configuration and must be fenced from file operations. */
  Optional<Path> configurationFile();

  FileStores NONE =
      new FileStores() {
        public Placement resolve(ApplicationPlacement placement) {
          throw new WorkspaceRefusedException(
              "Configure this server's filestore.js before using FileStore references");
        }

        public boolean permits(FileStoreReference reference, String account, ProjectRole role) {
          return false;
        }

        public Optional<Path> configurationFile() {
          return Optional.empty();
        }
      };
}
