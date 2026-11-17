package io.aeyer.plowshare.server.pool;

import java.sql.Connection;

/** Private infrastructure-only access to the still-owned logical pool handle for exact eviction. */
interface SqlPoolLease {
  Connection poolHandle();

  void callerFinished();
}
