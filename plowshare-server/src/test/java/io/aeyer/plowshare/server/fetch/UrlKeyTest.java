package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class UrlKeyTest {

    @Test
    void the_same_url_is_the_same_key() {
        assertEquals(UrlKey.of("https://e.example/a"), UrlKey.of("https://e.example/a"));
    }

    @Test
    void a_different_url_is_a_different_key() {
        assertNotEquals(UrlKey.of("https://e.example/a"), UrlKey.of("https://e.example/b"));
    }

    @Test
    void the_key_is_a_sha_256_and_carries_none_of_the_url() {
        assertTrue(UrlKey.of("https://secret.example/path").matches("^[0-9a-f]{64}$"));
    }

    @Test
    void host_case_does_not_make_a_different_page() {
        assertEquals(UrlKey.of("https://E.EXAMPLE/a"), UrlKey.of("https://e.example/a"));
    }

    /**
     * {@code normaliseHost} used to rebuild the URL through {@code new
     * URI(scheme, userInfo, host, port, path, query, fragment)}, the
     * multi-arg constructor that takes <b>decoded</b> components and
     * re-quotes only what each component still requires escaped. A slash is
     * legal inside a path, so a decoded {@code %2F} came back as a literal
     * {@code /} — collapsing a URL naming one path segment that contains a
     * slash onto a URL naming two segments, and hashing both to the same
     * {@code fetched_pages} key. That means a second caller asking for one
     * of these URLs would silently be served the page text fetched for the
     * other — exactly the failure this whole slice exists to prevent,
     * arriving through the key instead of through the fetch.
     */
    @Test
    void an_encoded_slash_in_the_path_is_a_different_page_than_a_literal_one() {
        assertNotEquals(UrlKey.of("https://e.example/a%2Fb"), UrlKey.of("https://e.example/a/b"));
    }

    /**
     * The same collapse, one component over: a decoded {@code %26} in a
     * query string comes back as a literal {@code &}, which turns one
     * query parameter whose value contains an encoded ampersand into two
     * separate parameters. {@code ?a=b%26c} (one parameter, {@code a},
     * valued {@code b&c}) and {@code ?a=b&c} (two parameters, {@code a=b}
     * and the valueless {@code c}) must not key the same row.
     */
    @Test
    void an_encoded_ampersand_in_the_query_is_a_different_page_than_a_literal_one() {
        assertNotEquals(
                UrlKey.of("https://e.example/p?a=b%26c"), UrlKey.of("https://e.example/p?a=b&c"));
    }

    @Test
    void the_key_does_not_depend_on_the_machines_locale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            String turkish = UrlKey.of("https://WIKI.example/I");
            Locale.setDefault(Locale.ROOT);
            assertEquals(UrlKey.of("https://WIKI.example/I"), turkish,
                    "QueryKey shipped this defect: a Turkish-locale server lowercases I "
                            + "differently and silently loses every stored row");
        } finally {
            Locale.setDefault(original);
        }
    }

    /**
     * {@code UrlKey.of} is the first thing to see a caller's raw string —
     * {@code find} must run before any fetch, so nothing upstream can be
     * relied on to have already rejected garbage (see the class javadoc).
     * A string that does not even parse as a URI must be refused rather
     * than hashed as given: {@code fetched_pages_a_key_is_a_sha_256} cannot
     * tell a hash of real garbage from a hash of a real URL, since every
     * SHA-256 has the same shape, so this refusal is the only thing standing
     * between a caller's typo and a stable-looking nonsense identity landing
     * in the store.
     */
    @Test
    void an_unparseable_string_is_refused_rather_than_hashed_as_given() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlKey.of("https://exa mple.com/a"));
    }
}
