package io.aeyer.plowshare.server.fetch;

import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.search.SearchProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * One read of one page — {@code fetch(url, offset)}'s whole behaviour, over {@link PageFetcher} and
 * {@link FetchedPageStore}, on the algorithm the design spec's §4 through §8 argue in full and this
 * comment does not restate line by line. Six steps: validate at the edge; key the URL; present in
 * the buffer, serve it; absent with {@code offset} at zero, consult the ignore list before spending
 * a call; absent with a positive {@code offset}, refuse rather than fetch — see "A store miss with
 * a positive offset is a refusal" below; fetch and store on a miss at offset zero; slice from
 * {@code offset} either way.
 *
 * <h2>The liveness stamp is both guards at once</h2>
 *
 * <p>{@link FetchedPageStore#touchRead} runs on <em>every</em> read this class serves from the
 * buffer, not only the first, and that single write does two jobs that look unrelated until a race
 * is drawn out by hand. A row read inside {@code plowshare.fetch.live-window} is served to every
 * caller without a refetch — the whole reason a server-wide buffer exists rather than one scoped to
 * a conversation, since two agents reading the same page a minute apart should cost the network
 * once, not twice. The same write also protects that row from {@link
 * FetchedPageStore#purgeExpired}: a reader who is a page-window into a long document must not have
 * the row pulled out from under them the moment its {@code fetched_at} crosses the TTL, however old
 * that fetch happens to be, because the row being read is exactly the row a purge must not be free
 * to delete. Splitting these into two mechanisms — a refetch guard here and a separate eviction
 * guard elsewhere — would need two clocks doing the same job of answering "is somebody still
 * reading this", and the two would drift the moment only one of them was updated on a given code
 * path. One stamp, touched once per read, is what keeps that impossible rather than merely
 * unlikely.
 *
 * <h2>There is no change detection, and it was rejected rather than deferred</h2>
 *
 * <p>An earlier draft of this design carried {@code ETag}/{@code Last-Modified} conditional
 * requests, a content hash over the extracted text, and a version column, so that a window read
 * against text that had changed underneath it could refuse rather than silently splice two versions
 * of a page together. All of it is missing from this class on purpose. What an append-only log can
 * honestly claim is that this tool returned this text at this moment — never that the page is
 * stable, and never that a second fetch would answer the same way. Machinery built to defend a
 * guarantee this system never makes is machinery maintained for nothing, and it would have bought
 * nothing real: the ten-minute live window already covers the one case that actually matters, a
 * reader mid-page, without hashing a single byte to do it. A consequence worth stating rather than
 * leaving to be rediscovered: a page carrying a clock costs nothing here, because nothing
 * downstream of {@link FetchedPageStore} ever consults a hash to decide whether that clock still
 * means what it did.
 *
 * <h2>The ignore list is consulted here, and it is not a prohibition</h2>
 *
 * <p>{@link SearchProperties#ignoredDomainsNow()} is capability data about one fetcher, not a rule
 * about what a page is allowed to contain — Fox News refusing a headless request while serving the
 * identical page to a desktop browser is the worked example the design spec argues from. Step 4
 * below declines a suppressed host before spending a call, and the refusal it returns says exactly
 * that: <em>this deployment's</em> fetcher is blocked there, not that the page is forbidden. That
 * distinction is why a fetch provider registered on a higher rung later — one that carries a
 * browser engine rather than a bare HTTP client — is free to reach every domain on this list
 * without this class changing at all: the list describes a limitation of {@link
 * PageFetcher#fetch}'s current implementation, and tiering exists precisely so that limitation can
 * shrink without a redesign. A real prohibition — a domain nothing may ever fetch, regardless of
 * which fetcher is asked — has the opposite shape and is not built here; widening this list into
 * one would be answering a question nobody asked this class.
 *
 * <h2>Domain matching</h2>
 *
 * <p>An entry is either a literal host ({@code "blocked.example"}, matching that host exactly) or a
 * wildcard ({@code "*.blocked.example"}, matching that host and every subdomain of it). A literal
 * entry does <b>not</b> implicitly cover its subdomains — {@code SearchPropertiesTest} already
 * carries both spellings in the same list as arbitrary strings {@code SearchProperties} does not
 * interpret, so this is the first code to give either shape a meaning, and an operator who means
 * "this host and everything under it" has an explicit way to say so rather than that being every
 * literal entry's silent, wider effect.
 *
 * <h2>Step 3 serves the buffer; it never refetches</h2>
 *
 * <p>The row's age is irrelevant on a hit. A purge is what removes a row — see {@link
 * FetchedPageStore#purgeExpired} — and a row {@link FetchedPageStore#find} still returns is, by
 * definition, not yet purged, so there is nothing for a present row to be refreshed against.
 * Refetching on every read would defeat the entire point of a server-wide buffer for no benefit
 * this class can name.
 *
 * <h2>A store miss with a positive offset is a refusal, not a fresh fetch</h2>
 *
 * <p>The design spec's §5 argues that a reader mid-page is protected because "reading keeps the
 * page alive, so the case [a stale offset] existed for does not arise" — and for a caller that
 * keeps reading, that is true: every read inside the live window renews {@code last_read}, so the
 * row a paging sequence depends on is exactly the row the live window protects from {@link
 * FetchedPageStore#purgeExpired}. But "does not arise" overclaims for a caller that stops reading
 * and comes back later. A row is absent with {@code offset > 0} exactly when a reader went quiet
 * past {@code plowshare.fetch.live-window}, the fetch aged past {@code plowshare.fetch.ttl},
 * <em>and</em> something called {@code purgeExpired} in between — three conditions, not zero, which
 * is what makes this rare rather than impossible, and the liveness stamp is what keeps it rare: it
 * does not make the case stop existing.
 *
 * <p>Refetching silently in that case would be exactly the failure this class's own liveness-stamp
 * section above spends a paragraph ruling out for a live row: a caller's {@code offset}, computed
 * against one version of the page's text, would be spliced against whatever text a fresh fetch
 * happens to return — a different day's version of a page that changes, or the same page with
 * different boilerplate a template varies per request — with nothing in the response saying the
 * ground had shifted. So this method refuses instead: a miss with a positive {@code offset} returns
 * a refusal naming the situation and telling the caller to read again from offset zero, on {@code
 * SearchService}'s own reasoning for refusing rather than silently re-running a ladder when a
 * stored result set has gone missing out from under a page-two request. A miss with {@code offset}
 * at zero is the ordinary case — a page never read before — and is unaffected: it is what step 5
 * exists for.
 *
 * <h2>A {@link Clock} in the constructor</h2>
 *
 * <p>{@code SearchService}'s own reasoning, unchanged: every timestamp this class writes or
 * compares is measured against "now", and a test proving the live-window guard actually holds a
 * page alive across a gap has to move "now" forward without waiting on a wall clock. {@link
 * FetchConfig} wires {@link Clock#systemUTC()}.
 *
 * <h2>Malformed input is refused at the edge, before step 2 ever runs</h2>
 *
 * <p>A blank {@code url} or a negative {@code offset} is refused with {@link CallerFault}, {@code
 * SearchService.validate}'s own shape. A third check joins them: a {@code url} that does not even
 * parse as a URI is refused here too, rather than being handed to {@link UrlKey#of}, whose own
 * javadoc calls its identical {@link IllegalArgumentException} "a backstop, not the primary path,
 * because {@code FetchService.read} validates at the edge first" — this method is what makes that
 * sentence true. <b>An unparseable string and a parseable URL naming a scheme this fetcher will not
 * dial are not the same mistake and must not collapse into one.</b> {@code file:///etc/passwd}
 * parses perfectly well as a URI; it names a place {@link BuiltinFetcher} refuses to go, which is a
 * fact learned by spending a fetch and reading its answer back as an ordinary refusal — the caller
 * asked a coherent question and got a true "no". {@code "https://e xample.com/a"}, by contrast, is
 * not a URL at all: {@link URI}'s constructor cannot even parse it, so there is no host to key it
 * under and no meaningful answer to give beyond "this was never well-formed", which is the caller's
 * own mistake and is rejected before either the store or the fetcher is ever asked about it.
 */
@Service
public class FetchService {

  private final PageFetcher fetcher;
  private final FetchedPageStore store;
  private final FetchProperties properties;
  private final SearchProperties searchProperties;
  private final Clock clock;

  public FetchService(
      PageFetcher fetcher,
      FetchedPageStore store,
      FetchProperties properties,
      SearchProperties searchProperties,
      Clock clock) {
    this.fetcher = fetcher;
    this.store = store;
    this.properties = properties;
    this.searchProperties = searchProperties;
    this.clock = clock;
  }

  /**
   * Answer one window of one page — see the class comment for the six steps this follows.
   *
   * @param url the page to read, exactly as a caller supplied it — kept verbatim in the returned
   *     {@link FetchWindow} and in {@link FetchedPageStore#put} rather than any normalised form
   * @param offset where to start this window, counting characters into the page's whole stored
   *     text. {@code 0} on a page never read before
   * @throws CallerFault if {@code url} is blank, does not parse as a URI, or {@code offset} is
   *     negative — see the class comment for why a URI that parses but names an undiallable scheme
   *     is not included here
   */
  public FetchWindow read(String url, int offset) {
    validate(url, offset);
    String key = UrlKey.of(url);
    Instant now = clock.instant();

    Optional<FetchedPage> stored = store.find(key);
    if (stored.isPresent()) {
      // The row's age is irrelevant on a hit -- see the class comment.
      // touchRead runs before the slice is cut so a read that is about
      // to be served is the read that keeps this row alive, not a read
      // that merely happened to look at it.
      store.touchRead(key, now);
      FetchedPage page = stored.get();
      // url, not page.url(): the record's own @param contract promises
      // the caller's own spelling back verbatim, and a second caller
      // can reach the same row under a differently-cased host (UrlKey
      // normalises only the host for hashing) without inheriting
      // whichever spelling happened to fetch the page first.
      return window(url, page.title(), page.text(), offset);
    }

    if (offset > 0) {
      // See the class comment's "A store miss with a positive offset"
      // section: refusing here, rather than falling through to a fresh
      // fetch, is what stops a caller's old offset from being spliced
      // against a different fetch's text. offset == 0 is the ordinary
      // never-fetched-before case and falls through as normal.
      return refusal(
          url,
          offset,
          "there is no stored page for "
              + url
              + " to read at offset "
              + offset
              + " -- it was either never fetched, or has since been purged. Read"
              + " again from offset 0 to fetch it fresh; this deployment will not"
              + " silently splice a new fetch's text onto an old paging offset.");
    }

    Optional<String> suppressedBy = suppressedBy(url);
    if (suppressedBy.isPresent()) {
      return refusal(
          url,
          offset,
          "this deployment's fetcher is blocked at "
              + suppressedBy.get()
              + ", and "
              + hostOf(url)
              + " matches that entry in"
              + " plowshare.search.ignored-domains. This is not a prohibition on"
              + " the page: a fetch provider on a higher rung is not bound by this"
              + " deployment's own fetcher and may be able to reach it.");
    }

    FetchAnswer answer = fetcher.fetch(url);
    if (!answer.isOk()) {
      return refusal(
          url,
          offset,
          "fetching " + url + " failed (" + answer.failure() + "): " + answer.message());
    }

    store.put(key, url, answer.page(), now);
    return window(url, answer.page().title(), answer.page().text(), offset);
  }

  /**
   * Refuses the two inputs this method cannot do anything sensible with, plus the one carried
   * forward from {@link UrlKey}'s own javadoc — see the class comment's last section for why an
   * unparseable string is refused here rather than left to {@link UrlKey#of}'s backstop.
   */
  private static void validate(String url, int offset) {
    if (url == null || url.isBlank()) {
      throw new CallerFault("url must not be blank");
    }
    if (offset < 0) {
      throw new CallerFault("offset must not be negative, was " + offset);
    }
    try {
      new URI(url);
    } catch (URISyntaxException unparseable) {
      throw new CallerFault("url is not a URL and cannot be read: " + url, unparseable);
    }
  }

  /**
   * The ignore-list entry {@code url}'s host matches, if any — see the class comment's "Domain
   * matching" section for what a literal entry and a {@code *.} entry each cover.
   *
   * <p>Package-private rather than {@code private}, on purpose: {@code FetchServiceTest} exercises
   * the wildcard-versus-literal and case-insensitivity rules directly against this method rather
   * than through {@link #read}, because the only network-safe way to prove a host was <em>not</em>
   * suppressed through {@link #read} is to actually dial it — and this project's own convention,
   * {@code InvariantsTest.no_source_names_the_reference_box}'s reasoning in full, is that no test
   * may reach a real remote host at all. Testing this method's return value directly proves the
   * matching rule without ever touching {@link #fetcher}.
   */
  Optional<String> suppressedBy(String url) {
    String host = hostOf(url);
    if (host == null) {
      return Optional.empty();
    }
    String lowerHost = host.toLowerCase(Locale.ROOT);
    List<String> ignored = searchProperties.ignoredDomainsNow();
    for (String pattern : ignored) {
      if (pattern.startsWith("*.")) {
        String suffix = pattern.substring(2);
        if (lowerHost.equals(suffix) || lowerHost.endsWith("." + suffix)) {
          return Optional.of(pattern);
        }
      } else if (lowerHost.equals(pattern)) {
        return Optional.of(pattern);
      }
    }
    return Optional.empty();
  }

  /**
   * {@code url}'s host, or {@code null} if it has none — {@code url} has already passed {@link
   * #validate}, so the only way this throws is a URI with no authority component at all, which has
   * no host to name.
   */
  private static String hostOf(String url) {
    try {
      return new URI(url).getHost();
    } catch (URISyntaxException unreachable) {
      return null;
    }
  }

  /**
   * {@code refusal} carried in an otherwise-empty {@link FetchWindow} — {@code offset} is echoed
   * back rather than {@code 0}, on {@code SearchPage}'s own precedent of echoing the caller's own
   * {@code page} number back on a refusal: a caller re-reading its own request against the answer
   * should see the offset it asked for, not one this method invented.
   */
  private static FetchWindow refusal(String url, int offset, String message) {
    return new FetchWindow(url, null, null, offset, offset, 0, false, message);
  }

  /**
   * {@code text}, cut to {@code plowshare.fetch.window} characters from {@code offset} — preferring
   * a block boundary in the last quarter of that span over the configured character count, on the
   * design spec's §6. {@code offset} past the end of {@code text} is not an error, on {@code
   * Window.cut}'s own reasoning for a line offset past a file's end: it is how a caller paging
   * forward learns to stop, and costs it nothing to discover.
   *
   * <p><b>The offset arithmetic below is computed in {@code long}.</b> {@link #validate} bounds
   * {@code offset} only below, at zero, so a caller supplying {@code Integer.MAX_VALUE} is not
   * misbehaving — it does not yet know how long the page is, which is exactly the answer this
   * method's {@code total} tells it. {@code offset + window} in {@code int} arithmetic would
   * overflow for such a caller and could hand back a negative {@code end}, on {@code
   * SearchService.slice}'s own argument for the identical widening.
   *
   * <h2>Review-flagged Critical 1: the quarter is measured against the span actually being cut, and
   * a cut is required to advance</h2>
   *
   * <p>An earlier version of this method measured "the last quarter" as {@code windowSize / 4} — a
   * constant — rather than against how much text this call is actually slicing. That is wrong
   * whenever the remainder from {@code offset} to {@code total} is itself smaller than a quarter of
   * the configured window: {@code
   * paging_to_exhaustion_terminates_and_reconstructs_the_stored_text}'s own fixture has a
   * 12-character remainder against a 2,000-character constant quarter, so the "last quarter"
   * swallowed the whole remainder, including the separator sitting exactly at {@code offset} — the
   * one a previous call had just cut in front of — and the read returned an empty window while
   * still reporting {@code hasMore}. A caller paging forward through that never reaches the end.
   * Measuring the quarter as {@code (end - offset) / 4} — a quarter of the span this call is
   * actually cutting, never wider than that span — removes the case entirely: a boundary this close
   * to {@code offset} can no longer fall inside the (now much narrower) window this call is willing
   * to prefer.
   *
   * <p>{@code cutEnd} is also fixed at {@code boundary + 2} rather than {@code boundary} itself, so
   * a cut lands <em>after</em> the separator: the next window opens on the first character of the
   * next block rather than on the blank line just cut in front of, which is what let {@code
   * nextOffset} sit exactly on the same separator a subsequent call would immediately re-find. And
   * {@code cutEnd <= offset} is refused outright and falls back to the plain {@code end} — a
   * residual guard that should be unreachable given the two fixes above (a boundary this method
   * accepts is always at or past {@code quarterStart}, which is always at or past {@code offset},
   * so {@code cutEnd = boundary + 2} is always strictly greater than {@code offset}), but a window
   * method that can return a version of itself that stalls a caller forever is worth a guard that
   * does not depend on that chain of reasoning staying intact the next time this method is edited.
   *
   * <p><b>What was considered and rejected: gating the whole boundary search on {@code end <
   * total}.</b> A prior review draft of this fix proposed skipping the boundary search entirely
   * whenever this call's naive window already reaches the end of the page, on the reasoning that
   * there is no later window for a mid-block split to matter to. That is incompatible with both the
   * design spec's own wording of §6 — "if a {@code \n\n} falls within the last quarter of the
   * window, cut there instead", with no exception carved out for a window that happens to reach the
   * page's end — and with {@code
   * a_window_prefers_a_block_boundary_and_reports_where_it_actually_reached}, whose fixture is a
   * 7,912-character page against an 8,000-character window: {@code end} equals {@code total} on
   * that very first read, and the test still requires the cut to fire. Gating on {@code end <
   * total} would return "next block" inline with the rest of the text on that read, failing a
   * pinned test. The span-relative quarter above is what actually removes the infinite loop,
   * without that trade.
   */
  private FetchWindow window(String url, String title, String text, int offset) {
    int total = text.length();
    if (offset >= total) {
      return new FetchWindow(url, title, "", offset, offset, total, false, null);
    }
    int windowSize = properties.getWindow();
    long endLong = Math.min((long) offset + windowSize, total);
    int end = (int) endLong;

    int span = end - offset;
    int quarterStart = Math.max(offset, end - span / 4);
    int cutEnd = end;
    int searchFrom = end - 2;
    if (searchFrom >= offset) {
      int boundary = text.lastIndexOf("\n\n", searchFrom);
      if (boundary >= quarterStart) {
        cutEnd = boundary + 2;
      }
    }
    if (cutEnd <= offset) {
      // Residual guard -- see the section above. Unreachable given the
      // two fixes above hold, and cheap insurance against a window
      // that would otherwise never advance if they stop holding.
      cutEnd = end;
    }

    String slice = text.substring(offset, cutEnd);
    boolean hasMore = cutEnd < total;
    return new FetchWindow(url, title, slice, offset, cutEnd, total, hasMore, null);
  }
}
