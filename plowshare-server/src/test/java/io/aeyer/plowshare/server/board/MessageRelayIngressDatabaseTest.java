package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.messaging.JdbcMessageRelayRepository;
import io.aeyer.plowshare.server.messaging.JdbcMessagingRepository;
import io.aeyer.plowshare.server.messaging.MessageIngress;
import io.aeyer.plowshare.server.messaging.MessageRefused;
import io.aeyer.plowshare.server.messaging.MessageRelayAccess;
import io.aeyer.plowshare.server.messaging.MessageRelayCodec;
import io.aeyer.plowshare.server.messaging.MessageRelayDefinitions;
import io.aeyer.plowshare.server.messaging.MessageRelayIngress;
import io.aeyer.plowshare.server.messaging.MessageRelayPages;
import io.aeyer.plowshare.server.messaging.MessageRelayPayload;
import io.aeyer.plowshare.server.messaging.MessageRelayProperties;
import io.aeyer.plowshare.server.messaging.MessageRelayRepository;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.messaging.RelayMessageIngress;
import io.aeyer.plowshare.server.messaging.RelayMessageRelayOutbox;
import io.aeyer.plowshare.server.messaging.RelayMessaging;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayDeliveryRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.MessageRelayReceiver;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayDeliveries;
import io.aeyer.plowshare.server.relay.RelayForwardingHistory;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayProjectFiles;
import io.aeyer.plowshare.server.relay.RelayPublisherAccounts;
import io.aeyer.plowshare.server.relay.RelayReceiver;
import io.aeyer.plowshare.server.relay.RelayRepository;
import io.aeyer.plowshare.server.relay.RelayWork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual Flyway, revision triggers, rollback, bounded PostgreSQL slices and competing row locks.
 */
@Tag("full-db")
@Testcontainers
class MessageRelayIngressDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  @TempDir Path definitions;
  private BoardFixture fixture;
  private JdbcMessagingRepository nativeRepository;
  private JdbcMessageRelayRepository repository;
  private RelayMessaging messages;
  private MessageRelayPages pages;
  private RelayMessageIngress ingress;
  private MessageRelayProperties properties;
  private MessageRelayRepository.Family family;
  private Relay relay;
  private RelayRepository broker;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() throws Exception {
    fixture = new BoardFixture(source);
    fixture.jdbc.update("UPDATE admins SET server_admin=true WHERE handle='enzo'");
    fixture.conversations.open(Home.of("payments"), Budget.of(10), TurnCap.of(4), "enzo");
    Files.writeString(
        definitions.resolve("worker.md"),
        "---\nname: worker\ndescription: fixture\nmodel: test\ntools: [send_message]\nmax-turns: 4\nmax-model-calls: 8\n---\nHelp.\n");
    var agents = AgentRegistry.of(definitions, Set.of("send_message"));
    nativeRepository = new JdbcMessagingRepository(fixture.jdbc);
    repository = new JdbcMessageRelayRepository(fixture.jdbc);
    messages =
        new RelayMessaging(
            nativeRepository,
            new JdbcAllowanceRepository(fixture.jdbc),
            fixture.conversations,
            fixture.firings,
            MessageInboxFixture.inboxes(fixture),
            fixture.work,
            mock(MessageService.Voice.class),
            (agent, project) -> agents.get(agent),
            fixture.drained::add,
            fixture.clock);
    pages = new MessageRelayPages(messages, repository, fixture.work);
    properties = new MessageRelayProperties();
    ingress = service(messages);
    long project =
        fixture.jdbc.queryForObject("SELECT id FROM projects WHERE name='payments'", Long.class);
    broker =
        new JdbcRelayRepository(
            fixture.jdbc,
            fixture.work,
            new JdbcRelayPayloadStore(
                fixture.jdbc,
                fixture.work,
                Duration.ofHours(1),
                Duration.ofHours(24),
                Duration.ofDays(30)));
    relay = new DurableRelay(broker, fixture.clock);
    var commands =
        relay.registerTopic(
            new Relay.TopicKey(project, "commands"),
            RelayPayload.Kind.TEXT,
            Relay.Policy.systemDefault());
    var results =
        relay.registerTopic(
            new Relay.TopicKey(project, "results"),
            RelayPayload.Kind.TEXT,
            Relay.Policy.systemDefault());
    family =
        new MessageRelayRepository.Family(
            "relay-fixture",
            "enzo",
            "payments",
            project,
            RelayPort.publisher("enzo"),
            "commands",
            "relay.fixture.commands",
            commands.generation(),
            "fixture",
            null,
            "worker",
            "results",
            results.generation());
  }

  private RelayMessageIngress service(MessageIngress nativeMessages) {
    return new RelayMessageIngress(
        nativeMessages,
        repository,
        new MessageRelayPages(nativeMessages, repository, fixture.work),
        mock(MessageRelayDefinitions.class),
        fixture.work,
        properties,
        () -> {});
  }

  private MessageRelayIngress.Request receive(
      UUID request, UUID delivery, String event, String body) {
    return command(
        new MessageRelayPayload.Receive(
            "plowshare-message-request/1", request, "client", null, body, null),
        delivery,
        event);
  }

  private MessageRelayIngress.Request command(
      MessageRelayPayload.Command command, UUID delivery, String event) {
    String receiver =
        command instanceof MessageRelayPayload.Receive
            ? "message.receive"
            : command instanceof MessageRelayPayload.Cancel ? "message.cancel" : "message.inspect";
    return new MessageRelayIngress.Request(
        new MessageRelayRepository.Intent(
            delivery,
            family,
            command.client(),
            command.requestId(),
            event,
            receiver,
            RelayPort.hash(command.toString() + "\n" + event),
            RelayCausation.root("source-root"),
            fixture.clock.get()),
        command);
  }

  private long count(String table) {
    if (!Set.of(
            "message_external_tasks",
            "message_external_contexts",
            "message_records",
            "message_relay_operations",
            "message_relay_bindings",
            "message_relay_task_outbox")
        .contains(table)) throw new IllegalArgumentException();
    return fixture.jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  @Test
  void native_effect_and_receipt_commit_once_with_exact_event_identity_and_alias_recovery() {
    UUID request = UUID.randomUUID();
    var input = receive(request, UUID.randomUUID(), "receive-event", "Question");
    var accepted = ingress.accept(input);
    assertNull(accepted.refusal());
    assertEquals(1, count("message_external_tasks"));
    assertEquals(1, count("message_relay_bindings"));
    assertEquals(
        0,
        count("message_relay_task_outbox"),
        "The complete initial page is already retained, not redundantly captured");
    assertEquals(accepted, ingress.accept(input));
    var alias = receive(request, UUID.randomUUID(), "receive-event", "Question");
    assertEquals(accepted, ingress.accept(alias));
    assertEquals(
        accepted,
        repository
            .receipt(
                alias.intent().delivery(),
                family.projectId(),
                family.publisher(),
                family.sourceTopic(),
                "receive-event")
            .orElseThrow());
    assertTrue(
        repository
            .receipt(
                alias.intent().delivery(),
                family.projectId(),
                family.publisher(),
                "other",
                "receive-event")
            .isEmpty());
    assertEquals(
        "REQUEST_CONFLICT",
        ingress.accept(receive(request, UUID.randomUUID(), "altered-event", "Question")).refusal());
    assertEquals(
        "REQUEST_CONFLICT",
        ingress.accept(receive(request, UUID.randomUUID(), "receive-event", "Changed")).refusal());
    assertEquals(1, count("message_external_tasks"));
    assertEquals(1, count("message_records"));
    assertEquals(
        "source-root",
        fixture.jdbc.queryForObject(
            "SELECT relay_causation->>'rootId' FROM message_routes WHERE message=?",
            String.class,
            accepted.task().message()));
    var inspect =
        command(
            new MessageRelayPayload.Inspect(
                "plowshare-message-inspect/1", UUID.randomUUID(), "client", null, request, null),
            UUID.randomUUID(),
            "inspect-event");
    assertEquals(accepted.task().id(), ingress.accept(inspect).task().id());
  }

  @Test
  void failure_after_native_writes_rolls_back_all_work_before_negative_receipt_is_committed() {
    var refusing = mock(MessageIngress.class);
    when(refusing.receive(anyString(), any()))
        .thenAnswer(
            call -> {
              messages.receive(call.getArgument(0), call.getArgument(1));
              throw new MessageRefused("fixture failure after native write");
            });
    var result =
        service(refusing)
            .accept(receive(UUID.randomUUID(), UUID.randomUUID(), "failing-event", "Question"));
    assertEquals("NOT_ACCESSIBLE", result.refusal());
    assertEquals(0, count("message_external_tasks"));
    assertEquals(0, count("message_external_contexts"));
    assertEquals(0, count("message_records"));
    assertEquals(1, count("message_relay_operations"));
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE publisher LIKE 'messaging:%'",
            Long.class));
  }

  private void reply(String request, String body) {
    fixture.work.inTransaction(
        () -> {
          var original = messages.route(request).orElseThrow();
          var record = nativeRepository.insertMessage(original.sender(), body, fixture.clock.get());
          nativeRepository.insertRoute(
              new MessageService.Route(
                  record.id(),
                  original.recipient(),
                  original.sender(),
                  request,
                  false,
                  false,
                  false,
                  null,
                  false,
                  original.causation()),
              null,
              null,
              1,
              false);
          return null;
        });
  }

  @Test
  void real_unicode_slices_preserve_over_200_replies_and_follow_reads_only_new_history() {
    var admitted =
        ingress.accept(receive(UUID.randomUUID(), UUID.randomUUID(), "initial", "Question"));
    String body = "😀".repeat(20000) + " \n\t";
    reply(admitted.task().message(), body);
    for (int index = 0; index < 205; index++) reply(admitted.task().message(), "Reply " + index);
    long revision =
        fixture.jdbc.queryForObject(
            "SELECT revision FROM message_external_tasks WHERE id=?",
            Long.class,
            admitted.task().id());
    assertTrue(revision > Long.parseLong(admitted.revision()));
    var current =
        pages.snapshot(family, "client", admitted.task().id(), UUID.randomUUID(), "inspect", null);
    List<String> bodies = new ArrayList<>();
    StringBuilder assembling = new StringBuilder();
    while (true) {
      for (var chunk : current.task().replies()) {
        assertEquals(
            assembling.codePointCount(0, assembling.length()), Integer.parseInt(chunk.offset()));
        assembling.append(chunk.body());
        if (chunk.complete()) {
          bodies.add(assembling.toString());
          assembling.setLength(0);
        }
      }
      if (current.next() == null) break;
      current =
          pages.snapshot(
              family, "client", admitted.task().id(), UUID.randomUUID(), "inspect", current.next());
    }
    assertEquals(206, bodies.size());
    assertEquals(body, bodies.getFirst());
    assertEquals("Reply 204", bodies.getLast());
    String resume = current.resume();
    reply(admitted.task().message(), "Only new body");
    var next =
        pages.snapshot(family, "client", admitted.task().id(), UUID.randomUUID(), "follow", resume);
    assertEquals(
        List.of("Only new body"),
        next.task().replies().stream().map(MessageRelayPayload.Reply::body).toList());
    assertNull(next.next());
    var untouched =
        pages.snapshot(
            family,
            "client",
            admitted.task().id(),
            UUID.randomUUID(),
            "follow-again",
            next.resume());
    assertTrue(untouched.task().replies().isEmpty());
  }

  @Test
  void query_receipt_is_immutable_while_fresh_query_and_cancel_advance_revision() {
    var admitted =
        ingress.accept(receive(UUID.randomUUID(), UUID.randomUUID(), "initial", "Question"));
    var query =
        command(
            new MessageRelayPayload.Inspect(
                "plowshare-message-inspect/1",
                UUID.randomUUID(),
                "client",
                admitted.task().id(),
                null,
                null),
            UUID.randomUUID(),
            "query");
    var before = ingress.accept(query);
    reply(admitted.task().message(), "New answer");
    assertEquals(before, ingress.accept(query));
    var after =
        ingress.accept(
            command(
                new MessageRelayPayload.Inspect(
                    "plowshare-message-inspect/1",
                    UUID.randomUUID(),
                    "client",
                    admitted.task().id(),
                    null,
                    null),
                UUID.randomUUID(),
                "fresh"));
    assertTrue(Long.parseLong(after.revision()) > Long.parseLong(before.revision()));
    assertEquals(1, after.task().replies().size());
    var canceled =
        ingress.accept(
            command(
                new MessageRelayPayload.Cancel(
                    "plowshare-message-cancel/1",
                    UUID.randomUUID(),
                    "client",
                    admitted.task().id()),
                UUID.randomUUID(),
                "cancel"));
    assertEquals("CANCELED", canceled.task().state());
    assertTrue(Long.parseLong(canceled.revision()) > Long.parseLong(after.revision()));
  }

  @Test
  void exact_legacy_mapping_preserves_ids_and_source_evidence_without_cross_channel_adoption() {
    UUID request = UUID.randomUUID();
    var legacy =
        messages.receive(
            "enzo",
            new MessageIngress.Request(
                "payments",
                "client",
                "worker",
                request,
                null,
                "Legacy body",
                null,
                new MessageIngress.Source(
                    "legacy-ws", "old-message", RelayPort.hash("old-fingerprint"))));
    var inspect =
        command(
            new MessageRelayPayload.Inspect(
                "plowshare-message-inspect/1", UUID.randomUUID(), "client", null, request, null),
            UUID.randomUUID(),
            "unmapped");
    assertEquals("NOT_ACCESSIBLE", ingress.accept(inspect).refusal());
    properties.setLegacyMappings(
        List.of(
            new MessageRelayProperties.LegacyMapping(
                "enzo",
                "payments",
                "client",
                family.sourceTopic(),
                family.subscriber(),
                family.sourceGeneration(),
                family.channel(),
                family.agent(),
                family.replyTopic(),
                family.replyGeneration())));
    var adopted =
        ingress.accept(
            command(
                new MessageRelayPayload.Inspect(
                    "plowshare-message-inspect/1",
                    UUID.randomUUID(),
                    "client",
                    null,
                    request,
                    null),
                UUID.randomUUID(),
                "mapped"));
    assertEquals(legacy.id(), adopted.task().id());
    assertEquals(legacy.context(), adopted.task().context());
    assertEquals(legacy.message(), adopted.task().message());
    assertEquals(
        legacy.source(),
        messages.summary("enzo", "payments", family.namespace(), "client", legacy.id()).source());
    assertEquals(0, count("message_relay_task_outbox"));
    assertThrows(
        MessageRefused.class,
        () -> messages.summary("enzo", "payments", "another-channel", "client", legacy.id()));
    assertThrows(
        MessageRefused.class,
        () ->
            messages.summary(
                "enzo", "payments", family.namespace(), "another-client", legacy.id()));
  }

  @Test
  void snapshot_revision_lock_serializes_cancel_without_task_to_route_deadlock() throws Exception {
    var admitted =
        ingress.accept(receive(UUID.randomUUID(), UUID.randomUUID(), "initial", "Question"));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var reader =
          executor.submit(
              () ->
                  fixture.work.inTransaction(
                      () -> {
                        var before =
                            messages.summary(
                                "enzo",
                                "payments",
                                family.namespace(),
                                "client",
                                admitted.task().id());
                        locked.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("fixture release timeout");
                        } catch (InterruptedException failure) {
                          throw new IllegalStateException(failure);
                        }
                        return before;
                      }));
      assertTrue(locked.await(5, TimeUnit.SECONDS));
      var cancel =
          executor.submit(
              () ->
                  messages.cancelTask(
                      "enzo", "payments", family.namespace(), "client", admitted.task().id()));
      try {
        assertFalse(cancel.isDone());
      } finally {
        release.countDown();
      }
      var before = reader.get(10, TimeUnit.SECONDS);
      var after = cancel.get(10, TimeUnit.SECONDS);
      assertNotEquals("CANCELED", before.state());
      assertEquals("CANCELED", after.state());
      assertTrue(after.revision() > before.revision());
    }
  }

  @Test
  void transactional_outbox_rolls_back_append_before_retry_without_repeating_native_work() {
    var admitted =
        ingress.accept(receive(UUID.randomUUID(), UUID.randomUUID(), "initial", "Question"));
    var broken = mock(Relay.class);
    when(broken.publish(any(), any(), any()))
        .thenAnswer(
            call -> {
              relay.publish(call.getArgument(0), call.getArgument(1), call.getArgument(2));
              throw new IllegalStateException("fixture failure after append");
            });
    var failing = new RelayMessageRelayOutbox(repository, pages, value -> {}, broken, fixture.work);
    assertTrue(failing.publishNext());
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE publisher='messaging:ingress'",
            Long.class));
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM message_relay_operations WHERE published", Long.class));
    assertTrue(
        fixture.jdbc.queryForObject(
            "SELECT retry_at>clock_timestamp() FROM message_relay_operations", Boolean.class));
    assertFalse(failing.publishNext(), "Backoff excludes the same failed operation");
    fixture.jdbc.update("UPDATE message_relay_operations SET retry_at='-infinity'");
    var healthy = new RelayMessageRelayOutbox(repository, pages, value -> {}, relay, fixture.work);
    assertTrue(healthy.publishNext());
    assertFalse(healthy.publishNext());
    assertEquals(1, count("message_external_tasks"));
    assertEquals(1, count("message_records"));
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE publisher='messaging:ingress'",
            Long.class));
    reply(admitted.task().message(), "Async reply");
    assertEquals(1, count("message_relay_task_outbox"));
    assertTrue(healthy.captureNext());
    assertEquals(0, count("message_relay_task_outbox"));
    assertTrue(healthy.publishNext());
    assertEquals(
        2,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE publisher='messaging:ingress'",
            Long.class));
  }

  @Test
  void deleted_or_recreated_return_topic_never_retargets_retained_snapshot() {
    var admitted =
        ingress.accept(receive(UUID.randomUUID(), UUID.randomUUID(), "initial", "Question"));
    reply(admitted.task().message(), "Owed before destination replacement");
    assertEquals(1, count("message_relay_task_outbox"));
    fixture.jdbc.update(
        "DELETE FROM relay_topics WHERE project_id=? AND name='results'", family.projectId());
    relay.registerTopic(family.replies(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var outbox = new RelayMessageRelayOutbox(repository, pages, value -> {}, relay, fixture.work);
    assertTrue(outbox.publishNext());
    assertEquals(
        "REPLACED",
        fixture.jdbc.queryForObject(
            "SELECT destination_failure FROM message_relay_operations", String.class));
    assertFalse(outbox.publishNext());
    assertTrue(
        fixture.jdbc.queryForObject(
            "SELECT disabled FROM message_relay_bindings WHERE task=?",
            Boolean.class,
            admitted.task().id()));
    assertEquals(0, count("message_relay_task_outbox"));
    reply(admitted.task().message(), "Retained later without recreating dead worker debt");
    assertEquals(0, count("message_relay_task_outbox"));
    assertFalse(outbox.captureNext());
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE publisher='messaging:ingress'",
            Long.class));
    assertEquals(
        admitted.task().id(),
        repository.task(family, "client", null, admitted.task().requestId()).orElseThrow());
    assertEquals(1, count("message_external_tasks"));
  }

  @Test
  void
      native_route_pins_and_source_generation_survive_broker_admission_and_recreated_source_refuses() {
    var sourceKey = new Relay.TopicKey(family.projectId(), family.sourceTopic());
    var subscription = new Relay.SubscriptionKey(sourceKey, family.subscriber());
    relay.subscribe(subscription, Relay.Start.OLDEST_RETAINED);
    UUID operation = UUID.randomUUID();
    var publication =
        relay.publish(
            sourceKey,
            new Relay.Draft(
                "source-operation",
                family.publisher(),
                fixture.clock.get(),
                null,
                null,
                new RelayPayload.Text(
                    "{\"schema\":\"plowshare-message-request/1\",\"requestId\":\""
                        + operation
                        + "\",\"client\":\"client\",\"context\":null,\"body\":\"Question\",\"command\":null}"),
                RelayCausation.root("root")));
    var deliveries = new JdbcRelayDeliveryRepository(fixture.jdbc, fixture.work);
    var work = new RelayWork("worker", null, null, null, null, "results", "fixture");
    var decision =
        new RelayDeliveries.Decision(
            RelayDeliveries.SourcePin.of(
                "fixture/routes.js", "export function route(){return [];}"),
            List.of(new RelayDeliveries.Branch("native", "message.receive", null, null, work)));
    var key = new RelayDeliveries.AdmissionKey(subscription, publication.position());
    deliveries.admit(key, decision, fixture.clock.get());
    assertEquals(
        work, deliveries.admission(key).orElseThrow().decision().branches().getFirst().work());
    var claimed =
        deliveries
            .claim(subscription, "fixture", Duration.ofSeconds(30), fixture.clock.get())
            .orElseThrow();
    var captured =
        deliveries.prepareDispatch(
            new RelayDeliveries.Claim(claimed.key(), claimed.worker(), claimed.fence()),
            fixture.clock.get());
    assertEquals(family.sourceGeneration(), captured.sourceGeneration());
    assertEquals(work, captured.branch().work());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            fixture.jdbc.update(
                "UPDATE relay_deliveries SET work_reply_topic=NULL WHERE id=?",
                captured.key().id()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            fixture.jdbc.update(
                "UPDATE relay_deliveries SET work_project='foreign' WHERE id=?",
                captured.key().id()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            fixture.jdbc.update(
                "UPDATE relay_deliveries SET work_channel=NULL WHERE id=?", captured.key().id()));
    fixture.jdbc.update(
        "UPDATE relay_deliveries SET receiver='agent.run',work_reply_topic=NULL,work_channel=NULL WHERE id=?",
        captured.key().id());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            fixture.jdbc.update(
                "UPDATE relay_deliveries SET work_channel='fixture' WHERE id=?",
                captured.key().id()));
    fixture.jdbc.update(
        "UPDATE relay_deliveries SET receiver='message.receive',work_reply_topic='results',work_channel='fixture' WHERE id=?",
        captured.key().id());
    fixture.jdbc.update(
        "DELETE FROM relay_topics WHERE project_id=? AND name=?",
        family.projectId(),
        family.sourceTopic());
    relay.registerTopic(sourceKey, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    assertTrue(deliveries.delivery(captured.key()).isEmpty());
    var nativeIngress = mock(MessageRelayIngress.class);
    var publishers = mock(RelayPublisherAccounts.class);
    when(publishers.publisherAccount(family.publisher())).thenReturn(Optional.of("enzo"));
    var receiver =
        new MessageRelayReceiver(
            "message.receive",
            nativeIngress,
            mock(MessageRelayAccess.class),
            publishers,
            broker,
            mock(RelayForwardingHistory.class),
            8,
            fixture.clock);
    assertThrows(
        RelayReceiver.Refused.class,
        () ->
            receiver.dispatch(
                new RelayReceiver.Request(
                    new RelayProjectFiles.Access("enzo", "payments", family.projectId()),
                    captured)));
    verifyNoInteractions(nativeIngress);
    assertEquals(0, count("message_external_tasks"));
  }

  @Test
  void later_snapshot_chunks_wait_for_the_previous_notice_before_capture_and_publication() {
    var admitted =
        ingress.accept(receive(UUID.randomUUID(), UUID.randomUUID(), "initial", "Question"));
    reply(admitted.task().message(), "😀".repeat(40000));
    var outbox = new RelayMessageRelayOutbox(repository, pages, value -> {}, relay, fixture.work);
    assertFalse(
        outbox.captureNext(),
        "The initial notice must be published before body traversal advances");
    assertTrue(outbox.publishNext());
    List<String> offsets = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      assertTrue(outbox.captureNext());
      assertFalse(
          outbox.captureNext(),
          "An unpublished chunk must fence its successor even during backoff");
      String encoded =
          fixture.jdbc.queryForObject(
              "SELECT snapshot FROM message_relay_operations WHERE NOT published", String.class);
      offsets.add(MessageRelayCodec.stored(encoded).task().replies().getFirst().offset());
      assertTrue(outbox.publishNext());
    }
    assertEquals(List.of("0", "16384", "32768"), offsets);
    assertFalse(outbox.captureNext());
    assertEquals(0, count("message_relay_task_outbox"));
  }

  @Test
  void terminal_cleanup_waits_for_the_native_revision_writer_and_leaves_no_recreated_debt()
      throws Exception {
    var admitted =
        ingress.accept(receive(UUID.randomUUID(), UUID.randomUUID(), "initial", "Question"));
    reply(admitted.task().message(), "Already owed");
    fixture.jdbc.update(
        "DELETE FROM relay_topics WHERE project_id=? AND name='results'", family.projectId());
    relay.registerTopic(family.replies(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var observed = spy(repository);
    var attempting = new CountDownLatch(1);
    doAnswer(
            call -> {
              attempting.countDown();
              return call.callRealMethod();
            })
        .when(observed)
        .lockTask(any());
    var outbox = new RelayMessageRelayOutbox(observed, pages, value -> {}, relay, fixture.work);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          executor.submit(
              () ->
                  fixture.work.inTransaction(
                      () -> {
                        nativeRepository
                            .lockExternalTask(
                                "enzo",
                                "payments",
                                family.namespace(),
                                "client",
                                admitted.task().id())
                            .orElseThrow();
                        locked.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("fixture release timeout");
                        } catch (InterruptedException failed) {
                          throw new IllegalStateException(failed);
                        }
                        reply(
                            admitted.task().message(),
                            "Committed by the writer that already owns the fence");
                        return null;
                      }));
      assertTrue(locked.await(5, TimeUnit.SECONDS));
      var terminal = executor.submit(outbox::publishNext);
      try {
        assertTrue(attempting.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> terminal.get(100, TimeUnit.MILLISECONDS));
        assertFalse(
            fixture.jdbc.queryForObject(
                "SELECT disabled FROM message_relay_bindings WHERE task=?",
                Boolean.class,
                admitted.task().id()));
      } finally {
        release.countDown();
      }
      writer.get(10, TimeUnit.SECONDS);
      assertTrue(terminal.get(10, TimeUnit.SECONDS));
    }
    assertTrue(
        fixture.jdbc.queryForObject(
            "SELECT disabled FROM message_relay_bindings WHERE task=?",
            Boolean.class,
            admitted.task().id()));
    assertEquals(0, count("message_relay_task_outbox"));
    reply(
        admitted.task().message(),
        "Later native writes retain history but create no disabled debt");
    assertEquals(0, count("message_relay_task_outbox"));
  }

  @Test
  void removed_positive_task_terminalizes_its_notice_without_append_or_repeated_retry() {
    var admitted =
        ingress.accept(receive(UUID.randomUUID(), UUID.randomUUID(), "initial", "Question"));
    fixture.jdbc.update("DELETE FROM message_external_tasks WHERE id=?", admitted.task().id());
    var outbox = new RelayMessageRelayOutbox(repository, pages, value -> {}, relay, fixture.work);
    assertTrue(outbox.publishNext());
    assertEquals(
        "TASK_UNAVAILABLE",
        fixture.jdbc.queryForObject(
            "SELECT destination_failure FROM message_relay_operations", String.class));
    assertFalse(outbox.publishNext());
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE publisher='messaging:ingress'",
            Long.class));
  }
}
