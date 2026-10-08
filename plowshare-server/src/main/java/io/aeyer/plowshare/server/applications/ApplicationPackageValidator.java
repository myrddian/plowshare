package io.aeyer.plowshare.server.applications;

import java.nio.file.Path;

/** Validates staged resources without starting jobs, publishing Relay messages or running hooks. */
public interface ApplicationPackageValidator {
  void validate(String project, Path root);
}
