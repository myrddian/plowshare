package io.aeyer.plowshare.server.archive;

import java.util.Optional;

/** Retained identity lookup for source loaders; never creates a project. */
public interface ProjectNames {
  Optional<String> nameForId(long id);
}
