package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TickerTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private ScheduleStore schedules;
  private TriggerStore triggers;
  private FiringStore firings;
  private FakeRunner runner;
  private Intake intake;
  private Ticker ticker;
  private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");
  // DispatcherTest's own reason: a strictly advancing clock keeps every firing's
  // own arrived_at distinct, so an ORDER BY over several firings arriving at one
  // fixed instant is not decided by MemoryIds.mint's tie-breaking randomness.
  private final AtomicLong ticks = new AtomicLong();
  private final Supplier<Instant> clock = () -> T0.plusMillis(ticks.getAndIncrement());

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /**
   * Copied from DispatcherTest: starts nothing; remembers each start so a test can end it by hand.
   */
  static final class FakeRunner implements Dispatcher.Runner {
    final Map<String, BiConsumer<String, Outcome>> running = new LinkedHashMap<>();
    final List<String> utterances = new ArrayList<>();
    final Set<String> speakingIn = new HashSet<>();
    String refuseWith;
    boolean becomesBusyOnStart;
    int jobs;

    @Override
    public boolean busy(TriggerRecord t) {
      return t.conversation() != null && speakingIn.contains(t.conversation());
    }

    @Override
    public String start(TriggerRecord t, String utterance, BiConsumer<String, Outcome> ended) {
      if (becomesBusyOnStart) {
        speakingIn.add(t.conversation());
        throw new IllegalStateException("conversation claimed underneath us");
      }
      if (refuseWith != null) {
        throw new IllegalStateException(refuseWith);
      }
      String job = "job_" + (++jobs);
      running.put(job, ended);
      utterances.add(utterance);
      return job;
    }

    void end(String job, String conversation) {
      running
          .remove(job)
          .accept(conversation, new Outcome(Outcome.Ending.ANSWERED, "done: " + job, 1, 1, ""));
    }
  }

  @BeforeEach
  void wire() {
    jdbc.execute("TRUNCATE TABLE user_inbox, firings, triggers, schedules, admins CASCADE");
    jdbc.update(
        "INSERT INTO admins (handle, password_hash, server_admin) VALUES ('enzo', 'h', TRUE)");
    schedules = new ScheduleStore(jdbc);
    triggers = new TriggerStore(jdbc);
    firings = new FiringStore(jdbc);
    runner = new FakeRunner();
    Dispatcher dispatcher =
        new Dispatcher(
            firings,
            triggers,
            runner,
            new Inbox(new InboxStore(jdbc), AccountPushes.NONE, clock),
            clock);
    intake = new Intake(triggers, firings, dispatcher, clock);
    ticker = new Ticker(schedules, intake);
  }

  @Test
  void a_due_schedule_fires_once_and_moves_to_its_next_time() {
    schedules.define(
        "nine",
        CronSchedule.parse("0 0 9 * * *", "UTC"),
        "daily",
        "enzo",
        Instant.parse("2026-09-13T08:00:00Z"));
    Instant at = Instant.parse("2026-09-13T09:00:05Z");
    assertEquals(1, ticker.tick(at));
    assertEquals(0, ticker.tick(at));
    assertEquals(
        Instant.parse("2026-09-14T09:00:00Z"), schedules.find("nine").orElseThrow().nextFireAt());
    FiringRecord fired = firings.list(null, null, 0, 10).get(0);
    assertEquals("nine", fired.schedule());
    assertEquals(Instant.parse("2026-09-13T09:00:00Z"), fired.fireAt());
  }

  @Test
  void two_tickers_on_one_database_fire_a_tick_once() {
    schedules.define(
        "nine",
        CronSchedule.parse("0 0 9 * * *", "UTC"),
        "daily",
        "enzo",
        Instant.parse("2026-09-13T08:00:00Z"));
    Ticker other = new Ticker(new ScheduleStore(jdbc), intake);
    Instant at = Instant.parse("2026-09-13T09:00:05Z");
    assertEquals(1, ticker.tick(at) + other.tick(at));
  }

  @Test
  void at_boot_a_long_missed_tick_is_rolled_forward_without_firing() {
    schedules.define(
        "nine",
        CronSchedule.parse("0 0 9 * * *", "UTC"),
        "daily",
        "enzo",
        Instant.parse("2026-09-13T08:00:00Z"));
    Instant boot = Instant.parse("2026-09-13T14:00:00Z");
    assertEquals(1, ticker.rollForward(boot, java.time.Duration.ofSeconds(30)));
    assertEquals(0, ticker.tick(boot));
    assertTrue(firings.list(null, null, 0, 10).isEmpty());
  }

  @Test
  void at_boot_a_tick_inside_the_grace_is_left_for_a_ticker_to_fire() {
    schedules.define(
        "nine",
        CronSchedule.parse("0 0 9 * * *", "UTC"),
        "daily",
        "enzo",
        Instant.parse("2026-09-13T08:00:00Z"));
    Instant boot = Instant.parse("2026-09-13T09:00:10Z");
    assertEquals(0, ticker.rollForward(boot, java.time.Duration.ofSeconds(30)));
    assertEquals(1, ticker.tick(boot));
  }

  @Test
  void one_broken_schedule_does_not_stop_the_others() {
    schedules.define(
        "nine",
        CronSchedule.parse("0 0 9 * * *", "UTC"),
        "daily",
        "enzo",
        Instant.parse("2026-09-13T08:00:00Z"));
    jdbc.update(
        "INSERT INTO schedules (name, cron, zone, emits, next_fire_at, defined_by)"
            + " VALUES ('broken', 'not a cron', 'UTC', 'daily', '2026-09-13T08:59:00Z', 'enzo')");
    assertEquals(1, ticker.tick(Instant.parse("2026-09-13T09:00:05Z")));
  }
}
