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

  /** Refreshes only the exact retained revisions; omission means gone, changed or inaccessible. */
  List<LogSearch.Source> searchSources(Home home, List<LogSearch.Reference> expected);

  LogSearch.Reach searchReach(Home home, String conversation);
}
