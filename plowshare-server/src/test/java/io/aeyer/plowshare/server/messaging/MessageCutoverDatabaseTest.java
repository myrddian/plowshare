package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryIds;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.board.BoardMessage;
import io.aeyer.plowshare.server.board.BoardStore;
import io.aeyer.plowshare.server.board.BoardTopic;
import io.aeyer.plowshare.server.events.JdbcFiringStore;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayConsumerRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayDeliveryRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayRetirementRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Requires actual Flyway cutover, retained relational constraints and committed allowance claims.
 */
@Tag("full-db")
@Testcontainers
class MessageCutoverDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void queued_paid_and_replied_threads_move_once_without_private_topics_or_replayed_execution()
      throws Exception {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("238").load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('owner','fixture')");
    jdbc.update("INSERT INTO projects(name,workspace) VALUES('project','fixture')");
    var work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    var conversations = new ConversationStore(jdbc);
    var boards = new BoardStore(jdbc, Instant::now);
    var firings = new JdbcFiringStore(jdbc);
    String sender = MemoryIds.mint("ins_", Instant.now());
    String receiver = MemoryIds.mint("ins_", Instant.now());
    String sourceLog = conversations.open(Home.of("project"), Budget.of(20), null, "owner").id();
    String recipientLog =
        conversations.log(Origin.BOARD, Home.of("project"), "worker", null, null, "owner").id();
    var sourceTopic =
        boards.openRoot(
            new BoardStore.NewTopic(
                "project",
                "Source",
                "MESSAGES",
                "owner",
                BoardTopic.BY_AGENT,
                "sender",
                sourceLog,
                20,
                1));
    var recipientTopic =
        boards.openRoot(
            new BoardStore.NewTopic(
                "project",
                "Recipient",
                "MESSAGES",
                "owner",
                BoardTopic.BY_AGENT,
                "worker",
                recipientLog,
                20,
                1));
    jdbc.update(
        "INSERT INTO board_message_instances(id,account,project,agent,conversation,topic,lifetime) VALUES(?,'owner','project','sender',?,?,'caller'),(?,'owner','project','worker',?,?,'persistent')",
        sender,
        sourceLog,
        sourceTopic.id(),
        receiver,
        recipientLog,
        recipientTopic.id());
    boards.seatIfAbsent(recipientTopic.id(), "worker", recipientLog);
    var queued =
        boards.post(
            new BoardStore.NewMessage(
                recipientTopic.id(),
                null,
                BoardMessage.BY_HARNESS,
                "sender",
                sourceLog,
                null,
                BoardMessage.POST,
                null,
                "Outstanding request",
                false,
                List.of()));
    var progress =
        boards.post(
            new BoardStore.NewMessage(
                sourceTopic.id(),
                null,
                BoardMessage.BY_HARNESS,
                "worker",
                recipientLog,
                null,
                BoardMessage.POST,
                null,
                "Progress already retained",
                false,
                List.of()));
    var paid =
        boards.post(
            new BoardStore.NewMessage(
                recipientTopic.id(),
                null,
                BoardMessage.BY_HARNESS,
                "sender",
                sourceLog,
                null,
                BoardMessage.POST,
                null,
                "Interrupted paid request",
                false,
                List.of()));
    jdbc.update(
        "INSERT INTO board_message_routes(message,sender,recipient,reply_expected) VALUES(?,?,?,true),(?,?,?,true)",
        queued.id(),
        sender,
        receiver,
        paid.id(),
        sender,
        receiver);
    jdbc.update(
        "INSERT INTO board_message_routes(message,sender,recipient,reply_to,reply_expected,handled_at,ending) VALUES(?,?,?,?,false,clock_timestamp(),'ANSWERED')",
        progress.id(),
        receiver,
        sender,
        queued.id());
    String pending = MemoryIds.mint("fir_", Instant.now());
    String interrupted = MemoryIds.mint("fir_", Instant.now());
    var json = new ObjectMapper();
    jdbc.update(
        "INSERT INTO firings(id,event,data,target,status,arrived_at,topic) VALUES(?,'board.wake',?::jsonb,?,'queued',clock_timestamp(),?),(?,'board.wake',?::jsonb,?,'queued',clock_timestamp(),?)",
        pending,
        json.writeValueAsString(new MessageWake(queued.id())),
        "conversation:" + recipientLog,
        recipientTopic.id(),
        interrupted,
        json.writeValueAsString(new MessageWake(paid.id())),
        "conversation:" + recipientLog,
        recipientTopic.id());
    jdbc.update(
        "UPDATE board_message_routes SET started_at=clock_timestamp() WHERE message=?", paid.id());
    jdbc.update(
        "UPDATE firings SET status='started',job_id='job_fixture',started_at=clock_timestamp(),finished_at=clock_timestamp(),reason='interrupted' WHERE id=?",
        interrupted);
    var originalAllowance = boards.allowance(recipientTopic.id()).id();
    assertTrue(boards.claim(recipientTopic.id(), 1).isPresent());
    assertTrue(boards.claim(recipientTopic.id(), 1).isPresent());

    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(Origin.MESSAGE, conversations.find(recipientLog).orElseThrow().origin());
    assertEquals(Origin.TURN, conversations.find(sourceLog).orElseThrow().origin());
    var messages = new JdbcMessagingRepository(jdbc);
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM message_records", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM board_topics", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM board_messages", Integer.class));
    assertEquals(
        2, jdbc.queryForObject("SELECT count(*) FROM message_agent_inboxes", Integer.class));
    assertEquals(originalAllowance, messages.allowance(paid.id()));
    assertEquals(
        2, new JdbcAllowanceRepository(jdbc).find(originalAllowance).orElseThrow().spent());
    assertEquals(queued.id(), messages.route(progress.id()).orElseThrow().replyTo());
    assertEquals("Progress already retained", messages.message(progress.id()).orElseThrow().body());
    assertEquals(receiver, firings.find(pending).orElseThrow().topic());
    assertEquals("queued", firings.find(pending).orElseThrow().status());
    assertEquals("job_fixture", firings.find(interrupted).orElseThrow().jobId());

    var broker =
        new JdbcRelayRepository(
            jdbc,
            work,
            new JdbcRelayPayloadStore(
                jdbc, work, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30)));
    var relay = new DurableRelay(broker, Instant::now);
    var publications = new RelayMessagePublications(relay, new MessagePayloadCodec(65536));
    var inboxes =
        new RelayMessageInboxes(
            messages,
            new JdbcMessageInboxRepository(jdbc),
            publications,
            relay,
            new JdbcRelayConsumerRepository(
                jdbc, work, new JdbcRelayDeliveryRepository(jdbc, work), broker),
            firings,
            work,
            target -> {},
            Instant::now,
            new JdbcRelayRetirementRepository(jdbc, work));
    assertEquals(0, inboxes.poll(), "Existing owning firings must not be admitted again");
    var voice = mock(MessageService.Voice.class);
    var engine =
        new RelayMessaging(
            messages,
            new JdbcAllowanceRepository(jdbc),
            conversations,
            firings,
            inboxes,
            work,
            voice,
            (name, project) ->
                new AgentDefinition(
                    name, name, "model", List.of(), List.of(), List.of(), 4, 8, "Answer"),
            target -> {},
            Instant::now);
    engine.recover();
    String terminal = engine.finalReply(paid.id()).orElseThrow();
    assertTrue(messages.route(paid.id()).orElseThrow().handled());
    assertTrue(messages.route(terminal).orElseThrow().generated());
    assertEquals("UNAVAILABLE", messages.route(terminal).orElseThrow().ending());
    engine.recover();
    assertEquals(terminal, engine.finalReply(paid.id()).orElseThrow());
    assertFalse(messages.route(queued.id()).orElseThrow().handled());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM firings WHERE data->>'direct_message'=?",
            Integer.class,
            queued.id()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM firings WHERE data->>'direct_message'=?",
            Integer.class,
            paid.id()));
    // Former IDs are retained historical evidence. Even a colliding value cannot hide a new
    // public forum topic, suppress its messages, or suppress its close lifecycle event.
    var forum =
        boards.openRoot(
            new BoardStore.NewTopic(
                "project",
                "Public forum",
                "PUBLIC",
                "owner",
                BoardTopic.BY_PERSON,
                "owner",
                null,
                20,
                1));
    jdbc.update("UPDATE message_instances SET former_topic=? WHERE id=?", forum.id(), receiver);
    assertTrue(boards.openTopics().stream().anyMatch(topic -> topic.id().equals(forum.id())));
    assertTrue(
        boards.summaries("owner", "project", false, 0, 10).stream()
            .anyMatch(summary -> summary.topic().id().equals(forum.id())));
    var forumMessage =
        boards.post(
            new BoardStore.NewMessage(
                forum.id(),
                null,
                BoardMessage.BY_PERSON,
                "owner",
                sourceLog,
                null,
                BoardMessage.POST,
                null,
                "Public resolution",
                false,
                List.of()));
    assertTrue(boards.close(forum.id(), forumMessage.id()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_source_events WHERE topic='board.message.posted' AND subject=?",
            Integer.class,
            forumMessage.id()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_source_events WHERE topic='board.closed' AND subject=?",
            Integer.class,
            forum.id()));
    for (String function :
        List.of("relay_capture_board_topic()", "relay_capture_board_message()")) {
      String definition =
          jdbc.queryForObject("SELECT pg_get_functiondef(?::regprocedure)", String.class, function);
      assertFalse(definition.contains("message_instances"));
      assertFalse(definition.contains("former_topic"));
    }
    verifyNoInteractions(voice);
  }
}
