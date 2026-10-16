package io.aeyer.plowshare.server.relay.tools;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.protocol.ProviderCredentials;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.providers.*;
import io.aeyer.plowshare.server.relay.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Behavior fixture only. PostgreSQL assertions use the specialist stores in a tagged test. */
final class HostedScopeFixture {
  static final String OWNER = "alice",
      PROJECT = "fixture",
      EXECUTOR = "@service/11111111-1111-1111-1111-111111111111";
  static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222"),
      CONNECTION = UUID.fromString("33333333-3333-3333-3333-333333333333");
  static final String CATALOGUE =
      "{\"version\":\"plowshare-tool-catalog/1\",\"tools\":[{\"name\":\"network_read\",\"description\":\"Read scope\",\"parameters\":[],\"timeoutSeconds\":30}]}";

  static Definition definition() {
    return new Definition(
        "scans",
        "scanner",
        "network_",
        List.of("*"),
        List.of("coordinator"),
        30,
        "configuration",
        CONNECTION,
        null,
        EXECUTOR);
  }

  static class Time extends Clock {
    Instant time = Instant.parse("2026-10-13T00:00:00Z");

    public Instant instant() {
      return time;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }
  }

  static class Memory implements HostedToolScopeRepository {
    final Map<UUID, Binding> bindings = new HashMap<>();
    final Map<UUID, Lease> leases = new HashMap<>();
    final Set<UUID> requests = new HashSet<>();
    final Map<UUID, Receipt> receipts = new HashMap<>();

    public void lockProject(String p) {}

    public List<Binding> list(String p, String a) {
      return bindings.values().stream()
          .filter(b -> b.project().equals(p) && (a == null || b.owner().equals(a)))
          .toList();
    }

    public Optional<Binding> find(UUID id, boolean l) {
      return Optional.ofNullable(bindings.get(id));
    }

    public Optional<Binding> route(String p, String r, boolean l) {
      return bindings.values().stream()
          .filter(b -> b.project().equals(p) && b.route().provider().equals(r))
          .findFirst();
    }

    public void save(Binding b, Long expected) {
      var old = bindings.get(b.binding());
      if (!Objects.equals(old == null ? null : old.generation(), expected))
        throw new io.aeyer.plowshare.server.faults.CallerFault("Stale generation");
      bindings.put(b.binding(), b);
    }

    public Optional<Lease> lease(UUID id) {
      return Optional.ofNullable(leases.get(id));
    }

    public void lease(Lease l) {
      leases.put(l.registration().binding().binding(), l);
    }

    public void clearLease(UUID id) {
      leases.remove(id);
    }

    public void reserve(UUID id, UUID b, String a, String o) {
      if (!requests.add(id))
        throw new io.aeyer.plowshare.server.faults.CallerFault("Committed UUID");
    }

    public void receipt(String a, Receipt r) {
      receipts.put(r.requestId(), r);
    }

    public Optional<Receipt> receipt(String a, String p, UUID id) {
      return Optional.ofNullable(receipts.get(id))
          .filter(r -> r.binding().owner().equals(a) && r.binding().project().equals(p));
    }
  }

  final Memory store = new Memory();
  final Time clock = new Time();
  final AtomicBoolean live = new AtomicBoolean(true);
  final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final AdminStore accounts = mock(AdminStore.class);
  final ProviderCredentialAccess credentialAccess = mock(ProviderCredentialAccess.class);
  final ProviderCredentialGrants credentials = mock(ProviderCredentialGrants.class);
  final ToolGrants definitions = mock(ToolGrants.class);
  final Relay relay = mock(Relay.class);
  final RelayLogRepository logs = mock(RelayLogRepository.class);
  final RelayPortRepository ports = mock(RelayPortRepository.class);
  final RelayToolInvocations results = mock(RelayToolInvocations.class);
  final RelayToolRepository ledger = mock(RelayToolRepository.class);
  final AtomicReference<RelayLogRepository.Head> head =
      new AtomicReference<>(new RelayLogRepository.Head(0, Optional.empty()));
  final AtomicReference<ProviderCredentialGrants.Reference> grant = new AtomicReference<>();
  final UUID server = UUID.randomUUID();
  OwnedHostedToolScopes service;

  HostedScopeFixture() {
    when(projects.id(PROJECT)).thenReturn(1L);
    when(projects.personalOwner(anyString())).thenReturn(Optional.empty());
    when(accounts.isEnabled(anyString())).thenReturn(true);
    when(members.mayWork(PROJECT, OWNER)).thenReturn(true);
    when(credentialAccess.subject(anyString(), any()))
        .thenAnswer(
            i -> {
              ProviderCredentials.Target t = i.getArgument(1);
              return t.subject() == null ? i.getArgument(0) : t.subject();
            });
    when(definitions.acceptsDynamic(any(), any(), any(), nullable(String.class))).thenReturn(true);
    var connection =
        new ProviderCredentials.Connection(
            CONNECTION,
            "configuration",
            UUID.randomUUID(),
            true,
            false,
            new ProviderCredentials.Definition(
                "Fixture",
                "https://provider.example/mcp",
                ProviderCredentials.Authentication.API_KEY,
                "X-API-Key",
                null));
    grant.set(
        new ProviderCredentialGrants.Reference(
            connection,
            new ProviderCredentials.Credential(
                CONNECTION,
                "configuration",
                OWNER,
                UUID.randomUUID(),
                connection.revision(),
                ProviderCredentials.State.READY,
                null,
                "fixture-key")));
    when(credentials.connection(anyString(), any())).thenAnswer(i -> grant.get().connection());
    when(credentials.require(anyString(), any())).thenAnswer(i -> grant.get());
    when(credentials.inspect(anyString(), any())).thenAnswer(i -> grant.get());
    when(credentials.header(any()))
        .thenReturn(new ProviderCredentialGrants.Header("X-API-Key", "private-upstream"));
    when(logs.latest(any())).thenAnswer(i -> head.get());
    when(relay.publish(any(), any()))
        .thenAnswer(
            i -> new Relay.Publication(i.getArgument(0), 1L, clock.instant(), i.getArgument(1)));
    service = service(store, server, UnitOfWork.NONE);
  }

  OwnedHostedToolScopes service(
      HostedToolScopeRepository store, UUID incarnation, UnitOfWork work) {
    return new OwnedHostedToolScopes(
        store,
        credentialAccess,
        credentials,
        projects,
        members,
        accounts,
        id -> Optional.empty(),
        definitions,
        ToolScopeConnections.NONE,
        (a, s, c) -> live.get(),
        Set.of(EXECUTOR),
        () -> Set.of("memory_read"),
        relay,
        logs,
        ports,
        () -> ledger,
        () -> results,
        work,
        clock,
        incarnation,
        32);
  }

  Binding save() {
    return service
        .save(OWNER, new Save(PROJECT, ID, UUID.randomUUID(), null, definition()))
        .binding();
  }

  Registration start() {
    var b = save();
    var r =
        service.register(
            EXECUTOR,
            "worker-session",
            "socket",
            new Register(ID, UUID.randomUUID(), b.generation()));
    catalogue(b, clock.instant());
    return r;
  }

  void catalogue(Binding b, Instant time) {
    catalogue(b, time, CATALOGUE);
  }

  void catalogue(Binding b, Instant time, String catalogue) {
    head.set(
        new RelayLogRepository.Head(
            head.get().position() + 1,
            Optional.of(
                new Relay.Publication(
                    new Relay.TopicKey(1L, "tool." + b.route().provider() + ".catalog"),
                    head.get().position() + 1,
                    time,
                    new Relay.Draft(
                        UUID.randomUUID().toString(),
                        RelayPort.publisher(EXECUTOR),
                        time,
                        null,
                        null,
                        new RelayPayload.Text(catalogue))))));
  }
}
