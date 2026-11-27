package io.aeyer.plowshare.server.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Specialist persistence contract. SQL, row decoding and guarded transitions belong to its JDBC
 * implementation.
 */
public interface InboxStore {
  String PREFIX = "inb_";
  String KIND_RUN = "run";
  String KIND_SYNC_CONFLICT = "sync.conflict";
  String KIND_HOOK = "hook";

  void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs);

  InboxItem deliver(
      String handle, String firing, String conversation, String ending, String answer, Instant at);

  /**
   * Reads one exact account-owned scheduled result under current visibility, without acknowledging
   * it.
   */
  Optional<InboxItem> scheduledResult(String handle, String id, String firing);

  /** Current visibility of an exact scheduled result, without reading its content or marking it. */
  boolean canSeeScheduledResult(String handle, String id, String firing);

  /** Exact recipient-owned Application copy, independently of the original's read state. */
  Optional<InboxItem> attentionResult(String handle, long application, String id, String source);

  boolean canSeeAttentionResult(
      String handle, long application, String id, String source, String kind);

  InboxItem notice(String handle, String kind, String text, Instant at);

  InboxItem notice(String handle, String kind, String text, String about, Instant at);

  InboxItem noticeFromLog(String handle, String kind, String text, String log, Instant at);

  InboxItem noticeFromLog(
      String handle, String kind, String text, String log, String about, Instant at);

  /** One retained notification per account/question, including repeated or concurrent delivery. */
  InboxItem noticeOnceFromLog(
      String handle, String kind, String text, String log, String about, Instant at);

  /**
   * Atomically reserve an alert attempt for an unread, unsettled question. This is an attempt,
   * never a receipt: a crash or a lost push leaves it eligible again after the retry interval.
   * Concurrent attempts cannot reserve the same interval. Read and settlement state is preserved.
   */
  boolean claimQuestionAlert(String handle, String id, Instant at, Duration retryInterval);

  /** Immutable reference receipt and whether this delivery created the Inbox item. */
  record GoalNotice(String id, boolean inserted) {}

  /**
   * Insert or recover one Goal Intent in the named account's Inbox under its current log access.
   */
  GoalNotice goalNotice(
      String account, UUID goal, String conversation, UUID intent, GoalInbox.Kind kind, Instant at);

  /** Settles only this account's exact Goal question. */
  boolean settleGoalQuestion(String account, UUID goal, UUID intent, Instant at);

  List<String> settle(String about, Instant at);

  List<InboxItem> list(String handle, boolean unreadOnly, int offset, int limit);

  record Page(List<InboxItem> items, int unread) {
    public Page {
      items = List.copyOf(items);
      if (unread < 0) throw new IllegalArgumentException("Invalid unread count");
    }
  }

  /** Page and unread count share one current visibility evaluation and one database snapshot. */
  Page page(String handle, boolean unreadOnly, int offset, int limit);

  /**
   * Unread ordinary items for a bot to consume. Human approval notices are excluded before
   * pagination, so retaining their unread receipts cannot hide older ordinary deliveries.
   */
  List<InboxItem> unreadForAgent(String handle, int offset, int limit);

  int markRead(String handle, List<String> ids, Instant at);

  int unread(String handle);

  int unreadSinceLastTurn(String handle, String conversation);

  Optional<Instant> newestUnreadSinceLastTurn(String handle, String conversation);
}
