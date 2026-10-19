package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.Optional;
import java.util.UUID;

/** Mocked consumer tests isolate their boundary from the independently tested grant repository. */
public final class GrantLineageFixture {
  private GrantLineageFixture() {}

  public static GrantLineage passthrough() {
    return new GrantLineage() {
      public boolean valid(UUID id) {
        return true;
      }

      public Optional<ProjectRole> projectRole(
          String project, String account, Optional<ProjectRole> ceiling) {
        return ceiling;
      }
    };
  }
}
