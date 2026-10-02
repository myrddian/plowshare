package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Excerpt;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.protocol.Replacement;
import io.aeyer.plowshare.protocol.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Every sentence a model reads about a change, from the facts a file side
 * reports — the one renderer, tested from facts to words for every kind.
 *
 * <p>The edit cases are {@code EditViewTest}'s, which held the words when the
 * file side wrote them (commit 49e51421): the same inputs, now turned into facts
 * by {@link Replacement} and into words here, must say exactly what they said.
 * Measured on one eleven-hour run: 27 of 306 edits were refused for an {@code
 * old} that was not in the file, and every one cost a whole read to recover
 * from; these hold the two answers that replace that read.
 */
class FileWordsTest {

    private static final String FILE = "/repo/A.java";

    /** {@code l0\n} through {@code l<n-1>\n}. */
    private static String numbered(int n) {
        return IntStream.range(0, n).mapToObj(i -> "l" + i + "\n").collect(Collectors.joining());
    }

    private static String view(String text, String old, String replacement) {
        return FileWords.edited(Replacement.edit(text, old, replacement).result(FILE));
    }

    private static String refusal(String text, String old) {
        Replacement.Refused refused = assertThrows(Replacement.Refused.class,
                () -> Replacement.apply(text, old, "x"));
        return FileWords.refusal(refused.result(FILE), false);
    }

    private static List<String> shownLines(String view) {
        List<String> out = new ArrayList<>();
        for (String line : view.lines().toList()) {
            if (!line.isEmpty() && !line.startsWith("[") && !line.startsWith("The ")) {
                out.add(line);
            }
        }
        return out;
    }

    private static final String BASE = "the text to replace is not in /repo/A.java; it must match"
            + " the file exactly, spaces and line breaks included — read the file again and copy it";

    // --- after a success -----------------------------------------------------------

    @Test
    void a_success_shows_the_new_text_with_three_lines_either_side_numbered_as_a_read_is() {
        assertEquals("The new text is on lines 10 to 11 now, shown with the 3 lines either side:\n"
                + "[Lines 7 to 14 of 21, counting from 0 as offset does.]\n"
                + "l7\nl8\nl9\nA\nB\nl11\nl12\nl13", view(numbered(20), "l10\n", "A\nB\n"));
    }

    @Test
    void one_line_of_new_text_is_named_as_one_line() {
        assertEquals("The new text is on line 10 now, shown with the 3 lines either side:\n"
                + "[Lines 7 to 13 of 20, counting from 0 as offset does.]\n"
                + "l7\nl8\nl9\nten\nl11\nl12\nl13", view(numbered(20), "l10", "ten"));
    }

    @Test
    void the_context_stops_at_either_end_of_the_file() {
        assertTrue(view(numbered(20), "l0\n", "zero\n").contains("[Lines 0 to 3 of 20,"));
        assertTrue(view(numbered(20), "l19\n", "nineteen\n").contains("[Lines 16 to 19 of 20,"));
    }

    @Test
    void long_new_text_shows_its_first_and_last_lines_and_says_how_many_are_left_out() {
        String added = IntStream.range(0, 100).mapToObj(i -> "n" + i + "\n")
                .collect(Collectors.joining());

        String view = view(numbered(20), "l10\n", added);

        assertTrue(view.startsWith("The new text is on lines 10 to 109 now, shown with the 3 lines"
                + " either side:\n[Lines 7 to 112 of 119, counting from 0 as offset does; the"
                + " middle is left out where marked.]\n"), view);
        assertTrue(view.contains("\n[… 66 lines not shown: 27 to 92 …]\n"), view);
        assertEquals(Replacement.MAX_SHOWN_LINES, shownLines(view).size(), view);
        assertTrue(view.contains("\nl9\nn0\n"), view);
        assertTrue(view.endsWith("\nn99\nl11\nl12\nl13"), view);
    }

    @Test
    void a_region_that_is_the_whole_file_says_so() {
        assertEquals("The new text is on line 1 now, shown with the 3 lines either side:\n"
                + "[The whole file, lines 0 to 2 of 3, counting from 0 as offset does.]\n"
                + "a\nB\nc", view("a\nb\nc\n", "b", "B"));
    }

    @Test
    void removed_text_shows_where_it_was() {
        assertEquals("The text was removed; this is where it was, with the 3 lines either side:\n"
                + "[The whole file, line 0 of 1, counting from 0 as offset does.]\n"
                + "keep keep", view("keep drop keep", " drop", ""));
        assertEquals("The text was removed; this is where it was, with the 3 lines either side:\n"
                + "[Lines 7 to 13 of 19, counting from 0 as offset does.]\n"
                + "l7\nl8\nl9\nl11\nl12\nl13\nl14", view(numbered(20), "l10\n", ""));
    }

    @Test
    void removing_the_last_line_or_everything_stays_inside_the_file() {
        assertTrue(view("a\nb\n", "b\n", "").contains("[The whole file, line 0 of 1,"));
        assertEquals("[The whole file is empty now.]", view("a\nb\n", "a\nb\n", ""));
    }

    @Test
    void line_endings_are_counted_as_a_read_counts_them() {
        assertTrue(view("a\r\nb\r\nc", "b", "B").endsWith("\na\nB\nc"));
        assertTrue(view(numbered(10).replace('\n', '\r'), "l5", "five")
                .startsWith("The new text is on line 5 now"));
    }

    @Test
    void a_very_long_line_is_cut_and_the_cut_is_marked() {
        String wide = "x".repeat(5_000);

        String view = view("a\n" + wide + "\nc\n", "a", "A");

        assertTrue(view.contains("x".repeat(Replacement.MAX_SHOWN_LINE_CHARS) + " [cut]\n"), view);
        assertFalse(view.contains("x".repeat(Replacement.MAX_SHOWN_LINE_CHARS + 1)), view);
        assertTrue(view.contains("a line ending [cut] is longer than "
                + Replacement.MAX_SHOWN_LINE_CHARS + " characters"), view);
        assertFalse(view.contains(FileWords.WHOLE), "a cut line is not the whole file");
    }

    @Test
    void a_view_is_bounded_whatever_the_file() {
        String wide = ("y".repeat(10_000) + "\n").repeat(500);

        String view = view(wide + "mark\n" + wide, "mark", wide);

        assertTrue(shownLines(view).size() <= Replacement.MAX_SHOWN_LINES);
        assertTrue(view.length() < 30_000, "length " + view.length());
    }

    @Test
    void facts_with_no_lines_show_nothing_rather_than_something_invented() {
        assertEquals("", FileWords.edited(new FileResult(1, FileResult.EDITED, FileRequest.EDIT,
                FILE, null, null, null, null, null, null, null, null, null, 0, 0, false, null,
                null, null, null, null, null, null, null)));
    }

    // --- after a mismatch ------------------------------------------------------------

    @Test
    void a_whitespace_only_difference_is_named_and_the_file_s_own_text_is_shown() {
        String file = "class A {\n    int x = 1;\n    int y = 2;\n}\n";

        assertEquals(BASE + "\n\n`old` differs from the file only in whitespace/indentation. The"
                + " file's text there:\n[Lines 1 to 2 of 4, counting from 0 as offset does.]\n"
                + "    int x = 1;\n    int y = 2;", refusal(file, "  int x = 1;\n  int y = 2;"));
    }

    @Test
    void a_look_alike_character_is_named_beside_the_one_the_file_has() {
        assertEquals(BASE + "\n\n`old` has U+2011 NON-BREAKING HYPHEN where the file has '-'."
                + " The file's text there:\n[Line 1 of 3, counting from 0 as offset does.]\n"
                + "second-hand", refusal("first\nsecond-hand\nthird\n", "second‑hand"));
    }

    @Test
    void every_listed_look_alike_is_named_in_either_direction() {
        for (char c : new char[] {'‐', '‑', '–', '—'}) {
            assertTrue(refusal("a-b\n", "a" + c + "b").contains("where the file has '-'"), "" + c);
        }
        assertTrue(refusal("a b\n", "a b").contains("U+00A0 NO-BREAK SPACE where the file has"
                + " ' '"));
        assertTrue(refusal("it's\n", "it’s").contains(
                "U+2019 RIGHT SINGLE QUOTATION MARK where the file has '''"));
        assertTrue(refusal("'q'\n", "‘q'").contains("U+2018 LEFT SINGLE QUOTATION MARK"));
        assertTrue(refusal("\"q\"\n", "“q”").contains(
                "U+201C LEFT DOUBLE QUOTATION MARK where the file has '\"', and U+201D RIGHT"
                        + " DOUBLE QUOTATION MARK where the file has '\"'"));
        assertTrue(refusal("a—b\n", "a-b").contains(
                "`old` has '-' where the file has U+2014 EM DASH"));
    }

    @Test
    void a_character_the_file_does_not_have_is_named_even_without_a_match() {
        assertEquals(BASE + "\n\n`old` has U+00E9 LATIN SMALL LETTER E WITH ACUTE, which is"
                + " nowhere in the file.", refusal("cafe\n", "café latte"));
    }

    @Test
    void any_character_is_named_by_the_server_whatever_machine_found_it() {
        // The terminal client kept a table of names and said U+2192 alone for
        // any character outside it; the names are this server's now.
        assertTrue(refusal("a\n", "→").contains("U+2192 RIGHTWARDS ARROW, which is nowhere"),
                refusal("a\n", "→"));
        assertTrue(refusal("a\n", "😀").contains("U+1F600 GRINNING FACE"));
    }

    @Test
    void a_look_alike_the_file_lacks_says_what_it_looks_like() {
        assertTrue(refusal("abc\n", "a‑z").contains(
                "U+2011 NON-BREAKING HYPHEN, which looks like '-', which is nowhere in the file."),
                refusal("abc\n", "a‑z"));
    }

    @Test
    void more_foreign_characters_than_are_listed_are_counted() {
        String out = refusal("x\n", "αβγδεζη");

        assertTrue(out.endsWith("U+03B5 GREEK SMALL LETTER EPSILON, and 2 more; each is nowhere in"
                + " the file."), out);
    }

    @Test
    void a_paraphrase_is_answered_with_the_closest_lines() {
        String file = "a();\nb();\nif (ready) {\n    go();\n}\nc();\n";

        assertEquals(BASE + "\n\nThe closest lines in the file now are:\n"
                + "[Lines 2 to 4 of 6, counting from 0 as offset does.]\n"
                + "if (ready) {\n    go();\n}", refusal(file, "if (ready) {\n    start();\n}"));
    }

    @Test
    void a_foreign_character_and_the_closest_lines_are_both_said() {
        assertEquals(BASE + "\n\n`old` has U+2192 RIGHTWARDS ARROW, which is nowhere in the file.\n"
                + "The closest lines in the file now are:\n"
                + "[The whole file, lines 0 to 2 of 3, counting from 0 as offset does.]\n"
                + "a\nif (ready) {\n}", refusal("a\nif (ready) {\n}\n", "if (ready) {\n  → go\n}"));
    }

    @Test
    void the_closest_lines_tie_to_the_first() {
        String out = refusal("x = 1;\ny = 2;\nx = 1;\ny = 2;\n", "x = 1;\nz = 3;");

        assertTrue(out.endsWith("[Lines 0 to 1 of 4, counting from 0 as offset does.]\n"
                + "x = 1;\ny = 2;"), out);
    }

    @Test
    void no_line_in_common_shows_nothing() {
        assertEquals(BASE, refusal("alpha\nbeta\n", "gamma\ndelta"));
        assertEquals(BASE, refusal("alpha\n\nbeta\n", "\ngamma"),
                "a blank line is in every file and is not a match");
    }

    @Test
    void an_empty_file_says_it_is_empty() {
        assertEquals(BASE + "\n\nThe file is empty.", refusal("", "anything"));
    }

    @Test
    void the_closest_lines_are_bounded() {
        StringBuilder file = new StringBuilder();
        StringBuilder old = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            file.append("line ").append(i).append('\n');
            old.append(i == 100 ? "changed" : "line " + i).append('\n');
        }

        String out = refusal(file.toString(), old.toString());

        assertTrue(out.contains("The closest lines in the file now are:\n[Lines 0 to 199 of 200,"
                + " counting from 0 as offset does; the middle is left out where marked.]"), out);
        assertTrue(shownLines(out.substring(BASE.length())).size()
                <= Replacement.MAX_SHOWN_LINES, out);
    }

    @Test
    void text_there_more_than_once_is_counted_and_nothing_is_shown() {
        String out = refusal("a\na\n", "a");

        assertEquals("the text to replace occurs 2 times in /repo/A.java, and an edit replaces"
                + " exactly one; include more of the surrounding text so it matches once", out);
    }

    @Test
    void empty_text_to_replace_is_refused_in_one_sentence() {
        assertEquals("an edit needs the text to replace, and it was empty; send the exact text, or"
                + " send the whole file as content", refusal("a", ""));
    }

    // --- every other refusal, from its facts -----------------------------------------

    @Test
    void a_file_that_is_not_there_says_nothing_was_done_and_an_edit_says_how_to_create_one() {
        assertEquals("there is no file at /repo/A.java on this machine, so nothing was edited;"
                + " to create it, send {\"path\", \"content\"} with the whole file",
                FileWords.refusal(FileResult.noFile(FileRequest.EDIT, FILE), true));
        assertEquals("there is no file at /repo/A.java, so nothing was deleted",
                FileWords.refusal(FileResult.noFile(FileRequest.DELETE, FILE), false));
        assertEquals("there is no file at /repo/A.java on this machine, so nothing was moved",
                FileWords.refusal(FileResult.noFile(FileRequest.MOVE, FILE), true));
    }

    @Test
    void every_reason_is_one_sentence_whichever_machine_holds_the_file() {
        String op = FileRequest.EDIT;
        assertEquals("path /repo/A.java is not UTF-8 text on this machine, so it cannot be"
                + " edited; send the whole file as content", FileWords.refusal(
                FileResult.notText(op, FILE, FileResult.NOT_UTF8), true));
        assertEquals("path /repo/A.java is a directory on this machine, so it cannot be written"
                + " as a file", FileWords.refusal(
                FileResult.refused(FileRequest.WRITE, FileResult.DIRECTORY, FILE), true));
        assertEquals("path /repo/A.java is a link, so it cannot be deleted as a file; name the"
                + " file itself", FileWords.refusal(
                FileResult.refused(FileRequest.DELETE, FileResult.LINK, FILE), false));
        assertEquals("path /repo/A.java is not a regular file, so it cannot be moved",
                FileWords.refusal(FileResult.refused(FileRequest.MOVE, FileResult.NOT_REGULAR,
                        FILE), false));
        assertEquals("path /repo/A.java is 9000000 bytes on this machine, and an edit loads no"
                + " more than 8388608 at once; send the whole file as content", FileWords.refusal(
                FileResult.tooLarge(op, FILE, 9_000_000, 8_388_608), true));
        assertEquals("path /repo/A.java already exists on this machine, and this write may only"
                + " create a file; read it before replacing it", FileWords.refusal(
                FileResult.refused(FileRequest.WRITE, FileResult.EXISTS, FILE), true));
        assertEquals("path /repo/B.java already exists on this machine, and a move never replaces"
                + " a file; delete it first or choose another destination", FileWords.refusal(
                FileResult.destinationExists(FILE, "/repo/B.java"), true));
        assertEquals("path /repo/A.java could not be written on this machine: disk full",
                FileWords.refusal(FileResult.failed(FileRequest.WRITE, FileResult.FAILED, FILE,
                        null, "disk full"), true));
        assertEquals("path /repo/A.java could not be moved to /repo/B.java: busy",
                FileWords.refusal(FileResult.failed(FileRequest.MOVE, FileResult.FAILED, FILE,
                        "/repo/B.java", "busy"), false));
    }

    @Test
    void the_fence_names_the_roots_to_ask_about_instead() {
        assertEquals("path /etc/passwd is outside this session's workspace, which is /repo, /lib;"
                + " ask for the roots you have rather than guessing at paths", FileWords.refusal(
                FileResult.fenced(FileRequest.WRITE, FileResult.OUTSIDE, "/etc/passwd",
                        List.of("/repo", "/lib")), true));
        assertEquals("path /old/A.java was inside this session's workspace, but the workspace"
                + " moved while this run was going and is now /repo; ask for your roots again"
                + " rather than the paths you had", FileWords.refusal(FileResult.fenced(
                FileRequest.EDIT, FileResult.WORKSPACE_MOVED, "/old/A.java", List.of("/repo")),
                true));
        assertEquals("no workspace is set for this session, so nothing on this machine is"
                + " reachable; whoever is using this client sets one", FileWords.refusal(
                FileResult.refused(FileRequest.DELETE, FileResult.NO_WORKSPACE, null), true));
        assertEquals("this request named no path", FileWords.refusal(
                FileResult.refused(FileRequest.DELETE, FileResult.NO_PATH, null), true));
        assertEquals("'a\u0000b' is not a path this machine can even name: Nul character",
                FileWords.refusal(FileResult.failed(FileRequest.EDIT, FileResult.UNNAMEABLE,
                        "a\u0000b", null, "Nul character"), true));
    }

    @Test
    void a_missing_argument_is_named_in_one_wording_for_every_client() {
        // The terminal client said "to replace it with" and the Java client "to
        // put in its place"; there is one wording now, and it is this.
        assertEquals("an edit needs the text to put in its place; nothing was sent",
                FileWords.refusal(FileResult.missing(FileRequest.EDIT, "content"), true));
        assertEquals("an edit needs the text to replace; nothing was sent",
                FileWords.refusal(FileResult.missing(FileRequest.EDIT, "replacing"), true));
        assertEquals("a write needs the content to write; nothing was sent",
                FileWords.refusal(FileResult.missing(FileRequest.WRITE, "content"), true));
        assertEquals("a move needs the path to move the file to; nothing was sent",
                FileWords.refusal(FileResult.missing(FileRequest.MOVE, "to"), true));
    }

    @Test
    void a_kind_or_reason_from_a_later_file_side_is_said_as_unknown_and_never_blank() {
        FileResult later = new FileResult(2, "quarantined", FileRequest.DELETE, FILE, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        FileResult laterReason = FileResult.refused(FileRequest.WRITE, "locked", FILE);

        assertEquals("the delete of /repo/A.java was not made on this machine, for a reason this"
                + " server does not know (quarantined)", FileWords.refusal(later, true));
        assertEquals("the write of /repo/A.java was not made, for a reason this server does not"
                + " know (refused, locked)", FileWords.refusal(laterReason, false));
    }

    @Test
    void the_whole_file_header_is_said_only_of_every_line_uncut() {
        Excerpt all = Excerpt.of(List.of("a", "b"), 0, 1);
        Excerpt part = Excerpt.of(List.of("a", "b", "c"), 0, 1);

        assertTrue(FileWords.block(all).startsWith(FileWords.WHOLE + ", lines 0 to 1 of 2"));
        assertTrue(FileWords.block(part).startsWith("[Lines 0 to 1 of 3"));
    }

    // --- the other ops (spec 2026-09-30, step 2) --------------------------------------
    //
    // Each expected sentence is the one the side that held the file wrote before
    // it reported facts — the Java client's (on this machine) or LocalProvider's
    // (this server's own) — quoted byte for byte. Where the terminal client said
    // it another way, the Java client's words are the one wording now.

    private static final String READ = FileRequest.READ;

    @Test
    void a_read_that_finds_nothing_or_no_text_says_what_it_said_on_either_side() {
        assertEquals("there is no file at /repo/A.java on this machine",
                FileWords.refusal(FileResult.noFile(READ, FILE), true));
        assertEquals("there is no file at /repo/A.java",
                FileWords.refusal(FileResult.noFile(FileRequest.STAT, FILE), false));
        assertEquals("path /repo/A.java is not UTF-8 text; these tools read text files only",
                FileWords.refusal(FileResult.notText(READ, FILE, FileResult.NOT_UTF8), true));
        assertEquals("path /repo/A.java is not UTF-8 text; these tools read text files only",
                FileWords.refusal(FileResult.notText(FileRequest.GREP, FILE,
                        FileResult.NOT_UTF8), false));
        assertEquals("path /repo/A.java is not a regular file on this machine, so there is"
                + " nothing to read", FileWords.refusal(
                FileResult.refused(READ, FileResult.NOT_REGULAR, FILE), true));
        // One wording where two sides differed: every side said "not a regular
        // file" for a directory a read met, and a change of one "is a directory".
        // The facts say which it is, and so do the words.
        assertEquals("path /repo/src is a directory on this machine, so there is nothing to read",
                FileWords.refusal(FileResult.refused(READ, FileResult.DIRECTORY, "/repo/src"),
                        true));
    }

    @Test
    void a_read_too_large_says_which_side_will_not_load_it() {
        assertEquals("path /repo/A.java is 9000000 bytes, and this client will not read more"
                + " than 8388608 at once; name a smaller file", FileWords.refusal(
                FileResult.tooLarge(READ, FILE, 9_000_000, 8_388_608), true));
        assertEquals("path /repo/A.java is 9000000 bytes, and this server will not read more"
                + " than 8388608 at once; name a smaller file", FileWords.refusal(
                FileResult.tooLarge(READ, FILE, 9_000_000, 8_388_608), false));
    }

    @Test
    void a_read_the_system_refused_quotes_the_system() {
        assertEquals("path /repo/A.java could not be read on this machine: Is a directory",
                FileWords.refusal(FileResult.failed(READ, FileResult.FAILED, FILE, null,
                        "Is a directory"), true));
        assertEquals("path /repo/A.java could not be read: EIO", FileWords.refusal(
                FileResult.failed(READ, FileResult.FAILED, FILE, null, "EIO"), false));
        assertEquals("path /repo/A.java could not be opened: /repo/A.java", FileWords.refusal(
                FileResult.failed(READ, FileResult.DENIED, FILE, null, "/repo/A.java"), false));
    }

    @Test
    void a_line_too_wide_for_any_read_names_the_line_and_what_still_works() {
        assertEquals("line 0 of this file is 196608 bytes, and one read carries at most 98304"
                + " bytes however few lines are asked for, so no offset or limit returns this"
                + " line and this file cannot be read. That is a bound on what one answer carries"
                + " and not on the file. file_grep searches this file without returning it: it"
                + " gives the offset of every line that contains the text you name, and the first"
                + " 200 characters of that line.", FileWords.refusal(FileResult.lineTooWide(READ,
                FILE, 0, 196_608, 98_304), true));
        // And exactly what the shared Window says of the same line, so the one
        // renderer and the protocol's own account cannot drift apart.
        Window.LineTooWide wide = assertThrows(Window.LineTooWide.class,
                () -> Window.of(0, 10).cut(List.of("x".repeat(Window.MAX_WINDOW_BYTES + 1))));
        assertEquals(wide.getMessage(), FileWords.refusal(FileResult.lineTooWide(READ, FILE,
                wide.offset(), wide.bytes(), Window.MAX_WINDOW_BYTES), false));
    }

    @Test
    void a_frame_that_cannot_be_served_names_the_field() {
        assertEquals("this request asks for a window this client cannot serve: a window's offset"
                + " is a line number and cannot be negative; got -1", FileWords.refusal(
                FileResult.unservable(READ, "offset", -1, null), true));
        assertEquals("this request asks for a window this client cannot serve: a window must"
                + " carry at least one line; got a limit of 0", FileWords.refusal(
                FileResult.unservable(READ, "limit", 0, null), true));
        assertEquals("this request asks for a search this client cannot run: a search needs"
                + " something to look for, and '   ' would match every line there is",
                FileWords.refusal(FileResult.unservable(FileRequest.GREP, "needle", null, "   "),
                        true));
    }

    @Test
    void a_glob_that_cannot_run_says_what_to_write_instead() {
        assertEquals("a glob pattern is required", FileWords.refusal(
                FileResult.pattern(FileResult.NO_PATTERN, null, null), true));
        assertEquals("'/repo/**/*.java' is an absolute pattern, and a pattern is matched against"
                + " paths relative to a root; write 'repo/**/*.java' instead", FileWords.refusal(
                FileResult.pattern(FileResult.ABSOLUTE_PATTERN, "/repo/**/*.java", null), false));
        assertEquals("'**/[x' is not a usable glob: Unclosed character class", FileWords.refusal(
                FileResult.pattern(FileResult.BAD_PATTERN, "**/[x", "Unclosed character class"),
                true));
        assertEquals("'**/**/**/**/**/x' has 5 '**/' segments and at most 4 are expanded; one is"
                + " almost always what is meant", FileWords.refusal(
                FileResult.tooManyWildcards("**/**/**/**/**/x", 5, 4), true));
        assertEquals("more than 10000 files match on this machine; narrow the pattern rather than"
                + " being handed a prefix of the answer", FileWords.refusal(
                FileResult.tooManyMatches(FileRequest.GLOB, 10_000), true));
        assertEquals("more than 10000 files match; narrow the pattern rather than being handed a"
                + " prefix of the answer", FileWords.refusal(
                FileResult.tooManyMatches(FileRequest.GLOB, 10_000), false));
        assertEquals("the tree under /repo could not be listed in full: Permission denied",
                FileWords.refusal(FileResult.failed(FileRequest.GLOB, FileResult.UNLISTABLE,
                        "/repo", null, "Permission denied"), true));
        assertEquals("the tree under /repo could not be searched in full: Permission denied",
                FileWords.refusal(FileResult.failed(FileRequest.GREP, FileResult.UNLISTABLE,
                        "/repo", null, "Permission denied"), false));
    }

    @Test
    void the_fence_says_the_same_about_a_read_as_about_a_change_and_a_hidden_path_is_hidden() {
        assertEquals("path /etc/passwd is outside this session's workspace, which is /repo;"
                + " ask for the roots you have rather than guessing at paths", FileWords.refusal(
                FileResult.fenced(READ, FileResult.OUTSIDE, "/etc/passwd", List.of("/repo")),
                true));
        assertEquals("no workspace is set for this session, so nothing on this machine is"
                + " reachable; whoever is using this client sets one", FileWords.refusal(
                FileResult.refused(FileRequest.GLOB, FileResult.NO_WORKSPACE, null), true));
        // Measured before this: a hidden path under the root was told it was
        // "outside this session's workspace, which is /repo" — a model that can
        // see /repo/.env is under /repo is told something it can see is false.
        assertEquals("path /repo/.env is inside this session's workspace, which is /repo, but"
                + " hidden: a name in it starts with '.', and no file tool reaches a hidden path",
                FileWords.refusal(FileResult.fenced(READ, FileResult.HIDDEN, "/repo/.env",
                        List.of("/repo")), true));
        assertEquals("path /other is outside this session's workspace, which is /repo; ask for"
                + " the roots you have rather than guessing at paths", FileWords.refusal(
                FileResult.fenced(FileRequest.RUN, FileResult.OUTSIDE, "/other",
                        List.of("/repo")), true), "a run's working directory is fenced alike");
    }

    @Test
    void a_document_the_client_converts_says_it_was_the_document() {
        assertEquals("path /repo/a.pdf is a PDF, which this client reads, and this one could not"
                + " be converted: it is encrypted, and this client has no password for it",
                FileWords.refusal(FileResult.unreadable(READ, FileResult.REFUSED,
                        FileResult.ENCRYPTED, "/repo/a.pdf", "PDF"), true));
        assertEquals("path /repo/a.pdf is a PDF, which this client reads, and this one could not"
                + " be converted: it could not be parsed; the file is damaged, or it is a variant"
                + " of the format this client does not read", FileWords.refusal(
                FileResult.unreadable(READ, FileResult.REFUSED, FileResult.DAMAGED, "/repo/a.pdf",
                        "PDF"), true));
        // The terminal client's: it converts none of these yet.
        assertEquals("path /repo/a.pdf is a PDF, and this client reads text files only; the MCP"
                + " client converts these and the terminal client does not yet",
                FileWords.refusal(FileResult.unreadable(READ, FileResult.NOT_TEXT,
                        FileResult.UNCONVERTED, "/repo/a.pdf", "PDF"), true));
        assertEquals("path /repo/a.png is a png image, and this client reads text files only; the"
                + " MCP client converts these and the terminal client does not yet",
                FileWords.refusal(FileResult.unreadable(READ, FileResult.NOT_TEXT,
                        FileResult.UNCONVERTED, "/repo/a.png", "png"), true));
    }

    @Test
    void a_picture_is_named_in_one_line_that_says_whether_it_was_copied() {
        FileResult facts = FileResult.named(READ, "/repo/logo.png", "png", "img_1");

        assertEquals("The file /repo/logo.png is a png image, so it is named rather than read: no"
                + " tool here answers with a picture. It was uploaded to the server and its id is"
                + " img_1. Hand that id to an agent that can see, as agent_run's 'images', and it"
                + " will be shown the picture -- you will not. The bytes were copied to get there,"
                + " which is what a workspace on another machine costs: editing this file now does"
                + " not change what that id resolves to.", FileWords.named(facts, true));
        assertEquals("The file /repo/logo.png is a png image, so it is named rather than read: no"
                + " tool in this server answers with a picture. Its id is img_1. Hand that id to"
                + " an agent that can see, as agent_run's 'images', and it will be shown the"
                + " picture -- you will not. Nothing was copied: the id points at this file, and"
                + " it stops resolving if the file goes or if this project stops reaching it.",
                FileWords.named(facts, false));
    }

    @Test
    void the_three_image_refusals_stay_three_remedies() {
        String head = "path /repo/logo.png is a png image, and ";
        assertEquals(head + "the server will not take that format: no. Both halves read one list"
                + " -- " + ImageFormat.accepted() + " -- so this means this client and that server"
                + " are different builds; convert the file to something they both take, or have"
                + " the two matched.", FileWords.refusal(FileResult.imageRefused(READ,
                "/repo/logo.png", "png", 415, "no", 10), true));
        assertEquals(head + "at 900000 bytes it is over what the server will hold: an image may be"
                + " at most 262144 bytes. Shrink or crop it and read it again; nothing about this"
                + " path or this workspace is wrong.", FileWords.refusal(FileResult.imageRefused(
                READ, "/repo/logo.png", "png", 413, "an image may be at most 262144 bytes",
                900_000), true));
        assertEquals(head + "the server has nowhere to put it: no data directory. That is a"
                + " deployment that cannot hold any image, so no other file and no other path gets"
                + " a different answer; whoever runs the server fixes it.", FileWords.refusal(
                FileResult.imageRefused(READ, "/repo/logo.png", "png", 400, "no data directory",
                        10), true));
        assertEquals(head + "the server refused to name it: teapot", FileWords.refusal(
                FileResult.imageRefused(READ, "/repo/logo.png", "png", 418, "teapot", 10), true));
        assertEquals(head + "it could not be uploaded because the server was not reached:"
                + " Connection refused", FileWords.refusal(FileResult.imageUnreached(READ,
                "/repo/logo.png", "png", "Connection refused"), true));
    }

    @Test
    void an_outage_and_an_unknown_op_are_worded_from_their_facts() {
        assertEquals("the workspace /repo this session was set to is no longer there",
                FileWords.unavailable(FileResult.unavailable(READ, FileResult.ROOT_GONE, "/repo",
                        null)));
        assertEquals("the workspace /repo this session was set to is no longer a directory",
                FileWords.unavailable(FileResult.unavailable(FileRequest.ROOTS,
                        FileResult.ROOT_NOT_DIRECTORY, "/repo", null)));
        assertEquals("this client failed while answering: java.lang.IllegalStateException: x",
                FileWords.unavailable(FileResult.unavailable(READ, FileResult.INTERNAL, null,
                        "java.lang.IllegalStateException: x")));
        assertEquals("this client does not know how to 'chmod'; it was built before the server"
                + " that asked, and the two builds need matching", FileWords.refusal(
                FileResult.refused("chmod", FileResult.UNKNOWN_OP, null), true));
        assertEquals("the read of /repo/A.java was refused on this machine, for a reason this"
                + " server does not know (refused, sealed)", FileWords.refusal(
                FileResult.refused(READ, "sealed", FILE), true));
    }

    @Test
    void a_reply_says_what_its_facts_say_or_else_its_own_words() {
        assertEquals("there is no file at /repo/A.java on this machine", FileWords.said(
                FileReply.refused("r1", FileResult.noFile(READ, FILE))));
        assertEquals("the workspace /repo this session was set to is no longer there",
                FileWords.said(FileReply.unavailable("r1", FileResult.unavailable(READ,
                        FileResult.ROOT_GONE, "/repo", null))));
        assertEquals("an old client's words", FileWords.said(
                FileReply.refused("r1", "an old client's words")));
    }
}
