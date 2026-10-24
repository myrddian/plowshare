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
    requirePositive(maximumAge);
    Instant at = RelayValues.time(now);
    // The verdict comes from relay_subscription_authority_validity, which is the one definition of
    // validity in the system (V188). It is not re-expressed here, because the wake-hint audience
    // below applies the same function as a set predicate, and two parallel notions of "valid" that
    // could drift would mean hinting to an account this gate refuses.
    var verdict =
        jdbc
            .query(
                """
        SELECT relay_subscription_authority_validity(
            a.topic_generation,t.generation,a.established_at,
            (? * interval '1 microsecond'),?) AS validity
        FROM relay_subscription_authority a
        JOIN relay_topics t ON t.scope_key=a.scope_key AND t.name=a.topic
        WHERE a.scope_key=? AND a.topic=? AND a.subscriber=? AND a.account=?
        """,
                (row, index) -> row.getString("validity"),
                micros(maximumAge),
                java.sql.Timestamp.from(at),
                RelayScopeCodec.write(subscription.topic()),
                subscription.topic().name(),
                subscription.subscriber(),
                account)
            .stream()
            .findFirst();
    // No row and a withdrawn row are the same refusal, which is why absence is not an error.
    return verdict.map(Validity::valueOf).orElse(Validity.NOT_ESTABLISHED);
  }

  @Override
  public java.util.List<String> validReaders(
      Relay.TopicKey topic, Duration maximumAge, Instant now) {
    Objects.requireNonNull(topic, "topic");
    requirePositive(maximumAge);
    Instant at = RelayValues.time(now);
    // One query for the whole audience, with the same predicate the single-subscription gate uses.
    // DISTINCT because one account may hold several groups on a topic and a hint is per account.
    return jdbc.queryForList(
        """
        SELECT DISTINCT a.account
        FROM relay_subscription_authority a
        JOIN relay_topics t ON t.scope_key=a.scope_key AND t.name=a.topic
        WHERE a.scope_key=? AND a.topic=?
          AND relay_subscription_authority_validity(
              a.topic_generation,t.generation,a.established_at,
              (? * interval '1 microsecond'),?)='VALID'
        ORDER BY a.account
        """,
        String.class,
        RelayScopeCodec.write(topic),
        topic.name(),
        micros(maximumAge),
        java.sql.Timestamp.from(at));
  }

  private static long micros(Duration maximumAge) {
    return maximumAge.toNanos() / 1000;
  }

  private static void requirePositive(Duration maximumAge) {
    if (maximumAge == null || maximumAge.isZero() || maximumAge.isNegative())
      throw new IllegalArgumentException("subscription maximum age must be positive");
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
}
