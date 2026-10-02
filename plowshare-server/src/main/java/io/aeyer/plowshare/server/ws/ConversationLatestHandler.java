package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ConversationView;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.requests.RequestedHome;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.latest} — the conversation one agent is still having in
 * one tier, which is what talking to a bot continues.
 *
 * <h2>The one handler in this area that mirrors no endpoint</h2>
 *
 * <p>{@link FrameTypes#CONVERSATION_LATEST} carries the argument: a listing
 * answers "what is open here", oldest first and unlimited, and this answers
 * "which one am I carrying on with". Reading the second out of the first would
 * mean reversing an order the console's opening screen is built on, or shipping
 * a tier so that a client can take the last row of it. So there is nothing for a
 * parity test to compare this against, and {@code ConversationFramesTest} says
 * so where the comparison would otherwise be expected.
 *
 * <h2>Nothing is opened, and the absence is an answer</h2>
 *
 * <p>An agent with no conversation in this tier is the ordinary first run, so
 * this answers {@code OK} with no payload rather than a 404: {@code Outcome} is
 * {@code NON_NULL}, so the answer is {@code &#123;"code":"OK"&#125;} and a
 * client tells "you have none yet" from "the server would not say" by the code
 * alone. <b>A handler that opened one to have something to answer with</b> would
 * put a row behind every start of a terminal, which is exactly the measured bug
 * the client's deferred open exists to stop.
 *
 * <h2>Why this catches nothing</h2>
 *
 * <p>The store's refusal of an agent it cannot name travels out of {@link
 * Conversations} untouched and {@link FrameRouter} hands it to {@code Faults},
 * which is the one table a status is read out of.
 */
public final class ConversationLatestHandler implements FrameHandler {

    /**
     * The tier to look in, as {@code GET /v1/conversations} spells the same
     * field.
     *
     * <p>The agent is <b>not</b> a component here and is read through {@link
     * Payloads#required} instead: a listing narrowed to nobody is still a
     * listing, but this question has no answer without a name — and a blank one
     * would be answered "you have no conversation yet", on the strength of which
     * a client opens a second one. The refusal has to come before the query.
     *
     * @param project which tier, or null for the global one
     */
    record Asked(String project) {
    }

    private final Conversations rules;

    /**
     * @param rules the service that decides which conversation an agent
     *     continues
     */
    public ConversationLatestHandler(Conversations rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        Asked asked = Payloads.as(payload, Asked.class, FrameTypes.CONVERSATION_LATEST);
        String agent = Payloads.required(payload, "agent", FrameTypes.CONVERSATION_LATEST,
                "the name agent.list declared for whoever is answering. Nothing was opened.");
        // ConversationView and not a shape of this handler's own: it is what
        // conversation.list and conversation.open both answer with, so a client
        // reads one record here rather than learning a second spelling of a
        // conversation for the one frame that has no endpoint behind it.
        return Outcome.ok(rules.toContinue(RequestedHome.in(asked.project()), agent)
                .map(ConversationView::of)
                .orElse(null));
    }
}
