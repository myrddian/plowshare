package io.aeyer.plowshare.server.cache;

import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.server.pool.JfrJdbcExecutionTrace;
import io.aeyer.plowshare.server.pool.TracedDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Database/cache composition. All addresses and credentials come from the configured database. */
@Configuration
@EnableConfigurationProperties({
  ProjectionCacheProperties.class,
  ProjectionNotificationProperties.class
})
@DependsOnDatabaseInitialization
public class ProjectionCacheConfiguration {
  private static final int MAX_SIGNAL_OBSERVERS = 256;

  @Bean
  public ProjectionTelemetry projectionTelemetry(ObjectProvider<MeterRegistry> registries) {
    var registry = registries.getIfAvailable();
    return registry == null
        ? ProjectionTelemetry.NONE
        : new MicrometerProjectionTelemetry(registry);
  }

  /** Not a DataSource bean: registering another one would disable Boot's primary pool creation. */
  public record VersionConnections(HikariDataSource source) implements AutoCloseable {
    public void close() {
      source.close();
    }
  }

  /**
   * Independent current-version leases cannot contend for connections held by disclosure
   * transactions in the main pool. This separate, explicitly bounded pool shares only
   * configuration.
   */
  @Bean(destroyMethod = "close")
  public VersionConnections projectionVersionConnections(
      DataSourceProperties database, ProjectionCacheProperties cache, DataSource primary) {
    var connections = projectionVersionConnections(database, cache);
    if (primary instanceof HikariDataSource configured) {
      var source = connections.source();
      // Hikari-specific Boot properties can override the generic datasource properties.
      // Freshness and LISTEN must follow the actual caller database, including those overrides.
      source.setJdbcUrl(configured.getJdbcUrl());
      source.setUsername(configured.getUsername());
      source.setPassword(configured.getPassword());
      source.setDriverClassName(configured.getDriverClassName());
      var driver = new Properties();
      driver.putAll(configured.getDataSourceProperties());
      source.setDataSourceProperties(driver);
      source.setSchema(configured.getSchema());
      source.setCatalog(configured.getCatalog());
      source.setConnectionInitSql(configured.getConnectionInitSql());
    }
    return connections;
  }

  /** Builds the independent pool without opening a socket; also used by configuration tests. */
  public VersionConnections projectionVersionConnections(
      DataSourceProperties database, ProjectionCacheProperties cache) {
    HikariDataSource source =
        database.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    source.setPoolName("projection-versions");
    source.setMinimumIdle(0);
    source.setMaximumPoolSize(cache.versionConnections());
    source.setReadOnly(true);
    return new VersionConnections(source);
  }

  /** One repository supplies both domain and item version evidence from the same pool. */
  @Bean
  public JdbcProjectionVersions projectionVersions(
      JdbcTemplate jdbc, VersionConnections authoritative, ProjectionCacheProperties properties) {
    return new JdbcProjectionVersions(
        jdbc,
        new TracedDataSource(authoritative.source(), new JfrJdbcExecutionTrace()),
        properties.maxPendingTransactions());
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  public ProjectionChangeSignals projectionChangeSignals(
      VersionConnections connections,
      ProjectionVersions versions,
      ProjectionCacheProperties properties,
      ProjectionNotificationProperties notified,
      ProjectionTelemetry telemetry) {
    // LISTEN owns one unpooled session. It must not pin a main or version-pool connection.
    var notificationSource =
        new DriverManagerDataSource(
            connections.source().getJdbcUrl(),
            connections.source().getUsername(),
            connections.source().getPassword());
    notificationSource.setDriverClassName(connections.source().getDriverClassName());
    notificationSource.setConnectionProperties(connections.source().getDataSourceProperties());
    var traced = new TracedDataSource(notificationSource, new JfrJdbcExecutionTrace());
    // Every projection line subscribes once, plus Relay wakeups and information invalidation. Lines
    // are fixed by composition and not counted, so this only bounds a runaway subscriber loop.
    int observers = MAX_SIGNAL_OBSERVERS;
    ProjectionChangeSignals signals;
    if (!notified.enabled()) {
      signals =
          new ListeningProjectionChangeSignals(
              new PostgresProjectionNotificationSource(traced),
              versions,
              observers,
              properties.notificationPoll(),
              properties.reconnectDelay());
    } else {
      // Probes bound how long a silently lost session goes unnoticed, whether or not any cache
      // trusts notifications for disclosure.
      signals =
          new ListeningProjectionChangeSignals(
              new PostgresProjectionNotificationSource(traced, notified.heartbeatTimeout()),
              versions,
              observers,
              properties.notificationPoll(),
              properties.reconnectDelay(),
              notified.heartbeat(),
              notified.heartbeatTimeout());
    }
    telemetry.observeListener(signals, notified);
    return signals;
  }

  @Bean(destroyMethod = "close")
  public ProjectionCaches projectionCaches(
      ProjectionVersions versions,
      ProjectionItemVersions itemVersions,
      ProjectionChangeSignals signals,
      ProjectionCacheProperties properties,
      ProjectionNotificationProperties notified,
      ProjectionTelemetry telemetry) {
    return new ProjectionCaches(
        versions,
        itemVersions,
        signals,
        properties,
        notified.enabled() ? notified.staleness() : null,
        telemetry);
  }
}
