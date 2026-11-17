package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.integrations.ha.HomeAssistantDiscoveryPolicy.Metadata;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.State;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed boundary to one configured native HA connection. Vendor JSON stays at the connection's
 * codec. A source owns its credential generation; a catalogue cannot reuse another source's
 * snapshots merely because both connections have the same URL.
 */
public interface HomeAssistantResourceSource {
  record Connection(String epoch, long metadataVersion, long stateVersion, boolean connected) {
    public Connection {
      IntegrationContracts.identity(epoch, 256);
      if (metadataVersion < 0 || stateVersion < 0)
        throw new IllegalArgumentException("negative HA source revision");
    }
  }

  record Selection(
      Reference reference, String entityId, Set<String> attributes, Metadata metadata) {
    public Selection {
      Objects.requireNonNull(reference);
      HomeAssistantResources.haName(entityId);
      attributes = HomeAssistantResources.names(attributes, HomeAssistantResources.MAX_ATTRIBUTES);
      Objects.requireNonNull(metadata);
    }
  }

  record MetadataSnapshot(Connection connection, List<Description> descriptions) {
    public MetadataSnapshot {
      Objects.requireNonNull(connection);
      descriptions = List.copyOf(descriptions);
      if (descriptions.size() > HomeAssistantResources.MAX_RESOURCES)
        throw new IllegalArgumentException("HA metadata snapshot exceeds its bound");
    }
  }

  record StateSnapshot(Connection connection, List<State> states) {
    public StateSnapshot {
      Objects.requireNonNull(connection);
      states = List.copyOf(states);
      if (states.size() > HomeAssistantResources.MAX_RESOURCES)
        throw new IllegalArgumentException("HA state snapshot exceeds its bound");
    }
  }

  Binding binding();

  /** In-memory connection generation and opaque invalidation versions; never a permission lease. */
  Connection connection();

  /** One bounded set of collection requests, never one network request per selected entity. */
  MetadataSnapshot metadata(List<Selection> selections, OperationBudget budget) throws IOException;

  /** One native get_states batch. No stale cached value may be returned as a fresh read. */
  StateSnapshot read(List<Selection> selections, OperationBudget budget) throws IOException;
}
