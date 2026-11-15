package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.events.ApplicationInbox;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxStore;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The signed-in account's user-inbox: what arrived, and marking it read. Frames only, per account —
 * {@code inbox.changed} is a bare push and not a request type this area routes.
 */
@Component
public class InboxFrames implements FrameArea {

  private ApplicationInbox applications;

  @Autowired
  public InboxFrames(Inbox inbox, InboxStore store, ApplicationInbox applications) {
    this(inbox, store);
    this.applications = applications;
  }

  private final Inbox inbox;
  private final InboxStore store;

  public InboxFrames(Inbox inbox, InboxStore store) {
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(FrameTypes.INBOX_RECEIPT, new InboxReceiptHandler(applications)),
        Map.entry(FrameTypes.INBOX_PUBLISH, new InboxPublishHandler(applications)),
        Map.entry(FrameTypes.INBOX_LIST, new InboxListHandler(inbox, applications)),
        Map.entry(FrameTypes.INBOX_READ, new InboxReadHandler(inbox, store, applications)));
  }
}
