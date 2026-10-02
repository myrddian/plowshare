package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The matching, and the two bounds on what it hands back.
 *
 * <h2>What is actually at stake here</h2>
 *
 * <p>Three of these tests are about facts of the platform rather than about
 * this record, and each of them names a way the two halves of the wire could
 * hand back different answers for the same search while both looked correct
 * from inside their own module:
 *
 * <ul>
 *   <li><b>the default locale.</b> {@code toLowerCase()} with no argument is the
 *       locale of the machine it runs on, and Turkish folds {@code I} to a
 *       dotless {@code ı}. A client on a Turkish desktop and a server in a
 *       container would disagree about whether {@code PUBLIC} contains {@code
 *       public};
 *   <li><b>the surrogate pair.</b> A line cut at a character count can land in
 *       the middle of one, and the half that survives does not encode: measured
 *       below, it becomes a {@code ?} on the way to UTF-8;
 *   <li><b>the cap being a property of the type.</b> A search that stopped and
 *       said {@link Found#END} is the confident empty answer with fifty lines in
 *       front of it.
 * </ul>
 *
 * <h2>The fixtures are built from the constants</h2>
 *
 * <p>{@link Needle#MAX_MATCHES} and {@link Needle#MAX_LINE_CHARS} are both
 * judgements that can move, so nothing here spells either number. A fixture
 * holding a literal would keep passing and stop measuring the boundary the day
 * one of them changed.
 */
class NeedleTest {

    @Test
    void a_literal_matches_the_lines_that_contain_it_and_no_others() {
        List<Found.Match> found = sift(new Needle("workspace", false),
                List.of("the workspace is here", "nothing", "workspaces plural"));

        assertEquals(List.of(0, 2), offsets(found));
    }

    @Test
    void a_pattern_written_as_a_regex_matches_only_itself() {
        // The tool description's claim, asserted rather than described: a model
        // that writes `.*` gets the two characters it typed. A regex engine here
        // would match the first line and every other line in the file, and the
        // description is what has to prevent the wasted turn — this test is
        // what says the engine is not there.
        //
        // NOT a test that regex is dangerous. The spec withdrew that argument
        // after measuring four catastrophic-backtracking examples at about a
        // millisecond each on this JVM; what is left is that a second backend is
        // a change nobody has asked for yet, and Needle.matches is where one
        // would go.
        List<Found.Match> found =
                sift(new Needle(".*", false), List.of("anything at all", "a .* in the text"));

        assertEquals(List.of(1), offsets(found));
    }

    @Test
    void case_folding_does_not_depend_on_the_machines_locale() {
        // MEASURED on JDK 21.0.8: "PUBLIC".toLowerCase(tr) is "publıc" with a
        // dotless i, so a case-insensitive search written with the no-argument
        // toLowerCase finds nothing on a Turkish machine and finds the line
        // everywhere else. Two answers, neither of which looks like a failure —
        // which is the whole reason this method is in this module.
        Locale wasSet = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertTrue(new Needle("public", true).matches("PUBLIC static void"),
                    "the fold is the platform's case rule and not this machine's language");
            assertTrue(new Needle("PUBLIC", true).matches("public static void"));
        } finally {
            Locale.setDefault(wasSet);
        }
    }

    @Test
    void a_case_sensitive_search_is_the_default_and_really_is_sensitive() {
        assertFalse(new Needle("public", false).matches("PUBLIC static void"));
        assertTrue(new Needle("public", false).matches("public static void"));
    }

    @Test
    void a_needle_with_nothing_in_it_is_refused_rather_than_matching_every_line() {
        // Every line contains the empty string, so an unchecked empty needle is
        // a search that returns the first fifty lines of the tree and calls them
        // matches. Whitespace alone is the same answer wearing a space.
        assertThrows(IllegalArgumentException.class, () -> new Needle("", false));
        assertThrows(IllegalArgumentException.class, () -> new Needle("   ", false));
        assertThrows(IllegalArgumentException.class, () -> new Needle(null, false));
    }

    @Test
    void the_offset_of_a_match_is_counted_the_way_a_window_counts() {
        // The whole point of the tool: the number in the answer goes straight
        // into a read. A one-based line number here would send every follow-up
        // read one line late, and every match would still look right.
        List<String> lines = List.of("first", "second", "the one wanted", "fourth");

        List<Found.Match> found = sift(new Needle("wanted", false), lines);

        assertEquals(1, found.size());
        assertEquals(List.of("the one wanted"),
                Window.of(found.get(0).offset(), 1).cut(lines).lines(),
                "a window opened at the match's offset returns the line the match carried");
    }

    @Test
    void more_matches_than_the_allowance_are_capped_and_the_answer_says_so() {
        List<String> lines = new ArrayList<>();
        for (int at = 0; at < Needle.MAX_MATCHES * 2; at++) {
            lines.add("hit " + at);
        }
        List<Found.Match> into = new ArrayList<>();

        boolean discarded = new Needle("hit", false).find("f", lines, into);

        assertTrue(discarded, "the caller is told to stop walking rather than left to guess");
        assertEquals(Needle.MAX_MATCHES, into.size());
        assertTrue(Found.of(into, discarded).capped());
        assertEquals(Found.MATCHES, Found.of(into, discarded).stoppedBy());
    }

    @Test
    void an_allowance_exactly_spent_with_nothing_left_over_is_not_a_cap() {
        // One line away from the test above and the one that matters: a search
        // that found exactly the allowance and had nothing else to find must not
        // send a model off narrowing a pattern that was already right.
        List<String> lines = new ArrayList<>();
        for (int at = 0; at < Needle.MAX_MATCHES; at++) {
            lines.add("hit " + at);
        }
        List<Found.Match> into = new ArrayList<>();

        boolean discarded = new Needle("hit", false).find("f", lines, into);

        assertFalse(discarded, "nothing was left out, so nothing is to be narrowed");
        assertEquals(Needle.MAX_MATCHES, into.size());
        assertEquals(Found.END, Found.of(into, discarded).stoppedBy());
    }

    @Test
    void a_budget_already_spent_by_an_earlier_file_stops_the_next_one() {
        // The cap is across the search and not across a file. A per-file
        // allowance would return fifty matches from every file in the tree.
        List<Found.Match> into = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int at = 0; at < Needle.MAX_MATCHES; at++) {
            lines.add("hit " + at);
        }
        new Needle("hit", false).find("first", lines, into);

        assertTrue(new Needle("hit", false).find("second", lines, into),
                "the second file's first match had nowhere to go");
        assertEquals(Needle.MAX_MATCHES, into.size());
    }

    @Test
    void a_line_longer_than_the_allowance_comes_back_cut_and_marked() {
        String line = "x".repeat(Needle.MAX_LINE_CHARS * 3) + "needle";

        List<Found.Match> found = sift(new Needle("needle", false), List.of(line));

        assertEquals(1, found.size());
        assertEquals(Needle.MAX_LINE_CHARS, found.get(0).line().length());
        assertTrue(found.get(0).truncated(),
                "a cut line with nothing to say so reads as a line that ends there");
    }

    @Test
    void a_line_at_the_allowance_is_not_cut_and_is_not_marked() {
        String line = "needle" + "x".repeat(Needle.MAX_LINE_CHARS - "needle".length());

        List<Found.Match> found = sift(new Needle("needle", false), List.of(line));

        assertEquals(Needle.MAX_LINE_CHARS, found.get(0).line().length());
        assertFalse(found.get(0).truncated(), "nothing was left out of this one");
        assertEquals(line, found.get(0).line());
    }

    @Test
    void a_cut_that_would_land_inside_a_character_takes_the_whole_character_off()
            throws CharacterCodingException {
        // MEASURED on JDK 21.0.8: an emoji is two chars, and a String cut
        // between them holds a lone high surrogate whose getBytes(UTF_8) is a
        // single 0x3F — a question mark. The strict encoder this project uses
        // everywhere else raises MalformedInputException on it instead. So a
        // substring at a character count is the same defect as `new
        // String(bytes, UTF_8)`: a silent replacement that reads as the file.
        //
        // THE PAIR HAS TO STRADDLE THE CAP or this measures nothing, and which
        // filler does that is a question of parity rather than of length: the
        // pairs begin after the filler, so the last character the cap allows is
        // a high surrogate only when the filler's length and the cap's last
        // index agree about being odd. Derived, so that a cap moved by one does
        // not leave this test passing over a boundary that no longer splits.
        String emoji = new String(Character.toChars(0x1F600));
        String filler = "n".repeat(Needle.MAX_LINE_CHARS % 2 == 0 ? 1 : 2);
        String line = filler + emoji.repeat(Needle.MAX_LINE_CHARS);
        assertTrue(Character.isHighSurrogate(line.charAt(Needle.MAX_LINE_CHARS - 1)),
                "the fixture really does put half a character on the cap");

        String cut = sift(new Needle("n", false), List.of(line)).get(0).line();

        assertEquals(Needle.MAX_LINE_CHARS - 1, cut.length(),
                "the pair that would have been split is off the end entirely");
        StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(cut));
        assertEquals(cut, new String(cut.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8), "and it survives the wire it is about to cross");
    }

    private static List<Found.Match> sift(Needle needle, List<String> lines) {
        List<Found.Match> into = new ArrayList<>();
        needle.find("fixture", lines, into);
        return into;
    }

    private static List<Integer> offsets(List<Found.Match> found) {
        return found.stream().map(Found.Match::offset).toList();
    }
}
