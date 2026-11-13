package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayRetirementRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelayMessageInboxesTest {
  private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
  private final MessagingRepository messages = mock(MessagingRepository.class);
  private final MessageInboxRepository inboxes = mock(MessageInboxRepository.class);
  private final Relay relay = mock(Relay.class);
  private final RelayConsumerRepository consumers = mock(RelayConsumerRepository.class);
  private final FiringStore firings = mock(FiringStore.class);
  private final RelayRetirementRepository retirement = mock(RelayRetirementRepository.class);
  private final MessagePayloadCodec codec = new MessagePayloadCodec(65536);
  private final MessagePublications publications = new RelayMessagePublications(relay, codec);
  private final MessageInboxes transport =
      new RelayMessageInboxes(
          messages,
          inboxes,
          publications,
          relay,
          consumers,
          firings,
          UnitOfWork.NONE,
          target -> {},
          () -> NOW,
          retirement);
  private final Relay.SubscriptionKey subscription =
      new Relay.SubscriptionKey(
          new Relay.TopicKey(9, "messaging.agent.fixture"), "messaging.inbox");
  private final MessageService.Instance recipient =
      new MessageService.Instance(
          "ins_Recipient", "alice", "project", "worker", "cnv_Recipient", "persistent", true);
  private final MessageService.Route route =
      new MessageService.Route(
          "bdm_Message", "ins_Sender", recipient.id(), null, true, false, false, null, false);
  private final MessageRecord record =
      new MessageRecord(route.message(), recipient.id(), "Request", NOW);

  @BeforeEach
  void setup() {
    when(inboxes.ready(isNull(), eq(32)))
        .thenReturn(
            List.of(new MessageInboxRepository.Inbox("alice", "project", "worker", subscription)));
    when(consumers.acquire(eq(subscription), anyString(), eq("alice"), any()))
        .thenReturn(
            Optional.of(
                new RelayConsumerRepository.Lease(
                    subscription, "worker", "alice", 1, NOW.plusSeconds(30))));
    when(messages.admissions(
            List.of(new MessagingRepository.AdmissionRequest(route.message(), true))))
        .thenReturn(
            Map.of(
                route.message(),
                new MessagingRepository.Admission(route, recipient, Optional.of(record.body()))));
    when(messages.admit(route.message())).thenReturn(true);
    when(firings.oweMessage(anyString(), anyString(), any(), any(), any()))
        .thenReturn(Optional.of(mock(FiringRecord.class)));
  }

  private Relay.Publication publication(MessagePayload payload) {
    return new Relay.Publication(
        subscription.topic(),
        1,
        NOW,
        new Relay.Draft(
            payload.message(),
            "messaging:" + RelayPort.hash("alice"),
            NOW,
            null,
            null,
            new RelayPayload.Text(codec.write(payload))));
  }

  private MessagePayload payload() {
    return new MessagePayload(
        route.message(),
        route.sender(),
        route.recipient(),
        new MessagePayload.Inline("Request"),
        null,
        recipient.conversation(),
        true,
        false);
  }

  @Test
  void cleanup_rechecks_retired_binding_before_removing_broker_and_forgetting_identity() {
    var inbox = new MessageInboxRepository.Inbox("alice", "project", "worker", subscription);
    when(inboxes.retired(isNull(), eq(32))).thenReturn(List.of(inbox));
    when(inboxes.lockRetired(inbox)).thenReturn(true);
    when(retirement.retireOwned(subscription)).thenReturn(true);
    assertEquals(1, transport.cleanupRetired());
    var ordered = inOrder(inboxes, retirement);
    ordered.verify(inboxes).retired(null, 32);
    ordered.verify(inboxes).lockRetired(inbox);
    ordered.verify(retirement).retireOwned(subscription);
    ordered.verify(inboxes).forgetRetired(inbox);
  }

  @Test
  void a_reactivated_binding_prevents_stale_physical_cleanup() {
    var inbox = new MessageInboxRepository.Inbox("alice", "project", "worker", subscription);
    when(inboxes.retired(isNull(), eq(32))).thenReturn(List.of(inbox));
    when(inboxes.lockRetired(inbox)).thenReturn(false);
    assertEquals(0, transport.cleanupRetired());
    verify(retirement, never()).retireOwned(any());
    verify(inboxes, never()).forgetRetired(any());
  }

  @Test
  void retained_references_do_not_request_or_hydrate_the_message_body() {
    var reference =
        new MessagePayload(
            route.message(),
            route.sender(),
            route.recipient(),
            new MessagePayload.Retained(route.message()),
            null,
            recipient.conversation(),
            true,
            false);
    var request = new MessagingRepository.AdmissionRequest(route.message(), false);
    when(messages.admissions(List.of(request)))
        .thenReturn(
            Map.of(
                route.message(),
                new MessagingRepository.Admission(route, recipient, Optional.empty())));
    when(relay.read(subscription, 32))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(subscription, 0, NOW),
                Optional.empty(),
                List.of(publication(reference))));
    assertEquals(1, transport.poll());
    verify(messages).admissions(List.of(request));
    verify(messages, never()).message(anyString());
  }

  @Test
  void retirement_requires_authoritative_absence_and_rechecks_durable_debt() {
    var inbox = new MessageInboxRepository.Inbox("alice", "project", "worker", subscription);
    when(inboxes.drained(isNull(), eq(32))).thenReturn(List.of(inbox));
    when(inboxes.retire(inbox)).thenReturn(true);
    assertEquals(1, transport.retireMissing((account, project) -> Set.of()));
    verify(inboxes).retire(inbox);
  }

  @Test
  void unavailable_definition_inventory_never_means_absence() {
    var inbox = new MessageInboxRepository.Inbox("alice", "project", "worker", subscription);
    when(inboxes.drained(isNull(), eq(32))).thenReturn(List.of(inbox));
    assertThrows(
        IllegalStateException.class,
        () ->
            transport.retireMissing(
                (account, project) -> {
                  throw new IllegalStateException("Inventory unavailable");
                }));
    verify(inboxes, never()).retire(any());
  }

  @Test
  void batch_is_validated_before_one_snapshot_read_and_native_wake_precedes_cursor_advance() {
    when(relay.read(subscription, 32))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(subscription, 0, NOW),
                Optional.empty(),
                List.of(publication(payload()))));
    assertEquals(1, transport.poll());
    var ordered = inOrder(messages, firings, consumers);
    ordered.verify(consumers).requireOwned(any());
    ordered
        .verify(messages)
        .admissions(List.of(new MessagingRepository.AdmissionRequest(route.message(), true)));
    ordered.verify(messages).admit(route.message());
    ordered
        .verify(firings)
        .oweMessage(
            recipient.id(),
            RelayMessageInboxes.target(recipient),
            new EventPayload.Message(new MessageWake(route.message())),
            NOW,
            1L);
    ordered.verify(consumers).advanceSeen(any(), eq(1L));
    verify(messages, never()).instance(anyString());
    verify(messages, never()).route(anyString());
    verify(messages, never()).message(anyString());
  }

  @Test
  void foreign_publishers_are_skipped_without_interpreting_their_invalid_carrier() {
    var foreign =
        new Relay.Publication(
            subscription.topic(),
            1,
            NOW,
            new Relay.Draft(
                "foreign",
                "sdk:" + RelayPort.hash("alice"),
                NOW,
                null,
                null,
                new RelayPayload.Text("not JSON")));
    when(relay.read(subscription, 32))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(subscription, 0, NOW), Optional.empty(), List.of(foreign)));
    assertEquals(0, transport.poll());
    verify(consumers).advanceSeen(any(), eq(1L));
    verify(messages, never()).admit(anyString());
    verify(firings, never()).oweMessage(anyString(), anyString(), any(), any(), any());
  }

  @Test
  void a_forged_instance_route_cannot_owe_a_wake_or_advance_the_owned_publication() {
    var forged =
        new MessagePayload(
            route.message(),
            "ins_Forged",
            route.recipient(),
            new MessagePayload.Inline("Request"),
            null,
            recipient.conversation(),
            true,
            false);
    when(relay.read(subscription, 32))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(subscription, 0, NOW),
                Optional.empty(),
                List.of(publication(forged))));
    assertThrows(IllegalStateException.class, transport::poll);
    verify(messages, never()).admit(anyString());
    verify(consumers, never()).advanceSeen(any(), anyLong());
  }

  @Test
  void a_gap_never_acknowledges_lost_arrival_or_runs_message_logic() {
    when(relay.read(subscription, 32))
        .thenReturn(
            new Relay.Read(
                new Relay.Subscription(subscription, 0, NOW),
                Optional.of(new Relay.Gap(0, 5)),
                List.of()));
    assertThrows(IllegalStateException.class, transport::poll);
    verify(messages, never()).admissions(any());
    verify(consumers, never()).acknowledgeGap(any(), anyLong());
    verify(consumers).release(any());
  }
}
