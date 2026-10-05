package io.aeyer.plowshare.server.archive;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Server workspace lookup; remote paths are never returned as this server's filesystem. */
public interface ProjectWorkspaces {
  Optional<ProjectRecord> find(String name);

  Long id(String name);

  /** Private union ownership; even administrators cannot read another account's Personal files. */
  Optional<String> personalOwner(String name);

  /** Includes mandatory server exclusions as well as the project's explicit exclusions. */
  List<Path> effectiveExclusions(ProjectRecord project);
}
