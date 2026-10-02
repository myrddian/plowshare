package io.aeyer.plowshare.server.harness;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.agents.ModelJson;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the stuck advisor's answer comes to: a note worth sending, or a reason to send nothing.
 *
 * <h2>Nothing is the usual answer, and a doubtful one is nothing too</h2>
 *
 * <p>The advisor answers {@code {"note": "…"}} or {@code {"note": null}}, and is told that null
 * is most of the time right. Everything else is swallowed: an empty answer, one that is not a
 * JSON object, a note that is not text or is blank, one longer than {@code advice-most}, one
 * that reads as the advisor's own reasoning or as talk about its instructions or tools, and a
 * general exhortation. Measured 2026-09-30: one hint delivered the advisor's own muddled
 * reasoning ("Let's request file_read… We cannot request new tools?") to a coder, and most of
 * the 187 said "stop reading, make a concrete change" -- which a coder four steps into its
 * work can do nothing with but answer.
 *
 * <h2>A note names only tools the run was offered</h2>
 *
 * <p>Measured 2026-09-30: an advisor told a {@code code_reviewer}, which held reads and searches
 * alone, to run a check "with a run/exec tool", and the reviewer called {@code run_python}, which
 * no run has. The brief lists the offered tools; a note that still names another is swallowed.
 * A tool's name is a snake_case word ({@link #TOOL_LIKE}); one is not a tool when it is offered,
 * when it is part of a file's name or path ({@code import_game_check.py}), or when the steps show
 * it -- in the question, or in a call's arguments or result -- since a test or a function the
 * work names is evidence the note may quote. What a call to a tool the run was not offered sent
 * or got back is not evidence of that kind: {@code StuckTrap} leaves it out, so a made-up tool the
 * model already tried is still not one the advisor may suggest.
 *
 * <p><b>Which way the tests lean.</b> A swallowed note that would have helped costs a hint; a
 * sent one that should not have been costs the working model's attention and, measured, its
 * work. So the checks are phrases that are never advice, not a judge of what is.
 */
final class StuckVerdict {

    /** What the advisor's answer came to: exactly one of the two is set. */
    record Reading(String note, String swallowed) {
    }

    /** How a note that is the advisor thinking aloud opens. */
    private static final Pattern REASONING_OPENING = Pattern.compile(
            "^(?:let's|let us|let me|we|i|i'm|i'll|i've|okay|ok|hmm+|alright)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Talk about the brief, the advisor's instructions or tools, or its own plans. */
    private static final Pattern META = Pattern.compile("\\b(?:the brief|these instructions|my instructions"
            + "|the instructions|system prompt|new tools|as an ai"
            + "|we (?:cannot|can't|can not|need|should|must|could|have to)"
            + "|i (?:need|should|must|think|will|cannot|can't|am going))\\b|\"note\"|<think",
            Pattern.CASE_INSENSITIVE);

    /** The exhortations measured as nearly every hint; advice names a failure and a move. */
    private static final Pattern EXHORTATION = Pattern.compile("\\b(?:step back|concrete change"
            + "|stop reading|reassess your approach|reconsider your approach|different approach"
            + "|materially different)\\b", Pattern.CASE_INSENSITIVE);

    /** A word that could be a tool's name: snake_case, and not part of a path or a file's name
     *  -- not after a word character, dot, slash or hyphen, nor before one, nor before a dot that
     *  starts an extension. */
    private static final Pattern TOOL_LIKE = Pattern.compile(
            "(?<![\\w./-])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![\\w/-]|\\.\\w)");

    private StuckVerdict() {
    }

    /**
     * @param answer what the advisor said, whole
     * @param most the longest note that is sent; a longer one is swallowed, not cut, since a
     *     cut note is one the advisor did not write
     * @param offered the tool names the run was offered
     * @param evidence what the steps show that a note may quote: the question, and the arguments
     *     and results of calls to offered tools
     */
    static Reading read(String answer, int most, Set<String> offered, String evidence) {
        if (answer == null || answer.isBlank()) {
            return swallowed("the advisor said nothing");
        }
        JsonNode object;
        try {
            object = ModelJson.object(answer);
        } catch (ModelJson.Unreadable unreadable) {
            return swallowed("the answer is not a JSON object with a note (" + unreadable.getMessage() + ")");
        }
        JsonNode note = object.get("note");
        if (note == null || note.isNull()) {
            return swallowed("the advisor had nothing to say");
        }
        if (!note.isTextual()) {
            return swallowed("the note is not text");
        }
        String text = note.asText().strip();
        if (text.isEmpty()) {
            return swallowed("the note is empty");
        }
        if (text.length() > most) {
            return swallowed("the note is " + text.length() + " characters, more than the " + most
                    + " a note may be");
        }
        String plain = text.replace('’', '\'');
        if (REASONING_OPENING.matcher(plain).find() || META.matcher(plain).find()) {
            return swallowed("the note reads as the advisor's own reasoning, not advice");
        }
        if (EXHORTATION.matcher(plain).find()) {
            return swallowed("the note is a general exhortation, not advice");
        }
        Matcher word = TOOL_LIKE.matcher(plain);
        while (word.find()) {
            String named = word.group();
            if (!offered.contains(named) && !shows(evidence, named)) {
                return swallowed("the note names " + named + ", which is not a tool this run was offered");
            }
        }
        return new Reading(text, null);
    }

    /** Whether {@code evidence} holds {@code word} as a whole word. */
    private static boolean shows(String evidence, String word) {
        return Pattern.compile("(?<!\\w)" + Pattern.quote(word) + "(?!\\w)").matcher(evidence).find();
    }

    private static Reading swallowed(String why) {
        return new Reading(null, why);
    }
}
