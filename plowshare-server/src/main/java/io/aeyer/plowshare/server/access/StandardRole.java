package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.ProjectRole;

/** Named helpers, not an ordinal permission hierarchy. Wire project roles remain unchanged. */
public enum StandardRole {
  SYSTEM,
  ADMIN,
  MANAGER,
  CONTRIBUTOR,
  READER,
  GLOBAL_READ;

  public static StandardRole project(ProjectRole role) {
    return switch (role) {
      case VIEWER -> READER;
      case CONTRIBUTOR -> CONTRIBUTOR;
      case MANAGER -> MANAGER;
    };
  }
}
