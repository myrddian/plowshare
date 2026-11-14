package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.messaging.BoardMessageExportProperties;
import io.aeyer.plowshare.server.messaging.BoardNativeExportRepository;
import io.aeyer.plowshare.server.messaging.BoardNativeMessageRequests;
import io.aeyer.plowshare.server.messaging.JdbcBoardNativeExportRepository;
import io.aeyer.plowshare.server.messaging.JdbcMessageExportRepository;
import io.aeyer.plowshare.server.messaging.JdbcMessagingRepository;
import io.aeyer.plowshare.server.messaging.MessageExportProperties;
import io.aeyer.plowshare.server.messaging.MessageExports;
import io.aeyer.plowshare.server.messaging.MessageRefused;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.messaging.NativeMessageExports;
import io.aeyer.plowshare.server.messaging.RelayMessageExports;
import io.aeyer.plowshare.server.messaging.RelayMessaging;
import io.aeyer.plowshare.server.relay.BoardRelayReceiver;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.DurableRelayDeliveries;
import io.aeyer.plowshare.server.relay.JdbcRelayDeliveryRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayPortRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayPublishingRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import io.aeyer.plowshare.server.relay.RelayDeliveries;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayPorts;
import io.aeyer.plowshare.server.relay.RelayProjectFiles;
import io.aeyer.plowshare.server.relay.RelayPublishing;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import io.aeyer.plowshare.server.relay.RelayPublishingRepository;
import io.aeyer.plowshare.server.relay.RelayReceiver;
import io.aeyer.plowshare.server.relay.RelayWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Requires PostgreSQL source joins, V252 FK/receipt mapping and broker/native transaction rollback.
 */
@Tag("full-db")
@Testcontainers
class MessageExportsDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource dataSource;
  private BoardFixture f;
  private Relay relay;
  private RelayMessaging messages;
  private RelayMessageExports exports;
  private JdbcMessageExportRepository repository;
  private RelayPublishingRepository publications;
  private MessageExportProperties.Policy policy;
  private UsageAttribution owner;
  private long project;
  private Relay.TopicKey returnTopic;
  private String conversation;

  @BeforeAll
  static void migrate() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  @BeforeEach
  void setup() throws Exception {
    f = new BoardFixture(dataSource);
    f.jdbc.execute("TRUNCATE inference_run_ownership CASCADE");
    f.jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('adapter','fixture')");
    f.jdbc.update(
        "INSERT INTO projects(name,workspace) VALUES('payments','fixture') ON CONFLICT(name) DO NOTHING");
    project = f.jdbc.queryForObject("SELECT id FROM projects WHERE name='payments'", Long.class);
    conversation = f.conversations.open(Home.of("payments"), Budget.of(20), null, "enzo").id();
    owner = owner(conversation, "worker");
    proof(owner);
    new EntryStore(f.jdbc, f.work)
        .append(conversation, 1, LoggedEntry.utterance("Source request", Speaker.person("enzo")));
    var definition =
        new AgentDefinition(
            "worker", "Worker", "model", List.of(), List.of(), List.of(), 4, 8, "Work");
    messages =
        new RelayMessaging(
            new JdbcMessagingRepository(f.jdbc),
            new JdbcAllowanceRepository(f.jdbc),
            f.conversations,
            f.firings,
            MessageInboxFixture.inboxes(f),
            f.work,
            mock(MessageService.Voice.class),
            (name, scope) -> definition,
            f.drained::add,
            f.clock);
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
            f.clock);
    returnTopic = new Relay.TopicKey(project, "external.returns");
    relay.registerTopic(returnTopic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    policy =
        new MessageExportProperties.Policy(
            "payments", "external.requests", "channel", "external.returns", "adapter");
    var properties = new MessageExportProperties();
    properties.setPolicies(List.of(policy));
    var ports = mock(RelayPorts.class);
    when(ports.authorizePublisher("adapter", "payments", "external.returns"))
        .thenReturn(returnTopic);
    var members = mock(ProjectMembers.class);
    when(members.mayWork("payments", "enzo")).thenReturn(true);
    var projects = mock(ProjectWorkspaces.class);
    when(projects.personalOwner("payments")).thenReturn(Optional.empty());
    repository = new JdbcMessageExportRepository(f.jdbc);
    exports =
        new RelayMessageExports(
            properties, repository, messages, relay, ports, members, projects, f.work, 8);
    publications = new JdbcRelayPublishingRepository(f.jdbc, f.work, relay, exports);
  }

  private UsageAttribution owner(String log, String agent) {
    return UsageAttribution.project(
            "enzo", Long.toString(project), UsageAttribution.Operation.AGENT_CHAT)
        .withExecution(
            UsageLineage.root(log),
            UsageLineage.root("run:" + log + ":1"),
            UsageLineage.NONE,
            agent,
            1L,
            1L);
  }

  private void proof(UsageAttribution attribution) throws Exception {
    f.jdbc.update(
        "INSERT INTO inference_run_ownership(conversation_id,turn_ordinal,attribution) VALUES(?,1,?::jsonb)",
        attribution.conversations().id(),
        new ObjectMapper().writeValueAsString(attribution));
  }

  private RelayPublishingRepository.Intent intent() {
    return new RelayPublishingRepository.Intent(
        UUID.randomUUID(),
        project,
        "payments",
        owner,
        "call",
        new RelayPublishing.Request("external.requests", "External work"),
        RelayPort.hash("intent"),
        Instant.parse("2026-11-13T00:00:00Z"),
        new RelayCausation("root", owner.runs().id(), 1));
  }

  private MessageExports.Command returned(UUID request, UUID source, boolean terminal) {
    return new MessageExports.Command(
        project,
        "payments",
        "channel",
        "external.returns",
        "adapter",
        new MessageExports.Return(request, source, "Remote result", terminal),
        request.toString());
  }

  @Test
  void native_request_export_and_correlated_reply_are_atomic_idempotent_and_broker_independent() {
    var intent = intent();
    var receipt = publications.publish(intent);
    assertEquals(receipt, publications.publish(intent));
    var binding = repository.binding(intent.id(), false).orElseThrow();
    assertEquals(
        conversation,
        messages.instance(binding.nativeRequest().requester()).orElseThrow().conversation());
    assertFalse(messages.route(binding.nativeRequest().requestMessage()).orElseThrow().handled());
    assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
    var command = returned(UUID.randomUUID(), intent.id(), true);
    var reply = exports.accept(command);
    assertEquals(reply, exports.accept(command));
    assertEquals(
        binding.nativeRequest().requestMessage(),
        messages.route(reply.message()).orElseThrow().replyTo());
    assertTrue(messages.route(binding.nativeRequest().requestMessage()).orElseThrow().handled());
    MessageInboxFixture.pulse(f);
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM firings WHERE data->>'direct_message'=?",
            Integer.class,
            reply.message()));
    f.jdbc.update(
        "DELETE FROM relay_topics WHERE name IN ('external.requests','external.returns')");
    assertEquals(receipt, publications.publish(intent));
    assertEquals(reply, exports.inspect(command).orElseThrow());
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM message_execution_exports", Integer.class));
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM message_execution_returns", Integer.class));
  }

  @Test
  void a_new_return_after_export_broker_expiry_preserves_bound_cause_and_depth_limit() {
    var intent = intent();
    publications.publish(intent);
    // Only the exported publication expires. The configured return incarnation remains live.
    f.jdbc.update(
        "DELETE FROM relay_topics WHERE project_id=? AND name='external.requests'", project);
    var command = returned(UUID.randomUUID(), intent.id(), false);
    var reply = exports.accept(command);
    var cause =
        f.jdbc.queryForObject(
            "SELECT relay_causation::text FROM message_execution_returns WHERE request_id=?",
            String.class,
            command.value().requestId());
    assertEquals(
        new RelayCausation("root", command.returnEvent(), 2), RelayCausationCodec.read(cause));
    assertTrue(messages.route(reply.message()).isPresent());
    f.jdbc.update(
        "UPDATE message_execution_exports SET relay_causation=?::jsonb WHERE source_event=?",
        RelayCausationCodec.write(new RelayCausation("root", "last-parent", 8)),
        intent.id());
    int before = f.jdbc.queryForObject("SELECT count(*) FROM message_records", Integer.class);
    assertThrows(
        MessageRefused.class, () -> exports.accept(returned(UUID.randomUUID(), intent.id(), true)));
    assertEquals(
        before, f.jdbc.queryForObject("SELECT count(*) FROM message_records", Integer.class));
  }

  @Test
  void broker_append_failure_rolls_back_native_request_participants_and_export_evidence() {
    relay.registerTopic(
        new Relay.TopicKey(project, "external.requests"),
        RelayPayload.Kind.LIFECYCLE,
        Relay.Policy.systemDefault());
    assertThrows(IllegalArgumentException.class, () -> publications.publish(intent()));
    for (String table :
        List.of(
            "message_records",
            "message_instances",
            "message_execution_exports",
            "relay_execution_publications"))
      assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
  }

  @Test
  void return_rollback_retains_outstanding_request_and_uuid_conflict_cannot_add_another_reply() {
    var intent = intent();
    publications.publish(intent);
    var command = returned(UUID.randomUUID(), intent.id(), true);
    assertThrows(
        IllegalStateException.class,
        () ->
            f.work.inTransaction(
                () -> {
                  exports.accept(command);
                  throw new IllegalStateException("rollback fixture");
                }));
    assertTrue(exports.inspect(command).isEmpty());
    assertFalse(
        messages
            .route(
                repository
                    .binding(intent.id(), false)
                    .orElseThrow()
                    .nativeRequest()
                    .requestMessage())
            .orElseThrow()
            .handled());
    var reply = exports.accept(command);
    var conflict =
        new MessageExports.Command(
            project,
            "payments",
            "channel",
            "external.returns",
            "adapter",
            new MessageExports.Return(
                command.value().requestId(), intent.id(), "Changed result", true),
            command.returnEvent());
    assertThrows(MessageRefused.class, () -> exports.accept(conflict));
    assertEquals(reply, exports.inspect(command).orElseThrow());
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM message_execution_returns", Integer.class));
  }

  @Test
  void cancellation_and_archival_do_not_revive_the_requester_or_owe_a_return_wake() {
    var intent = intent();
    publications.publish(intent);
    var binding = repository.binding(intent.id(), false).orElseThrow();
    messages.cancel(binding.nativeRequest().requestMessage(), "enzo");
    int afterCancel = f.jdbc.queryForObject("SELECT count(*) FROM message_records", Integer.class);
    assertThrows(
        MessageRefused.class, () -> exports.accept(returned(UUID.randomUUID(), intent.id(), true)));
    assertEquals(
        afterCancel, f.jdbc.queryForObject("SELECT count(*) FROM message_records", Integer.class));
    var other = intent();
    publications.publish(other);
    f.conversations.moveTo(conversation, ConversationLifecycle.ARCHIVED);
    assertThrows(
        MessageRefused.class, () -> exports.accept(returned(UUID.randomUUID(), other.id(), true)));
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM message_execution_returns", Integer.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"stop", "archive"})
  @Timeout(30)
  void lifecycle_change_winning_the_mailbox_lock_refuses_late_return_without_any_paid_wake(
      String change) throws Exception {
    var intent = intent();
    publications.publish(intent);
    var binding = repository.binding(intent.id(), false).orElseThrow();
    var command = returned(UUID.randomUUID(), intent.id(), true);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          f.work.inTransaction(
              () -> {
                new JdbcMessagingRepository(f.jdbc).lockAddress("mailbox:enzo");
                var entered = new CountDownLatch(1);
                var incoming =
                    executor.submit(
                        () -> {
                          entered.countDown();
                          return exports.accept(command);
                        });
                try {
                  assertTrue(entered.await(5, TimeUnit.SECONDS));
                  assertThrows(
                      TimeoutException.class, () -> incoming.get(150, TimeUnit.MILLISECONDS));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
                if (change.equals("stop"))
                  messages.stop(binding.nativeRequest().requester(), "enzo", false);
                else f.conversations.moveTo(conversation, ConversationLifecycle.ARCHIVED);
                return incoming;
              });
      var refused = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
      assertTrue(refused.getCause() instanceof MessageRefused);
    }
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM message_execution_returns", Integer.class));
    assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
  }

  @Test
  @Timeout(30)
  void
      concurrent_equal_returns_share_one_native_reply_and_different_terminal_returns_cannot_duplicate()
          throws Exception {
    var intent = intent();
    publications.publish(intent);
    var command = returned(UUID.randomUUID(), intent.id(), true);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> {
                start.await();
                return exports.accept(command);
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                return exports.accept(command);
              });
      start.countDown();
      assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM message_execution_returns", Integer.class));
    var other = intent();
    publications.publish(other);
    var again = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> {
                again.await();
                try {
                  exports.accept(returned(UUID.randomUUID(), other.id(), true));
                  return true;
                } catch (MessageRefused refused) {
                  return false;
                }
              });
      var b =
          executor.submit(
              () -> {
                again.await();
                try {
                  exports.accept(returned(UUID.randomUUID(), other.id(), true));
                  return true;
                } catch (MessageRefused refused) {
                  return false;
                }
              });
      again.countDown();
      assertFalse(a.get(10, TimeUnit.SECONDS).equals(b.get(10, TimeUnit.SECONDS)));
    }
    assertEquals(
        2, f.jdbc.queryForObject("SELECT count(*) FROM message_execution_returns", Integer.class));
  }

  @Test
  void
      native_message_in_a_turn_conversation_keeps_exact_recipient_and_unproven_sources_fail_closed() {
    var nativeRepository = new JdbcMessagingRepository(f.jdbc);
    var handling =
        new MessageService.Instance(
            "ins_Handling", "enzo", "payments", "worker", conversation, "persistent", true);
    var caller =
        new MessageService.Instance(
            "ins_OtherCaller", "enzo", "payments", "observer", conversation, "caller", true);
    nativeRepository.insertInstance(handling, false);
    nativeRepository.insertInstance(caller, false);
    var original =
        nativeRepository.insertMessage(
            handling.id(), "Native request in a person's history", f.clock.get());
    nativeRepository.insertRoute(
        new MessageService.Route(
            original.id(), caller.id(), handling.id(), null, true, false, false, null, false),
        "fixture-native",
        RelayPort.hash("native"),
        4,
        true);
    f.jdbc.update("DELETE FROM entries WHERE conversation_id=?", conversation);
    new EntryStore(f.jdbc, f.work)
        .append(
            conversation,
            1,
            LoggedEntry.utterance("Native request", Speaker.message(original.id())));
    assertEquals(handling, repository.source(owner).participant().orElseThrow());
    var intent = intent();
    publications.publish(intent);
    assertEquals(
        handling.id(),
        repository.binding(intent.id(), false).orElseThrow().nativeRequest().requester());
    f.jdbc.update(
        "UPDATE entries SET speaker_name='approval unproven' WHERE conversation_id=?",
        conversation);
    assertThrows(MessageRefused.class, () -> repository.source(owner));
    f.jdbc.update("DELETE FROM entries WHERE conversation_id=?", conversation);
    assertThrows(MessageRefused.class, () -> repository.source(owner));
  }

  @Test
  void message_source_uses_exact_turn_speaker_not_an_ambiguous_conversation_agent_lookup()
      throws Exception {
    var view = messages.open("enzo", "payments", "worker", false, UUID.randomUUID().toString());
    var actual = messages.instance(view.id()).orElseThrow();
    var nativeRepository = new JdbcMessagingRepository(f.jdbc);
    var other =
        new MessageService.Instance(
            "ins_Lookalike",
            "enzo",
            "payments",
            "observer",
            view.conversation(),
            "persistent",
            true);
    nativeRepository.insertInstance(other, false);
    var original = nativeRepository.insertMessage(actual.id(), "Original request", f.clock.get());
    nativeRepository.insertRoute(
        new MessageService.Route(
            original.id(), other.id(), actual.id(), null, true, false, false, null, false),
        "fixture-call",
        RelayPort.hash("fixture"),
        4,
        true);
    new EntryStore(f.jdbc, f.work)
        .append(
            view.conversation(),
            1,
            LoggedEntry.utterance("Original request", Speaker.message(original.id())));
    var messageOwner = owner(view.conversation(), "worker");
    proof(messageOwner);
    assertEquals(actual, repository.source(messageOwner).participant().orElseThrow());
    var forged =
        messageOwner.withExecution(
            messageOwner.conversations(),
            UsageLineage.root("foreign-run"),
            UsageLineage.NONE,
            "worker",
            1L,
            1L);
    assertThrows(MessageRefused.class, () -> repository.source(forged));
    f.jdbc.update(
        "UPDATE entries SET speaker_name='approval unproven' WHERE conversation_id=?",
        view.conversation());
    assertThrows(MessageRefused.class, () -> repository.source(messageOwner));
  }

  private record NativeBoard(
      BoardNativeMessageRequests nativeRequests,
      JdbcBoardNativeExportRepository proofs,
      BoardBridge bridge,
      BoardClosing closing,
      Board board,
      BoardTopic topic,
      RelayPublishingRepository publisher,
      ProjectMembers members,
      ProjectWorkspaces projects,
      BoardMessageExportProperties properties,
      RelayPublishingProperties grants) {}

  private NativeBoard nativeBoard(NativeMessageExports nativeApi) {
    var properties = new BoardMessageExportProperties();
    properties.setPolicies(
        List.of(
            new BoardMessageExportProperties.Policy(
                "payments", "forum.requests", "board", "board.post")));
    var grants = new RelayPublishingProperties();
    grants.setGrants(
        List.of(new RelayPublishingProperties.Grant("payments", "worker", "forum.requests")));
    var members = mock(ProjectMembers.class);
    when(members.mayWork("payments", "enzo")).thenReturn(true);
    var projects = mock(ProjectWorkspaces.class);
    when(projects.id("payments")).thenReturn(project);
    when(projects.personalOwner("payments")).thenReturn(Optional.empty());
    var proofs = new JdbcBoardNativeExportRepository(f.jdbc);
    var boundary =
        new BoardNativeMessageRequests(
            properties,
            new MessageExportProperties(),
            proofs,
            repository,
            nativeApi,
            grants,
            members,
            projects,
            8,
            f.work,
            Duration.ofSeconds(30),
            f.clock);
    var board = f.board(BoardFixture.TWO);
    var topic = f.openByBot(board).topic();
    var receipts = new JdbcBoardPostRepository(f.jdbc);
    var closing = new ProjectBoardClosing(board, f.store, receipts, members, projects, f.work);
    var bridge =
        new ForumBoardBridge(
            board,
            f.store,
            receipts,
            new JdbcBoardBridgeRepository(f.jdbc),
            closing,
            scope -> List.of(BoardFixture.TWO),
            relay,
            members,
            projects,
            f.work,
            boundary);
    return new NativeBoard(
        boundary,
        proofs,
        bridge,
        closing,
        board,
        topic,
        new JdbcRelayPublishingRepository(f.jdbc, f.work, relay, boundary),
        members,
        projects,
        properties,
        grants);
  }

  private RelayPublishingRepository.Intent boardIntent() {
    return new RelayPublishingRepository.Intent(
        UUID.randomUUID(),
        project,
        "payments",
        owner,
        "board-call",
        new RelayPublishing.Request(
            "forum.requests",
            "{\"schema\":\"plowshare-board-request/1\",\"body\":\"Investigate\"}"),
        RelayPort.hash("board-work"),
        f.clock.get(),
        new RelayCausation("root", owner.runs().id(), 1));
  }

  private record BoardDelivery(
      BoardRelayReceiver receiver,
      RelayReceiver.Request claimed,
      RelayReceiver.Request dispatching) {}

  private BoardDelivery boardDelivery(NativeBoard board, RelayPublishingRepository.Intent intent) {
    return boardDelivery(board, intent, "fixture.board", board.topic().id());
  }

  private BoardDelivery boardDelivery(
      NativeBoard board, RelayPublishingRepository.Intent intent, String group, String target) {
    board.publisher().publish(intent);
    var topic = new Relay.TopicKey(project, "forum.requests");
    var sub = new Relay.SubscriptionKey(topic, group);
    relay.subscribe(sub, Relay.Start.OLDEST_RETAINED);
    var publication = relay.retained(topic, intent.id().toString()).orElseThrow();
    var deliveries =
        new DurableRelayDeliveries(new JdbcRelayDeliveryRepository(f.jdbc, f.work), f.clock);
    // A new independent group begins at the oldest retained event. Earlier fixture exports have
    // no branch for this scenario; admit those empty decisions before selecting its intended event.
    for (var earlier : relay.read(sub, 1000).publications()) {
      if (earlier.position() >= publication.position()) break;
      deliveries.admit(
          new RelayDeliveries.AdmissionKey(sub, earlier.position()),
          new RelayDeliveries.Decision(
              RelayDeliveries.SourcePin.of(
                  "board/routes.js", "export function route(){return [];}"),
              List.of()));
    }
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(sub, publication.position()),
        new RelayDeliveries.Decision(
            RelayDeliveries.SourcePin.of("board/routes.js", "export function route(){return [];}"),
            List.of(
                new RelayDeliveries.Branch(
                    "board",
                    "board.post",
                    null,
                    null,
                    new RelayWork(null, null, null, target, null)))));
    var claimed = deliveries.claim(sub, "fixture.board", Duration.ofSeconds(30)).orElseThrow();
    var access = new RelayProjectFiles.Access("enzo", "payments", project);
    var receiver =
        new BoardRelayReceiver(
            "board.post",
            board.bridge(),
            new JdbcRelayPortRepository(f.jdbc, f.work, relay),
            mock(RelayPorts.class),
            (value, maximum) -> Optional.ofNullable(value.event().causation()),
            8,
            board.nativeRequests());
    return new BoardDelivery(
        receiver,
        new RelayReceiver.Request(access, claimed),
        new RelayReceiver.Request(access, deliveries.prepareDispatch(claimed.claim())));
  }

  private BoardBridge.Receipt dispatchBoard(BoardDelivery delivery) {
    delivery.receiver().require(delivery.claimed());
    var accepted =
        (RelayDeliveries.Accepted)
            ((RelayReceiver.Settled) delivery.receiver().dispatch(delivery.dispatching()))
                .resolution();
    var message = f.store.message(accepted.receipt().id()).orElseThrow();
    return new BoardBridge.Receipt(message.topic(), message.id());
  }

  private BoardMessage forumAnswer(NativeBoard board, String parent, String body) {
    return board
        .board()
        .post(
            new Board.Post(
                board.topic().id(),
                BoardMessage.BY_PERSON,
                "enzo",
                null,
                null,
                BoardMessage.POST,
                null,
                body,
                parent,
                List.of(),
                false))
        .message();
  }

  @Test
  void
      execution_board_request_and_full_answers_return_to_the_original_native_instance_without_person_attribution() {
    var board = nativeBoard(messages);
    var intent = boardIntent();
    var delivery = boardDelivery(board, intent);
    long seats = f.jdbc.queryForObject("SELECT count(*) FROM board_seats", Long.class);
    var accepted = dispatchBoard(delivery);
    var post = f.store.message(accepted.message()).orElseThrow();
    assertEquals(BoardMessage.BY_AGENT, post.authorKind());
    assertEquals("worker", post.author());
    assertEquals(null, post.conversation());
    assertEquals(null, post.entry());
    assertEquals(seats, f.jdbc.queryForObject("SELECT count(*) FROM board_seats", Long.class));
    forumAnswer(board, accepted.message(), "Full forum answer 😀");
    var drain =
        new RelayBoardReplies(
            new JdbcBoardBridgeRepository(f.jdbc),
            relay,
            f.work,
            Duration.ofSeconds(30),
            f.clock,
            board.nativeRequests());
    assertTrue(drain.publishNext());
    board
        .closing()
        .close(
            "enzo",
            new BoardClosing.Command(
                "payments", board.topic().id(), "Final resolution", List.of(), UUID.randomUUID()));
    assertTrue(drain.publishNext());
    assertFalse(drain.publishNext());
    var nativeRequest = board.proofs().binding(intent.id()).orElseThrow().nativeRequest();
    assertEquals(
        conversation, messages.instance(nativeRequest.requester()).orElseThrow().conversation());
    assertEquals(
        2,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM message_routes WHERE reply_to=?",
            Integer.class,
            nativeRequest.requestMessage()));
    assertTrue(
        f.jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM message_records WHERE body LIKE '%Full forum answer 😀%')",
            Boolean.class));
    assertTrue(messages.route(nativeRequest.requestMessage()).orElseThrow().handled());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_outbox", Integer.class));
    MessageInboxFixture.pulse(f);
    assertEquals(
        2,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM firings WHERE message_instance=?",
            Integer.class,
            nativeRequest.requester()));
  }

  @Test
  void
      known_native_requester_unavailable_commits_terminal_archive_without_rollback_only_or_native_writes() {
    var board = nativeBoard(messages);
    var intent = boardIntent();
    var accepted = dispatchBoard(boardDelivery(board, intent));
    forumAnswer(board, accepted.message(), "Owed answer retained for inspection");
    var nativeRequest = board.proofs().binding(intent.id()).orElseThrow().nativeRequest();
    messages.stop(nativeRequest.requester(), "enzo", false);
    int before = f.jdbc.queryForObject("SELECT count(*) FROM message_records", Integer.class);
    var drain =
        new RelayBoardReplies(
            new JdbcBoardBridgeRepository(f.jdbc),
            relay,
            f.work,
            Duration.ofSeconds(30),
            f.clock,
            board.nativeRequests());
    assertTrue(drain.publishNext());
    assertEquals(
        before, f.jdbc.queryForObject("SELECT count(*) FROM message_records", Integer.class));
    var failure =
        new JdbcBoardBridgeRepository(f.jdbc)
            .failure(deliveryIdFor(accepted.message()), "enzo")
            .orElseThrow();
    assertEquals(BoardBridgeRepository.Destination.NATIVE_UNAVAILABLE, failure.reason());
    assertEquals(1, failure.owedMessages());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_outbox", Integer.class));
  }

  private UUID deliveryIdFor(String message) {
    return f.jdbc.queryForObject(
        "SELECT request_id FROM board_relay_requests WHERE message_id=?", UUID.class, message);
  }

  @Test
  void
      transient_native_answer_failure_rolls_back_and_retries_debt_without_archiving_or_repeating_forum_action() {
    var nativeApi = mock(NativeMessageExports.class);
    when(nativeApi.export(any(), any(), any(), any()))
        .thenAnswer(
            call ->
                messages.export(
                    call.getArgument(0),
                    call.getArgument(1),
                    call.getArgument(2),
                    call.getArgument(3)));
    when(nativeApi.tryReturnReply(any(), any()))
        .thenThrow(new IllegalStateException("Transient storage fixture"));
    var board = nativeBoard(nativeApi);
    var intent = boardIntent();
    var accepted = dispatchBoard(boardDelivery(board, intent));
    forumAnswer(board, accepted.message(), "Recoverable full answer");
    var drain =
        new RelayBoardReplies(
            new JdbcBoardBridgeRepository(f.jdbc),
            relay,
            f.work,
            Duration.ofSeconds(30),
            f.clock,
            board.nativeRequests());
    assertThrows(IllegalStateException.class, drain::publishNext);
    assertTrue(
        new JdbcBoardBridgeRepository(f.jdbc)
            .failure(deliveryIdFor(accepted.message()), "enzo")
            .isEmpty());
    assertEquals(
        1, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_outbox", Integer.class));
    doAnswer(call -> messages.tryReturnReply(call.getArgument(0), call.getArgument(1)))
        .when(nativeApi)
        .tryReturnReply(any(), any());
    f.jdbc.update(
        "UPDATE board_relay_requests SET next_attempt_at=CURRENT_TIMESTAMP WHERE request_id=?",
        deliveryIdFor(accepted.message()));
    assertTrue(drain.publishNext());
    assertFalse(drain.publishNext());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM board_relay_failed_outbox", Integer.class));
  }

  @Test
  void native_preflight_refusal_has_restart_safe_final_outcome_and_read_only_reconciliation() {
    var board = nativeBoard(messages);
    var intent = boardIntent();
    var delivery = boardDelivery(board, intent);
    board
        .closing()
        .close(
            "enzo",
            new BoardClosing.Command(
                "payments", board.topic().id(), "Already closed", List.of(), UUID.randomUUID()));
    int before = f.jdbc.queryForObject("SELECT count(*) FROM board_messages", Integer.class);
    delivery.receiver().require(delivery.claimed());
    assertTrue(board.proofs().outcome(delivery.dispatching().identity(), "enzo").isEmpty());
    var result = (RelayReceiver.Settled) delivery.receiver().dispatch(delivery.dispatching());
    assertEquals(new RelayDeliveries.Failed("board.native.refused"), result.resolution());
    assertEquals(
        before, f.jdbc.queryForObject("SELECT count(*) FROM board_messages", Integer.class));
    assertEquals(
        BoardNativeExportRepository.OutcomeState.PENDING,
        board.proofs().outcome(delivery.dispatching().identity(), "enzo").orElseThrow().state());
    var fresh =
        new BoardNativeMessageRequests(
            board.properties(),
            new MessageExportProperties(),
            new JdbcBoardNativeExportRepository(f.jdbc),
            repository,
            messages,
            board.grants(),
            board.members(),
            board.projects(),
            8,
            f.work,
            Duration.ofSeconds(30),
            f.clock);
    var restarted =
        new RelayBoardReplies(
            new JdbcBoardBridgeRepository(f.jdbc),
            relay,
            f.work,
            Duration.ofSeconds(30),
            f.clock,
            fresh);
    assertTrue(restarted.publishNext());
    assertFalse(restarted.publishNext());
    var outcome = board.proofs().outcome(delivery.dispatching().identity(), "enzo").orElseThrow();
    assertEquals(BoardNativeExportRepository.OutcomeState.PUBLISHED, outcome.state());
    assertTrue(board.proofs().outcome(delivery.dispatching().identity(), "other").isEmpty());
    var old = delivery.dispatching().delivery();
    var uncertain =
        new RelayDeliveries.Delivery(
            old.key(),
            old.admission(),
            old.publication(),
            old.routing(),
            old.branch(),
            RelayDeliveries.State.UNCERTAIN,
            old.fence(),
            null,
            null,
            old.admittedAt(),
            old.updatedAt(),
            old.receipt(),
            "fixture.uncertain");
    assertEquals(
        new RelayDeliveries.Failed("board.native.refused"),
        delivery
            .receiver()
            .inspect(new RelayReceiver.Request(delivery.dispatching().access(), uncertain))
            .orElseThrow());
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM message_routes WHERE reply_to=?",
            Integer.class,
            board.proofs().binding(intent.id()).orElseThrow().nativeRequest().requestMessage()));
  }

  @Test
  @Timeout(30)
  void
      two_consumer_groups_share_one_native_forum_effect_and_cannot_retarget_or_send_a_false_final_refusal()
          throws Exception {
    var board = nativeBoard(messages);
    var intent = boardIntent();
    var first = boardDelivery(board, intent, "fixture.first", board.topic().id());
    var second = boardDelivery(board, intent, "fixture.second", board.topic().id());
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> {
                start.await();
                return dispatchBoard(first);
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                return dispatchBoard(second);
              });
      start.countDown();
      assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM board_relay_requests WHERE native_source_event=?",
            Integer.class,
            intent.id()));
    var other = f.openByBot(board.board()).topic();
    var retargeted = boardDelivery(board, intent, "fixture.retargeted", other.id());
    retargeted.receiver().require(retargeted.claimed());
    var conflict = (RelayReceiver.Settled) retargeted.receiver().dispatch(retargeted.dispatching());
    assertEquals(new RelayDeliveries.Failed("board.native.intent-conflict"), conflict.resolution());
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM message_board_outcomes", Integer.class));
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM board_messages WHERE author_kind='agent'", Integer.class));
    String message =
        f.jdbc.queryForObject(
            "SELECT message_id FROM board_relay_requests WHERE native_source_event=?",
            String.class,
            intent.id());
    forumAnswer(board, message, "Only the original board reply");
    var drain =
        new RelayBoardReplies(
            new JdbcBoardBridgeRepository(f.jdbc),
            relay,
            f.work,
            Duration.ofSeconds(30),
            Instant::now,
            board.nativeRequests());
    assertTrue(drain.publishNext());
    assertFalse(drain.publishNext());
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM message_routes WHERE reply_to=?",
            Integer.class,
            board.proofs().binding(intent.id()).orElseThrow().nativeRequest().requestMessage()));
  }

  @Test
  @Timeout(30)
  void
      concurrent_changed_targets_accept_only_one_effect_and_refusal_cannot_race_an_accepted_export()
          throws Exception {
    var board = nativeBoard(messages);
    var other = f.openByBot(board.board()).topic();
    var intent = boardIntent();
    var first = boardDelivery(board, intent, "fixture.original", board.topic().id());
    var second = boardDelivery(board, intent, "fixture.other", other.id());
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> {
                start.await();
                return ((RelayReceiver.Settled) first.receiver().dispatch(first.dispatching()))
                    .resolution();
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                return ((RelayReceiver.Settled) second.receiver().dispatch(second.dispatching()))
                    .resolution();
              });
      start.countDown();
      assertFalse(
          a.get(10, TimeUnit.SECONDS).getClass().equals(b.get(10, TimeUnit.SECONDS).getClass()));
    }
    assertEquals(
        1,
        f.jdbc.queryForObject(
            "SELECT count(*) FROM board_relay_requests WHERE native_source_event=?",
            Integer.class,
            intent.id()));
    assertEquals(
        0, f.jdbc.queryForObject("SELECT count(*) FROM message_board_outcomes", Integer.class));
    var racing = boardIntent();
    board
        .closing()
        .close(
            "enzo",
            new BoardClosing.Command(
                "payments", other.id(), "Closed target", List.of(), UUID.randomUUID()));
    var valid = boardDelivery(board, racing, "fixture.valid", board.topic().id());
    var refused = boardDelivery(board, racing, "fixture.refused", other.id());
    var again = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          executor.submit(
              () -> {
                again.await();
                return valid.receiver().dispatch(valid.dispatching());
              });
      var b =
          executor.submit(
              () -> {
                again.await();
                return refused.receiver().dispatch(refused.dispatching());
              });
      again.countDown();
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    int effects =
        f.jdbc.queryForObject(
            "SELECT count(*) FROM board_relay_requests WHERE native_source_event=?",
            Integer.class,
            racing.id());
    int outcomes =
        f.jdbc.queryForObject(
            "SELECT count(*) FROM message_board_outcomes WHERE source_event=?",
            Integer.class,
            racing.id());
    assertEquals(1, effects + outcomes);
  }
}
