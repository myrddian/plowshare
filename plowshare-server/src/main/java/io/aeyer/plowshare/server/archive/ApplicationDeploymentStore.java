package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import io.aeyer.plowshare.protocol.FileStoreReference;
import java.util.Optional;
import java.util.UUID;

/** Owns atomic source activation and its append-only receipt; files are staged before this call. */
public interface ApplicationDeploymentStore {
  record Retained(Release release, FileStoreReference destination, ApplicationPlacement placement) {
    public Retained {
      java.util.Objects.requireNonNull(release);
      java.util.Objects.requireNonNull(destination);
      java.util.Objects.requireNonNull(placement);
      if (destination.path().isEmpty()
          || !placement
              .applicationRoot()
              .equals(
                  new FileStoreReference(
                      destination.store(),
                      destination.path() + "/revisions/" + release.revision())))
        throw new IllegalArgumentException(
            "Retained source must match its destination and revision");
    }
  }

  record Mutation(
      String project,
      UUID requestId,
      UUID expectedRevision,
      String fingerprint,
      Retained release,
      java.nio.file.Path verifiedRoot,
      boolean install) {
    public Mutation {
      java.util.Objects.requireNonNull(project);
      java.util.Objects.requireNonNull(requestId);
      java.util.Objects.requireNonNull(release);
      java.util.Objects.requireNonNull(verifiedRoot);
      if (project.isBlank()
          || !project.equals(project.strip())
          || fingerprint == null
          || !fingerprint.matches("[0-9a-f]{64}")
          || !verifiedRoot.isAbsolute()
          || !install && expectedRevision == null)
        throw new IllegalArgumentException("Invalid deployment mutation");
    }
  }

  Optional<Receipt> receipt(String account, String project, UUID requestId, String fingerprint);

  Retained release(String project, UUID revision);

  Receipt commit(String account, Mutation mutation);

  Status status(String project);
}
