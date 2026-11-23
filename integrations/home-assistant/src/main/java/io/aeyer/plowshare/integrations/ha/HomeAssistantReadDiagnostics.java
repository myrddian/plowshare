package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only local observations of this provider's owners. Taking a snapshot performs no IO, does
 * not retry work, and cannot establish end-to-end availability or the cause of a disconnected peer.
 */
public interface HomeAssistantReadDiagnostics {
  enum ConnectionObservation {
    AUTHENTICATED_CONNECTION,
    UNAVAILABLE,
    NOT_OBSERVED
  }

  enum CatalogueObservation {
    CONFIRMED_LOCAL_LEASE,
    UNAVAILABLE,
    NOT_OBSERVED
  }

  record Snapshot(
      Instant checkedAt,
      ConnectionObservation homeAssistantConnection,
      CatalogueObservation catalogue,
      Optional<Instant> lastSuccessfulSdkCommunication) {
    public Snapshot {
      Objects.requireNonNull(checkedAt);
      Objects.requireNonNull(homeAssistantConnection);
      Objects.requireNonNull(catalogue);
      Objects.requireNonNull(lastSuccessfulSdkCommunication);
    }
  }

  /** Returns bounded metadata only: no origins, credentials, journal records or result payloads. */
  Snapshot snapshot();

  /**
   * Exact immutable configured attribute ceiling, or no observation. This is not an admission:
   * callers must independently complete a discovery/read fence for this same revision before
   * disclosing it. Absence from native metadata alone does not prove exclusion by policy.
   */
  default Optional<Set<String>> permittedAttributes(Reference reference, Revision revision) {
    return Optional.empty();
  }

  /** Embedded users that do not supply a provider owner must not invent connectivity evidence. */
  static HomeAssistantReadDiagnostics unobserved() {
    return () ->
        new Snapshot(
            Instant.now(),
            ConnectionObservation.NOT_OBSERVED,
            CatalogueObservation.NOT_OBSERVED,
            Optional.empty());
  }
}
