package io.aeyer.plowshare.server.archive;

/** Ordered project roles. Filesystem grants remain a separate, narrower boundary. */
public enum ProjectRole {
  VIEWER,
  CONTRIBUTOR,
  MANAGER;

  public boolean allows(ProjectRole required) {
    return ordinal() >= required.ordinal();
  }

  public static ProjectRole parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new ArchiveRefusedException("Choose VIEWER, CONTRIBUTOR or MANAGER");
    }
  }
}
