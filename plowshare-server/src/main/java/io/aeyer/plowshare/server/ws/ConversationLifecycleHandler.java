package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.api.LifecycleRequest;
import io.aeyer.plowshare.server.api.LifecycleView;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.requests.RequestedLifecycle;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.lifecycle} — archive a conversation, put it back, mark it
 * to go, or cancel the mark. The frame equivalent of {@code PUT
 * /v1/conversations/&#123;id&#125;/lifecycle}.
 *
 * <h2>One verb naming a destination, and the table that decides</h2>
 *
 * <p>{@code ConversationStore.moveTo} splices {@code
 * ConversationLifecycle.reachedFrom} into its own {@code WHERE}, so which
 * states may become the asked-for one is decided in one place for both
 * surfaces. <b>Nothing here restates it</b>: an id nothing opened is a 404, a
 * delegated child is a 409 and a move the table does not allow is a 409, all
 * three thrown by the store and mapped by {@code Faults}. A state nothing
 * spells is {@link RequestedLifecycle}'s 400, on the field rather than the row.
 *
 * <h2>Two reads of one payload, and why {@code conversation} is not {@code
 * id}</h2>
 *
 * <p>The endpoint takes the conversation from its path and the state from its
 * body. A frame carries both in one payload, so this reads the body's own
 * record through {@link Payloads#as} and the path's value through {@link
 * Payloads#required} — under the noun of the thing, because {@code
 * Envelope.id} already means the client's correlation. {@link Payloads} states
 * that convention for the whole surface and refuses the key {@code "id"}
 * outright.
 */
public final class ConversationLifecycleHandler implements FrameHandler {

    private final ConversationStore conversations;
    private final LogStages logStages;

    /**
     * @param conversations the one store both surfaces move a row through
     * @param logStages {@code log.close}: told when a person's conversation
     *     first moves out of {@code ACTIVE}
     */
    public ConversationLifecycleHandler(ConversationStore conversations, LogStages logStages) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.logStages = Objects.requireNonNull(logStages, "logStages");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String conversation = Payloads.required(payload, "conversation",
                FrameTypes.CONVERSATION_LIFECYCLE,
                "the id POST /v1/conversations answered with. Nothing was moved.");
        LifecycleRequest asked = Payloads.as(
                payload, LifecycleRequest.class, FrameTypes.CONVERSATION_LIFECYCLE);
        ConversationRecord moved = conversations.moveTo(
                conversation, RequestedLifecycle.in(asked.lifecycle()));
        logStages.moved(moved);
        return Outcome.ok(LifecycleView.of(moved));
    }
}
