package io.aeyer.plowshare.server.search;

import io.aeyer.plowshare.protocol.search.AnswerStatus;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.Verb;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Every provider process this server has been told to route search calls to, and what has happened
 * to each one — the table {@code V33__search_providers} creates, in the shape that migration
 * argues.
 *
 * <h2>Registration is an upsert, and the row it produces is a fact about the present</h2>
 *
 * <p>{@link #upsert} is the whole of registration: a {@code provider_key} either gets a first row
 * or has its existing one overwritten, and there is no method that adds a second row for one key —
 * registering the same key twice moves the URL rather than adding a row, which {@code
 * ProviderStoreTest} asserts by name. The same call also resets {@link
 * Registration#consecutiveFailures}, on the migration's own argument: a redeployment is a new thing
 * wearing an old provider's key, and carrying a dead deployment's failure count into a healthy one
 * would leave it skipped for a reason nothing on screen explains.
 *
 * <h2>{@link #recordOutcome} is one statement, not a read-modify-write</h2>
 *
 * <p>A read-modify-write health update can lose concurrent outcomes. Two ladder rungs answering
 * close together for the same provider (unlikely for one query, not unlikely across the concurrent
 * queries a server actually serves) can interleave a read from one with a write from the other, and
 * whichever finishes last wins with a stale base. {@link #recordOutcome} has no such window: the
 * {@code CASE WHEN} lives inside the {@code UPDATE} itself, so "was the last outcome a success" and
 * "write the new one" are the same statement, and Postgres's own row lock serialises two concurrent
 * outcomes for one provider rather than this class needing to. It also costs one round trip instead
 * of two, on every terminal search outcome this server ever records. Spec §3.
 *
 * <h2>Hand-written {@link JdbcTemplate}, following {@code EntryStore}</h2>
 *
 * <p>Not JPA. This module's stores are hand-written SQL over a plain {@link JdbcTemplate}
 * throughout, and a registry with five methods over one table is not the place to start otherwise.
 */
@Repository
public class ProviderStore {

  private static final String COLUMNS =
      "provider_key, base_url, name, version, description, verbs, cost_class,"
          + " network_tier, max_results, max_query_length, domain_exclusion,"
          + " last_health_at, last_health_status, consecutive_failures";

  /*
   * verbs IS BOUND AS A java.sql.Array, NOT A '{...}' LITERAL BUILT IN JAVA,
   * for ProjectStore.defineLending's reason: the driver's own escaping is the
   * one that is never wrong, and a hand-rolled literal is a second, worse
   * copy of it.
   *
   * registered_at IS NOT IN THE COLUMN LIST HERE. It is DEFAULT now() on
   * INSERT and explicitly `now()` on the CONFLICT branch below, so both
   * paths take it from one clock and neither needs a bound parameter for it.
   *
   * consecutive_failures IS NOT BOUND EITHER: it is 0 on the INSERT branch
   * because a brand-new row has failed zero times, and it is reset to 0 on
   * the CONFLICT branch for the reason the class javadoc gives -- a
   * re-registration is a new deployment, and the counter describes the
   * deployment, not the key.
   *
   * last_health_at, last_health_status and last_health_message are likewise
   * absent from both the column list and the CONFLICT SET: a registration is
   * not a health event, and touching them here would either invent a health
   * reading for a row nothing has searched through yet (the INSERT branch)
   * or discard the last real reading for no reason connected to what changed
   * (the CONFLICT branch, if a caller re-registered the same URL to bump a
   * version number).
   */
  private static final String UPSERT =
      """
            INSERT INTO search_providers
                (provider_key, base_url, name, version, description, verbs, cost_class,
                 network_tier, max_results, max_query_length, domain_exclusion)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider_key) DO UPDATE SET
                base_url             = EXCLUDED.base_url,
                name                 = EXCLUDED.name,
                version              = EXCLUDED.version,
                description          = EXCLUDED.description,
                verbs                = EXCLUDED.verbs,
                cost_class           = EXCLUDED.cost_class,
                network_tier         = EXCLUDED.network_tier,
                max_results          = EXCLUDED.max_results,
                max_query_length     = EXCLUDED.max_query_length,
                domain_exclusion     = EXCLUDED.domain_exclusion,
                registered_at        = now(),
                consecutive_failures = 0
            """;

  private static final String FIND =
      "SELECT " + COLUMNS + " FROM search_providers WHERE provider_key = ?";

  private static final String ALL =
      "SELECT " + COLUMNS + " FROM search_providers ORDER BY provider_key";

  private static final String REMOVE = "DELETE FROM search_providers WHERE provider_key = ?";

  /*
   * See the class javadoc's "recordOutcome is one statement" section for why
   * this is not a SELECT followed by an UPDATE. The four `?` in order: the
   * moment (bound from Java as an OffsetDateTime, not `now()`, so a caller —
   * a future test among them — can assert against the instant it actually
   * passed), the status name, the message, and the success flag the CASE
   * branches on; `provider_key` closes the WHERE.
   */
  private static final String RECORD_OUTCOME =
      """
            UPDATE search_providers
               SET last_health_at = ?, last_health_status = ?, last_health_message = ?,
                   consecutive_failures = CASE WHEN ? THEN 0 ELSE consecutive_failures + 1 END
             WHERE provider_key = ?
            """;

  private static final RowMapper<Registration> ROW_MAPPER =
      (rs, rowNum) -> {
        String providerKey = rs.getString("provider_key");
        ProviderFacts facts =
            new ProviderFacts(
                providerKey,
                rs.getString("name"),
                rs.getString("version"),
                rs.getString("description"),
                verbsOf(rs.getArray("verbs")),
                CostClass.valueOf(rs.getString("cost_class")),
                NetworkTier.valueOf(rs.getString("network_tier")),
                rs.getInt("max_results"),
                rs.getInt("max_query_length"),
                rs.getBoolean("domain_exclusion"));
        return new Registration(
            providerKey,
            rs.getString("base_url"),
            facts,
            instant(rs, "last_health_at"),
            rs.getString("last_health_status"),
            rs.getInt("consecutive_failures"));
      };

  private final JdbcTemplate jdbc;

  public ProviderStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Register a provider, or move an already-registered one to a new URL and new facts.
   *
   * <p>{@code providerKey} is taken as given rather than re-read from {@code facts.providerKey()} —
   * the caller (the registration route) is the one that knows which key an operator is registering
   * under, and this method writes it under that key regardless of what the fetched facts also
   * happen to say. Whether the two must agree is the registration route's question, not this
   * store's.
   *
   * <p>Resets {@link Registration#consecutiveFailures} to zero and <b>leaves the health columns
   * exactly as they were</b>. This sentence used to say it cleared them too, which is not what the
   * statement does and not what it should do — the {@code UPSERT}'s own comment, two dozen lines
   * above, argues the case and the SQL is the half that is right: a registration is not a health
   * event, so writing those columns here would either invent a reading for a row nothing has
   * searched through yet, or discard the last real one because somebody re-registered the same URL
   * to bump a version number. The failure counter is different in kind and that is why only it is
   * reset: it describes a deployment, and a re-registration is a new deployment wearing an old key.
   */
  public void upsert(String providerKey, String baseUrl, ProviderFacts facts) {
    String[] verbNames = facts.verbs().stream().map(Enum::name).toArray(String[]::new);
    jdbc.update(
        connection -> {
          PreparedStatement statement = connection.prepareStatement(UPSERT);
          statement.setString(1, providerKey);
          statement.setString(2, baseUrl);
          statement.setString(3, facts.name());
          statement.setString(4, facts.version());
          statement.setString(5, facts.description());
          statement.setArray(6, connection.createArrayOf("text", verbNames));
          statement.setString(7, facts.costClass().name());
          statement.setString(8, facts.networkTier().name());
          statement.setInt(9, facts.maxResults());
          statement.setInt(10, facts.maxQueryLength());
          statement.setBoolean(11, facts.domainExclusion());
          return statement;
        });
  }

  /** The row registered under this key, if there is one. */
  public Optional<Registration> find(String providerKey) {
    return jdbc.query(FIND, ROW_MAPPER, providerKey).stream().findFirst();
  }

  /** Every registered provider, ordered by key for a stable listing. */
  public List<Registration> all() {
    return jdbc.query(ALL, ROW_MAPPER);
  }

  /**
   * Deregister a provider.
   *
   * @return {@code true} if a row was removed, {@code false} if this key named nothing — a caller
   *     asking to remove a provider that is already gone gets an answer it can act on rather than
   *     an exception for a race it did not cause
   */
  public boolean remove(String providerKey) {
    return jdbc.update(REMOVE, providerKey) > 0;
  }

  /**
   * Record the terminal outcome of one search through this provider.
   *
   * <p>See the class javadoc for why this is a single {@code UPDATE} and not a read-modify-write.
   * {@code status == SUCCESS} is the only status that clears the counter — {@link
   * AnswerStatus#UNSUPPORTED} increments it the same as {@link AnswerStatus#FAILED}, because a
   * provider registered for a verb it then refuses is unhealthy for the ladder's purposes
   * regardless of which of the two ways it failed.
   *
   * <p>A call naming a provider that was removed between the search starting and this call runs the
   * {@code UPDATE} against zero rows and returns normally — there is nothing left to record health
   * against, and that is not this method's caller's fault.
   *
   * @param message the operator-facing detail, or {@code null} on a success
   */
  public void recordOutcome(String providerKey, AnswerStatus status, String message) {
    jdbc.update(
        RECORD_OUTCOME,
        utc(Instant.now()),
        status.name(),
        message,
        status == AnswerStatus.SUCCESS,
        providerKey);
  }

  /*
   * No null branch: verbs is NOT NULL and
   * search_providers_declares_at_least_one_verb keeps it non-empty, so every
   * row this store can read back names at least one verb this loop can
   * parse. LinkedHashSet rather than the array's own order thrown away, so a
   * caller iterating facts().verbs() twice sees the same order twice -- not
   * a guarantee ProviderFacts itself makes, but one this mapper can give for
   * free.
   */
  private static Set<Verb> verbsOf(Array column) throws SQLException {
    String[] names = (String[]) column.getArray();
    Set<Verb> verbs = new LinkedHashSet<>();
    for (String name : names) {
      verbs.add(Verb.valueOf(name));
    }
    return verbs;
  }

  /*
   * OffsetDateTime on both sides and never java.sql.Timestamp, for the reason
   * EntryStore, MemoryStore, ProposalStore, ReasonLog and ConversationStore
   * all give: Timestamp carries no zone and comes back through the JVM's
   * default calendar, so a server outside UTC round-trips a shifted instant.
   * A Testcontainers Postgres and a CI runner both happen to default to UTC,
   * which is exactly why that bug passes green in this suite and ships
   * anyway -- the argument has to be carried into new stores rather than
   * re-discovered per store.
   */
  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  /**
   * Null-aware, because {@code last_health_at} really is nullable: a row that has been registered
   * but never searched has no health reading yet. {@code EntryStore.instant}'s copy, for {@code
   * recorded_at}.
   */
  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
