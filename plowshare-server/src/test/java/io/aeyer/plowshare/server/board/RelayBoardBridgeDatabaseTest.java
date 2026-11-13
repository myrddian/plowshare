package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.DurableRelayDeliveries;
import io.aeyer.plowshare.server.relay.JdbcRelayDeliveryRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayPortRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayDeliveries;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL is required for bridge migrations, transactional triggers, row mapping and
 * concurrent destination/request locks.
 */
@Tag("full-db")
@Testcontainers
class RelayBoardBridgeDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private BoardFixture f;
  private Board board;
  private BoardTopic topic;
  private BoardBridge bridge;
  private BoardClosing closing;
  private JdbcProjectMembers members;
  private Relay relay;
  private BoardReplyPublications replies;
  private Relay.TopicKey replyTopic;
  private long sourceProject;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    f = new BoardFixture(source);
    f.jdbc.execute(
        "TRUNCATE relay_topics,relay_payloads,relay_source_events,relay_port_publishers CASCADE");
    f.jdbc.update("UPDATE admins SET server_admin=true WHERE handle='enzo'");
    f.jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('publisher','fixture')");
    f.jdbc.update(
        "INSERT INTO projects(name,workspace) VALUES('payments','fixture'),('source','fixture') ON CONFLICT(name) DO NOTHING");
    sourceProject =
        f.jdbc.queryForObject("SELECT id FROM projects WHERE name='payments'", Long.class);
    board = f.board(BoardFixture.TWO);
    topic = f.openByBot(board).topic();
    members = new JdbcProjectMembers(f.jdbc);
    members.add("payments", "enzo");
    members.assign("payments", "publisher", ProjectRole.CONTRIBUTOR, "enzo", true);
    var records = new JdbcBoardPostRepository(f.jdbc);
    var projects = mock(ProjectWorkspaces.class);
    when(projects.id("payments")).thenReturn(sourceProject);
    when(projects.personalOwner("payments")).thenReturn(Optional.empty());
    closing = new ProjectBoardClosing(board, f.store, records, members, projects, f.work);
    var repository = new JdbcBoardBridgeRepository(f.jdbc);
    relay =
        new DurableRelay(
            new JdbcRelayRepository(
                f.jdbc,
                f.work,
                new JdbcRelayPayloadStore(
                    f.jdbc,
                    f.work,
                    Duration.ofHours(1),
                    Duration.ofHours(24),
                    Duration.ofDays(30))),
            Instant::now);
    replyTopic = new Relay.TopicKey(sourceProject, "collector.inbox");
    relay.registerTopic(replyTopic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    bridge =
        new ForumBoardBridge(
            board,
            f.store,
            records,
            repository,
            closing,
            project -> List.of(BoardFixture.TWO),
            relay,
            members,
            projects,
            f.work);
    replies = new RelayBoardReplies(repository, relay, f.work);
  }

  private BoardBridge.Command command(UUID request) {
    return new BoardBridge.Command(
        request,
        "publisher",
        sourceProject,
        "source-event",
        new BoardBridge.Post(topic.id()),
        new BoardBridge.Payload("Bridge request", replyTopic.name()),
        null);
  }

  private BoardMessage answer(String parent, String text) {
    return board
        .post(
            new Board.Post(
                topic.id(),
                BoardMessage.BY_PERSON,
                "enzo",
                null,
                null,
                BoardMessage.POST,
                null,
                text,
                parent,
                List.of(),
                false))
        .message();
  }

  private List<Relay.Publication> published() {
    var subscription = new Relay.SubscriptionKey(replyTopic, "fixture.reader");
    relay.subscribe(subscription, Relay.Start.OLDEST_RETAINED);
    return relay.read(subscription, 100).publications();
  }

  @Test
  void thread_answers_and_one_terminal_survive_restarts_without_disclosing_unrelated_posts()
      throws Exception {
    var cmd = command(UUID.randomUUID());
    var receipt = bridge.accept(cmd);
    assertEquals(receipt, bridge.accept(cmd));
    var first = answer(receipt.message(), "Full answer one");
    answer(first.id(), "Nested answer two");
    answer(null, "Unrelated private deliberation");
    closing.close(
        "enzo",
        new BoardClosing.Command(
            "payments", topic.id(), "Terminal resolution", List.of(), UUID.randomUUID()));
    // A fresh publisher reads durable outbox state; no in-process board callback is required.
    var fresh = new RelayBoardReplies(new JdbcBoardBridgeRepository(f.jdbc), relay, f.work);
    int count = 0;
    while (count < 10 && fresh.publishNext()) count++;
    assertEquals(3, count);
    assertFalse(fresh.publishNext());
    var events = published();
    assertEquals(3, events.size());
    var json = new ObjectMapper();
    for (int index = 0; index < events.size(); index++) {
      var value = json.readTree(((RelayPayload.Text) events.get(index).event().payload()).text());
      assertEquals("source-event", value.get("reply_to").textValue());
      assertEquals(index == 2 ? "terminal" : "post", value.get("kind").textValue());
      assertFalse(value.get("body").textValue().contains("Unrelated"));
    }
    assertEquals(
        "Full answer one",
        json.readTree(((RelayPayload.Text) events.getFirst().event().payload()).text())
            .get("body")
            .textValue());
  }

  @Test
  void mutation_receipt_and_binding_roll_back_together_and_changed_intent_is_refused() {
    var cmd = command(UUID.randomUUID());
    int before = f.jdbc.queryForObject("SELECT count(*) FROM board_messages", Integer.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            f.work.inTransaction(
                () -> {
                  bridge.accept(cmd);
                  throw new IllegalStateException("rollback fixture");
                }));
    assertEquals(
        before, f.jdbc.queryForObject("SELECT count(*) FROM board_messages", Integer.class));
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_requests", Integer.class));
    assertTrue(bridge.inspect(cmd).isEmpty());
    bridge.accept(cmd);
    var changed =
        new BoardBridge.Command(
            cmd.requestId(),
            cmd.account(),
            cmd.sourceProject(),
            cmd.sourceEvent(),
            cmd.target(),
            new BoardBridge.Payload("Different body", replyTopic.name()),
            null);
    assertThrows(CallerFault.class, () -> bridge.accept(changed));
  }

  @Test
  void reply_append_and_outbox_removal_roll_back_together() {
    var receipt = bridge.accept(command(UUID.randomUUID()));
    answer(receipt.message(), "Answer");
    assertThrows(
        IllegalStateException.class,
        () ->
            f.work.inTransaction(
                () -> {
                  assertTrue(replies.publishNext());
                  throw new IllegalStateException("rollback fixture");
                }));
    assertTrue(published().isEmpty());
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_outbox", Integer.class));
    assertTrue(replies.publishNext());
    assertFalse(replies.publishNext());
    assertEquals(1, published().size());
  }

  @Test
  void an_unavailable_reply_destination_backs_off_without_starving_other_requests() {
    var first = bridge.accept(command(UUID.randomUUID()));
    answer(first.message(), "First answer");
    var other = new Relay.TopicKey(sourceProject, "collector.other");
    relay.registerTopic(other, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var second =
        bridge.accept(
            new BoardBridge.Command(
                UUID.randomUUID(),
                "publisher",
                sourceProject,
                "other-event",
                new BoardBridge.Post(topic.id()),
                new BoardBridge.Payload("Other request", other.name()),
                null));
    answer(second.message(), "Other answer");
    var unavailable = new AtomicBoolean(true);
    var publication = mock(Relay.class);
    when(publication.publish(any(), any(), any()))
        .thenAnswer(
            call -> {
              Relay.TopicKey destination = call.getArgument(0);
              if (destination.equals(replyTopic) && unavailable.get())
                throw new IllegalStateException("Unavailable fixture");
              return relay.publish(destination, call.getArgument(1), call.getArgument(2));
            });
    var repository = new JdbcBoardBridgeRepository(f.jdbc);
    var publisher = new RelayBoardReplies(repository, publication, f.work);
    assertThrows(IllegalStateException.class, publisher::publishNext);
    // A fresh instance respects the persisted per-request backoff and drains another binding.
    var restarted = new RelayBoardReplies(repository, publication, f.work);
    assertTrue(restarted.publishNext());
    assertFalse(restarted.publishNext());
    assertTrue(published().isEmpty());
    unavailable.set(false);
    f.jdbc.update("UPDATE board_relay_requests SET next_attempt_at=CURRENT_TIMESTAMP");
    assertTrue(restarted.publishNext());
    assertFalse(restarted.publishNext());
    assertEquals(1, published().size());
  }

  @Test
  void close_receiver_binding_owes_the_terminal_already_written_in_the_same_transaction() {
    var accepted = bridge.accept(command(UUID.randomUUID()));
    var command =
        new BoardBridge.Command(
            UUID.randomUUID(),
            "publisher",
            sourceProject,
            "close-event",
            new BoardBridge.Close(topic.id()),
            new BoardBridge.Payload("Terminal", replyTopic.name()),
            null);
    var closed = bridge.accept(command);
    assertEquals(closed, bridge.accept(command));
    assertTrue(replies.publishNext());
    assertTrue(replies.publishNext());
    assertFalse(replies.publishNext());
    assertEquals(2, published().size());
    assertEquals("resolution", f.store.message(closed.message()).orElseThrow().kind());
    assertTrue(f.store.message(accepted.message()).isPresent());
  }

  @Test
  void closing_validates_all_citation_scopes_before_the_mutation() {
    var other = f.openByBot(board).opening();
    var invalid =
        new BoardClosing.Command(
            "payments", topic.id(), "Resolved", List.of(other.id()), UUID.randomUUID());
    assertThrows(Board.Refused.class, () -> closing.close("enzo", invalid));
    assertFalse(f.store.topic(topic.id()).orElseThrow().isClosed());
    var cite = answer(null, "Evidence");
    var valid =
        new BoardClosing.Command(
            "payments", topic.id(), "Resolved", List.of(cite.id()), UUID.randomUUID());
    assertTrue(closing.close("enzo", valid).message().body().contains(cite.id()));
  }

  @Test
  void forum_writes_require_current_membership_and_cannot_cross_source_projects() {
    members.remove("payments", "publisher");
    int before = f.jdbc.queryForObject("SELECT count(*) FROM board_messages", Integer.class);
    assertThrows(Board.Refused.class, () -> bridge.accept(command(UUID.randomUUID())));
    assertEquals(
        before, f.jdbc.queryForObject("SELECT count(*) FROM board_messages", Integer.class));
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_requests", Integer.class));
    members.assign("payments", "publisher", ProjectRole.CONTRIBUTOR, "enzo", true);
    long otherProject =
        f.jdbc.queryForObject("SELECT id FROM projects WHERE name='source'", Long.class);
    var own = command(UUID.randomUUID());
    var foreign =
        new BoardBridge.Command(
            own.requestId(),
            own.account(),
            otherProject,
            own.sourceEvent(),
            own.target(),
            own.payload(),
            own.causation());
    assertThrows(Board.Refused.class, () -> bridge.accept(foreign));
    assertEquals(
        before, f.jdbc.queryForObject("SELECT count(*) FROM board_messages", Integer.class));
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_requests", Integer.class));
    assertTrue(bridge.accept(own).message().startsWith("bdm_"));
  }

  @Test
  @Timeout(30)
  void concurrent_equal_closes_keep_one_resolution_and_one_terminal() throws Exception {
    bridge.accept(command(UUID.randomUUID()));
    var command =
        new BoardClosing.Command("payments", topic.id(), "Resolved", List.of(), UUID.randomUUID());
    var ready = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                ready.await();
                return closing.close("enzo", command);
              });
      var second =
          executor.submit(
              () -> {
                ready.await();
                return closing.close("enzo", command);
              });
      ready.countDown();
      var firstReceipt = first.get(20, TimeUnit.SECONDS);
      var secondReceipt = second.get(20, TimeUnit.SECONDS);
      assertEquals(firstReceipt.message().id(), secondReceipt.message().id());
      assertEquals("closed", firstReceipt.topic().state());
      assertEquals("closed", secondReceipt.topic().state());
    }
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM board_relay_outbox WHERE terminal", Integer.class));
    assertTrue(replies.publishNext());
    assertFalse(replies.publishNext());
  }

  @Test
  void deleted_reply_topic_settles_with_inspectable_owed_evidence() {
    var cmd = command(UUID.randomUUID());
    var accepted = bridge.accept(cmd);
    var owed = answer(accepted.message(), "Still retained answer");
    f.jdbc.update(
        "DELETE FROM relay_topics WHERE project_id=? AND name=?", sourceProject, replyTopic.name());
    assertTrue(replies.publishNext());
    assertFalse(replies.publishNext());
    var repository = new JdbcBoardBridgeRepository(f.jdbc);
    var failure = repository.failure(cmd.requestId(), "publisher").orElseThrow();
    assertEquals(BoardBridgeRepository.Destination.MISSING, failure.reason());
    assertEquals(1, failure.owedMessages());
    assertTrue(repository.failure(cmd.requestId(), "other-account").isEmpty());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_outbox", Integer.class));
    assertEquals(
        owed.id(),
        f.jdbc.queryForObject("SELECT message_id FROM board_relay_failed_outbox", String.class));
    answer(owed.id(), "Future answers are not silently re-enqueued to a failed destination");
    assertFalse(replies.publishNext());
    assertEquals(accepted, bridge.inspect(cmd).orElseThrow());
  }

  @Test
  void recreated_topic_name_cannot_receive_an_old_disclosure() {
    var cmd = command(UUID.randomUUID());
    var accepted = bridge.accept(cmd);
    answer(accepted.message(), "Old destination only");
    UUID original = relay.topic(replyTopic).generation();
    f.jdbc.update(
        "DELETE FROM relay_topics WHERE project_id=? AND name=?", sourceProject, replyTopic.name());
    relay.registerTopic(replyTopic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    assertFalse(original.equals(relay.topic(replyTopic).generation()));
    assertTrue(replies.publishNext());
    assertTrue(published().isEmpty());
    assertEquals(
        BoardBridgeRepository.Destination.REPLACED,
        new JdbcBoardBridgeRepository(f.jdbc)
            .failure(cmd.requestId(), "publisher")
            .orElseThrow()
            .reason());
  }

  @Test
  void historical_unverified_binding_is_not_adopted_and_failure_archiving_rolls_back() {
    var cmd = command(UUID.randomUUID());
    var accepted = bridge.accept(cmd);
    answer(accepted.message(), "Old unverified answer");
    f.jdbc.update(
        "UPDATE board_relay_requests SET reply_generation=NULL WHERE request_id=?",
        cmd.requestId());
    assertThrows(
        IllegalStateException.class,
        () ->
            f.work.inTransaction(
                () -> {
                  assertTrue(replies.publishNext());
                  throw new IllegalStateException("rollback fixture");
                }));
    var repository = new JdbcBoardBridgeRepository(f.jdbc);
    assertTrue(repository.failure(cmd.requestId(), "publisher").isEmpty());
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_outbox", Integer.class));
    assertTrue(replies.publishNext());
    assertEquals(
        BoardBridgeRepository.Destination.UNVERIFIED,
        repository.failure(cmd.requestId(), "publisher").orElseThrow().reason());
    assertTrue(published().isEmpty());
  }

  @Test
  @Timeout(30)
  void deletion_and_recreation_before_the_broker_lock_cannot_retarget_a_reply() throws Exception {
    var cmd = command(UUID.randomUUID());
    var accepted = bridge.accept(cmd);
    answer(accepted.message(), "Fenced reply");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var blocked = mock(Relay.class);
    when(blocked.publish(any(), any(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("fixture timed out");
              return relay.publish(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            });
    var worker = new RelayBoardReplies(new JdbcBoardBridgeRepository(f.jdbc), blocked, f.work);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publishing = executor.submit(worker::publishNext);
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      try {
        // There must be no board-owned topic pre-lock: broker append owns the payload/topic order.
        f.jdbc.update(
            "DELETE FROM relay_topics WHERE project_id=? AND name=?",
            sourceProject,
            replyTopic.name());
        relay.registerTopic(replyTopic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
      } finally {
        release.countDown();
      }
      assertTrue(publishing.get(10, TimeUnit.SECONDS));
      assertTrue(published().isEmpty());
      assertEquals(
          BoardBridgeRepository.Destination.REPLACED,
          new JdbcBoardBridgeRepository(f.jdbc)
              .failure(cmd.requestId(), "publisher")
              .orElseThrow()
              .reason());
    }
  }

  @Test
  @Timeout(30)
  void concurrent_requests_sharing_a_reply_topic_publish_without_topic_lock_upgrade_cycles()
      throws Exception {
    var first = bridge.accept(command(UUID.randomUUID()));
    answer(first.message(), "First answer");
    var second = bridge.accept(command(UUID.randomUUID()));
    answer(second.message(), "Second answer");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> {
                start.await();
                return new RelayBoardReplies(new JdbcBoardBridgeRepository(f.jdbc), relay, f.work)
                    .publishNext();
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                return new RelayBoardReplies(new JdbcBoardBridgeRepository(f.jdbc), relay, f.work)
                    .publishNext();
              });
      start.countDown();
      assertTrue(a.get(10, TimeUnit.SECONDS));
      assertTrue(b.get(10, TimeUnit.SECONDS));
    }
    assertEquals(2, published().size());
    assertFalse(replies.publishNext());
  }

  @Test
  @Timeout(30)
  void concurrent_reply_waits_for_failure_then_does_not_reopen_settled_debt() throws Exception {
    var cmd = command(UUID.randomUUID());
    var accepted = bridge.accept(cmd);
    var owed = answer(accepted.message(), "Retained debt");
    var repository = new JdbcBoardBridgeRepository(f.jdbc);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var failing =
          executor.submit(
              () ->
                  f.work.inTransaction(
                      () -> {
                        var pending = repository.pending().orElseThrow();
                        locked.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("fixture timed out");
                        } catch (InterruptedException interruption) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interruption);
                        }
                        repository.undeliverable(
                            pending, BoardBridgeRepository.Destination.MISSING);
                        return true;
                      }));
      assertTrue(locked.await(10, TimeUnit.SECONDS));
      var entering = new CountDownLatch(1);
      var incoming =
          executor.submit(
              () -> {
                entering.countDown();
                return answer(owed.id(), "Reply concurrent with terminal failure");
              });
      assertTrue(entering.await(10, TimeUnit.SECONDS));
      release.countDown();
      assertTrue(failing.get(10, TimeUnit.SECONDS));
      incoming.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, repository.failure(cmd.requestId(), "publisher").orElseThrow().owedMessages());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_outbox", Integer.class));
    assertFalse(replies.publishNext());
  }

  @Test
  void pending_selection_uses_global_delivery_index_without_sorting_all_request_heads() {
    // Real PostgreSQL planning matters here: many owed rows must not become a materialized scan
    // and global sort merely to return the oldest answer. Use the owning production SQL verbatim.
    f.jdbc.update(
        """
        INSERT INTO board_messages(id,topic,author_kind,author,kind,body,posted_at)
        SELECT 'bdm_QueryPlan'||n,?,'person','enzo','post','Plan fixture',CURRENT_TIMESTAMP
        FROM generate_series(1,3000) n
        """,
        topic.id());
    f.jdbc.update(
        """
        INSERT INTO board_relay_requests(request_id,account,message_id,topic_id,reply_project_id,
          reply_topic,reply_generation,reply_to)
        SELECT md5(m.id)::uuid,'publisher',m.id,m.topic,?,?,?,'source-event'
        FROM board_messages m WHERE m.id LIKE 'bdm_QueryPlan%'
        """,
        sourceProject, replyTopic.name(), relay.topic(replyTopic).generation());
    f.jdbc.update(
        """
        INSERT INTO board_relay_outbox(request_id,message_id,terminal,message_seq)
        SELECT r.request_id,m.id,false,m.seq FROM board_relay_requests r
        JOIN board_messages m ON m.id=r.message_id WHERE m.id LIKE 'bdm_QueryPlan%'
        """);
    f.jdbc.execute("ANALYZE board_relay_outbox");
    f.jdbc.execute("ANALYZE board_relay_requests");
    f.jdbc.execute("ANALYZE board_messages");
    var plan =
        f.work.inTransaction(
            () ->
                String.join(
                    "\n",
                    f.jdbc.query(
                        "EXPLAIN (ANALYZE, BUFFERS) " + JdbcBoardBridgeRepository.PENDING_SQL,
                        (row, number) -> row.getString(1))));
    assertTrue(plan.contains("board_relay_outbox_delivery_order"), plan);
    assertFalse(plan.contains("Sort"), plan);
    assertFalse(plan.contains("CTE"), plan);
    var oldest =
        f.jdbc.queryForObject(
            "SELECT id FROM board_messages WHERE id LIKE 'bdm_QueryPlan%' ORDER BY seq LIMIT 1",
            String.class);
    assertEquals(
        oldest,
        f.work.inTransaction(
            () -> new JdbcBoardBridgeRepository(f.jdbc).pending().orElseThrow().message()));
  }

  @Test
  void typed_board_destinations_and_publisher_provenance_round_trip_through_the_new_sql() {
    var ports = new JdbcRelayPortRepository(f.jdbc, f.work, relay);
    ports.rememberPublisher("publisher");
    assertEquals(
        "publisher", ports.publisherAccount(RelayPort.publisher("publisher")).orElseThrow());
    var publication =
        relay.publish(
            replyTopic,
            new Relay.Draft(
                "request-event",
                "fixture",
                Instant.now(),
                null,
                null,
                new RelayPayload.Text("Request")));
    var sub = new Relay.SubscriptionKey(replyTopic, "fixture.bridge");
    relay.subscribe(sub, Relay.Start.OLDEST_RETAINED);
    var deliveries =
        new DurableRelayDeliveries(new JdbcRelayDeliveryRepository(f.jdbc, f.work), Instant::now);
    var work = new RelayWork(null, null, null, topic.id(), null);
    var decision =
        new RelayDeliveries.Decision(
            RelayDeliveries.SourcePin.of("bridge/routes.js", "export function route(){return [];}"),
            List.of(new RelayDeliveries.Branch("forum", "board.post", null, null, work)));
    var key = new RelayDeliveries.AdmissionKey(sub, publication.position());
    deliveries.admit(key, decision);
    assertEquals(
        work, deliveries.admission(key).orElseThrow().decision().branches().getFirst().work());
  }
}
