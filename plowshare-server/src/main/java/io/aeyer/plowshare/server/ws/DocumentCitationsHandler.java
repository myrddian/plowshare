package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.CitationsResponse;
import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.requests.RequestedCitationScope;
import io.aeyer.plowshare.server.requests.RequestedCorpusPage;
import java.util.Map;
import java.util.Objects;

/**
 * {@code document.citations} — what answers have said they took from the
 * corpus. The frame equivalent of {@code GET /v1/documents/citations}.
 *
 * <h2>Three scopes, one type, and the choice between them is not here</h2>
 *
 * <p>{@link RequestedCitationScope} holds both the refusal for a request that
 * names two scopes and the three-armed read that a request naming one or none
 * resolves to. <b>Deliberately both</b>: moving only the refusal would have
 * left this handler writing the branch out a second time, and two surfaces
 * reading the same citations through two copies of one {@code if} agree until
 * somebody edits one of them — drift a payload comparison cannot see.
 *
 * <h2>An empty list is an answer</h2>
 *
 * <p>Which is why {@code CitationsResponse.scope} is echoed: nothing has cited
 * anything, this conversation cited nothing, and this document has never been
 * cited are three different facts that come back as the same empty array.
 * <b>No {@code NOT_FOUND} for a conversation or a document that does not
 * exist</b>, on either surface — a citation listing is not a read of that row
 * and does not pretend to be one, and refusing would make this the second place
 * on the server that decides whether a conversation exists.
 */
public final class DocumentCitationsHandler implements FrameHandler {

    /**
     * {@code GET /v1/documents/citations}' three query parameters, as a
     * payload, under the names the query string already used.
     *
     * @param conversation list only what this conversation cited, or null
     * @param document list only what has cited this document, or null
     * @param limit how many at most, or null for {@link
     *     CitationStore#MOST_LISTED}
     */
    record Asked(String conversation, String document, Integer limit) {
    }

    private final CitationStore citations;

    /**
     * @param citations the one store both surfaces read citations from
     */
    public DocumentCitationsHandler(CitationStore citations) {
        this.citations = Objects.requireNonNull(citations, "citations");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        Asked asked = Payloads.as(payload, Asked.class, FrameTypes.DOCUMENT_CITATIONS);
        RequestedCitationScope scope =
                RequestedCitationScope.in(asked.conversation(), asked.document());
        int most = RequestedCorpusPage.cited(asked.limit());
        return Outcome.ok(
                CitationsResponse.of(scope.scope(), most, scope.from(citations, most)));
    }
}
