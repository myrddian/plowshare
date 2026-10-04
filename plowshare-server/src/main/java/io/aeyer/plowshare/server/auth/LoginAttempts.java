package io.aeyer.plowshare.server.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many times a handle may fail to log in before {@link #tooMany} refuses it outright, whatever
 * password it presents next.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@link AuthController#login} hashes the presented password against a real Argon2id record —
 * or, for a handle nobody has, against {@link AuthController#DUMMY_PASSWORD_HASH} — on every
 * attempt, deliberately, so that an unknown handle and a wrong password cost the same time. That is
 * a defence against telling handles apart; it is not a defence against volume. Argon2id tuned to be
 * worth anything is tens of milliseconds a guess, which is a password-cracking rate no offline
 * attacker would accept but is nowhere near slow enough to make online guessing expensive against a
 * single account with no lockout. This class is that lockout.
 *
 * <h2>Per handle, not per caller</h2>
 *
 * <p>{@link #tooMany(String)} and {@link #record(String, boolean)} both key on the handle the
 * request presented, not on a remote address or a session. That is the interface the login endpoint
 * has a use for — it already knows the handle before it knows anything else about the caller — and
 * it is also the safer default for a single-admin server: an IP-keyed throttle is defeated by
 * anything that rotates addresses, where a handle-keyed one is not defeated by anything short of
 * guessing a different real handle, and this server has exactly one.
 *
 * <p><b>It is keyed on whatever string the request presented, whether or not {@link
 * AdminStore#byHandle} finds anything under it.</b> That is deliberate and not an oversight: if
 * failures under an unknown handle were never recorded, the presence or absence of a lockout after
 * N attempts would itself be a signal of whether the handle exists, which is exactly the oracle
 * {@link AuthController#login}'s hashing is written to close. Recording blindly means the state
 * this class holds says nothing about which handles are real — the cost is that a caller who tries
 * enough distinct nonsense handles grows this map by one entry each, unboundedly, which the next
 * section says more about.
 *
 * <h2>In memory, for one admin</h2>
 *
 * <p>A {@link ConcurrentHashMap} in this process and nothing else — no table, no row, no migration.
 * That is a real limit and not a simplification with no cost, and it is worth saying exactly what
 * it costs rather than leaving it implied:
 *
 * <ul>
 *   <li><b>a restart forgets.</b> Every failure this class has counted is gone the moment the
 *       process restarts, so an attacker who can make the server restart — or who simply waits for
 *       an operator's own restart — gets a fresh set of attempts. This server has one admin and
 *       restarts are an operator's own action, not a caller's; a design that has to survive an
 *       attacker-triggered restart is a different and larger piece of work than this slice buys;
 *   <li><b>a second instance does not share.</b> Two processes in front of the same database each
 *       keep their own count, so an attacker who can reach both effectively doubles the limit.
 *       Plowshare ships as one process against one database, and a design for N instances sharing a
 *       throttle is a table and a migration — exactly the machinery the task this class exists for
 *       says not to build; and
 *   <li><b>distinct handles used to be unbounded, and no longer are.</b> This bullet used to end
 *       here: "there is no sweep, unlike {@code TokenStore}'s, because nothing here has an expiry
 *       to sweep on the common path... which the Argon2id cost on every one of those guesses
 *       already bounds to a slow trickle." <b>That was true of one caller making attempts
 *       sequentially and false of many callers making them at once</b> — {@link PasswordHasher}'s
 *       own class note has the arithmetic: at Tomcat's default 200 request threads, 200 concurrent
 *       attempts under 200 distinct handles was roughly 3.8&nbsp;GB of concurrent native memory
 *       with nothing bounding it, on top of one entry in this map per handle with nothing bounding
 *       <em>that</em> either. {@link PasswordHasher#matches(char[], String)} now caps the memory
 *       half with its own {@code Semaphore}; {@link #sweep(Instant)} is this class's half,
 *       described below.
 * </ul>
 *
 * <h2>Bounding the map: a sweep on the write path, {@link TokenStore}'s shape for the same reason
 * </h2>
 *
 * <p>{@link #record(String, boolean)} — the only method that can grow this map — sweeps up to
 * {@value #SWEEP_BUDGET} entries of it first, dropping any whose most recent activity is more than
 * {@link #lockout} in the past. That bound is chosen rather than, say, the entry's own age, because
 * an entry a caller is actively (if unsuccessfully) retrying must never be the one a sweep removes
 * out from under it — {@code lastActivity} is stamped on every failure, so a handle under sustained
 * attack stays in the map for as long as the attack continues, and only goes stale once it stops. A
 * handle whose lockout has already expired and has seen no attempt since is exactly the garbage
 * this bound exists to collect: {@link #tooMany(String)} would answer {@code false} for it
 * regardless, so removing the record changes nothing a caller can observe.
 *
 * <p>The same class of caller this bullet describes — one that fails under enough distinct handle
 * strings to grow this map — now also drives its own cleanup: every {@link #record(String,
 * boolean)} call it makes, successful or not, sweeps a bounded slice of the map for entries idle
 * past their own lockout. Growth and shrinkage are driven by the same traffic, so this store cannot
 * grow without also sweeping, on {@link TokenStore}'s own invariant for its three maps.
 *
 * <p>If this server ever holds more than one admin behind a load balancer, or needs the lockout to
 * survive a restart, that is the day this class stops being enough — and the fix is a table, on the
 * reasoning the task instructions for this class refuse to accept as this slice's job.
 *
 * <h2>This throttle is itself a denial of service, on purpose, against the one account it protects
 * </h2>
 *
 * <p><b>Locking out an attacker and locking out the operator are the same mechanism here, because
 * there is exactly one handle to lock.</b> {@link #maxFailures} wrong attempts under the admin's
 * own handle — mistyped by the admin, or guessed by anyone else — cost that admin their own access
 * for the whole of {@link #lockout}, and this class has no notion of "the real owner trying again"
 * to exempt: a failure is a failure, whoever typed it. That is not a defect being disclosed
 * reluctantly; it is the necessary shape of any lockout on a single account, stated so the next
 * reader does not have to discover it by being locked out. What is tunable is how expensive that
 * self-inflicted lockout is allowed to be — see the next section — and what is not tunable is a
 * bypass for the legitimate admin, because building one is building a bypass for whoever guessed
 * the password too.
 *
 * <h2>The numbers are {@link AuthProperties} keys, not constants here</h2>
 *
 * <p>{@link #maxFailures} consecutive failures under one handle lock it out for {@link #lockout},
 * both passed in by the caller — {@link AuthConfig#loginAttempts(AuthProperties)} reads {@link
 * AuthProperties#getLoginMaxFailures()} and {@link AuthProperties#getLoginLockout()}, bound the
 * same {@code ${VAR:default}} way every other number on that class is — rather than fixed here,
 * precisely because of the denial of service the section above names: an operator who finds the
 * shipped five-failures-per-fifteen-minutes too easy to trip, or decides it is not strict enough,
 * moves it without a rebuild. A success clears the count outright — {@link #record(String,
 * boolean)} with {@code ok=true} removes the entry rather than decrementing it — so a genuine login
 * is never one stray failure away from a lockout it would otherwise be approaching. A lockout that
 * has expired is not merely ignored by {@link #tooMany}; the next {@link #record} treats the
 * carried-over count as zero rather than resuming it, so one failure right after a lockout expires
 * does not immediately re-arm it on the strength of attempts from before the lockout even started.
 */
public final class LoginAttempts {

  /**
   * {@code lockedUntil} is null until {@link #maxFailures} is reached, on the same reasoning a
   * sentinel is avoided elsewhere in this package: a null here means exactly "not locked", and
   * nothing derives an instant from it while it is null. {@code lastActivity} is stamped on every
   * failure and is what {@link #sweep(Instant)} ages an entry against — see the class note on
   * bounding the map for why activity and not the entry's original creation is the clock this
   * bounds against.
   */
  private record State(int failures, Instant lockedUntil, Instant lastActivity) {}

  /**
   * How many entries {@link #sweep(Instant)} examines per call, {@link TokenStore#SWEEP_BUDGET}'s
   * own reasoning applied to this map: a bound on how much of a pause any one caller absorbs, sized
   * against a map this server expects to hold a handful of live entries for its one real admin plus
   * however many an attacker is currently trying — not against a map with millions of rows.
   */
  private static final int SWEEP_BUDGET = 1024;

  private final Clock clock;

  private final int maxFailures;

  private final Duration lockout;

  private final Map<String, State> attempts = new ConcurrentHashMap<>();

  /**
   * @param clock where the instants that decide whether a lockout has expired come from. A
   *     parameter so {@code LoginAttemptsTest} can drive a fifteen-minute lockout without a test
   *     that sleeps one out; production passes {@link Clock#systemUTC()} — see {@link
   *     AuthConfig#loginAttempts(AuthProperties)}.
   * @param maxFailures consecutive failures under one handle before {@link #tooMany} refuses it.
   *     See the class note on why this is a constructor argument rather than a constant: it is also
   *     the size of the denial of service this throttle trades for.
   * @param lockout how long a handle stays locked out once {@code maxFailures} is reached
   * @throws IllegalArgumentException if {@code maxFailures} is not positive or {@code lockout} is
   *     zero or negative — either would mean this class either never throttles anything or does
   *     nothing but throttle, and both are configuration defects worth refusing at construction
   *     rather than discovering at the first login
   */
  public LoginAttempts(Clock clock, int maxFailures, Duration lockout) {
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maxFailures < 1) {
      throw new IllegalArgumentException(
          "maxFailures must be at least 1; "
              + maxFailures
              + " would never lock anything"
              + " out, which is not a throttle");
    }
    this.maxFailures = maxFailures;
    Objects.requireNonNull(lockout, "lockout");
    if (lockout.isZero() || lockout.isNegative()) {
      throw new IllegalArgumentException(
          "lockout must be positive; a lockout of zero or less refuses nothing the"
              + " instant it would start");
    }
    this.lockout = lockout;
  }

  /**
   * Whether {@code handle} is currently locked out.
   *
   * @param handle whatever string the login request presented, real admin or not — see the class
   *     note on why this is not filtered to known handles
   * @return true if {@link #maxFailures} consecutive failures were recorded under this handle and
   *     {@link #lockout} has not yet passed since
   */
  public boolean tooMany(String handle) {
    State state = attempts.get(handle);
    return state != null
        && state.lockedUntil() != null
        && clock.instant().isBefore(state.lockedUntil());
  }

  /**
   * Record the outcome of one login attempt under {@code handle}.
   *
   * <p>A success clears the entry outright. A failure increments the count — from zero if there was
   * no prior entry, or if the prior entry's lockout has already expired — and, on reaching {@link
   * #maxFailures}, records the instant {@link #tooMany} starts refusing until.
   *
   * @param handle the same string {@link #tooMany(String)} will be asked about
   * @param ok whether the attempt this call reports actually authenticated
   */
  public void record(String handle, boolean ok) {
    Instant now = clock.instant();
    // Before either branch below, on TokenStore's own placement rule for
    // its three maps: this is the one method that can grow this one, so
    // it is also the one that sweeps it. See the class note on bounding
    // the map.
    sweep(now);
    if (ok) {
      attempts.remove(handle);
      return;
    }
    attempts.compute(
        handle,
        (key, prior) -> {
          int carried = prior == null || expired(prior, now) ? 0 : prior.failures();
          int failures = carried + 1;
          Instant lockedUntil = failures >= maxFailures ? now.plus(lockout) : null;
          return new State(failures, lockedUntil, now);
        });
  }

  /** Whether {@code state}'s lockout, if it had one, has already passed. */
  private static boolean expired(State state, Instant now) {
    return state.lockedUntil() != null && !now.isBefore(state.lockedUntil());
  }

  /**
   * Drop entries this map no longer needs: at most {@value #SWEEP_BUDGET} of them, each dropped
   * only if its own lockout window — measured from {@code lastActivity}, not from when the entry
   * was first created — has fully passed with no failure recorded since. See the class note on
   * bounding the map for why activity and not age is the clock this checks, and for why {@link
   * #record(String, boolean)} is where this is called from rather than {@link #tooMany(String)},
   * {@code TokenStore}'s own reason for the same split: this is the write path, and a per-request
   * read path should stay the two map reads and a compare it already is.
   *
   * <p>{@link Map#remove(Object, Object)}, value-checked, on {@code TokenStore}'s own reasoning for
   * the same call: a plain {@code remove(key)} could drop an entry a concurrent failure just
   * replaced with a fresh one, and the iterator's own {@code remove()} would do exactly that
   * without checking. Iterating while {@link #record(String, boolean)} on another thread writes is
   * safe on {@code ConcurrentHashMap}'s own guarantee — those iterators are weakly consistent and
   * never throw {@code ConcurrentModificationException}.
   */
  private void sweep(Instant now) {
    int examined = 0;
    Iterator<Map.Entry<String, State>> entries = attempts.entrySet().iterator();
    while (entries.hasNext() && examined++ < SWEEP_BUDGET) {
      Map.Entry<String, State> entry = entries.next();
      State state = entry.getValue();
      if (!now.isBefore(state.lastActivity().plus(lockout))) {
        attempts.remove(entry.getKey(), state);
      }
    }
  }

  /**
   * How many handles this map is currently holding a record for. Package-private, for {@code
   * LoginAttemptsTest} alone — the same reason {@link TokenStore#trackedRecords()} exists: an
   * internal count is the only way a test can observe that {@link #sweep(Instant)} is actually
   * bounding this map's growth, as opposed to merely existing.
   */
  int trackedHandles() {
    return attempts.size();
  }
}
