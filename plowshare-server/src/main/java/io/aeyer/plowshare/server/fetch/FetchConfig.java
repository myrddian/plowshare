package io.aeyer.plowshare.server.fetch;

import io.aeyer.plowshare.server.buffers.Buffers;
import io.aeyer.plowshare.server.search.ResultSetStore;
import io.aeyer.plowshare.server.search.SearchProperties;
import java.net.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link FetchProperties}, refuses to start on a number that could not be honoured —
 * {@code SearchConfig}'s and {@code DocumentsConfig}'s own rule, applied to the four values this
 * slice adds — and wires {@link PageFetcher} and {@link FetchService}, the two beans this slice
 * built but did not itself wire.
 *
 * <h2>{@code okhttp3.OkHttpClient}, named fully-qualified rather than imported</h2>
 *
 * <p>{@link #pageFetcher} takes the one {@code OkHttpClient} {@code SearchConfig#searchHttpClient}
 * already built, and {@link #guardedFetcher} derives fetch's own clients from it once, at boot. The
 * type is spelled out in full rather than imported: {@code InvariantsTest}'s {@code
 * an_http_client_is_held_by_exactly_seven_files_in_main} counts a literal {@code import} line
 * naming okhttp3's client type across {@code main}, by that test's own admission "not seen" by a
 * fully-qualified use, and this class was never meant to become an eighth holder.
 *
 * <h2>Fetch's clients are derived, and search's is left alone</h2>
 *
 * <p>Spec §2.6 of {@code 2026-09-28-fetch-stays-on-the-public-web-design.md}. Search registers
 * SearXNG on {@code localhost}, so a guard on the shared bean would break search. {@link
 * #guardedFetcher} builds one strict client and one client per {@code
 * plowshare.fetch.allow-private} entry with {@code newBuilder()}, each with no redirects, {@code
 * Proxy.NO_PROXY}, {@link GuardedSockets} and <b>its own connection pool</b>. {@code NO_PROXY}
 * matters: behind a JVM-wide proxy the socket would connect to the proxy, and the guard would judge
 * the proxy's address instead of the page's. The separate pool matters because OkHttp 4.12's {@code
 * Address} equality compares the proxy but not the socket factory. With a shared pool, an HTTP/2
 * connection an entry's exempt socket opened could be coalesced onto a strict-client request for
 * another host, and that request would never reach a {@code connect} to be judged.
 *
 * <h2>{@link Clock#systemUTC()}, not a {@code Clock} bean</h2>
 *
 * <p>{@code SearchConfig}'s own reasoning: this server has no {@code Clock} bean, and inventing one
 * here for {@link FetchService} alone would make every context that boots this class carry a bean
 * the rest of the server does not use.
 *
 * <p>All four bounds are checked, live and bound-once alike, on {@code
 * DocumentsConfig#ingestService}'s own reasoning for checking a live key as bound: {@code
 * RuntimeConfigSeed} writes the runtime map in {@code afterSingletonsInstantiated}, which runs
 * after every {@code @Configuration} class, so a check against the live accessor here would read
 * the map at the one moment it has not yet been reconciled with the environment. Reading {@link
 * FetchProperties#getTtl()} and {@link FetchProperties#getLiveWindow()} — the plain, bound getters
 * — checks what an operator configured, which is what a boot-time refusal should be about.
 */
@Configuration
@EnableConfigurationProperties(FetchProperties.class)
public class FetchConfig {

  /**
   * {@code plowshare.fetch.allow-private}, parsed once. It is parsed here, in the constructor, so a
   * bad entry refuses the boot beside this class's other boot checks, not later at the first fetch.
   */
  private final FetchAllowlist allowlist;

  /**
   * @throws IllegalStateException if {@code ttl}, {@code live-window}, or {@code timeout} is not a
   *     positive duration, {@code window} is not a positive number of characters, or an {@code
   *     allow-private} entry is malformed or names a never-tier literal. It names the key and the
   *     value that could not be honoured.
   */
  public FetchConfig(FetchProperties props) {
    requirePositive(
        "plowshare.fetch.ttl",
        props.getTtl(),
        "how long a fetched page is kept before it is a candidate for purging");
    requirePositive(
        "plowshare.fetch.live-window",
        props.getLiveWindow(),
        "how recently a page must have been read for it to count as still being read");
    requirePositive(
        "plowshare.fetch.timeout", props.getTimeout(), "the budget one fetch call is given");

    if (props.getWindow() < 1) {
      throw new IllegalStateException(
          "plowshare.fetch.window is "
              + props.getWindow()
              + "; it must be at least"
              + " 1. It is how many characters of a stored page's text one"
              + " page-turn hands a model, and a window below one would hand over"
              + " nothing at all");
    }

    this.allowlist = FetchAllowlist.parse(props.getAllowPrivate());
  }

  /**
   * Refuses a null, zero, or negative duration, naming {@code key}, the value that could not be
   * honoured, and what the duration governs — {@code SearchConfig}'s own three-duration pattern,
   * folded into one method rather than repeated three times with the key and the sentence as the
   * only things that differ.
   */
  private static void requirePositive(String key, Duration value, String meaning) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(
          key
              + " is "
              + value
              + "; it must be a positive duration. It is "
              + meaning
              + ", and zero or negative holds nothing at all");
    }
  }

  /**
   * The one out-of-process port {@link FetchService} dials through, guarded — see the class comment
   * for why {@code http} is spelled fully-qualified and why fetch's clients are derived from it
   * rather than shared.
   */
  @Bean
  public PageFetcher pageFetcher(okhttp3.OkHttpClient http, FetchProperties props) {
    return guardedFetcher(http, allowlist, props.getTimeout());
  }

  /**
   * A {@link BuiltinFetcher} over fetch's own clients, derived once from {@code base}: a strict
   * one, and one per {@code allowlist} entry exempting that entry's port. This is the only way a
   * {@link BuiltinFetcher} is built. The bean above calls it at boot, and tests call it with their
   * own base client and allowlist.
   */
  public static BuiltinFetcher guardedFetcher(
      okhttp3.OkHttpClient base, FetchAllowlist allowlist, Duration timeout) {
    Map<FetchAllowlist.Entry, okhttp3.OkHttpClient> exempt = new LinkedHashMap<>();
    for (FetchAllowlist.Entry entry : allowlist.entries()) {
      exempt.put(entry, derive(base, GuardedSockets.exempting(entry.port())));
    }
    return new BuiltinFetcher(derive(base, GuardedSockets.strict()), exempt, allowlist, timeout);
  }

  /** {@code base} with no redirects, no proxy, guarded sockets and a pool of its own. */
  private static okhttp3.OkHttpClient derive(okhttp3.OkHttpClient base, GuardedSockets sockets) {
    return base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .socketFactory(sockets)
        .connectionPool(new okhttp3.ConnectionPool())
        .build();
  }

  /**
   * {@code fetch(url, offset)}'s whole behaviour — see {@link FetchService}'s own javadoc for the
   * six steps and {@link Clock#systemUTC()}.
   */
  @Bean
  public FetchService fetchService(
      PageFetcher fetcher,
      FetchedPageStore store,
      FetchProperties props,
      SearchProperties searchProperties) {
    return new FetchService(fetcher, store, props, searchProperties, Clock.systemUTC());
  }

  /**
   * The collaborator behind {@code BufferPurgeController} — see {@link Buffers}' own class comment
   * for why it lives in neither {@code fetch} nor {@code search} despite reading a store from each,
   * and for why it is wired here rather than self-annotated: it needs a {@link Clock} exactly as
   * {@link #fetchService} above does, and {@link Clock#systemUTC()} on this class's own reasoning,
   * not a bean, is what supplies one.
   */
  @Bean
  public Buffers buffers(
      FetchedPageStore pages,
      ResultSetStore resultSets,
      FetchProperties fetchProperties,
      SearchProperties searchProperties) {
    return new Buffers(pages, resultSets, fetchProperties, searchProperties, Clock.systemUTC());
  }
}
