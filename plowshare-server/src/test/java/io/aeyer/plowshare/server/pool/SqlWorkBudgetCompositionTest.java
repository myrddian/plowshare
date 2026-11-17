package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

class SqlWorkBudgetCompositionTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rawPrimaryAndTemplateTransactionIdentitySurviveBothAdmissionModes(boolean admission) {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class))
        .withUserConfiguration(PoolAdmissionConfiguration.class, SqlWorkBudgetConfiguration.class)
        .withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://fixture.invalid/none",
            "spring.datasource.hikari.maximum-pool-size=20",
            "plowshare.pool-admission.enabled=" + admission,
            "spring.jdbc.template.fetch-size=17",
            "spring.jdbc.template.max-rows=19")
        .run(
            context -> {
              assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class));
              var jdbc = context.getBean(JdbcTemplate.class);
              assertInstanceOf(ScopedSqlDataSource.class, jdbc.getDataSource());
              assertSame(
                  jdbc.getDataSource(),
                  context.getBean(JdbcTransactionManager.class).getDataSource());
              assertEquals(17, jdbc.getFetchSize());
              assertEquals(19, jdbc.getMaxRows());
              assertInstanceOf(SqlWorkBudget.class, context.getBean(SqlWorkBudget.class));
            });
  }
}
