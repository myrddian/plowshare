package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.archive.ConversationStore;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real pre-V251 data, transactional Flyway refusal and retained source/ID conservation. */
@Tag("full-db")
@Testcontainers
class MessageIngressPreflightDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void
      malformed_source_refuses_before_ddl_and_explicit_repair_preserves_valid_and_absent_evidence() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("250").load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('owner','fixture')");
    jdbc.update("INSERT INTO projects(name,workspace) VALUES('project','fixture')");
    var conversation =
        new ConversationStore(jdbc).open(Home.of("project"), Budget.of(10), null, "owner");
    jdbc.update(
        "INSERT INTO message_instances(id,account,project,agent,conversation,lifetime) VALUES('ins_External','owner','project','external',?,'external'),('ins_Worker','owner','project','worker',?,'persistent')",
        conversation.id(),
        conversation.id());
    var allowance = Budget.of(10).snapshot();
    new JdbcAllowanceRepository(jdbc).retain(allowance);
    UUID context = UUID.randomUUID(), invalid = UUID.randomUUID(), absent = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO message_external_contexts(id,account,project,client,agent,sender,recipient) VALUES(?,'owner','project','client','worker','ins_External','ins_Worker')",
        context);
    for (String message : List.of("bdm_Original", "bdm_Absent")) {
      jdbc.update(
          "INSERT INTO message_records(id,recipient,body,posted_at) VALUES(?,'ins_Worker','Retained body',clock_timestamp())",
          message);
      jdbc.update(
          "INSERT INTO message_routes(message,sender,recipient,reply_expected,allowance_id) VALUES(?,'ins_External','ins_Worker',true,?)",
          message,
          allowance.id().value());
    }
    jdbc.update(
        "INSERT INTO message_external_tasks(id,context,account,project,client,request_id,body,source,message) VALUES(?,?,'owner','project','client',?,'Retained body','{}'::jsonb,'bdm_Original'),(?,?,'owner','project','client',?,'Retained body','null'::jsonb,'bdm_Absent')",
        invalid,
        context,
        UUID.randomUUID(),
        absent,
        context,
        UUID.randomUUID());
    for (String malformed :
        List.of(
            "{}",
            "[]",
            "{\"messageId\":17}",
            "{\"messageId\":\" \"}",
            "{\"messageId\":\"edge \"}",
            "{\"messageId\":\"secret\\u0001identity\"}",
            "{\"messageId\":\"" + "😀".repeat(129) + "\"}")) {
      jdbc.update(
          "UPDATE message_external_tasks SET source=?::jsonb WHERE id=?", malformed, invalid);
      var failed =
          assertThrows(
              FlywayException.class,
              () -> Flyway.configure().dataSource(source).target("251").load().migrate());
      StringBuilder chain = new StringBuilder();
      for (Throwable cause = failed; cause != null; cause = cause.getCause())
        chain.append(cause.getMessage()).append('\n');
      assertTrue(chain.toString().contains("V251 native ingress preflight refused"));
      assertFalse(chain.toString().contains("secret"));
      assertEquals(
          2, jdbc.queryForObject("SELECT count(*) FROM message_external_tasks", Integer.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM information_schema.columns WHERE table_name='message_external_tasks' AND column_name='native_source'",
              Integer.class));
    }
    String evidence =
        "{\"messageId\":\"retained-message\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"Retained evidence\"}]}";
    jdbc.update("UPDATE message_external_tasks SET source=?::jsonb WHERE id=?", evidence, invalid);
    Flyway.configure().dataSource(source).target("251").load().migrate();
    assertEquals(
        "retained-message",
        jdbc.queryForObject(
            "SELECT native_source->>'identity' FROM message_external_tasks WHERE id=?",
            String.class,
            invalid));
    assertTrue(
        jdbc.queryForObject(
            "SELECT source=?::jsonb AND body='Retained body' AND message='bdm_Original' AND context=? FROM message_external_tasks WHERE id=?",
            Boolean.class,
            evidence,
            context,
            invalid));
    assertTrue(
        jdbc.queryForObject(
            "SELECT native_source='null'::jsonb AND source='null'::jsonb FROM message_external_tasks WHERE id=?",
            Boolean.class,
            absent));
  }
}
