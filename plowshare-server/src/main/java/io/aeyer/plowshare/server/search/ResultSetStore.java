package io.aeyer.plowshare.server.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.aeyer.plowshare.protocol.search.Hit;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * One search's results, held so the model can page through them without the
 * provider being touched a second time — the table {@code
 * V34__search_result_sets} creates, in the shape that migration argues.
 *
 * <h2>This is a result set, not a cache</h2>
 *
 * <p>Nothing here reads one key's row to answer a search keyed differently —
 * {@link #get} and {@link #put} are always called with the same {@link
 * QueryKey}, computed once by the route that ran the ladder. See the
 * migration's own comment for why that distinction, not this class's API
 * shape, is what makes it a result set rather than a query cache.
 *
 * <h2>{@link #put} is an upsert, and a repeated key replaces rather than
 * accumulates</h2>
 *
 * <p>A model that asks the same question twice in one session is not paging
 * the first answer forward — it is asking again — and the second {@link #put}
 * for a key already on file overwrites the provider, the hits and the fetch
 * time in one statement, exactly as {@code ProviderStore.upsert} overwrites a
 * re-registration rather than leaving two rows for one key.
 *
 * <h2>The TTL is read at query time, never stored on the row</h2>
 *
 * <p>{@link #get} and {@link #purgeExpired} both take {@code ttl} as a
 * parameter rather than reading a column, because the caller — {@code
 * SearchProperties.resultSetTtlNow()}, live — is the one place that knows
 * today's policy, and a value baked into the row at {@link #put} time would
 * freeze that row under whatever the policy happened to be the moment it was
 * written. See the migration's comment on the column that is deliberately not
 * here.
 *
 * <h2>Hand-written {@link JdbcTemplate}, following {@code EntryStore} and
 * {@code ProviderStore}</h2>
 *
 * <p>Not JPA, and {@code hits} travels as JSONB the same way {@code
 * EntryStore}'s {@code tool_calls} does: bound with {@code CAST(? AS JSONB)}
 * so a malformed document is refused at the write rather than on the read
 * that tries to rebuild a {@link Hit} from it.
 */
@Repository
public class ResultSetStore {

    /*
     * ONE ObjectWriter AND ONE ObjectReader FOR EVERY CALL, on EntryStore's
     * own reasoning: Jackson documents both as immutable and safe to share
     * once configured, and this store is called from whatever thread served
     * the search that ran the ladder.
     */
    private static final ObjectWriter HITS_OUT =
            new ObjectMapper().writerFor(new TypeReference<List<Hit>>() {});

    private static final ObjectReader HITS_IN =
            new ObjectMapper().readerFor(new TypeReference<List<Hit>>() {});

    /*
     * INSERT ... ON CONFLICT rather than a read-then-decide, for the same
     * concurrency reason ProviderStore.upsert gives: two requests racing to
     * store the same query_key (a model that fired the same search twice
     * before the first answer came back) serialise on Postgres's own row
     * lock instead of this class needing to.
     *
     * CAST(? AS JSONB): see the class javadoc. The driver binds a String
     * parameter as varchar, and Postgres will not assign a varchar to a
     * jsonb column without the cast -- EntryStore.INSERT's own comment.
     */
    private static final String UPSERT = """
            INSERT INTO search_result_sets (query_key, provider_key, hits, fetched_at)
            VALUES (?, ?, CAST(? AS JSONB), ?)
            ON CONFLICT (query_key) DO UPDATE SET
                provider_key = EXCLUDED.provider_key,
                hits         = EXCLUDED.hits,
                fetched_at   = EXCLUDED.fetched_at
            """;

    /*
     * `fetched_at > ?` AND NOT `now() - fetched_at < ?`: the bound value is
     * `now - ttl`, computed once in Java from the `now` and `ttl` this call
     * was given, so a test can hand in a fixed instant and get a fixed
     * answer rather than the statement racing the wall clock a second call
     * inside the same test would also be racing.
     */
    private static final String GET = """
            SELECT provider_key, hits, fetched_at
              FROM search_result_sets
             WHERE query_key = ?
               AND fetched_at > ?
            """;

    private static final String PURGE_EXPIRED =
            "DELETE FROM search_result_sets WHERE fetched_at <= ?";

    private static final RowMapper<StoredSet> ROW_MAPPER = (rs, rowNum) -> new StoredSet(
            rs.getString("provider_key"), read(rs.getString("hits")), instant(rs, "fetched_at"));

    private final JdbcTemplate jdbc;

    public ResultSetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Store one search's results under {@code key}, replacing whatever this
     * key already named.
     *
     * @param key a {@link QueryKey#of} value — this method does not compute
     *     it, so a caller can key a stored set on whatever it already hashed
     *     for logging or for the response it is about to build
     * @param providerKey which provider produced {@code hits}
     * @param hits the hits, in the order to page them back in
     * @param now when this search ran — bound as given rather than read from
     *     {@code now()}, so a caller (a test among them) can assert against
     *     the instant it actually passed, {@code ProviderStore.recordOutcome}'s
     *     own reason for taking its moment as a parameter
     */
    public void put(String key, String providerKey, List<Hit> hits, Instant now) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(UPSERT);
            statement.setString(1, key);
            statement.setString(2, providerKey);
            statement.setString(3, write(hits));
            statement.setObject(4, utc(now));
            return statement;
        });
    }

    /**
     * The set stored under {@code key}, if one is on file and has not
     * outlived {@code ttl}.
     *
     * <p>A set past its TTL is answered as absent rather than returned
     * stale — the row is left for {@link #purgeExpired} to reclaim on its own
     * schedule, but this call will not hand it back regardless.
     *
     * @param now the moment to measure {@code ttl} from — the search route's
     *     clock, not this method's own, so every page of one search's results
     *     ages from the same reference point
     */
    public Optional<StoredSet> get(String key, Instant now, Duration ttl) {
        return jdbc.query(GET, ROW_MAPPER, key, utc(now.minus(ttl))).stream().findFirst();
    }

    /**
     * Delete every stored set whose {@code fetched_at} is at or before {@code
     * now - ttl}, and answer how many rows that was.
     *
     * <p>Deliberately not folded into {@link #get}: a page request should
     * cost one row read, not a table-wide scan-and-delete on every call, so
     * reclaiming space is its own operation for a caller to run on whatever
     * cadence it chooses.
     *
     * <p>Called by {@code Buffers.purge()}, behind {@code POST
     * /v1/buffers/purge} — an operator's verb, not a scheduler this server
     * starts; see {@code Buffers}' own class javadoc for why. {@link #get}'s
     * own {@code fetched_at > ?} filter already keeps an expired set from
     * ever being served, so correctness never depended on this running — what
     * this closes is reclamation: without a caller, an expired row stayed in
     * {@code search_result_sets} until something deleted it, forever.
     * {@code V36__buffer_purge_indexes.sql} adds the index this scan needed
     * once this method had one.
     */
    public int purgeExpired(Instant now, Duration ttl) {
        return jdbc.update(PURGE_EXPIRED, utc(now.minus(ttl)));
    }

    private static String write(List<Hit> hits) {
        try {
            return HITS_OUT.writeValueAsString(hits);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException(
                    "a stored set's hits could not be written: " + unwritable.getOriginalMessage(),
                    unwritable);
        }
    }

    private static List<Hit> read(String json) {
        try {
            return HITS_IN.readValue(json);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "a stored set's hits could not be read back: " + unreadable.getOriginalMessage(),
                    unreadable);
        }
    }

    /*
     * OffsetDateTime on both sides and never java.sql.Timestamp --
     * ProviderStore.utc's own comment, carried into this store rather than
     * re-discovered: Timestamp carries no zone and comes back through the
     * JVM's default calendar, so a server outside UTC round-trips a shifted
     * instant, a bug a UTC-defaulting test container cannot catch.
     */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
