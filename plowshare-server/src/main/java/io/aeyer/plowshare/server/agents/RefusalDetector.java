package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.llm.dispatch.Completion;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a completion is a probable refusal, decided outside the model.
 *
 * <h2>Detection is not authorisation</h2>
 *
 * <p>This answers one question — <em>does this read as the model declining the
 * task?</em> — and nothing else. Whether a refusal may be answered by another
 * model is {@link AgentDefinition.Fallback}'s question, asked of the agent's
 * own definition, and a detector that fires never by itself sends anything
 * anywhere. So a better detector changes how often an eligible agent is
 * rerouted and cannot change which agents are eligible.
 *
 * <p><b>The model is never asked.</b> Asking the model that refused whether it
 * refused spends a call on a second opinion from the one party with a stake in
 * the first, and puts the refusal in front of it again.
 *
 * <p>An interface so a classifier can replace {@link #PHRASES} without the
 * runtime knowing: it is handed the completion and nothing else, which is all
 * the phrase detector needs and is enough to hand a classifier too.
 */
@FunctionalInterface
public interface RefusalDetector {

    /**
     * Why this completion reads as a refusal, or empty when it does not.
     *
     * <p>Only a completion that is an answer is ever asked: one that called
     * tools is doing the task, and one that was cut off stopped rather than
     * declined.
     */
    Optional<String> refusal(Completion completion);

    /** Never finds one. What a runtime nobody configured uses. */
    RefusalDetector NEVER = completion -> Optional.empty();

    /** The deterministic detector; see {@link Phrases}. */
    RefusalDetector PHRASES = new Phrases();

    /**
     * A refusal is short and says so in its opening.
     *
     * <h2>Why both conditions and not either</h2>
     *
     * <p><b>Refusals from instruction-tuned models are formulaic and brief</b>
     * — "I'm sorry, but I can't help with that." is the whole of a typical
     * gpt-oss refusal. A long answer that happens to <em>contain</em> "I can't
     * help with" is overwhelmingly an answer: a partial refusal of one part of a
     * request, or a quoted sentence. Rerouting that would replace a real answer
     * with a second opinion, which is the expensive direction to be wrong in.
     * So the phrase has to open the answer and the answer has to be short.
     *
     * <p><b>Curly quotes are folded first.</b> Models emit U+2019 in "can't"
     * about as often as the ASCII apostrophe, and a list of phrases that matched
     * one spelling would miss half of what it exists to catch.
     */
    final class Phrases implements RefusalDetector {

        /** Longer than this and it is an answer that mentions a refusal. */
        static final int LONGEST_REFUSAL = 400;

        /**
         * How far into the answer the phrase must begin. Room for a short
         * preamble — "I understand the interest, but I can't help with" — and
         * not for a sentence of answer before it.
         */
        static final int OPENING = 48;

        static final List<String> OPENERS = List.of(
                "i'm sorry, but i can't",
                "i'm sorry, but i cannot",
                "i'm sorry, i can't",
                "i'm sorry, i cannot",
                "sorry, but i can't",
                "sorry, i can't",
                "i can't help with",
                "i cannot help with",
                "i can't assist with",
                "i cannot assist with",
                "i can't comply",
                "i cannot comply",
                "i can't provide",
                "i cannot provide",
                "i won't be able to help",
                "i'm unable to help",
                "i am unable to help",
                "i'm not able to help",
                "i must decline",
                "i can't do that",
                "i cannot do that");

        @Override
        public Optional<String> refusal(Completion completion) {
            Objects.requireNonNull(completion, "completion");
            if (!completion.toolCalls().isEmpty() || "length".equals(completion.finishReason())) {
                return Optional.empty();
            }
            String content = completion.content() == null ? "" : completion.content().strip();
            if (content.isEmpty() || content.length() > LONGEST_REFUSAL) {
                return Optional.empty();
            }
            String folded = Normalizer.normalize(content, Normalizer.Form.NFKC)
                    .replace('’', '\'')
                    .replace('‘', '\'')
                    .toLowerCase(Locale.ROOT);
            for (String opener : OPENERS) {
                int at = folded.indexOf(opener);
                if (at >= 0 && at <= OPENING) {
                    return Optional.of("the answer opens with the refusal phrase '" + opener
                            + "' and is " + content.length() + " characters long");
                }
            }
            return Optional.empty();
        }
    }
}
