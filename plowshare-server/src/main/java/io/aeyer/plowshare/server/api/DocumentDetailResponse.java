package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.DocumentStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What {@code GET /v1/documents/&#123;id&#125;} answers with — <b>one document's
 * structure, with none of its text in it</b>.
 *
 * <p>Anchor's {@code DocumentDetailResponse}, and its own SPEC says what it is
 * for: "callers wanting to understand a document's structure before querying".
 * That is the decision a person takes before spending minutes on {@code POST
 * /v1/documents/&#123;id&#125;/ask}, and until this route existed there was no
 * way to take it — the outline the deliberation's critic is shown was visible to
 * the three agents and to nothing else.
 *
 * <p><b>Titles and summaries, and no paragraph text.</b> Anchor says "no raw
 * text" and it is right for a second reason here: a route that rendered a
 * document's prose would be a third surface with its own quoting to keep
 * correct, and {@code POST /v1/documents/retrieve} beside it already answers for
 * text.
 *
 * <p><b>Two fields Anchor's has and this does not.</b> {@code
 * doc_summary_source} names which of three ways a document's summary was
 * arrived at; nothing here records that, because this server writes one summary
 * one way. {@code metadata} is Anchor's arbitrary-JSON slot, which V18 declined
 * for the reason it declined {@code authors} — a column nothing can fill and
 * nothing reads.
 *
 * @param summary what the document argues, or {@code null} for one the cascade
 *     has not reached
 * @param vocabulary what this document calls its own top-level parts —
 *     {@code CHAPTER}, {@code SECTION} or {@code PART} — or {@code null} for a
 *     row written before V26. <b>The word to render the outline below in</b>,
 *     which is the reader this column was added for
 * @param chapters the top tier, in document order, each carrying the tier under
 *     it
 */
public record DocumentDetailResponse(
        UUID documentId,
        String sourceName,
        String title,
        String summary,
        String vocabulary,
        Instant ingestedAt,
        String ingestedBy,
        long byteSize,
        List<Chapter> chapters) {

    /**
     * One chapter and its sections.
     *
     * <p><b>Flat, where a retrieve hit nests its chunk.</b> The three fields a
     * chapter shares with a section are the three that go through the gate, and
     * they are still produced by {@link UnitView#of} and nowhere else — this
     * record reads them back off the gated value rather than deriving them
     * again. What it does not do is nest a {@code chapter} key inside a {@code
     * chapters} array, which is a shape nobody reading the body would forgive.
     *
     * <p><b>There is no ordinal.</b> The list is in document order and that is
     * what an ordinal would say. Anchor sends one because its response is built
     * from rows it read individually; here the order is the answer, and a number
     * beside it would be a second statement of the same fact that a caller could
     * find disagreeing with the first.
     */
    public record Chapter(
            UUID id, String title, boolean synthetic, String summary, List<UnitView> sections) {

        static Chapter of(DocumentStore.StoredChapter chapter) {
            UnitView gated = UnitView.of(chapter.id(), chapter.title(), chapter.summary());
            return new Chapter(
                    gated.id(), gated.title(), gated.synthetic(), gated.summary(),
                    chapter.sections().stream()
                            .map(section -> UnitView.of(
                                    section.id(), section.title(), section.summary()))
                            .toList());
        }
    }

    /**
     * The view, built from what the store answered with.
     *
     * <p><b>Public rather than package-private</b>, which it was while
     * the HTTP surface was the only surface that built one. {@code
     * ws.DocumentFrames}' handlers answer with this same record, and a
     * narrower visibility would have meant either a frame handler living in
     * {@code api} -- the accident {@code requests}' package javadoc
     * describes -- or a second view record with the same fields. The
     * narrower statement is gone, and is the same cost that move charged.
     */
    public static DocumentDetailResponse of(
            DocumentStore.StoredDocument document, List<DocumentStore.StoredChapter> hierarchy) {
        return new DocumentDetailResponse(
                document.id(),
                document.sourceName(),
                document.title(),
                document.summary(),
                document.vocabulary() == null ? null : document.vocabulary().name(),
                document.ingestedAt(),
                document.ingestedBy(),
                document.byteSize(),
                hierarchy.stream().map(Chapter::of).toList());
    }
}
