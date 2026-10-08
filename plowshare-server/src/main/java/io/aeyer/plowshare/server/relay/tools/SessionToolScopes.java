package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ToolScopes;
import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.agents.ToolGrants;
import io.aeyer.plowshare.server.applications.ApplicationToolScopes;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import java.util.function.Supplier;

/**
 * Socket-bound provider scopes. Interactive owners assign connected tools directly; Applications
 * additionally require manifest execution identity and explicit scope-to-agent assignments.
 * Restart/disconnection drops availability. No effect is submitted or replayed by this service.
 */
public final class SessionToolScopes implements ToolScopeConnections {
  private record Key(String project, String account, String scope) {}

  private record Attached(
      ToolScopes.Connection value, String session, String socket, boolean application) {}

  private final Map<Key, Attached> attached = new LinkedHashMap<>();
  private final ApplicationResources resources;
  private final ProjectWorkspaces projects;
  private final ProjectMembers members;
  private final ToolGrants definitions;
  private final Sessions sessions;
  private final Supplier<Set<String>> builtins;

  public SessionToolScopes(
      ApplicationResources resources,
      ProjectWorkspaces projects,
      ProjectMembers members,
      ToolGrants definitions,
      Sessions sessions,
      Supplier<Set<String>> builtins) {
    this.resources = Objects.requireNonNull(resources);
    this.projects = Objects.requireNonNull(projects);
    this.members = Objects.requireNonNull(members);
    this.definitions = Objects.requireNonNull(definitions);
    this.sessions = Objects.requireNonNull(sessions);
    this.builtins = Objects.requireNonNull(builtins);
  }

  private void owner(String project, String account) {
    var personal = projects.personalOwner(project);
    if (account == null
        || projects.id(project) == null
        || !members.mayWork(project, account)
        || (personal.isPresent() && !personal.get().equals(account)))
      throw new CallerFault("Tool scope requires authenticated project work access");
  }

  private Optional<ApplicationToolScopes> application(String project) {
    return resources.root(projects.id(project)).map(ApplicationToolScopes::read);
  }

  private boolean authorized(Attached scope) {
    var value = scope.value();
    owner(value.project(), value.account());
    var policy = application(value.project());
    if (scope.application() != policy.isPresent()) return false;
    if (policy.isEmpty()) return true;
    var app = policy.get();
    if (!value.account().equals(app.executionAccount())) return false;
    return app.toolScopes().stream()
        .anyMatch(
            s ->
                s.scope().equals(value.scope())
                    && s.provider().equals(value.sourceProvider())
                    && s.grants().equals(value.grants())
                    && value.agents().stream()
                        .allMatch(
                            a ->
                                app.toolGrants()
                                    .contains(new ApplicationToolScopes.Assignment(s.scope(), a))));
  }

  @Override
  public ToolScopes.Connection connect(
      String account, String session, String socket, ToolScopes.Connect request) {
    owner(request.project(), account);
    if (session == null || socket == null || !sessions.live(account, session, socket))
      throw new CallerFault("Tool scope requires the authenticated live event socket");
    if (builtins.get().stream().anyMatch(n -> n.startsWith(request.prefix())))
      throw new CallerFault("Provider prefix overlaps built-in tools");
    var root = resources.root(projects.id(request.project()));
    var staticSettings =
        root.map(
                r ->
                    io.aeyer.plowshare.server.applications.ApplicationServerSettings.read(
                        request.project(), r))
            .orElse(io.aeyer.plowshare.server.applications.ApplicationServerSettings.EMPTY);
    if (staticSettings.providers().stream()
            .anyMatch(
                p ->
                    p.prefix().startsWith(request.prefix())
                        || request.prefix().startsWith(p.prefix()))
        || staticSettings.tools().stream().anyMatch(t -> t.name().startsWith(request.prefix())))
      throw new CallerFault("Runtime scope prefix overlaps an application provider");
    if (request.grants().stream().anyMatch(g -> !g.equals("*") && !g.startsWith(request.prefix())))
      throw new CallerFault("Scope grants must belong to the provider prefix");
    String route =
        "p-"
            + RelayPort.hash(
                    request.project()
                        + "\n"
                        + account
                        + "\n"
                        + request.scope()
                        + "\n"
                        + socket
                        + "\n"
                        + UUID.randomUUID())
                .substring(0, 32);
    var value =
        new ToolScopes.Connection(
            request.project(),
            request.scope(),
            request.provider(),
            route,
            account,
            request.prefix(),
            request.grants(),
            request.agents(),
            request.leaseSeconds());
    var candidate =
        new Attached(value, session, socket, application(request.project()).isPresent());
    if (!authorized(candidate))
      throw new CallerFault(
          "Application scope exceeds its explicit execution identity or assignments");
    // Definition resolution may consult this registry while holding its cache lock. Resolve
    // outside our monitor, then serialize only attachment changes to avoid lock inversion.
    for (String agent : value.agents())
      if (!definitions.acceptsDynamic(projects.id(value.project()), account, agent, session))
        throw new CallerFault("Assigned agent must exist and opt in with dynamic: true");
    synchronized (this) {
      Key key = new Key(value.project(), account, value.scope());
      Attached previous = attached.get(key);
      if (previous != null && sessions.live(account, previous.session(), previous.socket())) {
        var existing = previous.value();
        if (previous.session().equals(session)
            && previous.socket().equals(socket)
            && new ToolScopes.Connect(
                    existing.project(),
                    existing.scope(),
                    existing.sourceProvider(),
                    existing.prefix(),
                    existing.grants(),
                    existing.agents(),
                    existing.leaseSeconds())
                .equals(request)) return existing;
        throw new CallerFault("Disconnect the existing scope before changing its connection");
      }
      attached.values().removeIf(a -> !sessions.live(a.value().account(), a.session(), a.socket()));
      if (attached.size() >= 4096
          || attached.keySet().stream()
                  .filter(k -> k.project().equals(value.project()) && k.account().equals(account))
                  .count()
              >= 32 - staticSettings.providers().size())
        throw new CallerFault("Tool scope connection limit exceeded");
      for (var other : attached.values()) {
        if (other.value().project().equals(value.project())
            && other.value().account().equals(account)
            && (other.value().prefix().startsWith(value.prefix())
                || value.prefix().startsWith(other.value().prefix())))
          throw new CallerFault("Provider prefixes must not overlap for this owner");
      }
      attached.put(key, candidate);
      return value;
    }
  }

  @Override
  public synchronized List<ToolScopes.Connection> connections(String project, String account) {
    return attached.values().stream()
        .filter(
            a ->
                a.value().project().equals(project)
                    && (account == null || a.value().account().equals(account))
                    && sessions.live(a.value().account(), a.session(), a.socket()))
        .filter(
            a -> {
              try {
                return authorized(a);
              } catch (CallerFault refused) {
                return false;
              }
            })
        .map(Attached::value)
        .toList();
  }

  @Override
  public synchronized boolean permits(
      ToolScopes.Connection connection, String account, String agent, String session, String tool) {
    var scope = attached.get(new Key(connection.project(), account, connection.scope()));
    return scope != null
        && scope.value().equals(connection)
        && authorized(scope)
        && (scope.application() || Objects.equals(scope.session(), session))
        && connection.agents().contains(agent)
        && (connection.grants().contains("*") || connection.grants().contains(tool));
  }

  @Override
  public ToolScopes.Connections list(String account, String session, ToolScopes.Query request) {
    owner(request.project(), account);
    return new ToolScopes.Connections(connections(request.project(), account));
  }

  @Override
  public synchronized ToolScopes.Disconnected disconnect(
      String account, String session, ToolScopes.Disconnect request) {
    owner(request.project(), account);
    Key key = new Key(request.project(), account, request.scope());
    var previous = attached.get(key);
    if (previous != null && !previous.application() && !Objects.equals(session, previous.session()))
      throw new CallerFault("Scope belongs to another interactive session");
    return new ToolScopes.Disconnected(
        request.project(), request.scope(), attached.remove(key) != null);
  }
}
