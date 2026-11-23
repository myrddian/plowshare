package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReading;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResourceSummary;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One bounded native collection already filtered by the configured resource/field policy. JSON,
 * credentials, registry identities and unselected attributes never cross this boundary. Values
 * belong to this observation only; a catalogue must not reuse them as a later fresh read.
 */
public interface HomeAssistantCapabilitySource extends AutoCloseable {
  record Observation(ResourceSummary resource, List<FieldReading> readings) {
    public Observation {
      Objects.requireNonNull(resource);
      readings = List.copyOf(readings);
      if (readings.size() > HomeAssistantCapabilities.MAX_FIELDS
          || readings.stream().map(value -> value.field().id()).distinct().count()
              != readings.size()
          || readings.stream()
              .anyMatch(
                  value ->
                      !value.resourceId().equals(resource.resourceId())
                          || !value.entityId().equals(resource.entityId())))
        throw new IllegalArgumentException("Invalid capability observation");
      if (!readings.stream().map(FieldReading::field).toList().equals(resource.matchedFields()))
        throw new IllegalArgumentException("Capability fields disagree with observations");
    }
  }

  record Snapshot(
      String epoch, String policyFingerprint, Instant observedAt, List<Observation> observations) {
    public Snapshot {
      HomeAssistantCapabilities.text(epoch, 256);
      HomeAssistantCapabilities.revision(policyFingerprint);
      Objects.requireNonNull(observedAt);
      observations = List.copyOf(observations);
      if (observations.size() > HomeAssistantCapabilities.MAX_RESOURCES
          || observations.stream().map(value -> value.resource().resourceId()).distinct().count()
              != observations.size())
        throw new IllegalArgumentException("Invalid capability collection");
    }
  }

  Snapshot snapshot(OperationBudget budget) throws IOException;

  @Override
  void close() throws IOException;
}
