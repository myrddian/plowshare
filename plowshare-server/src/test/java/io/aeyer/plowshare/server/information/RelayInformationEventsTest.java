package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
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

  /**
   * The derived topic name for the one project this fixture publishes in, spelled out rather than
   * obtained from {@link InformationTopics}.
   *
   * <p>Calling the production derivation here would make every assertion below agree with whatever
   * the derivation currently returns, which is no assertion at all. Written as a literal, these
   * pins say what name the publisher and the reader must actually use, and a change to the recipe
   * fails them. {@code InformationTopicsTest} is where the literal itself is tied to the
   * derivation; the agreement with PostgreSQL is pinned in {@code
   * RelayInformationEventsDatabaseTest}.
   */
  private static final String TOPIC =
      "information.events.54d797420829941468803660fe5007bd0d8a59dacf07b04f3df76ae82f9b7e32";

  private static final String GROUP = "information.events";
  private static final UUID REVISION = UUID.randomUUID();

  private final InformationEventRecords records = mock(InformationEventRecords.class);
  private final Relay relay = mock(Relay.class);
  private final RelayConsumption consumption = mock(RelayConsumption.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final InformationEvents events =
      new RelayInformationEvents(records, relay, consumption, projects, () -> T0);

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
    verifyNoInteractions(consumption);
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

    events.publish(occurrence("private processing details"));

    // What reached the broker, asserted on the draft itself: the notice carries no detail, and the
    // publisher is this subsystem's own namespaced identity rather than the actor's handle.
    var draft = org.mockito.ArgumentCaptor.forClass(Relay.Draft.class);
    verify(relay).publish(eq(topic), draft.capture());
    String carried = ((RelayPayload.Text) draft.getValue().payload()).text();
    assertFalse(
        carried.contains("private processing details"),
        "owner-only detail must never enter a Relay publication");
    assertFalse(carried.contains("detail"), "the publication must not even name the field");
    assertEquals(issuer("author"), draft.getValue().publisher());
    assertFalse(draft.getValue().publisher().contains("author"), "the log must not name the actor");

    var order = inOrder(records, relay);
    order.verify(records).resolve(REVISION);
    order.verify(relay).registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
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
    verifyNoInteractions(consumption); // Publication never does per-event recipient-policy IO.
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
  void aThousandEventsRetainEveryDurablePositionWithoutAudienceQueriesOrSocketFanout() {
    when(records.resolve(REVISION))
        .thenReturn(
            new InformationEventRecords.Audience(
                new Relay.ProjectScope(7), "document", null, "author"));
    var position = new java.util.concurrent.atomic.AtomicLong();
    when(relay.publish(any(), any()))
        .thenAnswer(
            call ->
                new Relay.Publication(
                    call.getArgument(0), position.incrementAndGet(), T0, call.getArgument(1)));
    for (int i = 0; i < 1000; i++) events.publish(occurrence("private processing details"));
    verify(records, times(1000)).append(any());
    verify(relay, times(1000)).publish(any(), any());
    verifyNoInteractions(consumption);
    assertEquals(1000, position.get());
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
        RelayPayload.Kind.TEXT,
        Relay.Policy.systemDefault(),
        last,
        expired);
  }

  /** The server-owned publisher identity a genuine notice is issued under. */
  private static String issuer(String actor) {
    return "information:" + io.aeyer.plowshare.protocol.RelayPort.hash(actor);
  }

  private Relay.Publication publication(long position, String owner, String type) {
    return publication(position, owner, type, issuer(owner));
  }

  private Relay.Publication publication(
      long position, String owner, String type, String publisher) {
    return published(
        position,
        publisher,
        new RelayPayload.Text(
            InformationEventCodec.write(
                new InformationEventCodec.Notice(
                    REVISION.toString(), 3, "derive", "ready", type, "markdown", owner))));
  }

  private Relay.Publication published(long position, String publisher, RelayPayload payload) {
    return new Relay.Publication(
        new Relay.TopicKey(7, TOPIC),
        position,
        T0,
        new Relay.Draft("information:" + position, publisher, T0, null, null, payload));
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
    readable(2, 0, 0, publication(1, "author", "document"));
    // Detail lives in the revision's own history, not in the publication, and is read only for
    // the positions the caller has established are its own.
    when(records.details(new Relay.ProjectScope(7), java.util.Set.of(1L)))
        .thenReturn(java.util.Map.of(1L, "private processing details"));

    var others = events.read(context(InformationContext.Selection.project(PROJECT)), 0, 50);
    assertEquals(1, others.events().size());
    assertEquals("", others.events().getFirst().detail());
    // A non-owner's read asks the history for nothing at all; there is no detail to withhold.
    verify(records, never()).details(any(), any());

    var owner = new InformationContext("author", InformationContext.Selection.project(PROJECT));
    var mine = events.read(owner, 0, 50);
    assertEquals("private processing details", mine.events().getFirst().detail());
    verify(records).details(new Relay.ProjectScope(7), java.util.Set.of(1L));
  }

  /**
   * The structural barrier the payload family used to supply, restored on the consumer side.
   *
   * <p>While the topic declared a family of its own, an SDK publish could not land on it: the
   * transport always produces the general message, and the kinds disagreed. The topic now carries
   * that same general message, so the broker no longer answers "is this an information notice?" --
   * and it should not, because reserving a consumer's topic name is the broker policing a
   * consumer's vocabulary. The consumer answers it instead, by declining to trust a publisher it
   * did not issue under.
   */
  @Test
  void a_publication_this_subsystem_did_not_issue_is_never_interpreted() {
    var forged =
        publication(
            1, "author", "document", io.aeyer.plowshare.protocol.RelayPort.publisher("intruder"));
    readable(
        3, 0, 0, forged, publication(2, "author", "document"), publication(3, "x", "document"));

    var page = events.read(context(InformationContext.Selection.project(PROJECT)), 0, 50);

    assertEquals(
        List.of(2L, 3L),
        page.events().stream().map(Information.Event::sequence).toList(),
        "an SDK publisher's row must not be served as an information event");
    // The cursor still clears it. Refusing the page would let one foreign row wedge this
    // subscriber's feed for the topic's whole retention window.
    assertEquals(3, page.cursor());
  }

  @Test
  void a_forged_publication_is_rejected_before_anything_decodes_its_payload() {
    // Not a notice at all, and not even the general message. The publisher check runs first, so
    // nothing here is parsed, cast or interpreted -- it is simply not this subsystem's row.
    readable(
        1,
        0,
        0,
        published(
            1,
            io.aeyer.plowshare.protocol.RelayPort.publisher("intruder"),
            new RelayPayload.Lifecycle("job.ended", "job_fixture", "CANCELLED", null, null)));

    var page = events.read(context(InformationContext.Selection.project(PROJECT)), 0, 50);
    assertEquals(List.of(), page.events());
    assertEquals(1, page.cursor());
  }

  /**
   * A publication this subsystem <i>did</i> issue, carrying something it could not have written.
   * That is corruption of its own data rather than someone else's row, so it fails the page loudly
   * instead of being skipped.
   */
  @Test
  void our_own_publication_that_cannot_be_decoded_fails_the_page_rather_than_shortening_it() {
    readable(1, 0, 0, published(1, issuer("author"), new RelayPayload.Text("not a notice")));
    assertThrows(
        IllegalArgumentException.class,
        () -> events.read(context(InformationContext.Selection.project(PROJECT)), 0, 50));

    readable(2, 0, 0, published(2, issuer("author"), new RelayPayload.Empty()));
    assertThrows(
        IllegalStateException.class,
        () -> events.read(context(InformationContext.Selection.project(PROJECT)), 0, 50));
  }

  @Test
  void a_page_reports_only_the_selected_corpus_while_its_cursor_still_moves_past_the_rest() {
    readable(2, 0, 0, publication(1, "author", "code"), publication(2, "author", "document"));
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
