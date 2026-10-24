package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Subscription authority records in PostgreSQL. Establishment captures the topic's current
 * incarnation in the same statement that writes the record, so a concurrent topic reset either
 * precedes the capture or invalidates the record it produced.
 *
 * <p>Validation is a single indexed read joined to the topic, deliberately: it runs once per
 * established subscription, never once per publication per account.
 */
public final class JdbcRelaySubscriptionAuthority implements RelaySubscriptionAuthority {
  private final JdbcTemplate jdbc;

  public JdbcRelaySubscriptionAuthority(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Established establish(
      Relay.SubscriptionKey subscription, String account, Instant established) {
    Objects.requireNonNull(subscription, "subscription");
    RelayValues.identity(account, "subscription authority account");
    Instant at = RelayValues.time(established);
    // Selecting the generation from relay_topics inside the INSERT keeps the captured incarnation
    // and the record a single atomic statement. A missing topic selects no row, so nothing is
    // written and the caller is told, rather than an authority record existing for no topic.
    return jdbc
        .query(
            """
        INSERT INTO relay_subscription_authority(scope_key,topic,subscriber,account,topic_generation,established_at)
        SELECT ?,?,?,?,t.generation,? FROM relay_topics t WHERE t.scope_key=? AND t.name=?
        ON CONFLICT(scope_key,topic,subscriber,account) DO UPDATE SET
          topic_generation=EXCLUDED.topic_generation, established_at=EXCLUDED.established_at
        RETURNING topic_generation,established_at
        """,
            (row, index) ->
                new Established(
                    subscription,
                    account,
                    row.getObject("topic_generation", UUID.class),
                    row.getObject("established_at", OffsetDateTime.class).toInstant()),
            RelayScopeCodec.write(subscription.topic()),
            subscription.topic().name(),
            subscription.subscriber(),
            account,
            java.sql.Timestamp.from(at),
            RelayScopeCodec.write(subscription.topic()),
            subscription.topic().name())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Relay topic does not exist"));
  }

  @Override
  public Validity validate(
      Relay.SubscriptionKey subscription, String account, Duration maximumAge, Instant now) {
    Objects.requireNonNull(subscription, "subscription");
    RelayValues.identity(account, "subscription authority account");
    if (maximumAge == null || maximumAge.isZero() || maximumAge.isNegative())
      throw new IllegalArgumentException("subscription maximum age must be positive");
    Instant at = RelayValues.time(now);
    var record =
        jdbc
            .query(
                """
        SELECT a.topic_generation,a.established_at,t.generation AS current_generation
        FROM relay_subscription_authority a
        JOIN relay_topics t ON t.scope_key=a.scope_key AND t.name=a.topic
        WHERE a.scope_key=? AND a.topic=? AND a.subscriber=? AND a.account=?
        """,
                (row, index) ->
                    new Record(
                        row.getObject("topic_generation", UUID.class),
                        row.getObject("current_generation", UUID.class),
                        row.getObject("established_at", OffsetDateTime.class).toInstant()),
                RelayScopeCodec.write(subscription.topic()),
                subscription.topic().name(),
                subscription.subscriber(),
                account)
            .stream()
            .findFirst();
    if (record.isEmpty()) return Validity.NOT_ESTABLISHED;
    var found = record.get();
    // Both conditions are checked, and neither substitutes for the other. The generation is the
    // real revocation; the age only caps the exposure if a revocation was missed.
    if (!found.topicGeneration().equals(found.currentGeneration()))
      return Validity.GENERATION_CHANGED;
    if (!found.establishedAt().plus(maximumAge).isAfter(at)) return Validity.EXPIRED;
    return Validity.VALID;
  }

  @Override
  public void invalidateTopic(Relay.TopicKey topic) {
    Objects.requireNonNull(topic, "topic");
    int changed =
        jdbc.update(
            "UPDATE relay_topics SET generation=gen_random_uuid() WHERE scope_key=? AND name=?",
            RelayScopeCodec.write(topic),
            topic.name());
    if (changed != 1) throw new IllegalStateException("Relay topic does not exist");
  }

  @Override
  public int invalidateAccount(Relay.Scope scope, String account) {
    Objects.requireNonNull(scope, "scope");
    RelayValues.identity(account, "subscription authority account");
    return jdbc.update(
        "DELETE FROM relay_subscription_authority WHERE scope_key=? AND account=?",
        RelayScopeCodec.write(scope),
        account);
  }

  private record Record(UUID topicGeneration, UUID currentGeneration, Instant establishedAt) {}
}
