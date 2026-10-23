package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.pool.PoolAdmissionConfiguration.GatedConnections;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizer;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring tests with Boot's own JDBC autoconfigurations loaded, so that "our beans displace Boot's"
 * is demonstrated rather than assumed. No database is involved: the pool is a real {@link
 * HikariDataSource} whose borrow is replaced by a fake connection.
 */
class PoolAdmissionConfigurationTest {

  private static final int POOL_SIZE = 20;

  /** A real Hikari bean (so size and timeout come from it) that never opens a socket. */
  static final class FakeHikari extends HikariDataSource {
    final List<String> calls = new ArrayList<>();
    final AtomicInteger borrows = new AtomicInteger();

    @Override
    public Connection getConnection() {
      borrows.incrementAndGet();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                calls.add(method.getName());
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                  return method.getName().equals("getAutoCommit");
                }
                if (type == int.class) {
                  return 0;
                }
                return null;
              });
    }
  }

  @Configuration
  static class PrimaryPool {
    @Bean
    FakeHikari dataSource() {
      FakeHikari pool = new FakeHikari();
      pool.setMaximumPoolSize(POOL_SIZE);
      pool.setConnectionTimeout(30_000);
      return pool;
    }
  }

  @Configuration
  static class NonHikariPrimary {
    @Bean
    DataSource dataSource() {
      return (DataSource)
          Proxy.newProxyInstance(
              DataSource.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (proxy, method, args) -> null);
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JdbcTemplateAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class))
          .withUserConfiguration(PrimaryPool.class, PoolAdmissionConfiguration.class);

  @Test
  void the_primary_data_source_is_still_a_raw_hikari_data_source() {
    runner.run(
        context -> {
          // ProjectionCacheConfiguration copies the JDBC settings only when this instanceof holds.
          assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class));
          assertEquals(1, context.getBeansOfType(DataSource.class).size());
          assertFalse(context.getBean(DataSource.class) instanceof ThrottledDataSource);
        });
  }

  @Test
  void the_jdbc_template_and_transaction_manager_use_the_gated_source() {
    runner.run(
        context -> {
          // Boot's conditional-on-missing-bean variants must have backed off, not coexisted.
          assertEquals(1, context.getBeansOfType(JdbcTemplate.class).size());
          assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
          GatedConnections gated = context.getBean(GatedConnections.class);
          assertInstanceOf(ThrottledDataSource.class, gated.source());
          assertSame(gated.source(), context.getBean(JdbcTemplate.class).getDataSource());
          assertSame(gated.source(), context.getBean(JdbcTransactionManager.class).getDataSource());
        });
  }

  @Test
  void flyway_and_the_health_indicator_keep_the_ungated_primary() {
    runner.run(
        context -> {
          // They resolve DataSource by type; the only bean of that type is the raw pool.
          assertSame(context.getBean("dataSource"), context.getBean(DataSource.class));
          assertTrue(context.getBeansOfType(ThrottledDataSource.class).isEmpty());
        });
  }

  @Test
  void a_transaction_holds_one_permit_for_its_whole_span() {
    runner.run(
        context -> {
          JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
          AdmissionThrottle throttle = context.getBean(AdmissionThrottle.class);
          TransactionTemplate transaction =
              new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
          ConnectionCallback<Integer> heldNow = connection -> throttle.held();

          // Read before any statement: only a gated transaction manager has taken the permit by
          // now. On the raw source this is 0 and each statement would take a permit of its own.
          List<Integer> inside =
              transaction.execute(
                  status -> List.of(throttle.held(), jdbc.execute(heldNow), jdbc.execute(heldNow)));

          assertEquals(List.of(1, 1, 1), inside);
          assertEquals(0, throttle.held());
        });
  }

  /**
   * {@code PROPAGATION_REQUIRES_NEW} suspends the outer {@code ConnectionHolder} and borrows again
   * on the same thread, so that thread really does hold two physical connections. The gate bounds
   * units of work, so the second borrow must be visible to the throttle as well as to the pool;
   * otherwise enough concurrently nesting threads exceed the pool and fail on the pool's own
   * acquire timeout, which is the indistinguishable failure this throttle exists to remove.
   */
  @Test
  void a_requires_new_transaction_inside_another_accounts_for_its_second_connection() {
    runner.run(
        context -> {
          AdmissionThrottle throttle = context.getBean(AdmissionThrottle.class);
          FakeHikari pool = context.getBean(FakeHikari.class);
          PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
          TransactionTemplate outer = new TransactionTemplate(manager);
          TransactionTemplate requiresNew = new TransactionTemplate(manager);
          requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

          List<Integer> observed =
              outer.execute(
                  status -> {
                    int heldOutside = throttle.held();
                    int borrowedOutside = pool.borrows.get();
                    List<Integer> inner =
                        requiresNew.execute(
                            nested ->
                                List.of(throttle.held(), pool.borrows.get() - borrowedOutside));
                    return List.of(heldOutside, inner.get(0), inner.get(1));
                  });

          // One permit for the outer transaction; the suspended-and-reborrowed inner transaction
          // takes a second physical connection and a second permit alongside it.
          assertEquals(
              List.of(1, 2, 1),
              observed,
              "outer permits, permits inside REQUIRES_NEW, extra delegate borrows");
          assertEquals(0, throttle.held(), "both permits are returned when the span ends");
        });
  }

  /**
   * The gate is only effective if the real context finds it. Every other test here hands {@link
   * PoolAdmissionConfiguration} to the runner explicitly, which cannot catch the class ceasing to
   * be scannable or moving out of the production base package.
   *
   * <p>This asserts that Spring's own default component-scan filters, applied to the package of
   * {@code @SpringBootApplication}, treat the configuration as a candidate. It does not start the
   * real application context and does not prove the rest of the production configuration is
   * consistent with it.
   */
  @Test
  void default_component_scan_filters_find_the_configuration_in_the_production_base_package() {
    ClassPathScanningCandidateComponentProvider scan =
        new ClassPathScanningCandidateComponentProvider(true);
    Set<String> candidates =
        scan.findCandidateComponents(PlowshareServerApplication.class.getPackageName()).stream()
            .map(BeanDefinition::getBeanClassName)
            .collect(Collectors.toSet());

    assertTrue(
        candidates.contains(PoolAdmissionConfiguration.class.getName()),
        "component scanning from "
            + PlowshareServerApplication.class.getPackageName()
            + " must reach PoolAdmissionConfiguration");
  }

  /** Stands in for the repositories, which receive the shared {@link JdbcTemplate} by injection. */
  record InjectedTemplate(JdbcTemplate jdbc) {}

  @Configuration
  static class Repository {
    @Bean
    InjectedTemplate repository(JdbcTemplate jdbc) {
      return new InjectedTemplate(jdbc);
    }
  }

  /**
   * Picked up by scanning the production base package rather than by being named as a user
   * configuration, so the scan is what registers the gate.
   */
  @Configuration
  @ComponentScan(
      basePackageClasses = PlowshareServerApplication.class,
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = PoolAdmissionConfiguration.class))
  static class ScanForTheGate {}

  /**
   * The template an injected collaborator receives comes from the scanned configuration and is
   * gated. The scan is restricted to this one class by an include filter, so this proves the class
   * is reachable by a scan of the production base package and that its beans gate the shared
   * template; it does not load the rest of the production configuration, which would need a
   * database.
   */
  @Test
  void a_scan_of_the_production_base_package_gates_the_injected_jdbc_template() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JdbcTemplateAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class))
        .withUserConfiguration(PrimaryPool.class, ScanForTheGate.class, Repository.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertNotNull(context.getBean(AdmissionThrottle.class));
              assertInstanceOf(
                  ThrottledDataSource.class,
                  context.getBean(InjectedTemplate.class).jdbc().getDataSource());
              assertSame(
                  context.getBean(JdbcTemplate.class),
                  context.getBean(InjectedTemplate.class).jdbc());
            });
  }

  @Test
  void boots_transaction_manager_customizers_are_applied_to_the_gated_manager() {
    TransactionManagerCustomizer<JdbcTransactionManager> customizer =
        manager -> manager.setDefaultTimeout(42);
    runner
        .withBean(
            TransactionManagerCustomizers.class,
            () -> TransactionManagerCustomizers.of(List.of(customizer)))
        .run(
            context -> {
              JdbcTransactionManager manager = context.getBean(JdbcTransactionManager.class);
              assertEquals(42, manager.getDefaultTimeout());
              assertInstanceOf(ThrottledDataSource.class, manager.getDataSource());
            });
  }

  @Test
  void permits_are_derived_from_the_configured_pool_size() {
    runner.run(
        context -> {
          AdmissionThrottle throttle = context.getBean(AdmissionThrottle.class);
          assertEquals(18, throttle.permits());
          assertEquals(9, throttle.threshold());
        });
    runner
        .withPropertyValues("plowshare.pool-admission.permit-margin=4")
        .run(context -> assertEquals(16, context.getBean(AdmissionThrottle.class).permits()));
  }

  @Test
  void a_ceiling_at_or_above_the_acquire_timeout_fails_the_boot() {
    runner
        .withPropertyValues("plowshare.pool-admission.ceiling=30s")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  rootMessage(context.getStartupFailure()).contains("pool-admission.ceiling"),
                  rootMessage(context.getStartupFailure()));
            });
  }

  @Test
  void a_pool_too_small_to_define_a_curve_fails_the_boot() {
    new ApplicationContextRunner()
        .withUserConfiguration(PrimaryPool.class, PoolAdmissionConfiguration.class)
        .withPropertyValues("plowshare.pool-admission.permit-margin=18")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void a_primary_that_is_not_hikari_fails_the_boot_instead_of_skipping_the_throttle() {
    new ApplicationContextRunner()
        .withUserConfiguration(NonHikariPrimary.class, PoolAdmissionConfiguration.class)
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(rootMessage(context.getStartupFailure()).contains("HikariDataSource"));
            });
  }

  @Test
  void the_configured_pressure_window_reaches_the_built_throttle() {
    runner
        .withPropertyValues("plowshare.pool-admission.pressure-window=7s")
        .run(
            context ->
                assertEquals(
                    Duration.ofSeconds(7),
                    context.getBean(AdmissionThrottle.class).pressureWindow()));
    runner.run(
        context ->
            assertEquals(
                AdmissionThrottle.DEFAULT_PRESSURE_WINDOW,
                context.getBean(AdmissionThrottle.class).pressureWindow()));
  }

  @Test
  void the_transaction_manager_is_the_exception_translating_type_boot_would_have_built() {
    runner.run(
        context ->
            assertInstanceOf(
                JdbcTransactionManager.class, context.getBean(PlatformTransactionManager.class)));
  }

  @Test
  void the_properties_default_window_is_the_throttles_default_constant() {
    // The annotation default must be a string literal, so this is what links the two.
    new ApplicationContextRunner()
        .withUserConfiguration(PrimaryPool.class, PoolAdmissionConfiguration.class)
        .run(
            context ->
                assertEquals(
                    AdmissionThrottle.DEFAULT_PRESSURE_WINDOW,
                    context.getBean(PoolAdmissionProperties.class).pressureWindow()));
  }

  @Test
  void spring_jdbc_template_settings_still_reach_the_replacement_template() {
    runner
        .withPropertyValues(
            "spring.jdbc.template.fetch-size=77",
            "spring.jdbc.template.max-rows=5",
            "spring.jdbc.template.query-timeout=9s")
        .run(
            context -> {
              JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
              assertEquals(77, jdbc.getFetchSize());
              assertEquals(5, jdbc.getMaxRows());
              assertEquals(9, jdbc.getQueryTimeout());
            });
  }

  @Test
  void a_second_data_source_bean_would_make_boot_skip_the_primary_pool() {
    // Pins the reason the gated source is a holder record and not a bean.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
        .withUserConfiguration(NonHikariPrimary.class)
        .withPropertyValues("spring.datasource.url=jdbc:postgresql://db.invalid/none")
        .run(
            context -> {
              assertEquals(1, context.getBeansOfType(DataSource.class).size());
              assertFalse(context.getBean(DataSource.class) instanceof HikariDataSource);
            });
  }

  @Test
  void with_boots_own_pool_the_primary_is_hikari_and_the_wiring_applies() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class))
        .withUserConfiguration(PoolAdmissionConfiguration.class)
        .withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://db.invalid/none",
            "spring.datasource.hikari.maximum-pool-size=20")
        .run(
            context -> {
              assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class));
              assertInstanceOf(
                  ThrottledDataSource.class, context.getBean(JdbcTemplate.class).getDataSource());
              assertEquals(18, context.getBean(AdmissionThrottle.class).permits());
            });
  }

  @Test
  void disabling_the_throttle_leaves_the_autoconfigured_wiring_alone() {
    runner
        .withPropertyValues("plowshare.pool-admission.enabled=false")
        .run(
            context -> {
              assertTrue(context.getBeansOfType(AdmissionThrottle.class).isEmpty());
              assertSame(
                  context.getBean(DataSource.class),
                  context.getBean(JdbcTemplate.class).getDataSource());
              assertSame(
                  context.getBean(DataSource.class),
                  context.getBean(JdbcTransactionManager.class).getDataSource());
            });
  }

  @Test
  void closing_the_context_closes_the_real_pool_through_the_primary_bean() {
    FakeHikari[] pool = new FakeHikari[1];
    runner.run(context -> pool[0] = context.getBean(FakeHikari.class));
    assertTrue(pool[0].isClosed(), "the raw primary bean keeps Boot's shutdown path");
  }

  /**
   * Contract between the two guards: every pool size the properties accept must also be accepted by
   * the real throttle. The properties and the throttle each enforce {@code permits - threshold >=
   * 2} from a separate literal, so this fails if they ever disagree.
   */
  @Test
  void every_pool_size_the_properties_accept_builds_a_real_throttle() {
    int accepted = 0;
    for (double fraction : new double[] {0.1, 0.25, 0.5, 0.75, 0.9}) {
      for (int margin = 0; margin <= 4; margin++) {
        PoolAdmissionProperties properties =
            new PoolAdmissionProperties(
                true,
                margin,
                fraction,
                Duration.ofMillis(20),
                Duration.ofSeconds(3),
                Duration.ofSeconds(10));
        for (int size = 1; size <= 200; size++) {
          int permits;
          int threshold;
          try {
            permits = properties.permits(size);
            threshold = properties.threshold(size);
          } catch (IllegalArgumentException rejected) {
            continue;
          }
          AdmissionThrottle throttle =
              new AdmissionThrottle(
                  permits,
                  threshold,
                  properties.baseQuantum(),
                  properties.ceiling(),
                  properties.pressureWindow(),
                  System::nanoTime,
                  () -> 0.5,
                  delay -> {});
          assertEquals(permits, throttle.permits());
          assertEquals(threshold, throttle.threshold());
          accepted++;
        }
      }
    }
    assertTrue(accepted > 500, "the sweep must exercise real configurations: " + accepted);
  }

  private static String rootMessage(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return String.valueOf(root.getMessage());
  }
}
