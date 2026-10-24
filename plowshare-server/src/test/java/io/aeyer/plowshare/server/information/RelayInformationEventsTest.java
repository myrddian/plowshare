package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import io.aeyer.plowshare.server.relay.RelayConsumption;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The parts of the information-event cutover that are decided in Java rather than by PostgreSQL:
 * which selections have a Relay audience, what a page write does and does not take a lease for, and
 * the owner redaction reapplied when a page is read.
 *
 * <p>Everything about <i>who may receive</i> an event is decided by real rows and is pinned against
 * real PostgreSQL in {@code RelayInformationEventsDatabaseTest}. A mocked {@code RelayConsumption}
 * here would answer the authority question with whatever it was told to answer, which is no answer.
 */
class RelayInformationEventsTest {
  private static final Instant T0 = Instant.parse("2026-10-24T09:00:00Z");
  private static final String PROJECT = "atlas";
  private static final String TOPIC = "information.changed";
  private static final String GROUP = "information.events";
  private static final UUID REVISION = UUID.randomUUID();

  private final InformationEventRecords records = mock(InformationEventRecords.class);
  private final Relay relay = mock(Relay.class);
  private final RelayConsumption consumption = mock(RelayConsumption.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final List<String> hinted = new ArrayList<>();

  /** Runs after-commit work immediately, which is what a committed transaction does. */
  private final UnitOfWork work =
      new UnitOfWork() {
        @Override
        public <T> T inTransaction(java.util.function.Supplier<T> action) {
          return action.get();
        }
      };

  private final AccountPushes pushes = (handle, body) -> hinted.add(handle);

  private final InformationEvents events =
      new RelayInformationEvents(records, relay, consumption, projects, work, pushes, () -> T0);

  private InformationContext context(InformationContext.Selection selection) {
    return new InformationContext("reader", selection).withView(true);
  }

  private InformationEvents.Occurrence occurrence(String detail) {
    return new InformationEvents.Occurrence(REVISION, 3, "author", "derive", "ready", detail);
  }

  @Test
  void a_home_scope_with_no_relay_audience_fails_loudly_and_writes_nothing() {
    when(records.resolve(REVISION))
        .thenThrow(new InformationEventRecords.Unaddressable("GLOBAL_KNOWLEDGE"));

    var refused =
        assertThrows(
            InformationEventRecords.Unaddressable.class, () -> events.publish(occurrence("")));
    assertTrue(refused.getMessage().contains("GLOBAL_KNOWLEDGE"));
    // No topic brought into being, no publication, no history row, no hint. A refusal that
    // published to some other audience first would be worse than the defect it reports.
    verify(relay, never()).registerTopic(any(), any(), any());
    verify(relay, never()).publish(any(), any());
    verify(records, never()).append(any());
    assertTrue(hinted.isEmpty());
  }

  @Test
  void publication_registers_the_family_appends_once_and_records_relays_own_position() {
    when(records.resolve(REVISION))
        .thenReturn(
            new InformationEventRecords.Audience(
                new Relay.ProjectScope(7), "document", "markdown", "author"));
    var topic = new Relay.TopicKey(7, TOPIC);
    when(relay.publish(eq(topic), any()))
        .thenAnswer(
            call -> new Relay.Publication(topic, 42, T0, call.getArgument(1, Relay.Draft.class)));
    when(relay.readers(topic)).thenReturn(List.of("reader", "author"));

    events.publish(occurrence("private processing details"));

    var order = inOrder(records, relay);
    order.verify(records).resolve(REVISION);
    order
        .verify(relay)
        .registerTopic(topic, RelayPayload.Kind.INFORMATION_CHANGED, Relay.Policy.systemDefault());
    order.verify(relay).publish(eq(topic), any());
    verify(records)
        .append(
            new InformationEventRecords.Entry(
                REVISION,
                new Relay.ProjectScope(7),
                42,
                3,
                "author",
                "derive",
                "ready",
                "private processing details",
                T0));
    // Recipients come from the topic's subscriptions, in one read, and only as a wake hint.
    assertEquals(List.of("reader", "author"), hinted);
  }

  @Test
  void two_events_on_one_revision_are_two_publications_rather_than_one_deduplicated_identity() {
    when(records.resolve(REVISION))
        .thenReturn(
            new InformationEventRecords.Audience(
                new Relay.ProjectScope(7), "document", null, "author"));
    var topic = new Relay.TopicKey(7, TOPIC);
    var identities = new ArrayList<String>();
    when(relay.publish(eq(topic), any()))
        .thenAnswer(
            call -> {
              var draft = call.getArgument(1, Relay.Draft.class);
              identities.add(draft.eventId());
              return new Relay.Publication(topic, identities.size(), T0, draft);
            });

    events.publish(occurrence(""));
    events.publish(occurrence(""));

    assertEquals(2, identities.size());
    assertNotEquals(
        identities.get(0),
        identities.get(1),
        "a content-derived identity would collapse two retries of one stage into one publication");
  }

  @Test
  void a_selection_with_no_project_is_refused_rather_than_served_from_another_audience() {
    for (var selection :
        List.of(
            InformationContext.Selection.globalRead(),
            InformationContext.Selection.account(
                io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value()))) {
      var refused = assertThrows(CallerFault.class, () -> events.read(context(selection), 0, 50));
      assertTrue(refused.getMessage().contains("no Relay audience"));
    }
    verifyNoInteractions(consumption);
  }

  @Test
  void an_invalid_page_request_is_refused_before_any_subscription_is_established() {
    for (int[] page : new int[][] {{-1, 50}, {0, 0}, {0, 101}})
      assertThrows(
          CallerFault.class,
          () ->
              events.read(
                  context(InformationContext.Selection.project(PROJECT)), page[0], page[1]));
    verifyNoInteractions(consumption);
  }

  @Test
  void an_unknown_project_or_an_unpublished_topic_is_an_empty_page_and_not_a_subscription() {
    when(projects.id(PROJECT)).thenReturn(null);
    var absent = events.read(context(InformationContext.Selection.project(PROJECT)), 4, 50);
    assertEquals(List.of(), absent.events());
    assertEquals(4, absent.cursor());

    when(projects.id(PROJECT)).thenReturn(7L);
    when(relay.topic(new Relay.TopicKey(7, TOPIC)))
        .thenThrow(new IllegalStateException("no such topic"));
    var unpublished = events.read(context(InformationContext.Selection.project(PROJECT)), 4, 50);
    assertEquals(List.of(), unpublished.events());
    assertEquals(4, unpublished.cursor());
    verifyNoInteractions(consumption);
  }

  private Relay.Topic topic(long last, long expired) {
    return new Relay.Topic(
        new Relay.TopicKey(7, TOPIC),
        RelayPayload.Kind.INFORMATION_CHANGED,
        Relay.Policy.systemDefault(),
        last,
        expired);
  }

  private Relay.Publication publication(long position, String owner, String type, String detail) {
    return new Relay.Publication(
        new Relay.TopicKey(7, TOPIC),
        position,
        T0,
        new Relay.Draft(
            "information:" + position,
            owner,
            T0,
            null,
            null,
            new RelayPayload.InformationChanged(
                REVISION.toString(), 3, "derive", "ready", detail, type, "markdown", owner)));
  }

  private RelayConsumerRepository.Lease lease() {
    return new RelayConsumerRepository.Lease(
        Relay.SubscriptionKey.reader(new Relay.TopicKey(7, TOPIC), "reader", GROUP),
        "reader",
        "reader",
        1,
        T0.plusSeconds(30));
  }

  private Relay.Subscription subscription(long seen) {
    return new Relay.Subscription(
        Relay.SubscriptionKey.reader(new Relay.TopicKey(7, TOPIC), "reader", GROUP), seen, T0);
  }

  private void readable(long last, long expired, long seen, Relay.Publication... page) {
    when(projects.id(PROJECT)).thenReturn(7L);
    when(relay.topic(new Relay.TopicKey(7, TOPIC))).thenReturn(topic(last, expired));
    when(consumption.subscribe(any(), any(), eq(Relay.Start.OLDEST_RETAINED)))
        .thenReturn(subscription(seen));
    when(consumption.lease(any(), any(), any(Duration.class))).thenReturn(lease());
    when(consumption.consume(any(), any(), anyInt()))
        .thenReturn(new Relay.Read(subscription(seen), Optional.empty(), List.of(page)));
  }

  @Test
  void a_reader_consumes_its_own_logical_group_and_the_selected_projects_topic() {
    readable(5, 0, 5);
    events.read(context(InformationContext.Selection.project(PROJECT)), 0, 50);
    verify(consumption)
        .subscribe(
            "reader",
            new RelayConsumption.Request(PROJECT, TOPIC, GROUP),
            Relay.Start.OLDEST_RETAINED);
  }

  @Test
  void detail_reaches_the_home_resources_owner_and_no_other_subscriber() {
    readable(2, 0, 0, publication(1, "author", "document", "private processing details"));
    var others = events.read(context(InformationContext.Selection.project(PROJECT)), 0, 50);
    assertEquals(1, others.events().size());
    assertEquals("", others.events().getFirst().detail());

    var owner = new InformationContext("author", InformationContext.Selection.project(PROJECT));
    var mine = events.read(owner, 0, 50);
    assertEquals("private processing details", mine.events().getFirst().detail());
  }

  @Test
  void a_page_reports_only_the_selected_corpus_while_its_cursor_still_moves_past_the_rest() {
    readable(
        2,
        0,
        0,
        publication(1, "author", "code", null),
        publication(2, "author", "document", null));
    var page = events.read(context(InformationContext.Selection.project(PROJECT)), 0, 50);
    assertEquals(List.of(2L), page.events().stream().map(event -> event.sequence()).toList());
    assertEquals(2, page.cursor(), "a filtered page must still let the caller make progress");
  }

  @Test
  void an_acknowledged_cursor_commits_under_a_lease_that_is_always_released() {
    readable(9, 0, 3);
    events.read(context(InformationContext.Selection.project(PROJECT)), 6, 50);
    InOrder order = inOrder(consumption);
    order.verify(consumption).subscribe(eq("reader"), any(), any());
    order.verify(consumption).lease(eq("reader"), any(), any(Duration.class));
    order.verify(consumption).advance(eq("reader"), any(), eq(lease()), eq(6L));
    order.verify(consumption).release(eq("reader"), any(), eq(lease()));
    order.verify(consumption).consume(eq("reader"), any(), eq(50));
  }

  @Test
  void a_cursor_that_is_behind_or_beyond_the_log_takes_no_lease_and_never_moves_backwards() {
    readable(9, 0, 7);
    // Behind the committed position: Relay positions are monotonic, so this is not a seek.
    events.read(context(InformationContext.Selection.project(PROJECT)), 2, 50);
    verify(consumption, never()).lease(any(), any(), any(Duration.class));
    verify(consumption, never()).advance(any(), any(), any(), anyLong());

    // Beyond the last published position: clamped rather than refused or written.
    events.read(context(InformationContext.Selection.project(PROJECT)), 99, 50);
    verify(consumption).advance(eq("reader"), any(), eq(lease()), eq(9L));
  }

  @Test
  void retention_loss_is_acknowledged_before_a_position_is_moved_across_it() {
    readable(9, 4, 0);
    events.read(context(InformationContext.Selection.project(PROJECT)), 6, 50);
    InOrder order = inOrder(consumption);
    order.verify(consumption).lease(eq("reader"), any(), any(Duration.class));
    order.verify(consumption).acknowledgeGap(eq("reader"), any(), eq(lease()), eq(4L));
    order.verify(consumption).advance(eq("reader"), any(), eq(lease()), eq(6L));
    order.verify(consumption).release(eq("reader"), any(), eq(lease()));
  }

  @Test
  void contention_on_this_accounts_own_lease_still_serves_the_page_it_can_see() {
    readable(9, 0, 3);
    when(consumption.lease(eq("reader"), any(), any(Duration.class)))
        .thenThrow(new CallerFault("leased by another consumer"));
    var page = events.read(context(InformationContext.Selection.project(PROJECT)), 6, 50);
    assertEquals(6, page.cursor());
    verify(consumption).consume(eq("reader"), any(), eq(50));
  }
}
