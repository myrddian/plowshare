package io.aeyer.plowshare.server.access;

/**
 * Requests a coalesced committed snapshot refresh without waiting inside the caller's transaction.
 */
public interface AuthoritySnapshotRefresh {
  /** Never publishes the caller's uncommitted state or changes the current grant answer. */
  void request();

  static AuthoritySnapshotRefresh none() {
    return () -> {};
  }
}
