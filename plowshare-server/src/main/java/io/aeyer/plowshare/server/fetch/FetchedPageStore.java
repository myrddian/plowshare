package io.aeyer.plowshare.server.fetch;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * One fetched page, held whole so an agent can read it back in windows
 * without the page being fetched a second time — the table {@code
 * V35__fetched_pages} creates, in the shape that migration argues.
 *
 * <h2>{@link #put} is an upsert, and a repeated key replaces rather than
 * accumulates</h2>
 *
 * <p>An agent that fetches the same URL twice is not adding a second copy of
 * the page — it is asking again, most likely because the first copy is gone
 * or stale — and the second {@link #put} for a key already on file overwrites
 * the title, the text, {@code fetched_at} and {@code byte_size} in one
 * statement, {@code ResultSetStore.put}'s own reasoning for {@code
 * search_result_sets}. {@code last_read} is reset to the same {@code now} as
 * {@code fetched_at}: a page an agent just re-fetched has, at that instant,
 * been read no more recently than it was fetched, and starting the liveness
 * clock over is what lets a freshly re-fetched page survive {@link
 * #purgeExpired} on the same terms as a page nobody has read yet.
 *
 * <h2>Hand-written {@link JdbcTemplate}, following {@code ResultSetStore}</h2>
 *
 * <p>Not JPA, one {@link RowMapper}, statements inline — {@code
 * ResultSetStore}'s shape, carried over rather than rediscovered.
 */
@Repository
public class FetchedPageStore {

    /*
     * INSERT ... ON CONFLICT rather than a read-then-decide, for the same
     * concurrency reason ProviderStore.upsert and ResultSetStore.put give: two
     * requests racing to fetch the same URL (an agent that issued the same
     * tool call twice before the first answer came back) serialise on
     * Postgres's own row lock instead of this class needing to.
     *
     * last_read is bound to the same `now` as fetched_at on both the insert
     * and the conflict branch -- see this class's own javadoc on `put` for
     * why a fetch resets the liveness clock rather than leaving whatever a
     * previous read had set it to.
     */
    private static final String UPSERT = """
            INSERT INTO fetched_pages
                   (url_key, url, title, text, fetched_at, last_read, byte_size)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (url_key) DO UPDATE SET
                url        = EXCLUDED.url,
                title      = EXCLUDED.title,
                text       = EXCLUDED.text,
                fetched_at = EXCLUDED.fetched_at,
                last_read  = EXCLUDED.last_read,
                byte_size  = EXCLUDED.byte_size
            """;

    private static final String FIND = """
            SELECT url_key, url, title, text, fetched_at, last_read, byte_size
              FROM fetched_pages
             WHERE url_key = ?
            """;

    private static final String TOUCH_READ =
            "UPDATE fetched_pages SET last_read = ? WHERE url_key = ?";

    /*
     * THE AND IS THE WHOLE OF THIS STATEMENT'S DESIGN. A row is purgeable only
     * when it is BOTH past its TTL (fetched_at <= now - ttl) AND not being
     * read (last_read <= now - liveWindow). The ten-minute liveness stamp
     * this compares against is written by touchRead on every read an agent
     * makes of a page's text, and it is not only a refetch guard -- keeping a
     * page an agent is actively reading from silently going stale underneath
     * it -- it is an eviction guard as well, over the very same column. An
     * OR, or dropping the last_read half entirely, would let purgeExpired
     * delete a row an agent is a page-window into reading, the moment its
     * fetch crosses the TTL, regardless of how recently that agent last
     * turned the page: a reader part-way through a long document must not
     * have the ground pulled out from under it because the fetch that
     * produced it happened to be a day old. Two arguments, not one, are why
     * the caller supplies `now - ttl` and `now - liveWindow` as two separate
     * bound values rather than this statement computing one threshold from a
     * single duration: the two clocks answer two different questions --
     * "is this fetch old enough to distrust" and "is anybody still reading
     * it" -- and folding them into one would only be correct if the two
     * questions always had the same answer, which is exactly the case this
     * table exists to tell apart.
     */
    private static final String PURGE_EXPIRED =
            "DELETE FROM fetched_pages WHERE fetched_at <= ? AND last_read <= ?";

    private static final RowMapper<FetchedPage> ROW_MAPPER = (rs, rowNum) -> new FetchedPage(
            rs.getString("url_key"),
            rs.getString("url"),
            rs.getString("title"),
            rs.getString("text"),
            instant(rs, "fetched_at"),
            instant(rs, "last_read"),
            rs.getLong("byte_size"));

    private final JdbcTemplate jdbc;

    public FetchedPageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Store one fetched page under {@code urlKey}, replacing whatever this
     * key already named.
     *
     * @param urlKey a {@link UrlKey#of} value — this method does not compute
     *     it, so a caller can key a stored page on whatever it already
     *     hashed for a tool result it is about to build
     * @param url the URL {@code page} was fetched from, kept beside the key
     *     for the reason {@code V35__fetched_pages.sql} argues
     * @param page the page as {@link PageExtractor#extract} produced it
     * @param now when this fetch ran — bound as given rather than read from
     *     {@code now()}, {@code ResultSetStore.put}'s own reason, and also
     *     what {@code last_read} is reset to; see this class's own javadoc
     */
    public void put(String urlKey, String url, ExtractedPage page, Instant now) {
        byte[] textBytes = page.text().getBytes(StandardCharsets.UTF_8);
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(UPSERT);
            statement.setString(1, urlKey);
            statement.setString(2, url);
            statement.setString(3, page.title());
            statement.setString(4, page.text());
            statement.setObject(5, utc(now));
            statement.setObject(6, utc(now));
            statement.setLong(7, textBytes.length);
            return statement;
        });
    }

    /** The page stored under {@code urlKey}, if one is on file. */
    public Optional<FetchedPage> find(String urlKey) {
        return jdbc.query(FIND, ROW_MAPPER, urlKey).stream().findFirst();
    }

    /**
     * Move {@code urlKey}'s liveness stamp forward to {@code now}, without
     * touching {@code fetched_at}.
     *
     * <p>Called on every read an agent makes of a page's text, not only the
     * first — see {@link #PURGE_EXPIRED}'s own comment for what this stamp
     * guards.
     */
    public void touchRead(String urlKey, Instant now) {
        jdbc.update(TOUCH_READ, utc(now), urlKey);
    }

    /**
     * Delete every stored page that is both past {@code ttl} and not
     * presently being read, and answer how many rows that was.
     *
     * <p>See {@link #PURGE_EXPIRED} for the argument behind the {@code AND}.
     *
     * @param now the moment to measure both thresholds from
     * @param ttl how old a fetch may be before it is a candidate for
     *     purging — the bound value is {@code now - ttl}
     * @param liveWindow how recently a page must have been read for it to
     *     count as still being read — the bound value is {@code now -
     *     liveWindow}
     */
    public int purgeExpired(Instant now, Duration ttl, Duration liveWindow) {
        return jdbc.update(PURGE_EXPIRED, utc(now.minus(ttl)), utc(now.minus(liveWindow)));
    }

    /*
     * OffsetDateTime on both sides and never java.sql.Timestamp --
     * ResultSetStore.utc's own comment, carried into this store rather than
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
