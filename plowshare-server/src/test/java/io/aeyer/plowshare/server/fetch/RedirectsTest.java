package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

/** Spec §2.2: at most five hops, and never from https down to http. */
class RedirectsTest {

    private static final HttpUrl HTTP = HttpUrl.get("http://a.example/start");
    private static final HttpUrl HTTPS = HttpUrl.get("https://a.example/start");

    @Test
    void a_relative_location_resolves_against_the_hop() {
        assertEquals(new Redirects.Follow(HttpUrl.get("http://a.example/next?x=1")),
                Redirects.next(HTTP, "/next?x=1", 0));
        assertEquals(new Redirects.Follow(HttpUrl.get("https://b.example/x")),
                Redirects.next(HTTPS, "//b.example/x", 0),
                "a scheme-relative location keeps the hop's https");
    }

    @Test
    void five_redirects_are_followed_and_a_sixth_is_not() {
        assertInstanceOf(Redirects.Follow.class, Redirects.next(HTTP, "/6", 4),
                "four already followed, so this is the fifth");
        Redirects.Stop stop =
                assertInstanceOf(Redirects.Stop.class, Redirects.next(HTTP, "/7", 5));
        assertTrue(stop.message().contains("5 redirects"), stop.message());
    }

    @Test
    void https_to_http_is_refused() {
        Redirects.Stop stop = assertInstanceOf(Redirects.Stop.class,
                Redirects.next(HTTPS, "http://a.example/plain", 0));
        assertTrue(stop.message().contains("https to http"), stop.message());
    }

    @Test
    void every_other_scheme_pair_is_followed() {
        assertInstanceOf(Redirects.Follow.class,
                Redirects.next(HTTP, "https://a.example/secure", 0));
        assertInstanceOf(Redirects.Follow.class, Redirects.next(HTTPS, "https://b.example/", 0));
        assertInstanceOf(Redirects.Follow.class, Redirects.next(HTTP, "http://b.example/", 0));
    }

    @Test
    void a_location_that_is_not_http_is_a_stop() {
        Redirects.Stop stop = assertInstanceOf(Redirects.Stop.class,
                Redirects.next(HTTP, "file:///etc/passwd", 0));
        assertTrue(stop.message().contains("file:///etc/passwd"), stop.message());
        assertInstanceOf(Redirects.Stop.class, Redirects.next(HTTP, "ftp://a.example/", 0));
    }
}
