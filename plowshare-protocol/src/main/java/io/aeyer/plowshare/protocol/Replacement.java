package io.aeyer.plowshare.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The one rule for {@code file_edit}'s partial edit: replace the single
 * occurrence of a piece of text, or refuse.
 *
 * <h2>Why this is in the protocol module</h2>
 *
 * <p>{@link Window}'s and {@link FileAccess}'s reason. An edit is applied on the
 * machine that owns the file — the server's {@code LocalProvider} or a client —
 * because only that machine holds the file's own bytes: a read carries lines,
 * and a file's CRLFs and its last newline are gone before a line reaches the
 * server. Both Java sides execute this class rather than each writing it, and
 * the TUI's copy is held to the same case table,
 * {@code src/test/resources/io/aeyer/plowshare/protocol/replacements.json}.
 *
 * <h2>What counts as one occurrence</h2>
 *
 * <p><b>Every position, overlapping ones included</b>, so {@code aa} occurs
 * twice in {@code aaa}. Counting only non-overlapping matches would call that
 * edit unambiguous and then pick one of two places the caller could have meant.
 * Java and JavaScript strings are both UTF-16, so {@code indexOf} finds the same
 * positions on both sides.
 *
 * <h2>What an edit reports</h2>
 *
 * <p>{@link #edit} hands back, beside the edited text, where the new text now
 * is and the lines around it; a refusal for an {@code old} that is not there
 * carries the nearest region of the file. <b>Both as facts</b> — {@link
 * FileResult} — which the server words: {@link EditFacts} owns what is found
 * and why, and no side that holds a file writes a sentence a model reads.
 *
 * <h2>Bytes in, bytes out</h2>
 *
 * <p>{@link #decode} is strict: a file that is not UTF-8 is refused rather than
 * decoded with replacement characters, which would rewrite every byte it could
 * not read. A strictly decoded file re-encodes to the same bytes, so an edit
 * changes exactly the text it names and nothing else.
 */
public final class Replacement {

    private Replacement() {
    }

    /** How many lines either side of the new text a successful edit shows. */
    public static final int CONTEXT_LINES = 3;

    /**
     * The most lines a view of a file shows. A longer region keeps its first and
     * last lines and says how many between them are left out.
     */
    public static final int MAX_SHOWN_LINES = 40;

    /** The most characters of one line a view shows; a longer one is cut and marked. */
    public static final int MAX_SHOWN_LINE_CHARS = 500;

    /**
     * The most look-alike or foreign characters a no-match names; the rest are
     * counted ({@link FileResult#count()}). A pasted paragraph of curly quotes is
     * one fact, and a paste of another script is not a list worth a frame.
     */
    public static final int MAX_LISTED = 5;

    /**
     * An edit that was made: the file's new text, and where the new text is.
     *
     * @param text the whole file after the edit
     * @param first the first line the new text is on, or where removed text was
     * @param last the last line the new text is on
     * @param removed whether the replacement was empty
     * @param excerpt the lines {@code first} to {@code last} with {@link
     *     #CONTEXT_LINES} either side, bounded by {@link #MAX_SHOWN_LINES} and
     *     {@link #MAX_SHOWN_LINE_CHARS}
     */
    public record Edited(String text, int first, int last, boolean removed, Excerpt excerpt) {

        public Edited {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(excerpt, "excerpt");
        }

        /** What the file side answers with, about the file as the caller named it. */
        public FileResult result(String named) {
            return FileResult.edited(named, first, last, removed, excerpt);
        }
    }

    /** Why a replacement was refused. The words are the server's. */
    public enum Kind {
        /** {@code old} is not in the file. */
        ABSENT,
        /** {@code old} is in the file more than once. */
        AMBIGUOUS,
        /** {@code old} is empty, which occurs everywhere. */
        EMPTY
    }

    /** A replacement that could not be made, with how many occurrences there were. */
    public static final class Refused extends IllegalArgumentException {

        private final Kind kind;
        private final int count;
        /** The file and the text that was not in it, for an absent one's view; else null. */
        private final transient String text;
        private final transient String old;

        Refused(Kind kind, int count) {
            this(kind, count, null, null);
        }

        Refused(Kind kind, int count, String text, String old) {
            super(kind + " (" + count + ")");
            this.kind = kind;
            this.count = count;
            this.text = text;
            this.old = old;
        }

        public Kind kind() {
            return kind;
        }

        public int count() {
            return count;
        }

        /**
         * What the file side answers with, about the file as the caller named
         * it: {@link FileResult#NO_MATCH} with what {@link EditFacts} found
         * nearest, {@link FileResult#MANY_MATCHES} with the count, or {@link
         * FileResult#EMPTY_OLD}.
         */
        public FileResult result(String named) {
            return switch (kind) {
                case EMPTY -> FileResult.refused(FileRequest.EDIT, FileResult.EMPTY_OLD, named);
                case ABSENT -> EditFacts.absent(named, text == null ? "" : text,
                        old == null ? "" : old);
                case AMBIGUOUS -> FileResult.manyMatches(named, count);
            };
        }
    }

    /**
     * {@code text} with the single occurrence of {@code old} replaced.
     *
     * @throws Refused if {@code old} is empty, absent, or occurs more than once
     */
    public static String apply(String text, String old, String replacement) {
        return edit(text, old, replacement).text();
    }

    /**
     * The ASCII character {@code codePoint} is typed for, when it is one of the
     * look-alikes an edit folds (a U+2011 for a {@code -}), or -1 — which the
     * server's renderer asks when it names a character the file lacks.
     */
    public static int lookalikeOf(int codePoint) {
        return EditFacts.lookalikeOf(codePoint);
    }

    /**
     * {@link #apply}, and where in the result the new text is.
     *
     * @throws Refused if {@code old} is empty, absent, or occurs more than once
     */
    public static Edited edit(String text, String old, String replacement) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(old, "old");
        Objects.requireNonNull(replacement, "replacement");
        if (old.isEmpty()) {
            throw new Refused(Kind.EMPTY, 0);
        }
        int first = text.indexOf(old);
        if (first < 0) {
            throw new Refused(Kind.ABSENT, 0, text, old);
        }
        int count = 1;
        for (int at = text.indexOf(old, first + 1); at >= 0; at = text.indexOf(old, at + 1)) {
            count++;
        }
        if (count > 1) {
            throw new Refused(Kind.AMBIGUOUS, count);
        }
        String edited = text.substring(0, first) + replacement
                + text.substring(first + old.length());
        return EditFacts.success(edited, first, replacement);
    }

    /**
     * A file's bytes as text, or {@code null} when they are not UTF-8.
     *
     * <p>Null and not an exception: whether that is a refusal is the caller's,
     * which answers {@link FileResult#NOT_TEXT} with it.
     */
    public static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notText) {
            return null;
        }
    }
}
