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
   * Examines matured candidates, oldest first, one transaction each. Reachability is the only
   * authority: the delete itself carries the guard, so a publication or admission that references
   * the digest, including one committed after the candidate was selected, keeps its row. The
   * foreign keys are the backstop, and they do fire in one window the guard cannot close: a
   * reference that commits after the guard's {@code NOT EXISTS} has been evaluated but before the
   * delete acquires its row lock. The delete waits on the lock the referencing insert's own
   * foreign-key check holds, proceeds when it is released, and the {@code NO ACTION} check then
   * finds the committed row and raises. The payload is still never lost -- the transaction for that
   * digest rolls back -- but the exception ends the pass, leaving its remaining candidates for the
   * next one. {@code RelayMaintenance} logs and retries on the following interval. This is verified
   * in {@code RelayPayloadStoreDatabaseTest}.
   *
   * <p>A candidate that survives is rescheduled, otherwise it would match the candidate query on
   * every pass. {@code ejection_at} only defers the next examination and never authorizes a
   * deletion, so a wrong date costs at most one wasted examination or one more window of
   * unreachable bytes. Retention also runs by {@code max_records} and as a sweep, and cascades from
   * topics, subscriptions and projects remove references unobserved, which is why the date is a
   * lower bound on removal and why the deferral ceiling exists.
   */
  @Override
  public int sweep(Instant now, int limit) {
    RelayValues.limit(limit, 1000);
    Instant at = RelayValues.time(now);
    List<String> candidates =
        jdbc.queryForList(
            "SELECT digest FROM relay_payloads WHERE ejection_at<=? ORDER BY ejection_at LIMIT ?",
            String.class,
            Timestamp.from(at),
            limit);
    int removed = 0;
    for (String digest : candidates)
      removed += transactions.inTransaction(() -> reclaimOrDefer(digest, at));
    return removed;
  }

  /** Deletes the payload when unreachable, otherwise moves its next examination out. */
  private int reclaimOrDefer(String digest, Instant now) {
    int deleted =
        jdbc.update(
            """
            DELETE FROM relay_payloads p WHERE p.digest=?
              AND NOT EXISTS (SELECT 1 FROM relay_publications r WHERE r.payload_digest=p.digest)
              AND NOT EXISTS (SELECT 1 FROM relay_admissions a WHERE a.payload_digest=p.digest)
            """,
            digest);
    if (deleted > 0) return deleted;
    jdbc.update(
        "UPDATE relay_payloads SET ejection_at=? WHERE digest=?",
        Timestamp.from(nextExamination(digest, now)),
        digest);
    return 0;
  }

  /**
   * The furthest-out earliest possible removal among the references: {@code published_at +
   * retention} for a publication, and for a settled admission the same cut {@code pruneSettled}
   * applies (the admission and every delivery older than the retention). The second column reports
   * an admission with a delivery that is not terminal, whose removal is unbounded.
   */
  private Horizon horizon(String digest) {
    return jdbc.query(
            """
            SELECT GREATEST(
                (SELECT max(r.published_at + t.retention_seconds * interval '1 second')
                   FROM relay_publications r
                   JOIN relay_topics t ON t.scope_key=r.scope_key AND t.name=r.topic
                  WHERE r.payload_digest=?),
                (SELECT max(GREATEST(a.admitted_at, COALESCE(
                            (SELECT max(d.updated_at) FROM relay_deliveries d
                              WHERE d.scope_key=a.scope_key AND d.topic=a.topic
                                AND d.subscriber=a.subscriber
                                AND d.publication_position=a.publication_position),
                            a.admitted_at)) + ? * interval '1 second')
                   FROM relay_admissions a
                  WHERE a.payload_digest=? AND NOT EXISTS (
                        SELECT 1 FROM relay_deliveries d
                         WHERE d.scope_key=a.scope_key AND d.topic=a.topic
                           AND d.subscriber=a.subscriber
                           AND d.publication_position=a.publication_position
                           AND d.state NOT IN ('ACCEPTED','FAILED','ABANDONED')))),
                EXISTS (SELECT 1 FROM relay_admissions a
                         WHERE a.payload_digest=? AND EXISTS (
                               SELECT 1 FROM relay_deliveries d
                                WHERE d.scope_key=a.scope_key AND d.topic=a.topic
                                  AND d.subscriber=a.subscriber
                                  AND d.publication_position=a.publication_position
                                  AND d.state NOT IN ('ACCEPTED','FAILED','ABANDONED')))
            """,
            (rs, row) -> {
              Timestamp furthest = rs.getTimestamp(1);
              return new Horizon(furthest == null ? null : furthest.toInstant(), rs.getBoolean(2));
            },
            digest,
            settledRetention.toSeconds(),
            digest,
            digest)
        .get(0);
  }

  private Instant nextExamination(String digest, Instant now) {
    Horizon horizon = horizon(digest);
    Instant furthest = horizon.furthestRemoval();
    // An unsettled admission has no removal date, so it is examined again after the staging window.
    Instant unsettled = now.plus(stagingWindow);
    if (horizon.unsettledAdmission() && (furthest == null || furthest.isBefore(unsettled)))
      furthest = unsettled;
    if (furthest == null) furthest = unsettled;
    Instant capped = now.plus(deferralCeiling);
    if (furthest.isAfter(capped)) furthest = capped;
    // Truncate DOWN, the opposite of store(). There the staging window is a minimum protection and
    // so rounds up; here the horizon is a maximum deferral. A date that is too early costs one
    // wasted examination, while one that is too late leaves unreachable bytes a whole bucket
    // longer.
    Instant bucket = furthest.truncatedTo(ChronoUnit.HOURS);
    // The result must be strictly in the future. A publication whose retention has elapsed but not
    // yet been pruned still references the payload, and a past date would keep it in the candidate
    // set on every pass, turning a referenced payload into permanent sweep work.
    Instant nextHour = now.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
    return bucket.isBefore(nextHour) ? nextHour : bucket;
  }

  private record Horizon(Instant furthestRemoval, boolean unsettledAdmission) {}
}
