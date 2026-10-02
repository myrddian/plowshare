package io.aeyer.plowshare.server.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A chapter's text into the sections it declares.
 *
 * <p>Anchor's {@code ingest.SectionDetector}, ported with its constants and the
 * comments that record what each one is for. Four ORed boundary conditions with
 * a hard veto — a chemistry section name, a numbered heading, a numbered
 * subsection, or a short title-cased line followed by prose, with the LP/proof
 * keyword list beating all four.
 *
 * <p>Anchor's own framing of the constant lists: "Anchor's first audience is
 * chemistry; the chemistry-name set lives here as the canonical list. Other
 * domain corpora can extend this later but the chemistry list is the v0
 * baseline."
 *
 * <p><b>Two divergences, both of the same shape and both named in tests.</b>
 * Anchor drops text that belongs to no boundary — the lines above the first
 * heading, and (through {@link ChapterDetector}) a whole document whose every
 * heading was excluded. Here that text becomes a synthetic unit, which is the
 * mechanism this class already has for "text under no heading", rather than
 * being silently absent from the corpus.
 */
public final class SectionDetector {

    /**
     * The section names Anchor's first corpus actually uses. Seventeen, and
     * matched against a line normalised to lower-case letters and spaces, so
     * "3. RESULTS AND DISCUSSION" and "Results and Discussion" are one key.
     */
    private static final Set<String> CHEMISTRY_SECTIONS = Set.of(
            "abstract", "introduction", "background", "methods", "materials and methods",
            "experimental", "experimental section", "results", "results and discussion",
            "discussion", "conclusion", "conclusions", "references", "acknowledgements",
            "acknowledgments", "supporting information");

    /**
     * Sections dropped after they have served as a boundary. The bibliography
     * and the thanks: not claim-bearing, and a summariser handed them writes a
     * summary the deliberation goes on to treat as something the document
     * argues.
     *
     * <p>The references block is not lost by being dropped here —
     * {@link ReferencesExtractor} re-walks the raw text for it, which is what
     * pays this exclusion back.
     */
    private static final Set<String> EXCLUDED_SECTIONS = Set.of(
            "references", "bibliography", "acknowledgements", "acknowledgments");

    /**
     * LP / optimisation / proof-style keywords that look like single-word
     * title-case headings ({@code Minimize}, {@code Theorem}, {@code Lemma}) but
     * are inline structural elements of mathematical prose. Anchor's note names
     * the paper: "surfaced by the Wagner LP paper, where {@code Minimize:} lines
     * intro'd LP problem statements and the parser misclassified them as section
     * headings."
     */
    private static final Set<String> MATH_LP_NON_HEADINGS = Set.of(
            "minimize", "maximize", "minimise", "maximise",
            "subject to", "st",
            "proof", "proofs",
            "theorem", "lemma", "corollary", "proposition",
            "definition", "remark", "example", "claim",
            "case", "cases",
            "note", "notation");

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\s*\\d+\\.\\s+[A-Z].{0,80}$");

    /**
     * Multi-level numbered subsection: {@code 3.1. Antichains of fixed diameter}
     * / {@code 3.1 Foo} / {@code 3.10. Bar}. A pattern of its own because
     * {@link #NUMBERED_HEADING} matches only single-level {@code \d+\.}, and
     * Anchor records what the gap cost:
     *
     * <blockquote>the synthesiser ended up lifting subsection titles directly
     * out of chunk text into the GROUNDING block — a "helpful hallucination"
     * that still relaxes the contract. Detecting them up front means they're
     * first-class section rows in the DB instead.</blockquote>
     */
    private static final Pattern NUMBERED_SUBSECTION =
            Pattern.compile("^\\s*\\d+\\.\\d+\\.?\\s+[A-Z].{0,80}$");

    /**
     * A short, title-cased line. Tightened by Anchor from {@code
     * ^([A-Z][a-zA-Z]*\s*){1,6}$}, and the loose form's failure is the clearest
     * argument in the whole regression log:
     *
     * <blockquote>the loose form matched LaTeX-flattened math residue like "X Y"
     * (subscripts stripped by Tika), promoting it to a section title that then
     * leaked into the LLM prompt as {@code [X Y]}.</blockquote>
     *
     * <p>So every word is at least two characters — a single-letter token is
     * almost always math residue and never a heading — and the whole line is
     * between {@link #MIN_TITLE_CASE_HEADING_LENGTH} and {@link
     * #MAX_TITLE_CASE_HEADING_LENGTH} characters.
     */
    private static final Pattern TITLE_CASE_HEADING =
            Pattern.compile("^\\s*([A-Z][a-zA-Z]+\\s*){1,6}$");

    private static final int MIN_TITLE_CASE_HEADING_LENGTH = 6;
    private static final int MAX_TITLE_CASE_HEADING_LENGTH = 60;

    private SectionDetector() {
    }

    /**
     * The sections of one chapter, in order.
     *
     * @param lines the whole document, split on line breaks
     * @param bodyStartLine the chapter's first line of text.
     *     <b>Already past the heading</b>, which is where this differs from
     *     Anchor: Anchor takes the chapter's start line and adds one
     *     unconditionally, which for a synthetic chapter skips the document's
     *     first line because there is no heading there to skip. {@code
     *     ChapterDetector.Detected} computes it where the difference is still
     *     known
     * @param endLine one past the chapter's last line
     */
    public static List<Detected> detect(String[] lines, int bodyStartLine, int endLine) {
        Objects.requireNonNull(lines, "lines");
        if (bodyStartLine >= endLine) {
            return List.of();
        }

        List<Integer> boundaries = new ArrayList<>();
        for (int i = bodyStartLine; i < endLine; i++) {
            String raw = lines[i];
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalised = trimmed.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z\\s]", "").trim();

            // The veto is checked first and wins outright: `Minimize` matches
            // TITLE_CASE_HEADING and is over the length floor, so without this
            // it becomes a section title in any LP paper.
            if (MATH_LP_NON_HEADINGS.contains(normalised)) {
                continue;
            }

            boolean chemistryName = CHEMISTRY_SECTIONS.contains(normalised);
            boolean numbered = NUMBERED_HEADING.matcher(raw).matches();
            boolean numberedSubsection = NUMBERED_SUBSECTION.matcher(raw).matches();
            boolean titleCase = TITLE_CASE_HEADING.matcher(raw).matches()
                    && trimmed.length() >= MIN_TITLE_CASE_HEADING_LENGTH
                    && trimmed.length() <= MAX_TITLE_CASE_HEADING_LENGTH
                    && nextNonEmptyLooksLikeProse(lines, i + 1, endLine);

            if (chemistryName || numbered || numberedSubsection || titleCase) {
                boundaries.add(i);
            }
        }

        if (boundaries.isEmpty()) {
            // The whole chapter body is one synthetic section, carrying the
            // sentinel from SyntheticTitles: the render boundary drops it before
            // any surface a person or a model sees, and the obviously-internal
            // string makes a missed boundary visible instead of plausible.
            return List.of(new Detected(new StructuralRef.Synthetic(), 1, bodyStartLine, endLine));
        }

        List<Detected> sections = new ArrayList<>();
        int ordinal = 1;

        // DIVERGENCE. The text between the chapter's first line and its first
        // heading belongs to no section in Anchor and is dropped from the corpus
        // — a paper's title block, its authors and an unlabelled abstract, all of
        // it above "1. Introduction". That is not the intended kind of drop: an
        // excluded section is dropped deliberately and paid back by
        // ReferencesExtractor, while this text is simply never reached. It
        // becomes a synthetic section, which is what this class already does with
        // text that is under no heading.
        int firstBoundary = boundaries.get(0);
        if (hasText(lines, bodyStartLine, firstBoundary)) {
            sections.add(new Detected(
                    new StructuralRef.Synthetic(), ordinal++, bodyStartLine, firstBoundary));
        }

        for (int i = 0; i < boundaries.size(); i++) {
            int start = boundaries.get(i);
            int end = (i + 1 < boundaries.size()) ? boundaries.get(i + 1) : endLine;
            String rawLine = lines[start].trim();
            String title = NUMBERED_SUBSECTION.matcher(rawLine).matches()
                    ? trimSubsectionTitle(rawLine)
                    : rawLine;
            String key = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", "").trim();
            if (EXCLUDED_SECTIONS.contains(key)) {
                // A boundary first, dropped second, and no ordinal consumed —
                // the same two steps ChapterDetector.materialise takes.
                continue;
            }
            sections.add(new Detected(new StructuralRef.Named(title), ordinal++, start + 1, end));
        }
        return List.copyOf(sections);
    }

    /**
     * Anchor's {@code trimSubsectionTitle}. The shape it exists for is one every
     * PDF text extractor produces: the heading and the first sentence of body
     * prose on one physical line —
     *
     * <blockquote>{@code "3.1. Antichains of fixed diameter. Define the diameter
     * diam(F) of a family F ⊂ 2[n] as"}. The regex match is correct (it IS a
     * subsection), but the captured title shouldn't carry the body-text overflow
     * into the DB / API / LLM prompts.</blockquote>
     *
     * <p>Truncate at the first period after the section-number prefix — "almost
     * always the title-terminating period before sentence-case prose begins".
     */
    private static String trimSubsectionTitle(String line) {
        int i = 0;
        // section-number digits and the first period (`3.`)
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i < line.length() && line.charAt(i) == '.') {
            i++;
        }
        // subsection-number digits and an optional second period (`1.`)
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i < line.length() && line.charAt(i) == '.') {
            i++;
        }
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        int endOfTitle = line.indexOf('.', i);
        return endOfTitle < 0 ? line : line.substring(0, endOfTitle + 1);
    }

    /**
     * Whether the next line with anything on it reads like body text.
     *
     * <p>The second half of the title-case rule, and what stops a list of
     * capitalised items becoming a run of empty sections: prose has at least one
     * space and is not shouted.
     */
    private static boolean nextNonEmptyLooksLikeProse(String[] lines, int from, int end) {
        for (int j = from; j < end; j++) {
            String trimmed = lines[j].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            return trimmed.contains(" ") && !trimmed.equals(trimmed.toUpperCase(Locale.ROOT));
        }
        return false;
    }

    private static boolean hasText(String[] lines, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!lines[i].isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One section, as the detector found it.
     *
     * @param title the document's own heading, or {@link StructuralRef.Synthetic}
     *     for a unit the parser invented
     * @param ordinal where in its chapter, from 1
     * @param bodyStartLine the first line of the section's text, which excludes
     *     its own heading
     * @param endLine one past the section's last line
     */
    public record Detected(StructuralRef title, int ordinal, int bodyStartLine, int endLine) {

        public Detected {
            Objects.requireNonNull(title, "title");
            if (ordinal < 1) {
                throw new IllegalArgumentException(
                        "sections are counted from 1, and this one is at " + ordinal);
            }
        }
    }
}
