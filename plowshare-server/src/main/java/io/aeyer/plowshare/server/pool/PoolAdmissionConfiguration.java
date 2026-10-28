package io.aeyer.plowshare.server.pool;

import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.server.pool.AdmissionThrottle.Sleeper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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

/**
 * Routes the shared {@link JdbcTemplate} and transaction manager through an {@link
 * AdmissionThrottle}, leaving the primary {@link DataSource} untouched.
 *
 * <p><b>Why the shared template and transaction manager.</b> Boot's {@code
 * JdbcTemplateAutoConfiguration} and {@code DataSourceTransactionManagerAutoConfiguration} are both
 * {@code @ConditionalOnMissingBean}, and every repository receives its connections from one of
 * them. Declaring them here over the gated source therefore covers every repository without
 * touching any. The template and the transaction manager must share the one gated source: a
 * transaction binds its connection by {@code DataSource} key, so a template on a different source
 * would take a second connection inside the transaction instead of joining it.
 *
 * <p><b>The primary stays a raw {@link HikariDataSource}.</b> {@code ProjectionCacheConfiguration}
 * does {@code primary instanceof HikariDataSource} to copy the URL, credentials and driver into its
 * separate pool; a decorator in the primary position would fail that test silently and the second
 * pool would fall back to generic properties, possibly a different database. Flyway and the health
 * indicator likewise use the primary directly, which is what the permit margin accounts for.
 *
 * <p><b>The gated source is not a {@code DataSource} bean.</b> Boot only creates its pool when no
 * {@code DataSource} bean exists, so registering a second one would remove the primary altogether.
 * {@link GatedConnections} carries it instead.
 *
 * <p><b>Shutdown.</b> The wrapper owns nothing: it has no {@code close()} and is not a bean. The
 * pool is still the primary bean and Boot's inferred destroy method closes it on context shutdown.
 *
 * <p><b>Parity with Boot.</b> The transaction manager is a {@link JdbcTransactionManager}, which is
 * what Boot builds by default: unlike a plain {@code DataSourceTransactionManager} it translates
 * commit and rollback {@code SQLException}s into Spring's {@code DataAccessException} hierarchy, so
 * swapping the type would silently change the exception every failed commit raises.
 *
 * <p><b>Parity with Boot's template.</b> Replacing Boot's {@code JdbcTemplate} would otherwise
 * silently drop {@code spring.jdbc.template.*}, so those settings are applied here exactly as Boot
 * applies them, and Boot's {@code TransactionManagerCustomizers} are applied to the transaction
 * manager.
 *
 * <p><b>Metrics.</b> When a {@link MeterRegistry} is present the throttle's pressure is published
 * through {@link PoolAdmissionMetrics}; without one (a context without actuator) the throttle runs
 * unobserved rather than failing to start.
 *
 * <p>The pool size and acquire timeout are read from the primary pool itself so the derived bounds
 * are those actually in force. An incompatible ceiling, or a pool too small to define a delay
 * curve, fails startup here.
 */
@Configuration
@EnableConfigurationProperties({PoolAdmissionProperties.class, JdbcProperties.class})
@ConditionalOnProperty(
    prefix = "plowshare.pool-admission",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PoolAdmissionConfiguration {

  /** Holder for the gated source; deliberately not a {@link DataSource}. */
  public record GatedConnections(DataSource source) {}

  @Bean
  AdmissionThrottle admissionThrottle(
      PoolAdmissionProperties properties,
      DataSource primary,
      ObjectProvider<MeterRegistry> registries) {
    if (!(primary instanceof HikariDataSource pool)) {
      throw new IllegalStateException(
          "Pool admission needs the primary DataSource to be a HikariDataSource, but it is "
              + primary.getClass().getName()
              + ". Set plowshare.pool-admission.enabled=false to run without it.");
    }
    properties.validateAgainst(Duration.ofMillis(pool.getConnectionTimeout()));
    int size = pool.getMaximumPoolSize();
    MeterRegistry registry = registries.getIfAvailable();
    PoolAdmissionMetrics metrics = registry == null ? null : new PoolAdmissionMetrics(registry);
    Sleeper sleeper = delay -> Thread.sleep(delay);
    AdmissionThrottle throttle =
        new AdmissionThrottle(
            properties.permits(size),
            properties.threshold(size),
            properties.baseQuantum(),
            properties.ceiling(),
            properties.pressureWindow(),
            System::nanoTime,
            Math::random,
            metrics == null ? sleeper : metrics.instrument(sleeper),
            metrics == null ? waited -> {} : metrics::saturationWaited,
            metrics == null ? () -> {} : metrics::nestedOverdraft);
    if (metrics != null) {
      metrics.bind(registry, throttle);
    }
    return throttle;
  }

  @Bean
  GatedConnections gatedConnections(DataSource primary, AdmissionThrottle throttle) {
    return new GatedConnections(
        new ThrottledDataSource(
            new TracedDataSource(primary, new JfrJdbcExecutionTrace()), throttle));
  }

  @Bean
  JdbcTemplate jdbcTemplate(GatedConnections gated, JdbcProperties properties) {
    JdbcTemplate template = new JdbcTemplate(gated.source());
    JdbcProperties.Template settings = properties.getTemplate();
    template.setFetchSize(settings.getFetchSize());
    template.setMaxRows(settings.getMaxRows());
    if (settings.getQueryTimeout() != null) {
      template.setQueryTimeout((int) settings.getQueryTimeout().toSeconds());
    }
    return template;
  }

  @Bean
  JdbcTransactionManager transactionManager(
      GatedConnections gated, ObjectProvider<TransactionManagerCustomizers> customizers) {
    JdbcTransactionManager manager = new JdbcTransactionManager(gated.source());
    customizers.ifAvailable(c -> c.customize((TransactionManager) manager));
    return manager;
  }
}
