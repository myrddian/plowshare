package io.aeyer.plowshare.protocol.search;

import java.util.Set;

/**
 * What is true about a provider, as the provider states it.
 *
 * <p><b>Nothing here is policy.</b> There is no rung, no timeout and no enabled flag, because a
 * provider that could nominate its own ladder position could promote itself above the one an
 * operator is paying less for. Spec §3.
 */
public record ProviderFacts(
    String providerKey,
    String name,
    String version,
    String description,
    Set<Verb> verbs,
    CostClass costClass,
    NetworkTier networkTier,
    int maxResults,
    int maxQueryLength,
    boolean domainExclusion) {

  public ProviderFacts {
    verbs = verbs == null ? Set.of() : Set.copyOf(verbs);
  }
}
