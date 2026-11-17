package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit, revocable host control ceiling, separate from discovery and server-owned tool grants.
 * Replacing or disabling it prevents subsequent local admissions. An admission already accepted at
 * the final pre-send check may complete; this class promises no rollback or remote cancellation.
 */
public final class HomeAssistantActionPolicy {
  private final Binding binding;
  private Map<String, HomeAssistantSettings.Action> permitted;
  private long generation;

  public HomeAssistantActionPolicy(
      Binding binding, Map<String, HomeAssistantSettings.Action> permitted) {
    this.binding = Objects.requireNonNull(binding);
    replace(permitted);
  }

  public synchronized void replace(Map<String, HomeAssistantSettings.Action> replacement) {
    Map<String, HomeAssistantSettings.Action> selected = Map.copyOf(replacement);
    if (selected.size() > HomeAssistantResources.MAX_ACTIONS)
      throw new IllegalArgumentException("Action policy exceeds its bound");
    selected.forEach(
        (name, profile) -> {
          IntegrationContracts.identity(name, 256);
          HomeAssistantControlServices.requireSupported(profile);
          if (profile.targets().isEmpty() || profile.targets().size() > ActionExecutor.MAX_TARGETS)
            throw new IllegalArgumentException(
                "Executable control profiles require 1..20 fixed targets");
        });
    if (generation == Long.MAX_VALUE)
      throw new IllegalStateException("Action policy generation exhausted");
    permitted = selected;
    generation++;
  }

  public synchronized void disable() {
    replace(Map.of());
  }

  public synchronized long admit(ConfiguredAction action) throws IOException {
    require(action, generation);
    return generation;
  }

  public synchronized void require(ConfiguredAction action, long admittedGeneration)
      throws IOException {
    if (generation != admittedGeneration
        || !binding.equals(action.revision().binding())
        || !action.profile().equals(permitted.get(action.alias())))
      throw new IOException("HA action is unavailable under the current local control policy");
  }
}
