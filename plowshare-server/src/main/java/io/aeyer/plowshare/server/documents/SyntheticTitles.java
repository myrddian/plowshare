package io.aeyer.plowshare.server.documents;

/**
 * The strings the parser writes into {@code chapters.title} and {@code
 * sections.title} when it had to invent a structural unit because the source
 * document had no detectable heading at that level.
 *
 * <p>Anchor's {@code domain.SyntheticTitles}, ported with its reason, which is
 * that <b>a bypass is meant to be visible</b>:
 *
 * <blockquote>Defense-in-depth pair to {@link StructuralRef}: the rendering
 * boundary filters synthetic units out of all user/LLM-facing output, but if
 * anyone bypasses the helper these obviously-internal strings make the bug
 * visible instantly rather than blending in as a plausible-looking section name
 * like the previous {@code "Body"} / {@code "Document"} fallbacks
 * did.</blockquote>
 *
 * <p>Anchor's V5 migration is the record of that having actually happened: it
 * backfills every pre-V5 section titled {@code "Body"} to the sentinel, because
 * a fallback wearing a plausible name had been indistinguishable from a real
 * heading in every row and every prompt it had reached.
 *
 * <p><b>These strings are stored and never rendered.</b> Everything that turns a
 * stored unit into text a person or a model sees goes through {@link
 * StructuralRef}, which discards the title the moment the flag is set. This
 * class is what makes the failure loud if something does not.
 */
public final class SyntheticTitles {

    /** The title of a chapter the whole document is, because it declared none. */
    public static final String CHAPTER = "__SYNTHETIC_SEGMENT__";

    /** The title of the section a chapter's whole body is, because it
     *  declared none. */
    public static final String SECTION = "__SYNTHETIC_HEAP__";

    private SyntheticTitles() {
    }
}
