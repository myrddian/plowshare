package io.aeyer.plowshare.server.blobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Registry/claim SQL only; all mutators join the owning operation's short transaction. */
public final class JdbcBlobRepository implements BlobRepository {
  private final JdbcTemplate jdbc;

  public JdbcBlobRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public void adopt(BlobSource source, String alias, BlobClaim claim, Instant now) {
    transaction();
    BlobRepository.requireAlias(alias);
    register(source, now);
    bind(source, alias, claim);
  }

  @Override
  public void register(BlobSource source, Instant now) {
    transaction();
    int inserted =
        jdbc.update(
            """
        INSERT INTO binary_blobs(scope,sha256,file,byte_length,state,unclaimed_at)
        VALUES(?,?,?,?,'READY',?) ON CONFLICT(scope,sha256) DO NOTHING
        """,
            source.key().scope(),
            source.key().sha256(),
            source.file(),
            source.byteLength(),
            Timestamp.from(now));
    if (inserted == 0) {
      Entry entry = lock(source.key());
      if (!entry.source().equals(source) || entry.state() == State.DELETING) {
        throw new BlobUnavailableException();
      }
      if (entry.state() == State.DELETED) {
        changed(
            jdbc.update(
                """
            UPDATE binary_blobs SET state='READY',generation=generation+1,unclaimed_at=?
            WHERE scope=? AND sha256=? AND state='DELETED'
            """,
                Timestamp.from(now),
                source.key().scope(),
                source.key().sha256()));
      }
    }
  }

  private void bind(BlobSource source, String alias, BlobClaim claim) {
    int bound =
        jdbc.update(
            """
        INSERT INTO binary_blob_aliases(scope,alias,sha256) VALUES(?,?,?)
        ON CONFLICT(scope,alias) DO NOTHING
        """,
            source.key().scope(),
            alias,
            source.key().sha256());
    if (bound == 0) {
      var original = bound(source.key().scope(), alias).orElseThrow(BlobUnavailableException::new);
      if (!original.source().key().equals(source.key())) throw new BlobUnavailableException();
    }
    acquireLocked(source.key(), claim);
  }

  @Override
  public Optional<Entry> find(BlobKey key) {
    return jdbc
        .query(
            "SELECT * FROM binary_blobs WHERE scope=? AND sha256=?",
            JdbcBlobRepository::entry,
            key.scope(),
            key.sha256())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Entry> bound(String scope, String alias) {
    BlobKey.requireScope(scope);
    BlobRepository.requireAlias(alias);
    return jdbc
        .query(
            """
        SELECT b.* FROM binary_blob_aliases a JOIN binary_blobs b
          ON b.scope=a.scope AND b.sha256=a.sha256 WHERE a.scope=? AND a.alias=?
        """,
            JdbcBlobRepository::entry,
            scope,
            alias)
        .stream()
        .findFirst();
  }

  @Override
  public boolean readable(Entry entry, BlobClaim claim) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM binary_blobs b JOIN binary_blob_claims c
          ON c.scope=b.scope AND c.sha256=b.sha256
          WHERE b.scope=? AND b.sha256=? AND b.generation=? AND b.state='READY'
            AND c.kind=? AND c.owner=? AND c.slot=? AND c.retention=?)
        """,
            Boolean.class,
            entry.source().key().scope(),
            entry.source().key().sha256(),
            entry.generation(),
            claim.kind(),
            claim.owner(),
            claim.slot(),
            claim.retention().name()));
  }

  @Override
  public void acquire(BlobKey key, BlobClaim claim) {
    transaction();
    if (lock(key).state() != State.READY) throw new BlobUnavailableException();
    acquireLocked(key, claim);
  }

  private void acquireLocked(BlobKey key, BlobClaim claim) {
    int inserted =
        jdbc.update(
            """
        INSERT INTO binary_blob_claims(scope,kind,owner,slot,sha256,retention) VALUES(?,?,?,?,?,?)
        ON CONFLICT(scope,kind,owner,slot) DO NOTHING
        """,
            key.scope(),
            claim.kind(),
            claim.owner(),
            claim.slot(),
            key.sha256(),
            claim.retention().name());
    if (inserted == 0) {
      boolean identical =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  """
          SELECT sha256=? AND retention=? FROM binary_blob_claims
          WHERE scope=? AND kind=? AND owner=? AND slot=?
          """,
                  Boolean.class,
                  key.sha256(),
                  claim.retention().name(),
                  key.scope(),
                  claim.kind(),
                  claim.owner(),
                  claim.slot()));
      if (!identical) throw new BlobUnavailableException();
    }
  }

  @Override
  public void acquire(BlobKey key, BlobClaim claim, long byteLength) {
    transaction();
    var entry = lock(key);
    if (byteLength < 1
        || entry.state() != State.READY
        || entry.source().byteLength() != byteLength) {
      throw new BlobUnavailableException();
    }
    acquireLocked(key, claim);
  }

  @Override
  public void release(BlobKey key, BlobClaim claim, Instant now) {
    transaction();
    if (claim.retention() == BlobClaim.Retention.PINNED) {
      throw new IllegalArgumentException(
          "pinned blob claims require an owning-record removal policy");
    }
    lock(key);
    int removed =
        jdbc.update(
            """
        DELETE FROM binary_blob_claims WHERE scope=? AND kind=? AND owner=? AND slot=?
          AND sha256=? AND retention='RELEASABLE'
        """,
            key.scope(),
            claim.kind(),
            claim.owner(),
            claim.slot(),
            key.sha256());
    if (removed != 0) {
      changed(
          jdbc.update(
              "UPDATE binary_blobs SET unclaimed_at=? WHERE scope=? AND sha256=?",
              Timestamp.from(now),
              key.scope(),
              key.sha256()));
    } else {
      Integer conflicting =
          jdbc.queryForObject(
              """
          SELECT count(*) FROM binary_blob_claims WHERE scope=? AND kind=? AND owner=? AND slot=?
          """,
              Integer.class,
              key.scope(),
              claim.kind(),
              claim.owner(),
              claim.slot());
      if (!Objects.equals(conflicting, 0)) throw new BlobUnavailableException();
    }
  }

  @Override
  public List<Entry> deletions(Instant eligibleThrough, int limit) {
    transaction();
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("invalid blob cleanup batch");
    // SKIP LOCKED avoids waiting on an owner transaction. Recheck claims after obtaining the lock:
    // another transaction may have attached while the candidate SELECT's snapshot was being built.
    var candidates =
        jdbc.query(
            """
        SELECT b.* FROM binary_blobs b WHERE b.state='DELETING' OR
          (b.state='READY' AND b.unclaimed_at<=? AND NOT EXISTS
            (SELECT 1 FROM binary_blob_claims c WHERE c.scope=b.scope AND c.sha256=b.sha256))
        ORDER BY b.unclaimed_at,b.scope,b.sha256 LIMIT ? FOR UPDATE OF b SKIP LOCKED
        """,
            JdbcBlobRepository::entry,
            Timestamp.from(eligibleThrough),
            limit);
    var result = new java.util.ArrayList<Entry>();
    var selected = new java.util.ArrayList<Object[]>();
    for (Entry candidate : candidates) {
      if (candidate.state() == State.DELETING) {
        result.add(candidate);
      } else {
        selected.add(
            new Object[] {candidate.source().key().scope(), candidate.source().key().sha256()});
      }
    }
    if (!selected.isEmpty()) {
      // One set-based recheck after candidate locks, not one database round-trip per object.
      var parameters =
          new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
              .addValue("keys", selected)
              .addValue("through", Timestamp.from(eligibleThrough));
      result.addAll(
          new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
              .query(
                  """
          UPDATE binary_blobs b SET state='DELETING' WHERE (scope,sha256) IN (:keys)
            AND state='READY' AND unclaimed_at<=:through AND NOT EXISTS
              (SELECT 1 FROM binary_blob_claims c WHERE c.scope=b.scope AND c.sha256=b.sha256)
          RETURNING b.*
          """,
                  parameters,
                  JdbcBlobRepository::entry));
    }
    return List.copyOf(result);
  }

  @Override
  public boolean deleting(Entry entry) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM binary_blobs WHERE scope=? AND sha256=?
          AND generation=? AND state='DELETING')
        """,
            Boolean.class,
            entry.source().key().scope(),
            entry.source().key().sha256(),
            entry.generation()));
  }

  @Override
  public void deleted(Entry entry) {
    transaction();
    changed(
        jdbc.update(
            """
        UPDATE binary_blobs SET state='DELETED' WHERE scope=? AND sha256=?
          AND generation=? AND state='DELETING'
        """,
            entry.source().key().scope(),
            entry.source().key().sha256(),
            entry.generation()));
  }

  private Entry lock(BlobKey key) {
    return jdbc
        .query(
            "SELECT * FROM binary_blobs WHERE scope=? AND sha256=? FOR UPDATE",
            JdbcBlobRepository::entry,
            key.scope(),
            key.sha256())
        .stream()
        .findFirst()
        .orElseThrow(BlobUnavailableException::new);
  }

  private static Entry entry(ResultSet row, int index) throws SQLException {
    return new Entry(
        new BlobSource(
            new BlobKey(row.getString("scope"), row.getString("sha256")),
            row.getString("file"),
            row.getLong("byte_length")),
        row.getLong("generation"),
        State.valueOf(row.getString("state")));
  }

  private static void changed(int rows) {
    if (rows != 1) throw new IllegalStateException("blob state changed concurrently");
  }

  private static void transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("blob mutation requires an owning transaction");
    }
  }
}
