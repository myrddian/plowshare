package io.aeyer.plowshare.server.auth;

/** Read-only database availability; implementations must not disclose database details. */
public interface DatabaseReadiness {
  boolean available();
}
