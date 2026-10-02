package io.aeyer.plowshare.server.requests;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The proposal a write is filing, once it is settled that the caller named no
 * verdict of its own.
 *
 * <p>Moved out of {@code MemoryController.write}, where it was a presence check
 * on the request's own {@code verdict} key — no archive, no scribe, nothing
 * beyond the field — which is this package's shape exactly. The {@code
 * memory.write} frame binds the same record and has to refuse the same body in
 * the same words; a handler restating that paragraph would be a second copy of
 * it, and the paragraph is the whole point. A stale client that is told nothing
 * goes away believing it retired a memory that is still active and still
 * answering recalls.
 *
 * <h2>Why the field is read here and not on the record</h2>
 *
 * <p>{@code api.WriteMemoryRequest} used to answer "does this name a verdict?"
 * itself, with the refusal one line away in the controller. Splitting the
 * question from the answer across two packages is what lets the two drift, so
 * both halves are here — including the rule that an explicit {@code null} is
 * <b>not</b> a named verdict. That one is not a nicety: a client serialising an
 * absent field writes {@code "verdict": null}, {@code HttpServerClient} writes
 * exactly that shape one key over, and refusing it would refuse a caller that is
 * already doing the right thing.
 */
public final class RequestedProposal {

    private RequestedProposal() {
    }

    /**
     * {@code proposal}, or a {@link CallerFault} for a write that also named a
     * verdict.
     *
     * <p>Refused before anything is written and before the scribe is asked: a
     * caller told "no" about a write that half happened is worse off than one
     * told nothing.
     *
     * @param proposal the claim being proposed, handed straight back
     * @param verdict the request's own {@code verdict} field — a tripwire and
     *     not a field, per {@code api.WriteMemoryRequest}'s own javadoc
     * @throws CallerFault if {@code verdict} carries anything but JSON null
     */
    public static MemoryProposal toFile(MemoryProposal proposal, JsonNode verdict) {
        if (verdict != null && !verdict.isNull()) {
            throw new CallerFault(
                    "this write names a verdict, and this server does not take one. A verdict"
                            + " naming a memory to supersede would retire a memory the caller"
                            + " never read, so the judgement is made here, by the scribe, against"
                            + " candidates it retrieves itself. Nothing was written. Send the"
                            + " proposal without a verdict; the answer says which shape it was"
                            + " filed as and why.");
        }
        return proposal;
    }
}
