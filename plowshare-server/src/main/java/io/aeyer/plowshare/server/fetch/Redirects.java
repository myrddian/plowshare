package io.aeyer.plowshare.server.fetch;

import okhttp3.HttpUrl;

/**
 * What {@link BuiltinFetcher} does with one {@code 3xx} that carries a {@code Location}: follow it
 * to the next hop, or stop with a sentence (spec §2.2).
 *
 * <p>Fetch's clients never follow a redirect themselves. OkHttp's own following would dial the next
 * hop with no second look, and a public page that answers {@code 302 Location:
 * http://169.254.169.254/} is exactly the hop that needs one. Every hop this returns is checked
 * again by {@link BuiltinFetcher}, early on its URL and then at connect.
 *
 * <p>Five hops, not OkHttp's twenty. That covers http → https → canonical host → locale → page with
 * one to spare (spec cost 3).
 */
final class Redirects {

  /** The most redirects one fetch follows. The sixth is a stop. */
  static final int MAX_HOPS = 5;

  /** Follow, or stop. */
  sealed interface Step permits Follow, Stop {}

  /** Dial {@code next}, which must still pass the early and the connect-time checks. */
  record Follow(HttpUrl next) implements Step {}

  /** Do not dial. {@code message} is the {@link FetchFailure#FETCH_FAILED} sentence. */
  record Stop(String message) implements Step {}

  private Redirects() {}

  /**
   * @param from the hop that answered the redirect
   * @param location its {@code Location} header, as sent, possibly relative
   * @param followed how many redirects this fetch has already followed
   */
  static Step next(HttpUrl from, String location, int followed) {
    if (followed >= MAX_HOPS) {
      return new Stop(
          "more than "
              + MAX_HOPS
              + " redirects; "
              + from.host()
              + " redirected again and the chain was not followed further");
    }
    HttpUrl next = from.resolve(location);
    if (next == null) {
      return new Stop("redirected to a location that is not an http(s) url: " + location);
    }
    if (from.isHttps() && !next.isHttps()) {
      return new Stop("refused: a redirect from https to http (" + next.host() + ")");
    }
    return new Follow(next);
  }
}
