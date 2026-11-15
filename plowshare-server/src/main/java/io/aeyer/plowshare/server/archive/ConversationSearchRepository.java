package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;

/**
 * Authorized lexical evidence and batched snapshot refresh. Implementations apply home,
 * conversation and current input visibility before statistics, grouping and pagination. Reads are
 * side-effect free and bounded; unavailable reads never become empty successful searches.
 */
public interface ConversationSearchRepository {
  ConversationSearchRepository forAccount(String account);

  default LogSearch search(Home home, String question, int skip, int most) {
    return search(home, question, skip, most, null);
  }

  LogSearch search(Home home, String question, int skip, int most, String conversation);

  /** Refreshes only the exact retained revisions; omission means gone, changed or inaccessible. */
  List<LogSearch.Source> searchSources(Home home, List<LogSearch.Reference> expected);

  LogSearch.Reach searchReach(Home home, String conversation);
}
