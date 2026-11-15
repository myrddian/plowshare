package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.ApplicationInbox;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** {@code inbox.list} — the caller's own user-inbox, with its unread count. */
public final class InboxListHandler implements FrameHandler {

  record Body(Boolean unread, Integer offset, Integer limit, String project) {}

  public record Page(List<InboxItem> items, int unread) {}

  private final Inbox inbox;
  private ApplicationInbox applications;

  public InboxListHandler(Inbox inbox, ApplicationInbox applications) {
    this(inbox);
    this.applications = applications;
  }

  public InboxListHandler(Inbox inbox) {
    this.inbox = Objects.requireNonNull(inbox, "inbox");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String handle = asking.requireHandle(FrameTypes.INBOX_LIST);
    if (payload != null
        && payload.containsKey("project")
        && !(payload.get("project") instanceof String))
      throw new CallerFault("Application project must be a name; omit it for the personal Inbox");
    Body body = Payloads.as(payload, Body.class, FrameTypes.INBOX_LIST);
    int offset = body.offset() == null ? 0 : Math.max(0, body.offset());
    int limit = body.limit() == null ? 50 : Math.min(200, Math.max(1, body.limit()));
    var page =
        body.project() == null
            ? inbox.page(handle, Boolean.TRUE.equals(body.unread()), offset, limit)
            : Objects.requireNonNull(applications)
                .page(handle, body.project(), Boolean.TRUE.equals(body.unread()), offset, limit);
    return Outcome.ok(new Page(page.items(), page.unread()));
  }
}
