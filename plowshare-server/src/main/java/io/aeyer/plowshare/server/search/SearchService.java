package io.aeyer.plowshare.server.search;

import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * One request, and the pages that follow it — {@code POST /v1/search}'s whole behaviour, above
 * {@link SearchLadder} and {@link ResultSetStore}, neither of which is a Spring bean until {@link
 * SearchConfig} wires it alongside this class.
 *
 * <h2>Four steps, in this order, and why the order is load-bearing</h2>
 *
 * <ol>
 *   <li>{@code key = QueryKey.of(query, max)} — never the raw query string. {@code
 *       V34__search_result_sets.sql} enforces this at the column with {@code
 *       search_result_sets_a_key_is_a_sha_256}, which exists precisely because a caller passing the
 *       raw string here would land a user's plaintext question in a column meant to carry nothing
 *       that could be turned back into one. This class is that caller, and it is right by
 *       construction rather than by the constraint catching it.
 *   <li><b>{@code page > 1} decides first.</b> {@link ResultSetStore#get} against {@code key} —
 *       present, slice {@code hits} for {@code page} and return. No provider is dialled.
 *   <li>{@code page > 1} and no live set: refuse, rather than run {@link SearchLadder#run} again.
 *       See below.
 *   <li>{@code page == 1}: <b>always</b> run the ladder, and never read the stored set. A refusal
 *       is returned and nothing is stored; a success is stored under {@code key} — replacing
 *       whatever that key already held — before page one is sliced from it.
 * </ol>
 *
 * <h2>Page one is never served from the stored set — re-asking is a new search</h2>
 *
 * <p>This is the line that makes "a result set, not a cache" a fact about the code rather than only
 * about the prose. Spec §6 argues that what distinguishes a cache is that it serves <em>a later,
 * different</em> search; spec §11 lists cross-search result reuse among the things this slice does
 * not build; and {@code V34__search_result_sets.sql} opens by stating that "two searches that ask
 * the same thing get two ladder runs and… the second {@code put} simply replaces the first's row
 * rather than reading it back". Reading the stored set on {@code page == 1} would falsify all three
 * at once, and it is the code that would be wrong.
 *
 * <p>The behavioural argument stands on its own, without the documents. <b>Re-asking a question is
 * a new search, and it must be able to get a fresh answer.</b> Nothing in this design is a
 * cache-bust: there is no {@code refresh} argument on the tool, no {@code DELETE} on a stored set,
 * no way for a caller to spell "again, properly". A caller served page one out of a half-hour-old
 * row therefore has no way to get past it — and no way to notice it needs to, because that page is
 * indistinguishable from one fetched a second ago. Paging is untouched by any of this: paging is
 * exactly the {@code page > 1} case, which still reads the set and still never re-walks the ladder.
 *
 * <p><b>The cost, accepted rather than glossed.</b> A caller that returns to page one pays a second
 * provider call, which is real quota spent against a rung that already answered this query. And a
 * 1→2→1 sequence re-runs the ladder on the second page one, whose {@link ResultSetStore#put}
 * replaces the set — so a caller that then asks for page 2 again is paging a different search's
 * results under the same key. Every page stays internally consistent with the set that served it,
 * which is what the refusal below protects; what is not guaranteed is that a set survives a caller
 * going back. That is the honest trade for freshness, and the trade it replaces — a caller silently
 * pinned to a stale answer with no way out — is worse for being silent.
 *
 * <h2>Step 3 refuses; it does not silently refetch</h2>
 *
 * <p>The tempting alternative — a miss on {@code page > 1} quietly runs the ladder again and serves
 * page {@code page} of whatever comes back — is wrong for a reason spec §6 states and this class
 * does not re-argue: {@link SearchLadder} makes no promise that two runs of the same query answer
 * from the same rung. The first run might have gone through {@code searxng}; by the time a stale
 * page 2 is asked for, {@code searxng} may have crossed {@link
 * SearchProperties#failureThresholdNow()} and the second run answers from {@code brave} instead.
 * Silently handing back "page 2" in that case is page 2 of a <em>different search</em>, stitched
 * onto page 1 of the first one, with nothing in the response saying so — a seam a model has no way
 * to notice and every reason to trust. A refusal it can read and act on (search again, from page
 * one) is a worse experience and a better one: it costs a turn, but it never returns hits
 * inconsistent with each other under one label.
 *
 * <h2>No provider key ever reaches {@link SearchPage} as the source of a result</h2>
 *
 * <p>{@link SearchPage}'s own javadoc states the rule this class exists to keep: which provider
 * <em>answered</em> is invisible to the model by design, spec §2's whole point in multiplexing
 * providers behind one tool. What answered a query is not gone — {@link SearchLadder#run} already
 * recorded it through {@link ProviderStore#recordOutcome}, and a tool invocation is logged wherever
 * this server logs one — but it is not part of the answer a model reads back, because a model that
 * could see which rung served it could also learn to ask for one by name, which defeats the reason
 * a ladder exists rather than a provider parameter.
 *
 * <p>The narrower claim is the true one, and the wider one used to be written here. A refusal out
 * of an exhausted ladder <em>does</em> name every rung and what became of it, and it travels to the
 * model in {@link SearchPage#refusal} — spec §5, §8 and §12 all require exactly that, because an
 * operator whose {@code plowshare.search.ladder} names a provider nobody registered learns of it
 * only through the refusal a model reads back. What never happens is a provider being named <em>as
 * the source of a hit</em>: the success path here carries {@code result.hits()} and nothing else
 * off {@link LadderResult}, and {@code result.providerKey()} is used only to key the stored row and
 * to tell a success from a refusal.
 *
 * <h2>A {@link Clock} in the constructor, not {@link Clock#systemUTC()} inline</h2>
 *
 * <p>Every duration in this class — how old a stored set is allowed to be — is measured against
 * "now", passed to {@link ResultSetStore#get} exactly as {@link SearchLadder}'s own tests pass a
 * properties object rather than letting a collaborator read the clock itself. A test proving step 3
 * above actually refuses a page whose set has aged past {@link SearchProperties#resultSetTtlNow()}
 * has to move "now" forward without waiting on a wall clock. {@link SearchConfig} wires {@link
 * Clock#systemUTC()}, on {@code DocumentsConfig} and {@code DeliberationConfig}'s own precedent for
 * a clock this server has no bean for.
 *
 * <h2>Malformed input is refused here, before step 1 ever runs</h2>
 *
 * <p>A blank {@code query} or a non-positive {@code max} reaches {@link
 * io.aeyer.plowshare.protocol.search.SearchAsk}'s own constructor two calls down inside {@link
 * SearchLadder#run} — but only on the {@code page == 1} path, and only once a rung is registered
 * and under {@link SearchProperties#failureThresholdNow()}. With the shipped default {@code ladder:
 * ""}, the same malformed request never reaches that constructor at all and comes back a plain 200
 * refusal instead. That is not a contract a caller can program against: whether a given bad request
 * throws or answers depends on server state the caller cannot see. Worse, an {@link
 * IllegalArgumentException} escaping from in here is not caught by {@code ApiExceptionHandler} and
 * falls to its {@code Throwable} fallback, which replies "this is a fault in the server, not in the
 * request" — false, for a mistake that was entirely the caller's, and exactly what that handler's
 * own javadoc says a status must never claim. So {@code query}, {@code max}, {@code page} and
 * {@code pageSize} are checked first, uniformly, and a failure throws {@link CallerFault} — the
 * caller-fault type for code that has no HTTP surface of its own, which this class does not —
 * rather than leaving it to whichever downstream constructor happens to notice first. {@code
 * ApiExceptionHandler} maps it to the identical 400 that {@code api.BadRequestException} gets.
 *
 * <p>{@code page} and {@code pageSize} are rejected outright rather than clamped. {@link #slice}'s
 * arithmetic would silently fold {@code page <= 0} into page one's own slice — a caller asking for
 * page 0 and one asking for page 1 would get identical hits under different labels, with nothing in
 * the response saying so — and a {@code pageSize <= 0} would return an empty slice with {@code
 * hasMore} true forever, on any non-empty result. Both are the caller's own {@code SearchPage}
 * literally lying about what it served, which is worse than refusing the request outright.
 */
@Service
public class SearchService {

  private final SearchLadder ladder;
  private final ResultSetStore sets;
  private final SearchProperties properties;
  private final Clock clock;

  public SearchService(
      SearchLadder ladder, ResultSetStore sets, SearchProperties properties, Clock clock) {
    this.ladder = ladder;
    this.sets = sets;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Answer one page of one search — see the class comment for the four steps this follows.
   *
   * @param query the search terms, folded into {@code key} by {@link QueryKey#of} rather than
   *     carried any further
   * @param pageSize how many hits one page holds
   * @param max the ceiling handed to {@link SearchLadder#run} on a fresh search — part of {@code
   *     key} too, since a caller asking for fifty results bought a different set than one asking
   *     for twenty-five of the same terms
   * @param page which page to serve, counting from one. Page one always runs the ladder; only a
   *     later page reads a stored set
   * @throws CallerFault if {@code query} is blank, or {@code max}, {@code page} or {@code pageSize}
   *     is not at least one — see the class comment for why this is checked here rather than left
   *     to {@link io.aeyer.plowshare.protocol.search.SearchAsk}'s own constructor
   */
  public SearchPage search(String query, int pageSize, int max, int page) {
    validate(query, pageSize, max, page);
    String key = QueryKey.of(query, max);
    Instant now = clock.instant();
    Duration ttl = properties.resultSetTtlNow();

    // page > 1 decides FIRST, and page 1 never looks at the store at all
    // -- see the class comment. Reading the set before this branch is
    // what would turn this table into a cross-search cache, which spec
    // §11 defers, spec §6 argues against by name, and V34's own comment
    // states does not happen.
    if (page > 1) {
      Optional<StoredSet> stored = sets.get(key, now, ttl);
      if (stored.isPresent()) {
        return slice(stored.get().hits(), page, pageSize);
      }
      return new SearchPage(
          List.of(),
          page,
          pageSize,
          0,
          false,
          "There is no stored result set for this query, or it has expired; search"
              + " again from page 1 to get a fresh one. Page "
              + page
              + " can only be served from a set that page 1 of the same search"
              + " already produced.");
    }

    LadderResult result = ladder.run(query, max, properties.ignoredDomainsNow());
    if (result.providerKey() == null) {
      return new SearchPage(List.of(), page, pageSize, 0, false, result.refusal());
    }

    sets.put(key, result.providerKey(), result.hits(), now);
    return slice(result.hits(), page, pageSize);
  }

  /**
   * Refuses the four inputs {@link #search} cannot do anything sensible with — see the class
   * comment for why this runs before step 1 rather than being left to a downstream constructor, and
   * why {@code page} and {@code pageSize} are rejected rather than clamped in {@link #slice}.
   */
  private static void validate(String query, int pageSize, int max, int page) {
    if (query == null || query.isBlank()) {
      throw new CallerFault("query must not be blank");
    }
    if (max < 1) {
      throw new CallerFault("max must be at least 1, was " + max);
    }
    if (page < 1) {
      throw new CallerFault("page must be at least 1, was " + page);
    }
    if (pageSize < 1) {
      throw new CallerFault("pageSize must be at least 1, was " + pageSize);
    }
  }

  /**
   * {@code hits}, cut down to {@code page}'s slice of {@code pageSize} — the one place both the
   * fresh-ladder path and the stored-set path meet, so a page is sliced the same way regardless of
   * which one produced {@code hits}.
   *
   * <p>A {@code page} past the end is not an error: it is an empty slice with {@code hasMore}
   * false, on {@link SearchLadder}'s own reasoning about a thin answer — a caller asking for a page
   * that is not there has not failed at anything, and there is nothing here to refuse.
   *
   * <p>No clamp on {@code page} or {@code pageSize} themselves — {@link #validate} has already
   * refused anything below one by the time this runs.
   *
   * <p><b>The offset is computed in {@code long}, and that is not belt-and-braces.</b> {@link
   * #validate} bounds both numbers below and neither above, so {@code page = 200000} with {@code
   * pageSize = 20000} — two values a caller is perfectly entitled to send — overflows {@code int}
   * and lands {@code (page - 1) * pageSize} on a <em>negative</em> offset, which {@link Math#min}
   * then happily prefers to {@code total} and {@link List#subList} answers with an {@code
   * IndexOutOfBoundsException}. That escapes to {@code ApiExceptionHandler}'s {@code Throwable}
   * fallback as a 500 saying "this is a fault in the server, not in the request" — the exact claim
   * this class's own comment above spends a section refusing to let a caller's input produce.
   * Widening to {@code long} costs one cast and removes the failure outright; bounding {@code page}
   * in {@link #validate} instead was rejected because it would refuse a page number that is merely
   * past the end, which this method's whole first paragraph says is not an error. Both {@code min}
   * results are at most {@code total}, an {@code int}, so both narrowing casts are exact by
   * construction.
   */
  private static SearchPage slice(List<Hit> hits, int page, int pageSize) {
    int total = hits.size();
    int from = (int) Math.min((long) (page - 1) * pageSize, total);
    int to = (int) Math.min((long) from + pageSize, total);
    return new SearchPage(hits.subList(from, to), page, pageSize, total, to < total, null);
  }
}
