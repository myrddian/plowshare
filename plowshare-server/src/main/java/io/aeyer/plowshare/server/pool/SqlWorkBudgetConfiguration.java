package io.aeyer.plowshare.server.pool;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionManager;

/** Explicit SQL scopes are available with or without the existing admission throttle. */
@Configuration
@EnableConfigurationProperties({SqlWorkBudgetProperties.class, JdbcProperties.class})
public class SqlWorkBudgetConfiguration {
  @Bean(destroyMethod = "close")
  ScopedSqlWorkBudget sqlWorkBudget(
      SqlWorkBudgetProperties properties, ObjectProvider<MeterRegistry> metrics) {
    return new ScopedSqlWorkBudget(properties, metrics.getIfAvailable());
  }

  /**
   * The admission-enabled composition decorates its existing holder. When it is disabled this
   * holder preserves a single identity for template and transaction manager without replacing the
   * raw primary Hikari bean or changing unscoped borrow behavior.
   */
  @Configuration
  @ConditionalOnProperty(
      prefix = "plowshare.pool-admission",
      name = "enabled",
      havingValue = "false")
  static class WithoutAdmission {
    record Connections(DataSource source) {}

    @Bean
    Connections sqlWorkConnections(DataSource primary, ScopedSqlWorkBudget budget) {
      return new Connections(budget.decorate(primary));
    }

    @Bean
    JdbcTemplate jdbcTemplate(Connections connections, JdbcProperties properties) {
      JdbcTemplate jdbc = new JdbcTemplate(connections.source());
      JdbcProperties.Template settings = properties.getTemplate();
      jdbc.setFetchSize(settings.getFetchSize());
      jdbc.setMaxRows(settings.getMaxRows());
      if (settings.getQueryTimeout() != null)
        jdbc.setQueryTimeout((int) settings.getQueryTimeout().toSeconds());
      return jdbc;
    }

    @Bean
    JdbcTransactionManager transactionManager(
        Connections connections, ObjectProvider<TransactionManagerCustomizers> customizers) {
      JdbcTransactionManager manager = new JdbcTransactionManager(connections.source());
      customizers.ifAvailable(c -> c.customize((TransactionManager) manager));
      return manager;
    }
  }
}
