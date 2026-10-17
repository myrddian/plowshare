package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.acting.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.*;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityLifecycleTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SYSTEM",
        "system",
        "GLOBAL",
        "Global",
        "00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000002",
        "00000000-0000-0000-0000-000000000003",
        "00000000-0000-0000-0000-000000000004",
        "00000000-0000-0000-0000-000000000005",
        "00000000-0000-0000-0000-000000000006"
      })
  void builtinsCannotAcquireCredentialsPersonalSpacesOrActingAuthority(String handle) {
    var accounts = mock(AdminStore.class);
    var services = mock(ServiceAccountRepository.class);
    var members = mock(ProjectMembers.class);
    var events = mock(ApplicationEventPublisher.class);
    var service = new ServiceAccounts(services, UnitOfWork.NONE, accounts, members, events);
    assertThrows(CallerFault.class, () -> service.create("admin", handle));
    assertThrows(
        CallerFault.class,
        () ->
            service.issue(
                "admin",
                handle,
                "token",
                List.of(new ServiceAccounts.Scope("research", ProjectRole.VIEWER)),
                1));
    verify(services).lockAdministrators();
    verifyNoMoreInteractions(services);
    verifyNoInteractions(members, events);

    var personal = mock(PersonalSpaceRepository.class);
    var data = mock(DataLayout.class);
    var spaces = new PersonalSpaces(personal, UnitOfWork.NONE, data);
    assertThrows(CallerFault.class, () -> spaces.ensure(handle));
    verifyNoInteractions(personal);

    var tokens =
        new TokenStore(
            Clock.systemUTC(), Duration.ofMinutes(15), Duration.ofDays(1), Duration.ofSeconds(30));
    assertThrows(CallerFault.class, () -> tokens.issuePair(handle, false));
    assertThrows(CallerFault.class, () -> tokens.issuePair(handle, false, "hash"));
    assertThrows(CallerFault.class, () -> tokens.mintTicket(handle, 0L));
    var administration = mock(AccountAdministrationRepository.class);
    var passwords = mock(PasswordHasher.class);
    var admin =
        new ServerAdministration(
            administration, UnitOfWork.NONE, accounts, passwords, tokens, events);
    assertThrows(CallerFault.class, () -> admin.create("admin", handle, true));
    assertThrows(CallerFault.class, () -> admin.reset("admin", handle));
    assertThrows(CallerFault.class, () -> admin.update("admin", handle, true, true));
    verify(administration).lockAdministrators();
    verifyNoMoreInteractions(administration);
    verifyNoInteractions(passwords);

    var jdbc = mock(JdbcTemplate.class);
    var stored = new AdminStore(jdbc);
    assertThrows(CallerFault.class, () -> stored.create(handle, "hash"));
    assertThrows(CallerFault.class, () -> stored.changePassword(handle, "hash"));
    assertThrows(CallerFault.class, () -> stored.changePassword(handle, "hash", "old"));
    assertThrows(CallerFault.class, () -> stored.finishSetup("admin", "old", handle, "hash"));
    verifyNoInteractions(jdbc);
    var properties = new AuthProperties();
    properties.setAdminHandle(handle);
    var seed =
        new AdminSeed(
            accounts, passwords, properties, mock(org.springframework.core.env.Environment.class));
    assertThrows(CallerFault.class, () -> seed.run(null));
    verify(accounts, never()).byHandle(handle);

    assertThrows(IllegalArgumentException.class, () -> new ActingTarget.Personal(handle));
    assertThrows(IllegalArgumentException.class, () -> new RequestAuthority.Direct(handle));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActingTokens.Eligibility(
                "app", "grant", Set.of(handle), Set.of(), UUID.randomUUID()));
    // A grant label is not an account selector and retains its existing vocabulary.
    assertDoesNotThrow(
        () -> new ActingPolicySource.Grant("app", handle, ProjectRole.VIEWER, true, Set.of()));
  }

  @Test
  void unknownAndUnscopedCompatibilityReadsNeverBecomeGlobalOrPersonal() {
    var jdbc = mock(JdbcTemplate.class);
    var identities = new JdbcIdentityRepository(jdbc);
    assertThrows(CallerFault.class, () -> identities.legacyHome(Home.global()));
    assertThrows(IllegalArgumentException.class, () -> identities.project(" "));
    verifyNoInteractions(jdbc);
    assertThrows(CallerFault.class, () -> identities.project("unknown"));
    assertThrows(CallerFault.class, () -> identities.scope(BuiltInIdentities.GLOBAL_KNOWLEDGE));
    assertThrows(IllegalStateException.class, identities::verifyBuiltIns);
    assertThrows(IllegalArgumentException.class, () -> new AuthorityIds.ScopeId(new UUID(0, 0)));
  }

  @Test
  void startupRechecksAndNeverRepairsMissingBuiltins() {
    var repository = mock(IdentityRepository.class);
    var bootstrap = new IdentityBootstrap(repository);
    bootstrap.afterSingletonsInstantiated();
    doThrow(new IllegalStateException("missing identity")).when(repository).verifyBuiltIns();
    assertThrows(IllegalStateException.class, bootstrap::afterSingletonsInstantiated);
    verify(repository, times(2)).verifyBuiltIns();
    verifyNoMoreInteractions(repository);
  }

  @Test
  void aReservedIdentityNeverGrantsProjectPermission() {
    var members = mock(ProjectMembers.class);
    var permissions = new EffectiveProjectPermissions(members);
    for (String account :
        List.of("SYSTEM", "GLOBAL", BuiltInIdentities.SYSTEM.value().toString())) {
      for (var action : ScopedPermissions.Action.values())
        assertFalse(
            permissions.allows(
                new ScopedPermissions.Principal(account),
                new ScopedPermissions.ProjectTarget("global"),
                action));
    }
    verify(members, never()).isServerAdmin(any());
  }
}
