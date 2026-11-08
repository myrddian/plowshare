package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises Flyway's fresh install and populated upgrade paths on PostgreSQL. */
@Tag("full-db")
@Testcontainers
class ProductionBaselineDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg16-bookworm");

  @Test
  void baseline_bootstraps_empty_database_and_upgrade_preserves_digests_and_outbox_events() {
    var fresh = dataSource(POSTGRES.getDatabaseName());
    var freshJdbc = new JdbcTemplate(fresh);
    freshJdbc.execute("CREATE DATABASE upgrade_v233");
    var upgrade = dataSource("upgrade_v233");
    var upgradeJdbc = new JdbcTemplate(upgrade);

    // Flyway 10.20.1 can select a baseline newer than the requested target. Build the
    // historical fixture using only the versioned location, then use both locations.
    Flyway.configure().dataSource(upgrade).target("232").load().migrate();
    upgradeJdbc.update("INSERT INTO digests(id,depth,summary) VALUES ('parent',1,'parent')");
    upgradeJdbc.update(
        "INSERT INTO digests(id,depth,summary) VALUES ('child',0,'retained digest')");
    upgradeJdbc.update(
        """
        INSERT INTO relay_source_events(topic,family,subject,state,occurred_at)
        VALUES ('job.started','LIFECYCLE','job-fixture','submitted','2026-10-01T12:00:00Z')
        """);

    assertMigration(fresh, "B233__production_baseline.sql");
    assertMigration(upgrade, "V233__remove_unused_timestamps.sql");
    assertSchemaAndSeeds(freshJdbc);
    assertSchemaAndSeeds(upgradeJdbc);
    assertNotEquals(cacheIdentity(freshJdbc), cacheIdentity(upgradeJdbc));
    assertBoardTitleIsOneLine(freshJdbc);

    // The digest edge trigger still works when it loads a row with the shorter shape.
    upgradeJdbc.update("INSERT INTO digest_children(parent_id,child_id) VALUES ('parent','child')");
    assertEquals(
        "retained digest",
        upgradeJdbc.queryForObject("SELECT summary FROM digests WHERE id='child'", String.class));
    assertEquals(
        1,
        upgradeJdbc.queryForObject(
            """
            SELECT count(*) FROM relay_source_events
            WHERE subject='job-fixture' AND occurred_at='2026-10-01T12:00:00Z'
            """,
            Integer.class));
  }

  private static void assertMigration(DataSource source, String script) {
    var flyway =
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration", "classpath:db/baseline")
            .target("233")
            .load();
    assertEquals(1, flyway.migrate().migrationsExecuted);
    assertEquals(script, flyway.info().current().getScript());
    flyway.validate();
    assertEquals(0, flyway.migrate().migrationsExecuted);
  }

  private static void assertSchemaAndSeeds(JdbcTemplate jdbc) {
    assertEquals(
        0,
        jdbc.queryForObject(
            """
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema='public' AND (table_name,column_name) IN
              (('digests','formed_at'), ('relay_source_events','captured_at'))
            """,
            Integer.class));
    assertTrue(jdbc.queryForObject("SELECT authority_builtin_registry_valid()", Boolean.class));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM embedding_slots", Integer.class));
  }

  private static UUID cacheIdentity(JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT database_id FROM snapshot_cache_identity", UUID.class);
  }

  private static void assertBoardTitleIsOneLine(JdbcTemplate jdbc) {
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES ('fixture','!locked-fixture')");
    jdbc.update(
        """
        INSERT INTO board_topics(id,project,root,depth,title,label,account,opener_kind,opener,
                                 state,pot_total,pot_spent,reserve,opened_at)
        VALUES ('board-fixture','fixture','board-fixture',0,'title','label','fixture','person',
                'fixture','open',20,0,2,now())
        """);
    for (String lineBreak : new String[] {"\r", "\n"}) {
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              jdbc.update(
                  "UPDATE board_topics SET title=? WHERE id='board-fixture'",
                  "line" + lineBreak + "break"));
    }
  }

  private static DataSource dataSource(String database) {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
  }
}
