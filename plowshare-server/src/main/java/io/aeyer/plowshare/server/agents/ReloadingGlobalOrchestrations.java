package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.data.DataLayout;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Global orchestration refresh follows both file changes and the current global agent graph. */
public final class ReloadingGlobalOrchestrations implements GlobalOrchestrationDefinitions {
  private final DataLayout data;
  private final GlobalAgentDefinitions agents;
  private final Set<String> tools;
  private final DefinitionChecks checks;
  private final OrchestrationRegistry.Loaded packaged;
  private final DefinitionSource shipped;
  private List<DefinitionSource.Definition> stamp;
  private AgentRegistry inherited;
  private OrchestrationRegistry.Loaded snapshot;

  public ReloadingGlobalOrchestrations(
      DataLayout data,
      GlobalAgentDefinitions agents,
      Set<String> tools,
      DefinitionChecks checks,
      OrchestrationRegistry.Loaded packaged,
      DefinitionSource shipped) {
    this.data = Objects.requireNonNull(data);
    this.agents = Objects.requireNonNull(agents);
    this.tools = Set.copyOf(tools);
    this.checks = Objects.requireNonNull(checks);
    this.packaged = Objects.requireNonNull(packaged);
    this.shipped = DefinitionSource.snapshot(shipped);
  }

  @Override
  public synchronized OrchestrationRegistry.Loaded current() {
    if (!data.keepsAnything()) return packaged;
    var files =
        DefinitionSource.snapshot(new FilesystemDefinitions(data.orchestrationsFor(null), true));
    var observed = files.list();
    var currentAgents = agents.current();
    if (observed.equals(stamp) && inherited == currentAgents) return snapshot;
    stamp = null;
    inherited = null;
    snapshot = null;
    var next =
        OrchestrationRegistry.read(
            List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.GLOBAL, files),
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SHIPPED, shipped)),
            tools,
            currentAgents,
            checks);
    snapshot = next;
    inherited = currentAgents;
    stamp = observed;
    return next;
  }
}
