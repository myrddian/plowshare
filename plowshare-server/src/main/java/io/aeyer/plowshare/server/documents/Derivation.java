package io.aeyer.plowshare.server.documents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A document's text into the hierarchy, paragraphs and chunks it is stored as.
 *
 * <p>Anchor's {@code StructuralParser}: it composes {@link ChapterDetector},
 * {@link SectionDetector} and {@link Vocabulary} around the paragraph split, and
 * hands the result to {@link DocumentStore} to be written.
 *
 * <p><b>The detectors were declined once and are back.</b> V18's judgement was
 * that they are "~40 hand-curated strings and three regexes fitted to English
 * chemistry and optimisation PDFs — each constant a specific paper that broke a
 * specific prompt", and that a hierarchy nothing read would be frozen DDL for
 * ever. The description was accurate and the conclusion was the wrong one to
 * draw from it: a list of specific papers that broke specific prompts is a
 * regression log, and dropping it re-opens every failure it closes. The second
 * half is answered by there being a writer for everything V26 creates, in the
 * same change.
 *
 * <p>Note the javadoc trap the survey names: {@code StructuralParser}'s own
 * javadoc says summarisation "lives downstream in the (forthcoming)
 * summarisation service" when it is fully built. Anchor's comments date
 * themselves rather than the tree, and the code was read rather than the prose.
 */
public final class Derivation {

    private Derivation() {
    }

    /**
     * One derivation of one document: its vocabulary, its chapters and their
     * sections, the paragraphs under them, and the bibliography the detectors
     * dropped.
     *
     * @param extracted the document's text and the outline it declared, if the
     *     format carries one
     * @param chunking what chunk packing aims at and the ceiling it may not
     *     cross, and the tokenizer both are counted with; see {@link Chunker}
     */
    public static DerivedDocument derive(Extracted extracted, Chunking chunking) {
        Objects.requireNonNull(extracted, "extracted");
        return derive(extracted.text(), extracted.outlineTopLevel(),
                extracted.paragraphsWereInferred(), chunking);
    }

    /**
     * The same derivation over text and an outline that are not travelling
     * together.
     *
     * <p><b>Text with no provenance is text whose blank lines are the author's,
     * so this overload never joins blocks.</b> {@link
     * Extracted#paragraphsWereInferred()} is the question {@link #joined} turns
     * on, and a bare string cannot answer it; assuming the boundaries are a
     * guess would let this method delete paragraphs somebody typed.
     *
     * @param text the extracted document, line endings already normalised by
     *     {@link TextExtraction}
     * @param outlineTopLevel the document's own top-level outline entries, or
     *     empty for a format that declares none
     */
    public static DerivedDocument derive(
            String text, List<String> outlineTopLevel, Chunking chunking) {
        return derive(text, outlineTopLevel, false, chunking);
    }

    /**
     * The derivation, told whether the blank lines in {@code text} were inferred
     * from a page's geometry or typed by whoever wrote the document.
     *
     * @param paragraphsWereInferred see {@link Extracted#paragraphsWereInferred()}
     */
    static DerivedDocument derive(String text, List<String> outlineTopLevel,
            boolean paragraphsWereInferred, Chunking chunking) {
        Objects.requireNonNull(outlineTopLevel, "outlineTopLevel");
        Objects.requireNonNull(chunking, "chunking");
        if (text == null || text.isBlank()) {
            return new DerivedDocument(Vocabulary.SECTION, List.of(), List.of());
        }
        String[] lines = lines(text);
        Vocabulary vocabulary = ChapterDetector.detectVocabulary(text);

        // The document's paragraph ordinals and the occurrence counter run
        // ACROSS the whole document and not within a section, because that is
        // what V18 froze `paragraphs.ordinal` and the identity rule to mean. So
        // the walk below is one pass in document order and the tree is built
        // around it, rather than each section deriving independently.
        Ordinals ordinals = new Ordinals();
        List<DerivedDocument.Chapter> chapters = new ArrayList<>();
        for (ChapterDetector.Detected chapter
                : ChapterDetector.detect(text, outlineTopLevel)) {
            List<DerivedDocument.Section> sections = new ArrayList<>();
            for (SectionDetector.Detected section
                    : SectionDetector.detect(lines, chapter.bodyStartLine(), chapter.endLine())) {
                List<DerivedParagraph> paragraphs = new ArrayList<>();
                List<String> blocks =
                        split(lines, section.bodyStartLine(), section.endLine());
                for (String paragraph : paragraphsWereInferred ? joined(blocks) : blocks) {
                    DerivedParagraph derived = ordinals.next(paragraph, chunking);
                    if (derived != null) {
                        paragraphs.add(derived);
                    }
                }
                sections.add(new DerivedDocument.Section(
                        section.ordinal(), section.title(), paragraphs));
            }
            chapters.add(new DerivedDocument.Chapter(
                    chapter.ordinal(), chapter.title(), sections));
        }
        return new DerivedDocument(vocabulary, chapters, ReferencesExtractor.extract(text));
    }

    /**
     * The document as lines, and <b>the one split every part of the derivation
     * has to agree on</b>.
     *
     * <p>{@code \n} and not {@code \R}, which is what Anchor uses: {@link
     * TextExtraction} has already turned every {@code \r\n} and {@code \r} into
     * a newline, so the two differ only over form feeds and the exotic Unicode
     * separators — and there they differ in a way that would move a paragraph's
     * content hash, which is a paragraph's identity. The chapter ranges, the
     * section ranges and the paragraph split are all indices into THIS array;
     * two splitting rules would put the hierarchy over a different document than
     * the paragraphs were cut from.
     */
    private static String[] lines(String text) {
        return text.split("\n", -1);
    }

    /** The running ordinal and occurrence counters, which are per document and
     *  not per section. */
    private static final class Ordinals {

        private final Map<String, Integer> seen = new HashMap<>();
        private int ordinal;

        DerivedParagraph next(String paragraph, Chunking chunking) {
            List<Chunker.Chunk> chunks = Chunker.chunk(paragraph, chunking);
            if (chunks.isEmpty()) {
                // Unreachable for a non-blank paragraph — the split already
                // dropped the blanks — and answered rather than assumed, because
                // DerivedParagraph refuses an empty chunk list and this is the
                // only place that could hand it one.
                return null;
            }
            String hash = sha256(paragraph);
            // The occurrence is counted within the hash and not across the
            // document, so the first "Notes" and the first "Other" are both 1.
            // A single running counter would make every repeated paragraph
            // unmatchable the moment anything before it was reordered.
            int occurrence = seen.merge(hash, 1, Integer::sum);
            return new DerivedParagraph(++ordinal, paragraph, hash, occurrence, chunks);
        }
    }

    /**
     * One or more blank lines is a break; contiguous non-empty lines are
     * re-flowed into one string with single spaces.
     *
     * <p>Anchor's {@code splitParagraphs}, and its reason ports unchanged: PDF
     * and hard-wrapped text break lines mid-sentence, so a chunker that saw
     * those breaks would split a claim at a place the author did not choose.
     *
     * <p><b>Over a section's line range, which is what changed with the
     * hierarchy.</b> This used to split the whole document, because with no
     * detectors there was no range to be within. Splitting per section is what
     * keeps a heading out of the paragraph beneath it: a section's body starts
     * after its own heading line, so a heading followed immediately by prose is
     * two things rather than one paragraph that begins by shouting its own name.
     */
    private static List<String> split(String[] lines, int from, int to) {
        List<String> found = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = from; i < to; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                if (current.length() > 0) {
                    found.add(current.toString().trim());
                    current.setLength(0);
                }
                continue;
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(trimmed);
        }
        if (current.length() > 0) {
            found.add(current.toString().trim());
        }
        return found;
    }


    /**
     * The fewest words a block must hold to be a paragraph on its own.
     *
     * <p>A <em>word</em> here is a run of at least three letters once leading
     * and trailing punctuation is taken off. Not characters, not tokens:
     * {@code (1−X)A(X)k = (1−X)} is 24 characters and six tokens and no words,
     * and that is the distinction the whole rule turns on.
     */
    private static final int A_PARAGRAPH = 4;

    /**
     * Long enough that this rule keeps its hands off, whatever it can read.
     *
     * <p><b>The word count cannot read every script and must not act as though
     * it can.</b> Chinese, Japanese and Thai are written without spaces, so a
     * whole paragraph arrives as one token — one "word" — and every rule below
     * would fold an entire document into its first paragraph. Nothing else here
     * would notice, because nothing here checks a language.
     *
     * <p>120 characters is the guard, and it is chosen against the measurement
     * rather than picked: on arXiv:2212.12473 the display-maths rows run to 68
     * characters at the 90th percentile while a real paragraph's median is 195,
     * so a cap here sits above everything this rule exists to join and below
     * what it must never touch. It costs 0.01 of seam F1 on that paper, which is
     * the price of the rule being unable to destroy a document it cannot parse.
     */
    private static final int LONG_ENOUGH_TO_LEAVE_ALONE = 120;

    /** Letters, in any script — {@code \p{L}} and not {@code [a-zA-Z]}, so an
     *  accented or non-Latin word counts as a word. */
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^\\p{L}]");
    private static final Pattern EDGE_PUNCTUATION =
            Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");

    /**
     * Blocks that are not paragraphs, joined to the paragraph above them.
     *
     * <p><b>This is the correction to what the blank-line split actually
     * measures.</b> {@link #split} cuts wherever the extraction left a blank
     * line, and in a PDF that is a geometric event rather than a paragraph
     * boundary: a display equation, a matrix row, a running head, a page number
     * and a line of table data each arrive as their own block. Hand counted,
     * arXiv:2212.12473 has 36–42 paragraphs and this derivation produced 134, of
     * which <b>69% were not paragraphs at all</b>.
     *
     * <h2>Backwards, and the direction is nearly the whole of the fix</h2>
     *
     * <p>A display equation belongs to the sentence that introduces it, so it
     * goes to the block <em>above</em>. Measured on that paper against the 41
     * boundaries between its real units, at one threshold and changing nothing
     * else:
     *
     * <ul>
     *   <li>joining backwards: <b>seam F1 0.81</b>, 98% of real boundaries kept;
     *   <li>joining forwards: <b>seam F1 0.60</b>, at the same unit count.
     * </ul>
     *
     * <p><b>The counts are identical and the segmentations are not</b>, which is
     * why a count is the wrong thing to tune against and why an earlier attempt
     * at this in {@code PdfExtraction} — geometric, and therefore blind to
     * direction — moved 134 to 86 while making the seams slightly worse.
     *
     * <h2>What it does to prose, which is the test that matters</h2>
     *
     * <p>The rule must be inert on a document that never had the problem. Rows
     * it touches: <b>27–57%</b> on three arXiv papers, <b>4%</b> on the Odyssey
     * and <b>5%</b> on Frankenstein — and on those two books every row it
     * touches is front matter, a licence tail, a footnote marker or a structural
     * heading. Not one line of narrative. The measured boundary quality of the
     * two books is unchanged by it, while the papers' rises to meet theirs.
     *
     * <h2>What it does not do</h2>
     *
     * <p>A running head is not display maths: it does not belong to the
     * paragraph above, it <em>interrupted</em> one, and joining it backwards
     * leaves the two halves of a severed sentence still severed with the page
     * number now inside the first. That wants a different action — drop it and
     * rejoin — and it wants {@link ChapterDetector}'s recurrence test to
     * recognise it first. It is not attempted here.
     */
    private static List<String> joined(List<String> blocks) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : blocks) {
            if (!paragraphs.isEmpty() && !isAParagraph(block)) {
                // A space and not a newline: `split` already re-flowed each
                // block to single spaces, and a paragraph's text is its
                // identity — a join that spelled the seam differently would give
                // every affected paragraph a different content hash than the
                // same text arriving unsplit.
                paragraphs.set(paragraphs.size() - 1,
                        paragraphs.get(paragraphs.size() - 1) + " " + block);
                continue;
            }
            paragraphs.add(block);
        }
        return paragraphs;
    }

    /** Whether a block stands on its own. See {@link #A_PARAGRAPH} and {@link
     *  #LONG_ENOUGH_TO_LEAVE_ALONE}. */
    private static boolean isAParagraph(String block) {
        if (block.length() >= LONG_ENOUGH_TO_LEAVE_ALONE) {
            return true;
        }
        int words = 0;
        for (String token : block.split("\\s+")) {
            String bare = EDGE_PUNCTUATION.matcher(token).replaceAll("");
            if (bare.codePointCount(0, bare.length()) >= 3
                    && !NOT_A_LETTER.matcher(bare).find()) {
                words++;
                if (words >= A_PARAGRAPH) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The paragraph's content hash.
     *
     * <p>Over the re-flowed text and not over the source lines, deliberately: a
     * document re-saved with a different line width is the same paragraph, and
     * hashing the wrapping would give every paragraph a new id for a change
     * nobody made to the prose.
     */
    static String sha256(String paragraph) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(paragraph.getBytes(StandardCharsets.UTF_8)))
                    .toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", impossible);
        }
    }
}
