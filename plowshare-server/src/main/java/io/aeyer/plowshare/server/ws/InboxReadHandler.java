package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** {@code inbox.read} — mark items read; the account's other sockets are told. */
public final class InboxReadHandler implements FrameHandler {

  record Body(List<String> items) {}

  public record Marked(int marked, int unread) {}

  private final Inbox inbox;
  private final InboxStore store;

  public InboxReadHandler(Inbox inbox, InboxStore store) {
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String handle = asking.requireHandle(FrameTypes.INBOX_READ);
    Body body = Payloads.as(payload, Body.class, FrameTypes.INBOX_READ);
    if (body.items() == null || body.items().isEmpty()) {
      throw new CallerFault(
          FrameTypes.INBOX_READ
              + " needs 'items': the ids inbox.list answered"
              + " with. Nothing was marked.");
    }
    return Outcome.ok(new Marked(inbox.read(handle, body.items()), store.unread(handle)));
  }
}
