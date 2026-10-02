package io.aeyer.plowshare.server.auth;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing for the one credential this server accepts that a
 * human chose: the admin password. See {@code build.gradle.kts} for why
 * argon2-jvm and not {@code spring-security-crypto}'s encoders, and see {@link
 * Tokens} for why every other secret in this server is a fast digest instead —
 * that class's whole argument is that a slow hash is wasted on a value a
 * {@code SecureRandom} chose, and this class is the one place in this server
 * where the opposite is true: a password is exactly the kind of secret a slow,
 * memory-hard hash exists to protect, because a person chose it and a person's
 * choices are guessable at scale.
 *
 * <h2>Cost parameters</h2>
 *
 * <p>19 MiB of memory, two iterations, one degree of parallelism — OWASP's
 * current baseline recommendation for Argon2id when a dedicated Argon2
 * hardware accelerator is not a threat this deployment expects, which is true
 * of a single self-hosted admin account. Unlike {@link Tokens}, which this
 * server calls on every authenticated request and therefore has to keep cheap,
 * this class is called once per login — a few tens of milliseconds and a few
 * tens of megabytes are a cost worth paying there and would not be worth
 * paying on the hot path {@link Tokens} sits on.
 *
 * <h2>{@code char[]}, and why it is wiped</h2>
 *
 * <p>Both methods take the password as {@code char[]} rather than {@code
 * String} for the one property a {@code String} cannot have: a {@code char[]}
 * can be overwritten. A {@code String} holding a password sits in the heap
 * until the garbage collector reclaims it — on no schedule this code
 * controls — and would show up in a heap dump for as long as it survives.
 * {@link Argon2#wipeArray(char[])} zeroes the caller's array before either
 * method returns, whether hashing succeeded or failed, so the password does
 * not outlive the call it was passed into.
 *
 * <p>This is not complete memory hygiene, and it does not claim to be: if the
 * caller received the password as a {@code String} in the first place — an
 * HTTP request body deserialised by Jackson, say — that {@code String} is
 * already on the heap and this class cannot reach back and clear it. What it
 * guarantees is that <em>this class's own copy</em>, the one the caller
 * explicitly handed over as a mutable array, does not linger.
 *
 * <h2>A concurrency bound, because sequential cost is not a defence against
 * volume</h2>
 *
 * <p>{@code LoginAttempts}'s own class note used to claim the cost above
 * "already bounds it to a slow trickle". That is true of one caller making
 * attempts one at a time and false of many callers making them at once:
 * {@link #matches(char[], String)} is reachable with no credential at all
 * through {@code POST /v1/auth/login}, {@link LoginAttempts} throttles by the
 * handle a request presents rather than by anything about the request itself,
 * and a caller who varies the handle on every attempt is a different string
 * to that map every time — untouched by a per-handle lockout no matter how
 * many requests arrive. Each verification the argon2-jvm library performs
 * allocates roughly {@link #MEMORY_KB} kibibytes of native memory for the
 * duration of the call; at Tomcat's default of 200 request-handling threads,
 * 200 concurrent attempts under 200 distinct handles is on the order of
 * 200 &times; 19&nbsp;MiB, or roughly 3.8&nbsp;GB, of concurrent native
 * allocation from anyone who can reach the port — before counting the
 * {@link LoginAttempts} map entry each distinct handle also costs.
 *
 * <p>{@link #concurrency} is the fix: a {@link Semaphore} of {@value
 * #MAX_CONCURRENT_HASHES} permits, acquired around the one call in {@link
 * #hash(char[])} and the one in {@link #matches(char[], String)} that
 * actually performs the Argon2id work, released in a {@code finally}
 * alongside the array wipe. It bounds this class's <em>total</em> concurrent
 * native allocation to {@value #MAX_CONCURRENT_HASHES} &times; {@link
 * #MEMORY_KB} kibibytes — about 152&nbsp;MiB at the numbers above —
 * regardless of how many distinct handles, or distinct callers, are asking at
 * once. It is not scoped to logins alone: {@code POST /v1/auth/password}
 * calls {@link #matches(char[], String)} to verify a caller's current
 * password and {@link #hash(char[])} to produce the new one, and both go
 * through the same bound, because the resource being protected — this
 * process's native memory — does not care which endpoint asked for it.
 *
 * <p><b>What a caller sees when every permit is held: a wait, not a
 * refusal.</b> Both methods call {@link Semaphore#acquireUninterruptibly()}
 * rather than a timed {@code tryAcquire}, so a request that arrives once the
 * bound is saturated queues for a free permit instead of failing outright.
 * That is a deliberate choice among the two the task named — "queue briefly,
 * or refuse with a clear status" — and the reasoning is in the queue's own
 * numbers: an Argon2id verification here costs tens of milliseconds, so
 * {@value #MAX_CONCURRENT_HASHES} permits drain 200 queued callers in roughly
 * (200 &divide; {@value #MAX_CONCURRENT_HASHES}) rounds — on the order of a
 * couple of seconds in the worst case, not minutes. The alternative, refusing
 * outright once permits run out, would need this method to communicate a
 * distinct outcome to every caller up the stack — {@link #matches(char[],
 * String)} would have to stop answering a plain {@code boolean}, and {@code
 * AuthController#login} would have to invent a status for "the server is
 * busy hashing" that does not exist for any other reason today. A bounded
 * wait costs latency under hostile load and changes no caller's contract;
 * refusing would cost a new failure mode every caller of this class has to
 * learn. {@link AuthController} runs {@link #matches(char[], String)} on
 * Spring's container threads, which {@code TokenStore}'s own class note
 * establishes are platform threads on this stack today, so a thread blocked
 * here holds no virtual-thread carrier hostage the way a blocking call inside
 * a {@code synchronized} section would.
 *
 * <p>Sized in single digits deliberately: high enough that an ordinary login
 * or password change is never made to wait behind another legitimate one —
 * this server has one admin, so real concurrent Argon2id calls are rare — and
 * low enough that the memory bound above stays a small multiple of one
 * verification's cost rather than a number chosen to sound generous. {@link
 * LoginAttempts}'s own map-growth bound is the other half of closing this
 * finding; see that class for why a per-handle throttle needed a companion
 * bound on the map itself.
 */
@Component
public final class PasswordHasher {

    /** OWASP's current Argon2id baseline: 19 MiB. Argon2's memory cost is
     *  expressed in kibibytes. */
    private static final int MEMORY_KB = 19 * 1024;

    /** OWASP's current Argon2id baseline for the paired memory cost above. */
    private static final int ITERATIONS = 2;

    /** One lane. There is no concurrent hashing to parallelise across on a
     *  single login, and a higher value only widens the attacker's own
     *  opportunity to parallelise the same hash on multi-core hardware. */
    private static final int PARALLELISM = 1;

    /** See the class note "A concurrency bound" for the arithmetic this
     *  number is chosen against. */
    private static final int MAX_CONCURRENT_HASHES = 8;

    private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);

    /** The bound. See the class note for what it protects and why a caller
     *  waits rather than being refused when every permit is held. */
    private final Semaphore concurrency = new Semaphore(MAX_CONCURRENT_HASHES);

    /** How many Argon2id calls are inside the bound right now.
     *  Package-private, for {@code PasswordHasherTest} alone: it is the one
     *  way a test can observe that {@link #concurrency} is actually capping
     *  concurrency rather than merely existing, since the permit count itself
     *  is private and a passing test must not rest on trusting the field it
     *  is there to verify. */
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * Hash a password, wiping the caller's array in the process.
     *
     * @param password the plaintext, as a mutable array the caller owns; wiped
     *     to zero before this method returns, whether it succeeds or throws
     * @return the encoded {@code $argon2id$...} string, which carries the salt
     *     and the cost parameters above alongside the hash itself — nothing
     *     else needs to be stored beside it to verify a later attempt
     * @throws IllegalArgumentException if {@code password} is null or empty
     */
    public String hash(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException(
                    "PasswordHasher.hash() was given a null or empty password. That is a"
                            + " defect in the caller: an admin account is never created with no"
                            + " password to hash.");
        }
        try {
            concurrency.acquireUninterruptibly();
            inFlight.incrementAndGet();
            try {
                return argon2.hash(ITERATIONS, MEMORY_KB, PARALLELISM, password);
            } finally {
                inFlight.decrementAndGet();
                concurrency.release();
            }
        } finally {
            argon2.wipeArray(password);
        }
    }

    /**
     * Whether {@code password} is the plaintext {@code stored} was hashed
     * from.
     *
     * <p>Absence and malformation are both answered {@code false} rather than
     * thrown, on {@link Tokens#verify(String, String)}'s reasoning: a login
     * attempt is the ordinary path that calls this, and a wrong password or a
     * corrupted row should read to the caller as "does not match" rather than
     * force every login handler to also catch an exception from this method
     * specifically.
     *
     * @param password the plaintext to check, as a mutable array the caller
     *     owns; wiped to zero before this method returns
     * @param stored a previously produced {@link #hash(char[])}, or any other
     *     value — a blank or malformed one answers {@code false} rather than
     *     throwing
     * @return true only if {@code password} hashes to {@code stored} under
     *     Argon2id's own verification, which re-derives the cost parameters
     *     and the salt from the encoded string rather than from this class's
     *     current constants — so a row hashed under an older cost still
     *     verifies after {@link #MEMORY_KB} or {@link #ITERATIONS} changes
     */
    public boolean matches(char[] password, String stored) {
        if (password == null || password.length == 0 || stored == null || stored.isBlank()) {
            if (password != null) {
                argon2.wipeArray(password);
            }
            return false;
        }
        try {
            concurrency.acquireUninterruptibly();
            inFlight.incrementAndGet();
            try {
                return argon2.verify(stored, password);
            } catch (RuntimeException malformed) {
                // argon2-jvm throws on a stored value it cannot parse — a
                // corrupted row, or one from an incompatible library version.
                // Treated as "does not match" rather than propagated, on this
                // method's own contract above.
                return false;
            } finally {
                inFlight.decrementAndGet();
                concurrency.release();
            }
        } finally {
            argon2.wipeArray(password);
        }
    }

    /**
     * How many calls into {@link #concurrency}'s guarded section are in
     * flight right now. Package-private and for {@code PasswordHasherTest}
     * alone — see {@link #inFlight}'s own field javadoc.
     */
    int inFlight() {
        return inFlight.get();
    }

    /** The permit count {@link #concurrency} was built with, for {@code
     *  PasswordHasherTest} to assert against without a second copy of the
     *  number living in the test file. */
    static int maxConcurrentHashes() {
        return MAX_CONCURRENT_HASHES;
    }

    /**
     * Every char of {@code array} is the zero character — used only by tests,
     * which have no other way to observe that {@link Argon2#wipeArray(char[])}
     * ran, since it mutates the caller's own array rather than returning
     * anything.
     */
    static boolean isWiped(char[] array) {
        char[] zeroed = new char[array.length];
        return Arrays.equals(array, zeroed);
    }
}
