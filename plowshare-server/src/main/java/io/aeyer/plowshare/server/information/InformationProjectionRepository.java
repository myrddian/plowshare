package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationContent;
import java.util.*;

/** Owns revision-specific projection snapshots, manifests, remapping and atomic metadata copies. */
public interface InformationProjectionRepository {
  record Manifest(
      UUID revision,
      UUID resource,
      long generation,
      String snapshotHash,
      Map<String, String> configured,
      List<String> reusable) {
    public Manifest {
      Objects.requireNonNull(revision);
      Objects.requireNonNull(resource);
      if (generation < 1) throw new IllegalArgumentException("Invalid source generation");
      snapshotHash = InformationContent.digest(snapshotHash);
      configured = Map.copyOf(configured);
      reusable = List.copyOf(reusable);
      if (configured.size() > 7 || reusable.size() > 5)
        throw new IllegalArgumentException("Invalid reuse plan size");
      configured.forEach(
          (stage, hash) -> {
            if (!InformationCatalogue.STAGES.contains(stage))
              throw new IllegalArgumentException("Unknown processing stage");
            InformationContent.digest(hash);
          });
      for (int index = 0; index < reusable.size(); index++)
        if (!InformationCatalogue.STAGES.get(index).equals(reusable.get(index)))
          throw new IllegalArgumentException("Content reuse must preserve prerequisite order");
    }
  }

  /** Prepared snapshots survive gate interruption; the same request never approves a new plan. */
  record Prepared(Manifest manifest, InformationContent.Binding binding) {
    public Prepared {
      Objects.requireNonNull(manifest);
    }
  }

  Prepared prepare(
      InformationContext context,
      UUID request,
      UUID revision,
      InformationContent.Binding binding,
      Map<String, String> configuration);

  /** Rechecks head/authority/manifest and copies all owned rows inside the caller's transaction. */
  InformationCatalogue.Admission copy(
      InformationContext context,
      UUID request,
      Prepared prepared,
      Map<String, String> currentConfiguration);
}
