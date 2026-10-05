package io.aeyer.plowshare.server.events;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

  InboxItem notice(String handle, String kind, String text, Instant at);

  InboxItem notice(String handle, String kind, String text, String about, Instant at);

  InboxItem noticeFromLog(String handle, String kind, String text, String log, Instant at);

  InboxItem noticeFromLog(
      String handle, String kind, String text, String log, String about, Instant at);

  List<String> settle(String about, Instant at);

  List<InboxItem> list(String handle, boolean unreadOnly, int offset, int limit);

  int markRead(String handle, List<String> ids, Instant at);

  int unread(String handle);

  int unreadSinceLastTurn(String handle, String conversation);

  Optional<Instant> newestUnreadSinceLastTurn(String handle, String conversation);
}
