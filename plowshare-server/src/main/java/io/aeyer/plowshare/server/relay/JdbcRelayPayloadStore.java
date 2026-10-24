package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL implementation of {@link RelayPayloadStore}.
 *
 * <p>The digest is SHA-256 over the UTF-8 bytes of the application's serialization (see {@link
 * RelayPayloadCodec#write}), which is what is passed as the {@code payload} parameter. {@code
 * byte_length} instead comes from PostgreSQL's JSONB rendering, which normalizes key order and
 * whitespace. The two may differ; an equivalent payload hashed from a different rendering would
 * only miss a dedup, never read or delete the wrong bytes.
 *
 * <p>{@link #store} is a single idempotent statement and does not open a transaction, so a caller
 * outside one commits it immediately. {@code ON CONFLICT DO NOTHING} writes no row version for a
 * repeat of a hot digest.
 */
public final class JdbcRelayPayloadStore implements RelayPayloadStore {
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final int MAX_DIGESTS = 1000;
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Duration stagingWindow;

  public JdbcRelayPayloadStore(JdbcTemplate jdbc, UnitOfWork transactions, Duration stagingWindow) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.stagingWindow = Objects.requireNonNull(stagingWindow, "stagingWindow");
    if (stagingWindow.isNegative() || stagingWindow.isZero())
      throw new IllegalArgumentException("staging window must be positive");
  }

  @Override
  public String store(RelayPayload payload, Instant now) {
    String json = RelayPayloadCodec.write(payload);
    String digest = io.aeyer.plowshare.protocol.RelayPort.hash(json);
    // Truncating to the hour makes every payload stored in the same hour share one ejection_at,
    // so the ejection index stays dense in a few values rather than one per microsecond.
    Instant ejection = RelayValues.time(now).plus(stagingWindow).truncatedTo(ChronoUnit.HOURS);
    jdbc.update(
        """
        INSERT INTO relay_payloads(digest,payload,byte_length,ejection_at)
        VALUES (?,?::jsonb,octet_length((?::jsonb)::text),?)
        ON CONFLICT (digest) DO NOTHING
        """,
        digest,
        json,
        json,
        Timestamp.from(ejection));
    return digest;
  }

  @Override
  public RelayStoredPayload load(String digest) {
    RelayStoredPayload found = load(List.of(digest)).get(digest);
    if (found == null) throw new IllegalStateException("Relay payload not found");
    return found;
  }

  @Override
  public Map<String, RelayStoredPayload> load(Collection<String> digests) {
    Objects.requireNonNull(digests, "digests");
    var distinct = new LinkedHashSet<>(digests);
    if (distinct.isEmpty()) return Map.of();
    if (distinct.size() > MAX_DIGESTS)
      throw new IllegalArgumentException("at most " + MAX_DIGESTS + " digests per load");
    for (String digest : distinct) {
      if (digest == null || !DIGEST.matcher(digest).matches())
        throw new IllegalArgumentException("digest must be 64 lowercase hex characters");
    }
    var placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
    var found = new LinkedHashMap<String, RelayStoredPayload>();
    jdbc.query(
        "SELECT digest,payload::text,byte_length FROM relay_payloads WHERE digest IN ("
            + placeholders
            + ")",
        rs -> {
          found.put(
              rs.getString(1),
              new RelayStoredPayload(rs.getString(1), rs.getString(2), rs.getLong(3)));
        },
        distinct.toArray());
    return found;
  }

  /**
   * Deletes matured candidates, oldest first, one transaction each. Nothing references a payload
   * yet, so a candidate is deleted unconditionally; the reachability guards arrive with the
   * referencing columns.
   */
  @Override
  public int sweep(Instant now, int limit) {
    RelayValues.limit(limit, 1000);
    List<String> candidates =
        jdbc.queryForList(
            "SELECT digest FROM relay_payloads WHERE ejection_at<=? ORDER BY ejection_at LIMIT ?",
            String.class,
            Timestamp.from(RelayValues.time(now)),
            limit);
    int removed = 0;
    for (String digest : candidates)
      removed +=
          transactions.inTransaction(
              () -> jdbc.update("DELETE FROM relay_payloads p WHERE p.digest=?", digest));
    return removed;
  }
}
