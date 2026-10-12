package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.protocol.ProviderCredentials;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ToolScopes;
import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.agents.ToolGrants;
import io.aeyer.plowshare.server.applications.ApplicationServerSettings;
import io.aeyer.plowshare.server.applications.ApplicationToolScopes;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.providers.*;
import io.aeyer.plowshare.server.relay.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Durable assignments use the existing registry, broker and owning invocation ledger. Short
 * transactions lock binding, connection, credential and invocation in that order. A live socket,
 * server incarnation and renewable fence are availability, never a durable promise of execution.
 */
public final class OwnedHostedToolScopes implements HostedToolScopes {
  private final HostedToolScopeRepository repository;
  private final ProviderCredentialAccess credentialAccess;
  private final ProviderCredentialGrants credentials;
  private final ProjectWorkspaces projects;
  private final ProjectMembers members;
  private final AdminStore accounts;
  private final ApplicationResources resources;
  private final ToolGrants definitions;
  private final ToolScopeConnections local;
  private final ToolScopeConnections.Sessions sessions;
  private final Set<String> executors;
  private final Supplier<Set<String>> builtins;
  private final Relay relay;
  private final RelayLogRepository logs;
  private final RelayPortRepository ports;
  private final Supplier<RelayToolRepository> invocations;
  private final Supplier<RelayToolInvocations> results;
  private final UnitOfWork work;
  private final Clock clock;
  private final UUID server;
  private final int maxDepth;

  public OwnedHostedToolScopes(
      HostedToolScopeRepository repository,
      ProviderCredentialAccess credentialAccess,
      ProviderCredentialGrants credentials,
      ProjectWorkspaces projects,
      ProjectMembers members,
      AdminStore accounts,
      ApplicationResources resources,
      ToolGrants definitions,
      ToolScopeConnections local,
      ToolScopeConnections.Sessions sessions,
      Set<String> executors,
      Supplier<Set<String>> builtins,
      Relay relay,
      RelayLogRepository logs,
      RelayPortRepository ports,
      Supplier<RelayToolRepository> invocations,
      Supplier<RelayToolInvocations> results,
      UnitOfWork work,
      Clock clock,
      UUID server,
      int maxDepth) {
    this.repository = repository;
    this.credentialAccess = credentialAccess;
    this.credentials = credentials;
    this.projects = projects;
    this.members = members;
    this.accounts = accounts;
    this.resources = resources;
    this.definitions = definitions;
    this.local = local;
    this.sessions = sessions;
    this.executors = Set.copyOf(executors);
    this.builtins = builtins;
    this.relay = relay;
    this.logs = logs;
    this.ports = ports;
    this.invocations = invocations;
    this.results = results;
    this.work = work;
    this.clock = clock;
    this.server = server;
    this.maxDepth = maxDepth;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(
        clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
  }

  private void active(String actor) {
    if (actor == null || !accounts.isEnabled(actor))
      throw new CallerFault("Hosted scope requires an active account or exact service token");
  }

  private long owner(String project, String actor) {
    active(actor);
    Long id = projects.id(project);
    if (id == null
        || !members.mayWork(project, actor)
        || projects.personalOwner(project).filter(p -> !p.equals(actor)).isPresent())
      throw new CallerFault("Hosted scope requires the current project owner/work membership");
    return id;
  }

  private Optional<ApplicationToolScopes> application(String project) {
    return resources.root(projects.id(project)).map(ApplicationToolScopes::read);
  }

  private void policy(Binding b) {
    owner(b.project(), b.owner());
    var policy = application(b.project());
    if (b.application() != policy.isPresent())
      throw new CallerFault("Hosted scope Application authority changed");
    if (policy.isPresent()) {
      var app = policy.get();
      var d = b.definition();
      if (!b.owner().equals(app.executionAccount())
          || app.toolScopes().stream()
              .noneMatch(
                  s ->
                      s.scope().equals(d.scope())
                          && s.provider().equals(d.provider())
                          && s.grants().equals(d.grants())
                          && d.agents().stream()
                              .allMatch(
                                  a ->
                                      app.toolGrants()
                                          .contains(
                                              new ApplicationToolScopes.Assignment(s.scope(), a)))))
        throw new CallerFault("Hosted scope exceeds Application execution identity or assignments");
    }
    if (!executors.contains(b.definition().executor()))
      throw new CallerFault("Executor is outside configured hosting authority");
    active(b.definition().executor());
    String subject = credentialAccess.subject(b.owner(), target(b));
    if (!subject.equals(b.credentialSubject()))
      throw new CallerFault("Hosted credential subject changed");
  }

  private ProviderCredentials.Target target(Binding b) {
    var d = b.definition();
    return new ProviderCredentials.Target(d.credentialProject(), d.connection(), d.subject());
  }

  private Binding find(UUID id, boolean lock) {
    return repository
        .find(id, lock)
        .orElseThrow(() -> new CallerFault("Hosted binding unavailable"));
  }

  private Binding owned(String actor, String project, UUID id, boolean lock) {
    var b = find(id, lock);
    owner(project, actor);
    if (!project.equals(b.project()) || !actor.equals(b.owner()))
      throw new CallerFault("Hosted binding belongs to another owner or project");
    return b;
  }

  private void enabled(Binding b) {
    if (!b.enabled() || b.deleted()) throw new CallerFault("Hosted binding disabled or deleted");
    policy(b);
  }

  private ProviderCredentialGrants.Reference grant(Binding b) {
    enabled(b);
    return credentials.require(b.owner(), target(b));
  }

  private Binding value(
      UUID id,
      String project,
      String actor,
      long generation,
      boolean enabled,
      boolean deleted,
      Definition d,
      boolean application) {
    String subject =
        credentialAccess.subject(
            actor,
            new ProviderCredentials.Target(d.credentialProject(), d.connection(), d.subject()));
    var route =
        new ToolScopes.Connection(
            project,
            d.scope(),
            d.provider(),
            io.aeyer.plowshare.protocol.HostedToolScopes.route(id, generation),
            d.executor(),
            d.prefix(),
            d.grants(),
            d.agents(),
            d.leaseSeconds());
    return new Binding(
        id, project, actor, generation, enabled, deleted, application, d, subject, route);
  }

  private void assignments(Binding b) {
    policy(b);
    Long id = projects.id(b.project());
    for (String agent : b.definition().agents())
      if (!definitions.acceptsDynamic(id, b.owner(), agent, null))
        throw new CallerFault(
            "Hosted agent must be a server-resolvable definition with dynamic: true");
  }

  private void reservations(Binding b) {
    var d = b.definition();
    if (builtins.get().stream().anyMatch(n -> n.startsWith(d.prefix())))
      throw new CallerFault("Hosted prefix overlaps built-in tools");
    var staticSettings =
        resources
            .root(projects.id(b.project()))
            .map(r -> ApplicationServerSettings.read(b.project(), r))
            .orElse(ApplicationServerSettings.EMPTY);
    if (staticSettings.providers().stream().anyMatch(p -> overlap(p.prefix(), d.prefix()))
        || staticSettings.tools().stream().anyMatch(t -> t.name().startsWith(d.prefix())))
      throw new CallerFault("Hosted prefix overlaps Application provider authority");
    var rows = repository.list(b.project(), b.owner());
    if (rows.size() > 256) throw new CallerFault("Too many retained hosted bindings");
    var others =
        rows.stream().filter(r -> !r.deleted() && !r.binding().equals(b.binding())).toList();
    var localScopes = local.connections(b.project(), b.owner());
    if (others.size() + localScopes.size() + staticSettings.providers().size() >= 32
        || others.stream()
            .anyMatch(
                r ->
                    r.definition().scope().equals(d.scope())
                        || overlap(r.definition().prefix(), d.prefix()))
        || localScopes.stream()
            .anyMatch(r -> r.scope().equals(d.scope()) || overlap(r.prefix(), d.prefix())))
      throw new CallerFault("Hosted scope/name prefix conflicts or owner limit reached");
  }

  private static boolean overlap(String a, String b) {
    return a.startsWith(b) || b.startsWith(a);
  }

  public Receipt save(String actor, Save request) {
    owner(request.project(), actor);
    // Resolve definitions outside row locks: definition-cache invalidation may inspect this
    // registry.
    var candidate =
        value(
            request.binding(),
            request.project(),
            actor,
            request.expectedGeneration() == null ? 1 : request.expectedGeneration() + 1,
            true,
            false,
            request.definition(),
            application(request.project()).isPresent());
    assignments(candidate);
    return work.inTransaction(
        () -> {
          repository.lockProject(request.project());
          owner(request.project(), actor);
          var prior = repository.find(request.binding(), true);
          if (prior.isPresent()
                  && (!prior.get().owner().equals(actor)
                      || !prior.get().project().equals(request.project())
                      || prior.get().deleted())
              || !Objects.equals(
                  prior.map(Binding::generation).orElse(null), request.expectedGeneration()))
            throw new CallerFault("Hosted binding generation/owner changed; inspect status");
          policy(candidate);
          credentials.connection(actor, target(candidate));
          reservations(candidate);
          repository.save(candidate, request.expectedGeneration());
          repository.clearLease(candidate.binding());
          repository.reserve(
              request.requestId(), candidate.binding(), actor, "tool.scope.host.save");
          var receipt = new Receipt(request.requestId(), "tool.scope.host.save", candidate);
          repository.receipt(actor, receipt);
          return receipt;
        });
  }

  public Receipt change(String actor, Change request) {
    owner(request.project(), actor);
    return work.inTransaction(
        () -> {
          repository.lockProject(request.project());
          var prior = owned(actor, request.project(), request.binding(), true);
          if (prior.generation() != request.expectedGeneration() || prior.deleted())
            throw new CallerFault("Hosted binding generation changed or deleted");
          boolean enabled = request.action() == Action.ENABLE;
          var next =
              new Binding(
                  prior.binding(),
                  prior.project(),
                  prior.owner(),
                  prior.generation() + 1,
                  enabled,
                  request.action() == Action.DELETE,
                  prior.application(),
                  prior.definition(),
                  prior.credentialSubject(),
                  new ToolScopes.Connection(
                      prior.project(),
                      prior.definition().scope(),
                      prior.definition().provider(),
                      io.aeyer.plowshare.protocol.HostedToolScopes.route(
                          prior.binding(), prior.generation() + 1),
                      prior.definition().executor(),
                      prior.definition().prefix(),
                      prior.definition().grants(),
                      prior.definition().agents(),
                      prior.definition().leaseSeconds()));
          if (enabled) {
            policy(next);
            reservations(next);
          }
          repository.save(next, prior.generation());
          repository.clearLease(next.binding());
          repository.reserve(request.requestId(), next.binding(), actor, "tool.scope.host.change");
          var receipt = new Receipt(request.requestId(), "tool.scope.host.change", next);
          repository.receipt(actor, receipt);
          return receipt;
        });
  }

  public List<Binding> list(String actor, Query request) {
    owner(request.project(), actor);
    var rows = repository.list(request.project(), actor);
    if (rows.size() > 256)
      throw new CallerFault("Too many retained hosted bindings; inspect a known binding");
    return rows;
  }

  public Binding status(String actor, Status request) {
    return owned(actor, request.project(), request.binding(), false);
  }

  public Receipt receipt(String actor, ReceiptQuery request) {
    owner(request.project(), actor);
    return repository
        .receipt(actor, request.project(), request.requestId())
        .orElseThrow(
            () -> new CallerFault("Hosted receipt absent; absence never authorizes replay"));
  }

  private void trustedExecutor(String actor) {
    active(actor);
    if (!executors.contains(actor)) throw new CallerFault("Executor is no longer configured");
  }

  private void executor(Binding b, String actor) {
    trustedExecutor(actor);
    if (!b.definition().executor().equals(actor))
      throw new CallerFault("Hosted binding is unavailable to this executor");
  }

  private boolean live(HostedToolScopeRepository.Lease l) {
    return l.server().equals(server)
        && now().isBefore(l.registration().expiresAt())
        && sessions.live(
            l.registration().binding().definition().executor(), l.session(), l.socket());
  }

  private HostedToolScopeRepository.Lease worker(
      Binding b, String actor, String session, String socket, UUID registration) {
    executor(b, actor);
    enabled(b);
    var l =
        repository
            .lease(b.binding())
            .filter(
                v ->
                    v.registration().binding().equals(b)
                        && v.registration().registration().equals(registration)
                        && v.session().equals(session)
                        && v.socket().equals(socket)
                        && live(v))
            .orElseThrow(
                () ->
                    new CallerFault(
                        "Hosted worker registration is disconnected, expired or fenced"));
    return l;
  }

  public Registration register(String actor, String session, String socket, Register request) {
    active(actor);
    return work.inTransaction(
        () -> {
          var b = find(request.binding(), true);
          executor(b, actor);
          enabled(b);
          if (b.generation() != request.expectedGeneration()
              || !sessions.live(actor, session, socket))
            throw new CallerFault(
                "Worker needs current binding generation and authenticated socket");
          if (repository.lease(b.binding()).filter(this::live).isPresent())
            throw new CallerFault("A live executor already owns this route; inspect worker status");
          repository.reserve(request.requestId(), b.binding(), actor, "tool.scope.worker.register");
          var time = now();
          var registration =
              new Registration(
                  b, request.requestId(), time, time.plusSeconds(b.definition().leaseSeconds()));
          repository.lease(
              new HostedToolScopeRepository.Lease(
                  registration,
                  session,
                  socket,
                  server,
                  logs.latest(new Relay.TopicKey(projects.id(b.project()), catalogueTopic(b)))
                      .position()));
          return registration;
        });
  }

  public Registration renew(String actor, String session, String socket, Worker request) {
    return work.inTransaction(
        () -> {
          var b = find(request.binding(), true);
          var old = worker(b, actor, session, socket, request.registration());
          var registration =
              new Registration(
                  b,
                  old.registration().registration(),
                  old.registration().registeredAt(),
                  now().plusSeconds(b.definition().leaseSeconds()));
          repository.lease(
              new HostedToolScopeRepository.Lease(
                  registration, session, socket, server, old.catalogueAfter()));
          return registration;
        });
  }

  public WorkerStatus workerStatus(
      String actor, String session, String socket, WorkerQuery request) {
    var b = find(request.binding(), false);
    executor(b, actor);
    var lease =
        repository
            .lease(b.binding())
            .filter(
                l ->
                    l.session().equals(session)
                        && l.socket().equals(socket)
                        && live(l)
                        && b.enabled()
                        && !b.deleted());
    return new WorkerStatus(
        b.binding(), lease.map(HostedToolScopeRepository.Lease::registration).orElse(null));
  }

  private ApplicationServerSettings.Catalogue catalogue(
      Binding b, HostedToolScopeRepository.Lease l) {
    var latest = logs.latest(new Relay.TopicKey(projects.id(b.project()), catalogueTopic(b)));
    var pub =
        latest
            .publication()
            .filter(p -> p.position() == latest.position() && p.position() > l.catalogueAfter())
            .filter(
                p ->
                    !p.publishedAt().isBefore(l.registration().registeredAt().toInstant())
                        && clock
                            .instant()
                            .isBefore(p.publishedAt().plusSeconds(b.definition().leaseSeconds())))
            .orElseThrow(() -> new CallerFault("Hosted catalogue is absent or lease expired"));
    if (!pub.event().publisher().equals(RelayPort.publisher(b.definition().executor()))
        || !(pub.event().payload() instanceof RelayPayload.Text text))
      throw new CallerFault("Hosted catalogue provenance invalid");
    return checkedCatalogue(b, text.text());
  }

  private String catalogueTopic(Binding b) {
    return "tool." + b.route().provider() + ".catalog";
  }

  private ApplicationServerSettings.Catalogue checkedCatalogue(Binding b, String text) {
    var catalogue = ApplicationServerSettings.catalogue(text);
    for (var t : catalogue.tools())
      if (!t.name().startsWith(b.definition().prefix()) || builtins.get().contains(t.name()))
        throw new CallerFault("Hosted catalogue exceeds its reserved prefix");
    return catalogue;
  }

  private RelayToolDefinition tool(Binding b, HostedToolScopeRepository.Lease l, String name) {
    if (!b.definition().grants().contains("*") && !b.definition().grants().contains(name))
      throw new CallerFault("Hosted tool grant withdrawn");
    return catalogue(b, l).tools().stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new CallerFault("Hosted tool withdrawn"))
        .bind(
            b.project(),
            new ApplicationServerSettings.Provider(
                b.route().provider(),
                b.definition().executor(),
                b.definition().prefix(),
                b.definition().leaseSeconds()));
  }

  private HostedToolScopeRepository.Lease available(Binding b) {
    enabled(b);
    return repository
        .lease(b.binding())
        .filter(this::live)
        .orElseThrow(() -> new CallerFault("Hosted executor is unavailable"));
  }

  public List<ToolScopes.Connection> connections(String project, String actor) {
    return repository.list(project, actor).stream()
        .filter(
            b -> {
              try {
                var l = available(b);
                credentials.inspect(b.owner(), target(b));
                catalogue(b, l);
                return true;
              } catch (CallerFault unavailable) {
                return false;
              }
            })
        .map(Binding::route)
        .toList();
  }

  public boolean permits(
      ToolScopes.Connection connection, String actor, String agent, String session, String name) {
    try {
      var b =
          repository
              .route(connection.project(), connection.provider(), false)
              .orElseThrow(() -> new CallerFault("Hosted route absent"));
      var l = available(b);
      credentials.inspect(b.owner(), target(b));
      return b.route().equals(connection)
          && b.owner().equals(actor)
          && b.definition().agents().contains(agent)
          && definitions.acceptsDynamic(projects.id(b.project()), actor, agent, session)
          && tool(b, l, name) != null;
    } catch (CallerFault unavailable) {
      return false;
    }
  }

  public boolean permits(RelayToolDefinition tool, String actor) {
    try {
      var b =
          repository
              .route(tool.project(), tool.provider(), false)
              .orElseThrow(() -> new CallerFault("Hosted route absent"));
      var l = available(b);
      credentials.inspect(b.owner(), target(b));
      return b.owner().equals(actor) && tool(b, l, tool.name()).equals(tool);
    } catch (CallerFault unavailable) {
      return false;
    }
  }

  public HostedInvocation pin(RelayToolRepository.Intent intent) {
    var b =
        repository
            .route(intent.binding().project(), intent.binding().provider(), true)
            .orElseThrow(() -> new CallerFault("Hosted route changed during admission"));
    var l = available(b);
    var grant = grant(b);
    available(b); // Lock waits must not admit an expired/disconnected worker.
    if (!clock.instant().isBefore(Instant.parse(intent.request().deadline())))
      throw new CallerFault("Hosted invocation deadline ended during admission");
    String agent = intent.agent();
    if (!b.owner().equals(intent.request().account())
        || agent == null
        || !b.definition().agents().contains(agent)
        || !definitions.acceptsDynamic(projects.id(b.project()), b.owner(), agent, null)
        || !tool(b, l, intent.binding().name()).equals(intent.binding()))
      throw new CallerFault("Hosted invocation exceeds current owner/agent/contract authority");
    var m = grant.credential();
    return new HostedInvocation(
        b.binding(),
        b.generation(),
        l.registration().registration(),
        b.owner(),
        b.definition().executor(),
        b.definition().credentialProject(),
        b.definition().connection(),
        grant.connection().revision(),
        m.subject(),
        m.generation(),
        agent);
  }

  public Credential credential(
      String actor, String session, String socket, CredentialRequest request) {
    return work.inTransaction(
        () -> {
          var b = find(request.binding(), true);
          var l = worker(b, actor, session, socket, request.registration());
          var grant = grant(b);
          if (request.purpose() == Purpose.EXECUTION) {
            var stored =
                invocations
                    .get()
                    .find(projects.id(b.project()), b.owner(), request.invocation(), true)
                    .orElseThrow(() -> new CallerFault("Hosted invocation is unavailable"));
            var intent = stored.intent();
            var selected = intent.hosted();
            if (stored.result() != null
                || !clock.instant().isBefore(Instant.parse(intent.request().deadline()))
                || selected == null
                || !selected.binding().equals(b.binding())
                || selected.generation() != b.generation()
                || !selected.registration().equals(request.registration())
                || !selected.owner().equals(b.owner())
                || !selected.executor().equals(actor)
                || !selected.credentialProject().equals(grant.connection().project())
                || !selected.connection().equals(grant.connection().connection())
                || !selected.connectionRevision().equals(grant.connection().revision())
                || !selected.subject().equals(grant.credential().subject())
                || !selected.credentialGeneration().equals(grant.credential().generation())
                || !b.definition().agents().contains(selected.agent())
                || !definitions.acceptsDynamic(
                    projects.id(b.project()), b.owner(), selected.agent(), null)
                || !tool(b, l, intent.binding().name()).equals(intent.binding()))
              throw new CallerFault(
                  "Hosted invocation credential/contract/authority changed or deadline ended");
          }
          worker(b, actor, session, socket, request.registration());
          var header = credentials.header(grant);
          return new Credential(
              b.binding(),
              b.generation(),
              request.registration(),
              request.purpose(),
              request.invocation(),
              grant.connection(),
              grant.credential().subject(),
              grant.credential().generation(),
              header.name(),
              header.value());
        });
  }

  public Reconciliation read(String actor, Read request) {
    trustedExecutor(actor);
    var b = find(request.binding(), false);
    var stored =
        invocations
            .get()
            .find(projects.id(b.project()), b.owner(), request.invocation())
            .orElseThrow(() -> new CallerFault("No retained hosted invocation"));
    var pin = stored.intent().hosted();
    if (pin == null
        || !pin.binding().equals(request.binding())
        || !pin.registration().equals(request.registration())
        || !pin.executor().equals(actor))
      throw new CallerFault("Retained invocation does not belong to this executor fence");
    var outcome = results.get().read(b.project(), b.owner(), request.invocation());
    boolean verified =
        invocations
            .get()
            .find(projects.id(b.project()), b.owner(), request.invocation())
            .filter(r -> r.result() != null)
            .isPresent();
    return new Reconciliation(
        request.binding(),
        request.registration(),
        request.invocation(),
        verified,
        ResultState.valueOf(outcome.state().name()),
        outcome.text());
  }

  private record VerifiedResult(RelayToolRepository.Intent intent, RelayToolCodec.Result result) {}

  public RelayPort.Published publish(String actor, String session, String socket, Publish request) {
    trustedExecutor(actor);
    var publication = request.publication();
    return work.inTransaction(
        () -> {
          var b = find(request.binding(), true);
          io.aeyer.plowshare.protocol.RelayCausation causation;
          Optional<VerifiedResult> completion = Optional.empty();
          if (publication.topic().equals(catalogueTopic(b))) {
            var l = worker(b, actor, session, socket, request.registration());
            grant(b);
            checkedCatalogue(b, publication.text());
            if (!publication.project().equals(b.project())
                || publication.parentTopic() != null
                || publication.parentEventId() != null
                || publication.correlationId() != null)
              throw new CallerFault("Hosted catalogue must be its bound independent publication");
            causation =
                new io.aeyer.plowshare.protocol.RelayCausation(publication.requestId(), null, 0);
          } else {
            // Journalled results may reconcile retired generations. Authentication and the retained
            // original selection, not current discovery grants, are authoritative for this write.
            var result = RelayToolCodec.result(publication.text());
            UUID id = UUID.fromString(result.invocationId());
            var stored =
                invocations
                    .get()
                    .find(projects.id(b.project()), b.owner(), id, true)
                    .orElseThrow(() -> new CallerFault("Result has no retained hosted invocation"));
            var original = stored.intent();
            var pin = original.hosted();
            if (pin == null
                || !pin.binding().equals(request.binding())
                || !pin.executor().equals(actor)
                || !pin.registration().equals(request.registration())
                || !publication.project().equals(original.binding().project())
                || !publication.topic().equals(original.binding().results())
                || !Objects.equals(publication.parentTopic(), original.binding().requests())
                || !Objects.equals(publication.parentEventId(), id.toString())
                || !Objects.equals(publication.correlationId(), id.toString())
                || !publication.requestId().equals(RelayToolCodec.resultRequestId(id).toString())
                || !result.project().equals(original.request().project())
                || !result.provider().equals(original.request().provider())
                || !result.tool().equals(original.request().tool()))
              throw new CallerFault(
                  "Result provenance does not match the original hosted invocation");
            causation = original.causation().next(id.toString(), maxDepth);
            completion = Optional.of(new VerifiedResult(original, result));
          }
          var key = new Relay.TopicKey(projects.id(b.project()), publication.topic());
          relay.registerTopic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
          var pub =
              relay.publish(
                  key,
                  new Relay.Draft(
                      publication.requestId(),
                      RelayPort.publisher(actor),
                      publication.occurredAt(),
                      publication.correlationId(),
                      publication.parentEventId(),
                      new RelayPayload.Text(publication.text()),
                      causation));
          // Settle the existing owning intent in the publication transaction, so a completed
          // hosted call cannot acquire another credential while its owner has not yet polled.
          completion.ifPresent(c -> invocations.get().finish(c.intent(), c.result()));
          return new RelayPort.Published(
              publication.requestId(),
              publication.project(),
              publication.topic(),
              Long.toString(pub.position()),
              pub.publishedAt());
        });
  }

  private Relay.TopicKey requestTopic(
      Binding b, HostedToolScopeRepository.Lease l, String project, String topic, String group) {
    String prefix = "tool." + b.route().provider() + ".";
    if (!project.equals(b.project())
        || !topic.startsWith(prefix)
        || !topic.endsWith(".request")
        || !RelayToolDefinition.group().equals(group))
      throw new CallerFault("Worker may consume only its bound request route and group");
    String name = topic.substring(prefix.length(), topic.length() - ".request".length());
    var t = tool(b, l, name);
    var key = new Relay.TopicKey(projects.id(project), t.requests());
    relay.registerTopic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    return key;
  }

  public RelayPort.Batch consume(
      String actor, String session, String socket, Consume request, RelayReadBudget budget) {
    var query = request.consumption();
    long until =
        System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                query.waitMs() == null ? 0 : query.waitMs());
    while (true) {
      var batch =
          work.inTransaction(
              () -> {
                var b = find(request.binding(), true);
                var l = worker(b, actor, session, socket, request.registration());
                grant(b);
                var key = requestTopic(b, l, query.project(), query.topic(), query.group());
                return ports.consume(key, actor, query, budget);
              });
      if (batch.status() != RelayPort.Status.EMPTY || System.nanoTime() >= until) return batch;
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new CallerFault("Hosted consumption interrupted");
      }
    }
  }

  public RelayPort.Acknowledged acknowledge(
      String actor, String session, String socket, Ack request) {
    return work.inTransaction(
        () -> {
          var b = find(request.binding(), true);
          var l = worker(b, actor, session, socket, request.registration());
          grant(b);
          var query = request.acknowledgement();
          return ports.acknowledge(
              requestTopic(b, l, query.project(), query.topic(), query.group()), actor, query);
        });
  }
}
