package io.aeyer.plowshare.server.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Project-owned output, separate from account-owned Inbox items and private execution logs. */
public interface ApplicationInboxStore {
  Optional<Long> project(String name);

  /**
   * Resolve source ancestry and the orchestration root caller in one bounded snapshot. Empty covers
   * foreground, missing or incomplete provenance, and global work.
   */
  Optional<String> backgroundProject(String source);

  /**
   * Atomically retain the application item and attention copies for the supplied current Managers.
   * The key identifies one durable lifecycle event; conflicting reuse is refused. Returns only
   * accounts with a newly inserted personal item, for post-commit socket hints.
   */
  List<String> append(long project, String key, Notice notice, List<String> managers, Instant at);

  record Notice(
      String author,
      String kind,
      String source,
      String ending,
      String text,
      String about,
      boolean attention) {
    public Notice {
      if (author == null
          || author.isBlank()
          || kind == null
          || kind.isBlank()
          || text == null
          || text.isBlank()
          || text.length() > 131072
          || text.indexOf('\0') >= 0)
        throw new IllegalArgumentException("A bounded application notice is required");
    }
  }

  InboxStore.Page page(long project, boolean unread, int offset, int limit);

  /** Exact published result eligible for a subscription, with no acknowledgement. */
  Optional<InboxItem> subscriptionItem(long project, String item);

  boolean containsSubscriptionItem(long project, String item, String kind);

  int read(long project, List<String> ids, Instant at);

  int unread(long project);

  /** Reserve one unread human-question socket alert without creating or reviving an item. */
  boolean claimQuestionAlert(long project, String key, String manager, Instant at, Duration retry);

  /** Exact account-owned publication receipt; absence never authorizes automatic replay. */
  boolean published(long project, String author, UUID request);
}
