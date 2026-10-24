package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Read-tier consumption gated on {@link ProjectMembers#mayUse}, the authority that already governs
 * log inspection, rather than on {@link ProjectMembers#mayWork}.
 *
 * <p>Three checks stand between a caller and a consumer-state write, and they are independent:
 *
 * <ol>
 *   <li><b>Read authority on the topic's project.</b> Any role present admits; no role refuses. A
 *       personal project additionally requires its exact owner, as log inspection does.
 *   <li><b>A valid subscription.</b> Resolved once at {@link #subscribe} and recorded; afterwards
 *       only its validity is checked, never the audience again.
 *   <li><b>Ownership of the consumer state.</b> The lease and offset row being written must be the
 *       caller's own or unowned.
 * </ol>
 *
 * <p>No path here registers or configures a topic, publishes, opens a port or dispatches to a
 * receiver. {@link Relay#subscribe} requires the topic to exist already, so a read-tier caller
 * cannot bring one into being, and the policy argument that {@link Relay#registerTopic} would need
 * is never reached.
 */
public final class ReadTierRelayConsumption implements RelayConsumption {
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final Relay relay;
  private final RelayConsumerRepository consumers;
  private final RelaySubscriptionAuthority authority;
  private final Duration maximumAge;
  private final Supplier<Instant> clock;

  /**
   * @param maximumAge the configured bound on a subscription's age; must be positive. It is a
   *     deployment value and has no default here: a composition root that cannot supply it must
   *     fail rather than choose a permissive window on its behalf.
   */
  public ReadTierRelayConsumption(
      ProjectMembers members,
      ProjectWorkspaces projects,
      Relay relay,
      RelayConsumerRepository consumers,
      RelaySubscriptionAuthority authority,
      Duration maximumAge,
      Supplier<Instant> clock) {
    this.members = Objects.requireNonNull(members, "members");
    this.projects = Objects.requireNonNull(projects, "projects");
    this.relay = Objects.requireNonNull(relay, "relay");
    this.consumers = Objects.requireNonNull(consumers, "consumers");
    this.authority = Objects.requireNonNull(authority, "authority");
    if (maximumAge == null || maximumAge.isZero() || maximumAge.isNegative())
      throw new IllegalArgumentException(
          "A positive Relay subscription maximum age is required configuration");
    this.maximumAge = maximumAge;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Relay.Subscription subscribe(String account, Request request, Relay.Start start) {
    Objects.requireNonNull(start, "start");
    var key = readable(account, request);
    // Reading the topic first keeps the refusal honest: a read-tier caller must not create a topic,
    // and an absent topic is a caller fault rather than a broker invariant failure.
    requireTopic(key);
    var subscription = relay.subscribe(key, start);
    authority.establish(key, account, clock.get());
    return subscription;
  }

  @Override
  public Relay.Read consume(String account, Request request, int limit) {
    var key = established(account, request);
    return relay.read(key, limit);
  }

  @Override
  public Relay.Subscription advance(String account, Request request, long position) {
    var key = established(account, request);
    requireOwnState(key, account);
    return relay.advanceSeen(key, position);
  }

  @Override
  public RelayConsumerRepository.Lease lease(String account, Request request, Duration duration) {
    var key = established(account, request);
    requireOwnState(key, account);
    // The worker identity is the account itself, so a second lease call by the same account extends
    // its own lease instead of contending with it, while a work-tier pipeline worker — which names
    // itself per pass — can never be mistaken for it.
    return consumers
        .acquire(key, account, account, duration)
        .orElseThrow(
            () ->
                new CallerFault(
                    "Relay subscription is leased by another consumer: " + request.subscriber()));
  }

  @Override
  public void release(String account, Request request, RelayConsumerRepository.Lease lease) {
    Objects.requireNonNull(lease, "lease");
    var key = readable(account, request);
    if (!lease.subscription().equals(key) || !lease.account().equals(account))
      throw new CallerFault("Relay lease belongs to another subscription or account");
    consumers.release(lease);
  }

  /** Read authority on the topic's project, returning the typed subscription key it admits. */
  private Relay.SubscriptionKey readable(String account, Request request) {
    RelayValues.identity(account, "account");
    Objects.requireNonNull(request, "request");
    var owner = projects.personalOwner(request.project());
    if (owner.isPresent() && !owner.get().equals(account)
        || !members.mayUse(request.project(), account))
      throw new CallerFault("Relay topic is unavailable to this account");
    var id = projects.id(request.project());
    if (id == null || id < 1) throw new CallerFault("Relay topic is unavailable to this account");
    return new Relay.SubscriptionKey(new Relay.TopicKey(id, request.topic()), request.subscriber());
  }

  /** Read authority plus a subscription whose authority record is still valid. */
  private Relay.SubscriptionKey established(String account, Request request) {
    var key = readable(account, request);
    var validity = authority.validate(key, account, maximumAge, clock.get());
    if (!validity.valid())
      throw new CallerFault(
          "Relay subscription is no longer valid (" + validity + "); subscribe again");
    return key;
  }

  private void requireOwnState(Relay.SubscriptionKey key, String account) {
    if (!consumers.writableBy(key, account))
      throw new CallerFault("Relay consumer state belongs to another account");
  }

  private void requireTopic(Relay.SubscriptionKey key) {
    try {
      relay.topic(key.topic());
    } catch (IllegalStateException | IllegalArgumentException absent) {
      throw new CallerFault("Relay topic is unavailable to this account");
    }
  }
}
