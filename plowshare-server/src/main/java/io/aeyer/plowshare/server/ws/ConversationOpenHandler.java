package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.api.OpenConversationRequest;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.requests.RequestedBudget;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.open} — open a conversation and answer with the id to
 * speak into. The frame equivalent of {@code POST /v1/conversations}.
 *
 * <h2>The same store method the controller calls, and nothing between</h2>
 *
 * <p>{@code ConversationController.open} reads a turn cap and an allowance
 * through {@code requests}, calls {@link ConversationStore#open} and renders
 * the row. This does those three things and no others. The three refusals a
 * body can earn here — a cap named and lifted at once, an allowance named twice,
 * a number that is not one — are {@code RequestedTurnCap}'s and {@code
 * RequestedBudget}'s, reached with this payload's fields exactly as the
 * controller reaches them with its body's, and none is restated.
 *
 * <h2>{@link ConversationsProperties} is injected, and that is the parity that
 * a payload comparison cannot see</h2>
 *
 * <p>A body naming no {@code maxModelCalls} takes the operator's {@code
 * default-budget}. That number is in neither the request nor the response, so a
 * handler that reached for a constant of its own would agree with the endpoint
 * on every field of every answer and open conversations with a different
 * allowance. It takes the same bean the controller is handed, and {@code
 * ConversationFramesTest} pins the case with a configured number that is
 * deliberately not the shipped one.
 *
 * <h2>200 and not 201</h2>
 *
 * <p>{@link Outcome#ok} rather than {@code Code.CREATED}, matching the
 * endpoint, which answers 200 for the reason its own javadoc gives: every other
 * write in this server answers 200, and one endpoint answering 201 would make a
 * client branch on a status that means the same thing here as the one beside it.
 */
public final class ConversationOpenHandler implements FrameHandler {

    private final CallerAccess access;
    private final ConversationStore conversations;
    private final ConversationsProperties properties;
    private final LogStages logStages;

    /**
     * @param conversations the one store both surfaces open a row through
     * @param properties the operator's own defaults, the same bean the
     *     controller is injected with
     * @param logStages {@code log.open}: told once the row is committed
     */
    public ConversationOpenHandler(
            ConversationStore conversations, ConversationsProperties properties,
            LogStages logStages,
            CallerAccess access) {
        this.access = access;
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.logStages = Objects.requireNonNull(logStages, "logStages");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        OpenConversationRequest asked = Payloads.as(
                payload, OpenConversationRequest.class, FrameTypes.CONVERSATION_OPEN);
        access.requireSession(asking.sessionId(), asking.handle());
        TurnCap turnCap = RequestedTurnCap.in(
                asked.maxTurns(), asked.noTurnCap(), "each turn in this conversation");
        Budget budget = RequestedBudget.in(
                asked.maxModelCalls(), asked.noBudget(), properties.getDefaultBudget());
        ConversationRecord opened = conversations.open(
                RequestedHome.in(asked.project()), budget, turnCap, asking.handle());
        // The socket's session: its .plowshare/hooks/ is this conversation's local tier (spec
        // 2026-09-30-local-hooks-are-served decision 4).
        logStages.opened(LogStages.LogOpened.ofConversation(opened, asking.sessionId()));
        return Outcome.ok(ConversationView.of(opened));
    }
}
