package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ManagedPricingTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource data;
  JdbcTemplate jdbc;
  LlmProperties properties;
  ManagedPricing prices;

  @BeforeAll
  static void migrate() {
    data = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(data);
    jdbc.execute("TRUNCATE admin_pricing,admin_pricing_history,admin_audit,admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES ('owner','fixture',true,false),('reader','fixture',false,false)");
    properties = new LlmProperties();
    var pool = new PoolProperties();
    pool.setName("hosted");
    pool.setModels(List.of("deployment"));
    properties.setPools(List.of(pool));
    var price = new PriceProperties();
    price.setRevision("startup");
    price.setBillingRoute("hosted");
    price.setModel("deployment");
    price.setCurrency("USD");
    price.setRatesPerMillion(Map.of("input", BigDecimal.ONE, "output", new BigDecimal("4")));
    properties.setPricing(Map.of("contract", price));
    prices = restart();
  }

  ManagedPricing restart() {
    return new ManagedPricing(
        properties,
        new JdbcPricingRepository(jdbc, new ObjectMapper().findAndRegisterModules()),
        new AdminStore(jdbc),
        new DataSourceTransactionManager(data));
  }

  PricingAdministration.Change change(String version, String input) {
    return new PricingAdministration.Change(
        "hosted",
        "deployment",
        version,
        "TOKEN",
        "USD",
        new PricingAdministration.Rates(input, "2", null, null),
        List.of(),
        null,
        "operator");
  }

  @Test
  void edit_is_durable_audited_and_keeps_prior_admission_snapshots() {
    var old = prices.select("hosted", "deployment", Instant.now()).orElseThrow();
    var initial = prices.list("owner").getFirst();
    assertEquals("configuration", initial.origin());
    var saved = prices.set("owner", change(initial.version(), "0.25"));
    assertEquals("override", saved.origin());
    assertEquals("0.25", saved.card().rates().input());
    assertNotEquals(initial.version(), saved.version());
    assertEquals(saved.version(), restart().list("owner").getFirst().version());
    assertEquals(BigDecimal.ONE, old.rates().input());
    assertEquals(
        new BigDecimal("0.25"),
        restart().select("hosted", "deployment", Instant.now()).orElseThrow().rates().input());
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM admin_pricing_history", Integer.class));
    assertEquals(
        "pricing.set", jdbc.queryForObject("SELECT action FROM admin_audit", String.class));
    assertThrows(CallerFault.class, () -> prices.set("owner", change(initial.version(), "9")));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM admin_pricing_history", Integer.class));
  }

  @Test
  void regular_users_and_disabled_administrators_cannot_read_or_change_prices() {
    var change = change(prices.list("owner").getFirst().version(), "1");
    assertThrows(CallerFault.class, () -> prices.list("reader"));
    assertThrows(CallerFault.class, () -> prices.set("reader", change));
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='owner'");
    assertThrows(CallerFault.class, () -> prices.list("owner"));
    assertThrows(CallerFault.class, () -> prices.set("owner", change));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_pricing", Integer.class));
  }

  @Test
  void concurrent_editors_cannot_silently_overwrite_each_other() throws Exception {
    var change = change(prices.list("owner").getFirst().version(), "1");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<Boolean> edit =
          () -> {
            start.await();
            try {
              restart().set("owner", change);
              return true;
            } catch (CallerFault stale) {
              return false;
            }
          };
      var a = executor.submit(edit);
      var b = executor.submit(edit);
      start.countDown();
      assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM admin_pricing_history", Integer.class));
  }

  @Test
  void unknown_models_and_invalid_rates_leave_no_override_or_audit() {
    String version = prices.list("owner").getFirst().version();
    for (String invalid : List.of("-1", "1e4", "NaN", "0.0000000000001", ""))
      assertThrows(CallerFault.class, () -> prices.set("owner", change(version, invalid)));
    assertThrows(
        CallerFault.class,
        () ->
            prices.set(
                "owner",
                new PricingAdministration.Change(
                    "hosted", "unknown", version, "UNPRICED", null, null, null, null, null)));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM admin_pricing_history", Integer.class));
  }

  @Test
  void websocket_prices_refuse_numeric_rate_coercion_and_fractional_tiers() {
    var frames = new io.aeyer.plowshare.server.ws.PricingFrames(prices).frames();
    var asking = new io.aeyer.plowshare.server.ws.Asking("session", "owner");
    var payload = new java.util.HashMap<String, Object>();
    payload.put("billingRoute", "hosted");
    payload.put("model", "deployment");
    payload.put("expectedVersion", prices.list("owner").getFirst().version());
    payload.put("mode", "TOKEN");
    payload.put("currency", "USD");
    payload.put("rates", Map.of("input", 0.25, "output", "2"));
    assertThrows(CallerFault.class, () -> frames.get("admin.pricing.set").handle(payload, asking));
    payload.put("rates", Map.of("input", "0.25", "output", "2"));
    payload.put(
        "tiers",
        List.of(Map.of("fromInputTokens", 0.5, "rates", Map.of("input", "1", "output", "2"))));
    assertThrows(CallerFault.class, () -> frames.get("admin.pricing.set").handle(payload, asking));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_pricing", Integer.class));
  }

  @Test
  void explicit_unpriced_and_included_modes_override_startup_prices() {
    var entry = prices.list("owner").getFirst();
    for (String mode : List.of("UNPRICED", "INCLUDED", "ZERO_RATE")) {
      entry =
          prices.set(
              "owner",
              new PricingAdministration.Change(
                  "hosted",
                  "deployment",
                  entry.version(),
                  mode,
                  mode.equals("UNPRICED") ? null : "USD",
                  null,
                  null,
                  null,
                  "operator"));
      assertEquals(
          mode, prices.select("hosted", "deployment", Instant.now()).orElseThrow().mode().name());
    }
  }
}
