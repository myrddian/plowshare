package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
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
 * <p>{@link #store} is a single idempotent statement and does not open a transaction of its own, so
 * it commits immediately only when the calling thread has none open. A caller already inside a
 * transaction (for example {@code RelayInformationEvents.publish}, which publishes in its caller's
 * transaction) holds the row lock until that outer transaction ends. A repeat of an existing digest
 * re-arms its staging window and locks the row; a repeat of a digest already deferred past that
 * window writes no row version.
 */
public final class JdbcRelayPayloadStore implements RelayPayloadStore {
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final int MAX_DIGESTS = 1000;
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Duration stagingWindow;
  private final Duration deferralCeiling;
  private final Duration settledRetention;

  /**
   * @param stagingWindow minimum protection for a stored but not yet referenced payload
   * @param deferralCeiling longest a sweep may defer the next examination of a referenced payload
   * @param settledRetention how long a settled admission is kept; must match the cut {@code
   *     JdbcRelayDeliveryRepository#pruneSettled} applies
   */
  public JdbcRelayPayloadStore(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Duration stagingWindow,
      Duration deferralCeiling,
      Duration settledRetention) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.stagingWindow = Objects.requireNonNull(stagingWindow, "stagingWindow");
    if (stagingWindow.isNegative() || stagingWindow.isZero())
      throw new IllegalArgumentException("staging window must be positive");
    this.deferralCeiling = Objects.requireNonNull(deferralCeiling, "deferralCeiling");
    this.settledRetention = Objects.requireNonNull(settledRetention, "settledRetention");
    if (deferralCeiling.compareTo(Duration.ofHours(1)) < 0)
      throw new IllegalArgumentException("deferral ceiling must be at least one hour");
    if (settledRetention.isNegative() || settledRetention.isZero())
      throw new IllegalArgumentException("settled retention must be positive");
  }

  @Override
  public String store(RelayPayload payload, Instant now) {
    String json = RelayPayloadCodec.write(payload);
    String digest = RelayPort.hash(json);
    // Round up to the next hour boundary so the staging window is a guaranteed minimum: the row
    // is never sweep-eligible before now + window. Bucketing by hour keeps many payloads sharing
    // one ejection_at, so a sweep pass is a batch rather than a scan.
    Instant earliest = RelayValues.time(now).plus(stagingWindow);
    Instant ejection = earliest.truncatedTo(ChronoUnit.HOURS);
    if (ejection.isBefore(earliest)) ejection = ejection.plus(Duration.ofHours(1));
    // A repeat of an existing digest must re-arm the staging window, not leave it as it is. The
    // caller's own reference does not yet exist -- it is inserted after this commits, after a topic
    // lock and a locked read -- so for a row whose earlier references were pruned, an unmodified
    // ejection_at leaves it a matured sweep candidate for that whole window, with no lock held. A
    // sweep in that window sees no reference, deletes, and the caller's insert then fails its
    // foreign key. DO UPDATE moves the date out, and the sweep's delete requires the date to still
    // be matured, so a row re-armed here cannot be reclaimed by a pass that had already selected it
    // as a candidate. The sweep's reachability guard alone would not have sufficed: the lock this
    // statement takes only delays a delete that already selected the digest, and when that delete
    // resumes, under READ COMMITTED it follows the update chain and re-evaluates its predicate. If
    // the predicate named only the digest and the two NOT EXISTS subqueries it would still find no
    // reference -- the caller's publication insert comes later, after the topic lock -- and delete.
    // The date in the predicate is what makes the re-arm effective against an in-flight pass.
    //
    // The conflict clause is conditional so that a hot digest already deferred past this ejection
    // writes no new row version: the predicate is false and the update is skipped. GREATEST is
    // redundant with that predicate and is kept so the statement never moves a date backwards on
    // its own terms.
    jdbc.update(
        """
        INSERT INTO relay_payloads(digest,payload,byte_length,ejection_at)
        VALUES (?,?::jsonb,octet_length((?::jsonb)::text),?)
        ON CONFLICT (digest) DO UPDATE
          SET ejection_at=GREATEST(relay_payloads.ejection_at,excluded.ejection_at)
          WHERE relay_payloads.ejection_at<excluded.ejection_at
        """,
        digest,
        json,
        json,
        Timestamp.from(ejection));
    return digest;
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
    var placeholders = String.join(",", Collections.nCopies(distinct.size(), "?"));
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
   * Locks a bounded set before taking a fresh READ COMMITTED snapshot of its references. FOR UPDATE
   * conflicts with the foreign key's KEY SHARE lock: in-flight append/admission rows are skipped,
   * and another sweeper cannot examine the same payload. A separate statement after selection is
   * essential; a CTE in the selection statement would keep its pre-lock snapshot. The live date
   * predicate and both foreign keys remain deletion fences. No payload bytes are selected.
   *
   * <p>All candidates commit together. A failure rolls the batch back and the next scheduled pass
   * can retry; there is no per-row fallback or unbounded drain. The caller must supply a real
   * transaction boundary in production. Sweeps must not join a REPEATABLE READ transaction.
   */
  @Override
  public SweepResult sweepBatch(Instant now, int limit) {
    RelayValues.limit(limit, 1000);
    Instant at = RelayValues.time(now);
    return transactions.inTransaction(() -> sweepLocked(at, limit));
  }

  private SweepResult sweepLocked(Instant now, int limit) {
    // Bound physical work and lock retention even if a plan regresses or another writer stalls.
    // Settings are transaction-local and never change the pool's session defaults.
    jdbc.execute(
        "SET LOCAL statement_timeout='2s'; SET LOCAL lock_timeout='100ms'; SET LOCAL jit=off");
    List<String> candidates =
        jdbc.queryForList(
            """
            SELECT digest FROM relay_payloads WHERE ejection_at<=?
            ORDER BY ejection_at LIMIT ? FOR UPDATE SKIP LOCKED
            """,
            String.class,
            Timestamp.from(now),
            limit);
    if (candidates.isEmpty()) return new SweepResult(0, 0, java.util.Optional.empty());
    String placeholders = String.join(",", Collections.nCopies(candidates.size(), "(?::text)"));
    var arguments = new java.util.ArrayList<Object>(candidates);
    arguments.add(settledRetention.toSeconds());
    arguments.add(Timestamp.from(now.plus(stagingWindow)));
    arguments.add(Timestamp.from(now.plus(deferralCeiling)));
    arguments.add(Timestamp.from(now));
    arguments.add(Timestamp.from(now));
    arguments.add(Timestamp.from(now));
    arguments.add(Timestamp.from(now));
    arguments.add(Timestamp.from(now));
    // Aggregate only references to the locked digest set. Each admission's deliveries are folded
    // once; the same facts decide both reachability and the next examination date. The mutations
    // affect disjoint rows, so the data-modifying CTEs cannot update a row twice.
    return Objects.requireNonNull(
        jdbc.queryForObject(
            """
            WITH chosen(digest) AS MATERIALIZED (VALUES %s), publications AS MATERIALIZED (
              SELECT r.payload_digest,max(r.published_at+t.retention_seconds*interval '1 second') AS horizon
              FROM chosen c JOIN relay_publications r ON r.payload_digest=c.digest
              JOIN relay_topics t ON t.scope_key=r.scope_key AND t.name=r.topic
              GROUP BY r.payload_digest
            ), admissions AS MATERIALIZED (
              SELECT a.payload_digest,a.admitted_at,
                max(d.updated_at) AS updated_at,
                coalesce(bool_or(d.state NOT IN ('ACCEPTED','FAILED','ABANDONED')),false) AS unsettled
              FROM chosen c JOIN relay_admissions a ON a.payload_digest=c.digest
              LEFT JOIN relay_deliveries d ON d.scope_key=a.scope_key AND d.topic=a.topic
                AND d.subscriber=a.subscriber AND d.publication_position=a.publication_position
              GROUP BY a.payload_digest,a.scope_key,a.topic,a.subscriber,a.publication_position,a.admitted_at
            ), admission_horizons AS MATERIALIZED (
              SELECT payload_digest,
                max(GREATEST(admitted_at,coalesce(updated_at,admitted_at))
                    + ? * interval '1 second') FILTER (WHERE NOT unsettled) AS horizon,
                bool_or(unsettled) AS unsettled
              FROM admissions GROUP BY payload_digest
            ), facts AS MATERIALIZED (
              SELECT c.digest,(p.payload_digest IS NOT NULL OR a.payload_digest IS NOT NULL) AS referenced,
                LEAST(GREATEST(p.horizon,a.horizon,
                  CASE WHEN coalesce(a.unsettled,false) THEN ?::timestamptz END),?::timestamptz) AS capped
              FROM chosen c LEFT JOIN publications p ON p.payload_digest=c.digest
              LEFT JOIN admission_horizons a ON a.payload_digest=c.digest
            ), scheduled AS MATERIALIZED (
              SELECT digest,referenced,
                GREATEST(?::timestamptz+interval '1 minute', capped
                  - LEAST(interval '1 hour',GREATEST(capped-?::timestamptz,interval '0')/4)
                    * ((('x'||substr(digest,1,8))::bit(32)::bigint)::double precision/4294967296.0)) AS next_at
              FROM facts
            ), removed AS (
              DELETE FROM relay_payloads p USING scheduled s
              WHERE p.digest=s.digest AND NOT s.referenced AND p.ejection_at<=?
              RETURNING p.digest
            ), deferred AS (
              UPDATE relay_payloads p SET ejection_at=GREATEST(p.ejection_at,s.next_at)
              FROM scheduled s WHERE p.digest=s.digest AND s.referenced AND p.ejection_at<=?
              RETURNING p.digest
            )
            SELECT (SELECT count(*) FROM removed),(SELECT count(*) FROM deferred),
              (SELECT p.ejection_at FROM relay_payloads p WHERE p.ejection_at<=?
                AND NOT EXISTS (SELECT 1 FROM chosen c WHERE c.digest=p.digest)
                ORDER BY p.ejection_at LIMIT 1)
            """
                .formatted(placeholders),
            (row, index) -> {
              int removed = row.getInt(1);
              if (removed + row.getInt(2) != candidates.size())
                throw new IllegalStateException(
                    "Relay payload sweep did not account for its locked batch");
              Timestamp oldest = row.getTimestamp(3);
              return new SweepResult(
                  candidates.size(),
                  removed,
                  java.util.Optional.ofNullable(oldest).map(Timestamp::toInstant));
            },
            arguments.toArray()));
  }
}
