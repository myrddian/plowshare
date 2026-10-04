package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link PasswordHasher}, over its own {@code char[]} API and no container: nothing here touches a
 * database, so a plain unit test is the whole instrument this class needs.
 */
class PasswordHasherTest {

  private final PasswordHasher hasher = new PasswordHasher();

  @Test
  void a_password_verifies_against_its_own_hash() {
    String hash = hasher.hash("correct horse battery staple".toCharArray());

    assertTrue(hasher.matches("correct horse battery staple".toCharArray(), hash));
  }

  @Test
  void a_different_password_does_not_verify() {
    String hash = hasher.hash("correct horse battery staple".toCharArray());

    assertFalse(hasher.matches("wrong password entirely".toCharArray(), hash));
  }

  /**
   * If these are equal the hash is unsalted, and one leaked row would break every account that
   * shares a password.
   */
  @Test
  void two_hashes_of_one_password_differ_because_the_salt_does() {
    String first = hasher.hash("correct horse battery staple".toCharArray());
    String second = hasher.hash("correct horse battery staple".toCharArray());

    assertNotEquals(
        first,
        second,
        "two hashes of the same password must differ, or the salt is not doing"
            + " anything and one leaked row breaks every account sharing this"
            + " password");
    // And both still verify the password they were made from — the point
    // of a salt is that the encoded hash carries it, not that it changes
    // what verifies.
    assertTrue(hasher.matches("correct horse battery staple".toCharArray(), first));
    assertTrue(hasher.matches("correct horse battery staple".toCharArray(), second));
  }

  /**
   * Argon2's own API takes {@code char[]} so the caller can clear it. A {@code String} would sit in
   * the heap until GC and show up in a dump.
   */
  @Test
  void the_password_array_is_wiped_after_hashing() {
    char[] password = "correct horse battery staple".toCharArray();

    hasher.hash(password);

    assertTrue(
        PasswordHasher.isWiped(password),
        "hash() must zero the caller's array once it is done with it, or the"
            + " plaintext password sits in the heap for as long as this array"
            + " survives");
  }

  /**
   * {@link PasswordHasher#matches} wipes its own array too, on the same hygiene the class note
   * argues for {@link PasswordHasher#hash}.
   */
  @Test
  void the_password_array_is_wiped_after_matching() {
    String hash = hasher.hash("correct horse battery staple".toCharArray());
    char[] attempt = "correct horse battery staple".toCharArray();

    hasher.matches(attempt, hash);

    assertTrue(PasswordHasher.isWiped(attempt));
  }

  @Test
  void hashing_a_null_or_empty_password_is_refused() {
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(new char[0]));
  }

  // --- FIX 3: a bound on concurrent hashing ---------------------------------

  /**
   * The finding this pins: unauthenticated Argon2id amplification. {@link LoginAttempts} throttles
   * by handle, so many callers under distinct handles used to hash concurrently without limit. This
   * drives more concurrent {@link PasswordHasher#hash(char[])} calls than {@link
   * PasswordHasher#maxConcurrentHashes()} permits, and polls {@link PasswordHasher#inFlight()} from
   * a separate thread throughout, asserting the observed maximum never exceeds the permit count.
   *
   * <p><b>The poll is a race against the workers by construction</b> — there is no instant at which
   * a test can be sure every worker is inside the guarded section at once — so this cannot prove
   * {@code inFlight()} never exceeds the bound for a single instant nothing here happened to
   * sample. What it can and does prove, because Argon2id here costs tens of milliseconds a call and
   * the poll runs in a tight loop for the whole duration of the run, is that the maximum
   * <em>observed</em> across many samples taken throughout the whole run never exceeds the bound —
   * which is what a semaphore actually guarantees, and what the assertion checks for. The second
   * assertion, that the observed maximum is at least 2, is what rules out the vacuous case where
   * every worker happened to run one at a time before this thread ever sampled — proving the
   * fixture created genuine concurrency rather than an accidentally serial run.
   */
  @Test
  void concurrent_hashing_never_exceeds_the_permit_bound() throws Exception {
    int workers = PasswordHasher.maxConcurrentHashes() + 6;
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    AtomicInteger maxObserved = new AtomicInteger();
    AtomicBoolean done = new AtomicBoolean();
    Thread poller =
        new Thread(
            () -> {
              while (!done.get()) {
                maxObserved.updateAndGet(current -> Math.max(current, hasher.inFlight()));
              }
            });
    poller.start();
    try {
      List<Callable<String>> tasks = new ArrayList<>();
      for (int i = 0; i < workers; i++) {
        String password = "concurrent-password-" + i;
        tasks.add(() -> hasher.hash(password.toCharArray()));
      }
      List<Future<String>> results = pool.invokeAll(tasks);
      for (Future<String> result : results) {
        result.get();
      }
    } finally {
      done.set(true);
      poller.join();
      pool.shutdown();
    }

    assertTrue(
        maxObserved.get() <= PasswordHasher.maxConcurrentHashes(),
        "observed "
            + maxObserved.get()
            + " concurrent Argon2id calls in flight at once,"
            + " which exceeds the "
            + PasswordHasher.maxConcurrentHashes()
            + "-permit bound the concurrency semaphore is supposed to enforce");
    assertTrue(
        maxObserved.get() >= 2,
        "never observed more than one Argon2id call in flight at once across "
            + workers
            + " concurrent workers, so this run proved nothing about concurrency —"
            + " observed max was "
            + maxObserved.get());
  }

  /**
   * Absence and malformation both answer false rather than throw, so a login handler never has to
   * special-case this method.
   */
  @Test
  void matching_against_a_blank_or_malformed_hash_is_false_rather_than_thrown() {
    assertFalse(hasher.matches("anything".toCharArray(), null));
    assertFalse(hasher.matches("anything".toCharArray(), ""));
    assertFalse(hasher.matches("anything".toCharArray(), "not an argon2 hash"));
  }
}
