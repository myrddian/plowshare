package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.ProjectRole;

/** Named helpers, not an ordinal permission hierarchy. Wire project roles remain unchanged. */
public enum StandardRole {
  SYSTEM,
  ADMIN,
  MANAGER,
  CONTRIBUTOR,
  READER,
  GLOBAL_READ,
  /**
   * Creates projects; held only at the PROJECT_CREATION scope, issues nothing and reaches no
   * existing resource.
   */
  PROJECT_CREATOR;

  public static StandardRole project(ProjectRole role) {
    return switch (role) {
      case VIEWER -> READER;
      case CONTRIBUTOR -> CONTRIBUTOR;
      case MANAGER -> MANAGER;
    };
  }
}
