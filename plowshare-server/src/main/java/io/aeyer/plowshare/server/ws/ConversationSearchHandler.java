package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.LogSearchView;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedLogQuestion;
import io.aeyer.plowshare.server.requests.RequestedWindow;
import java.util.Map;
import java.util.Objects;

/**
 * {@code conversation.search} — where in one tier's conversations something was
 * said. The frame equivalent of {@code GET /v1/entries/search}.
 *
 * <h2>The question is read before the window, and the order is part of the
 * answer</h2>
 *
 * <p>A request that asks nothing and also asks for no page is told about the
 * question, because that is the mistake it has to fix first and because it is
 * what the endpoint says. Both sentences are {@code requests}' — {@link
 * RequestedLogQuestion} and {@link RequestedWindow} — which is where they went
 * when this controller's last inline throws came down, and neither is restated
 * here.
 *
 * <h2>The tier, and what an absent one means</h2>
 *
 * <p>Omitted is the global tier and not "everywhere", resolved through the same
 * {@link RequestedHome} {@code conversation.list} and the endpoint use: a
 * search that crossed the boundary would be the one read in this server that
 * mixed a project's conversations with the global tier's, in the place it would
 * be least visible.
 *
 * <h2>Why the payload says {@code q}</h2>
 *
 * <p>Because the query string does. {@link Payloads}' convention keeps the name
 * a query parameter already had, since that name is already a client-facing
 * spelling — renaming it to {@code question} here would make the frame surface
 * and the HTTP surface two vocabularies for one field.
 */
public final class ConversationSearchHandler implements FrameHandler {

    /**
     * {@code GET /v1/entries/search}'s four query parameters, as a payload.
     *
     * <p>All four in one record rather than {@code q} and {@code project}
     * beside a {@link Paged}: this endpoint's window is the same window a page
     * takes, but its request is a search rather than a page of one
     * conversation, and a record naming what it actually asks for is what a
     * reader of the wire needs.
     *
     * @param q the question, in prose
     * @param project which tier, or null for the global one
     * @param offset how many hits to pass over, or null for the beginning
     * @param limit how many hits this page may hold, or null for the cap
     */
    record Asked(String q, String project, Integer offset, Integer limit, String mode, String snapshot) {
    }

    private final EntryStore entries;

    /**
     * @param entries the one store both surfaces search through
     */
    public ConversationSearchHandler(EntryStore entries) {
        this.entries = Objects.requireNonNull(entries, "entries");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        Asked asked = Payloads.as(payload, Asked.class, FrameTypes.CONVERSATION_SEARCH);
        String question = RequestedLogQuestion.in(asked.q());
        RequestedWindow window = RequestedWindow.in(asked.offset(), asked.limit());
        if (asked.mode() == null && asked.snapshot() == null)
            return Outcome.ok(LogSearchView.of(entries.search(RequestedHome.in(asked.project()), question,
                    window.skip(), window.most()), window.skip(), window.most()));
        return Outcome.ok(entries.searchView(RequestedHome.in(asked.project()), question,
                window.skip(), window.most(), asked.mode(), asked.snapshot()));
    }
}
