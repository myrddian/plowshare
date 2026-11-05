package io.aeyer.plowshare.server.auth;

/** Current server-wide authority; project roles and service-token scopes do not grant this role. */
public interface ServerAdministratorAccess {
  /**
   * Checks the current persisted role of an enabled human account. Null, missing, bootstrap and
   * service identities are refused. Call for each operation; a previous result is not an authority
   * lease.
   */
  boolean isServerAdmin(String handle);
}
