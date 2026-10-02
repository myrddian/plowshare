package io.aeyer.plowshare.server.events;

import java.time.Instant;

public record InboxItem(
        String id, String handle, String kind, String firing, String conversation, String ending,
        String answer, Instant arrivedAt, Instant readAt, String about) {
    public InboxItem(String id, String handle, String kind, String firing, String conversation,
            String ending, String answer, Instant arrivedAt, Instant readAt) {
        this(id, handle, kind, firing, conversation, ending, answer, arrivedAt, readAt, null);
    }
}
