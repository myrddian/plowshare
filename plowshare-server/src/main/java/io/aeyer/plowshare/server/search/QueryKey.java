package io.aeyer.plowshare.server.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The key a {@link ResultSetStore} row is filed under: a SHA-256 of the
 * search's normalised terms, never the terms themselves.
 *
 * <h2>Why normalise before hashing</h2>
 *
 * <p>{@code "  Plowshare   Harness "} and {@code "plowshare harness"} are the
 * same search from a model's point of view — the same question, asked with
 * different whitespace and casing — and a caller re-asking it a moment later
 * should find the set already stored rather than pay for a second ladder run
 * over a difference nobody meant. Trimming, collapsing internal whitespace to
 * a single space and lowercasing folds that difference away before the hash
 * ever sees it; {@code max} is folded into the same digest, unnormalised,
 * because a caller asking for 50 results bought a different set than one
 * asking for 25 of the same terms, and the two must not collide onto one row.
 *
 * <h2>Why the digest and not the terms</h2>
 *
 * <p>{@code query_key} is the only trace of a search that {@code
 * search_result_sets} keeps — see that migration's own comment for why the
 * terms themselves are not a column there. A hash is what lets this class
 * hand the store something to key a row on without handing it something a
 * row could be turned back into. The digest choice itself (SHA-256 over a
 * 32-bit hash or MD5) is {@code archive.TurnStore.hashOf}'s argument, restated
 * in full by {@code V32__turn_system_prompt.sql}; this method mirrors that
 * idiom rather than inventing a third spelling of the same computation.
 */
public final class QueryKey {

    /**
     * Compiled once. {@code String.replaceAll} compiles its pattern on every
     * call, and this one runs on the way into every single search — the same
     * reason {@code Commands.LINE_BREAK} and {@code MemoryTools.LINE_BREAK}
     * are constants rather than literals at their call sites.
     */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private QueryKey() {}

    /**
     * The key for a search of {@code query} asking for at most {@code max}
     * results: lowercase hex SHA-256 of the UTF-8 bytes of the normalised
     * query, a single space, and {@code max}.
     *
     * <p><b>{@link Locale#ROOT} on the lowercase, never the default
     * locale.</b> {@code String.toLowerCase()} folds against whatever locale
     * the JVM booted with, and Turkish is the case everybody eventually
     * meets: under {@code -Duser.language=tr}, {@code "WIKI"} lowercases to
     * {@code "wıkı"} with dotless i, which hashes to a different key than the
     * same search on any other server. Nothing would report an error — page 2
     * would simply never find page 1's set, on that one deployment, and every
     * stored set written before a locale change would be silently
     * unreachable after it. A key is a wire-level identity and must not
     * depend on where the machine thinks it is; {@code SearchProperties.split}
     * already makes the same call for the same reason.
     */
    public static String of(String query, int max) {
        String normalised =
                WHITESPACE_RUN.matcher(query.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
        String canonical = normalised + " " + max;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException noSha256) {
            throw new IllegalStateException(
                    "this runtime has no SHA-256, so no query key can be named", noSha256);
        }
    }
}
