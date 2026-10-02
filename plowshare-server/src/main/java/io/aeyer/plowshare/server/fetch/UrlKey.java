package io.aeyer.plowshare.server.fetch;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The key a {@link FetchedPageStore} row is filed under: a SHA-256 of the
 * URL, host lowercased.
 *
 * <h2>Why the host is lowercased and the path is not</h2>
 *
 * <p>{@code https://E.EXAMPLE/a} and {@code https://e.example/a} name the
 * same page — DNS is case-insensitive, so the two spellings resolve to the
 * same server — while {@code https://e.example/A} and {@code
 * https://e.example/a} do not: a path is whatever the server says it is, and
 * a great many servers treat it case-sensitively. Folding the whole URL to
 * lowercase before hashing, the way {@code QueryKey.of} folds a whole query,
 * would collapse two different pages into one row; leaving the whole URL
 * alone would give two rows to a page an agent asked for under a host it
 * happened to type differently. Only the host is normalised, because only the
 * host is guaranteed to mean the same thing regardless of case.
 *
 * <h2>An unparseable string is refused, not hashed</h2>
 *
 * <p>The whole point of this store is to answer {@code find} <b>before</b>
 * any fetch runs, so that a page already held is never fetched twice — which
 * means {@link #of} is the first thing to see a caller's raw string, not a
 * validated one. {@code PageFetcher.fetch}'s own javadoc documents a fetcher
 * built to receive exactly that: it never throws, and reduces even a scheme
 * it will not dial to a failure result rather than an exception. Nothing
 * upstream of this method can be relied on to have rejected garbage already.
 *
 * <p>So a string that does not even parse as a URI is refused here with an
 * {@link IllegalArgumentException}, on the same reasoning {@link #of}'s own
 * {@code NoSuchAlgorithmException} handling already uses one exception lower:
 * a key is an identity, and an identity computed from something that is not
 * a URL — silently, with a hash that looks exactly like every other hash — is
 * worse than no key at all, because {@code
 * fetched_pages_a_key_is_a_sha_256} cannot tell the two apart and nothing
 * downstream would ever know to look.
 *
 * <p>This is a backstop, not the primary path. A later slice's {@code
 * FetchService.read} validates at the edge and answers a blank or malformed
 * URL with {@code CallerFault} before {@link #of} is ever reached,
 * so in the wired system this exception should be rare to the point of
 * unreachable from a well-behaved caller — it exists for the caller that
 * skips that edge, the same shape of protection {@code
 * fetched_pages_a_key_is_a_sha_256} gives one layer down. Do not read the
 * rarity as license to remove it: an unparseable string cannot be keyed at
 * all, which is a different situation from a parseable URL naming a scheme
 * the fetcher will not dial ({@code file:///etc/passwd}) — {@code
 * BuiltinFetcher} answers that one with {@code FETCH_FAILED}, correctly,
 * because it parsed fine and simply named a place this fetcher refuses to
 * go. The two must not be collapsed into one behaviour.
 *
 * <h2>Why the digest and not the URL</h2>
 *
 * <p>Unlike {@code QueryKey}, which hides a query's terms because a search
 * is a person's question, {@code fetched_pages} keeps the URL beside the key
 * — see {@code V35__fetched_pages.sql}'s own comment for why that table
 * differs from {@code search_result_sets} here. The hash still exists for a
 * different reason: a fixed-width, short key an agent can carry in a tool
 * result and hand back on a later read, rather than restating a possibly long
 * URL in full every time. The digest choice itself (SHA-256 over a 32-bit
 * hash or MD5) is {@code archive.TurnStore.hashOf}'s argument, restated in
 * full by {@code V32__turn_system_prompt.sql}; this method mirrors that idiom
 * rather than inventing a third spelling of the same computation.
 */
public final class UrlKey {

    private UrlKey() {}

    /**
     * The key for {@code url}: lowercase hex SHA-256 of the UTF-8 bytes of
     * {@code url} with its host lowercased.
     *
     * <p><b>{@link Locale#ROOT} on the lowercase, never the default
     * locale.</b> {@code QueryKey.of}'s own javadoc carries the argument in
     * full: under a Turkish locale, {@code String.toLowerCase()} folds a
     * dotted capital I differently than every other locale does, so a key
     * computed on one server would silently stop matching the same URL's key
     * computed on another. A key is a wire-level identity and must not depend
     * on where the machine thinks it is.
     */
    public static String of(String url) {
        String normalised = normaliseHost(url);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException noSha256) {
            throw new IllegalStateException(
                    "this runtime has no SHA-256, so no url key can be named", noSha256);
        }
    }

    private static String normaliseHost(String url) {
        try {
            URI parsed = new URI(url);
            String host = parsed.getHost();
            if (host == null || host.isEmpty()) {
                return url;
            }
            // Rebuilt from parts rather than string-splicing the lowercased
            // host back in: the host can also appear, unrelated, in a path,
            // query or fragment, and a plain indexOf/replace would risk
            // touching one of those instead of the host it was meant for.
            //
            // RAW COMPONENTS, AND NEVER THE MULTI-ARG URI CONSTRUCTOR. An
            // earlier version of this method rebuilt through `new
            // URI(scheme, userInfo, host, port, path, query, fragment)`,
            // which takes DECODED components and re-quotes only the
            // characters each component actually requires escaped -- so
            // `%2F` in a decoded path is handed back as a literal `/`
            // (legal in a path) and `%26` in a decoded query is handed back
            // as a literal `&` (legal in a query), and two URLs that differ
            // only in whether a slash or an ampersand was percent-encoded
            // normalised to the same string and hashed to the same key.
            // `https://e.example/a%2Fb` and `https://e.example/a/b` are
            // different resources -- the first names one path segment
            // containing a slash, the second names two segments -- and
            // collapsing them onto one `fetched_pages` row means a second
            // caller who asks for one is silently served the text fetched
            // for the other, which is the exact failure this whole slice
            // exists to prevent, arriving through the key rather than
            // through the fetch. Splicing the raw, already-encoded
            // components back together by hand -- touching only case, never
            // encoding -- keeps every percent-escape exactly as the caller
            // wrote it.
            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(parsed.getScheme()).append("://");
            if (parsed.getRawUserInfo() != null) {
                rebuilt.append(parsed.getRawUserInfo()).append('@');
            }
            rebuilt.append(host.toLowerCase(Locale.ROOT));
            if (parsed.getPort() != -1) {
                rebuilt.append(':').append(parsed.getPort());
            }
            if (parsed.getRawPath() != null) {
                rebuilt.append(parsed.getRawPath());
            }
            if (parsed.getRawQuery() != null) {
                rebuilt.append('?').append(parsed.getRawQuery());
            }
            if (parsed.getRawFragment() != null) {
                rebuilt.append('#').append(parsed.getRawFragment());
            }
            return rebuilt.toString();
        } catch (URISyntaxException unparseable) {
            // See the class javadoc: this is a backstop against a caller that
            // reaches UrlKey.of before FetchService.read's own validation, not
            // the primary defence -- but a string that does not even parse
            // has no host to normalise and no business becoming a stable-
            // looking key, so it is refused here rather than hashed as given.
            throw new IllegalArgumentException(
                    "field 'url' is not a URL and cannot be keyed: " + unparseable.getMessage(),
                    unparseable);
        }
    }
}
