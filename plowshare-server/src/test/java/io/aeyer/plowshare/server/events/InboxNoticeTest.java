package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
class InboxNoticeTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static final Instant AT = Instant.parse("2026-09-14T10:00:00Z");

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void account() {
    jdbc.update("DELETE FROM user_inbox");
    jdbc.update(
        "INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'x') ON CONFLICT DO NOTHING");
  }

  @Test
  void a_notice_is_listed_for_its_account_with_its_kind_and_no_conversation() {
    InboxStore store = new JdbcInboxStore(jdbc);
    store.notice("enzo", InboxStore.KIND_SYNC_CONFLICT, "ledger: src/a.ts conflicted", AT);
    List<InboxItem> items = store.list("enzo", true, 0, 10);
    assertEquals(1, items.size());
    assertEquals("sync.conflict", items.get(0).kind());
    assertNull(items.get(0).conversation());
    assertNull(items.get(0).ending());
    assertEquals(1, store.unread("enzo"));
  }

  @Test
  void a_run_result_is_still_kind_run() {
    InboxStore store = new JdbcInboxStore(jdbc);
    store.deliver("enzo", null, "cnv_1", "DONE", "answer", AT);
    assertEquals("run", store.list("enzo", true, 0, 10).get(0).kind());
  }

  @Test
  void notifying_pushes_the_new_unread_count() {
    AccountPushes pushes = mock(AccountPushes.class);
    Inbox inbox = new Inbox(new JdbcInboxStore(jdbc), pushes, () -> AT);
    inbox.notify("enzo", InboxStore.KIND_SYNC_CONFLICT, "text");
    verify(pushes).push("enzo", Inbox.changed(1));
  }
}
