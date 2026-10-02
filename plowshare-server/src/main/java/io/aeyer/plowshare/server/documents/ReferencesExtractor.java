package io.aeyer.plowshare.server.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The document's own bibliography, recovered after the hierarchy threw it away.
 *
 * <p>Anchor's {@code ingest.ReferencesExtractor}, and its reason for existing is
 * the exclusions:
 *
 * <blockquote>The structural parser drops references entirely because they
 * aren't claim-bearing — they don't contribute to summaries at any level. That's
 * the right call for the deliberation pipeline but it makes the citation list
 * invisible to anything downstream.</blockquote>
 *
 * <p>The list is not decoration. It is what lets the deliberation tell "an
 * author of this document" from "a third party this document cites" — a
 * distinction Anchor added after the pipeline proved structurally blind to
 * identity-shaped questions, because no summary at any level names an author.
 *
 * <h2>Where this departs from Anchor, and why</h2>
 *
 * <p>Anchor stops at the raw block and hands it to a model:
 * {@code DocumentMetadataExtractor.extractCitations} spends one chat call
 * turning the text into {@code Citation(refNum, raw)} rows. That extractor
 * belongs to the cascade slice, and this port needs the rows now — a table with
 * no writer is exactly the thing V18 refused to freeze.
 *
 * <p>So {@link #extract} splits the numbered forms deterministically, out of the
 * predicates Anchor already wrote in this same class: a bracketed {@code [16]}
 * or a plain {@code 16.} starts an entry, and Anchor's own observation that
 * "lowercase-start lines are continuation lines from a wrapped reference" is
 * what joins the rest. No model call, on the pipeline's own rule that ingest
 * derives before it summarises. An author-year bibliography yields nothing, and
 * that is the honest answer rather than a gap: {@code ref_num} is the handle the
 * paper's prose points with, and a bibliography that prints no numbers has none.
 */
public final class ReferencesExtractor {

    private static final Pattern REFERENCES_HEADING = Pattern.compile(
            "^\\s*(\\d+\\.?\\s*)?(references|bibliography)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * A chapter-style heading that would terminate the block. Anchor's note:
     * "Mirrors the patterns ChapterDetector uses... but kept narrower because
     * we're looking for the <em>next</em> major section boundary, not detecting
     * chapters from scratch."
     *
     * <p><b>Case-sensitive, which is Anchor's and looks like an oversight.</b>
     * The literal alternatives are lower-case, so a line reading "Chapter 5"
     * never terminates a bibliography and only the numbered form does. Left as
     * it is: the failure it could cause is a bibliography that runs on into a
     * following chapter, which costs some rows in {@code document_references} and
     * nothing in the hierarchy, and the narrow regex is deliberate.
     */
    private static final Pattern CHAPTER_BOUNDARY = Pattern.compile(
            "^\\s*(chapter\\s+\\d+|part\\s+[IVX]+|\\d+\\.\\s+[A-Z][a-zA-Z].{2,80})\\s*$");

    /** {@code [16] Author, Title} — the bracketed form. */
    private static final Pattern BRACKETED_ENTRY = Pattern.compile("^\\[(\\d+)\\]\\s*(.*)$");

    /** {@code 16. Author, Title} — the plain form. */
    private static final Pattern NUMBERED_ENTRY = Pattern.compile("^(\\d+)\\.\\s+(.*)$");

    /** How long a numbered line has to be before it reads as a reference entry
     *  rather than as a section heading. Anchor's heuristic and its reason: "30
     *  chars is... short enough to not exclude any real references, long enough
     *  to exclude one-word chapter titles like '1. Introduction'." */
    private static final int LOOKS_LIKE_AN_ENTRY = 30;

    private ReferencesExtractor() {
    }

    /**
     * The raw text of the references block, joined by newlines, or {@code ""}
     * when the document has no references heading. Excludes the heading itself.
     */
    public static String findReferencesText(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return "";
        }
        String[] lines = fullText.split("\\R", -1);

        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (REFERENCES_HEADING.matcher(lines[i]).matches()) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return "";
        }

        int end = lines.length;
        for (int i = start; i < lines.length; i++) {
            // Anchor's two guards: don't terminate on the heading just consumed,
            // and require a few lines of content first, so a numbered entry the
            // narrow regex mistakes for a chapter heading cannot truncate the
            // list at its first line.
            if (i - start < 3) {
                continue;
            }
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || looksLikeReferenceEntry(trimmed)) {
                continue;
            }
            if (CHAPTER_BOUNDARY.matcher(lines[i]).matches()) {
                end = i;
                break;
            }
        }

        StringBuilder text = new StringBuilder();
        for (int i = start; i < end; i++) {
            text.append(lines[i]);
            if (i < end - 1) {
                text.append('\n');
            }
        }
        return text.toString().trim();
    }

    /**
     * The document's bibliography as rows, in the order it prints them.
     *
     * <p>Empty for a document with no references heading, and empty for one
     * whose bibliography numbers nothing.
     *
     * @param fullText the extracted document — not the block, so that a caller
     *     never has to know this class has two halves
     */
    public static List<Reference> extract(String fullText) {
        String block = findReferencesText(fullText);
        if (block.isEmpty()) {
            return List.of();
        }
        List<Reference> found = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int number = 0;
        for (String line : block.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher marker = BRACKETED_ENTRY.matcher(trimmed);
            if (!marker.matches()) {
                Matcher plain = NUMBERED_ENTRY.matcher(trimmed);
                marker = plain.matches() && trimmed.length() >= LOOKS_LIKE_AN_ENTRY ? plain : null;
            }
            if (marker != null) {
                emit(found, number, current);
                number = Integer.parseInt(marker.group(1));
                current.setLength(0);
                current.append(marker.group(2).trim());
                continue;
            }
            if (number > 0) {
                // A continuation of the entry above: the wrapping belongs to the
                // page and not to the bibliography, so it is re-flowed exactly
                // as `Derivation` re-flows a paragraph.
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(trimmed);
            }
        }
        emit(found, number, current);
        return List.copyOf(found);
    }

    private static void emit(List<Reference> found, int number, StringBuilder current) {
        String raw = current.toString().trim();
        if (number > 0 && !raw.isEmpty()) {
            found.add(new Reference(number, raw));
        }
    }

    /**
     * Anchor's guard against truncating the list at one of its own entries:
     * {@code "16. Author, Title"} and {@code "[16] Author, Title"} both look like
     * chapter headings to the narrow boundary regex.
     */
    private static boolean looksLikeReferenceEntry(String trimmed) {
        if (trimmed.startsWith("[")) {
            return true;
        }
        int dot = trimmed.indexOf('.');
        if (dot > 0 && dot <= 4) {
            try {
                Integer.parseInt(trimmed.substring(0, dot));
                return trimmed.length() >= LOOKS_LIKE_AN_ENTRY;
            } catch (NumberFormatException notANumberedEntry) {
                // Fall through: a line like "e.g." is not a numbered entry.
            }
        }
        // A lowercase-start line is a continuation of a wrapped reference.
        return !trimmed.isEmpty() && Character.isLowerCase(trimmed.charAt(0));
    }

    /**
     * One entry of the document's own reference list. Anchor's {@code
     * Citation(refNum, raw)}, and <b>not</b> V25's {@code citations}, which
     * records what an answer took from the corpus.
     *
     * @param refNum the number the document itself prints, from 1. Its name and
     *     not its position: the paper's prose points at it as "[16]"
     * @param raw the entry as printed. Content the server did not write and
     *     cannot vouch for
     */
    public record Reference(int refNum, String raw) {

        public Reference {
            Objects.requireNonNull(raw, "raw");
            if (refNum < 1) {
                throw new IllegalArgumentException(
                        "a reference is numbered from 1, and this one is " + refNum);
            }
            if (raw.isBlank()) {
                throw new IllegalArgumentException("a blank reference entry is not an entry");
            }
        }
    }
}
