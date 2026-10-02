package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.TurnView;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.TurnStore;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.turns} — every turn a conversation has had, oldest first.
 * The frame equivalent of {@code GET /v1/conversations/&#123;id&#125;/turns},
 * and the read half of the two pilots the dispatcher plan names. Spec §3.1 uses
 * this type as its own example of what a frame looks like.
 *
 * <h2>The same service the controller calls, and nothing between</h2>
 *
 * <p>{@code ConversationController.turns} does two things: it asks {@link
 * Conversations} whether the conversation exists, and it renders {@code
 * TurnStore.forConversation} as {@link TurnView}s. This does those two things
 * and no others — the controller's body without its {@code ResponseEntity}.
 * <b>Neither check is restated here.</b> The existence rule is {@link
 * Conversations#requireExistsOrThereIsNo}'s, which is where it had to end up
 * before this class could exist at all: the controller spelled that {@code
 * ArchiveException} inline, in words no {@code requireExists(id, reading)} could
 * build, and a handler that copied the sentence would have been a second copy to
 * keep in step with the first.
 *
 * <h2>Why this catches nothing</h2>
 *
 * <p>{@code ArchiveException} travels out of {@link Conversations} untouched and
 * {@link FrameRouter} hands it to {@code Faults}, which is the one table this
 * surface and the HTTP surface both read a status out of — so a conversation
 * nobody opened is a 404 on both, with one sentence written in one place. A
 * handler that caught it to pick a code of its own would be the second mapping
 * this whole slice exists to prevent.
 *
 * <h2>Why the payload says {@code conversation} and not {@code id}</h2>
 *
 * <p>The endpoint takes the conversation from its path and calls it {@code id},
 * which a frame cannot copy: {@link
 * io.aeyer.plowshare.protocol.frames.Envelope#id()} is already {@code id} and
 * means something else — the client-generated correlation the response echoes.
 * A frame carrying {@code id} twice at two nesting levels, meaning two different
 * things, is a shape a reader has to be told about. {@code conversation} is what
 * {@code RunAgentRequest} already calls the same field, so it is this server's
 * existing name for it rather than one invented here. <b>That is now the
 * surface's convention rather than this type's choice</b> — {@link Payloads}
 * states it for every type the breadth plan adds, and {@link
 * Payloads#required} refuses the key {@code "id"} outright.
 */
public final class ConversationTurnsHandler implements FrameHandler {

    private final Conversations rules;
    private final TurnStore turns;

    /**
     * @param rules the service the controller asks the existence question of
     * @param turns the one store both surfaces read the history from
     */
    public ConversationTurnsHandler(Conversations rules, TurnStore turns) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.turns = Objects.requireNonNull(turns, "turns");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String conversation = Payloads.required(payload, "conversation",
                FrameTypes.CONVERSATION_TURNS,
                "the id POST /v1/conversations answered with. Nothing was read.");
        // The rule and not a read, exactly as in the controller: nothing in the
        // answer comes from that row. The phrase is passed whole because this
        // sentence is not "<noun> to read", which is the finding that blocked
        // this handler until Conversations grew a door wide enough for it.
        rules.requireExistsOrThereIsNo(conversation, "history to read back");
        return Outcome.ok(turns.forConversation(conversation).stream().map(TurnView::of).toList());
    }
}
