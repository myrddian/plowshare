package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Trigger;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Whether an utterance matches a trigger. A phrase matches case-insensitively on word boundaries,
 * literally (no regex characters); a {@code /command} matches only at the utterance's start (after
 * leading whitespace), followed by end or whitespace. No regex triggers — spec's own rule.
 */
final class TriggerMatch {

  private TriggerMatch() {}

  /** The first trigger of these that the utterance matches, in declared order. */
  static Optional<Trigger> first(List<Trigger> triggers, String utterance) {
    for (Trigger trigger : triggers) {
      if (matches(trigger, utterance)) {
        return Optional.of(trigger);
      }
    }
    return Optional.empty();
  }

  private static boolean matches(Trigger trigger, String utterance) {
    return trigger.command()
        ? matchesCommand(trigger.text(), utterance)
        : matchesPhrase(trigger.text(), utterance);
  }

  /**
   * The look-arounds test only the neighbouring character, so a phrase that itself begins or ends
   * with a non-word character (e.g. {@code c++}) still matches: {@code "c++ port"} needs only a
   * non-word character (or nothing) before its {@code c} and after its final {@code t}.
   */
  private static boolean matchesPhrase(String text, String utterance) {
    Pattern pattern =
        Pattern.compile(
            "(?<![\\p{L}\\p{N}_])" + Pattern.quote(text) + "(?![\\p{L}\\p{N}_])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    return pattern.matcher(utterance).find();
  }

  private static boolean matchesCommand(String text, String utterance) {
    String stripped = utterance.stripLeading();
    return stripped.regionMatches(true, 0, text, 0, text.length())
        && (stripped.length() == text.length()
            || Character.isWhitespace(stripped.charAt(text.length())));
  }
}
