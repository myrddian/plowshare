package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.llm.dispatch.Lane;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A normalized measurement, separate from call lifecycle and tokenizer preflight. Nullable counts
 * are unknown; zero is an explicit measurement. Totals stay provider-reported. Reasoning is already
 * in output. Cache quantities may only be subtracted under their contract.
 */
public record UsageObservation(
    Long inputTokens,
    Long outputTokens,
    Long providerTotalTokens,
    Long cacheReadTokens,
    Long cacheWriteTokens,
    Long reasoningTokens,
    Source source,
    CacheRelation cacheRelation,
    int normalizationVersion,
    Map<String, Long> numericDetails,
    List<Issue> issues) {

  public static final int VERSION = 1;
  public static final UsageObservation UNKNOWN =
      new UsageObservation(
          null,
          null,
          null,
          null,
          null,
          null,
          Source.NONE,
          CacheRelation.UNSPECIFIED,
          VERSION,
          Map.of(),
          List.of());

  private static final Set<String> DETAILS =
      Set.of(
          "prompt_tokens",
          "completion_tokens",
          "total_tokens",
          "prompt_tokens_details.cached_tokens",
          "completion_tokens_details.reasoning_tokens",
          "promptTokensCount",
          "predictedTokensCount",
          "totalTokensCount");

  public UsageObservation {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(cacheRelation, "cacheRelation");
    if (normalizationVersion != VERSION) {
      throw new IllegalArgumentException("unsupported usage normalization version");
    }
    for (Long value :
        new Long[] {
          inputTokens,
          outputTokens,
          providerTotalTokens,
          cacheReadTokens,
          cacheWriteTokens,
          reasoningTokens
        }) {
      nonnegative(value);
    }
    numericDetails = Map.copyOf(numericDetails);
    numericDetails.forEach(
        (name, count) -> {
          if (!DETAILS.contains(name)) {
            throw new IllegalArgumentException("unsupported numeric usage detail");
          }
          nonnegative(count);
        });
    var checked = new ArrayList<>(List.copyOf(issues));
    subset(checked, Field.CACHE_READ, cacheReadTokens, inputTokens);
    subset(checked, Field.CACHE_WRITE, cacheWriteTokens, inputTokens);
    subset(checked, Field.REASONING, reasoningTokens, outputTokens);
    if (cacheRelation == CacheRelation.DISJOINT_INPUT_SUBSETS
        && inputTokens != null
        && cacheReadTokens != null
        && cacheWriteTokens != null
        && sum(cacheReadTokens, cacheWriteTokens).compareTo(BigInteger.valueOf(inputTokens)) > 0) {
      add(checked, new Issue(Field.CACHE_RELATION, Problem.EXCEEDS_PARENT));
    }
    if (inputTokens != null
        && outputTokens != null
        && providerTotalTokens != null
        && !sum(inputTokens, outputTokens).equals(BigInteger.valueOf(providerTotalTokens))) {
      add(checked, new Issue(Field.TOTAL, Problem.INCONSISTENT_TOTAL));
    }
    issues = List.copyOf(checked);
    if (source == Source.NONE
        && (inputTokens != null
            || outputTokens != null
            || providerTotalTokens != null
            || cacheReadTokens != null
            || cacheWriteTokens != null
            || reasoningTokens != null
            || !numericDetails.isEmpty()
            || !issues.isEmpty())) {
      throw new IllegalArgumentException("an absent usage source cannot carry measurements");
    }
  }

  /** Completeness depends on the lane: embeddings need input, not a fabricated output zero. */
  public Coverage coverage(Lane lane) {
    Objects.requireNonNull(lane, "lane");
    if (!issues.isEmpty()) {
      return Coverage.INVALID;
    }
    if (inputTokens != null && (lane == Lane.EMBEDDING || outputTokens != null)) {
      return Coverage.COMPLETE;
    }
    return inputTokens == null
            && outputTokens == null
            && providerTotalTokens == null
            && cacheReadTokens == null
            && cacheWriteTokens == null
            && reasoningTokens == null
        ? Coverage.UNKNOWN
        : Coverage.PARTIAL;
  }

  private static void subset(List<Issue> issues, Field field, Long child, Long parent) {
    if (child != null && parent != null && child > parent) {
      add(issues, new Issue(field, Problem.EXCEEDS_PARENT));
    }
  }

  private static void add(List<Issue> issues, Issue issue) {
    if (!issues.contains(issue)) {
      issues.add(issue);
    }
  }

  private static BigInteger sum(long one, long two) {
    return BigInteger.valueOf(one).add(BigInteger.valueOf(two));
  }

  private static void nonnegative(Long value) {
    if (value != null && value < 0) {
      throw new IllegalArgumentException("normalized usage counts must be nonnegative");
    }
  }

  public enum Source {
    PROVIDER,
    TOKENIZER,
    ESTIMATE,
    NONE
  }

  public enum Coverage {
    COMPLETE,
    PARTIAL,
    UNKNOWN,
    INVALID,
    NOT_APPLICABLE
  }

  public enum CacheRelation {
    UNSPECIFIED,
    READ_SUBSET_OF_INPUT,
    DISJOINT_INPUT_SUBSETS
  }

  public enum Field {
    USAGE,
    INPUT,
    OUTPUT,
    TOTAL,
    CACHE_READ,
    CACHE_WRITE,
    REASONING,
    CACHE_RELATION
  }

  public enum Problem {
    NON_NUMERIC,
    NEGATIVE,
    OVERFLOW,
    EXCEEDS_PARENT,
    INCONSISTENT_TOTAL
  }

  /** Safe diagnostics contain a field and reason, never an arbitrary provider response. */
  public record Issue(Field field, Problem problem) {
    public Issue {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(problem, "problem");
    }
  }
}
