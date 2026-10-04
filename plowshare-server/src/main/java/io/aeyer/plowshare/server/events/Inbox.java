package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Delivery into a user-inbox, and telling that account's sockets the count changed. */
public class Inbox {

  public static final String CHANGED = "inbox.changed";

  private final InboxStore store;
  private final AccountPushes pushes;
  private final Supplier<Instant> clock;

  public Inbox(InboxStore store, AccountPushes pushes, Supplier<Instant> clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.pushes = Objects.requireNonNull(pushes, "pushes");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Every ending delivers, a cap included: the item's {@code ending} names which cap. An
   * event-started run cannot be continued from here — {@code Turn.originIsResumable} admits only
   * {@code turn} conversations, and resuming a capped event run is a follow-up (events spec §4.6).
   */
  public void deliver(String handle, String firing, String conversation, Outcome outcome) {
    deliver(handle, firing, conversation, outcome.ending().name(), outcome.text());
  }

  /** The same, with the text as it is to be read — a {@code delivery.pre} note appended, say. */
  public void deliver(
      String handle, String firing, String conversation, String ending, String text) {
    store.deliver(
        handle, firing, conversation == null ? "" : conversation, ending, text, clock.get());
    pushes.push(handle, changed(store.unread(handle)));
  }

  public List<InboxItem> list(String handle, boolean unreadOnly, int offset, int limit) {
    return store.list(handle, unreadOnly, offset, limit);
  }

  /** A notice no run produced — a sync conflict — to the same account-owned inbox. */
  public void notify(String handle, String kind, String text) {
    notify(handle, kind, text, null);
  }

  /**
   * {@link #notify(String, String, String)}, for a notice that asks the person something: {@code
   * about} names the question, and {@link #settle} takes the notice out of the inbox once it is
   * settled. Null is news.
   */
  public void notify(String handle, String kind, String text, String about) {
    store.notice(handle, kind, text, about, clock.get());
    pushes.push(handle, changed(store.unread(handle)));
  }

  public void notifyFromLog(String handle, String kind, String text, String log) {
    notifyFromLog(handle, kind, text, log, null);
  }

  public void notifyFromLog(String handle, String kind, String text, String log, String about) {
    store.noticeFromLog(handle, kind, text, log, about, clock.get());
    pushes.push(handle, changed(store.unread(handle)));
  }

  /**
   * The question {@code about} names is settled — answered, withdrawn, or no longer asked — so its
   * notices leave the inbox, and each account one left is told its new count at once, the way a new
   * notice is told, so a live client drops it.
   */
  public void settle(String about) {
    settle(about, clock.get());
  }

  /** {@link #settle(String)}, at a moment the caller already read. */
  public void settle(String about, Instant at) {
    store.settle(about, at).stream()
        .distinct()
        .forEach(handle -> pushes.push(handle, changed(store.unread(handle))));
  }

  public int read(String handle, List<String> ids) {
    int marked = store.markRead(handle, ids, clock.get());
    pushes.push(handle, changed(store.unread(handle)));
    return marked;
  }

  public static Map<String, Object> changed(int unread) {
    return Map.of("kind", CHANGED, "unread", unread);
  }
}
