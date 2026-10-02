package io.aeyer.plowshare.server.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A document's text into the chapters it declares.
 *
 * <p>Anchor's {@code ingest.ChapterDetector}, ported with its constants and the
 * comments that record why each one exists. <b>V18 declined this class and
 * described it accurately</b> — "~40 hand-curated strings and three regexes
 * fitted to English chemistry and optimisation PDFs, each constant a specific
 * paper that broke a specific prompt" — and drew the wrong conclusion from an
 * accurate description. That is a <em>regression log</em>, and a regression log
 * is the one kind of heuristic collection that is worth carrying wholesale:
 * dropping it re-opens every failure it closes, one paper at a time, with
 * nothing to say which.
 *
 * <h2>Precedence, and it is a precedence rather than a union</h2>
 *
 * <ol>
 *   <li><b>a numbered sequence, whatever it is called</b> — see {@link
 *       #sequenceBoundaries},
 *   <li>an explicit {@code ^Chapter N} heading,
 *   <li><b>the PDF outline's top-level entries</b> — the author's own table of
 *       contents, which is the only branch here that is not a guess about
 *       prose,
 *   <li>{@code ^Part I/II} markers,
 *   <li>one synthetic chapter over the whole document.
 * </ol>
 *
 * <p>The first branch that finds a boundary wins outright. A paper that says
 * both "Chapter 3" and "Part II" is a book with parts, and reading both as
 * boundaries would interleave two levels of one hierarchy into one list.
 *
 * <p><b>Whichever of the first three wins, the text above its first boundary is
 * a chapter too</b> — a synthetic one at ordinal 1, so that the chapters
 * partition the document rather than starting where its first heading does. See
 * {@link #preamble}, which is §3.3(b)'s divergence one level up.
 *
 * <p>Anchor's deferred fourth idea — a font-size-aligned heading detector — is
 * deferred here too, and for a reason that got sharper rather than weaker: it
 * needs per-glyph positioning, {@code PdfExtraction} carries text and an
 * outline, and the outline branch is the cheaper answer to most of what font
 * sizes would have been used for.
 */
public final class ChapterDetector {

    private static final Pattern CHAPTER_REGEX =
            Pattern.compile("^\\s*Chapter\\s+[0-9IVXLC]+\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART_REGEX =
            Pattern.compile("^\\s*Part\\s+[0-9IVXLC]+\\b.*", Pattern.CASE_INSENSITIVE);

    /** The academic-paper section shape, counted by {@link #detectVocabulary}
     *  and detected as a section boundary by {@link SectionDetector}. */
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+\\.\\s+[A-Z].{0,80}$");

    /**
     * Top-level headings that look chapter-shaped but are non-content — front
     * matter, back matter, navigational scaffolding. Anchor's thirteen, and its
     * reason, which is the most concrete failure in the whole port:
     *
     * <blockquote>When these get promoted to chapters, the per-chapter
     * summarizer dutifully writes a "References" or "Appendix" summary, which
     * the deliberation prompt then surfaces as authoritative content — and the
     * model writes things like "the References chapter concludes with a
     * counterexample," fragment-stitching that reads as fabrication.</blockquote>
     *
     * <p>It also removes real waste this corpus pays today: with no exclusions a
     * bibliography's blank-line-separated entries each become a paragraph, each
     * costs a model call in the cascade, and each is embedded and searchable as
     * though it were a claim.
     *
     * <p>The filter runs after stripping leading numeric prefixes, so "5.
     * References" and "REFERENCES" collapse to the same key.
     */
    private static final Set<String> EXCLUDED_CHAPTER_TITLES = Set.of(
            "references", "bibliography", "works cited",
            "acknowledgements", "acknowledgments",
            "appendix", "appendices",
            "supporting information", "supplementary material",
            "table of contents", "contents", "index",
            "abstract");

    private ChapterDetector() {
    }

    /**
     * Best-effort detection of the source's preferred terminology, ported whole.
     *
     * <p>Counts heading-shaped lines of each of the three formats; the format
     * with the most matches wins, and two hits are required before a format is
     * believed at all. {@link Vocabulary#SECTION} is the default "because
     * untagged academic papers dominate Anchor's corpus" — and they dominate any
     * corpus this ask path is built for, since the whole design is per-document
     * deliberation over a paper.
     */
    public static Vocabulary detectVocabulary(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return Vocabulary.SECTION;
        }
        int chapterHits = 0;
        int partHits = 0;
        int sectionHits = 0;
        for (String line : fullText.split("\\n", -1)) {
            if (CHAPTER_REGEX.matcher(line).matches()) {
                chapterHits++;
            } else if (PART_REGEX.matcher(line).matches()) {
                partHits++;
            } else if (NUMBERED.matcher(line).matches()) {
                sectionHits++;
            }
        }
        if (chapterHits >= 2 && chapterHits >= partHits && chapterHits >= sectionHits) {
            return Vocabulary.CHAPTER;
        }
        if (partHits >= 2 && partHits > sectionHits) {
            return Vocabulary.PART;
        }
        return Vocabulary.SECTION;
    }

    /**
     * The chapters of this document, in order.
     *
     * @param fullText the extracted document
     * @param outlineTopLevel the top-level entries of the document's own
     *     outline, from {@code Extracted}. Empty for a format that has no such
     *     thing, which until stage 1 was every format this server could read
     * @return one entry per surviving chapter, plus a synthetic one at ordinal 1
     *     for any text above the first of them; empty only for a document with
     *     no text at all
     */
    public static List<Detected> detect(String fullText, List<String> outlineTopLevel) {
        Objects.requireNonNull(outlineTopLevel, "outlineTopLevel");
        if (fullText == null || fullText.isBlank()) {
            return List.of();
        }
        // `\n` and not Anchor's `\R`, and it has to match Derivation.lines
        // exactly: the ranges below are indices into an array the paragraph
        // split re-derives, and two splitting rules would put the chapters over
        // a different document than the paragraphs are cut from. TextExtraction
        // has already normalised every line ending, so the two rules differ only
        // over form feeds and the exotic Unicode separators.
        String[] lines = fullText.split("\\n", -1);

        List<Detected> chapters = materialise(lines, sequenceBoundaries(lines));
        if (chapters.isEmpty()) {
            chapters = materialise(lines, boundaries(lines, CHAPTER_REGEX));
        }
        if (chapters.isEmpty()) {
            chapters = materialise(lines, outlineBoundaries(lines, outlineTopLevel));
        }
        if (chapters.isEmpty()) {
            chapters = materialise(lines, boundaries(lines, PART_REGEX));
        }
        if (chapters.isEmpty()) {
            // DIVERGENCE #10, and the one place this class is a fix rather than
            // a port. Anchor guards this fallback on no boundary having been
            // DETECTED; here it is guarded on none having SURVIVED, which is the
            // same condition one step later. In Anchor, a document whose every
            // heading is an excluded title materialises to an empty list, the
            // fallback does not fire, and the document is ingested as zero
            // chapters, zero sections and zero paragraphs — silently, as
            // nothing. Widened on the owner's instruction: "fix it and record
            // the divergance".
            return List.of(new Detected(new StructuralRef.Synthetic(), 1, 0, lines.length));
        }
        return chapters;
    }


    /** The longest a line can be and still be only a marker. A heading names a
     *  division; a sentence that opens with one is prose. */
    private static final int A_MARKER_IS_SHORT = 40;

    /** How many members a numbering must have before it is a numbering.
     *  {@link #A_LISTING}'s argument: two is a coincidence, three is a pattern. */
    private static final int A_SEQUENCE = 3;

    /** At most this many words in front of the number. "Letter 1" and "BOOK II"
     *  are markers; a running head is the paper's whole title and a page number,
     *  and this is what tells them apart without knowing either. */
    private static final int A_MARKERS_PREFIX = 3;

    /** {@code <one to three words> <number or roman numeral>}, and nothing else
     *  on the line. */
    private static final Pattern MARKER = Pattern.compile(
            "^\\s*(\\p{L}[\\p{L}'’]*(?:\\s+\\p{L}[\\p{L}'’]*){0,2})\\s+"
                    + "(\\d{1,3}|[IVXLC]{1,7})\\.?\\s*$");

    /**
     * Boundaries from a numbering the document keeps, whatever the document
     * calls it.
     *
     * <p><b>This branch exists because the three below it are a list of words we
     * have happened to meet.</b> {@code ^Chapter N} and {@code ^Part I} match
     * two English conventions; the PDF outline branch needs a PDF. A plain-text
     * book has none of those. Homer's divisions are books — which were scrolls,
     * and are chapters — Frankenstein opens in <em>letters</em> before it reaches
     * chapters, and neither is reachable by adding another word to a regex,
     * because the next document will use a different one. Measured before it was
     * built: erasing every varying part of the short non-sentence lines of four
     * documents and counting which <em>forms</em> recur finds all 24 of the
     * Odyssey's book headings and all 4 of Frankenstein's letters, with no
     * vocabulary at all.
     *
     * <h2>What makes a numbering, and why each condition is load-bearing</h2>
     *
     * <ol>
     *   <li><b>The line is only the marker</b> — at most {@value
     *       #A_MARKER_IS_SHORT} characters and not ending as a sentence does.
     *       {@code Theorem 1. Let k >= 3 be an integer...} opens with a marker
     *       and is a paragraph.
     *   <li><b>At most {@value #A_MARKERS_PREFIX} words before the number.</b>
     *       This is what excludes a running head, which is the paper's entire
     *       title beside a page number and otherwise recurs exactly as a chapter
     *       heading does. The same measurement found running heads in all three
     *       papers under this test and nothing else.
     *   <li><b>The numbers run 1, 2, 3, … with no gaps.</b> A division sequence
     *       starts at the first division. A running head that survived the rule
     *       above starts at whatever page it first appeared on and counts by
     *       twos.
     *   <li><b>At least {@value #A_SEQUENCE} of them.</b>
     * </ol>
     *
     * <p><b>Every sequence found is used, not the best one.</b> Frankenstein's
     * letters and its chapters are both divisions of the book and neither is
     * inside the other, so the boundaries are the union in document order. A
     * precedence would have to decide which of two real structures to discard.
     *
     * <p><b>A repeated numbering is two runs and both are kept here</b>, because
     * this method cannot tell a table of contents from the thing it lists — they
     * are the same lines. {@link #withContent} is what tells them apart, one step
     * later, by what lies between them.
     */
    private static List<Integer> sequenceBoundaries(String[] lines) {
        Map<String, List<int[]>> byPrefix = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.length() > A_MARKER_IS_SHORT) {
                continue;
            }
            Matcher marker = MARKER.matcher(line);
            if (!marker.matches()) {
                continue;
            }
            String prefix = marker.group(1).trim();
            if (prefix.split("\\s+").length > A_MARKERS_PREFIX) {
                continue;
            }
            int number = numberOf(marker.group(2));
            if (number <= 0) {
                continue;
            }
            byPrefix.computeIfAbsent(prefix.toLowerCase(Locale.ROOT), any -> new ArrayList<>())
                    .add(new int[] {i, number});
        }
        Set<Integer> found = new TreeSet<>();
        for (List<int[]> marks : byPrefix.values()) {
            found.addAll(runs(marks));
        }
        return new ArrayList<>(found);
    }

    /** The lines of every maximal 1, 2, 3, … run of at least {@value
     *  #A_SEQUENCE} markers, in the order the document has them. */
    private static List<Integer> runs(List<int[]> marks) {
        List<Integer> lines = new ArrayList<>();
        int from = 0;
        while (from < marks.size()) {
            int to = from;
            if (marks.get(from)[1] == 1) {
                while (to + 1 < marks.size() && marks.get(to + 1)[1] == marks.get(to)[1] + 1) {
                    to++;
                }
            }
            if (to - from + 1 >= A_SEQUENCE) {
                for (int i = from; i <= to; i++) {
                    lines.add(marks.get(i)[0]);
                }
            }
            from = to + 1;
        }
        return lines;
    }

    /**
     * A marker's number, arabic or roman, or {@code -1}.
     *
     * <p>Roman is not optional: the Odyssey numbers its books {@code I} to
     * {@code XXIV} and nothing else in this class can read that. Ambiguity is
     * resolved towards arabic, which is what the pattern's own alternation
     * order does; a bare {@code I}, {@code V}, {@code X}, {@code L} or {@code C}
     * is read as roman because on a line of its own beside one or two words that
     * is what it is.
     */
    private static int numberOf(String token) {
        String bare = token.endsWith(".") ? token.substring(0, token.length() - 1) : token;
        if (bare.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(bare);
            } catch (NumberFormatException tooBig) {
                return -1;
            }
        }
        int total = 0;
        int previous = 0;
        for (int i = bare.length() - 1; i >= 0; i--) {
            int digit = switch (bare.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> -1;
            };
            if (digit < 0) {
                return -1;
            }
            total += digit < previous ? -digit : digit;
            previous = Math.max(previous, digit);
        }
        return total > 0 ? total : -1;
    }

    private static List<Integer> boundaries(String[] lines, Pattern pattern) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).matches()) {
                starts.add(i);
            }
        }
        return starts;
    }

    /**
     * Lines that are, exactly, one of the outline's top-level entries.
     *
     * <p>Exact match after trimming and lower-casing, which is Anchor's rule and
     * is deliberately strict: an outline entry is a string the author wrote and
     * the extractor found again, so a near-match is a different line. A fuzzy
     * rule here would put a chapter boundary in the middle of prose that
     * happened to paraphrase a heading.
     */
    private static List<Integer> outlineBoundaries(String[] lines, List<String> outline) {
        if (outline.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String entry : outline) {
            wanted.add(entry.toLowerCase(Locale.ROOT).trim());
        }
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && wanted.contains(trimmed)) {
                starts.add(i);
            }
        }
        return starts;
    }

    /**
     * Boundaries into ranges, dropping the excluded ones.
     *
     * <p><b>Two steps, and the order between them is the design.</b> An excluded
     * heading is a boundary FIRST — so the chapter before it ends where it
     * starts, rather than running on and swallowing a bibliography — and is
     * dropped SECOND, without consuming an ordinal. A reader looking at chapters
     * 1, 2, 3 must not be looking at a gap where a chapter failed to store.
     *
     * <p><b>And the text above the first boundary is a chapter of its own</b> —
     * see {@link #preamble}.
     */
    private static List<Detected> materialise(String[] lines, List<Integer> starts) {
        List<Integer> real = withContent(lines, starts);
        List<Detected> chapters = new ArrayList<>();
        int ordinal = 1;
        for (int i = 0; i < real.size(); i++) {
            int start = real.get(i);
            int end = (i + 1 < real.size()) ? real.get(i + 1) : lines.length;
            String title = lines[start].trim();
            if (isExcludedTitle(title)) {
                continue;
            }
            // start + 1: a detected chapter's heading is a line of the document,
            // and it is not part of the chapter's body. The synthetic chapter
            // above has no heading line and so starts at 0 — see Detected.
            chapters.add(new Detected(new StructuralRef.Named(title), ordinal++, start + 1, end));
        }
        // `real` and not `starts`, so a document that opens with its own table
        // of contents gets a preamble reaching the first chapter that exists
        // rather than one ending at the contents' first line.
        return preamble(lines, real, chapters);
    }

    /**
     * The boundaries that are divisions, dropping the ones that are a document
     * listing its own divisions.
     *
     * <p><b>Found on Frankenstein, which detected 49 chapters for a book with
     * 28.</b> Project Gutenberg's edition opens with a table of contents whose
     * entries are the lines {@code Letter 1} … {@code Chapter 24}, and every
     * boundary rule in this class matches a <em>listing</em> of headings exactly
     * as well as it matches the headings — they are the same lines. So every
     * division was detected twice, and 24 of the 49 chapters held no text at
     * all.
     *
     * <p><b>Why that is worse than a wrong count.</b> The critic's entire
     * evidence in {@code Deliberation} is the document summary plus the
     * top-level summaries — that is the whole of the evidence asymmetry. Half of
     * them described nothing. It also spends a summariser call per empty unit.
     *
     * <p>Two rules, each with its own reason:
     *
     * <ol>
     *   <li><b>A boundary with no text under it is not a division.</b> There is
     *       nothing to summarise, nothing to retrieve and nothing to cite; it can
     *       only contribute an empty bullet to a macro view. This holds whatever
     *       produced it, and it is the rule that removes most of a contents
     *       block, whose entries sit on consecutive lines.
     *   <li><b>A title that a later boundary uses again is a reference to that
     *       one.</b> A document does not have two divisions with the same name;
     *       when a name occurs twice the first is the document pointing at the
     *       second. This is what catches a contents block's <em>last</em> entry,
     *       which is the only one with text beneath it — everything from there to
     *       the real first division — and which rule 1 therefore keeps.
     * </ol>
     *
     * <p><b>Rule 2 replaced a positional rule that was measurably wrong.</b> The
     * first attempt read the entry after a run of empty ones as the listing's
     * last member. That is true of a contents block and is true of nothing else:
     * on this same book the contents are followed immediately by the body's own
     * {@code Letter 1}, so the rule deleted the first real division of the
     * document it was written for. Position cannot tell a listing from what
     * follows it; the repeated name can.
     */
    private static List<Integer> withContent(String[] lines, List<Integer> starts) {
        int n = starts.size();
        boolean[] drop = new boolean[n];
        for (int i = 0; i < n; i++) {
            int end = (i + 1 < n) ? starts.get(i + 1) : lines.length;
            drop[i] = !hasText(lines, starts.get(i) + 1, end);
        }
        // Backwards, so that "later" holds only titles used AFTER i. The last
        // occurrence of a name is the division; the earlier ones point at it.
        Set<String> later = new LinkedHashSet<>();
        for (int i = n - 1; i >= 0; i--) {
            if (!later.add(sameName(lines[starts.get(i)]))) {
                drop[i] = true;
            }
        }
        List<Integer> real = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!drop[i]) {
                real.add(starts.get(i));
            }
        }
        return real;
    }

    /**
     * Two headings' titles, comparable.
     *
     * <p>Letters and digits only, lower-cased — {@link #isExcludedTitle}'s own
     * normalisation, and here for a reason measured on the Odyssey. Project
     * Gutenberg's edition punctuates its contents as {@code BOOK XXIV.} and its
     * heading as {@code BOOK XXIV}, so an exact comparison read them as two
     * different divisions and kept the contents' last entry — which then carried
     * the whole of Butler's preface under the name of the poem's final book. A
     * full stop is not a different chapter.
     */
    private static String sameName(String line) {
        return line.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * The text above the first chapter boundary, as a chapter the parser
     * invented.
     *
     * <p><b>DIVERGENCE, and it is §3.3(b)'s divergence one level up.</b> Anchor
     * builds chapters from {@code boundaries[0]} onward, so every line above the
     * first detected heading belongs to no chapter — and therefore to no
     * section, no paragraph, and no row. On a paper that is the title line, the
     * authors and an unlabelled abstract.
     * {@code implementation rationale} §3
     * measured it: the same four-page paper derives 20 paragraphs with no
     * outline and <b>17 with one</b>, because an outline is what makes a chapter
     * boundary exist at all on a paper. It fires on every detected branch —
     * {@code ^Chapter N}, the outline, {@code ^Part I} — and never on the
     * synthetic fallback, whose one chapter already starts at line 0.
     *
     * <p><b>A synthetic chapter at ordinal 1 rather than the first chapter's
     * range extended up to line 0.</b> Extending the range is the cheaper edit
     * and it says something false: it files a paper's title block and abstract
     * under a heading its author wrote for something else, and every render site
     * downstream — the ask prompt's chapter list, the REST hierarchy, the
     * chapter summariser — would then read that heading as covering text that
     * sits above it. A unit with no heading of its own is exactly what {@link
     * StructuralRef.Synthetic} and {@code is_synthetic} already mean, and
     * {@link SectionDetector} already answers the identical question that way
     * one level down. The two levels now agree: chapters partition the document,
     * sections partition a chapter, and the parser's inventions are the units
     * that say so.
     *
     * <p><b>Only in front of a chapter that survived.</b> When every detected
     * heading was excluded there is nothing for a preamble to precede, and
     * returning it alone would re-open DIVERGENCE #10 from the other side — the
     * fallback would not fire and the excluded block's own text would be the
     * thing dropped instead. The empty list goes back untouched and {@link
     * #detect} puts one synthetic chapter over the whole document.
     *
     * <p><b>It composes with §3.3(c) rather than re-opening it.</b> The preamble
     * has no heading line to skip, so its {@code bodyStartLine} is 0 and not
     * {@code start + 1} — which is the whole of what {@link Detected} carries
     * {@code bodyStartLine} for, and what {@link SectionDetector} is already
     * written against.
     */
    private static List<Detected> preamble(
            String[] lines, List<Integer> starts, List<Detected> chapters) {
        if (chapters.isEmpty() || starts.isEmpty()) {
            return List.copyOf(chapters);
        }
        int firstBoundary = starts.get(0);
        if (!hasText(lines, 0, firstBoundary)) {
            return List.copyOf(chapters);
        }
        List<Detected> withPreamble = new ArrayList<>(chapters.size() + 1);
        withPreamble.add(new Detected(new StructuralRef.Synthetic(), 1, 0, firstBoundary));
        for (Detected chapter : chapters) {
            // Renumbered rather than numbered from 2 in the loop above, so that
            // the ordinals stay contiguous from 1 whether or not the document
            // has a preamble — the exclusions' own rule, one level out.
            withPreamble.add(new Detected(chapter.title(), chapter.ordinal() + 1,
                    chapter.bodyStartLine(), chapter.endLine()));
        }
        return List.copyOf(withPreamble);
    }

    /** Whether anything but blank lines lies in {@code [from, to)}. Blank lines
     *  above a first heading are layout, not a preamble. */
    private static boolean hasText(String[] lines, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!lines[i].isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for a heading that is scaffolding rather than content.
     *
     * <p>Strips leading numbering and then every non-letter, so "5. References",
     * "References" and "REFERENCES" collapse to one key.
     *
     * <p><b>What this does not catch, stated because it looks like an
     * oversight and is Anchor's behaviour.</b> The word "chapter" is not
     * stripped, so "Chapter 7 References" keys as "chapter  references" and
     * matches nothing — the list therefore never fires on the {@code ^Chapter N}
     * branch, only on the outline and part branches where a heading line is the
     * bare title. Left as it is: every entry in the list records a real paper,
     * and widening the rule would be new behaviour fitted to no observed
     * failure.
     */
    private static boolean isExcludedTitle(String title) {
        if (title == null) {
            return false;
        }
        String key = title.toLowerCase(Locale.ROOT)
                .replaceAll("^[\\d\\.\\s]+", "")
                .replaceAll("[^a-z\\s]", "")
                .trim();
        return EXCLUDED_CHAPTER_TITLES.contains(key);
    }

    /**
     * One chapter, as the detector found it.
     *
     * @param title the document's own heading, or {@link StructuralRef.Synthetic}
     *     for the chapter the parser invented. <b>A {@link StructuralRef} and not
     *     a string</b>, so that nothing downstream can render a parser-invented
     *     unit without saying how it degrades
     * @param ordinal where in the document, from 1 — V26's base, and not
     *     Anchor's 0
     * @param bodyStartLine the first line of this chapter's text.
     *     <b>Anchor computes this downstream as {@code startLine + 1} and gets
     *     it wrong for the synthetic chapter</b>, which has no heading line to
     *     skip, so every document with no detected chapter loses its first line
     *     to an off-by-one. It is computed here, where the difference between "a
     *     heading was found" and "one was invented" is still known
     * @param endLine one past the chapter's last line
     */
    public record Detected(StructuralRef title, int ordinal, int bodyStartLine, int endLine) {

        public Detected {
            Objects.requireNonNull(title, "title");
            if (ordinal < 1) {
                throw new IllegalArgumentException(
                        "chapters are counted from 1, and this one is at " + ordinal);
            }
        }
    }
}
