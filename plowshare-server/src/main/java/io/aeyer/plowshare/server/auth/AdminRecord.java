package io.aeyer.plowshare.server.auth;

import java.time.OffsetDateTime;

/** An account's password hash, rotation requirement, and server administration role. */
public record AdminRecord(
    String handle,
    String passwordHash,
    boolean mustChangePassword,
    OffsetDateTime createdAt,
    boolean serverAdmin,
    boolean bootstrap,
    boolean enabled,
    String accountKind) {
  public boolean interactive() {
    return "USER".equals(accountKind);
  }

  public AdminRecord(
      String handle,
      String passwordHash,
      boolean mustChangePassword,
      OffsetDateTime createdAt,
      boolean serverAdmin,
      boolean bootstrap,
      boolean enabled) {
    this(
        handle,
        passwordHash,
        mustChangePassword,
        createdAt,
        serverAdmin,
        bootstrap,
        enabled,
        "USER");
  }

  @Override
  public String toString() {
    return "AdminRecord[handle="
        + handle
        + ", passwordHash=<redacted>, serverAdmin="
        + serverAdmin
        + ", bootstrap="
        + bootstrap
        + "]";
  }

  public AdminRecord(
      String handle,
      String passwordHash,
      boolean mustChangePassword,
      OffsetDateTime createdAt,
      boolean serverAdmin,
      boolean bootstrap) {
    this(handle, passwordHash, mustChangePassword, createdAt, serverAdmin, bootstrap, true);
  }

  public AdminRecord(
      String handle, String passwordHash, boolean mustChangePassword, OffsetDateTime createdAt) {
    this(handle, passwordHash, mustChangePassword, createdAt, true, false, true);
  }
}
