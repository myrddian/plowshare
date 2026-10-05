package io.aeyer.plowshare.protocol.search;

import io.aeyer.plowshare.protocol.WebContractValues;
import java.util.Objects;
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
    providerKey = SearchValues.identity(providerKey, "providerKey", 256);
    name = WebContractValues.text(name, "provider name", 1024, true);
    version = SearchValues.identity(version, "provider version", 256);
    description = WebContractValues.text(description, "provider description", 32768, false);
    verbs = verbs == null ? Set.of() : Set.copyOf(verbs);
    Objects.requireNonNull(costClass, "costClass");
    Objects.requireNonNull(networkTier, "networkTier");
    if (maxResults < 1 || maxQueryLength < 1)
      throw new IllegalArgumentException("provider limits must be positive");
  }
}
