package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.CompactionView;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.Conversations;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.compactions} — every seam a conversation has had, oldest
 * first. The frame equivalent of {@code GET
 * /v1/conversations/&#123;id&#125;/compactions}.
 *
 * <h2>The existence rule is the whole of what is not a read</h2>
 *
 * <p>An empty list is the ordinary answer and says something: this conversation
 * has never been folded. <b>A conversation nothing opened is a 404 and not an
 * empty list</b> — {@code CompactionStore.forConversation} cannot tell the two
 * apart, and answering {@code []} to an id nobody minted would tell a caller
 * that a conversation it invented has a clean history.
 *
 * <p>The phrase is passed whole to {@link
 * Conversations#requireExistsOrThereIsNo} rather than as a noun, for the reason
 * that method's javadoc gives: "there is no transcript to read the seams of" is
 * not "{@code X} to read", and this reading and {@code turns} are the two that
 * kept the sentence inline until the service grew a door wide enough for it. A
 * handler that spelled its own would be a 404 on both surfaces in two different
 * sets of words, which is drift a status comparison cannot see.
 *
 * <h2>Why this catches nothing</h2>
 *
 * <p>{@code ArchiveException} travels out of {@link Conversations} untouched
 * and {@link FrameRouter} hands it to {@code Faults}, the one table both
 * surfaces read a status out of.
 */
public final class ConversationCompactionsHandler implements FrameHandler {

    private final Conversations rules;
    private final CompactionStore compactions;

    /**
     * @param rules the service the controller asks the existence question of
     * @param compactions the one store both surfaces read the seams from
     */
    public ConversationCompactionsHandler(Conversations rules, CompactionStore compactions) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.compactions = Objects.requireNonNull(compactions, "compactions");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String conversation = Payloads.required(payload, "conversation",
                FrameTypes.CONVERSATION_COMPACTIONS,
                "the id POST /v1/conversations answered with. Nothing was read.");
        rules.requireExistsOrThereIsNo(conversation, "transcript to read the seams of");
        return Outcome.ok(compactions.forConversation(conversation).stream()
                .map(CompactionView::of)
                .toList());
    }
}
