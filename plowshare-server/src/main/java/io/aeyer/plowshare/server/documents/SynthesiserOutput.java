package io.aeyer.plowshare.server.documents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.agents.ModelJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What {@code ask_synthesiser} said, split into the prose a reader gets and the
 * attributions a machine checks.
 *
 * <p>Anchor's {@code SynthesiserOutputParser}, lifted out of the orchestrator
 * for the reason its javadoc gives — <i>"the failure modes are all 'what does
 * this specific text input produce'"</i> — and carrying the three it records as
 * observed on a real paper rather than reasoned about:
 *
 * <ol>
 *   <li>the model omits the {@code RESPONSE:} label and simply starts;
 *   <li>the model fences the JSON;
 *   <li>the model echoes the prompt's trailing label and re-emits the prose.
 * </ol>
 *
 * <h2>The third does not port, and its absence is the finding</h2>
 *
 * <p>Anchor's parser terminates the response at {@code GROUNDING:} <em>or</em>
 * at {@code SYNTHESISER OUTPUT:}, whichever comes first, because its prompt file
 * ends with that label and a small model echoes it. <b>An agent body is not a
 * prompt file that ends in a label.</b> {@code ask_synthesiser.md} ends with the
 * output template, the task is a separate message, and there is no trailing
 * label to echo — so porting the marker would be porting a branch nothing can
 * reach, which is a defence that reads as coverage and is not. What is kept is
 * the shape the failure had: the response ends at the first recognised marker
 * after it, and a second marker is one line to add if a body ever grows one.
 *
 * <h2>What the grounding block carries, and why it is not Anchor's</h2>
 *
 * <p>Anchor grounds in arrays of verbatim <em>title strings</em> because it has
 * no stable ids; half its synthesiser prompt and a paren-insensitive scrubber
 * are the cost of that, and {@code scrubSyntheticMarkers} is <b>not ported</b> —
 * with no title arrays there is nothing to scrub, and the synthetic-title hazard
 * stays at the render boundary where {@link StructuralRef} keeps it.
 *
 * <p>What replaces it is a paragraph id and the words the claim rests on. The id
 * is V18's surrogate key, which survives a re-ingest of unchanged text, and the
 * quote is the thing Anchor's design could not check: a title can only be
 * string-matched against another title, while <b>a quote either occurs in the
 * paragraph it names or it does not</b>. {@link Deliberation} is where that
 * check runs; this class only reads what was claimed.
 *
 * <h2>Three things are dropped here and one is reported</h2>
 *
 * <p>An entry whose {@code paragraph} is not a uuid, and an entry whose {@code
 * quote} is blank, are dropped: neither is an attribution, and a blank quote
 * would pass a substring test against every paragraph in the corpus. They are
 * dropped rather than reported because a well-formed block with a malformed
 * entry is a model that answered the question and mistyped a field.
 *
 * <h2>The two challenge lists, which Anchor asks for and neither system reads</h2>
 *
 * <p>{@code ask-synthesiser.txt} and this port's {@code ask_synthesiser.md} both
 * end by asking for {@code incorporated_critic_challenges} and {@code
 * rejected_critic_challenges}. <b>Anchor's {@code SynthesiserOutputParser} never
 * mentions either field</b>, and neither did this class: they were emitted,
 * parsed past, and thrown away. So a synthesiser that read the critic's
 * challenges and a synthesiser that ignored them produced <em>identical</em>
 * output on every path a reader or a machine could see — which is the shape of
 * defect this package keeps finding, a silent success where a failure belonged.
 *
 * <p>They are read now, and {@link Deliberation} reconciles them against what
 * the critic actually raised. That reconciliation is the point; the parse is
 * only what makes it possible.
 *
 * <p><b>The prompt's contract was unsatisfiable as written and is changed to
 * match.</b> Anchor asks for <i>"indices of challenges rejected, with reason"</i>
 * in a field whose example value is an array of bare numbers — there is nowhere
 * in it for a reason to go, so no model could comply and none was ever asked to
 * account for a rejection. A rejection here is an object carrying the challenge
 * and the reason, and <b>a rejection with no reason is dropped</b> on this
 * class's existing argument for a two-word quotation: it is not a bad
 * adjudication, it is not one. What a dropped rejection becomes is a challenge
 * that nothing ruled on, which is exactly what it is.
 *
 * <p>The numbers are one-based, because {@code Deliberation.formatted} numbers
 * the challenges from one when it shows them to the synthesiser. Anchor's own
 * examples are zero-based against a prompt that renders them from one, which is
 * an off-by-one nothing there could notice.
 *
 * <p><b>A block that could not be read at all is reported</b>, and that is the
 * one place this diverges from Anchor's best-effort return. Anchor hands back
 * {@code Map.of("raw_output", …)} and leaves the caller to notice. An answer
 * whose grounding could not be read is an answer whose claims were never
 * checked, and it must not arrive at a reader looking like an answer that
 * grounded in nothing — those are opposite facts.
 */
final class SynthesiserOutput {

    private static final String RESPONSE = "RESPONSE:";
    private static final String GROUNDING = "GROUNDING:";

    /** How much of an unreadable block is quoted back. Anchor's 500, and its
     *  arithmetic: enough to see what the model did, short of putting a whole
     *  second answer in a sentence somebody reads. */
    private static final int MOST_QUOTED = 500;

    /**
     * The shortest thing that can be an attribution.
     *
     * <p><b>Three, and it is a floor on evidence rather than a style rule.</b>
     * The check {@link Deliberation} runs is a substring, so a quotation's whole
     * value is that finding it in the paragraph it names is <em>evidence</em>
     * rather than coincidence. {@code "."} occurs in every paragraph in the
     * corpus and {@code "the"} in most of them; three words is where a
     * quotation starts distinguishing one from another, and it is the shortest
     * thing a claim has ever been written in.
     *
     * <p><b>Dropped rather than failed</b>, exactly as a blank quotation is and
     * for the same reason: an entry this short is not a wrong attribution, it is
     * not an attribution. Reporting it as a failure would fire the loudest signal
     * this system has at a model that was terse.
     */
    private static final int FEWEST_WORDS = 3;

    /** Whitespace, including the kinds a PDF leaves behind — {@code \s} is
     *  ASCII-only and {@code Deliberation.flattened} owns the argument. */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private final String response;
    private final List<Grounded> grounding;
    private final List<Integer> incorporated;
    private final List<Rejection> rejected;
    private final List<Integer> objectionsAddressed;
    private final String unreadable;

    private SynthesiserOutput(
            String response, List<Grounded> grounding, List<Integer> incorporated,
            List<Rejection> rejected, List<Integer> objectionsAddressed, String unreadable) {

        this.response = response;
        this.grounding = List.copyOf(grounding);
        this.incorporated = List.copyOf(incorporated);
        this.rejected = List.copyOf(rejected);
        this.objectionsAddressed = List.copyOf(objectionsAddressed);
        this.unreadable = unreadable;
    }

    /**
     * One claim's attribution, as the model made it.
     *
     * <p><b>Unvalidated.</b> The paragraph may name no row and the quote may
     * appear in no paragraph; this record is what was <em>said</em>, and {@link
     * Deliberation} is what asks the corpus.
     *
     * @param paragraph the id the model copied off a passage
     * @param quote the words it says the claim rests on. Uploaded text —
     *     somebody's document quoted back — and never blank
     */
    record Grounded(UUID paragraph, String quote) {

        Grounded {
            Objects.requireNonNull(paragraph, "paragraph");
            Objects.requireNonNull(quote, "quote");
        }
    }

    /**
     * One challenge the answer says it ruled against, and what it ruled from.
     *
     * <p><b>Unvalidated, like {@link Grounded}.</b> The number may name no
     * challenge the critic raised; this record is what was <em>said</em>, and
     * {@link Deliberation} is what holds it against the critic's own list.
     *
     * @param challenge the challenge's number as the synthesiser was shown it,
     *     counting from one
     * @param reason why it does not hold. <b>Model prose</b> — it reaches a
     *     reader quoted, for {@code Deliberation}'s reason
     */
    record Rejection(int challenge, String reason) {

        Rejection {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Read one synthesiser answer.
     *
     * @param raw what the model said, or null. Null and empty are the same fact
     *     — it said nothing — and neither throws: this is on the path that ends
     *     in a person's answer, and a parse failure must never be the thing that
     *     loses one
     */
    static SynthesiserOutput of(String raw) {
        if (raw == null) {
            return new SynthesiserOutput("", List.of(), List.of(), List.of(), List.of(), null);
        }
        int labelled = raw.indexOf(RESPONSE);
        int from = labelled >= 0 ? labelled + RESPONSE.length() : 0;
        int marker = raw.indexOf(GROUNDING, from);
        int to = marker >= 0 ? marker : raw.length();
        String response = raw.substring(from, to).strip();

        if (marker < 0) {
            return new SynthesiserOutput(response, List.of(), List.of(), List.of(), List.of(),
                    null);
        }
        String block = raw.substring(marker + GROUNDING.length()).strip();
        JsonNode read;
        try {
            // ModelJson and not a second reader, on its own argument: a second
            // copy of this boundary is what drifts, and the fence-and-preface
            // tolerance Anchor's stripFences hand-rolls is exactly what that
            // class already owns.
            read = ModelJson.object(block);
        } catch (ModelJson.Unreadable notJson) {
            return new SynthesiserOutput(response, List.of(), List.of(), List.of(), List.of(),
                    notJson.getMessage() + ": " + truncated(block));
        }
        return new SynthesiserOutput(response, attributions(read.path("grounded_in")),
                incorporated(read.path("incorporated_critic_challenges")),
                rejections(read.path("rejected_critic_challenges")),
                incorporated(read.path("objections_addressed")), null);
    }

    /** The prose the reader gets. Empty for an answer that said nothing, which
     *  is a decision the model made and not a failure to parse. */
    String response() {
        return response;
    }

    /** What the answer said it took from where, in the order it said it.
     *  Unchecked; see {@link Grounded}. */
    List<Grounded> grounding() {
        return grounding;
    }

    /**
     * The challenges the answer says it took up, as it numbered them.
     * Unchecked; {@link Deliberation} holds these against what was raised.
     */
    List<Integer> incorporated() {
        return incorporated;
    }

    /** The challenges the answer says it ruled against, with the reason each
     *  gave. Unchecked, and a rejection that gave no reason is not here. */
    List<Rejection> rejected() {
        return rejected;
    }

    /**
     * The review pass's objections the answer says it addressed.
     *
     * <p><b>A field of its own, and that is the whole point of it.</b> The
     * synthesiser is handed two numbered lists — the critic's challenges and the
     * reviewer's objections — and with one pair of fields to report in it put
     * objection numbers into {@code incorporated_critic_challenges}. Measured:
     * one answer claimed three incorporations against a critic that raised none.
     * {@code Deliberation.unruled} resolves those numbers against the critic's
     * list, so the check that catches a synthesiser ignoring its critic was
     * being satisfied by numbers that meant something else.
     *
     * <p>Unvalidated like the rest of this block, and <b>nothing reconciles it
     * yet</b>: the objections are prose from a stage whose output no machine
     * parses, so there is no list to hold these against. It exists so that the
     * challenge fields stop absorbing what does not belong to them.
     */
    List<Integer> objectionsAddressed() {
        return objectionsAddressed;
    }

    /** Why the grounding block could not be read, for an answer that emitted
     *  one and got it wrong. Empty both for a block that read and for an answer
     *  that emitted none. */
    Optional<String> groundingWasUnreadable() {
        return Optional.ofNullable(unreadable);
    }

    private static List<Grounded> attributions(JsonNode entries) {
        List<Grounded> attributions = new ArrayList<>();
        if (!entries.isArray()) {
            return attributions;
        }
        for (JsonNode entry : entries) {
            String quote = entry.path("quote").asText("");
            if (words(quote) < FEWEST_WORDS) {
                // A quotation that cannot tell one paragraph from another is not
                // an attribution. A blank one is a substring of every paragraph
                // in the corpus and a one-word one very nearly is, so both would
                // validate against whatever they named and evidence nothing.
                continue;
            }
            UUID paragraph = uuidOrNull(entry.path("paragraph").asText(""));
            if (paragraph == null) {
                continue;
            }
            attributions.add(new Grounded(paragraph, quote));
        }
        return attributions;
    }

    /**
     * The numbers in {@code incorporated_critic_challenges}.
     *
     * <p>Integral and not {@code asInt}: {@code asInt} answers 0 for a string, an
     * object and a null alike, and 0 is not a challenge number under one-based
     * numbering — so a model that wrote {@code ["the first one"]} would be
     * recorded as having ruled on a challenge that does not exist. What is not a
     * number is not a ruling.
     */
    private static List<Integer> incorporated(JsonNode entries) {
        List<Integer> numbers = new ArrayList<>();
        if (!entries.isArray()) {
            return numbers;
        }
        for (JsonNode entry : entries) {
            if (entry.isIntegralNumber()) {
                numbers.add(entry.intValue());
            }
        }
        return numbers;
    }

    /**
     * The rejections, <b>which must say why</b>.
     *
     * <p>An entry with no {@code reason}, or a blank one, is dropped. A
     * rejection is the one thing in this block the synthesiser does that neither
     * other agent can — it overrules a critic from evidence the critic was not
     * given — and an assertion with nothing behind it does not do that. Dropping
     * it makes the challenge read as unruled downstream, which is what an
     * unexplained rejection amounts to.
     *
     * <p>A bare number is tolerated as the older shape and dropped by the same
     * rule, so a model that answers Anchor's example literally is not read as
     * having ruled on anything.
     */
    private static List<Rejection> rejections(JsonNode entries) {
        List<Rejection> rejections = new ArrayList<>();
        if (!entries.isArray()) {
            return rejections;
        }
        for (JsonNode entry : entries) {
            JsonNode number = entry.path("challenge");
            if (!number.isIntegralNumber()) {
                continue;
            }
            String reason = entry.path("reason").asText("").strip();
            if (reason.isBlank()) {
                continue;
            }
            rejections.add(new Rejection(number.intValue(), reason));
        }
        return rejections;
    }

    private static int words(String quote) {
        String flattened = WHITESPACE.matcher(quote).replaceAll(" ").strip();
        return flattened.isEmpty() ? 0 : flattened.split(" ").length;
    }

    private static UUID uuidOrNull(String text) {
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static String truncated(String block) {
        String oneLine = WHITESPACE.matcher(block).replaceAll(" ").strip();
        return oneLine.length() <= MOST_QUOTED
                ? oneLine
                : oneLine.substring(0, MOST_QUOTED) + "...";
    }
}
