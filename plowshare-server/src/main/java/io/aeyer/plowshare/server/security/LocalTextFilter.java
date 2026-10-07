package io.aeyer.plowshare.server.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import java.io.IOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Local keyword and regex filtering over bundled rule data. Pattern matching is a supplemental text
 * check, not authorization.
 */
public final class LocalTextFilter implements TextFiltering {
  private record Keyword(String keyword, String severity) {}

  private record Category(
      @JsonProperty("category_name") String name,
      String description,
      @JsonProperty("default_action") String action,
      @JsonProperty("identifier_words") List<String> identifiers,
      @JsonProperty("additional_block_words") List<String> targets,
      @JsonProperty("always_block_keywords") List<Keyword> keywords,
      List<String> exceptions) {}

  private record Categories(List<Category> categories) {}

  private record Builtin(
      String name,
      @JsonProperty("display_name") String displayName,
      String pattern,
      String category,
      String description,
      String action,
      @JsonProperty("keyword_pattern") String keywordPattern,
      @JsonProperty("allow_word_numbers") Boolean allowWordNumbers) {}

  private record Patterns(List<Builtin> patterns) {}

  private record Selected(
      String name, FilteringProperties.Action action, Pattern pattern, Pattern context) {
    boolean matches(String value) {
      return (context == null || context.matcher(value).find()) && pattern.matcher(value).find();
    }
  }

  private final boolean enabled, conditional;
  private final List<Category> categories;
  private final List<Selected> patterns;

  public LocalTextFilter(FilteringProperties properties) {
    Objects.requireNonNull(properties);
    enabled = properties.isEnabled();
    conditional = properties.isConditionalMatches();
    var catalog = load("categories.json", Categories.class).categories();
    for (var name : properties.getCategories())
      if (catalog.stream().noneMatch(c -> c.name().equals(name)))
        throw new IllegalArgumentException("Unknown injection category");
    categories =
        catalog.stream().filter(c -> properties.getCategories().contains(c.name())).toList();
    var builtin = load("patterns.json", Patterns.class).patterns();
    patterns =
        properties.getPatterns().stream()
            .map(
                p -> {
                  var rule =
                      builtin.stream()
                          .filter(b -> b.name().equals(p.name()))
                          .findFirst()
                          .orElseThrow(
                              () -> new IllegalArgumentException("Unknown sensitive pattern"));
                  return new Selected(
                      p.name(),
                      p.action(),
                      Pattern.compile(rule.pattern()),
                      rule.keywordPattern() == null
                          ? null
                          : Pattern.compile(rule.keywordPattern(), Pattern.CASE_INSENSITIVE));
                })
            .toList();
  }

  private static <T> T load(String path, Class<T> type) {
    try (var input = LocalTextFilter.class.getResourceAsStream("/security/rules/" + path)) {
      if (input == null) throw new IllegalStateException("Missing bundled filter rules");
      return JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build()
          .readValue(input, type);
    } catch (IOException failed) {
      throw new IllegalStateException("Invalid bundled filter rules", failed);
    }
  }

  @Override
  public boolean filtersOutput() {
    return enabled && !patterns.isEmpty();
  }

  /**
   * Only untrusted user/tool text gets injection checks; disclosure checks can cover every role.
   */
  @Override
  public String inspect(String text, boolean injection, boolean mayMask) {
    Objects.requireNonNull(text);
    if (!enabled) return text;
    if (text.length() > 524288) throw blocked("input_limit");
    if (injection) {
      String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
      for (var category : categories) {
        // Exceptions never exempt an explicit high-severity override. Apply conditional
        // exceptions per sentence so an unrelated safe sentence cannot whitelist an attack.
        for (var word : category.keywords())
          if (word.severity().equals("high") && phrase(normalized, word.keyword()))
            throw blocked(category.name());
        if (conditional)
          for (var sentence : normalized.split("[.!?\\r\\n]+"))
            if (category.identifiers().stream().anyMatch(w -> phrase(sentence, w))
                && category.targets().stream().anyMatch(w -> phrase(sentence, w))
                && category.exceptions().stream().noneMatch(w -> phrase(sentence, w)))
              throw blocked(category.name());
      }
    }
    // Detect all policies against the original before masking: overlap cannot hide a BLOCK.
    for (var policy : patterns)
      if ((policy.action() == FilteringProperties.Action.BLOCK || !mayMask) && policy.matches(text))
        throw blocked(policy.name());
    String approved = text;
    for (var policy : patterns)
      if (policy.action() == FilteringProperties.Action.MASK && policy.matches(text))
        approved =
            policy
                .pattern()
                .matcher(approved)
                .replaceAll("[REDACTED_" + policy.name().toUpperCase(Locale.ROOT) + "]");
    return approved;
  }

  private static boolean phrase(String text, String keyword) {
    return Pattern.compile(
            "(?<![\\p{L}\\p{N}_])"
                + Pattern.quote(keyword.toLowerCase(Locale.ROOT))
                + "(?![\\p{L}\\p{N}_])")
        .matcher(text)
        .find();
  }

  private static LlmException blocked(String rule) {
    return new LlmException("Message filtering refused: " + rule);
  }
}
