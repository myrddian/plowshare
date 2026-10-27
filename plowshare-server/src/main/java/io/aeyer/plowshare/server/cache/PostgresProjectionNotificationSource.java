package io.aeyer.plowshare.server.cache;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Dedicated PostgreSQL listener infrastructure. Composition supplies an explicitly configured
 * unpooled source; this connection is never borrowed from caller transactions or used for domain
 * queries. Driver notification transport is drained each poll; retained local hints are a bounded
 * domain set.
 */
public final class PostgresProjectionNotificationSource implements ProjectionNotificationSource {
  static final String CHANNEL = "plowshare_projection_changes";
  // Older binaries reject unknown domain payloads on CHANNEL. A separate hint channel lets a
  // mixed-version fleet retain its existing projections during a rolling database migration.
  static final String READABILITY_CHANNEL = "plowshare_readability_changes";

  static final String PROJECTION_V2_CHANNEL = "plowshare_projection_changes_v2";

  private final DataSource source;

  public PostgresProjectionNotificationSource(DataSource source) {
    this.source = Objects.requireNonNull(source);
    if (source instanceof TransactionAwareDataSourceProxy)
      throw new IllegalArgumentException(
          "Notification sessions require an independent data source");
  }

  @Override
  public Session open() {
    Connection connection = null;
    try {
      connection = source.getConnection();
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      return new Notifications(connection, connection.unwrap(PGConnection.class));
    } catch (SQLException | RuntimeException failure) {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException ignored) {
          // The original failure is deliberately sanitized below; no deployment details escape.
        }
      }
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    // JDBC messages/causes can contain deployment addresses or authentication values.
    return new IllegalStateException("Projection notification connection unavailable");
  }

  private static final class Notifications implements Session {
    private final Connection connection;
    private final PGConnection postgres;
    private final JdbcTemplate jdbc;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean listening;

    Notifications(Connection connection, PGConnection postgres) {
      this.connection = connection;
      this.postgres = postgres;
      jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @Override
    public void listen() {
      if (closed.get()) throw unavailable();
      try {
        jdbc.execute("LISTEN " + CHANNEL);
        jdbc.execute("LISTEN " + READABILITY_CHANNEL);
        jdbc.execute("LISTEN " + PROJECTION_V2_CHANNEL);
        // LISTEN is transactional: baseline reads are forbidden until this commit has succeeded.
        connection.commit();
        connection.setAutoCommit(true);
        listening = true;
      } catch (SQLException | RuntimeException failure) {
        throw unavailable();
      }
    }

    @Override
    public Set<ProjectionVersions.Domain> poll(Duration timeout) {
      Objects.requireNonNull(timeout);
      long millis = timeout.toMillis();
      if (millis < 1 || millis > Integer.MAX_VALUE)
        throw new IllegalArgumentException("A bounded positive notification timeout is required");
      if (!listening || closed.get()) throw unavailable();
      try {
        PGNotification[] notifications = postgres.getNotifications((int) millis);
        var changed = EnumSet.noneOf(ProjectionVersions.Domain.class);
        if (notifications != null) {
          for (PGNotification notification : notifications) {
            if (!CHANNEL.equals(notification.getName())
                && !READABILITY_CHANNEL.equals(notification.getName())
                && !PROJECTION_V2_CHANNEL.equals(notification.getName())) continue;
            changed.add(
                switch (notification.getParameter()) {
                  case "documents" -> ProjectionVersions.Domain.DOCUMENTS;
                  case "search" -> ProjectionVersions.Domain.SEARCH;
                  case "schedules" -> ProjectionVersions.Domain.SCHEDULES;
                  case "relay" -> ProjectionVersions.Domain.RELAY;
                  case "transcripts" -> ProjectionVersions.Domain.TRANSCRIPTS;
                  case "authority" -> ProjectionVersions.Domain.AUTHORITY;
                  case "observations" -> ProjectionVersions.Domain.OBSERVATIONS;
                  case "readability" -> ProjectionVersions.Domain.READABILITY;
                  case "queues" -> ProjectionVersions.Domain.QUEUES;
                  case "progress" -> ProjectionVersions.Domain.PROGRESS;
                  case "types" -> ProjectionVersions.Domain.TYPES;
                  case "extractions" -> ProjectionVersions.Domain.EXTRACTIONS;
                  default -> throw unavailable();
                });
          }
        }
        return Set.copyOf(changed);
      } catch (SQLException failure) {
        throw unavailable();
      }
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      try {
        // Closing the physical socket also unblocks a concurrent bounded notification poll.
        connection.close();
      } catch (SQLException failure) {
        throw unavailable();
      }
    }
  }
}
