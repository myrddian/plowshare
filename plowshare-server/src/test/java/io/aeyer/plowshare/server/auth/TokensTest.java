package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The console's token primitive: it mints a secret, and it stores something that
 * is not it.
 *
 * <h2>The rule every assertion in this file is written around</h2>
 *
 * <p><b>No assertion here may print a token's value on failure.</b> A JUnit
 * failure message goes to stdout, to a build log, and into whatever CI keeps
 * those; {@code assertEquals(expectedToken, actual)} therefore publishes a live
 * credential at precisely the moment somebody is about to paste the output into
 * an issue. This is the same rule the LM Studio API key has had since slice 2 —
 * {@code PoolProperties} refuses to put the key in a {@code toString}, {@code
 * OpenAiTransport} refuses to put it in a failure detail, and {@code
 * InvariantsTest.no_source_carries_an_api_key} greps the tree for it — and the
 * bootstrap token joins it.
 *
 * <p>The mechanical consequence, because it is not obvious which assertions are
 * safe: {@code assertTrue}, {@code assertFalse} and {@code assertThrows} report
 * only the message they were given, so a token may be an <em>operand</em> of
 * them. {@code assertEquals} interpolates both sides into the failure. {@code
 * assertNotEquals} interpolates one — measured against the JUnit this build
 * resolves, 5.10.5, it fails with {@code "expected: not equal but was: <"} and
 * the <em>actual</em> value alone. That is not a reprieve, it is the same rule
 * with a sharper edge: {@code assertNotEquals} fails only when the two
 * <em>are</em> equal, so the single value it prints is the token. A token may
 * never be an argument to either. That is why the "the stored form is not the
 * token itself" check below is written as
 * {@code assertFalse(token.equals(hash))} and not as the {@code assertNotEquals}
 * that reads more naturally: the natural spelling prints the token in exactly
 * the case where the implementation is storing tokens in the clear, which is the
 * worst possible moment to also write one to a log.
 *
 * <p>So what is asserted throughout is <b>properties</b> — a length, a charset,
 * distinctness, and the outcome of a verification — never a value.
 *
 * <h2>How the constant-time property is pinned, and why not by timing</h2>
 *
 * <p>{@link Tokens#verify} must compare through {@link
 * java.security.MessageDigest#isEqual}, which reads both arrays to the end.
 * {@code String.equals} and {@code Arrays.equals} return at the first differing
 * byte, so the time they take reports the length of the matching prefix, and an
 * attacker who can time the endpoint recovers a hash a byte at a time instead of
 * guessing it whole.
 *
 * <p><b>That difference is observable only through timing, and a timing
 * assertion is not an instrument this project can have.</b> A wall-clock
 * comparison of two implementations on a JIT-compiled, GC'd, shared-CPU test
 * worker is noise with a signal somewhere under it; it would be flaky, it would
 * be quarantined, and a quarantined test is a test that cannot record what it is
 * cited for. This repository has already been caught once by an instrument whose
 * green meant two different things.
 *
 * <p>So the <em>mechanism</em> is asserted instead, and it is asserted against
 * the <b>compiled class</b> rather than against the source text. Be precise
 * about what that buys, because it is less than it looks: {@link #holds(byte[],
 * String)} is a raw byte substring search over the whole class file and does not
 * parse the constant pool at all. What compiling first removes is the comments
 * and the javadoc — and {@code Tokens} carries several paragraphs of javadoc
 * about {@code equals}, so a source scan would be useless. What it does
 * <b>not</b> remove is string literals, which land in {@code CONSTANT_Utf8}
 * entries verbatim, or parameter and local-variable names, which land in {@code
 * MethodParameters} and {@code LocalVariableTable} because this build compiles
 * with {@code -parameters} and the default debug tables. Two halves:
 *
 * <ul>
 *   <li>the class <b>does</b> reference {@code java/security/MessageDigest} and
 *       {@code isEqual}, so the constant-time primitive is genuinely linked; and
 *   <li>the byte run {@code equals} occurs in it <b>nowhere at all</b> — which
 *       rules out {@code String.equals}, {@code Arrays.equals} and {@code
 *       Objects.equals} in one needle, since all three are linked under that
 *       name and all three short-circuit.
 * </ul>
 *
 * <h2>What this does not cover, said rather than implied</h2>
 *
 * <p>The second half is a property of the <em>class file</em>, which is broader
 * than the property of {@code verify} that is actually wanted, and broader again
 * than "this class calls something named {@code equals}". The first widening is
 * deliberate and affordable: {@link Tokens} is three static methods, and neither
 * minting nor hashing has any business comparing two things. The second is the
 * price of not writing a class-file reader, and it means <b>a failure here is
 * not on its own evidence that anyone added a comparison</b>. Three edits that
 * touch no comparison at all turn it red, each checked against a compiled probe:
 *
 * <ul>
 *   <li>putting the word in a <b>string literal</b> — rewording one of {@code
 *       Tokens}'s own exception messages to mention it is enough, debug tables
 *       or no debug tables;
 *   <li>making {@code Tokens} a <b>record</b>, which brings both a generated
 *       {@code equals} and the {@code ObjectMethods} bootstrap into the pool;
 *       and
 *   <li>any <b>{@code switch} over a {@code String}</b>, which javac lowers to
 *       {@code hashCode} plus {@code String.equals}.
 * </ul>
 *
 * <p>A parameter or local variable whose name merely contains the word does it
 * too, under this build's flags. The failure message on that assertion lists all
 * of these, so the reader of a red bar does not have to rediscover them.
 *
 * <p>It is blind in the other direction as well, which the commit that
 * introduced it did not say. The needle is six bytes, so it does not see {@code
 * String.contentEquals} — capital {@code E}, so {@code equals} is not a
 * substring of it — nor {@code compareTo}, nor {@code List.contains} and {@code
 * Set.contains}. Every one of those short-circuits on the first difference, and
 * every one of them passes this test unnoticed. What the assertion rules out is
 * the three spellings someone reaches for first; it is a tripwire, not a proof.
 *
 * <p>What is genuinely not proved is that the {@code isEqual} the scan finds is
 * the value {@code verify} returns — a class could link it and ignore it. No
 * non-timing test can close that gap, and the pair of behavioural assertions in
 * {@link #a_hash_verifies_its_own_token_and_no_other()} is what stands between
 * this and a {@code verify} that links the primitive and answers with something
 * unrelated.
 *
 * <h2>The control, because a scan that finds nothing proves nothing</h2>
 *
 * <p>{@code assertFalse(holds(compiled, "equals"))} is the shape this project
 * has a standing complaint about: a mistyped needle, a class file read from the
 * wrong place, or an empty byte array all turn it green without inspecting
 * anything. {@code InvariantsTest.the_guard_can_see_the_files_it_asserts_over}
 * answers that for the source scans one package up, and {@link
 * #the_needles_find_what_they_are_spelled_to_find()} answers it here, the same
 * way: {@link ShortCircuiting} is compiled beside this test and does the exact
 * thing being ruled out, so each needle is required to match where it should and
 * to miss where it should. Without that pair, both halves above would pass over
 * a zero-length array.
 *
 * @see io.aeyer.plowshare.server.InvariantsTest
 */
class TokensTest {

    /** 24 random bytes rendered as hex. The length is asserted rather than
     *  derived from the implementation, because a constant a test reads out of
     *  the class it is testing agrees with that class by construction. */
    private static final int HEX_LENGTH = 48;

    /**
     * A thousand draws is a real check here and would not have been in {@code
     * MemoryIds}.
     *
     * <p>Worth stating because the neighbouring precedent looks like it argues
     * the opposite: {@code MemoryIds} draws three random bytes and therefore
     * needed a seeded-generator seam, since 500 draws over 24 bits collide about
     * 0.7% of the time and a suite that fails one run in 135 teaches people to
     * re-run rather than read. This draws 192 bits, where the birthday bound
     * over a thousand puts a collision somewhere around one in 10^52. So a
     * repeat below is not bad luck to be re-rolled — it is a broken source of
     * randomness, and this loop is allowed to say so without a seam that would
     * let a caller weaken the real thing.
     *
     * <p>The charset assertion is not cosmetic either. The bootstrap token is
     * the one secret this project puts in a URL — single-use and spent on
     * arrival, per the slice design — and it also has to survive a {@code
     * Set-Cookie} value and an {@code Authorization} header. Hex needs no
     * escaping in any of the three, so no layer between minting and verifying
     * gets the chance to re-encode it into something that no longer matches.
     */
    @Test
    void a_minted_token_is_long_enough_and_never_repeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = Tokens.mint();
            assertEquals(HEX_LENGTH, token.length(),
                    "a token's length is a fixed part of its strength");
            assertTrue(token.matches("[0-9a-f]+"),
                    "hex, so it survives a URL, a header and a cookie unescaped");
            assertTrue(seen.add(token),
                    "a repeat at iteration " + i + " means the source is not random");
        }
    }

    /**
     * The binding, in both directions: this token verifies, that one does not,
     * and what is stored is not what was minted.
     *
     * <p>The third assertion is the one that would otherwise be assumed. The
     * first two pass perfectly well against an implementation whose {@code hash}
     * is the identity function — a token verifies against itself and a different
     * token does not — and that implementation is the whole failure this class
     * exists to prevent, since it puts every live session's token in the store
     * in the clear.
     */
    @Test
    void a_hash_verifies_its_own_token_and_no_other() {
        String token = Tokens.mint();
        String other = Tokens.mint();
        String hash = Tokens.hash(token);

        assertTrue(Tokens.verify(token, hash),
                "a token does not verify against the hash of itself");
        assertFalse(Tokens.verify(other, hash),
                "a different token verified against this hash, so the hash is not binding");
        // assertFalse and not assertNotEquals: see the class note. The natural
        // spelling prints the token when it fails, and it fails exactly when the
        // stored form *is* the token.
        assertFalse(token.equals(hash), "the stored form must not be the token itself");
    }

    /**
     * Whatever a request carried, answered rather than thrown.
     *
     * <p>{@code AuthFilter} reads a cookie and an {@code Authorization} header, and
     * both are absent on most of the requests that reach it — a missing cookie
     * is a null, a header with nothing after {@code Bearer} is a blank. Those
     * are the ordinary case of an unauthenticated request, not an exceptional
     * one, and a primitive that threw on them would make the filter's happy path
     * a {@code catch} block.
     */
    @Test
    void verify_answers_false_for_what_a_request_may_not_have_carried() {
        String token = Tokens.mint();
        String hash = Tokens.hash(token);

        assertFalse(Tokens.verify(null, hash), "a request with no token verified");
        assertFalse(Tokens.verify(token, null), "a token verified against no stored hash");
        assertFalse(Tokens.verify(null, null), "nothing verified against nothing");
        assertFalse(Tokens.verify("", hash), "an empty token verified");
        assertFalse(Tokens.verify("   ", hash), "a blank token verified");
        assertFalse(Tokens.verify(token, ""), "a token verified against an empty hash");
        assertFalse(Tokens.verify(token, "   "), "a token verified against a blank hash");
    }

    /**
     * Hashing is the other side of the same question and answers it differently,
     * on purpose.
     *
     * <p>{@link Tokens#verify} is handed whatever arrived over the wire, so
     * absence is data. {@link Tokens#hash} is only ever called on something this
     * server just minted or is about to store, so a null or blank reaching it is
     * a defect in the caller and not a fact about a request — and returning the
     * hash of an empty string would write a row that anything blank then
     * verifies against.
     */
    @Test
    void hashing_nothing_is_a_caller_defect_and_not_a_hash() {
        assertThrows(IllegalArgumentException.class, () -> Tokens.hash(null));
        assertThrows(IllegalArgumentException.class, () -> Tokens.hash(""));
        assertThrows(IllegalArgumentException.class, () -> Tokens.hash("   "));
    }

    @Test
    void verify_compares_through_the_constant_time_primitive() throws IOException {
        byte[] compiled = classFileOf(Tokens.class);

        assertTrue(holds(compiled, "java/security/MessageDigest"),
                "Tokens.class does not reference MessageDigest at all, so whatever verify"
                        + " compares with, it is not the constant-time primitive");
        assertTrue(holds(compiled, "isEqual"),
                "Tokens.class references MessageDigest but never isEqual — getInstance and"
                        + " digest alone hash without comparing, and the comparison is the half"
                        + " a timing attack reads");
        assertFalse(holds(compiled, "equals"),
                "Tokens.class contains the byte run 'equals'. This is a byte scan over the"
                        + " whole class file and not a parse of the constant pool, so it fires on"
                        + " a real short-circuiting comparison — String.equals, Arrays.equals,"
                        + " Objects.equals — and it fires just the same on a string literal that"
                        + " uses the word, on a record's generated equals, on a switch over a"
                        + " String (javac lowers that to String.equals), and on a parameter or"
                        + " local variable whose name contains it. Work out which of those you"
                        + " added. If it is prose or a name, reword it. If it is a comparison,"
                        + " use MessageDigest.isEqual: the others return at the first differing"
                        + " byte, so the time the call takes reports the length of the matching"
                        + " prefix and a stored hash is recoverable a byte at a time.");
    }

    /**
     * The guard on the guard: each needle above matches where it should and
     * misses where it should.
     *
     * <p>Three of the four assertions above are "this byte array does / does not
     * contain that", and all three go green over an empty array. So the control
     * class is compiled beside this test doing the thing being ruled out, and
     * the same two needles are run against it with their answers swapped. A
     * misspelled needle, a class file that failed to load, or a {@link
     * #holds(byte[], String)} that never matches anything now fails here rather
     * than passing silently there.
     */
    @Test
    void the_needles_find_what_they_are_spelled_to_find() throws IOException {
        byte[] control = classFileOf(ShortCircuiting.class);

        assertTrue(control.length > 100,
                "the control class file is " + control.length + " bytes, which is not a class"
                        + " file — so every containment answer below is about the wrong bytes");
        assertTrue(holds(control, "equals"),
                "the control calls String.equals and the needle did not find it, so the"
                        + " assertion that Tokens.class holds no 'equals' is vacuous");
        assertFalse(holds(control, "isEqual"),
                "the control does not call MessageDigest.isEqual and the needle found it"
                        + " anyway, so the assertion that Tokens.class does hold 'isEqual'"
                        + " matches on something other than the primitive");
    }

    /**
     * The comparison {@link Tokens#verify} must not be: correct, and it leaks its
     * progress to anything holding a stopwatch.
     *
     * <p>It exists to be compiled, not to be called — {@link
     * #the_needles_find_what_they_are_spelled_to_find()} reads its class file.
     * Written against two plain strings rather than against {@link Tokens} so
     * that the control stays a control if {@code Tokens} changes shape.
     */
    private static final class ShortCircuiting {
        static boolean verify(String presented, String storedHash) {
            return presented.equals(storedHash);
        }
    }

    /**
     * The bytes the JVM loaded this class from.
     *
     * <p>Addressed through {@link Class#getResourceAsStream(String)} on the class
     * itself rather than by building a path into {@code build/classes}, so this
     * reads the artefact the test run is actually executing and not a file that
     * happens to sit where a previous compilation left one.
     */
    private static byte[] classFileOf(Class<?> type) throws IOException {
        String binary = type.getName();
        String fileName = binary.substring(binary.lastIndexOf('.') + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(fileName)) {
            assertNotNull(in, "no " + fileName + " on the classpath beside " + binary
                    + ", so there are no bytes to assert over");
            return in.readAllBytes();
        }
    }

    /**
     * Whether {@code ascii} occurs in {@code classFile} as a literal byte run.
     *
     * <p>Constant-pool UTF-8 entries are modified UTF-8, which for the ASCII
     * identifiers and internal class names searched for here is byte-identical
     * to {@link StandardCharsets#US_ASCII}. Nothing here parses the pool: the
     * question is only whether the class references a name, and a substring
     * search over the whole file answers it without a class-file reader this
     * repository would then have to maintain against a format that changes every
     * release. The price of not parsing is that this cannot tell a referenced
     * name from a string literal or a debug-table identifier; the class note
     * above spells out what that costs, and the failing assertion says it again
     * where someone will read it.
     */
    private static boolean holds(byte[] classFile, String ascii) {
        byte[] needle = ascii.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= classFile.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classFile[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
