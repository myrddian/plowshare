package io.aeyer.plowshare.server.cache;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Database/cache composition. All addresses and credentials come from the configured database. */
@Configuration
@EnableConfigurationProperties(ProjectionCacheProperties.class)
@DependsOnDatabaseInitialization
public class ProjectionCacheConfiguration {
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
      var driver = new java.util.Properties();
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

  @Bean
  public ProjectionVersions projectionVersions(
      JdbcTemplate jdbc, VersionConnections authoritative, ProjectionCacheProperties properties) {
    return new JdbcProjectionVersions(
        jdbc, authoritative.source(), properties.maxPendingTransactions());
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  public ProjectionChangeSignals projectionChangeSignals(
      VersionConnections connections,
      ProjectionVersions versions,
      ProjectionCacheProperties properties) {
    // LISTEN owns one unpooled session. It must not pin a main or version-pool connection.
    var notificationSource =
        new DriverManagerDataSource(
            connections.source().getJdbcUrl(),
            connections.source().getUsername(),
            connections.source().getPassword());
    notificationSource.setDriverClassName(connections.source().getDriverClassName());
    notificationSource.setConnectionProperties(connections.source().getDataSourceProperties());
    return new ListeningProjectionChangeSignals(
        new PostgresProjectionNotificationSource(notificationSource),
        versions,
        properties.maxProjections() + 1, // One additional bounded Relay wakeup bridge.
        properties.notificationPoll(),
        properties.reconnectDelay());
  }

  @Bean(destroyMethod = "close")
  public ProjectionCaches projectionCaches(
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      ProjectionCacheProperties properties) {
    return new ProjectionCaches(versions, signals, properties);
  }
}
