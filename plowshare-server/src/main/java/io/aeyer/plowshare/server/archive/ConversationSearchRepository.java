package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import java.util.function.Supplier;

/**
 * Authorized lexical evidence and batched snapshot refresh. Implementations apply home,
 * conversation and current input visibility before statistics, grouping and pagination. Reads are
 * side-effect free and bounded; unavailable reads never become empty successful searches.
 */
public interface ConversationSearchRepository {
  ConversationSearchRepository forAccount(String account);

  /** Resolve one project selection under the read deadline; reuse it only within this operation. */
  default <T> T withinSearchScope(Home home, Supplier<T> operation) {
    return operation.get();
  }

  default LogSearch search(Home home, String question, int skip, int most) {
    return search(home, question, skip, most, null);
  }

  LogSearch search(Home home, String question, int skip, int most, String conversation);

  /** Includes explicitly supplied context only when requested, before scoring and pagination. */
  default LogSearch search(
      Home home, String question, int skip, int most, String conversation, boolean includeInputs) {
    if (includeInputs) throw new UnsupportedOperationException("Full-input search is unavailable");
    return conversation == null
        ? search(home, question, skip, most)
        : search(home, question, skip, most, conversation);
  }

  /**
   * Bounded lexical search over evidence text (supplied context excluded).
   *
   * <p>Home, conversation and current input visibility are applied before corpus statistics,
   * scoring, grouping and pagination, exactly as for {@link #search}; a hidden entry contributes to
   * no count, rank or source reference. The page, its order and its ranks are those of an
   * exhaustive search whichever way {@code countEveryMatch} is set.
   *
   * <p><b>{@code countEveryMatch} true:</b> the implementation reads every match, so {@link
   * LogSearch#total()} and each hit's {@link LogSearch.Hit#sourceCount()} are exact, unless the
   * implementation's documented grouping window applies. Then {@link BoundedLogSearch#truncated()}
   * is true and the total counts the groups inside that window, which is the same for every page of
   * the question, so offset paging up to it is stable. {@code EntryStore} groups the {@code
   * MOST_GROUPED_ENTRIES} best-scoring matching entries.
   *
   * <p><b>{@code countEveryMatch} false:</b> the implementation may skip matches that cannot reach
   * the requested page. When it does, {@code truncated} is true and the total, source counts and
   * source references are <b>lower bounds</b> covering only the entries read, which can be little
   * more than the page and can differ between pages. A caller that pages by the total must not ask
   * for this.
   *
   * <p>Whenever {@code truncated} is true a caller must say so instead of presenting the total as
   * the number of matches; when it is false the counts are exact. The default delegates to {@link
   * #search}, which counts every match and is never truncated, so a repository without a bounded
   * implementation keeps its exact answer. Full-input search is not offered here; use {@link
   * #search(Home, String, int, int, String, boolean)}.
   *
   * @param home the tier to search; never widened by a missing or foreign conversation
   * @param question web-search syntax
   * @param skip groups to pass over, from 0
   * @param most groups to return
   * @param conversation a conversation that narrows the home, or null for the whole home
   * @param countEveryMatch whether totals and source counts must cover every match the
   *     implementation can group, at the cost of reading them all
   * @return the page and whether its totals are bounded
   */
  default BoundedLogSearch searchBounded(
      Home home,
      String question,
      int skip,
      int most,
      String conversation,
      boolean countEveryMatch) {
    return new BoundedLogSearch(search(home, question, skip, most, conversation, false), false);
  }

  /** Refreshes only the exact retained revisions; omission means gone, changed or inaccessible. */
  List<LogSearch.Source> searchSources(Home home, List<LogSearch.Reference> expected);

  LogSearch.Reach searchReach(Home home, String conversation);
}
