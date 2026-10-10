package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.applications.ApplicationServerSettings;
import io.aeyer.plowshare.server.archive.ProjectCatalogue;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Enrolls only explicitly declared Application accounts with current CONTRIBUTOR authority. */
public final class ApplicationRelayWorkerBindings implements RelayWorkerBindings {
  private static final Logger LOG = LoggerFactory.getLogger(ApplicationRelayWorkerBindings.class);
  private final ProjectCatalogue projects;
  private final ApplicationResources resources;
  private final ProjectMembers members;
  private final RelayWorkerProperties boot;

  public ApplicationRelayWorkerBindings(
      ProjectCatalogue projects,
      ApplicationResources resources,
      ProjectMembers members,
      RelayWorkerProperties boot) {
    this.projects = Objects.requireNonNull(projects);
    this.resources = Objects.requireNonNull(resources);
    this.members = Objects.requireNonNull(members);
    this.boot = Objects.requireNonNull(boot);
  }

  @Override
  public List<RelayWorkerProperties.Project> current() {
    var declared = new LinkedHashMap<String, RelayWorkerProperties.Project>();
    for (var binding : boot.getProjects()) declared.put(binding.project(), binding);
    for (var project : projects.all()) {
      try {
        var id = projects.id(project.name());
        var root = resources.root(id);
        if (root.isEmpty()) continue;
        resources.directory(
            id, "server"); // Validate the complete declaration tier against its fence.
        var worker = ApplicationServerSettings.read(project.name(), root.get()).worker();
        if (worker.isEmpty()) continue;
        var binding = new RelayWorkerProperties.Project(project.name(), worker.get().account());
        if (!members.mayWork(binding.project(), binding.account())) {
          declared.remove(binding.project());
          continue;
        }
        var previous = declared.putIfAbsent(binding.project(), binding);
        if (previous != null && !previous.equals(binding)) {
          // Neither identity wins silently when operator and Application declarations disagree.
          declared.remove(binding.project());
          LOG.warn(
              "Relay worker binding conflict for project {}; enrollment refused", project.name());
        }
      } catch (CallerFault | WorkspaceRefusedException invalid) {
        // A bad replacement removes prior enrollment; no stale Application authority is retained.
        declared.remove(project.name());
        LOG.warn(
            "Relay worker enrollment refused for project {}; Application configuration unavailable",
            project.name());
      }
    }
    if (declared.size() > 32)
      throw new CallerFault("At most 32 Relay worker projects may be enrolled");
    return List.copyOf(declared.values());
  }
}
