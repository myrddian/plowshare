package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.api.EntryPageView;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.requests.RequestedAfter;
import io.aeyer.plowshare.server.requests.RequestedBefore;
import io.aeyer.plowshare.server.requests.RequestedKinds;
import io.aeyer.plowshare.server.requests.RequestedWindow;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code conversation.trajectory} — everything that happened, one page at a
 * time. The frame equivalent of {@code GET
 * /v1/conversations/&#123;id&#125;/trajectory}.
 *
 * <h2>The other reading of one log</h2>
 *
 * <p>{@code EntryStore.pageOfLog} where {@link ConversationChatHandler} reads
 * {@code pageOfProjection}: which entries a fold covered, the diagnostics, the
 * failed attempts, the handles and the timings — every fact the server wrote
 * down and nothing could ask for. The two handlers differ in that one store
 * call and in nothing else, and they differ that way because the two endpoints
 * do.
 *
 * <p>The window is read before the existence question is asked, and both
 * bounds are {@link RequestedWindow}'s — see {@link ConversationChatHandler}'s
 * javadoc, which carries the argument for both.
 *
 * <p>{@code after} reads only what was written after that ordinal — what a
 * client that has shown the log through it has not seen — and every page says
 * how far the log reaches in {@code through}. It is the one field {@link
 * LogWindow} carries that {@link Paged} does not, read through {@link
 * RequestedAfter} on the same terms as the HTTP surface's own {@code after}
 * query parameter.
 *
 * <p>{@code before}, or {@code tail} for the log's end, reads the other way:
 * newest first, below that ordinal, which is how a client shows the last of a
 * long conversation in one read and goes back from there. {@code kinds}
 * narrows either reading to the kinds a client draws, and {@code drawn} leaves
 * out the answers that asked for tools, which no chat draws. Both are read through
 * {@link RequestedBefore} and {@link RequestedKinds}, on the same terms as the
 * HTTP surface's own query parameters.
 *
 * <p>A conversation with nothing in it answers with an empty page and a total
 * of zero, which is a true and ordinary state; {@code
 * Conversations.requireExists} is what keeps it from being confused with an id
 * nothing opened.
 */
public final class ConversationTrajectoryHandler implements FrameHandler {

    private final Conversations rules;
    private final EntryStore entries;

    /**
     * @param rules the service the controller asks the existence question of
     * @param entries the one store both surfaces read a page from
     */
    public ConversationTrajectoryHandler(Conversations rules, EntryStore entries) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.entries = Objects.requireNonNull(entries, "entries");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String conversation = Payloads.required(payload, "conversation",
                FrameTypes.CONVERSATION_TRAJECTORY,
                "the id POST /v1/conversations answered with. Nothing was read.");
        LogWindow asked = Payloads.as(payload, LogWindow.class, FrameTypes.CONVERSATION_TRAJECTORY);
        RequestedWindow window = RequestedWindow.in(asked.offset(), asked.limit());
        int after = RequestedAfter.in(asked.after());
        int before = RequestedBefore.in(asked.before(), asked.tail(), asked.after());
        Set<EntryKind> kinds = RequestedKinds.in(asked.kinds());
        boolean drawn = Boolean.TRUE.equals(asked.drawn());
        rules.requireExists(conversation, "trajectory");
        return Outcome.ok(EntryPageView.of(before != RequestedBefore.FORWARD
                        ? entries.pageOfLogBefore(conversation, before, kinds, drawn,
                                window.skip(), window.most())
                        : after == 0 && kinds.equals(EntryStore.EVERY_KIND) && !drawn
                        ? entries.pageOfLog(conversation, window.skip(), window.most())
                        : entries.pageOfLogAfter(conversation, after, kinds, drawn,
                                window.skip(), window.most()),
                window.skip(), window.most()));
    }
}
