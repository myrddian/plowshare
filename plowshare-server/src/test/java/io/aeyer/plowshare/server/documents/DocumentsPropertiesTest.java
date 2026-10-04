package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The first accessor that reads through the map, and the four answers it owes.
 *
 * <p>Everything before this task wrote to the map or refused a boot over it. This is where a value
 * an operator changed on a running server becomes visible to the code that uses it, so the
 * questions here are not about the store: they are about what a live accessor answers when the map
 * holds a value, when it holds nothing, when it holds something that is not a number, and <b>when
 * it cannot be reached at all</b>.
 *
 * <p><b>The last of those is the one this class exists for.</b> A live accessor is the one reader
 * of the map that has something true to fall back to, which is why {@link RuntimeConfig}'s class
 * comment sends the decision here rather than answering it there — and <em>which</em> true thing it
 * falls back to is a correctness question with a named failure behind it. {@link
 * #an_unreachable_store_answers_with_the_last_value_it_read} is that failure, written as a test.
 *
 * <p>Testcontainers and a real Postgres, following {@code RuntimeConfigTest}: the point of the
 * outage tests is that a connection genuinely fails and arrives as whatever Spring translates it
 * into, and a stubbed store handing back a hand-built {@code DataAccessException} would be
 * asserting that this class catches the exception this class chose to throw.
 *
 * <p>No Spring context. The wiring — {@code @Autowired(required = false)} on the setter — is
 * Spring's own and is exercised by every context test that boots this server; what needs pinning
 * here is the accessor's arithmetic, and a properties object plus a store is the whole of it.
 */
@Testcontainers
class DocumentsPropertiesTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String KEY = "plowshare.documents.ingest-budget";

  /**
   * What the accessor was bound with, standing in for {@code application.yml}'s shipped default —
   * which is 1000, the value {@code implementation rationale} §11 raised it to.
   */
  private static final int BOUND = 1000;

  private static Reachability store;
  private static JdbcTemplate jdbc;

  private RuntimeConfig config;
  private DocumentsProperties properties;
  private ListAppender<ILoggingEvent> heard;

  @BeforeAll
  static void migrate() {
    DataSource real =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(real).load().migrate();
    store = new Reachability(real);
    jdbc = new JdbcTemplate(store);
  }

  /**
   * A fresh store per test, and that is not tidiness: the last-known-good fallback is memory held
   * per {@link RuntimeConfig} instance, so a shared one would carry the previous test's successful
   * read into this one and the outage tests would pass on somebody else's cache.
   */
  @BeforeEach
  void freshMapAndFreshAccessor() {
    store.up = true;
    jdbc.execute("TRUNCATE TABLE runtime_config");
    config = new RuntimeConfig(jdbc);
    properties = new DocumentsProperties();
    properties.setIngestBudget(BOUND);
    properties.setLive(config);
    heard = new ListAppender<>();
    heard.start();
    ((Logger) LoggerFactory.getLogger(RuntimeConfig.class)).addAppender(heard);
  }

  @AfterEach
  void stopListening() {
    ((Logger) LoggerFactory.getLogger(RuntimeConfig.class)).detachAppender(heard);
    store.up = true;
  }

  /** The whole point of the slice: a write, and the accessor says so. */
  @Test
  void a_live_accessor_reflects_a_write() {
    config.put(KEY, "1200", "enzo");

    assertEquals(1200, properties.ingestBudgetNow());
  }

  /**
   * The cost of setter injection, pinned: a properties object built by hand in a test has no {@link
   * RuntimeConfig} and must still answer.
   *
   * <p>Not hypothetical — {@code DocumentsConfigTest} constructs one exactly this way to assert
   * what an unbound instance holds, and every properties class that goes live after this one
   * inherits the same hazard.
   */
  @Test
  void a_properties_object_with_no_runtime_config_returns_its_bound_value() {
    DocumentsProperties alone = new DocumentsProperties();
    alone.setIngestBudget(1000);

    assertEquals(1000, alone.ingestBudgetNow());
  }

  /**
   * A key nobody has ever set is the bound value, <b>and it is silent</b>.
   *
   * <p>The silence is half the assertion. "No override is set" and "an override exists and could
   * not be read" are two states, they produce the same number, and the only thing that tells an
   * operator which one they are in is that the second one says so. Warning here would make the
   * warning meaningless on the first boot of every deployment, which is the ordinary case; not
   * warning below would hide an outage behind it.
   */
  @Test
  void a_key_no_operator_has_set_is_the_bound_value_and_is_not_a_warning() {
    assertEquals(BOUND, properties.ingestBudgetNow());

    assertEquals("", warnings(), "an absent key is not a failure to read one");
  }

  /**
   * The store is read on every call, so a second write lands on the next read and not on some later
   * one.
   *
   * <p>This is the assertion that a cache with no invalidation would fail — the other half of the
   * outage behaviour below, and the reason that cache is a fallback rather than a read-through.
   */
  @Test
  void a_second_write_is_visible_to_the_very_next_read() {
    config.put(KEY, "1200", "enzo");
    assertEquals(1200, properties.ingestBudgetNow());

    config.put(KEY, "1500", "enzo");

    assertEquals(1500, properties.ingestBudgetNow());
  }

  /**
   * <b>The assertion this task is about.</b> An unreachable store answers with the last value it
   * read, and not with the value the server booted with.
   *
   * <p>The failure it refuses has a name. An operator raises {@code ingest-budget} from 300 to
   * 1000, a Postgres blip lands mid-ingest, and a bound-value fallback silently reverts to 300 —
   * the exact number and the exact failure {@code implementation rationale} §11 records the budget
   * work as existing to stop, arriving through the feature that was supposed to end it. What "fall
   * back" has to mean is <em>last known good</em>, not <em>revert</em>.
   *
   * <p>1200 against a bound 1000, so the two answers are distinguishable: the test cannot be
   * satisfied by an accessor that ignored the map entirely.
   */
  @Test
  void an_unreachable_store_answers_with_the_last_value_it_read() {
    config.put(KEY, "1200", "enzo");
    assertEquals(1200, properties.ingestBudgetNow());

    store.up = false;

    assertEquals(
        1200,
        properties.ingestBudgetNow(),
        "the map last said 1200 and Postgres is now unreachable, so 1200 is the last"
            + " known good answer; "
            + BOUND
            + " is the value this server booted"
            + " with, and answering it here is the silent revert TODO.md §11 is"
            + " about");
  }

  /**
   * And the bound value is the floor, for the window where there is nothing better: an outage that
   * starts before the first successful read leaves the accessor with only what Spring bound.
   *
   * <p>Which is also why the fallback cannot be described as "the map's value": for this window
   * there has never been one.
   */
  @Test
  void an_unreachable_store_with_nothing_read_yet_answers_with_the_bound_value() {
    store.up = false;

    assertEquals(BOUND, properties.ingestBudgetNow());
  }

  /**
   * It is loud, and it names the key.
   *
   * <p>{@code ApiExceptionHandler.embeddingUnavailable}'s reasoning: a dependency that is not
   * answering is a fact about the deployment rather than about one caller, so it goes to the
   * operator's log rather than into the value. And it names the key because the whole content of
   * the warning is <em>which</em> setting is now answering from memory — an operator who has just
   * written a value and cannot see it take effect is the reader.
   */
  @Test
  void an_unreachable_store_says_which_key_it_could_not_read() {
    config.put(KEY, "1200", "enzo");
    properties.ingestBudgetNow();

    store.up = false;
    properties.ingestBudgetNow();

    String said = warnings();
    assertTrue(said.contains(KEY), said);
  }

  /**
   * A value that is not a whole number is the bound value, and says so too.
   *
   * <p>Same collapse as the outage, one step further in: an override that exists and cannot be used
   * produces the same number as no override at all, and the operator who typed it is the only
   * person who can fix it. The map takes any text — spec §1.1's stated cost, no write-time
   * validation — so this is a state a {@code PUT} can reach.
   */
  @Test
  void a_value_that_is_not_a_number_is_the_bound_value_and_says_so() {
    config.put(KEY, "as many as it takes", "enzo");

    assertEquals(BOUND, properties.ingestBudgetNow());

    String said = warnings();
    assertTrue(said.contains(KEY), said);
    assertTrue(said.contains("as many as it takes"), said);
  }

  /**
   * The fallback lasts exactly as long as the outage does.
   *
   * <p>A store that came back is read again, so the memory is a fallback and not a cache with a
   * lifetime of its own — an operator who writes during an outage does not have to wait out a
   * window nobody documented after it clears.
   */
  @Test
  void a_store_that_comes_back_is_read_again() {
    config.put(KEY, "1200", "enzo");
    assertEquals(1200, properties.ingestBudgetNow());
    store.up = false;
    assertEquals(1200, properties.ingestBudgetNow());

    store.up = true;
    config.put(KEY, "1500", "enzo");

    assertEquals(1500, properties.ingestBudgetNow());
  }

  /**
   * And a successful read of <em>nothing</em> is a successful read: a row deleted out from under
   * the server puts the accessor back on its bound value rather than on the value that row used to
   * hold.
   *
   * <p>What the fallback promises is that an unreachable store answers what a reachable one last
   * answered. A remembered value that outlived its row would be something else — a value with no
   * author and no way to remove it, on a server whose store is up and says the key is unset.
   */
  @Test
  void a_row_deleted_while_the_store_is_up_puts_the_accessor_back_on_its_bound_value() {
    config.put(KEY, "1200", "enzo");
    assertEquals(1200, properties.ingestBudgetNow());

    jdbc.update("DELETE FROM runtime_config WHERE key = ?", KEY);

    assertEquals(BOUND, properties.ingestBudgetNow());
  }

  /** Everything {@link RuntimeConfig} warned about during one test. */
  private String warnings() {
    return heard.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .reduce("", (all, one) -> all + one + "\n");
  }

  /**
   * Postgres, taken away and given back.
   *
   * <p>A wrapper on the real {@link DataSource} rather than a stubbed {@link RuntimeConfig}, so the
   * accessor meets the exception Spring's own translation produces from a connection that could not
   * be opened. A stub throwing a hand-picked {@code DataAccessException} would assert that this
   * code catches the type this test chose, which is the test writing both halves of its own answer.
   *
   * <p>Stopping the container would be the more faithful outage and it is not available: the
   * container is {@code static} and shared by every test in the class, so taking it down would take
   * the fixture with it.
   */
  private static final class Reachability extends DelegatingDataSource {

    private volatile boolean up = true;

    Reachability(DataSource real) {
      super(real);
    }

    @Override
    public Connection getConnection() throws SQLException {
      refuseIfDown();
      return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      refuseIfDown();
      return super.getConnection(username, password);
    }

    private void refuseIfDown() throws SQLException {
      if (!up) {
        throw new SQLException("this test has taken Postgres away");
      }
    }
  }
}
