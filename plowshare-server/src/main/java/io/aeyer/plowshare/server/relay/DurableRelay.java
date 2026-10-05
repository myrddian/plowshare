package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Broker entry point. Its trusted clock assigns publication/seen timestamps, never client input.
 */
public final class DurableRelay implements Relay {
  private final RelayRepository repository;
  private final Supplier<Instant> clock;
  private final java.util.function.Function<TopicKey, Optional<Policy>> systemPolicies;
  private final java.util.function.Consumer<Relay.TopicKey> committed;

  public DurableRelay(RelayRepository repository, Supplier<Instant> clock) {
    this(repository, clock, topic -> {});
  }

  /** The composition-supplied callback must defer hints until any enclosing transaction commits. */
  public DurableRelay(
      RelayRepository repository,
      Supplier<Instant> clock,
      java.util.function.Consumer<Relay.TopicKey> committed) {
    this(repository, clock, committed, topic -> Optional.empty());
  }

  /** Only explicit system deployment policy may override a publisher's registration default. */
  public DurableRelay(
      RelayRepository repository,
      Supplier<Instant> clock,
      java.util.function.Consumer<TopicKey> committed,
      java.util.function.Function<TopicKey, Optional<Policy>> systemPolicies) {
    this.systemPolicies = Objects.requireNonNull(systemPolicies);
    this.committed = Objects.requireNonNull(committed);
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Topic registerTopic(TopicKey key, RelayPayload.Kind kind, Policy initialPolicy) {
    var validated = new Topic(key, kind, initialPolicy, 0, 0);
    var configured =
        key.scope() == SystemScope.SERVER
            ? Objects.requireNonNull(systemPolicies.apply(key))
            : Optional.<Policy>empty();
    if (configured.isPresent()) return repository.configureTopic(key, kind, configured.get());
    return repository.registerTopic(validated.key(), validated.kind(), validated.policy());
  }

  @Override
  public Topic configureTopic(TopicKey key, RelayPayload.Kind kind, Policy policy) {
    // The immutable topic value validates the registered system-topic family before persistence.
    var validated = new Topic(key, kind, policy, 0, 0);
    return repository.configureTopic(validated.key(), validated.kind(), validated.policy());
  }

  @Override
  public Publication publish(TopicKey key, Draft draft) {
    var publication =
        repository.append(
            Objects.requireNonNull(key, "key"),
            Objects.requireNonNull(draft, "draft"),
            RelayValues.time(clock.get()));
    committed.accept(key);
    return publication;
  }

  @Override
  public Topic topic(TopicKey key) {
    return repository.topic(Objects.requireNonNull(key, "key"));
  }

  @Override
  public Subscription subscribe(SubscriptionKey key, Start start) {
    return repository.subscribe(
        Objects.requireNonNull(key, "key"),
        Objects.requireNonNull(start, "start"),
        RelayValues.time(clock.get()));
  }

  @Override
  public Optional<Publication> retained(TopicKey key, String eventId) {
    return repository.retained(
        Objects.requireNonNull(key, "key"), RelayValues.identity(eventId, "event ID"));
  }

  @Override
  public Read read(SubscriptionKey key, int limit) {
    RelayValues.limit(limit, 1000);
    return repository.read(Objects.requireNonNull(key, "key"), limit);
  }

  @Override
  public Subscription advanceSeen(SubscriptionKey key, long position) {
    if (position < 1) throw new IllegalArgumentException("position must be positive");
    return repository.advanceSeen(
        Objects.requireNonNull(key, "key"), position, RelayValues.time(clock.get()));
  }

  @Override
  public Subscription acknowledgeGap(SubscriptionKey key, long expiredThrough) {
    if (expiredThrough < 1) throw new IllegalArgumentException("expired boundary must be positive");
    return repository.acknowledgeGap(
        Objects.requireNonNull(key, "key"), expiredThrough, RelayValues.time(clock.get()));
  }

  @Override
  public Unread unread(SubscriptionKey key) {
    return repository.unread(Objects.requireNonNull(key, "key"));
  }
}
