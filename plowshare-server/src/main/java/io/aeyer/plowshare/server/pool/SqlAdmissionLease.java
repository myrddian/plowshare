package io.aeyer.plowshare.server.pool;

/** Separates caller nesting from physical occupancy for explicitly scoped database cleanup. */
interface SqlAdmissionLease {
  void callerFinished();
}
