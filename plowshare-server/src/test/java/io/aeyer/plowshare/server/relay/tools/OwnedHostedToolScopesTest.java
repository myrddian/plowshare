package io.aeyer.plowshare.server.relay.tools;

import static io.aeyer.plowshare.server.relay.tools.HostedScopeFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.providers.ProviderCredentialGrants;
import io.aeyer.plowshare.server.relay.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OwnedHostedToolScopesTest {
  final HostedScopeFixture f = new HostedScopeFixture();

  RelayToolDefinition tool(Registration r) {
    return ApplicationCatalogue.tool(r.binding());
  }

  private static class ApplicationCatalogue {
    static RelayToolDefinition tool(Binding b) {
      return io.aeyer.plowshare.server.applications.ApplicationServerSettings.catalogue(CATALOGUE)
          .tools()
          .getFirst()
          .bind(
              PROJECT,
              new io.aeyer.plowshare.server.applications.ApplicationServerSettings.Provider(
                  b.route().provider(), EXECUTOR, "network_", 30));
    }
  }

  RelayToolRepository.Intent intent(Registration r) {
    var b = tool(r);
    UUID id = UUID.randomUUID();
    var request =
        new RelayToolCodec.Request(
            RelayToolCodec.VERSION,
            id.toString(),
            PROJECT,
            b.provider(),
            b.name(),
            OWNER,
            "run",
            "call",
            f.clock.instant().plusSeconds(30).toString(),
            Map.of());
    return new RelayToolRepository.Intent(
        id,
        1L,
        RelayPort.hash(id.toString()),
        b,
        request,
        f.clock.instant(),
        new RelayCausation("root", "run", 1),
        "coordinator",
        null);
  }

  RelayToolRepository.Intent retain(Registration r) {
    var i = intent(r);
    var pin = f.service.pin(i);
    var retained =
        new RelayToolRepository.Intent(
            i.id(),
            i.projectId(),
            i.fingerprint(),
            i.binding(),
            i.request(),
            i.occurredAt(),
            i.causation(),
            i.agent(),
            pin);
    when(f.ledger.find(1L, OWNER, i.id(), true))
        .thenReturn(Optional.of(new RelayToolRepository.Stored(retained, null)));
    return retained;
  }

  Credential release(Registration r, UUID invocation) {
    return f.service.credential(
        EXECUTOR,
        "worker-session",
        "socket",
        new CredentialRequest(ID, r.registration(), Purpose.EXECUTION, invocation));
  }

  @Test
  void an_authorized_service_credential_keeps_caller_worker_and_subject_distinct() {
    String subject = "@service/44444444-4444-4444-4444-444444444444";
    var original = f.grant.get();
    var credential = original.credential();
    f.grant.set(
        new ProviderCredentialGrants.Reference(
            original.connection(),
            new ProviderCredentials.Credential(
                credential.connection(),
                credential.project(),
                subject,
                credential.generation(),
                credential.connectionRevision(),
                credential.state(),
                credential.expiresAt(),
                credential.keyId())));
    var definition = HostedScopeFixture.definition();
    // The fixture's credential-access capability represents already-authorized selection.
    // ProviderBoundariesTest separately exercises the real administrator/subject role checks.
    var binding =
        f.service
            .save(
                OWNER,
                new Save(
                    PROJECT,
                    ID,
                    UUID.randomUUID(),
                    null,
                    new Definition(
                        definition.scope(),
                        definition.provider(),
                        definition.prefix(),
                        definition.grants(),
                        definition.agents(),
                        definition.leaseSeconds(),
                        definition.credentialProject(),
                        definition.connection(),
                        subject,
                        EXECUTOR)))
            .binding();
    var registered =
        f.service.register(
            EXECUTOR,
            "worker-session",
            "socket",
            new Register(ID, UUID.randomUUID(), binding.generation()));
    f.catalogue(binding, f.clock.instant());
    var retained = retain(registered);
    assertEquals(OWNER, retained.request().account());
    assertEquals(OWNER, retained.hosted().owner());
    assertEquals(EXECUTOR, retained.hosted().executor());
    assertEquals(subject, retained.hosted().subject());
    assertEquals(subject, release(registered, retained.id()).subject());
    assertNotEquals(subject, retained.request().account());
    assertNotEquals(EXECUTOR, retained.request().account());
  }

  @Test
  void binding_is_owner_bound_cross_client_and_personal_without_worker_membership() {
    var r = f.start();
    when(f.projects.personalOwner(PROJECT)).thenReturn(Optional.of(OWNER));
    assertFalse(f.members.mayWork(PROJECT, EXECUTOR));
    assertEquals(List.of(r.binding().route()), f.service.connections(PROJECT, OWNER));
    assertTrue(f.service.connections(PROJECT, "bob").isEmpty());
    assertTrue(
        f.service.permits(
            r.binding().route(), OWNER, "coordinator", "first-client", "network_read"));
    assertTrue(
        f.service.permits(
            r.binding().route(), OWNER, "coordinator", "second-client", "network_read"));
    assertFalse(
        f.service.permits(
            r.binding().route(), "bob", "coordinator", "second-client", "network_read"));
    assertThrows(CallerFault.class, () -> f.service.status("bob", new Status(PROJECT, ID)));
    assertThrows(
        CallerFault.class,
        () ->
            f.service.register(
                OWNER, "worker-session", "socket", new Register(ID, UUID.randomUUID(), 1)));
  }

  @Test
  void one_live_worker_is_fenced_and_restart_requires_new_registration_and_catalogue() {
    var r = f.start();
    assertThrows(
        CallerFault.class,
        () ->
            f.service.register(
                EXECUTOR, "second", "socket2", new Register(ID, UUID.randomUUID(), 1)));
    var restarted =
        f.service(f.store, UUID.randomUUID(), io.aeyer.plowshare.server.archive.UnitOfWork.NONE);
    assertTrue(restarted.connections(PROJECT, OWNER).isEmpty());
    var fresh =
        restarted.register(
            EXECUTOR, "new-session", "new-socket", new Register(ID, UUID.randomUUID(), 1));
    assertTrue(restarted.connections(PROJECT, OWNER).isEmpty());
    var old = r;
    assertThrows(
        CallerFault.class,
        () ->
            restarted.renew(
                EXECUTOR, "worker-session", "socket", new Worker(ID, old.registration())));
    f.catalogue(fresh.binding(), f.clock.instant());
    assertEquals(1, restarted.connections(PROJECT, OWNER).size());
  }

  @Test
  void credential_release_requires_exact_retained_selection_and_does_not_expose_rotation_secrets() {
    var r = f.start();
    var i = retain(r);
    var value = release(r, i.id());
    assertEquals("private-upstream", value.value());
    assertFalse(value.toString().contains("private-upstream"));
    assertEquals(f.grant.get().credential().generation(), value.credentialGeneration());
    assertThrows(CallerFault.class, () -> release(r, UUID.randomUUID()));
    assertThrows(
        CallerFault.class,
        () ->
            f.service.credential(
                "bob",
                "worker-session",
                "socket",
                new CredentialRequest(ID, r.registration(), Purpose.EXECUTION, i.id())));
    assertThrows(
        CallerFault.class,
        () ->
            f.service.credential(
                EXECUTOR,
                "other-session",
                "other-socket",
                new CredentialRequest(ID, r.registration(), Purpose.EXECUTION, i.id())));
    var prior = f.grant.get();
    var m = prior.credential();
    f.grant.set(
        new ProviderCredentialGrants.Reference(
            prior.connection(),
            new ProviderCredentials.Credential(
                m.connection(),
                m.project(),
                m.subject(),
                UUID.randomUUID(),
                m.connectionRevision(),
                m.state(),
                m.expiresAt(),
                m.keyId())));
    assertThrows(CallerFault.class, () -> release(r, i.id()));
  }

  @Test
  void completed_expired_or_changed_contract_cannot_acquire_an_execution_credential() {
    var r = f.start();
    var i = retain(r);
    when(f.ledger.find(1L, OWNER, i.id(), true))
        .thenReturn(
            Optional.of(
                new RelayToolRepository.Stored(
                    i,
                    new RelayToolCodec.Result(
                        RelayToolCodec.VERSION,
                        i.id().toString(),
                        PROJECT,
                        i.binding().provider(),
                        i.binding().name(),
                        RelayToolCodec.State.COMPLETED,
                        "done"))));
    assertThrows(CallerFault.class, () -> release(r, i.id()));
    when(f.ledger.find(1L, OWNER, i.id(), true))
        .thenReturn(Optional.of(new RelayToolRepository.Stored(i, null)));
    f.head.set(new RelayLogRepository.Head(2, Optional.empty()));
    assertThrows(CallerFault.class, () -> release(r, i.id()));
    f.catalogue(r.binding(), f.clock.instant());
    f.clock.time = f.clock.time.plusSeconds(31);
    assertThrows(CallerFault.class, () -> release(r, i.id()));
  }

  @Test
  void account_membership_agent_and_socket_revocation_remove_tools() {
    var r = f.start();
    when(f.accounts.isEnabled(EXECUTOR)).thenReturn(false);
    assertTrue(f.service.connections(PROJECT, OWNER).isEmpty());
    when(f.accounts.isEnabled(EXECUTOR)).thenReturn(true);
    when(f.members.mayWork(PROJECT, OWNER)).thenReturn(false);
    assertTrue(f.service.connections(PROJECT, OWNER).isEmpty());
    when(f.members.mayWork(PROJECT, OWNER)).thenReturn(true);
    when(f.definitions.acceptsDynamic(any(), any(), any(), nullable(String.class)))
        .thenReturn(false);
    assertFalse(f.service.permits(r.binding().route(), OWNER, "coordinator", null, "network_read"));
    f.live.set(false);
    assertTrue(f.service.connections(PROJECT, OWNER).isEmpty());
  }

  @Test
  void
      disabling_advances_generation_and_withdraws_availability_but_original_result_can_reconcile() {
    var r = f.start();
    var i = retain(r);
    var disabled =
        f.service.change(OWNER, new Change(PROJECT, ID, UUID.randomUUID(), 1, Action.DISABLE));
    assertEquals(2, disabled.binding().generation());
    assertTrue(f.service.connections(PROJECT, OWNER).isEmpty());
    assertThrows(CallerFault.class, () -> release(r, i.id()));
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.VERSION,
            i.id().toString(),
            PROJECT,
            i.binding().provider(),
            i.binding().name(),
            RelayToolCodec.State.COMPLETED,
            "journalled result");
    var body =
        new RelayPort.Publish(
            RelayToolCodec.resultRequestId(i.id()).toString(),
            PROJECT,
            i.binding().results(),
            RelayToolCodec.write(result),
            f.clock.instant(),
            i.id().toString(),
            i.binding().requests(),
            i.id().toString());
    f.service.publish(
        EXECUTOR, "reconnected", "new-socket", new Publish(ID, r.registration(), body));
    verify(f.relay)
        .publish(
            any(),
            argThat(
                d ->
                    d.publisher().equals(RelayPort.publisher(EXECUTOR))
                        && d.causation().equals(i.causation().next(i.id().toString(), 32))));
    assertThrows(
        CallerFault.class,
        () ->
            f.service.publish(
                EXECUTOR, "reconnected", "new-socket", new Publish(ID, UUID.randomUUID(), body)));
  }

  @Test
  void foreign_topics_and_catalogue_roots_refuse_before_broker_or_port_access() {
    var r = f.start();
    var query =
        new RelayPort.Consume(
            PROJECT,
            "unrelated.events",
            "tool-provider",
            UUID.randomUUID().toString(),
            RelayPort.Start.OLDEST_RETAINED,
            1,
            0);
    assertThrows(
        CallerFault.class,
        () ->
            f.service.consume(
                EXECUTOR,
                "worker-session",
                "socket",
                new Consume(ID, r.registration(), query),
                RelayReadBudget.SEGMENTED));
    var body =
        new RelayPort.Publish(
            UUID.randomUUID().toString(),
            "foreign",
            "tool." + r.binding().route().provider() + ".catalog",
            CATALOGUE,
            f.clock.instant(),
            null,
            null,
            null);
    assertThrows(
        CallerFault.class,
        () ->
            f.service.publish(
                EXECUTOR, "worker-session", "socket", new Publish(ID, r.registration(), body)));
    verifyNoInteractions(f.ports);
    verify(f.relay, never()).publish(any(), any());
  }

  @Test
  void dynamic_opt_in_prefix_limits_and_foreign_binding_ids_refuse() {
    when(f.definitions.acceptsDynamic(any(), any(), any(), nullable(String.class)))
        .thenReturn(false);
    assertThrows(CallerFault.class, f::save);
    when(f.definitions.acceptsDynamic(any(), any(), any(), nullable(String.class)))
        .thenReturn(true);
    f.save();
    assertThrows(
        CallerFault.class,
        () ->
            f.service.save(
                OWNER,
                new Save(PROJECT, UUID.randomUUID(), UUID.randomUUID(), null, definition())));
    assertThrows(
        CallerFault.class,
        () ->
            f.service.change(OWNER, new Change(PROJECT, ID, UUID.randomUUID(), 2, Action.DISABLE)));
  }

  @Test
  void retained_worker_read_is_restricted_to_original_fence_and_never_submits() {
    var r = f.start();
    var i = retain(r);
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.VERSION,
            i.id().toString(),
            PROJECT,
            i.binding().provider(),
            i.binding().name(),
            RelayToolCodec.State.COMPLETED,
            "done");
    when(f.ledger.find(1L, OWNER, i.id()))
        .thenReturn(Optional.of(new RelayToolRepository.Stored(i, result)));
    when(f.results.read(PROJECT, OWNER, i.id()))
        .thenReturn(new RelayToolInvocations.Outcome(i.id(), result.state(), result.text()));
    f.service.change(OWNER, new Change(PROJECT, ID, UUID.randomUUID(), 1, Action.DISABLE));
    var read = f.service.read(EXECUTOR, new Read(ID, r.registration(), i.id()));
    assertTrue(read.verified());
    assertEquals("done", read.text());
    assertThrows(
        CallerFault.class, () -> f.service.read(EXECUTOR, new Read(ID, UUID.randomUUID(), i.id())));
    assertThrows(
        CallerFault.class, () -> f.service.read("bob", new Read(ID, r.registration(), i.id())));
    verify(f.ledger, never()).submit(any());
    verifyNoInteractions(f.relay);
  }

  @Test
  void current_credential_subject_and_connection_reference_are_rechecked_for_discovery() {
    var r = f.start();
    var selected =
        f.service.credential(
            EXECUTOR,
            "worker-session",
            "socket",
            new CredentialRequest(ID, r.registration(), Purpose.DISCOVERY, null));
    assertNull(selected.invocation());
    doThrow(new CallerFault("Subject membership revoked"))
        .when(f.credentialAccess)
        .subject(eq(OWNER), any());
    assertThrows(
        CallerFault.class,
        () ->
            f.service.credential(
                EXECUTOR,
                "worker-session",
                "socket",
                new CredentialRequest(ID, r.registration(), Purpose.DISCOVERY, null)));
    assertTrue(f.service.connections(PROJECT, OWNER).isEmpty());
  }

  @Test
  void renewal_preserves_fence_and_cannot_revive_expired_registration() {
    var r = f.start();
    f.clock.time = f.clock.time.plusSeconds(15);
    var renewed =
        f.service.renew(EXECUTOR, "worker-session", "socket", new Worker(ID, r.registration()));
    assertEquals(r.registration(), renewed.registration());
    assertEquals(r.registeredAt(), renewed.registeredAt());
    assertTrue(renewed.expiresAt().isAfter(r.expiresAt()));
    f.clock.time = f.clock.time.plusSeconds(31);
    assertThrows(
        CallerFault.class,
        () ->
            f.service.renew(
                EXECUTOR, "worker-session", "socket", new Worker(ID, r.registration())));
  }
}
