package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What {@code V68__a_question_s_notice_settles_with_it.sql} does to the notices a running server
 * already wrote: an approval's question whose approval is no longer asked is settled, and so is a
 * run's question its run is no longer asking; the questions still open, and every piece of news — a
 * run's ending, a stall, an approval's continuation — are left as they were. Stops at V67, writes
 * the rows, then lets V68 land on them ({@link LogOwnerBackfillTest}'s shape).
 */
@Tag("full-db")
@Testcontainers
class InboxSettleBackfillTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final OffsetDateTime WHEN =
      OffsetDateTime.ofInstant(Instant.parse("2026-09-29T03:37:37Z"), ZoneOffset.UTC);

  private JdbcTemplate jdbc;
  private long project;

  @BeforeEach
  void schemaAtV67() {
    jdbc = new JdbcTemplate(dataSource());
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    migrateTo("67");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'x')");
    project =
        jdbc.queryForObject(
            "INSERT INTO projects (name, workspace) VALUES ('story'," + " '/repo') RETURNING id",
            Long.class);
  }

  @Test
  void an_approval_s_question_settles_with_its_approval_and_its_news_does_not() {
    approval("apr_0123456789abcdef", "asked");
    approval("apr_fedcba9876543210", "allowed");
    approval("apr_aaaaaaaaaaaaaaaa", "denied");
    notice(
        "inb_open",
        "approval",
        "Approve running make test in /repo on the server side?" + " [apr_0123456789abcdef]");
    notice(
        "inb_allowed",
        "approval",
        "Approve running 3 commands in /repo on the local side?"
            + " acceptance:\n- make\n- make test [apr_fedcba9876543210]");
    notice(
        "inb_denied",
        "approval",
        "Approve running rm -rf build in /repo on the server" + " side? [apr_aaaaaaaaaaaaaaaa]");
    notice(
        "inb_gone",
        "approval",
        "Approve running ls in /repo on the server side?" + " [apr_bbbbbbbbbbbbbbbb]");
    notice(
        "inb_continued",
        "approval",
        "Approval apr_fedcba9876543210 continued the run," + " which ended ANSWERED: done");

    migrateTo("68");

    assertEquals("approval:apr_0123456789abcdef", about("inb_open"));
    assertNull(settledAt("inb_open"), "still asked: the person may still answer it here");
    assertEquals("approval:apr_fedcba9876543210", about("inb_allowed"));
    assertNotNull(settledAt("inb_allowed"));
    assertNotNull(settledAt("inb_denied"));
    assertNotNull(settledAt("inb_gone"), "no approval left to answer");
    assertNull(about("inb_continued"), "news, not a question");
    assertNull(settledAt("inb_continued"));
  }

  @Test
  void a_run_s_question_settles_unless_its_run_still_asks_it() {
    run("orc_ANSWERED0000001", "running");
    String answered = message("msg_1", "orc_ANSWERED0000001", "question", WHEN);
    run("orc_ASKING00000001", "asking");
    message("msg_2", "orc_ASKING00000001", "question", WHEN);
    String open = message("msg_3", "orc_ASKING00000001", "question", WHEN.plusMinutes(5));
    run("orc_STUCK000000001", "cancelled");
    message("msg_4", "orc_STUCK000000001", "question", WHEN);
    run("orc_VERIFIED000001", "asking");
    String uncovered = message("msg_5", "orc_VERIFIED000001", "question", WHEN);
    run("orc_CAPPED00000001", "finished");
    message("msg_6", "orc_CAPPED00000001", "question", WHEN);

    notice(
        "inb_ordinary",
        "orchestration",
        "The orchestration 'code_implementation' (id"
            + " orc_ANSWERED0000001) is asking a question and waits for the answer.\n\n...",
        WHEN.plusMinutes(1));
    notice(
        "inb_older",
        "orchestration",
        "The orchestration 'code_implementation' (id"
            + " orc_ASKING00000001) is asking a question and waits for the answer.\n\n...",
        WHEN.plusMinutes(1));
    notice(
        "inb_latest",
        "orchestration",
        "The orchestration 'code_implementation' (id"
            + " orc_ASKING00000001) is asking a question and waits for the answer.\n\n...",
        WHEN.plusMinutes(6));
    notice(
        "inb_stuck",
        "orchestration",
        "`orc_STUCK000000001` (`code_implementation`) ended 3"
            + " turns in a row without making progress. Still pending: `goal`.",
        WHEN.plusMinutes(1));
    notice(
        "inb_uncovered",
        "orchestration",
        "`orc_VERIFIED000001` (`implement_specification`)"
            + " wrote spec.md's acceptance commands, and the verifier found no command that"
            + " would observe:\n- x",
        WHEN.plusMinutes(1));
    notice(
        "inb_cap",
        "orchestration",
        "The orchestration 'code_implementation'"
            + " (orc_CAPPED00000001): the conductor stopped at its turn cap; answer `yes` to"
            + " continue with a fresh turn, or `no` to stop it here. — `/answer"
            + " orc_CAPPED00000001 yes` to continue",
        WHEN.plusMinutes(1));
    notice(
        "inb_ending",
        "orchestration",
        "The orchestration 'code_implementation' (id" + " orc_CAPPED00000001) finished.\n\n...",
        WHEN.plusMinutes(2));
    notice(
        "inb_stall",
        "orchestration",
        "`orc_ASKING00000001` (`code_implementation`) has"
            + " done nothing for 15 minutes: `/runs orc_ASKING00000001` to look",
        WHEN.plusMinutes(2));

    migrateTo("68");

    assertEquals("question:" + answered, about("inb_ordinary"));
    assertNotNull(settledAt("inb_ordinary"), "answered: its run is running again");
    assertNotNull(settledAt("inb_older"), "an earlier question of a run asking another");
    assertEquals("question:" + open, about("inb_latest"));
    assertNull(settledAt("inb_latest"), "the question its run is asking now");
    assertNotNull(settledAt("inb_stuck"), "its run was cancelled");
    assertEquals("question:" + uncovered, about("inb_uncovered"));
    assertNull(settledAt("inb_uncovered"), "still asked");
    assertNotNull(settledAt("inb_cap"), "its run ended");
    for (String news : new String[] {"inb_ending", "inb_stall"}) {
      assertNull(about(news), news + " is news");
      assertNull(settledAt(news), news + " is news");
    }
  }

  @Test
  void a_run_result_is_untouched() {
    jdbc.update(
        "INSERT INTO user_inbox (id, handle, conversation, ending, answer, arrived_at)"
            + " VALUES ('inb_run', 'enzo', 'cnv_1', 'ANSWERED', 'three PRs', ?)",
        WHEN);

    migrateTo("68");

    Map<String, Object> row =
        jdbc.queryForMap("SELECT about, settled_at FROM user_inbox WHERE id = 'inb_run'");
    assertNull(row.get("about"));
    assertNull(row.get("settled_at"));
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void migrateTo(String version) {
    Flyway.configure().dataSource(dataSource()).target(version).load().migrate();
  }

  private void approval(String id, String state) {
    boolean asked = "asked".equals(state);
    jdbc.update(
        "INSERT INTO run_approvals (id, project_id, conversation, asked_in, handle,"
            + " agent, side, argv, cwd, state, scope, answered_by, answered_at, created_at)"
            + " VALUES (?, ?, 'cnv_c', 'cnv_c', 'enzo', 'coder', 'server', '[\"make\"]'::jsonb,"
            + " '/repo', ?, ?, ?, ?, ?)",
        id,
        project,
        state,
        "allowed".equals(state) ? "once" : null,
        asked ? null : "enzo",
        asked ? null : WHEN.plusMinutes(3),
        WHEN);
  }

  private void run(String id, String state) {
    boolean ended = !"running".equals(state) && !"asking".equals(state);
    String conductor = "cnv_" + id;
    jdbc.update(
        "INSERT INTO conversations (id, origin, agent, lifecycle, created_at,"
            + " budget_total, budget_spent) VALUES (?, 'orchestration', 'x', 'active', ?, 100,"
            + " 0)",
        conductor,
        WHEN);
    jdbc.update(
        "INSERT INTO orchestrations (id, definition_name, tier, definition_hash,"
            + " stages, max_returns, conductor_conversation, caller_conversation,"
            + " caller_agent, caller_handle, state, created_at, definition_source,"
            + " definition_origin, depth, result, failure, ended_at) VALUES (?,"
            + " 'code_implementation', 'SHIPPED', 'h', '[]'::jsonb, 2, ?, NULL, 'aristoxenus',"
            + " 'enzo', ?, ?, 's', 'o', 0, ?, ?, ?)",
        id,
        conductor,
        state,
        WHEN,
        "finished".equals(state) ? "done" : null,
        ended && !"finished".equals(state) ? "cancelled by enzo" : null,
        ended ? WHEN.plusMinutes(4) : null);
  }

  private String message(String id, String orchestration, String kind, OffsetDateTime at) {
    jdbc.update(
        "INSERT INTO orchestration_messages (id, orchestration, kind, text, author,"
            + " created_at) VALUES (?, ?, ?, 'Which database?', 'harness', ?)",
        id,
        orchestration,
        kind,
        at);
    return id;
  }

  private void notice(String id, String kind, String text) {
    notice(id, kind, text, WHEN.plusMinutes(1));
  }

  private void notice(String id, String kind, String text, OffsetDateTime at) {
    jdbc.update(
        "INSERT INTO user_inbox (id, handle, kind, answer, arrived_at)"
            + " VALUES (?, 'enzo', ?, ?, ?)",
        id,
        kind,
        text,
        at);
  }

  private String about(String id) {
    return jdbc.queryForObject("SELECT about FROM user_inbox WHERE id = ?", String.class, id);
  }

  private OffsetDateTime settledAt(String id) {
    return jdbc.queryForObject(
        "SELECT settled_at FROM user_inbox WHERE id = ?", OffsetDateTime.class, id);
  }
}
