package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the consumer does with a publication, and what it refuses to do.
 *
 * <p>No database: the broker is the {@link Relay} contract and is mocked, because every assertion
 * here is about this subsystem's own decisions — whether it issued a publication, whether it can
 * decode one it issued, and where it leaves its cursor. Real PostgreSQL would not make any of them
 * truer and is used in {@code RelayEventPublicationDatabaseTest} only for the things that need it.
 */
class RelayEventPublicationTest {
  private static final Instant AT = Instant.parse("2026-10-25T09:00:00Z");

  /**
   * A publisher identity in this subsystem's own namespace: the prefix and a 64-character digest.
   */
  private static final String OURS = "event:" + RelayPort.hash("enzo");

  private Relay relay;
  private FiredArrival arrivals;
  private EventPublication publication;
  private Relay.TopicKey topic;
  private Relay.SubscriptionKey subscription;

  @BeforeEach
  void wire() {
    relay = mock(Relay.class);
    arrivals = mock(FiredArrival.class);
    when(arrivals.recordFired(any(), any(), any())).thenReturn(List.of());
    publication = new RelayEventPublication(relay, arrivals, UnitOfWork.NONE, () -> {}, () -> AT);
    // Derived the way the publisher derives it, from the scope, rather than written down here: a
    // test that spelled the name would be the counterexample FiredEventTopicNameIsPrivateTest
    // exists to forbid, and would stop being a test of the derivation.
    topic =
        new Relay.TopicKey(Relay.SystemScope.SERVER, EventTopics.fired(Relay.SystemScope.SERVER));
    subscription = new Relay.SubscriptionKey(topic, "builtin.events");
  }

  /** Teaches a mocked broker to accept a registration, a subscription and one append. */
  private void answerBroker(Relay broker, long position, Relay.Read read) {
    when(broker.registerTopic(any(), any(), any()))
        .thenAnswer(
            call ->
                new Relay.Topic(
                    call.getArgument(0),
                    RelayPayload.Kind.TEXT,
                    Relay.Policy.systemDefault(),
                    position,
                    0));
    when(broker.subscribe(any(), any()))
        .thenAnswer(call -> new Relay.Subscription(call.getArgument(0), position - 1, AT));
    when(broker.publish(any(), any()))
        .thenAnswer(
            call -> new Relay.Publication(call.getArgument(0), position, AT, call.getArgument(1)));
    when(broker.read(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(read);
  }

  private Relay.Publication at(long position, String publisher, RelayPayload payload) {
    return new Relay.Publication(
        topic,
        position,
        AT,
        new Relay.Draft("event:id-" + position, publisher, AT, null, null, payload));
  }

  private RelayPayload.Text notice(String event, EventPayload data) {
    return new RelayPayload.Text(FiredEventCodec.write(new FiredEventCodec.Notice(event, data)));
  }

  /** Reads back whatever the batch holds, after the publisher has appended its own. */
  private void serving(Relay.Read read) {
    answerBroker(relay, 1, read);
  }

  // ---------------------------------------------------------------------------------------------
  // What it issued
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_publication_this_subsystem_issued_is_decoded_and_recorded() {
    serving(
        new Relay.Read(
            new Relay.Subscription(subscription, 0, AT),
            Optional.empty(),
            List.of(at(1, OURS, notice("daily", new EventPayload.Text("x=1"))))));
    publication.fire("daily", new EventPayload.Text("x=1"), "enzo");
    verify(arrivals).recordFired("daily", new EventPayload.Text("x=1"), AT);
    verify(relay).advanceSeen(subscription, 1);
  }

  /**
   * Every state a publisher identity can be in, not only the one shape the implementation happens
   * to reject. Each of these must be skipped rather than decoded, and the cursor must still move.
   */
  @Test
  void a_publication_from_any_foreign_publisher_is_skipped_and_the_cursor_still_advances() {
    String hex = RelayPort.hash("enzo");
    for (String foreign :
        new String[] {
          // Another subsystem's namespace, and the SDK namespace an external publisher gets.
          "sdk:" + hex,
          "information:" + hex,
          "filter:" + hex,
          // The right prefix, a digest of the wrong length.
          "event:" + hex.substring(0, 63),
          "event:" + hex + "0",
          "event:",
          // The right prefix and length, but not lower-case hex.
          "event:" + hex.substring(0, 63).toUpperCase() + "A",
          "event:" + "g".repeat(64),
          // The right prefix and a digest, with something appended.
          "event:" + hex + ":extra",
          // This family's own event-identity shape, which is a UUID and not a digest.
          "event:" + java.util.UUID.randomUUID(),
          // A prefix that merely contains ours.
          "not-event:" + hex,
          "eventual:" + hex
        }) {
      var broker = mock(Relay.class);
      var inbox = mock(FiredArrival.class);
      var publisher = new RelayEventPublication(broker, inbox, UnitOfWork.NONE, () -> {}, () -> AT);
      var row =
          new Relay.Publication(
              topic,
              7,
              AT,
              new Relay.Draft(
                  "event:foreign",
                  foreign,
                  AT,
                  null,
                  null,
                  notice("daily", new EventPayload.Empty())));
      answerBroker(
          broker,
          1,
          new Relay.Read(
              new Relay.Subscription(subscription, 6, AT), Optional.empty(), List.of(row)));
      publisher.fire("daily", new EventPayload.Empty(), "enzo");
      verify(inbox, never()).recordFired(any(), any(), any());
      // The whole point of skipping rather than refusing: one foreign row must not be able to wedge
      // this subscriber's feed for the topic's entire retention window.
      verify(broker).advanceSeen(subscription, 7);
    }
  }

  /**
   * The remaining state a publisher can be in: absent. It is unreachable through the contract
   * rather than handled by the consumer, so it is pinned where it is actually refused — a publisher
   * check that stopped tolerating null would be a change to {@code Relay.Draft}, not to this class.
   */
  @Test
  void a_publication_with_no_publisher_at_all_cannot_exist() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Relay.Draft(
                "event:none", null, AT, null, null, notice("daily", new EventPayload.Empty())));
  }

  @Test
  void a_foreign_row_before_one_of_ours_does_not_stop_ours_being_recorded() {
    serving(
        new Relay.Read(
            new Relay.Subscription(subscription, 0, AT),
            Optional.empty(),
            List.of(
                at(
                    1,
                    "sdk:" + RelayPort.hash("stranger"),
                    notice("daily", new EventPayload.Empty())),
                at(2, OURS, notice("daily", new EventPayload.Text("x=1"))))));
    publication.fire("daily", new EventPayload.Text("x=1"), "enzo");
    verify(arrivals).recordFired("daily", new EventPayload.Text("x=1"), AT);
    verify(arrivals, never()).recordFired(eq("daily"), eq(new EventPayload.Empty()), any());
    verify(relay).advanceSeen(subscription, 2);
  }

  // ---------------------------------------------------------------------------------------------
  // What it issued and cannot read
  // ---------------------------------------------------------------------------------------------

  /**
   * Every payload a publication of ours could be carrying other than the general message. Each is
   * corruption of this subsystem's own writing, so each fails loudly rather than being skipped: a
   * raised event the caller was told nothing about must not vanish.
   */
  @Test
  void every_non_message_payload_on_a_publication_of_ours_fails_loudly() {
    for (RelayPayload wrong :
        new RelayPayload[] {
          new RelayPayload.Empty(),
          new RelayPayload.ScheduleDue("morning", "daily", AT),
          new RelayPayload.Lifecycle("some.topic", "subject", "state", null, null),
          new RelayPayload.WakeRequested("fir_1", "conversation:cnv_1", RelayPayload.WakeKind.BOARD)
        }) {
      var broker = mock(Relay.class);
      var inbox = mock(FiredArrival.class);
      var publisher = new RelayEventPublication(broker, inbox, UnitOfWork.NONE, () -> {}, () -> AT);
      answerBroker(
          broker,
          1,
          new Relay.Read(
              new Relay.Subscription(subscription, 0, AT),
              Optional.empty(),
              List.of(at(1, OURS, wrong))));
      var thrown =
          assertThrows(
              IllegalStateException.class,
              () -> publisher.fire("daily", new EventPayload.Empty(), "enzo"),
              "accepted as a fired event payload: " + wrong);
      assertTrue(thrown.getMessage().contains("non-message payload"));
      verify(inbox, never()).recordFired(any(), any(), any());
      verify(broker, never()).advanceSeen(any(), org.mockito.ArgumentMatchers.anyLong());
    }
  }

  @Test
  void a_message_of_ours_that_will_not_decode_fails_loudly_and_moves_no_cursor() {
    serving(
        new Relay.Read(
            new Relay.Subscription(subscription, 0, AT),
            Optional.empty(),
            List.of(at(1, OURS, new RelayPayload.Text("{\"schema\":\"someone-elses/1\"}")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> publication.fire("daily", new EventPayload.Empty(), "enzo"));
    verify(arrivals, never()).recordFired(any(), any(), any());
    verify(relay, never()).advanceSeen(any(), org.mockito.ArgumentMatchers.anyLong());
  }

  // ---------------------------------------------------------------------------------------------
  // The publication itself
  // ---------------------------------------------------------------------------------------------

  @Test
  void the_topic_is_registered_on_the_server_scope_as_the_general_message() {
    serving(
        new Relay.Read(new Relay.Subscription(subscription, 1, AT), Optional.empty(), List.of()));
    publication.fire("daily", new EventPayload.Empty(), "enzo");
    verify(relay).registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    assertEquals(Relay.SystemScope.SERVER, topic.scope());
  }

  /**
   * First registration starts at the cutover baseline. Replaying would hand the firing inbox events
   * it already holds, which is the reason the schedules migration made the same choice.
   */
  @Test
  void the_subscription_starts_at_the_latest_position_and_never_replays() {
    serving(
        new Relay.Read(new Relay.Subscription(subscription, 1, AT), Optional.empty(), List.of()));
    publication.fire("daily", new EventPayload.Empty(), "enzo");
    verify(relay).subscribe(subscription, Relay.Start.LATEST);
  }

  @Test
  void the_publisher_names_no_account_and_the_event_identity_is_fresh_each_time() {
    serving(
        new Relay.Read(new Relay.Subscription(subscription, 1, AT), Optional.empty(), List.of()));
    var drafts = org.mockito.ArgumentCaptor.forClass(Relay.Draft.class);
    publication.fire("daily", new EventPayload.Text("x=1"), "enzo");
    publication.fire("daily", new EventPayload.Text("x=1"), "enzo");
    verify(relay, times(2)).publish(eq(topic), drafts.capture());
    var first = drafts.getAllValues().get(0);
    var second = drafts.getAllValues().get(1);
    // The same actor attributes both publications identically, and the digest is not the handle.
    assertEquals(first.publisher(), second.publisher());
    assertEquals(OURS, first.publisher());
    assertFalse(first.publisher().contains("enzo"));
    // Two presses of the same button are two events: a content-derived identity would collapse the
    // second into the first through broker deduplication and lose its firings.
    assertNotEquals(first.eventId(), second.eventId());
    assertEquals(
        new FiredEventCodec.Notice("daily", new EventPayload.Text("x=1")),
        FiredEventCodec.read(((RelayPayload.Text) first.payload()).text()));
  }

  /** A gap is acknowledged before the position moves, because a position cannot cross one. */
  @Test
  void an_expired_gap_is_acknowledged_before_the_cursor_moves() {
    serving(
        new Relay.Read(
            new Relay.Subscription(subscription, 0, AT),
            Optional.of(new Relay.Gap(0, 5)),
            List.of(at(6, OURS, notice("daily", new EventPayload.Empty())))));
    publication.fire("daily", new EventPayload.Empty(), "enzo");
    var order = inOrder(relay);
    order.verify(relay).acknowledgeGap(subscription, 5);
    order.verify(relay).advanceSeen(subscription, 6);
  }

  // ---------------------------------------------------------------------------------------------
  // Refusals before anything is published
  // ---------------------------------------------------------------------------------------------

  /** Every state an unusable event name can be in, each refused before the broker is touched. */
  @Test
  void an_unusable_event_name_is_refused_before_anything_is_published() {
    for (String name :
        new String[] {null, "", "   ", " padded", "padded ", "new\nline", "e".repeat(1025)}) {
      var broker = mock(Relay.class);
      var publisher =
          new RelayEventPublication(broker, arrivals, UnitOfWork.NONE, () -> {}, () -> AT);
      assertThrows(
          CallerFault.class,
          () -> publisher.fire(name, new EventPayload.Empty(), "enzo"),
          "accepted as an event name: " + name);
      verifyNoInteractions(broker);
    }
  }

  @Test
  void a_payload_outside_the_manual_families_is_refused_before_anything_is_published() {
    assertThrows(
        IllegalArgumentException.class,
        () -> publication.fire("daily", new EventPayload.Scheduled("tick", AT), "enzo"));
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void absent_data_is_the_empty_payload_rather_than_a_refusal() {
    serving(
        new Relay.Read(
            new Relay.Subscription(subscription, 0, AT),
            Optional.empty(),
            List.of(at(1, OURS, notice("daily", new EventPayload.Empty())))));
    publication.fire("daily", null, "enzo");
    verify(arrivals).recordFired("daily", new EventPayload.Empty(), AT);
  }

  /**
   * Dispatch starts jobs, so it must never run inside the publishing transaction. The boundary is
   * composition-supplied, and this asserts the publisher actually consults it.
   */
  @Test
  void a_publication_refuses_to_start_inside_a_caller_transaction() {
    var publisher =
        new RelayEventPublication(
            relay,
            arrivals,
            UnitOfWork.NONE,
            () -> {
              throw new IllegalStateException("a caller transaction is active");
            },
            () -> AT);
    assertThrows(
        IllegalStateException.class,
        () -> publisher.fire("daily", new EventPayload.Empty(), "enzo"));
    verify(relay, never()).publish(any(), any());
  }
}
