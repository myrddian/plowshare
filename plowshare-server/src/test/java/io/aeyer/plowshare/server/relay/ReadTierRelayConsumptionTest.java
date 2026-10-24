package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Decision 0038: a read-tier account gains subscription and consumption, and <b>nothing else</b>.
 *
 * <p>Every test here runs against one members stub — {@link #viewer} — whose only true answer is
 * {@code mayUse}. That is the whole point: the same account that the consumption tests admit is the
 * account the tier tests refuse, so a change that widened the read tier into the work or manage
 * tier cannot pass both halves. The tier tests are deliberately one per capability rather than one
 * loop, because the capability that gets widened by accident is the one a loop forgot to list.
 *
 * <p>Validity and configuration are mocked here; {@link RelayReadTierSubscriberDatabaseTest} pins
 * the same contract against real PostgreSQL, where the generation and age actually live.
 */
class ReadTierRelayConsumptionTest {
  private static final String VIEWER = "viewer";
  private static final String PROJECT = "atlas";
  private static final long PROJECT_ID = 9;
  private static final Duration WINDOW = Duration.ofHours(12);
  private static final Relay.TopicKey TOPIC = new Relay.TopicKey(PROJECT_ID, "release.observed");

  /** The stored group identity the server derives for the viewer from the logical name. */
  private static final Relay.SubscriptionKey KEY =
      Relay.SubscriptionKey.reader(TOPIC, VIEWER, "notices");

  private static final Duration LEASE = Duration.ofMinutes(2);
  private static final RelayConsumption.Request REQUEST =
      new RelayConsumption.Request(PROJECT, "release.observed", "notices");

  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final Relay relay = mock(Relay.class);
  private final RelayConsumerRepository consumers = mock(RelayConsumerRepository.class);
  private final RelaySubscriptionAuthority authority = mock(RelaySubscriptionAuthority.class);
  private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
  private final RelayConsumption consumption =
      new ReadTierRelayConsumption(
          members, projects, relay, consumers, authority, WINDOW, () -> now);

  /** A VIEWER: present in the project, so {@code mayUse} holds and nothing else does. */
  private void viewer() {
    when(members.mayUse(PROJECT, VIEWER)).thenReturn(true);
    when(members.mayWork(PROJECT, VIEWER)).thenReturn(false);
    when(members.mayManage(PROJECT, VIEWER)).thenReturn(false);
    when(projects.id(PROJECT)).thenReturn(PROJECT_ID);
    when(projects.personalOwner(PROJECT)).thenReturn(Optional.empty());
  }

  private void established() {
    when(authority.validate(KEY, VIEWER, WINDOW, now))
        .thenReturn(RelaySubscriptionAuthority.Validity.VALID);
  }

  private RelayConsumerRepository.Lease lease(String account) {
    return new RelayConsumerRepository.Lease(KEY, account, account, 3, now.plusSeconds(120));
  }

  private Relay.Subscription subscription(long seen) {
    return new Relay.Subscription(KEY, seen, now, UUID.randomUUID());
  }

  // ---------------------------------------------------------------- pin 1: the read tier admits

  @Test
  void a_viewer_subscribes_consumes_and_advances_its_own_offset() {
    viewer();
    when(relay.topic(KEY.topic()))
        .thenReturn(
            new Relay.Topic(
                KEY.topic(), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), 3, 0));
    when(relay.subscribeAsReader(KEY, Relay.Start.OLDEST_RETAINED, VIEWER))
        .thenReturn(Optional.of(subscription(0)));
    assertEquals(
        0, consumption.subscribe(VIEWER, REQUEST, Relay.Start.OLDEST_RETAINED).seenThrough());
    verify(authority).establish(KEY, VIEWER, now);
    // The work-tier registration path is never used, so no project-declared group can be adopted.
    verify(relay, never()).subscribe(any(), any());
    // Registration is never attempted: an absent topic is refused, not created.
    verify(relay, never()).registerTopic(any(), any(), any());
    verify(relay, never()).configureTopic(any(), any(), any());

    established();
    when(relay.read(KEY, 10))
        .thenReturn(new Relay.Read(subscription(0), Optional.empty(), List.of()));
    assertTrue(consumption.consume(VIEWER, REQUEST, 10).publications().isEmpty());

    var held = lease(VIEWER);
    when(consumers.advanceSeen(held, 2)).thenReturn(subscription(2));
    assertEquals(2, consumption.advance(VIEWER, REQUEST, held, 2).seenThrough());
  }

  @Test
  void the_stored_group_identity_is_derived_from_the_account_not_taken_from_the_request() {
    viewer();
    when(relay.topic(TOPIC))
        .thenReturn(
            new Relay.Topic(TOPIC, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), 3, 0));
    when(relay.subscribeAsReader(any(), any(), anyString()))
        .thenReturn(Optional.of(subscription(0)));
    consumption.subscribe(VIEWER, REQUEST, Relay.Start.OLDEST_RETAINED);
    // Asserted on the identity that reaches the broker, not on the one the request asked for: a
    // prefix that is merely intended is a prefix that is not applied.
    verify(relay)
        .subscribeAsReader(
            argThat(key -> key.subscriber().equals("reader:viewer:notices")),
            eq(Relay.Start.OLDEST_RETAINED),
            eq(VIEWER));
    assertNotEquals("notices", KEY.subscriber());
  }

  @Test
  void a_derived_group_name_still_fits_the_contract_that_reports_it_back() {
    // Log inspection maps stored group names into RelayLog.Subscriber, which bounds an identity at
    // 256. A derived name that exceeded it would be storable and then make the topic's log page
    // unreadable for every account on that topic, so the budget is enforced where the name is made.
    var longest = "a".repeat(247);
    var derived = Relay.SubscriptionKey.reader(TOPIC, longest, "n").subscriber();
    assertEquals(256, derived.length());
    assertDoesNotThrow(
        () -> new io.aeyer.plowshare.protocol.RelayLog.Subscriber(derived, "1", now, null));
    // One character more is refused at the boundary rather than discovered at the log page.
    assertThrows(
        IllegalArgumentException.class,
        () -> Relay.SubscriptionKey.reader(TOPIC, longest + "a", "n"));
    assertThrows(
        IllegalArgumentException.class, () -> Relay.SubscriptionKey.reader(TOPIC, longest, "no"));
    // A colon in an account would make the owner inside the name ambiguous.
    assertThrows(
        IllegalArgumentException.class, () -> Relay.SubscriptionKey.reader(TOPIC, "a:b", "n"));
  }

  @Test
  void the_same_logical_name_from_two_accounts_is_two_independent_groups() {
    var mine = Relay.SubscriptionKey.reader(TOPIC, VIEWER, "notices");
    var theirs = Relay.SubscriptionKey.reader(TOPIC, "contributor", "notices");
    assertNotEquals(mine, theirs);
    assertNotEquals(mine.subscriber(), theirs.subscriber());
    // And neither can ever equal a project-declared name, because that namespace has no colon.
    for (var key : List.of(mine, theirs)) {
      assertTrue(key.subscriber().startsWith("reader:"));
      assertThrows(
          IllegalArgumentException.class,
          () -> new Relay.SubscriptionKey(TOPIC, key.subscriber() + ":extra"));
    }
    assertEquals("notices", new Relay.SubscriptionKey(TOPIC, "notices").subscriber());
  }

  @Test
  void a_read_tier_subscriber_cannot_create_the_topic_it_subscribes_to() {
    viewer();
    when(relay.topic(KEY.topic()))
        .thenThrow(new IllegalStateException("Relay topic does not exist"));
    assertThrows(
        CallerFault.class, () -> consumption.subscribe(VIEWER, REQUEST, Relay.Start.LATEST));
    verify(relay, never()).registerTopic(any(), any(), any());
    verify(relay, never()).subscribeAsReader(any(), any(), anyString());
    verifyNoInteractions(authority);
  }

  // ------------------------------------------------------- pin 2: no role in the project refuses

  @Test
  void an_account_with_no_role_can_neither_subscribe_nor_consume_nor_advance() {
    when(projects.id(PROJECT)).thenReturn(PROJECT_ID);
    when(projects.personalOwner(PROJECT)).thenReturn(Optional.empty());
    when(members.mayUse(PROJECT, "stranger")).thenReturn(false);
    when(authority.validate(any(), eq("stranger"), any(), any()))
        .thenReturn(RelaySubscriptionAuthority.Validity.VALID);
    assertThrows(
        CallerFault.class, () -> consumption.subscribe("stranger", REQUEST, Relay.Start.LATEST));
    assertThrows(CallerFault.class, () -> consumption.consume("stranger", REQUEST, 1));
    assertThrows(
        CallerFault.class, () -> consumption.advance("stranger", REQUEST, lease("stranger"), 1));
    assertThrows(CallerFault.class, () -> consumption.lease("stranger", REQUEST, LEASE));
    verifyNoInteractions(relay, consumers);
  }

  @Test
  void a_personal_project_still_requires_its_exact_owner() {
    viewer();
    when(projects.personalOwner(PROJECT)).thenReturn(Optional.of("someone-else"));
    assertThrows(CallerFault.class, () -> consumption.consume(VIEWER, REQUEST, 1));
    verifyNoInteractions(relay);
  }

  // --------------------------------------- pin 3: another subscriber's consumer state is refused

  @Test
  void a_group_that_is_not_this_accounts_cannot_be_leased() {
    viewer();
    established();
    // Empty is how the repository reports "not this account's group, or leased by another owner".
    when(consumers.acquireAsReader(KEY, VIEWER, LEASE)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> consumption.lease(VIEWER, REQUEST, LEASE));
    // And the work-tier acquire is never reached, so the reader fence cannot be sidestepped.
    verify(consumers, never()).acquire(any(), any(), any(), any());
  }

  @Test
  void committing_an_offset_requires_a_live_lease_rather_than_an_absent_one() {
    viewer();
    established();
    var held = lease(VIEWER);
    // No live lease row: the repository fence throws, and that is a refusal, not a server fault.
    when(consumers.advanceSeen(held, 2))
        .thenThrow(new IllegalStateException("Relay consumer ownership is stale"));
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, REQUEST, held, 2));
    verify(relay, never()).advanceSeen(any(), anyLong());
  }

  @Test
  void a_lease_for_another_group_or_account_cannot_commit_this_groups_offset() {
    viewer();
    established();
    var foreign = new RelayConsumerRepository.Lease(KEY, "other", "other", 4, now.plusSeconds(60));
    var elsewhere =
        new RelayConsumerRepository.Lease(
            Relay.SubscriptionKey.reader(TOPIC, VIEWER, "other"),
            VIEWER,
            VIEWER,
            4,
            now.plusSeconds(60));
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, REQUEST, foreign, 2));
    assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, REQUEST, elsewhere, 2));
    verify(consumers, never()).advanceSeen(any(), anyLong());
  }

  @Test
  void a_lease_is_released_only_by_the_account_that_holds_it() {
    viewer();
    var foreign =
        new RelayConsumerRepository.Lease(
            KEY, "contributor", "contributor", 4, now.plusSeconds(60));
    assertThrows(CallerFault.class, () -> consumption.release(VIEWER, REQUEST, foreign));
    verify(consumers, never()).release(any());
  }

  // ------------------------------------------------- pins 5 and 6: validity, and where it is from

  @Test
  void an_invalid_subscription_refuses_every_operation_after_subscribe() {
    viewer();
    for (var invalid :
        List.of(
            RelaySubscriptionAuthority.Validity.NOT_ESTABLISHED,
            RelaySubscriptionAuthority.Validity.GENERATION_CHANGED,
            RelaySubscriptionAuthority.Validity.EXPIRED)) {
      when(authority.validate(KEY, VIEWER, WINDOW, now)).thenReturn(invalid);
      assertThrows(CallerFault.class, () -> consumption.consume(VIEWER, REQUEST, 1));
      assertThrows(CallerFault.class, () -> consumption.advance(VIEWER, REQUEST, lease(VIEWER), 1));
      assertThrows(CallerFault.class, () -> consumption.lease(VIEWER, REQUEST, LEASE));
    }
    verify(relay, never()).read(any(), anyInt());
    verifyNoInteractions(consumers);
  }

  @Test
  void the_configured_window_is_the_one_validity_is_asked_about() {
    viewer();
    var other = Duration.ofMinutes(5);
    var configured =
        new ReadTierRelayConsumption(
            members, projects, relay, consumers, authority, other, () -> now);
    when(authority.validate(KEY, VIEWER, other, now))
        .thenReturn(RelaySubscriptionAuthority.Validity.VALID);
    when(relay.read(KEY, 1))
        .thenReturn(new Relay.Read(subscription(0), Optional.empty(), List.of()));
    configured.consume(VIEWER, REQUEST, 1);
    verify(authority).validate(KEY, VIEWER, other, now);
    verify(authority, never()).validate(KEY, VIEWER, WINDOW, now);
  }

  @Test
  void a_missing_or_unusable_maximum_age_is_refused_rather_than_defaulted() {
    for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ReadTierRelayConsumption(
                  members, projects, relay, consumers, authority, invalid, () -> now));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReadTierRelayConsumption(
                members, projects, relay, consumers, authority, null, () -> now));
    var properties = new RelayProperties();
    assertThrows(IllegalStateException.class, properties::subscriptionMaximumAge);
    properties.setSubscriptionMaximumAge(Duration.ofHours(12));
    assertEquals(Duration.ofHours(12), properties.subscriptionMaximumAge());
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setSubscriptionMaximumAge(Duration.ofSeconds(30)));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setSubscriptionMaximumAge(Duration.ofDays(31)));
  }

  // --------------------------- pin 4: work and manage tiers are unchanged, one capability each

  @Test
  void a_viewer_still_cannot_publish_through_a_port() {
    viewer();
    var ports =
        new ProjectRelayPorts(
            relay, mock(RelayPortRepository.class), portBindings(), members, projects, 8);
    assertThrows(
        CallerFault.class,
        () ->
            ports.publish(
                VIEWER,
                new RelayPort.Publish(
                    UUID.randomUUID().toString(),
                    PROJECT,
                    "release.observed",
                    "text",
                    Instant.EPOCH,
                    null,
                    null,
                    null)));
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void a_viewer_still_cannot_consume_or_acknowledge_through_a_port() {
    viewer();
    var ports =
        new ProjectRelayPorts(
            relay, mock(RelayPortRepository.class), portBindings(), members, projects, 8);
    assertThrows(
        CallerFault.class,
        () ->
            ports.consume(
                VIEWER,
                new RelayPort.Consume(
                    PROJECT,
                    "release.observed",
                    "notices",
                    UUID.randomUUID().toString(),
                    RelayPort.Start.OLDEST_RETAINED,
                    1,
                    null)));
    assertThrows(
        CallerFault.class,
        () ->
            ports.acknowledge(
                VIEWER,
                new RelayPort.Ack(
                    PROJECT,
                    "release.observed",
                    "notices",
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    "1",
                    null)));
    verifyNoInteractions(relay);
  }

  @Test
  void a_viewer_still_cannot_run_the_processing_pipeline() {
    viewer();
    var processing =
        new ProjectRelayProcessing(projects, members, mock(RelaySubscriptionWork.class));
    assertThrows(
        CallerFault.class, () -> processing.process(VIEWER, new RelayLog.Process(PROJECT, 1)));
  }

  @Test
  void a_viewer_still_cannot_register_or_configure_a_topic_through_subscription_work() {
    viewer();
    var work =
        new OwnedRelaySubscriptionWork(
            mock(RelayProjectFiles.class),
            members,
            mock(RelayRouting.class),
            relay,
            mock(RelayDispatch.class),
            consumers);
    var access = new RelayProjectFiles.Access(VIEWER, PROJECT, PROJECT_ID);
    assertThrows(CallerFault.class, () -> work.subscriptions(access));
    assertThrows(CallerFault.class, () -> work.process(access, KEY, "worker", 1, 1));
    verify(relay, never()).registerTopic(any(), any(), any());
    verify(relay, never()).configureTopic(any(), any(), any());
  }

  @Test
  void a_viewer_still_cannot_receive_dispatch() {
    viewer();
    var dispatch =
        new ReceiverRelayDispatch(
            mock(RelayDeliveries.class),
            new RegisteredRelayReceivers(List.of()),
            members,
            projects,
            mock(RelayDispatch.Boundary.class));
    var access = new RelayProjectFiles.Access(VIEWER, PROJECT, PROJECT_ID);
    assertThrows(
        CallerFault.class, () -> dispatch.next(access, KEY, "worker", Duration.ofMinutes(2)));
  }

  @Test
  void a_viewer_still_cannot_use_the_operator_controls() {
    viewer();
    var operations =
        new ProjectRelayOperations(
            members,
            projects,
            mock(RelayRouting.class),
            mock(RelayDispatch.class),
            mock(RelayOperationRepository.class),
            mock(RelayPublicationSignals.class),
            mock(UnitOfWork.class));
    assertThrows(
        CallerFault.class,
        () ->
            operations.operate(
                VIEWER,
                new io.aeyer.plowshare.protocol.RelayControl.Request(
                    UUID.randomUUID().toString(),
                    PROJECT,
                    "release.observed",
                    UUID.randomUUID().toString(),
                    io.aeyer.plowshare.protocol.RelayControl.Action.REMOVE_TOPIC,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "fixture")));
  }

  private RelayPortProperties portBindings() {
    var properties = new RelayPortProperties();
    properties.setBindings(
        List.of(
            new RelayPortProperties.Binding(
                PROJECT,
                "release.observed",
                VIEWER,
                RelayPortProperties.Direction.INGRESS,
                List.of()),
            new RelayPortProperties.Binding(
                PROJECT,
                "release.observed",
                VIEWER,
                RelayPortProperties.Direction.EGRESS,
                List.of("notices"))));
    return properties;
  }
}
