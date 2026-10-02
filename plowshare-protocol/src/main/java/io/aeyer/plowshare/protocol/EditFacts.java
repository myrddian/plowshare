package io.aeyer.plowshare.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link Replacement} finds in a file for an edit's answer: the region an
 * edit changed, and the region nearest an {@code old} that was not there —
 * <b>as facts</b>, which the server words.
 *
 * <h2>Why an edit answers with the file at all</h2>
 *
 * <p>Measured on one eleven-hour run (2026-09-29/30, 306 {@code file_edit}
 * calls): 27 were refused because {@code old} was not in the file, and every
 * one was followed by a read and then an edit that worked — a round trip per
 * mismatch. Eleven of the 27 were a model editing a file it had already edited,
 * from its copy from before that edit: a success answered one sentence, so the
 * file's new state was never in front of it. Nine were paraphrases, two were
 * indentation, one was a U+2011 typed for a hyphen. Each answer is the read the
 * model would otherwise have spent a turn on, cut to the lines that matter.
 *
 * <h2>Found here, worded on the server</h2>
 *
 * <p>This used to be {@code EditView}, and rendered the answer too, so that
 * every side answered in the same words — and three sides then had to be kept
 * saying them (spec 2026-09-30, the file side reports facts; the server words
 * them). <b>What stays on the file side is only what needs the file</b>: the
 * line the new text is on, the window nearest a miss, whether it differs only
 * in whitespace, which look-alike characters stand where. The server's renderer
 * holds every word, the character names among them. The TUI's port of this
 * class, {@code binding/editfacts.ts}, is held to the same case table,
 * {@code edit-results.json}.
 *
 * <h2>Lines as a read numbers them</h2>
 *
 * <p>Split as {@link String#lines()} splits them, which is how both sides read
 * a file, so a line's number here is the {@code offset} a later read reaches it
 * at.
 *
 * <h2>Nothing here changes what an edit does</h2>
 *
 * <p>A mismatch is still refused. Whitespace, a look-alike character and the
 * closest lines are reported, never applied: a fuzzy match applied
 * automatically would change text the caller did not name.
 */
final class EditFacts {

    private EditFacts() {
    }

    // --- after a success ------------------------------------------------------------

    /**
     * The edit, and the region {@code replacement} occupies in {@code edited},
     * which it entered at {@code at}, with {@link Replacement#CONTEXT_LINES}
     * lines either side.
     */
    static Replacement.Edited success(String edited, int at, String replacement) {
        List<String> lines = edited.lines().toList();
        if (lines.isEmpty()) {
            return new Replacement.Edited(edited, 0, 0, replacement.isEmpty(),
                    Excerpt.of(lines, 0, -1));
        }
        int last = lines.size() - 1;
        int first = Math.min(lineOf(edited, at), last);
        int end = replacement.isEmpty()
                ? first
                : Math.min(lineOf(edited, at + replacement.length() - 1), last);
        int context = Replacement.CONTEXT_LINES;
        return new Replacement.Edited(edited, first, end, replacement.isEmpty(),
                Excerpt.of(lines, first - context, end + context));
    }

    // --- after a mismatch -----------------------------------------------------------

    /**
     * What the file side can tell about an {@code old} that is not in {@code
     * text}: a look-alike match, a whitespace-only match, or the closest lines
     * and the characters of {@code old} the file never has.
     */
    static FileResult absent(String path, String text, String old) {
        if (text.isEmpty()) {
            return FileResult.noMatch(path, FileResult.EMPTY_FILE, null, null, null, null);
        }
        List<String> lines = text.lines().toList();

        FileResult lookalikes = lookalikes(path, text, old, lines);
        if (lookalikes != null) {
            return lookalikes;
        }
        FileResult spaced = whitespace(path, text, old, lines);
        if (spaced != null) {
            return spaced;
        }
        List<Integer> foreign = foreign(text, old);
        int count = foreign.size();
        List<Integer> named = count > Replacement.MAX_LISTED
                ? foreign.subList(0, Replacement.MAX_LISTED)
                : foreign;
        Excerpt closest = closest(lines, old);
        return FileResult.noMatch(path, closest == null ? null : FileResult.CLOSEST, closest,
                null, named, count);
    }

    /**
     * {@code old} matches once look-alike characters are folded to the ASCII
     * they stand for, on either side: each difference, and where.
     */
    private static FileResult lookalikes(String path, String text, String old,
            List<String> lines) {
        String foldedOld = fold(old);
        String foldedText = fold(text);
        if (foldedOld.equals(old) && foldedText.equals(text)) {
            return null;
        }
        // One char to one char, so a position in the folded text is the same
        // position in the file.
        int at = foldedText.indexOf(foldedOld);
        if (at < 0) {
            return null;
        }
        Set<FileResult.Difference> differences = new LinkedHashSet<>();
        for (int i = 0; i < old.length(); i++) {
            char sent = old.charAt(i);
            char there = text.charAt(at + i);
            if (sent != there) {
                differences.add(new FileResult.Difference(sent, there));
            }
        }
        return FileResult.noMatch(path, FileResult.LOOKALIKE,
                Excerpt.of(lines, lineOf(text, at), lineOf(text, at + old.length() - 1)),
                new ArrayList<>(differences), null, null);
    }

    /** {@code old} matches once every whitespace character is ignored on both sides. */
    private static FileResult whitespace(String path, String text, String old,
            List<String> lines) {
        String squashedOld = squash(old);
        if (squashedOld.isEmpty()) {
            return null;
        }
        int at = squash(text).indexOf(squashedOld);
        if (at < 0) {
            return null;
        }
        int start = -1;
        int end = -1;
        int seen = 0;
        for (int i = 0; i < text.length() && end < 0; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                continue;
            }
            if (seen == at) {
                start = i;
            }
            if (seen == at + squashedOld.length() - 1) {
                end = i;
            }
            seen++;
        }
        return FileResult.noMatch(path, FileResult.WHITESPACE,
                Excerpt.of(lines, lineOf(text, start), lineOf(text, end)), null, null, null);
    }

    /** The non-ASCII code points of {@code old} that are nowhere in the file, in order. */
    private static List<Integer> foreign(String text, String old) {
        Set<Integer> seen = new LinkedHashSet<>();
        old.codePoints().filter(cp -> cp > 0x7F).forEach(seen::add);
        List<Integer> named = new ArrayList<>();
        for (int cp : seen) {
            if (text.indexOf(new String(Character.toChars(cp))) < 0) {
                named.add(cp);
            }
        }
        return named;
    }

    /**
     * The window of {@code old}'s line count holding the most lines equal to
     * {@code old}'s once both are trimmed, the first of equals; {@code null}
     * when no line matches. Blank lines are in every file and count for nothing.
     */
    private static Excerpt closest(List<String> lines, String old) {
        List<String> wanted = old.lines().toList();
        int span = Math.max(1, wanted.size());
        int starts = Math.max(0, lines.size() - span) + 1;
        Map<String, List<Integer>> where = new HashMap<>();
        for (int j = 0; j < wanted.size(); j++) {
            String line = wanted.get(j).strip();
            if (!line.isEmpty()) {
                where.computeIfAbsent(line, key -> new ArrayList<>()).add(j);
            }
        }
        if (where.isEmpty()) {
            return null;
        }
        int[] score = new int[starts];
        for (int at = 0; at < lines.size(); at++) {
            List<Integer> in = where.get(lines.get(at).strip());
            if (in == null) {
                continue;
            }
            for (int j : in) {
                int start = at - j;
                if (start >= 0 && start < starts) {
                    score[start]++;
                }
            }
        }
        int best = 0;
        for (int start = 1; start < starts; start++) {
            if (score[start] > score[best]) {
                best = start;
            }
        }
        if (score[best] == 0) {
            return null;
        }
        return Excerpt.of(lines, best, best + span - 1);
    }

    /**
     * The line {@code at} is on, counting terminators as {@link String#lines()}
     * does: {@code \n}, {@code \r\n} and a lone {@code \r} each end one line.
     */
    static int lineOf(String text, int at) {
        int line = 0;
        for (int i = 0; i < at; i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                line++;
            }
        }
        return line;
    }

    // --- characters -----------------------------------------------------------------

    /**
     * Characters a model types for the ASCII one a file has, each to that one
     * character — so folding never moves a position.
     */
    private static final Map<Character, Character> FOLDS = folds();

    private static Map<Character, Character> folds() {
        Map<Character, Character> folds = new HashMap<>();
        for (char c : "‐‑‒–—―−﹣－".toCharArray()) {
            folds.put(c, '-');
        }
        for (char c : "            　"
                .toCharArray()) {
            folds.put(c, ' ');
        }
        for (char c : "‘’‚‛′＇".toCharArray()) {
            folds.put(c, '\'');
        }
        for (char c : "“”„‟″＂".toCharArray()) {
            folds.put(c, '"');
        }
        return Map.copyOf(folds);
    }

    /** The ASCII character {@code codePoint} is a look-alike of, or -1. */
    static int lookalikeOf(int codePoint) {
        if (codePoint > 0xFFFF) {
            return -1;
        }
        Character ascii = FOLDS.get((char) codePoint);
        return ascii == null ? -1 : ascii;
    }

    private static String fold(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            Character ascii = FOLDS.get(text.charAt(i));
            if (ascii != null) {
                if (out == null) {
                    out = new StringBuilder(text);
                }
                out.setCharAt(i, ascii);
            }
        }
        return out == null ? text : out.toString();
    }

    private static String squash(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
