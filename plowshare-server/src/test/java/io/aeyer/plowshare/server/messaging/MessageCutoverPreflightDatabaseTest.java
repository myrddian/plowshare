package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.MessageCutoverPreflight;
import io.aeyer.plowshare.server.board.BoardStore;
import io.aeyer.plowshare.server.board.BoardTopic;
import java.time.Instant;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Requires actual pre-V243 schema, SQL corruption fixtures and Flyway callback rollback behavior.
 */
@Tag("full-db")
@Testcontainers
class MessageCutoverPreflightDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void orphan_project_is_refused_before_any_cutover_ddl() {
    var fixture = fixture("orphan");
    fixture.jdbc().update("UPDATE board_message_instances SET project='missing-project'");
    var failed = assertThrows(FlywayException.class, () -> migrate(fixture.source()));
    assertTrue(messageChain(failed).contains("V243 messaging preflight refused"));
    unchanged(fixture.jdbc());
  }

  @Test
  void corrupt_allowance_is_refused_without_rewriting_shipped_migration() {
    var fixture = fixture("allowance");
    // The ordinary insertion trigger supplies this allowance. Disable only user triggers in this
    // isolated corruption fixture to represent an inconsistent historical/manual data repair.
    fixture.jdbc().execute("ALTER TABLE board_topics DISABLE TRIGGER USER");
    fixture.jdbc().update("UPDATE board_topics SET allowance_id=NULL");
    fixture.jdbc().execute("ALTER TABLE board_topics ENABLE TRIGGER USER");
    var failed = assertThrows(FlywayException.class, () -> migrate(fixture.source()));
    assertTrue(messageChain(failed).contains("V243 messaging preflight refused"));
    unchanged(fixture.jdbc());
  }

  @Test
  void valid_legacy_instance_passes_preflight_and_keeps_its_identity() {
    var fixture = fixture("valid");
    migrate(fixture.source());
    assertEquals(
        1,
        fixture
            .jdbc()
            .queryForObject(
                "SELECT count(*) FROM message_instances WHERE id='ins_Fixture'", Integer.class));
    assertFalse(
        Boolean.TRUE.equals(
            fixture
                .jdbc()
                .queryForObject(
                    "SELECT to_regclass('board_message_instances') IS NOT NULL", Boolean.class)));
  }

  private static Fixture fixture(String suffix) {
    // Each database owns its vector extension; separate schemas would share the extension while
    // hiding its type from later fixtures' search paths. Suffixes are this test's fixed allowlist.
    if (!Set.of("orphan", "allowance", "valid").contains(suffix))
      throw new IllegalArgumentException("Unknown fixture");
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    admin.execute("CREATE DATABASE preflight_" + suffix);
    String url =
        POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/preflight_" + suffix);
    var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("242").load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('owner','fixture')");
    jdbc.update("INSERT INTO projects(name,workspace) VALUES('project','fixture')");
    var conversations = new ConversationStore(jdbc);
    String log = conversations.open(Home.of("project"), Budget.of(10), null, "owner").id();
    var board = new BoardStore(jdbc, Instant::now);
    var topic =
        board.openRoot(
            new BoardStore.NewTopic(
                "project",
                "Messages",
                "MESSAGES",
                "owner",
                BoardTopic.BY_AGENT,
                "worker",
                log,
                10,
                1));
    jdbc.update(
        "INSERT INTO board_message_instances(id,account,project,agent,conversation,topic,lifetime) VALUES('ins_Fixture','owner','project','worker',?,?,'persistent')",
        log,
        topic.id());
    return new Fixture(source, jdbc);
  }

  private static void migrate(DriverManagerDataSource source) {
    Flyway.configure().dataSource(source).callbacks(new MessageCutoverPreflight()).load().migrate();
  }

  private static void unchanged(JdbcTemplate jdbc) {
    assertTrue(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT to_regclass('board_message_instances') IS NOT NULL", Boolean.class)));
    assertFalse(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT to_regclass('message_instances') IS NOT NULL", Boolean.class)));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM board_message_instances", Integer.class));
  }

  private static String messageChain(Throwable failure) {
    StringBuilder value = new StringBuilder();
    for (Throwable next = failure; next != null; next = next.getCause())
      value.append(next.getMessage()).append('\n');
    return value.toString();
  }

  private record Fixture(DriverManagerDataSource source, JdbcTemplate jdbc) {}
}
