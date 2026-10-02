package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Locale;

/**
 * Which halves of the corpus {@code POST /v1/documents/search} was told to
 * read, or a refusal that names the ones this server has.
 *
 * <h2>Never silently defaulted</h2>
 *
 * <p>A caller comparing two answers to one question — which is the whole of
 * what the field is for — would otherwise be comparing an answer against
 * itself and concluding the two halves agree. A conjunctive word search is
 * correctly silent for most well-formed questions, so a lexical half that has
 * broken and one that is working as designed produce the same hybrid answer;
 * running each half alone against the same question is the only way to tell
 * them apart, and a misspelling answered as hybrid would look exactly like the
 * comparison succeeding.
 *
 * <p>Blank is refused rather than treated as absent, the distinction {@link
 * RequestedDocumentName} draws over a blank name: a caller that meant to
 * choose and sent nothing has said something.
 *
 * <h2>Absence is the caller's branch and never this type's</h2>
 *
 * <p><b>This method is not called for a request that named no mode.</b> Both
 * surfaces keep the two-door shape {@code DocumentController} argued for: a
 * request with no {@code mode} reaches {@code RetrievalService.search(String,
 * int)}, whose own default is documented as {@code HYBRID}, so neither this
 * file nor a frame handler holds a second spelling of "hybrid" to drift away
 * from the first. A factory here that returned {@code HYBRID} for null would
 * be exactly that second spelling.
 */
public final class RequestedSearchMode {

    private RequestedSearchMode() {
    }

    /**
     * The named mode.
     *
     * @param named the request's own {@code mode} field, which the caller has
     *     already established is not null
     * @throws CallerFault if it names no mode this server has
     */
    public static RetrievalService.Mode in(String named) {
        for (RetrievalService.Mode mode : RetrievalService.Mode.values()) {
            if (mode.name().equalsIgnoreCase(named.strip())) {
                return mode;
            }
        }
        StringBuilder known = new StringBuilder();
        for (RetrievalService.Mode mode : RetrievalService.Mode.values()) {
            known.append(known.isEmpty() ? "" : ", ")
                    .append(mode.name().toLowerCase(Locale.ROOT));
        }
        throw new CallerFault(
                "`mode` is '" + named + "'; it must be one of " + known + ". Leave it out to"
                        + " read the corpus both ways, which is what every other surface does."
                        + " It is not defaulted for you, because a mode nobody recognised"
                        + " answered as hybrid would look exactly like the comparison this"
                        + " field exists to make");
    }
}
