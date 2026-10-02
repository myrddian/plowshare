package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The question a log search is for, or the refusal for a search that named
 * none.
 *
 * <p>Moved out of {@code ConversationController.searchEntries}, where it was a
 * plain presence check on the request's own {@code q} field — no store, no
 * domain object, nothing beyond the string — which is {@link RequestedTask}'s
 * shape exactly and this package's reason for existing. The {@code
 * conversation.search} frame reads the same field and has to refuse it in the
 * same words; a handler restating the sentence would be a second copy of it.
 *
 * <p><b>Named for the log and not for questions in general.</b> {@code POST
 * /v1/memories/recall} and {@code POST /v1/memories/navigate} also require a
 * question and refuse it in sentences of their own, naming their own endpoints
 * and their own alternatives. A {@code RequestedQuestion} would invite the next
 * task to route one of those through this message, which names {@code GET
 * /v1/conversations} and a trajectory read — corrections that are no use to a
 * caller searching the archive.
 */
public final class RequestedLogQuestion {

    private RequestedLogQuestion() {
    }

    /**
     * {@code q}, or a {@link CallerFault} for a search that asked nothing.
     *
     * <p><b>Blank is the case this is really about.</b> The empty string is
     * what an unset field arrives as, and a search that read it as "no filter"
     * would answer with a page of the whole tier under a question nobody
     * asked — so the refusal names the two reads that <em>are</em> listings.
     *
     * @param q the request's own {@code q} field
     * @throws CallerFault if {@code q} is null or blank
     */
    public static String in(String q) {
        if (q == null || q.isBlank()) {
            throw new CallerFault(
                    "'q' is the question to search this tier's conversations for, in prose, and"
                            + " it cannot be empty. A search with no question is not a listing of"
                            + " everything; use GET /v1/conversations to list a tier and"
                            + " GET /v1/conversations/{id}/trajectory to read one whole.");
        }
        return q;
    }
}
