package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.aeyer.plowshare.server.approvals.ApprovalDelivery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class InboxStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private InboxStore store;
  private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE user_inbox, firings, triggers, schedules, admins,"
            + " entries, turns, conversations CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('sam', 'h')");
    store = new JdbcInboxStore(jdbc);
  }

  @Test
  void an_account_sees_only_its_own_items() {
    store.deliver("enzo", null, "cnv_1", "ANSWERED", "three PRs", T0);
    store.deliver("sam", null, "cnv_2", "ANSWERED", "nothing", T0);
    assertEquals(1, store.list("enzo", false, 0, 50).size());
    assertEquals("three PRs", store.list("enzo", false, 0, 50).get(0).answer());
  }

  @Test
  void marking_read_touches_only_the_callers_rows() {
    InboxItem mine = store.deliver("enzo", null, "cnv_1", "ANSWERED", "a", T0);
    InboxItem theirs = store.deliver("sam", null, "cnv_2", "ANSWERED", "b", T0);
    assertEquals(
        1, store.markRead("enzo", java.util.List.of(mine.id(), theirs.id()), T0.plusSeconds(5)));
    assertEquals(0, store.unread("enzo"));
    assertEquals(1, store.unread("sam"));
  }

  /**
   * Every kind this server writes a notice under, against the real schema. Measured 2026-09-26:
   * 8f90cb10 began delivering approval questions to the inbox as kind 'approval' and no migration
   * added it to user_inbox_kind_is_known, so every such delivery failed at the insert — caught,
   * logged, and left undelivered for a retry that could never succeed.
   */
  @Test
  void every_notice_kind_the_server_writes_is_one_the_schema_accepts() {
    for (String kind :
        List.of(
            InboxStore.KIND_SYNC_CONFLICT,
            ApprovalDelivery.INBOX_KIND,
            // Delivery.INBOX_KIND, package-private to orchestrations.
            "orchestration",
            InboxStore.KIND_HOOK)) {
      assertEquals(kind, store.notice("enzo", kind, "a " + kind + " notice", T0).kind());
    }
  }

  /**
   * A notice asking the person something leaves the inbox once its question is settled (V68): not
   * listed, not counted, not marked read, not counted for a turn's notice. News — a run's result, a
   * notice about nothing — is untouched by it.
   */
  @Test
  void a_settled_question_is_neither_listed_nor_counted_and_news_is_untouched() {
    jdbc.update(
        "INSERT INTO conversations (id, origin, created_at, last_turn_at,"
            + " budget_total, budget_spent) VALUES ('cnv_talk', 'turn', ?, ?, 100, 0)",
        Timestamp.from(T0.minusSeconds(3600)),
        Timestamp.from(T0.minusSeconds(60)));
    InboxItem question =
        store.notice(
            "enzo",
            ApprovalDelivery.INBOX_KIND,
            "Approve running make? [apr_1]",
            "approval:apr_1",
            T0);
    InboxItem other =
        store.notice(
            "enzo",
            ApprovalDelivery.INBOX_KIND,
            "Approve running ls? [apr_2]",
            "approval:apr_2",
            T0);
    assertEquals("approval:apr_1", question.about());
    assertEquals("approval:apr_2", other.about());
    InboxItem news = store.notice("enzo", "orchestration", "it finished", T0);
    InboxItem result = store.deliver("enzo", null, "cnv_1", "ANSWERED", "three PRs", T0);
    assertEquals(4, store.unread("enzo"));

    assertEquals(List.of("enzo"), store.settle("approval:apr_1", T0.plusSeconds(5)));

    assertEquals(3, store.unread("enzo"));
    assertEquals(
        List.of(other.id(), news.id(), result.id()).stream().sorted().toList(),
        store.list("enzo", false, 0, 50).stream().map(InboxItem::id).sorted().toList());
    assertEquals(3, store.list("enzo", true, 0, 50).size());
    assertEquals(3, store.unreadSinceLastTurn("enzo", "cnv_talk"));
    assertEquals(
        0,
        store.markRead("enzo", List.of(question.id()), T0.plusSeconds(6)),
        "a settled notice is not there to be read");
    assertEquals(List.of(), store.settle("approval:apr_1", T0.plusSeconds(7)), "settled once");
    assertEquals(
        List.of(),
        store.settle("approval:apr_9", T0.plusSeconds(7)),
        "a question nobody was told of settles nothing");
  }

  @Test
  void a_settle_names_every_account_it_settled_a_notice_for() {
    store.notice("enzo", "orchestration", "asked", "question:msg_1", T0);
    store.notice("sam", "orchestration", "asked", "question:msg_1", T0);

    assertEquals(
        List.of("enzo", "sam"),
        store.settle("question:msg_1", T0.plusSeconds(1)).stream().sorted().toList());
    assertEquals(0, store.unread("enzo"));
    assertEquals(0, store.unread("sam"));
  }

  @Test
  void only_items_that_arrived_after_the_conversations_last_turn_count_for_a_notice() {
    // Match V6/V17/V29/V31 required columns; see the Task 1 Step 1 note.
    jdbc.update(
        "INSERT INTO conversations (id, origin, created_at, last_turn_at,"
            + " budget_total, budget_spent) VALUES ('cnv_talk', 'turn', ?, ?, 100, 0)",
        Timestamp.from(T0.minusSeconds(3600)),
        Timestamp.from(T0));
    store.deliver("enzo", null, "cnv_a", "ANSWERED", "old", T0.minusSeconds(60));
    store.deliver("enzo", null, "cnv_b", "ANSWERED", "new", T0.plusSeconds(60));
    assertEquals(1, store.unreadSinceLastTurn("enzo", "cnv_talk"));
    assertEquals(
        T0.plusSeconds(60), store.newestUnreadSinceLastTurn("enzo", "cnv_talk").orElseThrow());
  }
}
