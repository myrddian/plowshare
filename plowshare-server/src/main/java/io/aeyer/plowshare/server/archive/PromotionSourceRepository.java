package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import java.util.Objects;
import java.util.Optional;

/** Durable source home and retained-information safety for an existing promotion proposal. */
public interface PromotionSourceRepository {
  record Source(ScopeId home, String project, boolean publishable) {
    public Source {
      Objects.requireNonNull(home);
      if (project == null || project.isBlank())
        throw new IllegalArgumentException("Source project required");
    }
  }

  Optional<Source> source(String proposal);
}
