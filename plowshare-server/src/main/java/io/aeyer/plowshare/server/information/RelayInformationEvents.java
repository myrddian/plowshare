package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.AccountEvent;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumption;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Information events as a wrapper over Relay (decision 0037): one append per event to the topic its
 * home scope maps to, and read-back through the calling account's own read-tier subscription
 * (decision 0038).
 *
 * <p>There is no second log. There is no installation-wide ordering fence — Relay's lock on one
 * topic row in one project is the ordering mechanism. There is no recipient table and no permission
 * predicate evaluated per candidate account — a topic's audience is the accounts that may read the
 * home scope, and subscription is what admits them.
 *
 * <p><b>No durable subscriber inbox is kept.</b> The schedules migration kept firing records
 * because a schedule's copied input is work that must survive topic expiration; an information
 * event is an invalidation notice whose authoritative state is the catalogue itself, which the
 * reader already re-reads. A reader that was away longer than the topic's retention acknowledges
 * the loss and re-reads the catalogue, so there is nothing per-subscriber an inbox would preserve.
 *
 * <p>What is kept is the revision's own history, which is not an inbox and not per subscriber:
 * {@code information.status} shows an owner what happened to their document, and that has to
 * outlive a four-day delivery retention and be answerable by revision rather than by topic
 * position. See {@link InformationEventRecords#append}.
 */
public final class RelayInformationEvents implements InformationEvents {
  /** Bounded: a page write is a few statements, and a wedged lease must not outlive a request. */
  private static final Duration LEASE = Duration.ofSeconds(30);

  /**
   * The logical consumer group every account reading this family uses. {@code
   * RelayValues.readerSubscriber} prefixes it with the authenticated account server-side, so two
   * accounts using this one name are two groups with independent committed offsets, and neither can
   * name the project's work-tier pipeline group.
   */
  private static final String GROUP = "information.events";

  private final InformationEventRecords records;
  private final Relay relay;
  private final RelayConsumption consumption;
  private final ProjectWorkspaces projects;
  private final UnitOfWork transactions;
  private final AccountPushes pushes;
  private final Supplier<Instant> clock;

  public RelayInformationEvents(
      InformationEventRecords records,
      Relay relay,
      RelayConsumption consumption,
      ProjectWorkspaces projects,
      UnitOfWork transactions,
      AccountPushes pushes,
      Supplier<Instant> clock) {
    this.records = Objects.requireNonNull(records, "records");
    this.relay = Objects.requireNonNull(relay, "relay");
    this.consumption = Objects.requireNonNull(consumption, "consumption");
    this.projects = Objects.requireNonNull(projects, "projects");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.pushes = Objects.requireNonNull(pushes, "pushes");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void publish(Occurrence event) {
    Objects.requireNonNull(event, "event");
    var resolved = records.resolve(event.revision());
    var topic = new Relay.TopicKey(resolved.scope(), Relay.INFORMATION_CHANGED);
    relay.registerTopic(topic, RelayPayload.Kind.INFORMATION_CHANGED, Relay.Policy.systemDefault());
    var payload =
        new RelayPayload.InformationChanged(
            event.revision().toString(),
            event.generation(),
            event.stage(),
            event.action(),
            event.detail(),
            resolved.documentType(),
            resolved.documentSubtype(),
            resolved.owner());
    // A fresh identity per event, deliberately. Broker deduplication keys on the event ID, and the
    // catalogue legitimately records the same revision, stage and action more than once — two
    // retries of one stage are two events — so a content-derived identity would collapse them into
    // the first publication and lose the second.
    var occurredAt = clock.get();
    var draft =
        new Relay.Draft(
            "information:" + UUID.randomUUID(), event.actor(), occurredAt, null, null, payload);
    var publication = relay.publish(topic, draft);
    // The revision's own history, at the position Relay just allocated, in this same transaction.
    // It is not a second log: it has no sequence of its own and nothing reads it by cursor. See
    // InformationEventRecords.append for why it is kept.
    records.append(
        new InformationEventRecords.Entry(
            event.revision(),
            resolved.scope(),
            publication.position(),
            event.generation(),
            event.actor(),
            event.stage(),
            event.action(),
            event.detail(),
            publication.event().occurredAt()));
    // The audience is resolved after commit, not captured during it, so a membership change
    // committing in between withdraws the hint rather than being raced past by it.
    transactions.afterCommit(() -> hint(resolved.scope(), event, publication.position()));
  }

  /**
   * Wakes the topic's current audience once the publication is committed.
   *
   * <p>This is the only fan-out left and it is a hint, not delivery: the audience comes from Relay
   * in one set-based read, nothing is persisted per recipient, and a lost hint costs a reader only
   * latency because its durable position still holds the event. {@link AccountPushes} never throws,
   * and this runs after commit so it can neither roll back nor delay the mutation.
   *
   * <p>The audience, not the subscriber list. A hint names a revision, its generation and a stream
   * position, so sending one is a disclosure and is governed by the same invariant delivery is: no
   * account may receive an information event for a scope it may not read. A {@code
   * relay_subscriptions} row outlives the withdrawal of its authority — revocation removes the
   * authority record and leaves the subscription — so hinting to every subscriber would keep
   * telling a revoked account which document changed and when, while the consume path refused it.
   * {@link RelayConsumption#audience} answers with the accounts whose authority is established and
   * still valid, by the one definition the consume gate also uses.
   */
  private void hint(Relay.Scope scope, Occurrence event, long position) {
    var body =
        new AccountEvent.InformationChanged(
            position, event.revision(), Math.toIntExact(event.generation()));
    for (String account : consumption.audience(scope, Relay.INFORMATION_CHANGED))
      pushes.push(account, body);
  }

  @Override
  public Information.Events read(InformationContext context, long after, int limit) {
    Objects.requireNonNull(context, "context");
    if (after < 0 || limit < 1 || limit > 100)
      throw new CallerFault("event cursor must be nonnegative and limit between 1 and 100");
    String project = project(context);
    Long id = projects.id(project);
    if (id == null) return new Information.Events(List.of(), after);
    var key = new Relay.TopicKey(new Relay.ProjectScope(id), Relay.INFORMATION_CHANGED);

    Relay.Topic topic;
    try {
      topic = relay.topic(key);
    } catch (IllegalStateException | IllegalArgumentException absent) {
      // Nothing has ever been published in this project, so there is no audience question to
      // answer and nothing any account could receive. The previous reader produced the same empty
      // page here, through a readability filter that matched no rows.
      return new Information.Events(List.of(), after);
    }

    String account = context.account();
    var request = new RelayConsumption.Request(project, Relay.INFORMATION_CHANGED, GROUP);
    // Establishes — or re-establishes, which is how a lapsed subscription renews — this account's
    // read authority over the topic. A refusal here is the invariant doing its job and propagates.
    var subscription = consumption.subscribe(account, request, Relay.Start.OLDEST_RETAINED);
    commit(account, request, subscription, topic, after);

    var page = consumption.consume(account, request, limit);
    var events = new ArrayList<Information.Event>(page.publications().size());
    long cursor = Math.max(after, page.subscription().seenThrough());
    for (var publication : page.publications()) {
      cursor = publication.position();
      if (publication.event().payload() instanceof RelayPayload.InformationChanged changed
          && changed.documentType().equals(context.corpus().documentType()))
        events.add(event(publication, changed, account));
    }
    return new Information.Events(events, cursor);
  }

  /**
   * Moves this subscription's own durable position, under a lease, before the page is read.
   *
   * <p>Both writes are conditional, so a steady paging read that has nothing to commit takes no
   * lease at all. Retention loss is acknowledged first because a position cannot move across an
   * unacknowledged gap; the boundary comes from the topic snapshot already read rather than from a
   * second page read, so there is no fetch-discard-refetch here.
   */
  private void commit(
      String account,
      RelayConsumption.Request request,
      Relay.Subscription subscription,
      Relay.Topic topic,
      long after) {
    long seen = subscription.seenThrough();
    boolean expired = topic.expiredThrough() > seen;
    long target = Math.min(after, topic.lastPosition());
    boolean advancing = target > seen && target > topic.expiredThrough();
    if (!expired && !advancing) return;
    try {
      var lease = consumption.lease(account, request, LEASE);
      try {
        if (expired) consumption.acknowledgeGap(account, request, lease, topic.expiredThrough());
        if (advancing) consumption.advance(account, request, lease, target);
      } finally {
        consumption.release(account, request, lease);
      }
    } catch (CallerFault contended) {
      // The only refusal reachable here is contention on this account's own lease: its authority
      // was just established, and the subscription and lease are its own by construction. Another
      // of this account's own consumers holds or has just renewed the lease, and will commit the
      // same progress, so serving the page from the position that is durable now is correct. The
      // position never moves backwards, so nothing is lost by not writing it here.
      org.slf4j.LoggerFactory.getLogger(RelayInformationEvents.class)
          .debug("information event position left to a concurrent consumer of the same account");
    }
  }

  /** The project whose topic this selection reads, or an actionable refusal when it has none. */
  private static String project(InformationContext context) {
    if (context.selection().scope() == InformationContext.Scope.PROJECT)
      return context.selection().project();
    // PERSONAL never arrives here: InformationAccess.resolve aliases it to the personal project,
    // which is a project like any other. GLOBAL_READ and ACCOUNT are homed outside every project,
    // and Relay has no audience that can express them, so this refuses rather than guessing one.
    throw new CallerFault(
        "information events have no Relay audience for a "
            + context.selection().scope()
            + " selection; select the project that homes them");
  }

  /**
   * One publication as the operation's reply shape. {@code detail} is withheld from every account
   * but the home resource's owner, which is the redaction the previous reader applied in SQL; the
   * owner travels in the payload so reapplying it costs no query.
   */
  private static Information.Event event(
      Relay.Publication publication, RelayPayload.InformationChanged changed, String account) {
    boolean owner = changed.owner().equals(account);
    return new Information.Event(
        publication.position(),
        UUID.fromString(changed.revision()),
        changed.generation(),
        changed.stage(),
        changed.action(),
        owner && changed.detail() != null ? changed.detail() : "",
        publication.event().occurredAt(),
        changed.documentType(),
        changed.documentSubtype());
  }
}
