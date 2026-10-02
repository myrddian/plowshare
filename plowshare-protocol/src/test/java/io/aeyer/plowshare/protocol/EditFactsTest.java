package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * What an edit reports: where the new text is after a success, and what is
 * nearest an {@code old} that is not in the file — as facts.
 *
 * <p>{@code edit-results.json} is the table both file sides are held to: this
 * runs it against {@link Replacement}, and the TUI's {@code editfacts.test.ts}
 * runs the same file against its port, so a fact that one side reports and the
 * other does not fails the day it is written. The words for these facts are the
 * server's, and {@code FileWordsTest} holds them.
 */
class EditFactsTest {

    private static final ObjectMapper JSON =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** {@code l0\n} through {@code l<n-1>\n}. */
    private static String numbered(int n) {
        return IntStream.range(0, n).mapToObj(i -> "l" + i + "\n").collect(Collectors.joining());
    }

    /** The lines a read of {@code text} numbers {@code from} to {@code to}, inclusive. */
    private static List<String> read(String text, int from, int to) {
        return text.lines().toList().subList(from, to + 1);
    }

    private static FileResult refusal(String text, String old) {
        Replacement.Refused refused = assertThrows(Replacement.Refused.class,
                () -> Replacement.apply(text, old, "x"));
        return refused.result("/repo/A.java");
    }

    @TestFactory
    List<DynamicTest> every_case_in_the_shared_table() throws Exception {
        JsonNode cases;
        try (InputStream in = EditFactsTest.class.getResourceAsStream("edit-results.json")) {
            cases = JSON.readTree(in);
        }
        assertTrue(cases.size() > 20, "the table has the cases it was written with");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                FileResult expected = JSON.treeToValue(c.get("result"), FileResult.class);
                FileResult actual;
                try {
                    actual = Replacement.edit(c.get("text").asText(), c.get("old").asText(),
                            c.get("new").asText()).result("/repo/A.java");
                } catch (Replacement.Refused refused) {
                    actual = refused.result("/repo/A.java");
                }
                assertEquals(expected, actual);
            }));
        }
        return tests;
    }

    // --- after a success -----------------------------------------------------------

    @Test
    void the_lines_reported_are_the_lines_a_read_of_the_edited_file_returns() {
        Replacement.Edited edited = Replacement.edit(numbered(20), "l10\n", "A\nB\n");

        assertEquals(10, edited.first());
        assertEquals(11, edited.last());
        assertEquals(read(edited.text(), 7, 14), edited.excerpt().lines(),
                "offset 7 of a read of the edited file is the first line reported");
        assertFalse(edited.excerpt().whole());
    }

    @Test
    void long_new_text_is_reported_by_its_ends_and_the_gap_between_them() {
        String added = IntStream.range(0, 100).mapToObj(i -> "n" + i + "\n")
                .collect(Collectors.joining());
        Replacement.Edited edited = Replacement.edit(numbered(20), "l10\n", added);

        Excerpt excerpt = edited.excerpt();
        assertEquals(Replacement.MAX_SHOWN_LINES, excerpt.lines().size());
        assertEquals(20, excerpt.gap());
        assertEquals(66, excerpt.omitted());
        assertEquals(read(edited.text(), 7, 26), excerpt.lines().subList(0, 20));
        assertEquals(read(edited.text(), 93, 112), excerpt.lines().subList(20, 40));
    }

    @Test
    void only_every_line_uncut_is_the_whole_file() {
        assertTrue(Replacement.edit("a\nb\nc\n", "b", "B").excerpt().whole());
        assertTrue(Replacement.edit("a\nb\n", "a\nb\n", "").excerpt().whole(),
                "an empty file is all of itself");
        String wide = "x".repeat(Replacement.MAX_SHOWN_LINE_CHARS + 1);
        Excerpt cut = Replacement.edit("a\n" + wide + "\n", "a", "A").excerpt();
        assertEquals(List.of(1), cut.clipped());
        assertFalse(cut.whole(), "a cut line is not the whole file");
    }

    @Test
    void what_crosses_the_wire_is_bounded_whatever_the_file() {
        String wide = ("y".repeat(10_000) + "\n").repeat(500);
        Excerpt excerpt = Replacement.edit(wide + "mark\n" + wide, "mark", wide).excerpt();

        assertTrue(excerpt.lines().size() <= Replacement.MAX_SHOWN_LINES);
        assertTrue(excerpt.lines().stream()
                .allMatch(line -> line.length() <= Replacement.MAX_SHOWN_LINE_CHARS));
    }

    @Test
    void apply_is_the_edited_text() {
        assertEquals(Replacement.edit("alpha beta", "beta", "gamma").text(),
                Replacement.apply("alpha beta", "beta", "gamma"));
    }

    // --- after a mismatch ------------------------------------------------------------

    @Test
    void every_listed_look_alike_is_recognised_in_either_direction() {
        for (char c : new char[] {'‐', '‑', '–', '—'}) {
            FileResult result = refusal("a-b\n", "a" + c + "b");
            assertEquals(FileResult.LOOKALIKE, result.near(), "" + c);
            assertEquals(List.of(new FileResult.Difference(c, '-')), result.differences());
        }
        assertEquals(List.of(new FileResult.Difference('’', '\'')),
                refusal("it's\n", "it’s").differences());
        assertEquals(List.of(new FileResult.Difference('-', '—')),
                refusal("a—b\n", "a-b").differences());
    }

    @Test
    void only_a_miss_with_no_match_names_the_characters_the_file_lacks() {
        FileResult lookalike = refusal("a-b\n", "a‑b");
        FileResult foreign = refusal("cafe\n", "café latte");

        assertNull(lookalike.foreign());
        assertEquals(List.of(0xE9), foreign.foreign());
        assertEquals(1, foreign.count());
        assertEquals(0x2D, Replacement.lookalikeOf(0x2011));
        assertEquals(-1, Replacement.lookalikeOf(0xE9));
        assertEquals(-1, Replacement.lookalikeOf(0x1F600));
    }
}
