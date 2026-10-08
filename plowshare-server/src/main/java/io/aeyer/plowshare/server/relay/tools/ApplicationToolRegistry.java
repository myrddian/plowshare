package io.aeyer.plowshare.server.relay.tools;

import static io.aeyer.plowshare.server.agents.ToolFailure.Code.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.applications.ApplicationServerSettings;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectNames;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.relay.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

/**
 * Application authority and provider catalogues are read live, never copied into global boot tools.
 * Catalogue publication is an authenticated Relay effect; newest retained position wins. A lease
 * expires availability, not knowledge of the schema. An empty catalogue withdraws its tools.
 */
public final class ApplicationToolRegistry implements ScopedTools, RelayToolAuthority {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ApplicationToolRegistry.class);
  private final ApplicationResources resources;
  private final ProjectWorkspaces projects;
  private final ProjectMembers members;
  private final ProjectNames projectNames;
  private final RelayLogRepository logs;
  private final RelayToolProperties boot;
  private final RelayToolInvocations invocations;
  private final ToolGrants grants;
  private final Supplier<Set<String>> builtins;
  private final Supplier<Instant> clock;
  private final ToolScopeConnections connections;

  public ApplicationToolRegistry(
      ApplicationResources resources,
      ProjectWorkspaces projects,
      ProjectMembers members,
      ProjectNames projectNames,
      RelayLogRepository logs,
      RelayToolProperties boot,
      RelayToolInvocations invocations,
      ToolGrants grants,
      Supplier<Set<String>> builtins,
      Supplier<Instant> clock) {
    this(
        resources,
        projects,
        members,
        projectNames,
        logs,
        boot,
        invocations,
        grants,
        builtins,
        clock,
        ToolScopeConnections.NONE);
  }

  public ApplicationToolRegistry(
      ApplicationResources resources,
      ProjectWorkspaces projects,
      ProjectMembers members,
      ProjectNames projectNames,
      RelayLogRepository logs,
      RelayToolProperties boot,
      RelayToolInvocations invocations,
      ToolGrants grants,
      Supplier<Set<String>> builtins,
      Supplier<Instant> clock,
      ToolScopeConnections connections) {
    this.resources = Objects.requireNonNull(resources);
    this.projects = Objects.requireNonNull(projects);
    this.members = Objects.requireNonNull(members);
    this.projectNames = Objects.requireNonNull(projectNames);
    this.logs = Objects.requireNonNull(logs);
    this.boot = Objects.requireNonNull(boot);
    this.invocations = Objects.requireNonNull(invocations);
    this.grants = Objects.requireNonNull(grants);
    this.builtins = Objects.requireNonNull(builtins);
    this.clock = Objects.requireNonNull(clock);
    this.connections = Objects.requireNonNull(connections);
  }

  private ApplicationServerSettings settings(String project) {
    Long id = projects.id(project);
    return id == null
        ? ApplicationServerSettings.EMPTY
        : resources
            .root(id)
            .map(root -> checked(project, root, builtins.get()))
            .orElse(ApplicationServerSettings.EMPTY);
  }

  private static ApplicationServerSettings checked(
      String project, Path root, Set<String> builtins) {
    var settings = ApplicationServerSettings.read(project, root);
    if (settings.tools().stream().anyMatch(t -> builtins.contains(t.name())))
      throw new CallerFault("Application tools cannot replace built-in tool names");
    if (settings.ports().stream().anyMatch(p -> p.topic().startsWith("tool.")))
      throw new CallerFault("Tool topics require tool declarations, not general port grants");
    var providers = settings.providers();
    for (int i = 0; i < providers.size(); i++) {
      var provider = providers.get(i);
      if (builtins.stream().anyMatch(n -> n.startsWith(provider.prefix())))
        throw new CallerFault("Application provider prefix overlaps built-in tools");
      for (int j = 0; j < i; j++)
        if (provider.prefix().startsWith(providers.get(j).prefix())
            || providers.get(j).prefix().startsWith(provider.prefix()))
          throw new CallerFault("Application provider prefixes must not overlap");
      for (var tool : settings.tools())
        if ((tool.provider().equals(provider.provider())
                && (!tool.account().equals(provider.account())
                    || !tool.name().startsWith(provider.prefix())))
            || (tool.name().startsWith(provider.prefix())
                && !tool.provider().equals(provider.provider())))
          throw new CallerFault("Application tool does not match its provider authority");
    }
    return settings;
  }

  private record Entry(
      RelayToolDefinition binding,
      boolean available,
      Optional<io.aeyer.plowshare.protocol.ToolScopes.Connection> scope) {}

  private record Snapshot(Map<String, Entry> entries, String revision) {
    Snapshot {
      entries = Map.copyOf(entries);
    }
  }

  private Snapshot snapshot(String project) {
    return snapshot(project, settings(project));
  }

  private ApplicationServerSettings settings(String project, String account) {
    var base = settings(project);
    var providers = new ArrayList<>(base.providers());
    connections
        .connections(project, account)
        .forEach(
            c ->
                providers.add(
                    new ApplicationServerSettings.Provider(
                        c.provider(), c.account(), c.prefix(), c.leaseSeconds())));
    return new ApplicationServerSettings(base.tools(), base.ports(), providers);
  }

  private Snapshot snapshot(String project, String account) {
    return snapshot(project, settings(project, account));
  }

  private boolean assigned(Entry entry, String account, String agent, String session) {
    try {
      Long id = projects.id(entry.binding().project());
      if (entry.scope().isPresent())
        return grants.acceptsDynamic(id, account, agent, session)
            && connections.permits(
                entry.scope().get(), account, agent, session, entry.binding().name());
      var policy =
          resources
              .root(id)
              .map(io.aeyer.plowshare.server.applications.ApplicationToolScopes::read);
      if (policy.isPresent() && !policy.get().toolScopes().isEmpty())
        return account.equals(entry.binding().account())
            && grants.acceptsDynamic(id, account, agent, session)
            && policy
                .get()
                .permits(account, agent, entry.binding().provider(), entry.binding().name());
      return grants.permits(id, account, agent, session, entry.binding().name());
    } catch (CallerFault refused) {
      return false; // Unreadable authority never preserves a prior grant.
    }
  }

  private Snapshot snapshot(String project, ApplicationServerSettings settings) {
    Map<String, Entry> entries = new TreeMap<>();
    settings
        .tools()
        .forEach(
            t ->
                entries.put(
                    t.name(),
                    new Entry(
                        t,
                        settings.providers().stream()
                            .noneMatch(p -> p.provider().equals(t.provider())),
                        Optional.empty())));
    StringBuilder revision = new StringBuilder(settings.toString());
    Long id = projects.id(project);
    for (var provider : settings.providers()) {
      var latest = logs.latest(new Relay.TopicKey(id, provider.topic()));
      if (latest.position() == 0) continue;
      // Expired broker history is unavailable authority, not permission to revive old tools.
      entries.values().removeIf(e -> e.binding().provider().equals(provider.provider()));
      revision.append(latest.position());
      if (latest.publication().isEmpty()) continue;
      if (latest.publication().get().position() != latest.position())
        throw new CallerFault("The newest tool catalogue is unavailable");
      var publication = latest.publication().get();
      if (!publication.event().publisher().equals(RelayPort.publisher(provider.account()))
          || !(publication.event().payload() instanceof RelayPayload.Text text))
        throw new CallerFault("Application tool catalogue has invalid provider provenance");
      var catalogue = ApplicationServerSettings.catalogue(text.text());
      validateCatalogue(settings, provider, catalogue);
      entries.values().removeIf(e -> e.binding().provider().equals(provider.provider()));
      boolean available =
          clock.get().isBefore(publication.publishedAt().plusSeconds(provider.leaseSeconds()));
      catalogue
          .tools()
          .forEach(
              t ->
                  entries.put(
                      t.name(),
                      new Entry(
                          t.bind(project, provider),
                          available,
                          connections.connections(project, provider.account()).stream()
                              .filter(c -> c.provider().equals(provider.provider()))
                              .findFirst())));
      revision.append(publication.event().eventId());
    }
    return new Snapshot(entries, RelayPort.hash(revision.toString()));
  }

  private void validateCatalogue(
      ApplicationServerSettings settings,
      ApplicationServerSettings.Provider provider,
      ApplicationServerSettings.Catalogue catalogue) {
    var reserved = builtins.get();
    for (var tool : catalogue.tools()) {
      if (!tool.name().startsWith(provider.prefix()) || reserved.contains(tool.name()))
        throw new CallerFault("Tool catalogue exceeds its authorized name prefix");
      var declared =
          settings.tools().stream().filter(t -> t.name().equals(tool.name())).findFirst();
      if (declared.isPresent() && !declared.get().provider().equals(provider.provider()))
        throw new CallerFault("Tool catalogue conflicts with another provider");
    }
  }

  @Override
  public Set<String> stagedNames(String project, Path root, Set<String> builtins) {
    return checked(project, root, ScopedTools.reservedNames(builtins)).tools().stream()
        .map(RelayToolDefinition::name)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private String project(Long id) {
    return projectNames.nameForId(id).orElse(null);
  }

  @Override
  public Set<String> names(Long id) {
    String project = id == null ? null : project(id);
    return project == null ? Set.of() : snapshot(project).entries().keySet();
  }

  @Override
  public String revision(Long id) {
    String project = id == null ? null : project(id);
    return project == null
        ? ""
        : RelayPort.hash(
            snapshot(project).revision() + connections.connections(project, null).toString());
  }

  @Override
  public List<AgentTool> tools(Home home, String agent, String session, BooleanSupplier cancelled) {
    if (home == null || home.isGlobal()) return List.of();
    return snapshot(home.project()).entries().values().stream()
        .map(e -> (AgentTool) new LiveTool(e, agent, session, cancelled))
        .toList();
  }

  @Override
  public List<AgentTool> tools(
      Home home, String agent, String session, BooleanSupplier cancelled, String account) {
    if (account == null) return tools(home, agent, session, cancelled);
    if (home == null || home.isGlobal()) return List.of();
    return snapshot(home.project(), account).entries().values().stream()
        .filter(e -> assigned(e, account, agent, session))
        .map(e -> (AgentTool) new LiveTool(e, agent, session, cancelled))
        .toList();
  }

  @Override
  public Optional<ToolFailure> checkAccess(
      Home home, String agent, String session, String tool, UsageAttribution owner) {
    if (home == null || home.isGlobal()) return Optional.empty();
    if (owner == null || owner.accountHandle() == null || !home.project().equals(owner.projectId()))
      return Optional.of(
          new ToolFailure(E_NO_ACCESS, "This run cannot access the tool. Try another tool."));
    try {
      var personal = projects.personalOwner(home.project());
      if (personal.isPresent() && !personal.get().equals(owner.accountHandle())
          || !members.mayWork(home.project(), owner.accountHandle())
          || !(grants.permits(
                  projects.id(home.project()), owner.accountHandle(), agent, session, tool)
              || Optional.ofNullable(
                      snapshot(home.project(), owner.accountHandle()).entries().get(tool))
                  .map(e -> assigned(e, owner.accountHandle(), agent, session))
                  .orElse(false)))
        return Optional.of(
            new ToolFailure(
                E_NO_ACCESS,
                "The account or agent no longer has this tool grant. Try another tool."));
      return Optional.empty();
    } catch (CallerFault refused) {
      return Optional.of(
          new ToolFailure(E_NO_ACCESS, "The agent's tool grant is unavailable. Try another tool."));
    }
  }

  @Override
  public boolean permits(
      String account,
      String project,
      String topic,
      RelayPortProperties.Direction direction,
      String group) {
    if (boot.permits(account, project, topic, direction, group)) return true;
    var settings = settings(project, account);
    if (settings.ports().stream()
        .anyMatch(
            b ->
                b.account().equals(account)
                    && b.topic().equals(topic)
                    && b.direction() == direction
                    && (direction == RelayPortProperties.Direction.INGRESS
                        || group == null
                        || b.groups().contains(group)))) return true;
    if (settings.providers().stream()
        .anyMatch(
            p ->
                p.account().equals(account)
                    && p.topic().equals(topic)
                    && (direction == RelayPortProperties.Direction.INGRESS
                        || group == null
                        || RelayToolDefinition.group().equals(group)))) return true;
    var properties = new RelayToolProperties();
    properties.setBindings(
        snapshot(project, settings).entries().values().stream().map(Entry::binding).toList());
    return properties.permits(account, project, topic, direction, group);
  }

  @Override
  public void validateResult(String account, RelayPort.Publish request) {
    var settings = settings(request.project(), account);
    var provider =
        settings.providers().stream()
            .filter(p -> p.account().equals(account) && p.topic().equals(request.topic()))
            .findFirst();
    if (provider.isPresent()) {
      if (request.parentTopic() != null)
        throw new CallerFault("Tool catalogue must be an independent publication");
      validateCatalogue(
          settings, provider.get(), ApplicationServerSettings.catalogue(request.text()));
      return;
    }
    boot.validateResult(account, request);
    var properties = new RelayToolProperties();
    properties.setBindings(
        snapshot(request.project(), settings).entries().values().stream()
            .map(Entry::binding)
            .toList());
    properties.validateResult(account, request);
  }

  private static String failure(ToolFailure.Code code, String message) {
    return new ToolFailure(code, message).render();
  }

  private final class LiveTool implements AgentTool {
    private final RelayToolDefinition offered;
    private final Entry entry;
    private final String agent;
    private final String session;
    private final BooleanSupplier cancelled;
    private String call;

    LiveTool(Entry entry, String agent, String session, BooleanSupplier cancelled) {
      this.entry = entry;
      this.offered = entry.binding();
      this.agent = agent;
      this.session = session;
      this.cancelled = cancelled;
    }

    @Override
    public ToolSchema schema() {
      return offered.schema();
    }

    @Override
    public void calledAs(String call) {
      this.call = call;
    }

    @Override
    public String run(String arguments, Home home) {
      return failure(E_NO_ACCESS, "An authenticated run owner is required. Try another tool.");
    }

    @Override
    public String run(String arguments, Home home, UsageAttribution owner) {
      if (owner == null
          || owner.accountHandle() == null
          || !offered.project().equals(home.project())
          || !offered.project().equals(owner.projectId()))
        return failure(E_NO_ACCESS, "This run cannot access the tool. Try another tool.");
      try {
        var personal = projects.personalOwner(home.project());
        if (personal.isPresent() && !personal.get().equals(owner.accountHandle())
            || !members.mayWork(home.project(), owner.accountHandle())
            || !members.mayWork(home.project(), offered.account()))
          return failure(E_NO_ACCESS, "Project or provider access was revoked. Try another tool.");
        if (!assigned(entry, owner.accountHandle(), agent, session))
          return failure(
              E_NO_ACCESS, "The provider scope or agent grant was revoked. Try another tool.");
        if (entry.scope().isPresent()
            && connections.connections(home.project(), owner.accountHandle()).stream()
                .noneMatch(entry.scope().get()::equals))
          return failure(E_NO_CONNECTION, "The provider scope is disconnected. Try another tool.");
        var current = snapshot(home.project(), owner.accountHandle()).entries().get(offered.name());
        if (current == null) return failure(E_NO_EXEC, "The tool was withdrawn. Try another tool.");
        var denied = checkAccess(home, agent, session, offered.name(), owner);
        if (denied.isPresent()) return denied.get().render();
        if (!current.binding().equals(offered))
          return failure(
              E_NO_EXEC, "The tool definition changed. Refresh the tool list before calling it.");
        if (!current.available())
          return failure(
              E_NO_CONNECTION, "The provider catalogue lease expired. Try another tool.");
        if (call == null) return failure(E_NO_EXEC, "The model call identity is unavailable.");
        var result =
            invocations.invoke(
                current.binding(), RelayToolCodec.arguments(arguments), owner, call, cancelled);
        return switch (result.state()) {
          case COMPLETED -> "Invocation " + result.id() + ": COMPLETED\n" + result.text();
          case REJECTED -> failure(E_NO_EXEC, "Invocation " + result.id() + ": " + result.text());
          case UNKNOWN ->
              failure(
                  E_NO_CONNECTION,
                  "Invocation "
                      + result.id()
                      + ": "
                      + result.text()
                      + "\nReconcile this invocation with relay_tool_read; do not repeat an uncertain effect.");
        };
      } catch (CallerFault | IllegalArgumentException invalid) {
        return failure(E_NO_EXEC, invalid.getMessage());
      } catch (RuntimeException failed) {
        log.warn("External tool execution failed for {}", offered.name(), failed);
        return failure(
            E_GENERAL_TOOL_FAILURE,
            "The tool could not complete. Check the server diagnostic and reconcile any submitted invocation before retrying.");
      }
    }
  }
}
