package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.events.InboxStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** {@code inbox.list} — the caller's own user-inbox, with its unread count. */
public final class InboxListHandler implements FrameHandler {

  record Body(Boolean unread, Integer offset, Integer limit) {}

  public record Page(List<InboxItem> items, int unread) {}

  private final Inbox inbox;
  private final InboxStore store;

  public InboxListHandler(Inbox inbox, InboxStore store) {
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String handle = asking.requireHandle(FrameTypes.INBOX_LIST);
    Body body = Payloads.as(payload, Body.class, FrameTypes.INBOX_LIST);
    int offset = body.offset() == null ? 0 : Math.max(0, body.offset());
    int limit = body.limit() == null ? 50 : Math.min(200, Math.max(1, body.limit()));
    return Outcome.ok(
        new Page(
            inbox.list(handle, Boolean.TRUE.equals(body.unread()), offset, limit),
            store.unread(handle)));
  }
}
