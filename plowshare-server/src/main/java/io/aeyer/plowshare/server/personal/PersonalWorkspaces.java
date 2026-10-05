package io.aeyer.plowshare.server.personal;

import java.nio.file.Path;
import java.util.Optional;

/** Read-only lookup of an initialized private union's physical workspace; grants no authority. */
public interface PersonalWorkspaces {
  Optional<Path> workspace(String project);
}
