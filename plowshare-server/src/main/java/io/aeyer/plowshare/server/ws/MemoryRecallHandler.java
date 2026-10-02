package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.RecallRequest;
import io.aeyer.plowshare.server.api.RecallResponse;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedMemoryQuestion;
import java.util.Map;
import java.util.Objects;

/**
 * {@code memory.recall} — the memories nearest a question. The frame
 * equivalent of {@code POST /v1/memories/recall}.
 *
 * <h2>The answer echoes what produced it, including what it could not see</h2>
 *
 * <p>The response carries the question and the limit actually applied back
 * alongside the hits, and carries {@code unsearchable} beside them: how many
 * live memories in the searched tiers have no embedding and so could not be
 * looked at. That last field is what stops an empty answer being a lie, and it
 * is rendered here from the same {@code Archive.Recall} the endpoint renders
 * it from — a handler that answered the hits alone would be a socket client's
 * only way of asking this question, with no way to tell an empty archive from
 * a broken one.
 *
 * <p><b>The limit a payload does not name is the request record's own
 * default</b>, not a number written out here: both surfaces bind {@link
 * RecallRequest} and both ask it, so there is one default rather than two that
 * agree today.
 */
public final class MemoryRecallHandler implements FrameHandler {

    private final Archive archive;

    /**
     * @param archive the one archive both surfaces run the vector query against
     */
    public MemoryRecallHandler(Archive archive) {
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        RecallRequest request =
                Payloads.as(payload, RecallRequest.class, FrameTypes.MEMORY_RECALL);
        String question = RequestedMemoryQuestion.recalled(request.question());
        Home home = RequestedHome.in(request.project());
        int limit = request.limitOrDefault();

        Archive.Recall recalled = archive.accountingEnabled() ? archive.recall(question, home, limit,archive.usage(home,asking.handle(),UsageAttribution.Operation.EMBEDDING_QUERY)) : archive.recall(question,home,limit);
        return Outcome.ok(new RecallResponse(
                question, limit, recalled.memories(), recalled.unsearchable()));
    }
}
