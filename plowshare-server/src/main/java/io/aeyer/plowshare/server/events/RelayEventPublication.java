package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code event.fire} as a wrapper over Relay (decision 0037): one append per raised event, and the
 * existing firing inbox as the subscriber that copies it.
 *
 * <p>Existing firing records are the subscriber's durable inbox, so copied input survives topic
 * expiration without a second queue — the reasoning {@code RelayScheduledPublication} established
 * and the reason this migration needs no new table. Append, fan-out and the subscriber's
 * acknowledgement share one transaction; dispatch happens after it commits, because dispatch starts
 * jobs.
 *
 * <p><b>This is consolidation, not a performance fix, and must not be argued as one.</b> {@code
 * event.fire} had none of the pathologies that made the information-event migration urgent: no
 * installation-wide advisory lock, no per-account recipient fan-out, and no delivery cursor. The
 * {@code firings} scan in {@code JdbcFiringStore.preflight} is a read-only compatibility inventory,
 * not a delivery position. What this change buys is one delivery substrate instead of two.
 *
 * <h2>The authority unit, and why it is the server scope</h2>
 *
 * <p>A fired event is scoped to the installation, not to a project, and the system says so in its
 * own authorization code rather than by omission. {@code ProjectAuthorization} lists {@code
 * event.fire} in its {@code SERVER} set beside {@code retention.sweep} and {@code schedule.define},
 * and that branch calls {@code requireServerAdmin} and returns <em>without resolving a
 * project</em>; {@code FrameRouter} runs that check on every frame. Three further facts agree with
 * it: the frame's body is {@code (event, data)} and carries no project, and {@code Asking}
 * deliberately holds none because a socket is opened per session; {@code
 * JdbcTriggerStore.listening} selects {@code WHERE event = ? AND NOT paused} with no project or
 * account filter, so one raised event fans out across every project on the installation; and {@code
 * firings} has no project column at all, while {@code triggers.project} is nullable with {@code
 * triggers_one_home} permitting a trigger that names neither a project nor a conversation. An event
 * matching nothing records an {@code unmatched} firing attached to no trigger, and so to no project
 * by any route.
 *
 * <p>So the audience is the server administrators. {@link Relay.SystemScope#SERVER} is the scope
 * that names them, and choosing it widens nothing: {@code ScopedRelayInspection.require} gates
 * system-scope inspection on {@code isServerAdmin}, which is the same population {@code
 * requireServerAdmin} admits to the operation, so the publication is readable by exactly the
 * accounts that could already raise it. Decision 0039's prohibition on reusing this scope is
 * directed at a different case — admitting non-administrators to it in order to reach a
 * non-administrator audience, which would expose the internal wake topics it carries — and the
 * narrowing is recorded there. The shipped precedent is in this same subsystem and for this same
 * table: {@code JdbcScheduleStore.claimPublication} returns the server scope for a projectless
 * schedule, and {@code ScheduleStore} states it outright.
 *
 * <p>The consequence to keep in view: should a project-scoped ingress for fired events ever exist,
 * it must derive its own scope rather than inherit this one. {@link EventTopics} is already total
 * over both scope cases so that such an ingress extends the derivation instead of rewriting it.
 */
public final class RelayEventPublication implements EventPublication {

  /**
   * The only scope a fired event resolves to. See the class comment for the authority finding; it
   * is a constant here rather than a parameter because nothing on the ingress path carries a scope
   * to resolve, and a parameter would invite a caller to supply one the authorization never
   * checked.
   */
  private static final Relay.Scope SCOPE = Relay.SystemScope.SERVER;

  /**
   * The publisher namespace every fired event is issued under, following the convention {@code
   * RelayMessageReview} established with {@code filter:} and {@code RelayInformationEvents} with
   * {@code information:}: a server-owned prefix and the actor's hash rather than the handle, so the
   * publication attributes itself without naming an account to everyone who can inspect the log.
   *
   * <p>It is also what the read path trusts. Relay is content-agnostic by design, so it does not
   * police who may publish under a topic name on this subsystem's behalf, and the registered
   * payload family is the general message every publisher produces. The structural question "did
   * this subsystem issue this?" therefore belongs here: a publication it did not issue is never
   * decoded, so a forged payload never reaches {@link FiredEventCodec}.
   */
  private static final String PUBLISHER = "event:";

  /**
   * The consumer group the firing inbox reads under. A work-tier, project-declared name — this is
   * the server's own compatibility subscriber, not a read-tier reader's per-account group — and it
   * matches {@code builtin.scheduling}, the name the schedules migration gave the same role.
   */
  private static final String SUBSCRIBER = "builtin.events";

  /**
   * How many publications one call will copy. A fired event is published and consumed inside one
   * transaction, so the steady state is one; the batch exists so that a publication left unconsumed
   * by an earlier failure, or a foreign row that has to be skipped, is drained rather than
   * permanently blocking the ones behind it. Bounded because {@code Relay.read} admits 1..1,000.
   */
  private static final int BATCH = 100;

  private final Relay relay;
  private final FiredArrival arrivals;
  private final UnitOfWork transactions;
  private final Boundary boundary;
  private final Supplier<Instant> clock;

  public RelayEventPublication(
      Relay relay,
      FiredArrival arrivals,
      UnitOfWork transactions,
      Boundary boundary,
      Supplier<Instant> clock) {
    this.relay = Objects.requireNonNull(relay, "relay");
    this.arrivals = Objects.requireNonNull(arrivals, "arrivals");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.boundary = Objects.requireNonNull(boundary, "boundary");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public List<FiringRecord> fire(String event, EventPayload data, String actor) {
    // Validation first, and outside the transaction: an event whose name or data the family refuses
    // must leave no publication behind, and these are the refusals event.fire has always given.
    String named = require(event);
    EventPayload carried = data == null ? new EventPayload.Empty() : data;
    EventPayloadCodec.require(named, null, null, null, carried);
    String by = EventPayload.identity(actor, "actor");
    boundary.requireOutsideTransaction();

    var topic = new Relay.TopicKey(SCOPE, EventTopics.of(SCOPE));
    var subscription = new Relay.SubscriptionKey(topic, SUBSCRIBER);
    var payload =
        new RelayPayload.Text(FiredEventCodec.write(new FiredEventCodec.Notice(named, carried)));
    // A fresh identity per raised event, deliberately. Broker deduplication keys on the event ID,
    // and the same account may legitimately raise the same event with the same data twice — two
    // presses of the same button are two events — so a content-derived identity would collapse the
    // second into the first publication and silently lose its firings.
    var draft =
        new Relay.Draft(
            PUBLISHER + UUID.randomUUID(),
            PUBLISHER + RelayPort.hash(by),
            clock.get(),
            null,
            null,
            payload);

    var recorded =
        transactions.inTransaction(
            () -> {
              relay.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
              // First registration starts at the cutover baseline rather than replaying the
              // installation's legacy work, which is the choice the schedules migration made for
              // the same reason: a firing already in the inbox must not arrive a second time.
              // Subsequent registration preserves the exact durable position.
              relay.subscribe(subscription, Relay.Start.LATEST);
              relay.publish(topic, draft);
              // Consumed through the subscription rather than from the draft in hand. Reading it
              // back is what makes the firing arrive *through* the publication instead of beside
              // it, and it is the only shape in which the publisher check below has anything to
              // check.
              return consume(subscription);
            });

    boundary.requireOutsideTransaction();
    arrivals.dispatch(recorded);
    return recorded.stream().map(ScheduledArrival.Arrival::firing).toList();
  }

  /**
   * Copies every publication this subsystem issued into the firing inbox and advances the
   * subscriber's position past the batch.
   *
   * <p>Runs inside the publishing transaction and sees that transaction's own append, so the
   * ordinary case is one publication. The position is advanced once for the whole batch, after the
   * copies, so the cursor never moves past a firing that did not commit with it.
   */
  private List<ScheduledArrival.Arrival> consume(Relay.SubscriptionKey subscription) {
    var read = relay.read(subscription, BATCH);
    // A retention gap cannot arise while publication and consumption share a transaction, so this
    // is a recovery path: it acknowledges the exact boundary Relay reported, because a position
    // cannot move across an unacknowledged gap and leaving one outstanding would wedge the feed.
    read.gap().ifPresent(gap -> relay.acknowledgeGap(subscription, gap.throughInclusive()));
    Instant now = clock.get();
    var copied = new ArrayList<ScheduledArrival.Arrival>();
    long position = 0;
    for (var publication : read.publications()) {
      position = publication.position();
      // Not ours, so not interpreted — and the cursor still moves past it. Refusing the page would
      // let one foreign row wedge this subscriber's feed for the topic's whole retention window,
      // and a row this subsystem did not write is not its data to fail over.
      if (!issuedHere(publication.event().publisher())) continue;
      // Ours, so a malformed one is corruption of this subsystem's own writing and propagates.
      // Serving a silently shortened batch would hide it, and dropping the firing would lose a
      // raised event the caller was told nothing about.
      if (!(publication.event().payload() instanceof RelayPayload.Text message))
        throw new IllegalStateException("fired event topic carries a non-message payload");
      var notice = FiredEventCodec.read(message.text());
      copied.addAll(arrivals.recordFired(notice.event(), notice.data(), now));
    }
    if (position > 0) relay.advanceSeen(subscription, position);
    return List.copyOf(copied);
  }

  /**
   * Whether this subsystem issued the publication.
   *
   * <p>Relay is a content-agnostic substrate: it carries a contract, registers the general message
   * family every publisher produces, and does not reserve a topic name for one consumer — that
   * would be the broker policing a consumer's vocabulary. So the consumer declines to trust the
   * publisher instead. The namespace is server-owned and an external publisher cannot reach it:
   * {@code RelayPort.publisher} derives every SDK identity as {@code sdk:<hash>} and no transport
   * lets a caller choose its own.
   */
  private static boolean issuedHere(String publisher) {
    return publisher != null
        && publisher.length() == PUBLISHER.length() + 64
        && publisher.startsWith(PUBLISHER)
        && publisher.substring(PUBLISHER.length()).matches("[0-9a-f]{64}");
  }

  /** The refusals {@code event.fire} has always given for an unusable event name. */
  private static String require(String event) {
    if (event == null || event.isBlank())
      throw new CallerFault("an event needs a name; triggers listen for it by that name");
    try {
      return EventPayload.identity(event, "event");
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("event needs a bounded identity without padding or control characters");
    }
  }
}
