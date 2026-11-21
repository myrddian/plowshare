package io.aeyer.plowshare.server.archive;

import java.util.Locale;

/**
 * Which repository search answers lexical conversation retrieval.
 *
 * <p>Selected once at startup by {@code plowshare.retrieval.lexical-engine}. It is an operator's
 * choice and rollback lever, not a per-request option: every engine applies the same visibility,
 * home and conversation scoping, and they differ in cost and in the grouping, excerpt and total
 * semantics described on {@link ConversationSearchRepository#searchBounded}.
 */
public enum LexicalEngine {
  /**
   * Stored passages and postings with every match counted. Totals and source counts are exact up to
   * the repository's grouping window, so a client may page by the total. The default.
   */
  PASSAGES,
  /**
   * The same search, allowed to skip matches that cannot reach the page. Faster on common words,
   * but a truncated total is then a lower bound that can be little more than the page: only for
   * deployments whose clients read {@code retrieval.truncated} instead of stopping at the total.
   */
  PASSAGES_PRUNED,
  /** The original whole-entry search; exact totals, and a cost that grows with every match. */
  LEGACY;

  /** The configuration key, named in the failure an invalid value produces. */
  public static final String PROPERTY = "plowshare.retrieval.lexical-engine";

  /**
   * Parses the configured value.
   *
   * @param configured {@code passages}, {@code passages-pruned} or {@code legacy}, in any case;
   *     null or blank selects the default
   * @return the selected engine
   * @throws IllegalArgumentException for any other value, so a misspelt rollback fails startup
   *     instead of silently serving the engine the operator meant to leave
   */
  public static LexicalEngine configured(String configured) {
    if (configured == null || configured.isBlank()) return PASSAGES;
    return switch (configured.strip().toLowerCase(Locale.ROOT)) {
      case "passages" -> PASSAGES;
      case "passages-pruned" -> PASSAGES_PRUNED;
      case "legacy" -> LEGACY;
      default ->
          throw new IllegalArgumentException(
              PROPERTY + " must be passages, passages-pruned or legacy");
    };
  }
}
