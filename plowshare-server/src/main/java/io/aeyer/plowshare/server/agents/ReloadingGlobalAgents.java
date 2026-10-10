package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.data.DataLayout;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Refreshes the operator-owned global tier; callers and caches retain immutable snapshots. */
public final class ReloadingGlobalAgents implements GlobalAgentDefinitions {
  private final DataLayout data;
  private final Set<String> tools;
  private final DefinitionChecks checks;
  private final AgentGuidance guidance;
  private final DefinitionSource shipped;
  private final AgentRegistry packaged;
  private List<DefinitionSource.Definition> stamp;
  private AgentRegistry snapshot;

  public ReloadingGlobalAgents(
      DataLayout data,
      Set<String> tools,
      DefinitionChecks checks,
      AgentGuidance guidance,
      AgentRegistry packaged,
      DefinitionSource shipped) {
    this.data = Objects.requireNonNull(data);
    this.tools = Set.copyOf(tools);
    this.checks = Objects.requireNonNull(checks);
    this.guidance = Objects.requireNonNull(guidance);
    this.packaged = Objects.requireNonNull(packaged);
    // Freeze resources once. A file edit changes the writable tier, never the installed release.
    this.shipped = DefinitionSource.snapshot(shipped);
  }

  @Override
  public synchronized AgentRegistry current() {
    if (!data.keepsAnything()) return packaged;
    var agents = DefinitionSource.snapshot(new FilesystemDefinitions(data.agentsFor(null)));
    var bots = DefinitionSource.snapshot(new FilesystemDefinitions(data.botsFor(null)));
    var observed =
        java.util.stream.Stream.concat(agents.list().stream(), bots.list().stream()).toList();
    if (observed.equals(stamp)) return snapshot;
    // Failed replacements cannot continue serving old grants. Loading uses these exact bytes.
    stamp = null;
    snapshot = null;
    AgentsConfig.requireNoClash(agents, bots);
    var source = new LayeredDefinitions(List.of(agents, bots, shipped));
    var loaded = checks.applyTo(AgentRegistry.read(source, tools, Set.of()), source.describe());
    var next = new AgentRegistry(loaded, guidance);
    snapshot = next;
    stamp = observed;
    return next;
  }

  @Override
  public synchronized void invalidate() {
    stamp = null;
    snapshot = null;
  }
}
