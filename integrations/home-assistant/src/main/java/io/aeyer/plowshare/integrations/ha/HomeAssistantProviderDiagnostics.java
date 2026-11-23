package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Provider-local observations only. The supervisor stops on SDK failure, so a provider-hosted tool
 * cannot diagnose its own dead process. HA's independent diagnostics download covers that boundary.
 */
final class HomeAssistantProviderDiagnostics implements HomeAssistantReadDiagnostics {
  private final HomeAssistantResourceSource source;
  private final HomeAssistantDiscoveryPolicy policy;
  private volatile BooleanSupplier catalogue;
  private volatile Instant sdkSuccess;

  HomeAssistantProviderDiagnostics(
      HomeAssistantResourceSource source, HomeAssistantDiscoveryPolicy policy) {
    this.source = Objects.requireNonNull(source);
    this.policy = Objects.requireNonNull(policy);
  }

  void attach(BooleanSupplier catalogue) {
    if (this.catalogue != null)
      throw new IllegalStateException("Diagnostic provider already attached");
    this.catalogue = Objects.requireNonNull(catalogue);
  }

  /** Recorded only after a public SDK lifecycle operation returns successfully. */
  void sdkSucceeded() {
    sdkSuccess = Instant.now();
  }

  @Override
  public Snapshot snapshot() {
    var observed = catalogue;
    return new Snapshot(
        Instant.now(),
        source.connection().connected()
            ? ConnectionObservation.AUTHENTICATED_CONNECTION
            : ConnectionObservation.UNAVAILABLE,
        observed == null
            ? CatalogueObservation.NOT_OBSERVED
            : observed.getAsBoolean()
                ? CatalogueObservation.CONFIRMED_LOCAL_LEASE
                : CatalogueObservation.UNAVAILABLE,
        Optional.ofNullable(sdkSuccess));
  }

  @Override
  public Optional<Set<String>> permittedAttributes(Reference reference, Revision revision) {
    if (!policy.binding().equals(revision.binding())
        || !policy.revision().equals(revision.policy())) return Optional.empty();
    return policy.selection(reference).map(HomeAssistantDiscoveryPolicy.Selection::attributes);
  }
}
