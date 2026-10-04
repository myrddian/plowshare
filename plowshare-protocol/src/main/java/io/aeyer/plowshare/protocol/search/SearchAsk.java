package io.aeyer.plowshare.protocol.search;

import java.util.List;

/**
 * One question, on its way out of this machine.
 *
 * <p>{@code max} is the most we are willing to fetch and never a target — a provider answering six
 * against a max of fifty has not failed at anything. Spec §2.
 *
 * <p>{@code ignoredDomains} is advisory. A provider may apply it, and one that declares {@code
 * domainExclusion} usually will, because doing so spends its result budget on hits that are
 * actually fetchable. A provider that ignores it is not misbehaving, and nothing downstream
 * re-filters on it. Spec §7.
 */
public record SearchAsk(String requestId, String query, int max, List<String> ignoredDomains) {

  public SearchAsk {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("requestId must not be blank");
    }
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    if (max < 1) {
      throw new IllegalArgumentException("max must be at least 1, was " + max);
    }
    ignoredDomains = ignoredDomains == null ? List.of() : List.copyOf(ignoredDomains);
  }
}
