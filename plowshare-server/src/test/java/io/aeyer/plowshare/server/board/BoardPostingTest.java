package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.ws.Asking;
import io.aeyer.plowshare.server.ws.BoardPostingFrames;
import io.aeyer.plowshare.server.ws.FrameTypes;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("full-db")
@Testcontainers
class BoardPostingTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  BoardFixture f;
  Board board;
  BoardTopic topic;
  BoardPostingFrames frames;
  ProjectMembers members;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void fresh() {
    f = new BoardFixture(source);
    board = f.board(BoardFixture.TWO);
    topic = f.openByBot(board).topic();
    members = new ProjectMembers(f.jdbc);
    members.add("payments", "enzo");
    frames = new BoardPostingFrames(board, f.store, members, f.work, f.jdbc);
  }

  BoardPostingFrames.Receipt post(UUID key, String body) throws Exception {
    return (BoardPostingFrames.Receipt)
        frames
            .frames()
            .get(FrameTypes.BOARD_POST)
            .handle(
                Map.of(
                    "project",
                    "payments",
                    "topic",
                    topic.id(),
                    "body",
                    body,
                    "requestId",
                    key.toString()),
                new Asking("test", "enzo"))
            .payload();
  }

  BoardPostingFrames.OpenReceipt open(UUID key, String title) throws Exception {
    return (BoardPostingFrames.OpenReceipt)
        frames
            .frames()
            .get(FrameTypes.BOARD_OPEN)
            .handle(
                Map.of(
                    "project",
                    "payments",
                    "title",
                    title,
                    "label",
                    "Discussion",
                    "body",
                    "Research this question",
                    "requestId",
                    key.toString(),
                    "maxModelCalls",
                    3),
                new Asking("test", "enzo"))
            .payload();
  }

  @Test
  void creating_a_person_topic_retries_without_duplicate_seats_or_wakes_even_after_close()
      throws Exception {
    UUID key = UUID.randomUUID();
    var receipt = open(key, "A person's new topic");
    assertEquals("enzo", receipt.topic().account());
    assertEquals(BoardTopic.BY_PERSON, receipt.topic().openerKind());
    assertEquals("enzo", receipt.message().author());
    assertEquals(3, receipt.topic().potTotal());
    assertEquals(2, f.store.seats(receipt.topic().id()).size());
    int wakes = f.drained.size();
    assertEquals(receipt, open(key, "A person's new topic"));
    assertEquals(wakes, f.drained.size());
    f.store.close(receipt.topic().id(), receipt.message().id());
    var repeated = open(key, "A person's new topic");
    assertEquals(receipt.message(), repeated.message());
    assertTrue(repeated.topic().isClosed());
    assertEquals(wakes, f.drained.size());
    assertThrows(CallerFault.class, () -> open(key, "A changed topic"));
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM board_post_receipts", Integer.class));
  }

  @Test
  void concurrent_creation_retries_open_one_topic_and_one_opening_message() throws Exception {
    UUID key = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> open(key, "Concurrent person topic"));
      var second = pool.submit(() -> open(key, "Concurrent person topic"));
      assertEquals(first.get(), second.get());
    }
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM board_topics WHERE title='Concurrent person topic'",
            Integer.class));
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM board_post_receipts", Integer.class));
  }

  @Test
  void creation_requires_account_membership_and_valid_limits_and_rolls_back_receipt_failure()
      throws Exception {
    var handler = frames.frames().get(FrameTypes.BOARD_OPEN);
    var payload =
        Map.<String, Object>of(
            "project",
            "payments",
            "title",
            "Private topic",
            "label",
            "Discussion",
            "body",
            "Research this question",
            "requestId",
            UUID.randomUUID().toString());
    assertThrows(CallerFault.class, () -> handler.handle(payload, new Asking("test", null)));
    assertThrows(CallerFault.class, () -> handler.handle(payload, new Asking("test", "other")));
    var invalid = new java.util.HashMap<>(payload);
    invalid.put("maxModelCalls", 2.5);
    assertThrows(CallerFault.class, () -> handler.handle(invalid, new Asking("test", "enzo")));
    int topics = f.jdbc.queryForObject("SELECT count(*) FROM board_topics", Integer.class),
        wakes = f.drained.size();
    f.jdbc.execute(
        "ALTER TABLE board_post_receipts ADD CONSTRAINT force_receipt_failure CHECK(false)");
    try {
      assertThrows(Exception.class, () -> open(UUID.randomUUID(), "Must roll back"));
    } finally {
      f.jdbc.execute("ALTER TABLE board_post_receipts DROP CONSTRAINT force_receipt_failure");
    }
    assertEquals(topics, f.jdbc.queryForObject("SELECT count(*) FROM board_topics", Integer.class));
    assertEquals(wakes, f.drained.size());
    members.remove("payments", "enzo");
    assertThrows(CallerFault.class, () -> open(UUID.randomUUID(), "Not a member"));
  }

  @Test
  void duplicates_return_the_original_message_even_after_close_without_another_wake()
      throws Exception {
    UUID key = UUID.randomUUID();
    var before = f.store.seats(topic.id());
    var receipt = post(key, "A person’s project update");
    int wakes = f.drained.size();
    assertEquals(BoardMessage.BY_PERSON, receipt.message().authorKind());
    assertEquals("enzo", receipt.message().author());
    assertEquals(
        before, f.store.seats(topic.id()), "posting must not consume agent read positions");
    assertEquals(receipt, post(key, "A person’s project update"));
    assertEquals(wakes, f.drained.size());
    f.store.close(topic.id(), receipt.message().id());
    assertEquals(receipt, post(key, "A person’s project update"));
    assertThrows(CallerFault.class, () -> post(key, "Changed payload"));
    assertThrows(Board.Refused.class, () -> post(UUID.randomUUID(), "After close"));
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM board_post_receipts", Integer.class));
  }

  @Test
  void concurrent_retries_create_only_one_receipt_and_one_message() throws Exception {
    UUID key = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> post(key, "A concurrent update"));
      var second = pool.submit(() -> post(key, "A concurrent update"));
      assertEquals(first.get(), second.get());
    }
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM board_post_receipts", Integer.class));
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM board_messages WHERE body='A concurrent update'", Integer.class));
  }

  @Test
  void account_project_membership_and_request_identity_are_required_before_posting()
      throws Exception {
    var payload =
        Map.<String, Object>of(
            "project",
            "payments",
            "topic",
            topic.id(),
            "body",
            "Private topic",
            "requestId",
            UUID.randomUUID().toString());
    var handler = frames.frames().get(FrameTypes.BOARD_POST);
    assertThrows(CallerFault.class, () -> handler.handle(payload, new Asking("test", null)));
    assertThrows(CallerFault.class, () -> handler.handle(payload, new Asking("test", "other")));
    assertThrows(
        CallerFault.class,
        () ->
            handler.handle(
                Map.of(
                    "project",
                    "other",
                    "topic",
                    topic.id(),
                    "body",
                    "Wrong project",
                    "requestId",
                    UUID.randomUUID().toString()),
                new Asking("test", "enzo")));
    members.remove("payments", "enzo");
    assertThrows(CallerFault.class, () -> post(UUID.randomUUID(), "No membership"));
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_post_receipts", Integer.class));
  }

  @Test
  void receipt_failure_rolls_back_the_message_and_its_wakes() throws Exception {
    int messages = f.store.messages(topic.id()).size();
    int wakes = f.drained.size();
    f.jdbc.execute(
        "ALTER TABLE board_post_receipts ADD CONSTRAINT force_receipt_failure CHECK(false)");
    try {
      assertThrows(Exception.class, () -> post(UUID.randomUUID(), "Must roll back"));
    } finally {
      f.jdbc.execute("ALTER TABLE board_post_receipts DROP CONSTRAINT force_receipt_failure");
    }
    assertEquals(messages, f.store.messages(topic.id()).size());
    assertEquals(wakes, f.drained.size());
  }

  BoardPostingFrames.RetryReceipt retry(UUID key, int maxTurns) throws Exception {
    return (BoardPostingFrames.RetryReceipt)
        frames
            .frames()
            .get(FrameTypes.BOARD_RETRY)
            .handle(
                Map.of(
                    "project",
                    "payments",
                    "topic",
                    topic.id(),
                    "member",
                    "researcher",
                    "requestId",
                    key.toString(),
                    "maxTurns",
                    maxTurns),
                new Asking("test", "enzo"))
            .payload();
  }

  @Test
  void member_retry_keeps_the_conversation_budget_and_one_wake_with_a_stable_receipt()
      throws Exception {
    var before = f.store.seat(topic.id(), "researcher").orElseThrow();
    f.store.recordFailure(topic.id(), "researcher", "TURN_CAP");
    UUID key = UUID.randomUUID();
    var receipt = retry(key, 24);
    int wakes = f.drained.size();
    assertEquals(
        before.conversation(), f.store.seat(topic.id(), "researcher").orElseThrow().conversation());
    assertNull(f.store.seat(topic.id(), "researcher").orElseThrow().failedEnding());
    assertEquals(java.util.List.of("researcher"), receipt.message().mentions());
    assertEquals(24, receipt.maxTurns());
    assertEquals(topic.potTotal(), f.store.topic(topic.id()).orElseThrow().potTotal());
    assertEquals(topic.potSpent(), f.store.topic(topic.id()).orElseThrow().potSpent());
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM firings WHERE target=? AND status='queued'",
            Integer.class,
            "conversation:" + before.conversation()));
    assertEquals(
        24,
        f.jdbc.queryForObject(
            "SELECT (data->>'maxTurns')::int FROM firings WHERE target=? AND status='queued'",
            Integer.class,
            "conversation:" + before.conversation()));
    assertEquals(receipt, retry(key, 24));
    assertEquals(wakes, f.drained.size());
    assertThrows(CallerFault.class, () -> retry(key, 25));
    assertThrows(Board.Refused.class, () -> retry(UUID.randomUUID(), 24));
    f.store.close(topic.id(), receipt.message().id());
    assertEquals(receipt, retry(key, 24));
    assertEquals(wakes, f.drained.size());
  }

  @Test
  void concurrent_member_retry_submissions_create_one_receipt_and_one_message() throws Exception {
    f.store.recordFailure(topic.id(), "researcher", "UNAVAILABLE");
    UUID key = UUID.randomUUID();
    int messages = f.store.messages(topic.id()).size();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> retry(key, 40));
      var second = pool.submit(() -> retry(key, 40));
      assertEquals(first.get(), second.get());
    }
    assertEquals(messages + 1, f.store.messages(topic.id()).size());
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM board_post_receipts", Integer.class));
  }

  @Test
  void
      retry_requires_ownership_membership_failure_budget_and_valid_limit_and_rolls_back_atomically()
          throws Exception {
    var handler = frames.frames().get(FrameTypes.BOARD_RETRY);
    var payload =
        new java.util.HashMap<String, Object>(
            Map.of(
                "project",
                "payments",
                "topic",
                topic.id(),
                "member",
                "researcher",
                "requestId",
                UUID.randomUUID().toString(),
                "maxTurns",
                24));
    assertThrows(CallerFault.class, () -> handler.handle(payload, new Asking("test", null)));
    assertThrows(CallerFault.class, () -> handler.handle(payload, new Asking("test", "other")));
    assertThrows(Board.Refused.class, () -> retry(UUID.randomUUID(), 24));
    f.store.recordFailure(topic.id(), "researcher", "TURN_CAP");
    for (Object limit : java.util.List.of(0, -1, 1.5, 2147483648L, "24")) {
      payload.put("maxTurns", limit);
      assertThrows(CallerFault.class, () -> handler.handle(payload, new Asking("test", "enzo")));
    }
    payload.put("maxTurns", 24);
    payload.put("project", "other");
    assertThrows(CallerFault.class, () -> handler.handle(payload, new Asking("test", "enzo")));
    payload.put("project", "payments");
    payload.put("member", "ghost");
    assertThrows(Board.Refused.class, () -> handler.handle(payload, new Asking("test", "enzo")));
    int messages = f.store.messages(topic.id()).size(), wakes = f.drained.size();
    f.jdbc.execute(
        "ALTER TABLE board_post_receipts ADD CONSTRAINT force_receipt_failure CHECK(false)");
    try {
      assertThrows(Exception.class, () -> retry(UUID.randomUUID(), 24));
    } finally {
      f.jdbc.execute("ALTER TABLE board_post_receipts DROP CONSTRAINT force_receipt_failure");
    }
    assertEquals(messages, f.store.messages(topic.id()).size());
    assertEquals(wakes, f.drained.size());
    assertEquals("TURN_CAP", f.store.seat(topic.id(), "researcher").orElseThrow().failedEnding());
    members.remove("payments", "enzo");
    assertThrows(CallerFault.class, () -> retry(UUID.randomUUID(), 24));
    members.add("payments", "enzo");
    f.jdbc.update("UPDATE board_topics SET pot_spent=pot_total-reserve WHERE id=?", topic.id());
    assertThrows(Board.Refused.class, () -> retry(UUID.randomUUID(), 24));
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_post_receipts", Integer.class));
  }
}
