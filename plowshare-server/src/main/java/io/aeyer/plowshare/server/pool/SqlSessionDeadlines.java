package io.aeyer.plowshare.server.pool;

import java.sql.Connection;
import java.sql.SQLException;

/** Database-owned session control; fakes inject this seam without pretending to be PostgreSQL. */
interface SqlSessionDeadlines {
  interface Remaining {
    long millis() throws SQLException;
  }

  interface Session {
    void beforeStatement(Remaining remaining) throws SQLException;

    void restore(Remaining remaining) throws SQLException;
  }

  Session attach(Connection connection);
}
