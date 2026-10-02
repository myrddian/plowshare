package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxStore;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The signed-in account's user-inbox: what arrived, and marking it read.
 * Frames only, per account — {@code inbox.changed} is a bare push and not a
 * request type this area routes.
 */
@Component
public class InboxFrames implements FrameArea {

    private final Inbox inbox;
    private final InboxStore store;

    public InboxFrames(Inbox inbox, InboxStore store) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Map<String, FrameHandler> frames() {
        return Map.ofEntries(
                Map.entry(FrameTypes.INBOX_LIST, new InboxListHandler(inbox, store)),
                Map.entry(FrameTypes.INBOX_READ, new InboxReadHandler(inbox, store)));
    }
}
