package io.aeyer.plowshare.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Minting a bearer secret, and storing something that is not it.
 *
 * <p>The primitive under slice 4's console authentication: the one-time
 * bootstrap token printed at startup, the 15-minute access token and the 7-day
 * refresh token are all minted here, and what any store keeps is {@link
 * #hash(String)} of one, never the one itself. Nothing in this class knows about
 * HTTP, cookies, expiry or a store — those are the tasks that follow, and this
 * is deliberately the piece none of them has to reimplement.
 *
 * <h2>Why the stored form is a hash, when the LM Studio key is not</h2>
 *
 * <p>This repository already holds a long-lived credential in the clear —
 * {@code PoolProperties.apiKey}, read from configuration and sent upstream — and
 * the difference is worth stating, because "we store the API key as plaintext"
 * is otherwise a precedent that would answer this question the wrong way.
 *
 * <p><b>The API key is a credential this server <em>presents</em>. A token is one
 * it <em>accepts</em>.</b> A credential that must be presented has to be
 * recoverable, so the protection around it is that it is never written anywhere
 * it was not already: no {@code toString}, no failure detail, no log line, and
 * {@code InvariantsTest.no_source_carries_an_api_key} greps the tree for its
 * prefix. A credential that is only ever <em>checked</em> needs none of that
 * recoverability, and giving it any is pure downside: a heap dump, a stray
 * {@code log.debug} of a row, a database file copied off the box, or a backup
 * would each hand over every live session at once.
 *
 * <p>And the two are not equally recoverable afterwards. The API key is rotated
 * by editing one file; a leaked set of session tokens is not rotatable at all in
 * any sense that helps, because by the time anyone knows, the holder has been
 * the operator for as long as the shortest expiry allows. So the accept side
 * stores a digest, and a leak of the store is a leak of digests.
 *
 * <h2>Why SHA-256 and deliberately not bcrypt, scrypt or argon2</h2>
 *
 * <p>The slow-hash family exists to buy time against <em>offline brute force of
 * a human-chosen password</em>. Every word of that is absent here. There is no
 * password, there is no user to choose a weak one, and there is no dictionary to
 * run: a token is {@value #TOKEN_BYTES} bytes straight out of {@link
 * SecureRandom}, which is 192 bits with no structure to exploit. <b>The
 * work factor a slow hash sells is already paid, once, at minting.</b> No
 * plausible amount of hardware searches a 192-bit space, so multiplying the cost
 * of each guess by ten thousand changes an impossible number into a slightly
 * larger impossible number.
 *
 * <p>What it would change is the server. A deliberately slow hash runs on
 * <em>every authenticated request</em> — every REST call, every WebSocket
 * upgrade, every console poll — and argon2 tuned to be worth having costs tens
 * of milliseconds and tens of megabytes each time. That is real latency and real
 * memory spent against a threat model that does not exist, and it would make the
 * auth filter the slowest thing in a request that otherwise does a map lookup.
 *
 * <p>The rule this follows, stated so the next credential is not argued from
 * scratch: <b>slow hashes are for secrets a human chose; a single fast digest is
 * for secrets a CSPRNG chose.</b> If Plowshare ever grows a password — the slice
 * design says it will not — that secret does not come through this class.
 *
 * <h2>Why the comparison is {@link MessageDigest#isEqual(byte[], byte[])}</h2>
 *
 * <p>{@code String.equals}, {@code Arrays.equals} and {@code Objects.equals} all
 * return at the first differing byte. The time the call takes therefore reports
 * how long a prefix matched, and an attacker who can time the endpoint recovers
 * a stored digest one byte at a time — a few hundred requests per byte instead
 * of the 2^192 the token's entropy was supposed to buy. {@code isEqual} reads to
 * the end of the array whatever it finds, and folds the length difference into
 * the same accumulator rather than returning early on it — for any non-empty
 * second argument, which is the only kind this class passes it. JDK 21's
 * implementation does return early, before the accumulator, on reference
 * identity and on a zero-length second array; neither is reachable from {@link
 * #verify(String, String)}, whose blank guard runs first and whose first
 * argument is a freshly formatted digest.
 *
 * <p>{@code TokensTest} pins this by scanning this class's own compiled form for
 * two byte runs: {@code isEqual} must occur in it and {@code equals} must not.
 * It is a raw substring search over the whole class file rather than a parse of
 * the constant pool, so what it buys over scanning the source is exactly one
 * thing — javac has already discarded the comments and the javadoc, and this
 * file has a great deal of both about {@code equals}. It buys nothing further:
 * string literals survive into the pool, and so do parameter and local-variable
 * names, since this build compiles with {@code -parameters} and the default
 * debug tables. So an exception message here that used the word would fail that
 * test while comparing nothing — a constraint on this file's prose, not on its
 * behaviour, and {@code TokensTest} says so where it fails.
 *
 * <p>The scan is also why the guards below are spelled with {@link
 * String#isBlank()} and null checks rather than with any comparison — and those
 * guards are not a timing hazard themselves, because what they branch on is
 * whether a request carried a token at all, which its sender already knows.
 *
 * <h2>No logger, deliberately</h2>
 *
 * <p><b>This class has no {@code Logger} field and must not acquire one.</b>
 * Every value that passes through it is either a live secret or a digest of one,
 * so there is no line it could usefully log that is safe to write, and a debug
 * statement added here in a hurry is precisely the leak the hashing above exists
 * to make survivable. The same reasoning applies to exception messages: the
 * {@link IllegalArgumentException} {@link #hash(String)} throws names the
 * argument that was wrong and never quotes it, and the {@link
 * IllegalStateException} from the shared digest helper names only the algorithm
 * that was missing.
 *
 * <p>The bootstrap token is printed exactly once, at startup, by the task that
 * mints it — not from here.
 */
public final class Tokens {

    /**
     * 192 bits, and the number the slow-hash argument above rests on.
     *
     * <p>Sixteen would already be past guessing; twenty-four is chosen because
     * it renders as {@code 48} hex characters, which is short enough to sit in
     * the startup URL line without wrapping a terminal and long enough that
     * nobody is tempted to argue the margin later.
     */
    private static final int TOKEN_BYTES = 24;

    /**
     * Required of every JVM by the standard algorithm names, so {@link
     * MessageDigest#getInstance(String)} cannot fail here for a reason an
     * operator could fix — see {@link #hash(String)} on why that is fatal rather
     * than checked.
     */
    private static final String DIGEST = "SHA-256";

    /** One instance, seeded by the platform, shared: {@link SecureRandom} is
     *  thread-safe, and a fresh one per call re-seeds from the OS for no
     *  benefit. There is deliberately no seam for passing another generator in —
     *  unlike {@code MemoryIds}, which needed one to make a birthday-bound test
     *  decidable over three random bytes. Over twenty-four there is no collision
     *  to make decidable, so the only thing such a seam could do here is let a
     *  caller weaken the real source. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Lowercase hex.
     *
     * <p>Diverging from {@code MemoryIds}, which formats uppercase, and the
     * divergence is the point rather than an oversight: a memory id is compared
     * as an ordinary string and sorted, while a token is compared as a digest,
     * so <b>a token has to have exactly one spelling</b>. Two cases would mean a
     * value that is the same secret and a different digest, and the layer that
     * re-cased it would be somewhere between a browser, a cookie jar and a
     * header. Lowercase because it is what {@link HexFormat#of()} gives without
     * being asked, which is one fewer call for someone to forget.
     */
    private static final HexFormat HEX = HexFormat.of();

    private Tokens() {}

    /**
     * A fresh secret: {@value #TOKEN_BYTES} random bytes, rendered as two
     * lowercase hex characters each.
     *
     * <p>Hex rather than base64url because the value has to survive a URL query
     * (the bootstrap line), a {@code Set-Cookie} value and an {@code
     * Authorization} header unescaped, and hex is in the unreserved set of all
     * three. Base64url would fit too and be a third shorter; it is not worth the
     * one day somebody pads it, or a proxy re-encodes a {@code -}, and the
     * digest then does not match for a reason nobody can see.
     *
     * <p>Every token this server issues comes from here — bootstrap, access and
     * refresh alike — because a token's strength should be one fact in one place
     * rather than three constants that drifted.
     */
    public static String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    /**
     * What a store keeps instead of the token: its SHA-256, lowercase hex.
     *
     * <p>Blank and null are rejected rather than hashed, and that is the
     * opposite of what {@link #verify(String, String)} does with the same
     * inputs, on purpose. This is only ever called on a value this server just
     * minted or is about to persist, so nothing reaching it is a fact about a
     * request — it is a defect in the caller. Hashing an empty string instead
     * would write a perfectly valid-looking row that every blank credential then
     * verifies against, which is a hole shaped exactly like a working login.
     *
     * <p>The exception names the argument and never quotes it; see the class
     * note on why nothing here writes a value anywhere.
     *
     * @param token a minted token; never null, never blank
     * @throws IllegalArgumentException if {@code token} is null or blank
     * @throws IllegalStateException if this JVM has no SHA-256, which the
     *     platform requires it to have, so there is nothing a caller could
     *     usefully do about it and nothing to declare
     */
    public static String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "hash() was given a null or blank token. It is called on a value this"
                            + " server minted, so an absent one is a defect in the caller and"
                            + " not something a request can cause.");
        }
        return HEX.formatHex(digest(token));
    }

    /**
     * Whether {@code presented} is the token {@code storedHash} was made from.
     *
     * <p><b>Absence is an answer here, not an exception.</b> The filter this
     * feeds reads a cookie that is missing on most requests and an {@code
     * Authorization} header that is missing on the rest; a null or a blank is
     * the ordinary shape of an unauthenticated request, and a primitive that
     * threw on it would turn the filter's commonest path into a {@code catch}
     * block — which is both slower and the kind of code where a
     * {@code return true} eventually gets written by accident.
     *
     * <p>The comparison is {@link MessageDigest#isEqual(byte[], byte[])}. See the
     * class note for why no other comparison may appear in this file, and for
     * why the null and blank guards above it are not themselves a timing leak.
     *
     * @param presented whatever the request carried, including nothing
     * @param storedHash the {@link #hash(String)} a store holds, or nothing. It
     *     must be that method's own output and therefore lowercase: this
     *     compares the hex <em>strings</em> byte for byte and not the digests
     *     they denote, so an uppercased or otherwise re-cased copy of the right
     *     digest does not verify. Nothing today can produce one — every stored
     *     value comes from {@code hash} — but a store that normalises the case
     *     of what it writes or reads back is how that stops being true.
     * @return true only if both are present and the digest of the first is the
     *     second
     */
    public static boolean verify(String presented, String storedHash) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                HEX.formatHex(digest(presented)).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The digest itself, shared by the two callers above so that the charset is
     * decided once.
     *
     * <p>UTF-8 explicitly and not the platform default: a token is hex either
     * way, so this changes no byte on any machine anyone has run this on — which
     * is exactly why it would be an unnoticed dependency on a locale if it were
     * left implicit.
     */
    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(DIGEST).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Not recoverable and not a caller's problem: SHA-256 is required of
            // every conforming JVM, so this is a broken platform rather than a
            // configuration anyone can fix.
            throw new IllegalStateException(
                    DIGEST + " is required of every JVM and this one does not provide it", e);
        }
    }
}
