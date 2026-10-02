package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.Noticing;
import java.util.Objects;
import java.util.Optional;

/** Notices and the inbox tool for the account that spoke this turn. Nobody spoke, nothing. */
public class InboxNoticing implements Noticing {

    private final InboxStore store;
    private final Inbox inbox;
    private final SpeakerHandles speakers;

    public InboxNoticing(InboxStore store, Inbox inbox, SpeakerHandles speakers) {
        this.store = Objects.requireNonNull(store, "store");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.speakers = Objects.requireNonNull(speakers, "speakers");
    }

    @Override
    public Optional<String> noticeFor(AgentDefinition definition, String sessionId, String conversation) {
        if (sessionId == null || conversation == null) {
            return Optional.empty();
        }
        return speakers.handleOf(sessionId).flatMap(handle -> {
            int fresh = store.unreadSinceLastTurn(handle, conversation);
            if (fresh == 0) {
                return Optional.empty();
            }
            String when = store.newestUnreadSinceLastTurn(handle, conversation)
                    .map(Object::toString).orElse("since the last turn");
            return Optional.of("Harness notice: " + fresh + (fresh == 1 ? " new item" : " new items")
                    + " arrived in " + handle + "'s user-inbox, the latest at " + when
                    + ". Mention it if it helps; call inbox_read to see them.");
        });
    }

    @Override
    public Optional<AgentTool> inboxToolFor(AgentDefinition definition, String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return speakers.handleOf(sessionId).map(handle -> new InboxTool(inbox, handle));
    }
}
