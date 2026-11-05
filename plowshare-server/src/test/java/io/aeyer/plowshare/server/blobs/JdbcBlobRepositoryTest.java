package io.aeyer.plowshare.server.blobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL migration, uniqueness, transaction rollback and claim/collection lock assertions. */
@Tag("full-db")
@Testcontainers
class JdbcBlobRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static TransactionTemplate transactions;
  static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
  static final BlobSource SOURCE = source("projects/1", "a", "first.bin");
  static final BlobClaim FIRST = claim("first");
  static final BlobClaim SECOND = claim("second");
  JdbcBlobRepository repository;

  @BeforeAll
  static void migrate() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).target("224").load().migrate();
    jdbc = new JdbcTemplate(data);
    jdbc.update("INSERT INTO projects(name,workspace) VALUES('pre-blob-upgrade','fixture')");
    Flyway.configure().dataSource(data).load().migrate();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE name='pre-blob-upgrade'", Integer.class));
    transactions = new TransactionTemplate(new DataSourceTransactionManager(data));
  }

  @BeforeEach
  void fixture() {
    jdbc.update("DELETE FROM binary_blob_claims");
    jdbc.update("DELETE FROM binary_blob_aliases");
    jdbc.update("DELETE FROM binary_blobs");
    repository = new JdbcBlobRepository(jdbc);
  }

  static BlobClaim claim(String owner) {
    return new BlobClaim("fixture", owner, 0, BlobClaim.Retention.RELEASABLE);
  }

  static BlobSource source(String scope, String digest, String file) {
    return new BlobSource(new BlobKey(scope, digest.repeat(64)), scope + "/images/" + file, 128);
  }

  void adopt(BlobSource source, String alias, BlobClaim claim) {
    transactions.execute(
        status -> {
          repository.adopt(source, alias, claim, NOW);
          return null;
        });
  }

  int count(String table) {
    // Table choice is test-only and every call uses a literal owned by this fixture.
    return java.util.Objects.requireNonNull(
        jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
  }

  @Test
  void adoption_and_claim_retries_preserve_one_location_and_slot() {
    adopt(SOURCE, "image", FIRST);
    adopt(SOURCE, "image", FIRST);
    transactions.execute(
        status -> {
          repository.acquire(SOURCE.key(), SECOND);
          return null;
        });
    var entry = repository.bound("projects/1", "image").orElseThrow();
    assertEquals(SOURCE, entry.source());
    assertTrue(repository.readable(entry, FIRST));
    assertTrue(repository.readable(entry, SECOND));
    assertEquals(1, count("binary_blobs"));
    assertEquals(2, count("binary_blob_claims"));
    assertThrows(IllegalStateException.class, () -> repository.acquire(SOURCE.key(), FIRST));
  }

  @Test
  void changed_claim_or_alias_rolls_back_and_does_not_reassign_identity() {
    adopt(SOURCE, "image", FIRST);
    var other = source("projects/1", "b", "other.bin");
    assertThrows(BlobUnavailableException.class, () -> adopt(other, "second", FIRST));
    assertEquals(1, count("binary_blobs"));
    assertThrows(BlobUnavailableException.class, () -> adopt(other, "image", SECOND));
    assertEquals(1, count("binary_blob_aliases"));
    assertEquals(SOURCE, repository.bound("projects/1", "image").orElseThrow().source());
  }

  @Test
  void failed_owner_transaction_rolls_back_adoption_and_release() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  repository.adopt(SOURCE, "image", FIRST, NOW);
                  throw new IllegalStateException("fixture rollback");
                }));
    assertEquals(0, count("binary_blobs"));
    adopt(SOURCE, "image", FIRST);
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                status -> {
                  repository.release(SOURCE.key(), FIRST, NOW);
                  throw new IllegalStateException("fixture rollback");
                }));
    assertEquals(1, count("binary_blob_claims"));
    assertTrue(transactions.execute(status -> repository.deletions(NOW, 10)).isEmpty());
  }

  @Test
  void scopes_are_independent_and_pinned_claims_cannot_be_disguised_as_releasable() {
    var pin = new BlobClaim("image-retention", "image", 0, BlobClaim.Retention.PINNED);
    adopt(SOURCE, "image", pin);
    var foreign = source("projects/2", "a", "first.bin");
    adopt(foreign, "image", FIRST);
    assertEquals(2, count("binary_blobs"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.execute(
                status -> {
                  repository.release(SOURCE.key(), pin, NOW);
                  return null;
                }));
    var disguised =
        new BlobClaim(pin.kind(), pin.owner(), pin.slot(), BlobClaim.Retention.RELEASABLE);
    assertThrows(
        BlobUnavailableException.class,
        () ->
            transactions.execute(
                status -> {
                  repository.release(SOURCE.key(), disguised, NOW);
                  return null;
                }));
    assertEquals(2, count("binary_blob_claims"));
  }

  @Test
  void last_release_marks_one_generation_and_cannot_be_reattached_until_verified_registration() {
    adopt(SOURCE, "image", FIRST);
    transactions.execute(
        status -> {
          repository.release(SOURCE.key(), FIRST, NOW);
          return null;
        });
    assertTrue(
        transactions.execute(status -> repository.deletions(NOW.minusNanos(1000), 10)).isEmpty());
    var marker = transactions.execute(status -> repository.deletions(NOW, 10)).getFirst();
    assertTrue(repository.deleting(marker));
    assertThrows(
        BlobUnavailableException.class,
        () ->
            transactions.execute(
                status -> {
                  repository.acquire(SOURCE.key(), SECOND);
                  return null;
                }));
    assertThrows(BlobUnavailableException.class, () -> adopt(SOURCE, "image", SECOND));
    transactions.execute(
        status -> {
          repository.deleted(marker);
          return null;
        });
    assertFalse(repository.readable(repository.bound("projects/1", "image").orElseThrow(), FIRST));
    adopt(SOURCE, "image", SECOND);
    assertEquals(2, repository.bound("projects/1", "image").orElseThrow().generation());
    assertFalse(repository.deleting(marker));
  }

  @Test
  void a_locked_new_claim_excludes_collection_without_blocking_the_batch() throws Exception {
    adopt(SOURCE, "image", FIRST);
    transactions.execute(
        status -> {
          repository.release(SOURCE.key(), FIRST, NOW);
          return null;
        });
    var acquired = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var owner =
          executor.submit(
              () ->
                  transactions.execute(
                      status -> {
                        repository.acquire(SOURCE.key(), SECOND);
                        acquired.countDown();
                        await(finish);
                        return true;
                      }));
      try {
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        var pass =
            executor.submit(() -> transactions.execute(status -> repository.deletions(NOW, 10)));
        assertTrue(pass.get(5, TimeUnit.SECONDS).isEmpty());
      } finally {
        finish.countDown();
      }
      assertTrue(owner.get(5, TimeUnit.SECONDS));
    }
    assertTrue(transactions.execute(status -> repository.deletions(NOW, 10)).isEmpty());
    assertEquals(1, count("binary_blob_claims"));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }
}
