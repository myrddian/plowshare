package io.aeyer.plowshare.server.documents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.agents.ModelJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code ask_critic} said, read — or the absence that makes {@link
 * Deliberation} ask it a second time.
 *
 * <p>Anchor's {@code tryParseCritic}, and an empty answer here is what its
 * {@code parseCriticOrRetry} branches on. Two things about the port are worth
 * saying before the code.
 *
 * <h2>An object with no {@code challenges} array is not a critic that found
 * nothing</h2>
 *
 * <p><b>This is a defect in Anchor, fixed rather than ported.</b> Its parse
 * takes any JSON object: {@code challengesNode.isArray()} is simply false for a
 * missing key, the list stays empty, and a {@code ParsedCritic} comes back
 * carrying no challenges. So {@code {"ok": true}} — or any object a confused
 * model emits — is indistinguishable from a critic that read the macro view and
 * had nothing to say, which is the one verdict of this stage a reader most needs
 * to trust. The retry that exists for exactly this never fires, and the
 * synthesiser proceeds under "no challenges raised" as though the critic had
 * agreed with the proposer.
 *
 * <p>So the array is what makes a reading: present and an array, empty or not.
 * An empty one is a real and common answer and stays one.
 *
 * <h2>The verdict is a string and not a boolean</h2>
 *
 * <p>{@code macro_view_supports_proposer} is {@code true}, {@code false} or the
 * <em>string</em> {@code "partially"} in Anchor's own prompt, so its type is
 * already "one of three words" rather than a boolean with an escape hatch.
 * {@code asText} over all three keeps the three distinct; a boolean with a flag
 * beside it would be two fields answering one question. An absent key is {@code
 * "unknown"}, which is Anchor's own fallback string.
 */
final class CriticOutput {

    /** What a critic that emitted no verdict is recorded as. Anchor's own
     *  fallback, and it is a fourth value rather than a guess at one of the
     *  three. */
    static final String UNKNOWN = "unknown";

    private final List<String> challenges;
    private final String support;

    private CriticOutput(List<String> challenges, String support) {
        this.challenges = List.copyOf(challenges);
        this.support = Objects.requireNonNull(support, "support");
    }

    /**
     * Read one critic answer.
     *
     * @param raw what the model said, or null
     * @return the reading, or empty for anything that is not an object carrying
     *     a {@code challenges} array — which is what {@link Deliberation} retries
     *     once, at temperature zero
     */
    static Optional<CriticOutput> of(String raw) {
        JsonNode read;
        try {
            read = ModelJson.object(raw);
        } catch (ModelJson.Unreadable notJson) {
            return Optional.empty();
        }
        JsonNode listed = read.path("challenges");
        if (!listed.isArray()) {
            return Optional.empty();
        }
        List<String> challenges = new ArrayList<>();
        for (JsonNode challenge : listed) {
            // Textual and not asText: a number or an object in this array is a
            // model that did not answer the question, and asText would turn it
            // into a challenge reading "7".
            if (challenge.isTextual() && !challenge.asText().isBlank()) {
                challenges.add(challenge.asText().strip());
            }
        }
        return Optional.of(new CriticOutput(challenges, verdict(read)));
    }

    /** What the critic challenged, in the order it said so. <b>Model prose about
     *  uploaded text</b>, and it reaches the synthesiser quoted for that
     *  reason. */
    List<String> challenges() {
        return challenges;
    }

    /** {@code "true"}, {@code "false"}, {@code "partially"}, or {@link
     *  #UNKNOWN}. */
    String support() {
        return support;
    }

    /** The four words a verdict can be. Anchor's prompt asks for three and its
     *  parser falls back on the fourth. */
    private static final List<String> VERDICTS =
            List.of("true", "false", "partially", UNKNOWN);

    /**
     * The verdict, <b>constrained to the four words rather than passed
     * through</b>.
     *
     * <p>Anchor's parser takes {@code asText()} of whatever the field held and
     * hands it to a caller that renders it. That is a model writing directly
     * into a sentence somebody reads, with no bound on its length or its line
     * breaks — and {@code Deliberation} puts this one under a heading it wrote
     * itself, beside a block that says whether the answer's quotations held. A
     * verdict is a closed set of words and there is nothing here worth passing
     * through; anything else is a critic that did not answer the question, which
     * is what {@link #UNKNOWN} already means.
     */
    private static String verdict(JsonNode read) {
        String said = read.path("macro_view_supports_proposer").asText("")
                .strip().toLowerCase(java.util.Locale.ROOT);
        return VERDICTS.contains(said) ? said : UNKNOWN;
    }
}
